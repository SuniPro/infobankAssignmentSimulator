package com.raphael.scheduling.wdedf;

/**
 * WDEDF 의 설정 가능한 파라미터.
 *
 * @param name 보고용 정책 이름 (예: "WDEDF-MESSAGE")
 * @param lambdaBasisPoints 선점 quantum 비율 Λ (기저점, 0 ~ 10_000). 논문은 기본값을 정하지 않는다.
 *     {@code PQ = floor(Ω × lambdaBasisPoints / 10_000)}. 0 이면 차입이 불가능하다
 * @param idleDebtPolicy 비활성화 시 부채 처리 (명세 해석 A17)
 */
public record WdedfConfig(String name, int lambdaBasisPoints, IdleDebtPolicy idleDebtPolicy) {

  public WdedfConfig {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
    if (lambdaBasisPoints < 0 || lambdaBasisPoints > 10_000) {
      throw new IllegalArgumentException("lambdaBasisPoints must be in [0, 10000]");
    }
    if (idleDebtPolicy == null) {
      throw new IllegalArgumentException("idleDebtPolicy is required");
    }
  }
}
