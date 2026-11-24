package com.raphael.scheduling.wdedf;

import java.util.ArrayList;
import java.util.List;

/**
 * 논문의 Blocked List 와 선점 token PT (Algorithm 2).
 *
 * <p>큐 객체에 내장된 이중 연결 리스트이며, PT 는 인덱스 대신 노드를 가리킨다. {@code token == null}
 * 은 인덱스 0 (목록의 첫 큐)을 뜻한다. 인덱스 의미와의 대응 (명세 해석 A13):
 * <ul>
 *   <li>{@code PT = 0} → {@link #resetToken()}</li>
 *   <li>{@code PT++; if PT ≥ size then PT = 0} → {@link #advanceToken()}</li>
 *   <li>{@code Remove_Packet(PT)} 후 PT 는 같은 인덱스, 즉 다음 큐를 가리키며 끝을 넘으면 0
 *       → {@link #removeTokenQueue()}</li>
 *   <li>{@code Add_Block_List} 는 꼬리에 추가하며 PT 의 인덱스 의미를 바꾸지 않는다.</li>
 * </ul>
 * 모든 연산은 O(1) 이다 (라운드 전이의 순회 제외).
 */
final class BlockedList {

  private WdedfQueue first;
  private WdedfQueue last;
  private WdedfQueue token;
  private int size;

  private long tokenAdvances;
  private long tokenWraps;

  boolean isEmpty() {
    return size == 0;
  }

  int size() {
    return size;
  }

  WdedfQueue first() {
    return first;
  }

  void append(WdedfQueue queue) {
    queue.previousBlocked = last;
    queue.nextBlocked = null;
    if (last == null) {
      first = queue;
    } else {
      last.nextBlocked = queue;
    }
    last = queue;
    size++;
  }

  /** 목록에서 {@code queue} 를 뗀다. token 이 가리키던 큐라면 token 은 다음 큐(없으면 0)로 간다. */
  void unlink(WdedfQueue queue) {
    if (token == queue) {
      token = queue.nextBlocked;
    }
    if (queue.previousBlocked == null) {
      first = queue.nextBlocked;
    } else {
      queue.previousBlocked.nextBlocked = queue.nextBlocked;
    }
    if (queue.nextBlocked == null) {
      last = queue.previousBlocked;
    } else {
      queue.nextBlocked.previousBlocked = queue.previousBlocked;
    }
    queue.previousBlocked = null;
    queue.nextBlocked = null;
    size--;
  }

  /** PT 가 가리키는 큐 (목록이 비어 있지 않아야 한다). */
  WdedfQueue tokenQueue() {
    return token == null ? first : token;
  }

  /** PT 위치의 큐를 제거해 돌려준다. PT 는 같은 인덱스(다음 큐)를 가리킨다. */
  WdedfQueue removeTokenQueue() {
    WdedfQueue queue = tokenQueue();
    WdedfQueue next = queue.nextBlocked;
    unlink(queue);
    token = next;
    if (next == null) {
      tokenWraps++;
    }
    return queue;
  }

  /** {@code PT++}, 끝을 넘으면 0. */
  void advanceToken() {
    tokenAdvances++;
    token = tokenQueue().nextBlocked;
    if (token == null) {
      tokenWraps++;
    }
  }

  void resetToken() {
    token = null;
  }

  /** 현재 PT 의 0 기반 인덱스 (테스트·진단용, O(B)). */
  int tokenIndex() {
    int index = 0;
    for (WdedfQueue q = first; q != null; q = q.nextBlocked) {
      if (q == tokenQueue()) {
        return index;
      }
      index++;
    }
    return 0;
  }

  List<String> keys() {
    List<String> keys = new ArrayList<>(size);
    for (WdedfQueue q = first; q != null; q = q.nextBlocked) {
      keys.add(q.key);
    }
    return keys;
  }

  long tokenAdvances() {
    return tokenAdvances;
  }

  long tokenWraps() {
    return tokenWraps;
  }
}
