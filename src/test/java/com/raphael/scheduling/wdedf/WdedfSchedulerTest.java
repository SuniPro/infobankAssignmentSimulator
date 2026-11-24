package com.raphael.scheduling.wdedf;

import static com.raphael.scheduling.wdedf.WdedfTestSupport.quanta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.domain.Message;
import com.raphael.scheduling.DwrrScheduler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WdedfSchedulerTest {

  private final WdedfTestSupport t = new WdedfTestSupport();

  private static List<Long> drainIds(com.raphael.scheduling.SchedulingPolicy policy) {
    List<Long> ids = new ArrayList<>();
    while (!policy.isEmpty()) {
      ids.add(policy.selectNext(0).id());
    }
    return ids;
  }

  @Test
  void exampleOneOrderingUnderDrrAndWdedf() {
    // Table 1: A1(400bit, T̂_D 160), B1(500bit, 216), A2(400bit, 320). 서비스 시작 시점에 셋 다 대기.
    for (Map<String, Long> q : List.of(quanta("A", 1_000, "B", 1_000),
        quanta("A", 1_000, "B", 1_500))) {
      WdedfTestSupport s = new WdedfTestSupport();
      Message a1 = s.msg("A", 400, 160);
      Message b1 = s.msg("B", 500, 216);
      Message a2 = s.msg("A", 400, 320);
      WdedfScheduler wdedf = s.scheduler(q, 0);
      List.of(a1, b1, a2).forEach(wdedf::enqueue);
      assertEquals(List.of(a1.id(), b1.id(), a2.id()), drainIds(wdedf));

      DwrrScheduler drr = new DwrrScheduler(Map.of("A", 1, "B", 1), 1_000);
      List.of(a1, b1, a2).forEach(drr::enqueue);
      assertEquals(List.of(a1.id(), a2.id(), b1.id()), drainIds(drr));
    }
  }

  @Test
  void exampleTwoWaitingQueueKeepsDeficitWithinTheRound() {
    // A(ω=1500) 의 A1(600) 이 나가면 A 는 WAITING 으로 DC 900 을 보존한다. B·C 가 backlog 인 같은
    // 라운드 안에 A2 가 도착하면 새 quantum 없이 보존된 DC 로 즉시 ACTIVE 가 된다.
    WdedfScheduler wdedf = t.scheduler(quanta("A", 1_500, "B", 3_500, "C", 5_000), 0);
    wdedf.enqueue(t.msg("A", 600, 0));
    for (int i = 0; i < 5; i++) {
      wdedf.enqueue(t.msg("B", 1_500, 1_000 + i));
      wdedf.enqueue(t.msg("C", 800, 1_000 + i));
    }
    assertEquals("A", wdedf.selectNext(0).tenantId());
    assertEquals(QueueState.WAITING, wdedf.stateOf("A"));
    assertEquals(900, wdedf.deficitOf("A"));
    wdedf.selectNext(0);
    Message a2 = t.msg("A", 600, 10);
    wdedf.enqueue(a2);
    assertEquals(QueueState.ACTIVE, wdedf.stateOf("A"));
    assertEquals(900, wdedf.deficitOf("A"));
    assertEquals(1, wdedf.round());
    assertEquals(a2.id(), wdedf.selectNext(0).id());
    assertEquals(300, wdedf.deficitOf("A"));
    assertEquals(900, wdedf.diagnostics().get("retainedCreditReused"));

    // DWRR 은 큐가 비는 즉시 deficit 을 0 으로 만든다.
    DwrrScheduler dwrr = new DwrrScheduler(Map.of("A", 1, "B", 1), 1_500);
    dwrr.enqueue(t.msg("A", 600, 0));
    dwrr.enqueue(t.msg("B", 1_500, 0));
    dwrr.selectNext(0);
    dwrr.enqueue(t.msg("A", 600, 0));
    assertEquals(List.of("B", "A"), List.of(dwrr.selectNext(0).tenantId(),
        dwrr.selectNext(0).tenantId()));
  }

  @Test
  void queueStateMachineCoversEveryFigureTwoTransition() {
    WdedfScheduler w = t.scheduler(quanta("A", 3, "B", 1), 0);
    w.enqueue(t.msg("A", 2, 0));                       // INACTIVE → ACTIVE, DC = ω = 3
    assertEquals(QueueState.ACTIVE, w.stateOf("A"));
    assertEquals(3, w.deficitOf("A"));
    w.selectNext(0);                                   // ACTIVE → WAITING, DC 1 보존
    assertEquals(QueueState.WAITING, w.stateOf("A"));
    assertEquals(1, w.deficitOf("A"));
    w.enqueue(t.msg("A", 1, 0));                       // WAITING → ACTIVE (충분)
    assertEquals(QueueState.ACTIVE, w.stateOf("A"));
    w.selectNext(0);                                   // DC 0 → WAITING
    w.enqueue(t.msg("A", 2, 0));                       // WAITING → BLOCKED (부족)
    assertEquals(QueueState.BLOCKED, w.stateOf("A"));
    assertEquals(1, w.round());
    w.selectNext(0);                                   // 새 라운드: BLOCKED → ACTIVE (DC 0+3)
    assertEquals(2, w.round());
    assertEquals(QueueState.WAITING, w.stateOf("A"));
    assertEquals(1, w.deficitOf("A"));
    w.enqueue(t.msg("B", 2, 0));                       // INACTIVE → BLOCKED (ω=1 < 2)
    assertEquals(QueueState.BLOCKED, w.stateOf("B"));
    w.selectNext(0);                                   // 라운드 3: A WAITING → INACTIVE, B 보충
    assertEquals(QueueState.INACTIVE, w.stateOf("A"));
    assertEquals(0, w.deficitOf("A"));
    assertEquals(1, w.diagnostics().get("retainedCreditExpired"));

    WdedfScheduler v = t.scheduler(quanta("A", 3), 0);
    v.enqueue(t.msg("A", 1, 0));
    v.enqueue(t.msg("A", 1, 0));
    v.enqueue(t.msg("A", 2, 0));
    v.selectNext(0);                                   // ACTIVE → ACTIVE (DC 2, head 1)
    v.selectNext(0);                                   // ACTIVE → BLOCKED (DC 1, head 2)
    assertEquals(QueueState.BLOCKED, v.stateOf("A"));
    w.checkInvariants();
    v.checkInvariants();

    Map<String, Long> d = w.diagnostics();
    for (String key : List.of("transition.INACTIVE->ACTIVE", "transition.ACTIVE->WAITING",
        "transition.WAITING->ACTIVE", "transition.WAITING->BLOCKED",
        "transition.BLOCKED->ACTIVE", "transition.WAITING->INACTIVE",
        "transition.INACTIVE->BLOCKED")) {
      assertTrue(d.getOrDefault(key, 0L) > 0, key + " in " + d);
    }
    assertTrue(v.diagnostics().get("transition.ACTIVE->ACTIVE") > 0);
    assertTrue(v.diagnostics().get("transition.ACTIVE->BLOCKED") > 0);
  }

  @Test
  void quantumIsReplenishedOncePerRoundAndUnusedDeficitCarriesOver() {
    WdedfScheduler w = t.scheduler(quanta("A", 1, "B", 1), 0);
    for (int i = 0; i < 3; i++) {
      w.enqueue(t.msg("A", 3, 0));
    }
    for (int i = 0; i < 10; i++) {
      w.enqueue(t.msg("B", 1, 5));
    }
    assertEquals(1, w.deficitOf("A"));                 // 활성화 quantum
    assertEquals("B", w.selectNext(0).tenantId());     // 라운드 1
    assertEquals("B", w.selectNext(0).tenantId());     // 라운드 2
    assertEquals(2, w.round());
    assertEquals(2, w.deficitOf("A"));                 // 라운드마다 정확히 ω 만큼
    assertEquals("A", w.selectNext(0).tenantId());     // 라운드 3: DC 3 → A (출발 시각 0 < 5)
    assertEquals(0, w.deficitOf("A"));
    assertEquals("B", w.selectNext(0).tenantId());
    assertEquals(3, w.round());
    assertEquals(4, w.diagnostics().get("quantumReplenishments"));
  }

  @Test
  void costsFarAboveQuantumTerminateViaRoundCompressionWithoutLooping() {
    WdedfScheduler w = t.scheduler(quanta("A", 1, "B", 3), 0);
    w.enqueue(t.msg("A", 1_000_000, 0));
    w.enqueue(t.msg("B", 999_999, 0));
    Message first = w.selectNext(0);
    // B 는 333,333 라운드 후, A 는 999,999 라운드 후 적격 → B 먼저.
    assertEquals("B", first.tenantId());
    assertTrue(w.diagnostics().get("skippedRounds") > 0);
    assertTrue(w.operationCount() < 50, "operations=" + w.operationCount());
    assertEquals("A", w.selectNext(0).tenantId());
    assertTrue(w.isEmpty());
    w.checkInvariants();
    assertThrows(IllegalStateException.class, () -> w.selectNext(0));
  }

  @Test
  void borrowingIsBoundedByPqAndPdcAndCreatesCarriedDebt() {
    // Ω = 6, Λ = 50% → PQ = 3. PDC_B = ω_B = 2.
    WdedfScheduler w = t.scheduler(quanta("A", 4, "B", 2), 5_000);
    for (int i = 0; i < 10; i++) {
      w.enqueue(t.msg("A", 1, 100));
    }
    for (int i = 0; i < 3; i++) {
      w.enqueue(t.msg("B", 1, 10));
    }
    assertEquals("B", w.selectNext(0).tenantId());
    assertEquals("B", w.selectNext(0).tenantId());
    assertEquals(QueueState.BLOCKED, w.stateOf("B"));
    assertEquals(3, w.remainingPq());

    assertEquals("B", w.selectNext(0).tenantId());     // 차입 성공
    assertEquals(-1, w.deficitOf("B"));                // 일반 DC 에서도 차감 → 부채
    assertEquals(2, w.remainingPq());
    assertEquals(1, w.pdcOf("B"));
    assertEquals(QueueState.WAITING, w.stateOf("B"));

    w.enqueue(t.msg("B", 1, 10));                      // WAITING → BLOCKED (DC −1)
    assertEquals("B", w.selectNext(0).tenantId());     // 두 번째 차입: PDC 소진
    assertEquals(-2, w.deficitOf("B"));
    assertEquals(0, w.pdcOf("B"));
    w.enqueue(t.msg("B", 1, 10));
    assertEquals("A", w.selectNext(0).tenantId());     // PDC 부족으로 거절
    assertEquals(1, w.diagnostics().get("borrowRejectedPdc"));
    for (int i = 0; i < 3; i++) {                     // A 의 DC 4 를 모두 사용 → A BLOCKED
      assertEquals("A", w.selectNext(0).tenantId());
    }
    assertEquals(1, w.round());
    assertEquals(List.of("B", "A"), w.blockedOrder());
    // 다음 선택에서 라운드 2 가 시작된다: B 는 −2 + 2 = 0 으로 여전히 부족해 BLOCKED 로 남고
    // (부채 이월, 즉시 보충 A11), A 는 0 + 4 로 ACTIVE. PQ = 3, PDC_B = 2 로 재설정되므로 B 가
    // 차입한다 — B 가 BLOCKED 로 남아 있었기 때문에만 가능한 결과다.
    assertEquals("B", w.selectNext(0).tenantId());
    assertEquals(2, w.round());
    assertEquals(-1, w.deficitOf("B"));
    assertEquals(2, w.remainingPq());
    assertEquals(1, w.pdcOf("B"));
    assertEquals(3, w.diagnostics().get("borrowSuccesses"));
    assertEquals(-2, w.diagnostics().get("minDeficit"));
    w.checkInvariants();
  }

  @Test
  void borrowingIsRejectedForInsufficientPqOrEqualDepartureOrZeroLambda() {
    for (int lambda : new int[] {0, 1_000}) {          // PQ = 0 (Ω=6 × 10% = 0.6 → 0)
      WdedfTestSupport s = new WdedfTestSupport();
      WdedfScheduler w = s.scheduler(quanta("A", 4, "B", 2), lambda);
      for (int i = 0; i < 4; i++) {
        w.enqueue(s.msg("A", 1, 100));
      }
      for (int i = 0; i < 3; i++) {
        w.enqueue(s.msg("B", 1, 10));
      }
      List<String> order = new ArrayList<>();
      while (!w.isEmpty()) {
        order.add(w.selectNext(0).tenantId());
      }
      assertEquals(List.of("B", "B", "A", "A", "A", "A", "B"), order);
      assertEquals(0, w.diagnostics().get("borrowSuccesses"));
      assertEquals(0, w.preemptiveQuantum());          // 정수 내림: Ω × Λ < 1
      assertTrue(w.diagnostics().get("borrowRejectedPq") > 0);
    }
    WdedfTestSupport s = new WdedfTestSupport();
    WdedfScheduler w = s.scheduler(quanta("A", 4, "B", 2), 5_000);
    // B 를 먼저 넣어 id 가 작게 한다: 출발 시각이 같으면 B 가 먼저 나가 DC 를 소진하고 BLOCKED 가
    // 된 뒤, 같은 출발 시각의 A head 와 비교된다 (엄격한 > 가 아니므로 차입 불가).
    for (int i = 0; i < 3; i++) {
      w.enqueue(s.msg("B", 1, 100));
    }
    for (int i = 0; i < 4; i++) {
      w.enqueue(s.msg("A", 1, 100));
    }
    assertEquals("B", w.selectNext(0).tenantId());
    assertEquals("B", w.selectNext(0).tenantId());
    assertEquals(QueueState.BLOCKED, w.stateOf("B"));
    assertEquals("A", w.selectNext(0).tenantId());
    assertEquals(0, w.diagnostics().get("borrowSuccesses"));
    assertTrue(w.diagnostics().get("borrowRejectedNotMoreUrgent") > 0);
  }

  @Test
  void tokenRotatesOverBlockedQueuesOnRejection() {
    WdedfScheduler w = t.scheduler(quanta("X", 1, "Y", 1, "Z", 1, "A", 10), 5_000);
    for (String q : List.of("X", "Y", "Z")) {
      w.enqueue(t.msg(q, 2, 500));                     // ω=1 < 2 → BLOCKED, 덜 급함
    }
    for (int i = 0; i < 10; i++) {
      w.enqueue(t.msg("A", 1, 100));
    }
    assertEquals(List.of("X", "Y", "Z"), w.blockedOrder());
    assertEquals(0, w.tokenIndex());
    w.selectNext(0);
    assertEquals(1, w.tokenIndex());
    w.selectNext(0);
    assertEquals(2, w.tokenIndex());
    w.selectNext(0);
    assertEquals(0, w.tokenIndex());                   // PT ≥ size → 0
    assertEquals(1, w.diagnostics().get("tokenWraps"));
    assertEquals(3, w.diagnostics().get("borrowRejectedNotMoreUrgent"));
  }

  @Test
  void blockedListIndexSemanticsAfterRemovalAndAppend() {
    BlockedList list = new BlockedList();
    WdedfQueue x = new WdedfQueue("X", 0, 1);
    WdedfQueue y = new WdedfQueue("Y", 1, 1);
    WdedfQueue z = new WdedfQueue("Z", 2, 1);
    WdedfQueue v = new WdedfQueue("V", 3, 1);
    list.append(x);
    list.append(y);
    list.append(z);
    list.advanceToken();
    assertEquals(y, list.tokenQueue());
    assertEquals(y, list.removeTokenQueue());          // PT 는 같은 인덱스 → Z
    assertEquals(z, list.tokenQueue());
    assertEquals(1, list.tokenIndex());
    assertEquals(z, list.removeTokenQueue());          // 끝을 넘음 → 0
    assertEquals(x, list.tokenQueue());
    list.append(v);
    assertEquals(List.of("X", "V"), list.keys());
    list.advanceToken();
    assertEquals(v, list.tokenQueue());
    list.unlink(v);                                    // token 이 가리키던 큐 제거 → 0
    assertEquals(x, list.tokenQueue());
    assertEquals(1, list.size());
  }

  @Test
  void paperIdleResetForgivesBorrowingDebtWhileRetainDebtKeepsIt() {
    for (IdleDebtPolicy policy : IdleDebtPolicy.values()) {
      WdedfTestSupport s = new WdedfTestSupport();
      WdedfScheduler w = s.scheduler(quanta("A", 4, "B", 2), 5_000, policy);
      for (int i = 0; i < 6; i++) {
        w.enqueue(s.msg("A", 1, 100));
      }
      for (int i = 0; i < 3; i++) {
        w.enqueue(s.msg("B", 1, 10));
      }
      w.selectNext(0);
      w.selectNext(0);
      w.selectNext(0);                                 // B 차입 후 비어 WAITING, DC −1
      assertEquals(-1, w.deficitOf("B"));
      while (w.round() == 1) {
        w.selectNext(0);
      }
      assertEquals(QueueState.INACTIVE, w.stateOf("B"));
      w.enqueue(s.msg("B", 1, 10));
      if (policy == IdleDebtPolicy.PAPER_RESET) {
        assertEquals(1, w.diagnostics().get("debtForgiven"));
        assertEquals(2, w.deficitOf("B"));             // DC = ω: 부채 탕감
      } else {
        assertEquals(0, w.diagnostics().get("debtForgiven"));
        assertEquals(1, w.deficitOf("B"));             // DC = −1 + ω
      }
      w.checkInvariants();
    }
  }

  @Test
  void rejectsInvalidConfigurationAndUnknownQueues() {
    assertThrows(IllegalArgumentException.class, () -> t.scheduler(quanta("A", 0), 0));
    assertThrows(IllegalArgumentException.class, () -> t.scheduler(Map.of(), 0));
    assertThrows(IllegalArgumentException.class,
        () -> new WdedfConfig("x", 10_001, IdleDebtPolicy.PAPER_RESET));
    assertThrows(IllegalArgumentException.class,
        () -> new WdedfConfig("x", -1, IdleDebtPolicy.PAPER_RESET));
    WdedfScheduler w = t.scheduler(quanta("A", 1), 0);
    assertThrows(IllegalArgumentException.class, () -> w.enqueue(t.msg("Z", 1, 0)));
  }

  @Test
  void randomizedDifferentialAgainstReferenceWithInvariantsAndFifoPerQueue() {
    for (long seed = 1; seed <= 60; seed++) {
      Random random = new Random(seed);
      int queueCount = 1 + random.nextInt(6);
      Map<String, Long> q = new java.util.LinkedHashMap<>();
      for (int i = 0; i < queueCount; i++) {
        q.put("Q" + i, 1L + random.nextInt(seed % 4 == 0 ? 3 : 8));
      }
      int lambda = new int[] {0, 500, 2_000, 5_000, 10_000}[random.nextInt(5)];
      IdleDebtPolicy debt = random.nextBoolean()
          ? IdleDebtPolicy.PAPER_RESET : IdleDebtPolicy.RETAIN_DEBT;
      int maxCost = seed % 5 == 0 ? 40 : 6;
      WdedfTestSupport s = new WdedfTestSupport();
      WdedfScheduler fast = s.scheduler(q, lambda, debt);
      ReferenceWdedf reference =
          new ReferenceWdedf(q, s.departures, Message::tenantId, lambda, debt);
      Map<String, Long> lastIdPerQueue = new HashMap<>();
      for (int step = 0; step < 2_500; step++) {
        if (fast.isEmpty() || random.nextInt(10) < 4) {
          Message m = s.msg("Q" + random.nextInt(queueCount), 1 + random.nextInt(maxCost),
              random.nextInt(1_000));
          fast.enqueue(m);
          reference.enqueue(m);
        } else {
          Message expected = reference.selectNext(0);
          Message actual = fast.selectNext(0);
          assertEquals(expected, actual, "seed " + seed + " step " + step);
          Long previous = lastIdPerQueue.put(actual.tenantId(), actual.id());
          assertTrue(previous == null || previous < actual.id(), "FIFO within queue");
          for (String key : q.keySet()) {
            assertEquals(reference.deficitOf(key), fast.deficitOf(key), "deficit " + key);
          }
        }
        fast.checkInvariants();
      }
    }
  }
}
