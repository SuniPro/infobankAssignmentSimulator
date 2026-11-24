package com.raphael.scheduling;

import com.raphael.domain.Message;

/**
 * 메시지를 지금 dispatch 해도 되는지 판단하는 비파괴적 게이트 (v0.4 Service Budget).
 * 스케줄러는 메시지를 꺼내거나 deficit 을 소비하기 <em>전에</em> {@link #reopenTime} 으로 확인한다.
 * 소비 ({@link #consume}) 는 메시지가 실제로 선택된 뒤 한 번만 한다.
 */
public interface DispatchGate {

  /** 항상 허용하는 게이트. */
  DispatchGate OPEN = new DispatchGate() {
    @Override
    public long reopenTime(Message message, long now) {
      return now;
    }

    @Override
    public void consume(Message message, long now) {
    }
  };

  /**
   * {@code message} 가 허용되는 가장 이른 시각. {@code now} 이하이면 지금 허용된다. 게이트 상태는
   * 시간이 흐르면 단조적으로만 열린다 (소비가 없으면 반환값은 바뀌지 않는다).
   */
  long reopenTime(Message message, long now);

  /** 선택된 메시지의 예산을 차감한다. */
  void consume(Message message, long now);
}
