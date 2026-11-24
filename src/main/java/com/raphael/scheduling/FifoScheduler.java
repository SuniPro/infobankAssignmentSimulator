package com.raphael.scheduling;

import com.raphael.domain.Message;
import java.util.ArrayDeque;

/**
 * 모든 tenant가 공유하는 단일 FIFO 큐(실험 E0 baseline).
 *
 * <p>tenant 식별, weight, 메시지 cost를 모두 무시한다. {@link #operationCount()}는 선택 1회당
 * 1 step으로 센다. enqueue와 selectNext는 O(1)이다.
 */
public final class FifoScheduler implements SchedulingPolicy {

  private final ArrayDeque<Message> queue = new ArrayDeque<>();
  private long operations;

  @Override
  public String name() {
    return "FIFO";
  }

  @Override
  public void enqueue(Message message) {
    queue.addLast(message);
  }

  @Override
  public Message selectNext(long now) {
    Message next = queue.pollFirst();
    if (next == null) {
      throw new IllegalStateException("selectNext called on an empty FIFO scheduler");
    }
    operations++;
    return next;
  }

  @Override
  public boolean isEmpty() {
    return queue.isEmpty();
  }

  @Override
  public long pendingCount() {
    return queue.size();
  }

  @Override
  public long operationCount() {
    return operations;
  }
}
