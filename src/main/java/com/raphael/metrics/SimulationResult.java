package com.raphael.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 시뮬레이션 1회 실행의 결정적 결과다. 벽시계(wall-clock) 데이터를 포함하지 않으므로, 같은
 * 시나리오와 정책으로 두 번 실행하면 {@code equals}로 같은 결과가 나와야 한다.
 *
 * @param policyName 스케줄링 정책 이름
 * @param virtualElapsed 마지막으로 처리한 이벤트의 가상 시간(마이크로초)
 * @param tenants 시나리오의 테넌트 순서대로 나열한 테넌트별 지표
 * @param schedulerSelections selectNext 호출 횟수
 * @param schedulerOperations 정책이 정의하는 기본 스케줄링 단계 수
 * @param providerCapacityViolations 초당 tps 감사를 위반한 디스패치 수(기대값 0)
 * @param fairness 시나리오에 관측 윈도우가 설정된 경우, 그 윈도우에서의 서비스 할당
 */
public record SimulationResult(
    String policyName,
    long virtualElapsed,
    Map<String, TenantMetrics> tenants,
    long schedulerSelections,
    long schedulerOperations,
    long providerCapacityViolations,
    Optional<WindowedFairness> fairness) {

  public SimulationResult {
    tenants = Collections.unmodifiableMap(new LinkedHashMap<>(tenants));
  }

  public TenantMetrics tenant(String tenantId) {
    TenantMetrics metrics = tenants.get(tenantId);
    if (metrics == null) {
      throw new IllegalArgumentException("Unknown tenant " + tenantId);
    }
    return metrics;
  }

  public long totalReceived() {
    return tenants.values().stream().mapToLong(TenantMetrics::received).sum();
  }

  public long totalDispatched() {
    return tenants.values().stream().mapToLong(TenantMetrics::dispatched).sum();
  }

  public long totalDelivered() {
    return tenants.values().stream().mapToLong(TenantMetrics::delivered).sum();
  }

  /** 수신한 모든 메시지가 수신 횟수와 정확히 같은 횟수만큼 디스패치되고 전달되었다. */
  public boolean isConserved() {
    return tenants.values().stream().allMatch(TenantMetrics::isConserved)
        && schedulerSelections == totalDispatched();
  }
}
