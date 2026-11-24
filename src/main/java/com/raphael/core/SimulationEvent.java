package com.raphael.core;

import com.raphael.domain.Message;
import java.util.Comparator;

/**
 * 불변 시뮬레이션 이벤트.
 *
 * @param timestamp 이벤트가 발생하는 가상 시각(마이크로초)
 * @param sequence 엔진이 부여하는 엄격히 증가하는 삽입 번호. 최종 tie-breaker이다
 * @param type 이벤트 유형. 두 번째 정렬 키이다({@link EventType} 참고)
 * @param message 해당 메시지. {@link EventType#DISPATCH_READY}·자원 이벤트의 경우 {@code null}
 * @param resource 자원 이벤트의 샤드 번호, 그 외에는 -1
 */
public record SimulationEvent(
    long timestamp, long sequence, EventType type, Message message, int resource) {

  /** 메시지 이벤트용 생성자 (자원 번호 없음). */
  public SimulationEvent(long timestamp, long sequence, EventType type, Message message) {
    this(timestamp, sequence, type, message, -1);
  }

  /**
   * 전순서(total order)이자 결정론적인 이벤트 순서: timestamp, 이벤트 유형, 삽입 sequence 순으로
   * 비교한다. sequence 번호가 유일하므로 두 이벤트가 같다고 비교되는 경우는 없다.
   */
  public static final Comparator<SimulationEvent> ORDER = (left, right) -> {
    int byTime = Long.compare(left.timestamp, right.timestamp);
    if (byTime != 0) {
      return byTime;
    }
    int byType = left.type.compareTo(right.type);
    if (byType != 0) {
      return byType;
    }
    return Long.compare(left.sequence, right.sequence);
  };
}
