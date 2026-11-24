package com.raphael.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.domain.Tenant;
import com.raphael.metrics.MetricsCollector;
import com.raphael.metrics.SimulationResult;
import com.raphael.provider.ProviderSimulator;
import com.raphael.scheduling.FifoScheduler;
import com.raphael.scheduling.SchedulingPolicy;
import com.raphael.support.TraceRecorder;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import org.junit.jupiter.api.Test;

class SimulationEngineTest {

  private static final List<Tenant> TENANTS = List.of(new Tenant("A", 1), new Tenant("B", 1));

  private static Message msg(long id, String tenant, long arrival) {
    return new Message(id, tenant, MessageType.ADVERTISEMENT, arrival, arrival + 10_000_000, 1);
  }

  private static SimulationEngine engine(SchedulingPolicy policy, int tps, long delay,
      TraceRecorder trace) {
    return new SimulationEngine(policy, new ProviderSimulator(tps, delay),
        new MetricsCollector(TENANTS, tps, Optional.empty()), trace, false);
  }

  @Test
  void eventOrderIsTimestampThenTypeThenSequence() {
    PriorityQueue<SimulationEvent> queue = new PriorityQueue<>(SimulationEvent.ORDER);
    queue.add(new SimulationEvent(5, 0, EventType.DISPATCH_READY, null));
    queue.add(new SimulationEvent(5, 1, EventType.ARRIVAL, null));
    queue.add(new SimulationEvent(5, 2, EventType.DELIVERED, null));
    queue.add(new SimulationEvent(5, 3, EventType.ARRIVAL, null));
    queue.add(new SimulationEvent(4, 4, EventType.DISPATCH_READY, null));
    List<Long> order = java.util.stream.Stream.generate(queue::poll).limit(5)
        .map(SimulationEvent::sequence).toList();
    assertEquals(List.of(4L, 2L, 1L, 3L, 0L), order);
  }

  @Test
  void simultaneousArrivalsAreDispatchedInSchedulingOrder() {
    TraceRecorder trace = new TraceRecorder();
    SimulationEngine engine = engine(new FifoScheduler(), 10, 0, trace);
    for (int i = 0; i < 20; i++) {
      engine.scheduleArrival(msg(i, i % 2 == 0 ? "A" : "B", 0));
    }
    engine.run();
    assertEquals(java.util.stream.LongStream.range(0, 20).boxed().toList(),
        trace.dispatchedIds());
  }

  @Test
  void arrivalAtSlotTimeIsEligibleForThatSlot() {
    // 10 TPS → 슬롯은 0, 100ms, 200ms ... 에 열린다. 100ms 에 도착한 메시지는 같은 시각에
    // 열리는 슬롯을 사용해야 한다 (ARRIVAL 이 DISPATCH_READY 보다 먼저 처리되기 때문).
    TraceRecorder trace = new TraceRecorder();
    SimulationEngine engine = engine(new FifoScheduler(), 10, 0, trace);
    engine.scheduleArrival(msg(0, "A", 0));
    engine.scheduleArrival(msg(1, "A", 0));
    engine.scheduleArrival(msg(2, "B", 200_000));
    engine.run();
    assertEquals(List.of(0L, 100_000L, 200_000L),
        trace.dispatches.stream().map(TraceRecorder.Step::time).toList());
  }

  @Test
  void providerIdleArrivalIsDispatchedImmediately() {
    // 작업 보존성: provider 가 유휴 상태일 때 도착한 메시지는 지연 없이 바로 발송된다.
    TraceRecorder trace = new TraceRecorder();
    SimulationEngine engine = engine(new FifoScheduler(), 1, 50, trace);
    engine.scheduleArrival(msg(0, "A", 0));
    engine.scheduleArrival(msg(1, "A", 7_500_000));
    SimulationResult result = engine.run();
    assertEquals(List.of(0L, 7_500_000L),
        trace.dispatches.stream().map(TraceRecorder.Step::time).toList());
    assertEquals(7_500_050, result.virtualElapsed());
  }

  @Test
  void clockNeverMovesBackwardsAcrossObservedEvents() {
    TraceRecorder trace = new TraceRecorder();
    SimulationEngine engine = engine(new FifoScheduler(), 3, 123_457, trace);
    for (int i = 0; i < 50; i++) {
      engine.scheduleArrival(msg(i, "A", (i * 7_919L) % 2_000_000));
    }
    engine.run();
    List<Long> all = new java.util.ArrayList<>();
    // 관측 콜백은 발생 순서대로 기록되므로, 병합 순서를 재구성하지 않고 각 목록의 단조성을 확인한다.
    for (List<TraceRecorder.Step> steps : List.of(trace.arrivals, trace.dispatches,
        trace.deliveries)) {
      List<Long> times = steps.stream().map(TraceRecorder.Step::time).toList();
      assertEquals(times.stream().sorted(Comparator.naturalOrder()).toList(), times);
      all.addAll(times);
    }
    assertEquals(150, all.size());
  }

  @Test
  void engineIsSingleUse() {
    SimulationEngine engine = engine(new FifoScheduler(), 10, 0, new TraceRecorder());
    engine.scheduleArrival(msg(0, "A", 0));
    engine.run();
    assertThrows(IllegalStateException.class, engine::run);
    assertThrows(IllegalStateException.class, () -> engine.scheduleArrival(msg(1, "A", 0)));
  }

  @Test
  void emptyScenarioEndsAtTimeZero() {
    SimulationResult result = engine(new FifoScheduler(), 10, 0, new TraceRecorder()).run();
    assertEquals(0, result.virtualElapsed());
    assertTrue(result.isConserved());
  }

  @Test
  void policyThatBreaksWorkConservingContractFailsFastInsteadOfLooping() {
    // 메시지를 보유하면서도 null 을 반환하는 정책 → 엔진은 같은 시각 재시도 루프 대신 즉시 실패해야 한다.
    SchedulingPolicy broken = new SchedulingPolicy() {
      private long pending;

      @Override
      public String name() {
        return "broken";
      }

      @Override
      public void enqueue(Message message) {
        pending++;
      }

      @Override
      public Message selectNext(long now) {
        return null;
      }

      @Override
      public boolean isEmpty() {
        return pending == 0;
      }

      @Override
      public long pendingCount() {
        return pending;
      }

      @Override
      public long operationCount() {
        return 0;
      }
    };
    SimulationEngine engine = engine(broken, 10, 0, new TraceRecorder());
    engine.scheduleArrival(msg(0, "A", 0));
    IllegalStateException error = assertThrows(IllegalStateException.class, engine::run);
    assertTrue(error.getMessage().contains("returned no message"));
  }
}
