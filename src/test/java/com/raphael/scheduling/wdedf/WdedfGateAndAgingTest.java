package com.raphael.scheduling.wdedf;

import static com.raphael.scheduling.wdedf.WdedfTestSupport.quanta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.scheduling.DispatchGate;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** v0.4 WDEDF 확장: 예산 게이트 (주차) 와 aging 순서. */
class WdedfGateAndAgingTest {

  @Test
  void openGateBehavesExactlyLikeNoGate() {
    for (long seed = 1; seed <= 20; seed++) {
      Random random = new Random(seed);
      WdedfTestSupport s = new WdedfTestSupport();
      Map<String, Long> q = quanta("A", 1 + random.nextInt(4), "B", 1 + random.nextInt(4),
          "C", 2);
      WdedfScheduler plain = s.scheduler(q, 2_000);
      WdedfScheduler gated = new WdedfScheduler(q, s.departures, Message::tenantId,
          new WdedfConfig("gated", 2_000, IdleDebtPolicy.PAPER_RESET), DispatchGate.OPEN);
      for (int step = 0; step < 1_500; step++) {
        if (plain.isEmpty() || random.nextInt(10) < 4) {
          Message m = s.msg(String.valueOf("ABC".charAt(random.nextInt(3))),
              1 + random.nextInt(6), random.nextInt(500));
          plain.enqueue(m);
          gated.enqueue(m);
        } else {
          assertEquals(0, gated.nextEligibleTime(0));
          assertEquals(plain.selectNext(0), gated.selectNext(0), "seed " + seed);
        }
        gated.checkInvariants();
      }
    }
  }

  /** 큐 A 의 head 는 시각 100 까지 막는 게이트. */
  private static final class BlockA implements DispatchGate {
    int consumed;

    @Override
    public long reopenTime(Message message, long now) {
      return message.tenantId().equals("A") ? Math.max(now, 100) : now;
    }

    @Override
    public void consume(Message message, long now) {
      consumed++;
    }
  }

  @Test
  void gatedQueueIsParkedWithoutDequeueAndReturnsWhenGateOpens() {
    WdedfTestSupport s = new WdedfTestSupport();
    WdedfScheduler w = new WdedfScheduler(quanta("A", 2, "B", 2), s.departures,
        Message::tenantId, new WdedfConfig("g", 0, IdleDebtPolicy.PAPER_RESET), new BlockA());
    Message a1 = s.msg("A", 1, 0);                           // A 가 더 급하지만 막혀 있다
    Message b1 = s.msg("B", 1, 50);
    w.enqueue(a1);
    w.enqueue(b1);
    assertEquals(0, w.nextEligibleTime(0));
    assertEquals(b1, w.selectNext(0));
    assertEquals(100, w.nextEligibleTime(1));                // A 만 남음: 재개 시각
    assertEquals(100, w.nextEligibleTime(1));                // 멱등
    assertEquals(1, w.pendingCount());
    assertEquals(2, w.deficitOf("A"));                       // deficit 소비 없음
    w.checkInvariants();
    assertEquals(100, w.nextEligibleTime(100));
    assertEquals(a1, w.selectNext(100));
    assertTrue(w.diagnostics().get("budgetParks") >= 1);
  }

  @Test
  void agingCapsTheOrderingKeyWithoutChangingTheDeadline() {
    DepartureTimePolicy business = new BusinessDepartureTimePolicy(0);
    AgingDeparturePolicy aging = new AgingDeparturePolicy(business, 10_000_000);
    Message old = new Message(1, "A", MessageType.ADVERTISEMENT, 0, 1_000_000_000L, 1);
    Message urgent = new Message(2, "B", MessageType.OTP, 50_000_000, 70_000_000, 1);
    for (boolean withAging : new boolean[] {false, true}) {
      WdedfScheduler w = new WdedfScheduler(Map.of("A", 10L, "B", 10L),
          withAging ? aging : business, Message::tenantId,
          new WdedfConfig("w", 0, IdleDebtPolicy.PAPER_RESET));
      w.enqueue(old);
      w.enqueue(urgent);
      // aging 없음: B (70 s) 가 먼저. aging: A 의 키 = min(1000 s, 0 + 10 s) = 10 s < B 의 60 s.
      assertEquals(withAging ? old : urgent, w.selectNext(50_000_000));
    }
    assertEquals(10_000_000, aging.departureTime(old));
    assertEquals(60_000_000, aging.departureTime(urgent));
    assertEquals(1_000_000_000L, old.deadline());
    assertTrue(aging.isPromoted(old, 50_000_000));
    assertTrue(!aging.isPromoted(old, 5_000_000));
    assertTrue(aging.isPromoted(urgent, 69_000_000));       // 키 60 s 도 aging 으로 정해짐
    assertTrue(!aging.isPromoted(urgent, 55_000_000));      // 아직 threshold 를 넘기지 않음
    Message tight = new Message(3, "B", MessageType.OTP, 0, 5_000_000, 1);
    assertTrue(!aging.isPromoted(tight, 50_000_000));       // 기본 키 (5 s) 가 더 이르면 승격 아님
  }
}
