package com.raphael.metrics;

import java.util.List;
import java.util.OptionalDouble;

/**
 * {@link ObservationWindow} 안에서의 서비스 할당과, 가중치로 정규화한 서비스에 대한 Jain 공정성
 * 지수를 담는다.
 *
 * <p>서비스는 두 가지 단위로 측정한다. 디스패치된 메시지 수, 그리고 누적 스케줄링 비용(DWRR이
 * 실제로 균등화하는 양)이다. 비용이 모두 1이면 두 값은 일치한다. 비용이 서로 다르면 비용 기반
 * 지수만이 DWRR의 보장을 측정한다.
 *
 * <p>이 지수는 평가 대상 테넌트 모두가 윈도우 전체에 걸쳐 끊김 없이 backlog 상태였을 때만 의미가
 * 있다. 그렇지 않으면 서비스를 덜 받은 테넌트는 단지 보낼 것이 없었을 수도 있다. 따라서
 * {@link #allTenantsBacklogged()}가 성립하지 않으면 지수는 empty다.
 */
public record WindowedFairness(
    ObservationWindow window,
    List<TenantShare> shares,
    OptionalDouble jainIndexByCount,
    OptionalDouble jainIndexByCost) {

  /** 윈도우 동안 한 테넌트가 받은 서비스. */
  public record TenantShare(
      String tenantId,
      int weight,
      long dispatchedCount,
      long dispatchedCost,
      boolean backloggedThroughoutWindow) {
  }

  static WindowedFairness evaluate(ObservationWindow window, List<TenantShare> shares) {
    boolean allBacklogged = !shares.isEmpty()
        && shares.stream().allMatch(TenantShare::backloggedThroughoutWindow);
    if (!allBacklogged) {
      return new WindowedFairness(
          window, List.copyOf(shares), OptionalDouble.empty(), OptionalDouble.empty());
    }
    double[] byCount = new double[shares.size()];
    double[] byCost = new double[shares.size()];
    for (int i = 0; i < shares.size(); i++) {
      TenantShare share = shares.get(i);
      byCount[i] = share.dispatchedCount() / (double) share.weight();
      byCost[i] = share.dispatchedCost() / (double) share.weight();
    }
    return new WindowedFairness(
        window, List.copyOf(shares), jainIndex(byCount), jainIndex(byCost));
  }

  /**
   * Jain 지수 {@code (sum x)^2 / (n * sum x^2)}. 할당이 완전히 균등하면 1이고, 한 테넌트가 모든
   * 서비스를 받으면 1/n이다. 관측된 서비스가 없으면(0/0) empty다.
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
    return !shares.isEmpty() && shares.stream().allMatch(TenantShare::backloggedThroughoutWindow);
  }
}
