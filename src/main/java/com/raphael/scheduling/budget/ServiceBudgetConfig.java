package com.raphael.scheduling.budget;

/**
 * Service Budget 설정. 세 가지 서로 다른 의미의 할당을 구분한다.
 *
 * <ul>
 *   <li><b>서비스 클래스 예약 (reservation, 빌려 줄 수 있음)</b>: {@code otpWeight} 대
 *       {@code advertisementWeight}. 두 클래스 모두 선택 가능한 작업이 있으면 dispatch 슬롯을 이
 *       비율로 나눈다 (smooth weighted round robin). 한 클래스에 선택 가능한 작업이 없으면 그
 *       예약분은 다른 클래스가 쓴다 (작업 보존). 엄격한 OTP 우선순위가 아니다: 광고도 자기 예약을
 *       보장받는다.</li>
 *   <li><b>서비스 클래스 하드 리밋 (빌려 줄 수 없음)</b>: {@code otpLimitBasisPoints},
 *       {@code advertisementLimitBasisPoints} — provider 용량 대비 비율 (기저점, 0 = 없음). 해당
 *       클래스의 dispatch 율 상한이며 남는 용량이 있어도 넘을 수 없다. 메시지 단위로 센다.</li>
 *   <li><b>테넌트 전역 예산 (하드 리밋)</b>: {@code tenantRateBasisPoints} (provider 용량 대비, 0 =
 *       없음) 와 {@code tenantBurst} (cost 단위). 테넌트의 모든 자식 큐·클래스·샤드가 하나의 토큰
 *       버킷을 공유하므로 어느 큐나 샤드도 테넌트 예산 전체를 독립적으로 쓸 수 없다.</li>
 * </ul>
 */
public record ServiceBudgetConfig(
    int otpWeight,
    int advertisementWeight,
    int otpLimitBasisPoints,
    int advertisementLimitBasisPoints,
    int tenantRateBasisPoints,
    long tenantBurst) {

  public ServiceBudgetConfig {
    if (otpWeight <= 0 || advertisementWeight <= 0) {
      throw new IllegalArgumentException("Class weights must be positive");
    }
    for (int bp : new int[] {otpLimitBasisPoints, advertisementLimitBasisPoints,
        tenantRateBasisPoints}) {
      if (bp < 0 || bp > 10_000) {
        throw new IllegalArgumentException("Basis points must be in [0, 10000]");
      }
    }
    if (tenantRateBasisPoints > 0 && tenantBurst <= 0) {
      throw new IllegalArgumentException("tenantBurst must be positive when a tenant rate is set");
    }
  }

  /** 기본 실험값 (논문·운영 근거 없는 선택값): OTP 3 : 광고 7 예약, 클래스 리밋 없음, 테넌트 80%. */
  public static ServiceBudgetConfig defaults() {
    return new ServiceBudgetConfig(3, 7, 0, 0, 8_000, 50);
  }
}
