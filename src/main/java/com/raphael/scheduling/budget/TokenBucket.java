package com.raphael.scheduling.budget;

import com.raphael.core.VirtualClock;

/**
 * 가상 시간 토큰 버킷. 초당 {@code rate} 단위로 채워지고 최대 {@code burst} 단위까지 쌓인다.
 * 내부적으로 토큰을 μ단위 (단위 × 10^6) 정수로 저장하므로 반올림 오차가 없다. 처음에는 가득 차 있다.
 *
 * <p>하드 리밋이다: 토큰이 부족하면 소비할 수 없고, 빌려 쓸 수 없다.
 */
public final class TokenBucket {

  private final long rate;
  private final long burst;
  private final long capacityMicros;
  private long tokensMicros;
  private long lastUpdate;

  /**
   * @param rate 초당 충전 단위 (양수)
   * @param burst 최대 저장 단위 (양수). 메시지 하나의 cost 이상이어야 그 메시지를 보낼 수 있다
   */
  public TokenBucket(long rate, long burst) {
    if (rate <= 0 || burst <= 0) {
      throw new IllegalArgumentException("rate and burst must be positive");
    }
    this.rate = rate;
    this.burst = burst;
    this.capacityMicros = Math.multiplyExact(burst, VirtualClock.MICROS_PER_SECOND);
    this.tokensMicros = capacityMicros;
  }

  public long rate() {
    return rate;
  }

  public long burst() {
    return burst;
  }

  /** {@code units} 를 보낼 수 있는 가장 이른 시각 ({@code now} 이하이면 지금). 초과 요청은 MAX. */
  public long reopenTime(long units, long now) {
    refill(now);
    if (units > burst) {
      return Long.MAX_VALUE;
    }
    long needed = units * VirtualClock.MICROS_PER_SECOND - tokensMicros;
    if (needed <= 0) {
      return now;
    }
    return now + (needed + rate - 1) / rate;
  }

  public boolean admits(long units, long now) {
    return reopenTime(units, now) <= now;
  }

  /**
   * {@code units} 를 차감한다.
   *
   * @return 토큰이 충분했으면 {@code true}. 부족하면 0 으로 만들고 {@code false} (감사용)
   */
  public boolean consume(long units, long now) {
    refill(now);
    long cost = units * VirtualClock.MICROS_PER_SECOND;
    if (cost > tokensMicros) {
      tokensMicros = 0;
      return false;
    }
    tokensMicros -= cost;
    return true;
  }

  private void refill(long now) {
    if (now <= lastUpdate) {
      return;
    }
    long missing = capacityMicros - tokensMicros;
    long elapsed = now - lastUpdate;
    if (missing > 0) {
      tokensMicros = elapsed >= (missing + rate - 1) / rate
          ? capacityMicros
          : tokensMicros + elapsed * rate;
    }
    lastUpdate = now;
  }
}
