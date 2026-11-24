package com.raphael.scheduling.wdedf;

/**
 * 비활성화(WAITING → INACTIVE) 시 음수 DC(차입 부채)의 처리 (명세 해석 A17).
 */
public enum IdleDebtPolicy {
  /**
   * 논문 의미: WAITING → INACTIVE 에서 DC 를 0 으로, 재활성화 시 {@code DC = ω}. 비었다가 라운드를
   * 넘긴 큐의 차입 부채는 탕감된다.
   */
  PAPER_RESET,
  /**
   * 진단 변형: 양수 credit 은 버리되 음수 부채는 유지한다 ({@code DC ← min(DC, 0)}, 재활성화 시
   * {@code DC ← DC + ω}). churn 으로 반복 차입하는 효과를 측정하기 위한 것이다.
   */
  RETAIN_DEBT
}
