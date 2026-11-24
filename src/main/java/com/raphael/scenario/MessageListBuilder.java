package com.raphael.scenario;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 결정론적 메시지 목록 작성기. 추가된 메시지를 (도착 시각, 추가 순서) 로 안정 정렬한 뒤 id 를
 * 0 부터 순서대로 부여한다. 따라서 id 순서는 도착 순서와 일치하고, 같은 입력은 항상 같은 목록을
 * 만든다.
 */
final class MessageListBuilder {

  private record Draft(long order, String tenant, MessageType type, long arrival, long deadline,
      int cost) {
  }

  private final List<Draft> drafts = new ArrayList<>();

  MessageListBuilder add(String tenant, MessageType type, long arrival, long deadline, int cost) {
    drafts.add(new Draft(drafts.size(), tenant, type, arrival, deadline, cost));
    return this;
  }

  int size() {
    return drafts.size();
  }

  List<Message> build() {
    List<Draft> sorted = new ArrayList<>(drafts);
    sorted.sort(Comparator.comparingLong(Draft::arrival).thenComparingLong(Draft::order));
    List<Message> messages = new ArrayList<>(sorted.size());
    long id = 0;
    for (Draft d : sorted) {
      messages.add(new Message(id++, d.tenant, d.type, d.arrival, d.deadline, d.cost));
    }
    return messages;
  }

  /** 평균 {@code meanGapMicros} 의 지수 분포 간격 (Poisson 도착). μs 로 내림하며 최소 1us. */
  static long exponentialGap(Random random, double meanGapMicros) {
    return Math.max(1L, (long) (-Math.log(1.0 - random.nextDouble()) * meanGapMicros));
  }
}
