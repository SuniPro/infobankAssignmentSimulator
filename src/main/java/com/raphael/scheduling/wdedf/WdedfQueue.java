package com.raphael.scheduling.wdedf;

import com.raphael.domain.Message;
import java.util.Arrays;

/**
 * WDEDF 큐 하나의 상태. {@link WdedfScheduler} 만 소유하고 변경한다.
 *
 * <p>메시지와 enqueue 시점에 계산한 출발 시각을 원형 배열 두 개에 나란히 저장하므로 메시지당
 * 추가 객체 할당이 없다. blocked 목록의 링크({@link #previousBlocked}, {@link #nextBlocked})를
 * 큐 객체에 내장하여 blocked 목록 연산을 O(1) 로 만든다.
 */
final class WdedfQueue {

  final String key;
  /** 생성 순서. 결정론적 tie-breaker 의 마지막 키. */
  final int index;
  /** 식 (1) 의 ω_i. */
  final long quantum;

  QueueState state = QueueState.INACTIVE;
  /** 일반 deficit counter DC_i. 차입 후 음수가 될 수 있다. */
  long deficit;
  /** 선점 deficit counter PDC_i. {@link #pdcRound} 가 현재 라운드가 아니면 ω_i 로 간주한다. */
  long pdc;
  long pdcRound = -1;
  /** quantum 이 마지막으로 적용된 라운드. 라운드당 1 회 적용을 강제한다. */
  long lastQuantumRound = -1;
  /** waiting 목록 중복 등록 방지 플래그. */
  boolean waitingListed;
  /** v0.4: 예산 게이트 때문에 heap 대신 주차 목록에 있는 ACTIVE 큐. */
  boolean parked;

  WdedfQueue previousBlocked;
  WdedfQueue nextBlocked;

  private Message[] messages = new Message[4];
  private long[] departures = new long[4];
  private int head;
  private int size;

  WdedfQueue(String key, int index, long quantum) {
    this.key = key;
    this.index = index;
    this.quantum = quantum;
  }

  void push(Message message, long departure) {
    if (size == messages.length) {
      grow();
    }
    int tail = (head + size) % messages.length;
    messages[tail] = message;
    departures[tail] = departure;
    size++;
  }

  Message pop() {
    Message message = messages[head];
    messages[head] = null;
    head = (head + 1) % messages.length;
    size--;
    return message;
  }

  Message headMessage() {
    return messages[head];
  }

  long headDeparture() {
    return departures[head];
  }

  int headCost() {
    return messages[head].cost();
  }

  boolean isEmpty() {
    return size == 0;
  }

  int size() {
    return size;
  }

  private void grow() {
    Message[] grownMessages = new Message[messages.length * 2];
    long[] grownDepartures = new long[messages.length * 2];
    for (int i = 0; i < size; i++) {
      grownMessages[i] = messages[(head + i) % messages.length];
      grownDepartures[i] = departures[(head + i) % messages.length];
    }
    Arrays.fill(messages, null);
    messages = grownMessages;
    departures = grownDepartures;
    head = 0;
  }
}
