package com.raphael.scheduling.wdedf;

import com.raphael.domain.Message;

/**
 * WDEDF 가 큐 head 를 정렬하는 기준 시각(가상 μs)을 메시지마다 계산한다.
 *
 * <p>값은 enqueue 시점에 알 수 있는 정보로만 계산되어야 하며, 같은 메시지에 대해 항상 같은 값을
 * 돌려줘야 한다. 스케줄러는 enqueue 시점에 한 번 계산해 큐 원소와 함께 저장한다.
 */
public interface DepartureTimePolicy {

  /** 정책 이름 (보고용). */
  String name();

  /** {@code message} 의 기준 출발 시각 (가상 μs). */
  long departureTime(Message message);
}
