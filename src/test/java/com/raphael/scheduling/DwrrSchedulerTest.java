package com.raphael.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DwrrSchedulerTest {

  private long nextId;

  private Message msg(String tenant, int cost) {
    return new Message(nextId++, tenant, MessageType.ADVERTISEMENT, 0, 0, cost);
  }

  private void enqueue(SchedulingPolicy policy, String tenant, int count, int cost) {
    for (int i = 0; i < count; i++) {
      policy.enqueue(msg(tenant, cost));
    }
  }

  private static String select(SchedulingPolicy policy, int count) {
    StringBuilder order = new StringBuilder();
    for (int i = 0; i < count; i++) {
      order.append(policy.selectNext(0).tenantId());
    }
    return order.toString();
  }

  @Test
  void equalWeightsAlternateOneMessagePerVisit() {
    DwrrScheduler dwrr = new DwrrScheduler(Map.of("A", 1, "B", 1), 1);
    enqueue(dwrr, "A", 5, 1);
    enqueue(dwrr, "B", 3, 1);
    // B 가 비면 링에서 제거되고, 이후 A 가 모든 슬롯을 사용한다.
    assertEquals("ABABABAA", select(dwrr, 8));
  }

  @Test
  void unequalWeightsServeMultipleMessagesPerVisit() {
    DwrrScheduler dwrr = new DwrrScheduler(Map.of("A", 1, "B", 3), 1);
    enqueue(dwrr, "A", 4, 1);
    enqueue(dwrr, "B", 12, 1);
    assertEquals("ABBBABBBABBBABBB", select(dwrr, 16));
  }

  @Test
  void quantumIsReplenishedOncePerVisitNotPerSelection() {
    DwrrScheduler dwrr = new DwrrScheduler(Map.of("A", 3, "B", 1), 2);
    enqueue(dwrr, "A", 10, 1);
    enqueue(dwrr, "B", 10, 1);
    assertNull(dwrr.tenantInService());

    // 방문 시작: quantum = 2 * 3 = 6 보충 후 비용 1 차감 → 5.
    dwrr.selectNext(0);
    assertEquals("A", dwrr.tenantInService());
    assertEquals(5, dwrr.deficitOf("A"));
    // 같은 방문이 여러 provider 슬롯에 걸쳐 유지되며, 재보충 없이 차감만 일어난다.
    for (long expected = 4; expected >= 0; expected--) {
      assertEquals("A", dwrr.selectNext(0).tenantId());
      assertEquals(expected, dwrr.deficitOf("A"));
      assertEquals("A", dwrr.tenantInService());
    }
    // deficit 0 → 다음 선택에서 B 로 넘어간다 (B quantum = 2).
    assertEquals("BB", select(dwrr, 2));
    assertEquals(0, dwrr.deficitOf("B"));
    assertEquals("A", dwrr.selectNext(0).tenantId());
  }

  @Test
  void costlyMessageAccumulatesDeficitAcrossRounds() {
    DwrrScheduler dwrr = new DwrrScheduler(Map.of("A", 1, "B", 1), 1);
    enqueue(dwrr, "A", 3, 3);
    enqueue(dwrr, "B", 20, 1);

    // 1회차: A 방문(deficit 1 < 3) → 미사용 deficit 를 보존한 채 B 에게 넘어간다.
    assertEquals("B", dwrr.selectNext(0).tenantId());
    assertEquals(1, dwrr.deficitOf("A"));
    assertEquals("B", dwrr.selectNext(0).tenantId());
    assertEquals(2, dwrr.deficitOf("A"));
    // 3회차 방문에서 deficit 3 에 도달해 A 가 발송된다.
    assertEquals("A", dwrr.selectNext(0).tenantId());
    assertEquals(0, dwrr.deficitOf("A"));
    // 정상 상태: 비용 3 인 A 1건당 비용 1 인 B 3건 → 비용 기준으로 1:1.
    assertEquals("BBBABBBA", select(dwrr, 8));
  }

  @Test
  void messageCostFarAboveQuantumIsServedInOneBoundedSelection() {
    DwrrScheduler dwrr = new DwrrScheduler(Map.of("A", 1, "B", 2), 1);
    dwrr.enqueue(msg("A", 1_000_000));
    dwrr.enqueue(msg("B", 999_999));
    // 압축이 없다면 수십만 번의 링 회전이 필요하다. 압축 덕분에 선택 1회당 O(활성 테넌트 수).
    Message first = dwrr.selectNext(0);
    assertEquals("B", first.tenantId());
    assertTrue(dwrr.operationCount() <= 3 * 2 + 2, "operations=" + dwrr.operationCount());
    assertEquals("A", dwrr.selectNext(0).tenantId());
    assertTrue(dwrr.fastForwardCount() >= 1);
    assertTrue(dwrr.isEmpty());
  }

  @Test
  void drainedTenantLeavesRingWithZeroDeficitAndReactivatesAtTail() {
    DwrrScheduler dwrr = new DwrrScheduler(Map.of("A", 4, "B", 1, "C", 1), 1);
    enqueue(dwrr, "A", 2, 1);
    enqueue(dwrr, "B", 5, 1);
    enqueue(dwrr, "C", 5, 1);
    assertEquals(List.of("A", "B", "C"), dwrr.activeRingOrder());

    assertEquals("AA", select(dwrr, 2));
    // A 는 deficit 2 가 남았지만 큐가 비었으므로 제거되고 deficit 은 0 으로 초기화된다.
    assertFalse(dwrr.isActive("A"));
    assertEquals(0, dwrr.deficitOf("A"));
    assertEquals(List.of("B", "C"), dwrr.activeRingOrder());

    assertEquals("B", dwrr.selectNext(0).tenantId());
    // 재활성화: 링의 꼬리에 정확히 한 번 추가되고, 방문 시작 전 deficit 는 0 이다.
    enqueue(dwrr, "A", 3, 1);
    assertTrue(dwrr.isActive("A"));
    assertEquals(0, dwrr.deficitOf("A"));
    assertEquals(List.of("B", "C", "A"), dwrr.activeRingOrder());
    assertEquals("CAAA", select(dwrr, 4));
  }

  @Test
  void repeatedEnqueuesDoNotDuplicateRingMembership() {
    DwrrScheduler dwrr = new DwrrScheduler(Map.of("A", 1, "B", 1), 1);
    enqueue(dwrr, "A", 50, 1);
    enqueue(dwrr, "B", 1, 1);
    enqueue(dwrr, "A", 50, 1);
    assertEquals(List.of("A", "B"), dwrr.activeRingOrder());
    assertEquals(101, dwrr.pendingCount());
  }

  @Test
  void preservesFifoOrderWithinEachTenant() {
    DwrrScheduler dwrr = new DwrrScheduler(Map.of("A", 2, "B", 3), 1);
    Random random = new Random(7);
    Map<String, List<Long>> enqueued = new HashMap<>();
    for (int i = 0; i < 500; i++) {
      Message m = msg(random.nextBoolean() ? "A" : "B", 1 + random.nextInt(4));
      enqueued.computeIfAbsent(m.tenantId(), t -> new ArrayList<>()).add(m.id());
      dwrr.enqueue(m);
    }
    Map<String, List<Long>> dispatched = new HashMap<>();
    while (!dwrr.isEmpty()) {
      Message m = dwrr.selectNext(0);
      dispatched.computeIfAbsent(m.tenantId(), t -> new ArrayList<>()).add(m.id());
    }
    assertEquals(enqueued, dispatched);
  }

  @Test
  void singleTenantIsServedOnEverySelection() {
    DwrrScheduler dwrr = new DwrrScheduler(Map.of("A", 1, "B", 1), 1);
    enqueue(dwrr, "A", 100, 1);
    assertEquals("A".repeat(100), select(dwrr, 100));
  }

  @Test
  void backloggedTenantsReceiveExactlyWeightProportionalServiceWithUnitCosts() {
    DwrrScheduler dwrr = new DwrrScheduler(Map.of("T1", 1, "T2", 2, "T4", 4), 1);
    enqueue(dwrr, "T1", 10_000, 1);
    enqueue(dwrr, "T2", 10_000, 1);
    enqueue(dwrr, "T4", 10_000, 1);
    Map<String, Integer> served = new HashMap<>();
    // 7,000 선택 = 정확히 1,000 라운드 (모든 테넌트가 계속 backlog 상태).
    for (int i = 0; i < 7_000; i++) {
      served.merge(dwrr.selectNext(0).tenantId(), 1, Integer::sum);
    }
    assertEquals(Map.of("T1", 1_000, "T2", 2_000, "T4", 4_000), served);
  }

  @Test
  void backloggedTenantsReceiveWeightProportionalCostWithVariableCosts() {
    Map<String, Integer> weights = Map.of("T1", 1, "T2", 2, "T4", 4);
    Map<String, Integer> costs = Map.of("T1", 2, "T2", 3, "T4", 1);
    DwrrScheduler dwrr = new DwrrScheduler(weights, 1);
    for (String tenant : weights.keySet()) {
      enqueue(dwrr, tenant, 200_000, costs.get(tenant));
    }
    Map<String, Long> cost = new HashMap<>();
    for (int i = 0; i < 100_000; i++) {
      Message m = dwrr.selectNext(0);
      cost.merge(m.tenantId(), (long) m.cost(), Long::sum);
    }
    // DRR 보장: backlog 동안 방문 사이의 deficit 는 [0, maxCost) 에 머문다. 따라서 R 라운드 후
    // 누적 비용/quantum 은 (R - maxCost/quantum - 1, R] 범위에 있고, 두 테넌트 간 정규화 서비스
    // 차이는 maxCost/minQuantum + 1 라운드 이내다 (진행 중인 라운드 1 개 포함).
    double bound = 3.0 / 1 + 1;
    List<Double> normalized = weights.keySet().stream()
        .map(t -> cost.get(t) / (double) weights.get(t)).toList();
    double spread = normalized.stream().mapToDouble(d -> d).max().orElseThrow()
        - normalized.stream().mapToDouble(d -> d).min().orElseThrow();
    assertTrue(spread <= bound, "normalized=" + normalized);
  }

  @Test
  void fastForwardMatchesTextbookDwrrOnRandomWorkloads() {
    for (long seed = 1; seed <= 30; seed++) {
      Random random = new Random(seed);
      Map<String, Integer> weights = new HashMap<>();
      int tenants = 1 + random.nextInt(6);
      for (int t = 0; t < tenants; t++) {
        weights.put("T" + t, 1 + random.nextInt(5));
      }
      int baseQuantum = 1 + random.nextInt(3);
      DwrrScheduler fast = new DwrrScheduler(weights, baseQuantum);
      NaiveDwrrReference naive = new NaiveDwrrReference(weights, baseQuantum);
      int maxCost = 1 + random.nextInt(seed % 3 == 0 ? 200 : 8);

      for (int step = 0; step < 3_000; step++) {
        // 도착과 발송을 무작위로 섞어 활성화/비활성화/재활성화를 반복시킨다.
        if (fast.isEmpty() || random.nextInt(10) < 4) {
          Message m = msg("T" + random.nextInt(tenants), 1 + random.nextInt(maxCost));
          fast.enqueue(m);
          naive.enqueue(m);
        } else {
          Message expected = naive.selectNext(0);
          assertEquals(expected, fast.selectNext(0), "seed " + seed + " step " + step);
          for (String tenant : weights.keySet()) {
            assertEquals(naive.deficitOf(tenant), fast.deficitOf(tenant), "deficit " + tenant);
          }
        }
        assertEquals(naive.pendingCount(), fast.pendingCount());
        List<String> ring = fast.activeRingOrder();
        assertEquals(ring.size(), new HashSet<>(ring).size(), "duplicate ring entry");
      }
    }
  }

  @Test
  void selectNextAlwaysReturnsAMessageWithBoundedWorkWhileNonEmpty() {
    Random random = new Random(42);
    Map<String, Integer> weights = Map.of("A", 1, "B", 1, "C", 2, "D", 3);
    DwrrScheduler dwrr = new DwrrScheduler(weights, 1);
    int total = 20_000;
    for (int i = 0; i < total; i++) {
      dwrr.enqueue(msg(List.of("A", "B", "C", "D").get(random.nextInt(4)),
          1 + random.nextInt(1_000)));
    }
    int active = weights.size();
    for (int i = 0; i < total; i++) {
      long before = dwrr.operationCount();
      Message m = dwrr.selectNext(0);
      // 작업 상한: 실패 방문 ≤ A, fast-forward 스캔 ≤ A, 이후 방문 ≤ A (+ 시작 방문 1).
      assertTrue(dwrr.operationCount() - before <= 3L * active + 1);
      assertTrue(m != null);
    }
    assertTrue(dwrr.isEmpty());
    assertThrows(IllegalStateException.class, () -> dwrr.selectNext(0));
  }

  @Test
  void rejectsInvalidConfigurationAndUnknownTenants() {
    assertThrows(IllegalArgumentException.class, () -> new DwrrScheduler(Map.of("A", 1), 0));
    assertThrows(IllegalArgumentException.class, () -> new DwrrScheduler(Map.of("A", 0), 1));
    DwrrScheduler dwrr = new DwrrScheduler(Map.of("A", 1), 1);
    assertThrows(IllegalArgumentException.class, () -> dwrr.enqueue(msg("Z", 1)));
  }
}
