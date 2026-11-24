package com.raphael.experiment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.domain.Tenant;
import com.raphael.metrics.GroupMetrics;
import com.raphael.metrics.ObservationWindow;
import com.raphael.metrics.SimulationResult;
import com.raphael.metrics.WindowedFairness;
import com.raphael.scenario.MessageScenarios;
import com.raphael.scenario.PaperScenarios;
import com.raphael.scenario.Scenario;
import com.raphael.scenario.ScenarioGenerator;
import com.raphael.support.Runs;
import com.raphael.support.TraceRecorder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 엔진 수준 WDEDF 검증: 보존, 용량, 작업 보존, 결정론, 경계 조건, 논문 Example 1 의 시간표.
 * 정확성 검증이며 WDEDF 가 우월하다는 가정은 하지 않는다.
 */
class WdedfIntegrationTest {

  private static final List<ExperimentConfig> WDEDF_ARMS = List.of(
      StandardArms.e2WdedfMessage(1), StandardArms.d1WdedfPaper(1),
      StandardArms.d2WdedfNoBorrowing(1), StandardArms.d4WdedfRetainDebt(1),
      StandardArms.d6WdedfClass(1), StandardArms.e2WdedfMessage(3));

  private static Scenario randomScenario(long seed) {
    Random random = new Random(seed);
    List<Tenant> tenants = List.of(new Tenant("A", 1 + random.nextInt(4)),
        new Tenant("B", 1 + random.nextInt(4)), new Tenant("C", 1 + random.nextInt(4)));
    List<Message> messages = new ArrayList<>();
    for (int i = 0; i < 3_000; i++) {
      long arrival = random.nextInt(20) == 0 ? 0 : random.nextInt(30_000_000);
      messages.add(new Message(i, tenants.get(random.nextInt(3)).id(),
          random.nextBoolean() ? MessageType.OTP : MessageType.ADVERTISEMENT,
          arrival, arrival + random.nextInt(5_000_000), 1 + random.nextInt(5)));
    }
    return new Scenario("R" + seed, "random", tenants, messages, 1 + random.nextInt(200),
        random.nextInt(300_000), Optional.empty());
  }

  @Test
  void everyWdedfVariantConservesRespectsCapacityAndIsWorkConserving() {
    for (long seed = 1; seed <= 4; seed++) {
      Scenario scenario = randomScenario(seed);
      long[] fifoTimes = Runs.trace(scenario, ExperimentConfig.fifo()).dispatchTimes();
      for (ExperimentConfig arm : WDEDF_ARMS) {
        TraceRecorder trace = Runs.trace(scenario, arm);
        Set<Long> ids = new HashSet<>(trace.dispatchedIds());
        assertEquals(scenario.messages().size(), ids.size(), "duplicate or lost dispatch");
        assertEquals(scenario.messages().size(), trace.deliveries.size());
        // 같은 provider 에서 작업 보존 정책들의 발송 시각 집합은 같다 → 불필요한 유휴 없음.
        assertArrayEquals(fifoTimes, trace.dispatchTimes(), arm.experimentId());
        long[] t = trace.dispatchTimes();
        for (int k = 1; k < t.length; k++) {
          assertTrue(t[k] > t[k - 1], "same-timestamp dispatch");
        }
        for (int k = 0; k + scenario.providerCapacity() < t.length; k++) {
          assertTrue(t[k + scenario.providerCapacity()] - t[k] >= 1_000_000);
        }
      }
    }
  }

  @Test
  void wdedfRunsAreDeterministicAcrossRepetitions() {
    Scenario scenario = randomScenario(9);
    ExperimentRunner runner = new ExperimentRunner(1, 3);
    for (ExperimentConfig arm : WDEDF_ARMS) {
      // run() 은 반복 결과가 하나라도 다르면 예외를 던진다.
      ExperimentResult result = runner.run(scenario, arm);
      assertEquals(result.result(), runner.run(scenario, arm).result());
      assertEquals(Runs.trace(scenario, arm).dispatchedIds(),
          Runs.trace(scenario, arm).dispatchedIds());
    }
  }

  @Test
  void paperExampleOneReproducesTableTwoCompletionTimes() {
    Scenario p1 = PaperScenarios.exampleOne();
    ExperimentConfig dwrr = ExperimentConfig.dwrr(PaperScenarios.dwrrBaseQuantum(p1, 1_000));
    ExperimentConfig wdedf = ExperimentConfig.wdedf("P", "paper", "WDEDF-PAPER",
        new ExperimentConfig.WdedfArm(ExperimentConfig.DepartureMode.PAPER, 0,
            com.raphael.scheduling.wdedf.IdleDebtPolicy.PAPER_RESET,
            com.raphael.scheduling.QueueLayout.Topology.TENANT, 1,
            java.util.OptionalLong.of(1_000)));
    // 서비스 시작 t = t_A + 162. 완료 = dispatch + L/C (COST_RATE 배달 시각).
    assertEquals(List.of(0L, 162L, 202L, 242L), Runs.trace(p1, dwrr).dispatches.stream()
        .map(TraceRecorder.Step::time).toList());
    assertEquals(List.of("C", "A", "A", "B"), Runs.trace(p1, dwrr).dispatchedTenants());
    TraceRecorder w = Runs.trace(p1, wdedf);
    assertEquals(List.of("C", "A", "B", "A"), w.dispatchedTenants());
    // Table 2 WDEDF 열: A1 t+40, B1 t+90, A2 t+130 (t = 162).
    assertEquals(List.of(162L, 202L, 252L, 292L),
        w.deliveries.stream().map(TraceRecorder.Step::time).toList());
    SimulationResult r = Runs.run(p1, wdedf).result();
    // A1 은 T̂_D = 160 보다 늦은 162 에 dispatch → 논문 출발 위반 1 건 (명세 A5).
    assertEquals(1, r.tenantGroups().get("A").paperDepartureViolations());
    assertEquals(0, r.tenantGroups().get("B").paperDepartureViolations());
    assertEquals(1, Runs.run(p1, dwrr).result().tenantGroups().get("B")
        .paperDepartureViolations());
  }

