package com.raphael.provider;

import com.raphael.core.VirtualClock;

/**
 * 고정 처리량 한도와 고정 배달 지연을 갖는 외부 배달 provider.
 *
 * <p>각 발송은 provider를 {@code 1s / tps} 동안 점유한다. 마이크로초 미만의 나머지는
 * {@code remainder}에 이월되므로, 연속된 임의의 {@code tps}회 발송은 정확히 1초에 걸치며
 * 반올림 오차가 누적되지 않는다. 동작은 v0.1과 동일하다.
 */
public final class ProviderSimulator {

  private final int tps;
  private final long deliveryDelay;

  private long nextAvailableTime;
  private long remainder;

  /**
   * @param tps 초당 메시지 수. 모든 슬롯이 최소 1us가 되도록 {@code [1, 1_000_000]} 범위여야 한다
   * @param deliveryDelay 마이크로초 단위의 고정 외부 지연. 음수가 아니어야 한다
   */
  public ProviderSimulator(int tps, long deliveryDelay) {
    if (tps <= 0 || tps > VirtualClock.MICROS_PER_SECOND) {
      throw new IllegalArgumentException("tps must be in [1, 1_000_000]: " + tps);
    }
    if (deliveryDelay < 0) {
      throw new IllegalArgumentException("deliveryDelay must be non-negative: " + deliveryDelay);
    }
    this.tps = tps;
    this.deliveryDelay = deliveryDelay;
  }

  /** 다음 발송이 허용되는 가장 이른 가상 시각. */
  public long nextAvailableTime() {
    return nextAvailableTime;
  }

  /**
   * {@code now} 시각에 메시지 하나를 provider에 넘긴다.
   *
   * @return 메시지가 배달되는 가상 시각
   * @throws IllegalStateException {@link #nextAvailableTime()} 이전에 호출된 경우
   */
  public long dispatch(long now) {
    if (now < nextAvailableTime) {
      throw new IllegalStateException("Provider rate limit exceeded");
    }
    // rate-limit 반올림 오차가 누적되지 않도록 마이크로초 미만의 나머지를 보존한다.
    long numerator = VirtualClock.MICROS_PER_SECOND + remainder;
    nextAvailableTime = now + numerator / tps;
    remainder = numerator % tps;
    return now + deliveryDelay;
  }

  public int tps() {
    return tps;
  }

  public long deliveryDelay() {
    return deliveryDelay;
  }
}
