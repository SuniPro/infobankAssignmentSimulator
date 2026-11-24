package com.raphael.scheduling;

import com.raphael.domain.Message;
import java.util.ArrayDeque;

/**
 * tenant별 FIFO 큐와 DWRR 상태. 가변 객체이며 {@link DwrrScheduler}가 독점적으로 소유한다.
 *
 * <p>스케줄러가 유지하는 불변식:
 * <ul>
 *   <li>{@code active}가 {@code true}인 것은 큐가 비어 있지 않은 것과 동치이며, 이는 다시
 *       tenant가 active ring에 (정확히 한 번) 들어 있는 것과 동치이다.</li>
 *   <li>tenant가 비활성 상태이면 {@code deficit}은 항상 0이다.</li>
 *   <li>{@code selectNext} 호출 바깥에서, 현재 서비스 방문 중이 아닌 활성 tenant는
 *       {@code deficit < head.cost}를 만족한다. 그렇지 않다면 여전히 발송 가능한 상태일 것이다.</li>
 * </ul>
 */
final class TenantQueue {

  private final String tenantId;
  private final long quantum;
  private final ArrayDeque<Message> messages = new ArrayDeque<>();

  long deficit;
  boolean active;

  TenantQueue(String tenantId, long quantum) {
    this.tenantId = tenantId;
    this.quantum = quantum;
  }

  String tenantId() {
    return tenantId;
  }

  long quantum() {
    return quantum;
  }

  void add(Message message) {
    messages.addLast(message);
  }

  Message head() {
    return messages.peekFirst();
  }

  Message removeHead() {
    return messages.pollFirst();
  }

  boolean isEmpty() {
    return messages.isEmpty();
  }

  int size() {
    return messages.size();
  }
}
