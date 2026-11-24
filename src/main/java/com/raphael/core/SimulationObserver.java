package com.raphael.core;

import com.raphael.domain.Message;

/**
 * 엔진의 메시지 생명주기를 들여다보는 선택적 읽기 전용 hook으로, trace와 검증에 사용한다.
 * Observer는 시뮬레이션 상태를 변경해서는 안 된다.
 */
public interface SimulationObserver {

  /** 모든 callback을 무시하는 observer. */
  SimulationObserver NONE = new SimulationObserver() { };

  default void onArrival(Message message, long now) {
  }

  default void onDispatch(Message message, long now) {
  }

  default void onDelivery(Message message, long now) {
  }
}
