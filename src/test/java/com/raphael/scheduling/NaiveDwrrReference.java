package com.raphael.scheduling;

import com.raphael.domain.Message;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * 교과서적인 DWRR 루프 (유휴 라운드 압축 없음). 테스트 전용 기준 구현으로,
 * {@link DwrrScheduler} 의 fast-forward 가 발송 순서를 바꾸지 않음을 차등 검증하는 데 사용한다.
 */
final class NaiveDwrrReference implements SchedulingPolicy {

  private final Map<String, Integer> weights;
  private final int baseQuantum;
  private final Map<String, ArrayDeque<Message>> queues = new HashMap<>();
  private final Map<String, Long> deficits = new HashMap<>();
  private final ArrayDeque<String> ring = new ArrayDeque<>();
  private boolean inVisit;
  private long pending;

  NaiveDwrrReference(Map<String, Integer> weights, int baseQuantum) {
    this.weights = weights;
    this.baseQuantum = baseQuantum;
  }

  @Override
  public String name() {
    return "naive-DWRR";
  }

  @Override
  public void enqueue(Message message) {
    ArrayDeque<Message> queue =
        queues.computeIfAbsent(message.tenantId(), id -> new ArrayDeque<>());
    if (queue.isEmpty()) {
      ring.addLast(message.tenantId());
      deficits.put(message.tenantId(), 0L);
    }
    queue.addLast(message);
    pending++;
  }

  @Override
  public Message selectNext(long now) {
    while (true) {
      String tenant = ring.peekFirst();
      if (!inVisit) {
        deficits.merge(tenant, (long) baseQuantum * weights.get(tenant), Long::sum);
        inVisit = true;
      }
      ArrayDeque<Message> queue = queues.get(tenant);
      if (queue.peekFirst().cost() <= deficits.get(tenant)) {
        Message head = queue.pollFirst();
        deficits.merge(tenant, (long) -head.cost(), Long::sum);
        pending--;
        if (queue.isEmpty()) {
          ring.pollFirst();
          deficits.put(tenant, 0L);
          inVisit = false;
        }
        return head;
      }
      ring.addLast(ring.pollFirst());
      inVisit = false;
    }
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

  long deficitOf(String tenant) {
    return deficits.getOrDefault(tenant, 0L);
  }
}
