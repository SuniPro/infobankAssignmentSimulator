package com.raphael.scheduling.wdedf;

/** 논문 §2 와 Fig. 2 의 WDEDF 큐 상태. 각 큐는 항상 정확히 하나의 상태에 있다. */
public enum QueueState {
  /** backlog 없음, 직전 라운드에서 넘어온 credit 없음. 어떤 목록에도 속하지 않는다. */
  INACTIVE,
  /** head 의 cost 를 DC 가 감당할 수 있음. active 후보 heap 에 정확히 한 번 존재한다. */
  ACTIVE,
  /** 현재 라운드에서 비었음. DC 를 라운드 끝까지 보존한다. waiting 목록에 존재한다. */
  WAITING,
  /** 메시지가 있으나 DC 가 head 의 cost 보다 작음. blocked 목록에 정확히 한 번 존재한다. */
  BLOCKED
}
