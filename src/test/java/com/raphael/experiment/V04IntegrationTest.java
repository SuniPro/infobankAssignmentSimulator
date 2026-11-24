package com.raphael.experiment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.core.ResourceController.ResourceEvent;
import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.domain.Tenant;
import com.raphael.metrics.SimulationResult;
import com.raphael.scenario.MessageScenarios;
import com.raphael.scenario.Scenario;
import com.raphael.scheduling.budget.ServiceBudgetConfig;
import com.raphael.scheduling.sharding.ShardingConfig;
import com.raphael.support.Runs;
import com.raphael.support.TraceRecorder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** v0.4 엔진 수준 검증: 예산, 클래스 보호, 샤드, 장애, 보존, 일시적 선택 불가. */
class V04IntegrationTest {

  private static final ServiceBudgetConfig NO_TENANT_LIMIT =
      new ServiceBudgetConfig(3, 7, 0, 0, 0, 0);
  private static final ShardingConfig SHARDS = new ShardingConfig(4, 2, 42, List.of());

  /** 테넌트 A: OTP·광고 각 {@code n} 건, 테넌트 B: 광고 {@code n} 건, 모두 t=0, 100 TPS. */
  private static Scenario mixed(int n) {
    List<Message> messages = new ArrayList<>();
    long id = 0;
    for (int i = 0; i < n; i++) {
      messages.add(new Message(id++, "A", MessageType.OTP, 0, 10_000_000_000L, 1));
      messages.add(new Message(id++, "A", MessageType.ADVERTISEMENT, 0, 10_000_000_000L, 1));
      messages.add(new Message(id++, "B", MessageType.ADVERTISEMENT, 0, 10_000_000_000L, 1));
    }
    return new Scenario("M", "mixed", List.of(new Tenant("A", 1), new Tenant("B", 1)),
        messages, 100, 100_000, Optional.empty());
  }

  private static long countIn(TraceRecorder trace, String tenant, MessageType type, long until) {
    return trace.dispatches.stream().filter(s -> s.time() < until
        && (tenant == null || s.message().tenantId().equals(tenant))
        && (type == null || s.message().type() == type)).count();
  }

  @Test
  void classReservationSplitsSlotsByWeightWhileBothClassesHaveWork() {
    TraceRecorder trace = Runs.trace(mixed(2_000), ArchitectureArms.e3(1, NO_TENANT_LIMIT));
    List<MessageType> first = trace.dispatches.stream().limit(1_000)
        .map(s -> s.message().type()).toList();
    assertEquals(300, first.stream().filter(t -> t == MessageType.OTP).count());
    // 엄격한 OTP 우선이 아니다: 첫 10 슬롯에 광고가 7 개.
    assertEquals(7, first.subList(0, 10).stream()
        .filter(t -> t == MessageType.ADVERTISEMENT).count());
  }

