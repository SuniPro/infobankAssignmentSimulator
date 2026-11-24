package com.raphael.scheduling;

import com.raphael.domain.Message;
import com.raphael.scheduling.wdedf.DepartureTimePolicy;
import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 진단용 전역 메시지 단위 EDF. 모든 대기 메시지를 하나의 heap 에 넣고 출발 기준 시각이 가장
 * 이른 메시지를 보낸다. 테넌트 큐, deficit, 가중치가 없으므로 deadline 순서의 효과만 분리해
 * 보여 준다 (WDEDF 와 혼동하지 말 것: WDEDF 는 큐 head 만 비교한다).
 *
 * <p>enqueue·selectNext 모두 O(log M) (M 은 대기 메시지 수). 메시지마다 heap 항목 하나를 할당한다.
 */
public final class EdfScheduler implements SchedulingPolicy {

  private record Entry(long departure, Message message) {
  }

  private static final Comparator<Entry> ORDER = (left, right) -> {
    int c = Long.compare(left.departure, right.departure);
    if (c != 0) {
      return c;
    }
    c = Long.compare(left.message.arrivalTime(), right.message.arrivalTime());
    return c != 0 ? c : Long.compare(left.message.id(), right.message.id());
  };

  private final String name;
  private final DepartureTimePolicy departurePolicy;
  private final PriorityQueue<Entry> heap = new PriorityQueue<>(ORDER);
  private long heapOperations;

  public EdfScheduler(String name, DepartureTimePolicy departurePolicy) {
    this.name = name;
    this.departurePolicy = departurePolicy;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public void enqueue(Message message) {
    heap.offer(new Entry(departurePolicy.departureTime(message), message));
    heapOperations++;
  }

  @Override
  public Message selectNext(long now) {
    Entry entry = heap.poll();
    if (entry == null) {
      throw new IllegalStateException("selectNext called on an empty EDF scheduler");
    }
    heapOperations++;
    return entry.message;
  }

  @Override
  public boolean isEmpty() {
    return heap.isEmpty();
  }

  @Override
  public long pendingCount() {
    return heap.size();
  }

  @Override
  public long operationCount() {
    return heapOperations;
  }

  @Override
  public Map<String, Long> diagnostics() {
    return Map.of("heapOperations", heapOperations);
  }
}
