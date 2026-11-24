package com.raphael;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.raphael.domain.Tenant;
import com.raphael.experiment.ExperimentConfig;
import com.raphael.metrics.ObservationWindow;
import com.raphael.metrics.SimulationResult;
import com.raphael.metrics.TenantMetrics;
import com.raphael.metrics.WindowedFairness;
import com.raphael.scenario.Scenario;
import com.raphael.scenario.ScenarioGenerator;
import com.raphael.support.Runs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * v0.2 에서 검증된 S1~S4b 의 FIFO(E0)·DWRR(E1) 결정론적 결과를 골든 파일로 고정한다.
 *
 * <p>골든 파일은 v0.3 변경 전에 v0.2 코드로 생성되었다 ({@code -Dgolden.record=true}). 이후의 모든
 * 변경은 이 투영(projection)을 바이트 단위로 보존해야 한다. 투영에는 v0.2 에 존재하던 필드만
 * 포함하므로, v0.3 에서 추가된 지표는 비교 대상이 아니다.
 */
class HistoricalResultsGoldenTest {

  private static final String RESOURCE = "golden/v02-fifo-dwrr.txt";

  static String projection() {
    StringBuilder out = new StringBuilder();
    ObservationWindow window = new ObservationWindow(30_000_000, 330_000_000);
    List<Tenant> tenants = List.of(new Tenant("T1", 1), new Tenant("T2", 2), new Tenant("T4", 4));
    List<Scenario> scenarios = List.of(
        ScenarioGenerator.basicCorrectness(),
        ScenarioGenerator.noisyNeighbor(800_000, 450),
        ScenarioGenerator.continuousOtp(800_000, 450, 333_000, 1_200_000_000L),
        ScenarioGenerator.weightedFairness("S4a", tenants, Map.of(), 200_000, 450, window),
        ScenarioGenerator.weightedFairness(
            "S4b", tenants, Map.of("T1", 2, "T2", 3, "T4", 1), 200_000, 450, window));
    for (Scenario scenario : scenarios) {
      for (ExperimentConfig arm : List.of(ExperimentConfig.fifo(), ExperimentConfig.dwrr(1))) {
        SimulationResult r = Runs.run(scenario, arm).result();
        out.append(scenario.name()).append('/').append(arm.experimentId())
            .append(" policy=").append(r.policyName())
            .append(" elapsed=").append(r.virtualElapsed())
            .append(" selections=").append(r.schedulerSelections())
            .append(" operations=").append(r.schedulerOperations())
            .append(" capacityViolations=").append(r.providerCapacityViolations()).append('\n');
        for (TenantMetrics t : r.tenants().values()) {
          out.append("  ").append(t).append('\n');
        }
        if (r.fairness().isPresent()) {
          WindowedFairness f = r.fairness().get();
          out.append("  jainCount=").append(f.jainIndexByCount())
              .append(" jainCost=").append(f.jainIndexByCost()).append('\n');
          for (WindowedFairness.TenantShare s : f.shares()) {
            out.append("  share ").append(s.tenantId()).append(" w=").append(s.weight())
                .append(" n=").append(s.dispatchedCount()).append(" c=")
                .append(s.dispatchedCost()).append(" backlogged=")
                .append(s.backloggedThroughoutWindow()).append('\n');
          }
        }
      }
    }
    return out.toString();
  }

  @Test
  void fifoAndDwrrResultsAreUnchangedSinceV02() throws IOException {
    String actual = projection();
    if (Boolean.getBoolean("golden.record")) {
      Path target = Path.of("src/test/resources", RESOURCE);
      Files.writeString(target, actual, StandardCharsets.UTF_8);
      return;
    }
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing golden resource " + RESOURCE);
      }
      assertEquals(new String(in.readAllBytes(), StandardCharsets.UTF_8), actual);
    }
  }
}
