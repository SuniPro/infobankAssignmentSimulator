package com.raphael;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.experiment.ExperimentConfig;
import com.raphael.metrics.SimulationResult;
import com.raphael.metrics.TenantMetrics;
import com.raphael.scenario.ScenarioGenerator;
import com.raphael.support.Runs;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * 공개된 v0.1 E0 기준선(FIFO에서의 S2)을 고정한다. 리팩터링 이전의 특성화 테스트가 레거시
 * {@code main}을 대상으로 단언하던 값과 동일하며, 이제는 구조화된 {@link SimulationResult}에서
 * 읽어 온다. 값은 레거시 리포트와 동일하게 소수점 셋째 자리로 반올림한 뒤 비교한다. 마이크로초
 * 단위의 정확한 일치는 {@code LegacyDifferentialTest}가 검증한다.
 */
class BaselineRegressionTest {

  @Test
  void e0ReproducesPublishedBaseline() {
    SimulationResult r = Runs.run(
        ScenarioGenerator.noisyNeighbor(800_000, 450), ExperimentConfig.fifo()).result();

    TenantMetrics a = r.tenant("A");
    assertEquals(800_000, a.received());
    assertEquals(800_000, a.dispatched());
    assertEquals(800_000, a.delivered());
    assertEquals(0.0, a.deadlineMissRate().getAsDouble());
    assertEquals("888.887", seconds(a.queueLatency().p50()));
    assertEquals("1688.887", seconds(a.queueLatency().p95()));
    assertEquals("1759.998", seconds(a.queueLatency().p99()));
    assertEquals("1777.776", seconds(a.queueLatency().max()));
    assertEquals("888.988", seconds(a.averageEndToEndLatency().getAsDouble()));
    assertEquals("1777.876", seconds(a.lastDeliveryTime().getAsLong()));

    TenantMetrics b = r.tenant("B");
    assertEquals(1, b.received());
    assertEquals(1, b.dispatched());
    assertEquals(1, b.delivered());
    assertEquals(1.0, b.deadlineMissRate().getAsDouble());
    assertEquals("1776.778", seconds(b.queueLatency().max()));
    assertEquals("1776.878", seconds(b.endToEndLatency().max()));
    assertEquals("1777.878", seconds(b.lastDeliveryTime().getAsLong()));

    assertEquals("1777.878", seconds(r.virtualElapsed()));
    assertTrue(r.isConserved());
  }

  private static String seconds(double micros) {
    return String.format(Locale.ROOT, "%.3f", micros / 1_000_000.0);
  }
}