  @Test
  void unusedReservationIsBorrowedSoSingleClassUsesFullCapacity() {
    List<Message> otpOnly = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      otpOnly.add(new Message(i, i % 2 == 0 ? "A" : "B", MessageType.OTP, 0, 10_000_000_000L, 1));
    }
    Scenario scenario = new Scenario("O", "otp only", List.of(new Tenant("A", 1),
        new Tenant("B", 1)), otpOnly, 100, 0, Optional.empty());
    assertArrayEquals(Runs.trace(scenario, ExperimentConfig.fifo()).dispatchTimes(),
        Runs.trace(scenario, ArchitectureArms.e3(1, NO_TENANT_LIMIT)).dispatchTimes());
  }

  @Test
  void classHardLimitIsNotBorrowable() {
    List<Message> otpOnly = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      otpOnly.add(new Message(i, "A", MessageType.OTP, 0, 10_000_000_000L, 1));
    }
    Scenario scenario = new Scenario("L", "otp limited", List.of(new Tenant("A", 1)), otpOnly,
        100, 0, Optional.empty());
    ServiceBudgetConfig limited = new ServiceBudgetConfig(3, 7, 1_000, 0, 0, 0); // OTP ≤ 10/s
    TraceRecorder trace = Runs.trace(scenario, ArchitectureArms.e3(1, limited));
    assertEquals(100, trace.dispatches.size());
    // 버킷: 10/s, burst max(1, 10/10) = 1 → 5 s 동안 최대 1 + 50 건.
    assertTrue(countIn(trace, null, null, 5_000_000) <= 51);
    assertTrue(trace.dispatchTimes()[99] >= 9_900_000);
  }

  @Test
  void tenantBudgetIsSharedAcrossClassQueuesAndShards() {
    // A 의 예산 = 100 TPS 의 20% = 20/s, burst 5 → 10 s 안에 최대 5 + 200 = 205 건 (OTP + 광고 합계).
    ServiceBudgetConfig budget = new ServiceBudgetConfig(3, 7, 0, 0, 2_000, 5);
    for (ExperimentConfig arm : List.of(ArchitectureArms.e3(1, budget),
        ArchitectureArms.e4(1, budget, SHARDS))) {
      ExperimentRunner.Run run = new ExperimentRunner(0, 1).runOnce(mixed(500), arm,
          com.raphael.core.SimulationObserver.NONE);
      TraceRecorder trace = Runs.trace(mixed(500), arm);
      long aIn10s = countIn(trace, "A", null, 10_000_000);
      assertTrue(aIn10s <= 205, arm.experimentId() + " A dispatched " + aIn10s);
      assertTrue(aIn10s >= 190, arm.experimentId() + " A dispatched " + aIn10s);
      assertEquals(0, run.result().serviceBudgetViolations());
      long[] times = trace.dispatchTimes();
      for (int k = 1; k < times.length; k++) {
        assertTrue(times[k] > times[k - 1], "same-timestamp dispatch");
      }
    }
  }

  @Test
  void shardSubsetLimitsASingleTenantToItsShardCapacity() {
    List<Message> solo = new ArrayList<>();
    for (int i = 0; i < 400; i++) {
      solo.add(new Message(i, "A", MessageType.ADVERTISEMENT, 0, 10_000_000_000L, 1));
    }
    Scenario scenario = new Scenario("1", "solo", List.of(new Tenant("A", 1)), solo, 100, 0,
        Optional.empty());
    long[] times = Runs.trace(scenario, ArchitectureArms.e4(1, NO_TENANT_LIMIT, SHARDS))
        .dispatchTimes();
    // 4 샤드 중 2 개 × 25/s = 50/s → 400 건에 약 8 s (전역 100/s 라면 4 s).
    assertTrue(times[399] >= 7_900_000, "last dispatch " + times[399]);
  }

  @Test
  void failureAndRecoveryConserveMessagesAndRespectCapacity() {
    Scenario f1 = MessageScenarios.shardFailureWorkload(3_000, 50);
    ShardingConfig failing = SHARDS.withFailures(List.of(
        new ResourceEvent(2_000_000, 1, true), new ResourceEvent(6_000_000, 1, false)));
    for (ExperimentConfig arm : List.of(ArchitectureArms.e4(1, ServiceBudgetConfig.defaults(),
        failing), ArchitectureArms.e5(1, ServiceBudgetConfig.defaults(), failing, 5_000_000))) {
      SimulationResult r = Runs.run(f1, arm).result();   // 실행기가 보존·용량·예산 위반을 검증
      assertTrue(r.isConserved());
      assertEquals(4_000_000, r.schedulerDiagnostics().get("shard.1.downtimeMicros"));
      assertEquals(0, r.schedulerDiagnostics().get("shardCapacityViolations"));
      SimulationResult healthy = Runs.run(f1, ArchitectureArms.e4(1,
          ServiceBudgetConfig.defaults(), SHARDS)).result();
      BlastRadius blast = BlastRadius.compare("x", healthy, r, java.util.Set.of("T00"));
      assertEquals(15, blast.nonAggressorTenants());
    }
  }

  @Test
  void e0ToE5AreDeterministicAndConserveOnSmallWorkloads() {
    List<ExperimentConfig> arms = ArchitectureArms.e0ToE5(10, ServiceBudgetConfig.defaults(),
        SHARDS, 5_000_000);
    ExperimentRunner runner = new ExperimentRunner(0, 2);  // 두 번 실행해 결과 동일성 검증
    for (Scenario scenario : List.of(MessageScenarios.intraTenantHeadOfLine(500, 1_000_000, 5),
        MessageScenarios.prolongedWaiting(500, 300, 5), mixed(200))) {
      for (ExperimentResult result : runner.runAll(scenario, arms)) {
        SimulationResult r = result.result();
        assertTrue(r.isConserved(), scenario.name() + "/" + result.config().experimentId());
        assertEquals(scenario.messages().size(), new HashSet<>(Runs.trace(scenario,
            result.config()).dispatchedIds()).size());
        assertEquals(0, r.providerCapacityViolations());
      }
    }
  }
}
