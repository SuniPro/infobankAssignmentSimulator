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

  /**
   * v0.4: 지금 선택 가능한 메시지가 있으면 {@code now}, 없으면 선택 가능성이 바뀔 수 있는 가장 이른
   * 미래 시각 (예: 예산 보충, 샤드 슬롯 해제), 외부 사건 (도착, 자원 복구) 없이는 바뀌지 않으면
   * {@link Long#MAX_VALUE} 를 돌려준다. 반환값이 {@code now} 이면 바로 이어지는 {@link #selectNext}
   * 는 반드시 메시지를 돌려줘야 한다. 같은 {@code now} 로 반복 호출해도 결과가 같아야 하며 (멱등),
   * 엔진은 {@code now} 를 감소시키지 않는다. 메시지를 꺼내거나 deficit 을 소비해서는 안 된다.
   *
   * <p>기본 구현 (FIFO, DWRR, 게이트 없는 WDEDF, EDF): 대기 메시지가 있으면 항상 선택 가능.
   */
  default long nextEligibleTime(long now) {
    return isEmpty() ? Long.MAX_VALUE : now;
  }

  /**
   * 정책별 알고리즘 진단 계수 (예: 라운드 수, heap 연산 수, 차입 횟수). 결정론적이어야 하며
   * CPU 명령 수가 아니다. 기본값은 비어 있다. 반환 순서가 보고 순서가 된다.
   */
  default java.util.Map<String, Long> diagnostics() {
    return java.util.Map.of();
  }
}
