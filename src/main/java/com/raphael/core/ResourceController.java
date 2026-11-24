package com.raphael.core;

import java.util.List;

/**
 * 장애·복구가 가능한 처리 자원(샤드/worker)을 가진 정책이 구현한다. 엔진은 실행 시작 시
 * {@link #resourceEvents()} 의 사건을 예약하고, 해당 시각에 {@link #fail}/{@link #recover} 를
 * 호출한다. 장애 중인 자원은 새 dispatch 를 받지 않으며, 대기 메시지는 그대로 보존된다.
 */
public interface ResourceController {

  /** 장애 또는 복구 사건. */
  record ResourceEvent(long time, int resource, boolean failure) {
    public ResourceEvent {
      if (time < 0 || resource < 0) {
        throw new IllegalArgumentException("Invalid resource event");
      }
    }
  }

  List<ResourceEvent> resourceEvents();

  void fail(int resource, long now);

  void recover(int resource, long now);
}
