package com.raphael.experiment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.domain.Tenant;
import com.raphael.metrics.ObservationWindow;
import com.raphael.metrics.SimulationResult;
import com.raphael.metrics.WindowedFairness;
import com.raphael.scenario.Scenario;
import com.raphael.scenario.ScenarioGenerator;
import com.raphael.support.Runs;
import com.raphael.support.TraceRecorder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExperimentIntegrationTest {

  private static final List<ExperimentConfig> ARMS =
      List.of(ExperimentConfig.fifo(), ExperimentConfig.dwrr(1));

  /** 여러 테넌트·가중치·비용·도착 시각이 섞인 재현 가능한 무작위 시나리오. */
  private static Scenario randomScenario(long seed) {
    Random random = new Random(seed);
    List<Tenant> tenants = List.of(
        new Tenant("A", 1 + random.nextInt(4)), new Tenant("B", 1 + random.nextInt(4)),
        new Tenant("C", 1 + random.nextInt(4)));
    List<Message> messages = new ArrayList<>();
    for (int i = 0; i < 3_000; i++) {
      long arrival = random.nextInt(20) == 0 ? 0 : random.nextInt(30_000_000);
      messages.add(new Message(i, tenants.get(random.nextInt(3)).id(), MessageType.OTP,
          arrival, arrival + random.nextInt(5_000_000), 1 + random.nextInt(5)));
    }
    return new Scenario("R" + seed, "random", tenants, messages, 1 + random.nextInt(200),
        random.nextInt(300_000), Optional.empty());
  }

  @Test
  void s1FifoServesOtpLastWhileDwrrServesItAtTheFirstSlotAfterArrival() {
    Scenario s1 = ScenarioGenerator.basicCorrectness();
    TraceRecorder fifo = Runs.trace(s1, ExperimentConfig.fifo());
    TraceRecorder dwrr = Runs.trace(s1, ExperimentConfig.dwrr(1));

    assertEquals(101, fifo.dispatches.size());
    assertEquals("B", fifo.dispatchedTenants().get(100));
    assertEquals(10_000_000, fifo.dispatches.get(100).time());

    // DWRR: 10 TPS 의 11번째 슬롯(정확히 1s)이 OTP 도착 시각과 같고, 도착이 발송보다 먼저 처리된다.
    assertEquals("B", dwrr.dispatchedTenants().get(10));
    assertEquals(1_000_000, dwrr.dispatches.get(10).time());
    assertEquals(List.of("A"), dwrr.dispatchedTenants().subList(0, 10).stream().distinct()
        .toList());
    assertEquals(List.of("A"), dwrr.dispatchedTenants().subList(11, 101).stream().distinct()
        .toList());
  }

  @Test
  void everyArmConservesMessagesAndDispatchesEachExactlyOnce() {
    for (long seed = 1; seed <= 5; seed++) {
      Scenario scenario = randomScenario(seed);
      for (ExperimentConfig arm : ARMS) {
        TraceRecorder trace = Runs.trace(scenario, arm);
        Set<Long> dispatched = new HashSet<>(trace.dispatchedIds());
        Set<Long> delivered = new HashSet<>();
        trace.deliveries.forEach(step -> delivered.add(step.message().id()));
        assertEquals(scenario.messages().size(), trace.dispatches.size());
        assertEquals(scenario.messages().size(), dispatched.size(), "duplicate dispatch");
        assertEquals(dispatched, delivered);
      }
    }
  }

  @Test
  void everyArmRespectsProviderCapacityInEverySlidingSecond() {
    for (long seed = 1; seed <= 5; seed++) {
      Scenario scenario = randomScenario(seed);
      int tps = scenario.providerCapacity();
      for (ExperimentConfig arm : ARMS) {
        long[] times = Runs.trace(scenario, arm).dispatchTimes();
        for (int k = 0; k + tps < times.length; k++) {
          assertTrue(times[k + tps] - times[k] >= 1_000_000,
              "more than " + tps + " dispatches within one second at index " + k);
        }
        for (int k = 1; k < times.length; k++) {
          assertTrue(times[k] > times[k - 1], "same-timestamp dispatch");
        }
      }
    }
  }

  @Test
  void dwrrIntroducesNoIdleTimeComparedWithFifo() {
    // provider 가 메시지 종류와 무관하게 슬롯을 소비하므로, 작업 보존적인 두 정책의 발송 시각
    // 집합은 동일해야 한다. DWRR 이 불필요하게 쉬면 이 등식이 깨진다.
    for (long seed = 1; seed <= 5; seed++) {
      Scenario scenario = randomScenario(seed);
      long[] fifo = Runs.trace(scenario, ExperimentConfig.fifo()).dispatchTimes();
      long[] dwrr = Runs.trace(scenario, ExperimentConfig.dwrr(1)).dispatchTimes();
      assertArrayEquals(fifo, dwrr);
    }
  }

  @Test
  void dwrrDiffersFromFifoOnlyInOrderNotInTiming() {
    Scenario scenario = randomScenario(99);
    SimulationResult fifo = Runs.run(scenario, ExperimentConfig.fifo()).result();
    SimulationResult dwrr = Runs.run(scenario, ExperimentConfig.dwrr(1)).result();
    assertEquals(fifo.schedulerSelections(), dwrr.schedulerSelections());
    assertEquals(fifo.totalDelivered(), dwrr.totalDelivered());
  }

  @Test
  void singleActiveTenantUnderDwrrUsesFullProviderCapacity() {
    Scenario campaignOnly = ScenarioGenerator.campaignWithSingleOtp(
        "solo", "single tenant", 5_000, 450, 100_000_000, 1, 0);
    long[] fifo = Runs.trace(campaignOnly, ExperimentConfig.fifo()).dispatchTimes();
    long[] dwrr = Runs.trace(campaignOnly, ExperimentConfig.dwrr(1)).dispatchTimes();
    assertArrayEquals(fifo, dwrr);
    // 5,000 건을 쉬지 않고 연속 발송: 450 건마다 정확히 1초.
    assertEquals(1_000_000, dwrr[450] - dwrr[0]);
  }

  @Test
  void repeatedRunsAreDeterministicAndArmsAreIsolated() {
    Scenario scenario = randomScenario(3);
    ExperimentRunner runner = new ExperimentRunner(1, 3);
    // run() 은 반복 실행 결과가 하나라도 다르면 예외를 던진다.
    ExperimentResult first = runner.run(scenario, ExperimentConfig.fifo());
    runner.run(scenario, ExperimentConfig.dwrr(1));
    ExperimentResult again = runner.run(scenario, ExperimentConfig.fifo());
    assertEquals(first.result(), again.result());
    assertEquals(3, again.measuredRuns().size());

    TraceRecorder a = Runs.trace(scenario, ExperimentConfig.dwrr(1));
    TraceRecorder b = Runs.trace(scenario, ExperimentConfig.dwrr(1));
    assertEquals(a.dispatchedIds(), b.dispatchedIds());
    assertArrayEquals(a.dispatchTimes(), b.dispatchTimes());

    ExperimentConfig dwrr = ExperimentConfig.dwrr(1);
    assertNotSame(dwrr.policyFactory().apply(scenario), dwrr.policyFactory().apply(scenario));
  }

  @Test
  void scenarioInputsAreIdenticalAcrossGenerations() {
    assertEquals(ScenarioGenerator.noisyNeighbor(1_000, 450),
        ScenarioGenerator.noisyNeighbor(1_000, 450));
    Scenario s3 = ScenarioGenerator.continuousOtp(10, 450, 500_000, 3_000_000);
    List<Message> otps = s3.messages().stream()
        .filter(m -> m.type() == MessageType.OTP).toList();
    assertEquals(6, otps.size());
    assertEquals(1_000_000, otps.get(0).arrivalTime());
    assertEquals(3_500_000, otps.get(5).arrivalTime());
    assertEquals(3_500_000 + 60_000_000, otps.get(5).deadline());
  }

  /** 수용 기준: S2 전체 규모(800,001 건)에서 두 정책 모두 450 TPS 제약 아래 모든 메시지를 처리. */
  @Test
  void s2FullScaleAcceptance() {
    Scenario s2 = ScenarioGenerator.noisyNeighbor(800_000, 450);
    SimulationResult fifo = Runs.run(s2, ExperimentConfig.fifo()).result();
    SimulationResult dwrr = Runs.run(s2, ExperimentConfig.dwrr(1)).result();

    for (SimulationResult r : List.of(fifo, dwrr)) {
      assertEquals(800_001, r.totalReceived());
      assertEquals(800_001, r.totalDelivered());
      assertEquals(0, r.providerCapacityViolations());
    }
    assertEquals(1.0, fifo.tenant("B").deadlineMissRate().getAsDouble());
    assertEquals(0.0, dwrr.tenant("B").deadlineMissRate().getAsDouble());
    assertEquals(0.0, dwrr.tenant("A").deadlineMissRate().getAsDouble());

    // 캠페인 완료 시각의 차이는 OTP 가 차지한 provider 슬롯 하나(⌈1s/450⌉ = 2,223us) 이내여야 한다.
    long delta = dwrr.tenant("A").lastDeliveryTime().getAsLong()
        - fifo.tenant("A").lastDeliveryTime().getAsLong();
    assertTrue(delta >= 0 && delta <= 2_223, "delta=" + delta);
  }

  @Test
  void s4DwrrAllocatesWindowServiceByWeightWhileFifoDoesNot() {
    ObservationWindow window = new ObservationWindow(5_000_000, 60_000_000);
    List<Tenant> tenants = List.of(new Tenant("T1", 1), new Tenant("T2", 2), new Tenant("T4", 4));
    Scenario s4 = ScenarioGenerator.weightedFairness(
        "S4", tenants, Map.of(), 40_000, 450, window);

    WindowedFairness dwrr =
        Runs.run(s4, ExperimentConfig.dwrr(1)).result().fairness().orElseThrow();
    assertTrue(dwrr.allTenantsBacklogged());
    long[] counts = dwrr.shares().stream()
        .mapToLong(WindowedFairness.TenantShare::dispatchedCount).toArray();
    // 창 안의 발송은 7 건 단위 라운드로 진행되므로, 정규화 서비스 차이는 최대 1 라운드
    // (가중치 w 인 테넌트는 최대 w 건) 이내여야 한다.
    for (int i = 0; i < counts.length; i++) {
      double perWeight = counts[i] / (double) tenants.get(i).weight();
      assertTrue(Math.abs(perWeight - counts[0]) <= 1.0, Arrays.toString(counts));
    }
    assertTrue(dwrr.jainIndexByCount().getAsDouble() > 0.9999);

    WindowedFairness fifo =
        Runs.run(s4, ExperimentConfig.fifo()).result().fairness().orElseThrow();
    assertTrue(fifo.allTenantsBacklogged());
    // FIFO 는 블록 순서대로 T1 만 서비스 → 지수는 1/n 에 해당.
    assertEquals(1.0 / 3, fifo.jainIndexByCount().getAsDouble(), 1e-12);
  }

  @Test
  void fairnessIsNotReportedForWindowsWithoutContinuousBacklog() {
    // T4 는 DWRR 에서 약 156 초에 큐가 비므로 [5s, 200s) 창 전체에서 backlog 상태가 아니다.
    ObservationWindow window = new ObservationWindow(5_000_000, 200_000_000);
    List<Tenant> tenants = List.of(new Tenant("T1", 1), new Tenant("T2", 2), new Tenant("T4", 4));
    Scenario s4 = ScenarioGenerator.weightedFairness(
        "S4", tenants, Map.of(), 40_000, 450, window);
    WindowedFairness dwrr =
        Runs.run(s4, ExperimentConfig.dwrr(1)).result().fairness().orElseThrow();
    assertTrue(dwrr.jainIndexByCount().isEmpty());
    assertTrue(dwrr.shares().stream().anyMatch(s -> !s.backloggedThroughoutWindow()));
  }
}
