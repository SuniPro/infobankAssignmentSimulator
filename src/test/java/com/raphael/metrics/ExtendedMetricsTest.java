package com.raphael.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.domain.Tenant;
import com.raphael.provider.ServiceModel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** v0.3 지표: 유형별 집계, 논문 출발 위반, slack, lateness, 단기 공정성, lag, 간격, COST_RATE 감사. */
class ExtendedMetricsTest {

  private static final List<Tenant> TENANTS = List.of(new Tenant("A", 1), new Tenant("B", 1));

  private static void serve(MetricsCollector c, Message m, long dispatch, long delivery) {
    c.onArrival(m, m.arrivalTime());
    c.onDispatch(m, dispatch);
    c.onDelivery(m, delivery);
  }

  @Test
  void separatesMessageTypesAndPaperViolationsFromBusinessMisses() {
    // 논문 T̂_D = 도착 + 10. 업무 deadline 은 별도.
    MetricsCollector c = new MetricsCollector(TENANTS, ServiceModel.MESSAGE_RATE, 10, 100,
        Optional.empty(), m -> m.arrivalTime() + 10);
    serve(c, new Message(0, "A", MessageType.OTP, 0, 1_000, 1), 11, 111);       // T̂_D 위반만
    serve(c, new Message(1, "A", MessageType.ADVERTISEMENT, 0, 50, 1), 10, 110); // 업무 miss 만
    serve(c, new Message(2, "B", MessageType.OTP, 0, 1_000, 1), 5, 105);       // 둘 다 아님
    SimulationResult r = c.buildResult("x", 111, 3, 3);
    GroupMetrics otp = r.messageType("OTP").orElseThrow();
    assertEquals(2, otp.received());
    assertEquals(1, otp.paperDepartureViolations());
    assertEquals(0, otp.deadlineMisses());
    GroupMetrics aAds = r.tenantTypeGroups().get("A/ADVERTISEMENT");
    assertEquals(1, aAds.deadlineMisses());
    assertEquals(0, aAds.paperDepartureViolations());
    assertEquals(60, aAds.lateness().max());                 // 110 − 50
    assertEquals(50 - 100 - 10, aAds.slackAtDispatch().min()); // deadline − 지연 − dispatch
    assertEquals(0.5, r.tenantGroups().get("A").paperDepartureViolationRate().getAsDouble());
    assertEquals(3, r.tenant("A").received() + r.tenant("B").received());
  }

  @Test
  void costRateAuditChecksMinimumTransmissionGap() {
    MetricsCollector c = new MetricsCollector(TENANTS, ServiceModel.COST_RATE, 1_000_000, 0,
        Optional.empty(), null);
    // cost 100 @ 1,000,000/s → 최소 100us 간격.
    serve(c, new Message(0, "A", MessageType.OTP, 0, 10_000, 100), 0, 100);
    serve(c, new Message(1, "A", MessageType.OTP, 0, 10_000, 100), 100, 200);
    serve(c, new Message(2, "A", MessageType.OTP, 0, 10_000, 100), 199, 299);
    assertEquals(1, c.buildResult("x", 299, 3, 3).providerCapacityViolations());
  }

  @Test
  void windowTracksGapsLagAndShortWindowFairness() {
    ObservationWindow window = new ObservationWindow(0, 4_000, 2_000);
    MetricsCollector c = new MetricsCollector(TENANTS, ServiceModel.MESSAGE_RATE, 1_000_000, 0,
        Optional.of(window), null);
    // A 와 B 모두 처음부터 끝까지 backlog. 첫 하위 구간 [0,2000) 은 A 만, 둘째는 B 만 서비스.
    long id = 0;
    List<Message> a = new java.util.ArrayList<>();
    List<Message> b = new java.util.ArrayList<>();
    for (int i = 0; i < 3; i++) {
      a.add(new Message(id++, "A", MessageType.OTP, 0, 1_000_000, 1));
      b.add(new Message(id++, "B", MessageType.OTP, 0, 1_000_000, 1));
    }
    a.forEach(m -> c.onArrival(m, 0));
    b.forEach(m -> c.onArrival(m, 0));
    c.onDispatch(a.get(0), 500);
    c.onDispatch(a.get(1), 1_000);
    c.onDispatch(b.get(0), 2_500);
    c.onDispatch(b.get(1), 3_000);
    c.onDispatch(a.get(2), 4_500);
    c.onDispatch(b.get(2), 5_000);
    WindowedFairness f = c.buildResult("x", 5_000, 6, 6).fairness().orElseThrow();
    assertTrue(f.allTenantsBacklogged());
    assertEquals(1.0, f.jainIndexByCost().getAsDouble(), 1e-12);       // 장기: 2 대 2
    assertEquals(0.5, f.shortWindowJainMinByCost().getAsDouble(), 1e-12); // 단기: 한쪽만
    WindowedFairness.TenantShare shareA = f.shares().get(0);
    WindowedFairness.TenantShare shareB = f.shares().get(1);
    assertEquals(3_000, shareA.maxServiceGap());                       // 1000 → 구간 끝 4000
    assertEquals(2_500, shareB.maxServiceGap());                       // 구간 시작 → 2500
    // B 는 A 가 2 건 받는 동안 0 건: 이상치 (2 × 1/2) 대비 1 cost 뒤처짐.
    assertEquals(1.0, shareB.maxLagBehind(), 1e-12);
    assertEquals(1.0, shareA.maxLagAhead(), 1e-12);
  }
}
