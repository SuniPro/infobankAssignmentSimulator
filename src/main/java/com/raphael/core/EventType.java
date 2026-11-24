package com.raphael.core;

/**
 * 시뮬레이션 이벤트 유형.
 *
 * <p>선언 순서에는 의미가 있다. 같은 timestamp를 가진 이벤트 간의 tie-breaker로 쓰이기 때문이다.
 * 동일한 가상 시각에서 엔진은 배달(delivery)을 먼저, 그다음 도착(arrival), 마지막으로 발송 결정을
 * 처리한다. 도착을 {@link #DISPATCH_READY}보다 먼저 처리하므로, 시각 {@code t}에 도착한 메시지는
 * 같은 시각 {@code t}에 열리는 provider 슬롯을 사용할 수 있다.
 * 이 순서는 v0.1에서 그대로 물려받은 것이며, 바꾸면 모든 baseline 결과가 달라진다.
 */
public enum EventType {
  DELIVERED,
  ARRIVAL,
  DISPATCH_READY,
  /** v0.4: 샤드(worker) 장애. 같은 시각의 dispatch 다음에 처리된다. */
  RESOURCE_FAILURE,
  /** v0.4: 샤드(worker) 복구. 처리 후 dispatch 를 다시 예약한다. */
  RESOURCE_RECOVERY
}
