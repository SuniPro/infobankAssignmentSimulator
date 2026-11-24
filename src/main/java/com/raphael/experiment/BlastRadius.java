package com.raphael.experiment;

import com.raphael.metrics.SimulationResult;
import com.raphael.metrics.TenantMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 장애 영향 범위: 같은 arm·같은 워크로드에서 장애를 넣은 실행과 넣지 않은 실행을 비교한다.
 *
 * <p><b>SLO 저하 기준</b> (aggressor 가 아닌 테넌트 각각에 대해): 다음 중 하나이면 "영향받음".
 * <ol>
 *   <li>deadline miss 율 증가 &gt; 1 퍼센트포인트. 분모는 그 테넌트의 수신 메시지 수.</li>
 *   <li>E2E p99 가 기준 실행의 1.5 배를 넘고, 동시에 1 초 이상 증가.</li>
 * </ol>
 * 영향 범위 = 영향받은 비-aggressor 테넌트 수 / 비-aggressor 테넌트 수.
 *
 * @param degradation 비-aggressor 테넌트 전체의 miss 수 증가 (장애 − 기준), 장애 관련 품질 저하
 */
public record BlastRadius(
    String experimentId,
    int nonAggressorTenants,
    int affectedTenants,
    long additionalMisses,
    List<TenantImpact> impacts) {

  public static final double MISS_RATE_THRESHOLD = 0.01;
  public static final double P99_RATIO_THRESHOLD = 1.5;
  public static final long P99_ABSOLUTE_THRESHOLD = 1_000_000;

  /** 테넌트 하나의 비교. p99 는 배달이 없으면 -1. */
  public record TenantImpact(String tenantId, double baselineMissRate, double failureMissRate,
      long baselineE2eP99, long failureE2eP99, boolean affected) {
  }

  public static BlastRadius compare(String experimentId, SimulationResult baseline,
      SimulationResult failure, Set<String> aggressors) {
    List<TenantImpact> impacts = new ArrayList<>();
    int affected = 0;
    long additionalMisses = 0;
    for (TenantMetrics base : baseline.tenants().values()) {
      if (aggressors.contains(base.tenantId()) || base.received() == 0) {
        continue;
      }
      TenantMetrics fail = failure.tenant(base.tenantId());
      double baseMiss = base.deadlineMissRate().orElse(0);
      double failMiss = fail.deadlineMissRate().orElse(0);
      long baseP99 = base.endToEndLatency().isEmpty() ? -1 : base.endToEndLatency().p99();
      long failP99 = fail.endToEndLatency().isEmpty() ? -1 : fail.endToEndLatency().p99();
      boolean latency = baseP99 >= 0 && failP99 >= 0
          && failP99 > P99_RATIO_THRESHOLD * baseP99
          && failP99 - baseP99 > P99_ABSOLUTE_THRESHOLD;
      boolean hit = failMiss - baseMiss > MISS_RATE_THRESHOLD || latency;
      affected += hit ? 1 : 0;
      additionalMisses += (fail.deadlineMisses() + fail.undelivered())
          - (base.deadlineMisses() + base.undelivered());
      impacts.add(new TenantImpact(base.tenantId(), baseMiss, failMiss, baseP99, failP99, hit));
    }
    return new BlastRadius(experimentId, impacts.size(), affected, additionalMisses,
        List.copyOf(impacts));
  }
}
