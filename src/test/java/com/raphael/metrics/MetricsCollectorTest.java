package com.raphael.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.domain.Tenant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MetricsCollectorTest {

  private static final List<Tenant> TENANTS = List.of(new Tenant("A", 1), new Tenant("B", 3));

  private static Message msg(long id, String tenant, long arrival, long deadline, int cost) {
    return new Message(id, tenant, MessageType.OTP, arrival, deadline, cost);
  }

  @Test
  void deliveryExactlyAtDeadlineSucceedsAndOneMicrosecondLaterMisses() {
    MetricsCollector collector = new MetricsCollector(TENANTS, 10, Optional.empty());
    Message onTime = msg(0, "A", 0, 100_000, 1);
    Message late = msg(1, "A", 0, 99_999, 1);
    for (Message m : List.of(onTime, late)) {
      collector.onArrival(m, 0);
    }
    collector.onDispatch(onTime, 0);
    collector.onDispatch(late, 100_000);
    collector.onDelivery(onTime, 100_000);
    collector.onDelivery(late, 100_000);
    TenantMetrics a = collector.buildResult("x", 100_000, 2, 2).tenant("A");
    assertEquals(1, a.deadlineMisses());
    assertEquals(0.5, a.deadlineMissRate().getAsDouble());
  }

  @Test
  void missRateUsesAllReceivedMessagesAndCountsUndeliveredAsMisses() {
    MetricsCollector collector = new MetricsCollector(TENANTS, 10, Optional.empty());
    for (int i = 0; i < 4; i++) {
      collector.onArrival(msg(i, "A", 0, 1_000_000, 1), 0);
    }
    collector.onDispatch(msg(0, "A", 0, 1_000_000, 1), 0);
    collector.onDelivery(msg(0, "A", 0, 1_000_000, 1), 10);
    TenantMetrics a = collector.buildResult("x", 10, 1, 1).tenant("A");
    assertEquals(3, a.undelivered());
    assertEquals(0.75, a.deadlineMissRate().getAsDouble());
    assertFalse(a.isConserved());
  }

  @Test
  void tenantWithoutTrafficReportsMissingValuesNotZero() {
    TenantMetrics b = new MetricsCollector(TENANTS, 10, Optional.empty())
        .buildResult("x", 0, 0, 0).tenant("B");
    assertTrue(b.deadlineMissRate().isEmpty());
    assertTrue(b.averageEndToEndLatency().isEmpty());
    assertTrue(b.lastDeliveryTime().isEmpty());
    assertTrue(b.queueLatency().isEmpty());
    assertThrows(IllegalStateException.class, () -> b.queueLatency().p50());
  }

  @Test
  void unknownTenantIsRejected() {
    MetricsCollector collector = new MetricsCollector(TENANTS, 10, Optional.empty());
    assertThrows(IllegalArgumentException.class,
        () -> collector.onArrival(msg(0, "Z", 0, 1, 1), 0));
  }

  @Test
  void nearestRankPercentilesUseExactIntegerArithmetic() {
    LongSamples samples = new LongSamples();
    for (long v = 100; v >= 1; v--) {
      samples.add(v);
    }
    LatencySummary summary = LatencySummary.of(samples);
    assertEquals(new LatencySummary(100, 50, 95, 99, 100), summary);

    // n=7, p=0.95 → ceil(6.65) = 7 번째 값.
    assertEquals(7, LatencySummary.nearestRank(new long[] {1, 2, 3, 4, 5, 6, 7}, 9_500));
    assertEquals(1, LatencySummary.nearestRank(new long[] {1}, 5_000));
  }

  @Test
  void capacityAuditDetectsMoreThanTpsDispatchesInOneSecond() {
    MetricsCollector collector = new MetricsCollector(TENANTS, 2, Optional.empty());
    long[] times = {0, 500_000, 999_999, 1_999_999};
    for (int i = 0; i < times.length; i++) {
      Message m = msg(i, "A", 0, 10_000_000, 1);
      collector.onArrival(m, 0);
      collector.onDispatch(m, times[i]);
      collector.onDelivery(m, times[i]);
    }
    // 999,999 의 발송은 [0, 1s) 안에 3 번째 발송이므로 위반 1 건. 1,999,999 는 2 칸 전(500,000)과
    // 1.5초 떨어져 있으므로 위반이 아니다.
    assertEquals(1, collector.buildResult("x", 2_000_000, 4, 4).providerCapacityViolations());
  }

  @Test
  void observationWindowCountsServiceAndChecksContinuousBacklog() {
    ObservationWindow window = new ObservationWindow(10, 20);
    MetricsCollector collector = new MetricsCollector(TENANTS, 1_000_000, Optional.of(window));
    // A: 시각 0 에 3 건 도착, 5·15·25 에 발송 → [10,20) 동안 계속 backlog 상태.
    // B: 시각 0 에 2 건 도착, 6·16 에 발송 → 16 에 큐가 비므로 창 전체를 덮지 못한다.
    long id = 0;
    long[] aTimes = {5, 15, 25};
    long[] bTimes = {6, 16};
    Message[] a = new Message[3];
    Message[] b = new Message[2];
    for (int i = 0; i < 3; i++) {
      a[i] = msg(id++, "A", 0, 1_000, 1);
      collector.onArrival(a[i], 0);
    }
    for (int i = 0; i < 2; i++) {
      b[i] = msg(id++, "B", 0, 1_000, 4);
      collector.onArrival(b[i], 0);
    }
    collector.onDispatch(a[0], aTimes[0]);
    collector.onDispatch(b[0], bTimes[0]);
    collector.onDispatch(a[1], aTimes[1]);
    collector.onDispatch(b[1], bTimes[1]);
    collector.onDispatch(a[2], aTimes[2]);

    WindowedFairness fairness = collector.buildResult("x", 25, 5, 5).fairness().orElseThrow();
    WindowedFairness.TenantShare shareA = fairness.shares().get(0);
    WindowedFairness.TenantShare shareB = fairness.shares().get(1);
    assertEquals(1, shareA.dispatchedCount());
    assertTrue(shareA.backloggedThroughoutWindow());
    assertEquals(1, shareB.dispatchedCount());
    assertEquals(4, shareB.dispatchedCost());
    assertFalse(shareB.backloggedThroughoutWindow());
    // 한 테넌트라도 backlog 가 끊기면 공정성 지수는 산출하지 않는다.
    assertTrue(fairness.jainIndexByCount().isEmpty());
    assertFalse(fairness.allTenantsBacklogged());
  }

  @Test
  void jainIndexBoundaries() {
    assertEquals(1.0, WindowedFairness.jainIndex(new double[] {5, 5, 5}).getAsDouble(), 1e-12);
    assertEquals(1.0 / 3, WindowedFairness.jainIndex(new double[] {9, 0, 0}).getAsDouble(),
        1e-12);
    assertTrue(WindowedFairness.jainIndex(new double[] {0, 0}).isEmpty());
    assertTrue(WindowedFairness.jainIndex(new double[0]).isEmpty());
  }
}