  @Test
  void businessDeadlineBoundaryAndInfeasibleMessagesAreReportedNotDropped() {
    // 배달 지연 100ms. deadline = 도착 + 100ms 는 즉시 dispatch 되면 정확히 deadline 에 배달 → 성공.
    // deadline = 도착 + 50ms 는 지연보다 짧아 실행 불가능 → 배달되지만 miss 로 집계.
    List<Message> messages = List.of(
        new Message(0, "A", MessageType.OTP, 0, 100_000, 1),
        new Message(1, "B", MessageType.OTP, 5_000_000, 5_050_000, 1));
    Scenario scenario = new Scenario("B", "boundary",
        List.of(new Tenant("A", 1), new Tenant("B", 1)), messages, 10, 100_000,
        Optional.empty());
    for (ExperimentConfig arm : WDEDF_ARMS) {
      SimulationResult r = Runs.run(scenario, arm).result();
      assertEquals(0, r.tenant("A").deadlineMisses());
      assertEquals(1, r.tenant("B").deadlineMisses());
      assertEquals(1, r.tenant("B").delivered());
      GroupMetrics otp = r.messageType("OTP").orElseThrow();
      assertEquals(50_000, otp.lateness().max());
      assertEquals(-50_000, otp.slackAtDispatch().min());
    }
  }

  @Test
  void wdedfPreservesWeightedFairnessForContinuouslyBackloggedTenants() {
    ObservationWindow window = new ObservationWindow(5_000_000, 60_000_000);
    List<Tenant> tenants = List.of(new Tenant("T1", 1), new Tenant("T2", 2), new Tenant("T4", 4));
    for (Map<String, Integer> costs : List.of(Map.<String, Integer>of(),
        Map.of("T1", 2, "T2", 3, "T4", 1))) {
      Scenario s4 = ScenarioGenerator.weightedFairness("S4", tenants, costs, 40_000, 450, window);
      for (ExperimentConfig arm : List.of(StandardArms.e2WdedfMessage(1),
          StandardArms.d1WdedfPaper(1), StandardArms.d2WdedfNoBorrowing(1))) {
        WindowedFairness f = Runs.run(s4, arm).result().fairness().orElseThrow();
        assertTrue(f.allTenantsBacklogged());
        assertTrue(f.jainIndexByCost().getAsDouble() > 0.9999, arm.experimentId() + " " + f);
        // 단기 구간에서도 비용 기준 공정성은 유지된다 (라운드 7 cost ≪ 1 초 = 450 dispatch).
        assertTrue(f.shortWindowJainMinByCost().getAsDouble() > 0.999);
      }
    }
  }

  @Test
  void singleQueueTerminatesEvenWhenEveryCostExceedsTheQuantum() {
    List<Message> messages = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      messages.add(new Message(i, "A", MessageType.ADVERTISEMENT, 0, 10_000_000_000L, 1_000));
    }
    Scenario scenario = new Scenario("L", "large costs", List.of(new Tenant("A", 1)),
        messages, 100, 0, Optional.empty());
    for (ExperimentConfig arm : WDEDF_ARMS) {
      SimulationResult r = Runs.run(scenario, arm).result();
      assertEquals(50, r.totalDelivered());
      // MESSAGE_RATE: cost 와 무관하게 100 msg/s → 50 건은 0.49 s 에 끝난다 (빈 라운드는 시간 0).
      assertEquals(490_000, r.virtualElapsed());
      assertTrue(r.schedulerDiagnostics().get("skippedRounds") > 0);
    }
  }

  @Test
  void intraTenantHeadOfLineBlockingIsNotBypassedByTenantQueueWdedf() {
    Scenario s5 = MessageScenarios.intraTenantHeadOfLine(5_000, 1_000_000, 10);
    // 테넌트 큐 WDEDF 는 head 만 비교하므로 A 의 OTP 는 A 의 광고 뒤에서 기다린다.
    GroupMetrics tenantQueues = Runs.run(s5, StandardArms.e2WdedfMessage(1)).result()
        .tenantTypeGroups().get("A/OTP");
    GroupMetrics classQueues = Runs.run(s5, StandardArms.d6WdedfClass(1)).result()
        .tenantTypeGroups().get("A/OTP");
    assertTrue(tenantQueues.queueLatency().p50() > 10_000_000);
    assertEquals(0, classQueues.queueLatency().max());
  }
}
