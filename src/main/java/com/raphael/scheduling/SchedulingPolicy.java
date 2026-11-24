package com.raphael.scheduling;

import com.raphael.domain.Message;

/**
 * 대기 중인 메시지 중 다음에 provider로 넘길 메시지를 결정한다.
 *
 * <p>정책은 메시지의 순서만 정한다. 가상 시간, provider 용량, 이벤트 실행, 지표 보고는 제어하지
 * 않으며, 엔진은 시뮬레이션 스레드에서만 정책을 호출한다.
 *
 * <p><b>Work-conserving 계약.</b> {@link #isEmpty()}가 {@code false}인 동안에는
 * {@link #selectNext(long)}가 유한한 step 안에 대기 메시지를 반환해야 한다. 정책은 메시지를
 * 보유한 상태에서 "이번에는 보낼 것 없음"이라고 응답해서는 안 된다. 그렇게 되면 엔진은
 * provider를 놀리거나(용량 낭비) 같은 timestamp에서 재시도(무한 루프)해야 하기 때문이다.
 */
public interface SchedulingPolicy {

  /** 보고서에 쓰이는 짧은 정책 이름. */
  String name();

  /** 새로 도착한 메시지를 받아들인다. */
  void enqueue(Message message);

  /**
   * 다음에 발송할 메시지를 제거하고 반환한다.
   *
   * @param now 현재 가상 시각. FIFO와 DWRR은 사용하지 않으며, 시간 인지형 정책을 위해 예약되어 있다
   * @return 선택된 메시지. 정책이 비어 있지 않으면 절대 {@code null}이 아니다
   * @throws IllegalStateException 정책이 비어 있는 경우
   */
  Message selectNext(long now);

  boolean isEmpty();

  long pendingCount();

  /**
   * 지금까지 수행한 기본 스케줄링 step 수로, 정의는 정책마다 다르다. 하드웨어와 무관한
   * 스케줄링 작업량 척도로 사용한다. 구체적인 정의는 각 구현을 참고한다.
   */
  long operationCount();
}
