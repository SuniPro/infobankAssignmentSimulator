package com.raphael.metrics;

import java.util.List;
import java.util.OptionalDouble;

/**
 * {@link ObservationWindow} 안에서의 서비스 할당과, 가중치로 정규화한 서비스에 대한 Jain 공정성
 * 지수를 담는다.
 *
 * <p>서비스는 두 가지 단위로 측정한다. 디스패치된 메시지 수, 그리고 누적 스케줄링 비용(DWRR 과
 * WDEDF 가 실제로 균등화하는 양)이다. 비용이 모두 1이면 두 값은 일치한다.
 *
 * <p>장기 공정성은 관측 구간 전체, 단기 공정성은 {@link ObservationWindow#shortWindowLength()}
 * 길이의 연속 하위 구간마다 계산한 지수의 최솟값과 평균이다.
 *
 * <p>이상적 가중 할당 모델: 구간 시작 이후 전체 테넌트가 받은 누적 cost 를 {@code S(t)} 라 할 때
 * 테넌트 i 의 이상적 누적 cost 는 {@code S(t) × w_i / Σw} 이다 (모든 테넌트가 backlog 상태라는
 * 전제의 GPS 형 기준). {@code maxLagBehind} 는 이상치보다 뒤처진 최대 cost,
 * {@code maxLagAhead} 는 앞선 최대 cost 이다.
 *
 * <p>모든 지수는 평가 대상 테넌트 모두가 구간 전체에 걸쳐 끊김 없이 backlog 상태였을 때만 의미가
 * 있으므로, 그렇지 않으면 empty 다.
 */
public record WindowedFairness(
    ObservationWindow window,
    List<TenantShare> shares,
    OptionalDouble jainIndexByCount,
    OptionalDouble jainIndexByCost,
    OptionalDouble shortWindowJainMinByCost,
    OptionalDouble shortWindowJainMeanByCost) {

  /**
   * 구간 동안 한 테넌트가 받은 서비스.
   *
   * @param maxServiceGap 구간 안에서 이 테넌트의 연속 dispatch 사이(구간 경계 포함) 최대 간격 μs
   */
  public record TenantShare(
      String tenantId,
      int weight,
      long dispatchedCount,
      long dispatchedCost,
      boolean backloggedThroughoutWindow,
      long maxServiceGap,
      double maxLagBehind,
      double maxLagAhead) {
  }

  static WindowedFairness evaluate(
      ObservationWindow window, List<TenantShare> shares, long[][] shortWindowCost) {
    if (!allBacklogged(shares)) {
      return new WindowedFairness(window, List.copyOf(shares), OptionalDouble.empty(),
          OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty());
    }
    double[] byCount = new double[shares.size()];
    double[] byCost = new double[shares.size()];
    for (int i = 0; i < shares.size(); i++) {
      TenantShare share = shares.get(i);
      byCount[i] = share.dispatchedCount() / (double) share.weight();
      byCost[i] = share.dispatchedCost() / (double) share.weight();
    }
    OptionalDouble shortMin = OptionalDouble.empty();
    OptionalDouble shortMean = OptionalDouble.empty();
    if (shortWindowCost != null) {
      double min = Double.MAX_VALUE;
      double sum = 0;
      int counted = 0;
      double[] normalized = new double[shares.size()];
      for (int w = 0; w < window.shortWindowCount(); w++) {
        for (int i = 0; i < shares.size(); i++) {
          normalized[i] = shortWindowCost[i][w] / (double) shares.get(i).weight();
        }
        OptionalDouble index = jainIndex(normalized);
        if (index.isPresent()) {
          min = Math.min(min, index.getAsDouble());
          sum += index.getAsDouble();
          counted++;
        }
      }
      if (counted > 0) {
        shortMin = OptionalDouble.of(min);
        shortMean = OptionalDouble.of(sum / counted);
      }
    }
    return new WindowedFairness(window, List.copyOf(shares), jainIndex(byCount),
        jainIndex(byCost), shortMin, shortMean);
  }

  /**
   * Jain 지수 {@code (Σx)^2 / (n × Σx^2)}. 완전히 균등하면 1, 한 테넌트가 전부 받으면 1/n.
   * 서비스가 전혀 없으면(0/0) empty.
   */
  public static OptionalDouble jainIndex(double[] allocations) {
    double sum = 0;
    double sumOfSquares = 0;
    for (double allocation : allocations) {
      sum += allocation;
      sumOfSquares += allocation * allocation;
    }
    if (allocations.length == 0 || sumOfSquares == 0) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(sum * sum / (allocations.length * sumOfSquares));
  }

  public boolean allTenantsBacklogged() {
    return allBacklogged(shares);
  }

  private static boolean allBacklogged(List<TenantShare> shares) {
    return !shares.isEmpty() && shares.stream().allMatch(TenantShare::backloggedThroughoutWindow);
  }
}
