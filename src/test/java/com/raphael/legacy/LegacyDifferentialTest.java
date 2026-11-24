package com.raphael.legacy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.raphael.experiment.ExperimentConfig;
import com.raphael.metrics.SimulationResult;
import com.raphael.metrics.TenantMetrics;
import com.raphael.scenario.Scenario;
import com.raphael.scenario.ScenarioGenerator;
import com.raphael.support.TraceRecorder;
import com.raphael.experiment.ExperimentRunner;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 차등 테스트: {@code FifoScheduler}로 동작하는 리팩터링된 엔진은 보존된 v0.1 구현을 모든
 * 메시지에 대해 마이크로초 해상도로 정확히 재현해야 한다.
 */
class LegacyDifferentialTest {

  @ParameterizedTest(name = "campaign={0} tps={1} otpAt={2}us ttl={3}us delay={4}us")
  @CsvSource({
      "800000, 450, 1000000, 60000000, 100000",
      "0, 450, 1000000, 60000000, 100000",
      "1000, 7, 1000000, 60000000, 100000",
      "5000, 1000000, 0, 1, 0",
      "12345, 333, 2500000, 30000000, 250000",
      "100, 10, 1000000, 60000000, 100000",
  })
  void refactoredFifoMatchesLegacyExactly(
      int campaign, int tps, long otpArrival, long otpTtl, long delay) {
    // 레거시 실행(자체 리포트를 출력하므로 출력을 막는다).
    LegacySimulatorReference.SimulationEngine legacy =
        new LegacySimulatorReference.SimulationEngine(
            new LegacySimulatorReference.FifoScheduler(),
            new LegacySimulatorReference.ProviderSimulator(tps, delay));
    new LegacySimulatorReference.ScenarioGenerator().generate(
        new LegacySimulatorReference.Scenario(campaign, tps, otpArrival, otpTtl, delay), legacy);
    PrintStream original = System.out;
    try {
      System.setOut(new PrintStream(new ByteArrayOutputStream()));
      legacy.run();
    } finally {
      System.setOut(original);
    }

    // 리팩터링된 엔진 실행.
    Scenario scenario = ScenarioGenerator.campaignWithSingleOtp(
        "diff", "differential", campaign, tps, otpArrival, otpTtl, delay);
    TraceRecorder trace = new TraceRecorder();
    SimulationResult result = new ExperimentRunner(0, 1)
        .runOnce(scenario, ExperimentConfig.fifo(), trace).result();

    assertEquals(legacy.now(), result.virtualElapsed());
    Map<String, LegacySimulatorReference.MetricsCollector.TenantMetrics> legacyTenants =
        legacy.metrics().tenants();
    Map<String, long[]> queueLatencies = new TreeMap<>();
    for (String tenant : legacyTenants.keySet()) {
      queueLatencies.put(tenant, trace.dispatches.stream()
          .filter(s -> s.message().tenantId().equals(tenant))
          .mapToLong(s -> s.time() - s.message().arrivalTime()).sorted().toArray());
    }
    for (var entry : legacyTenants.entrySet()) {
      var expected = entry.getValue();
      TenantMetrics actual = result.tenant(entry.getKey());
      assertEquals(expected.received, actual.received());
      assertEquals(expected.dispatched, actual.dispatched());
      assertEquals(expected.delivered, actual.delivered());
      assertEquals(expected.deadlineMisses, actual.deadlineMisses());
      assertEquals(expected.deliveryLatencySum, actual.endToEndLatencySum());
      assertEquals(expected.lastDelivery, actual.lastDeliveryTime().orElse(0));
      // 레거시는 printReport()에서 자신의 목록을 정렬한다.
      assertArrayEquals(
          expected.queueLatencies.stream().mapToLong(Long::longValue).toArray(),
          queueLatencies.get(entry.getKey()));
    }
  }
}
