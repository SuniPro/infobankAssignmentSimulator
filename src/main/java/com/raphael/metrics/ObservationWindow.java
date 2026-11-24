package com.raphael.metrics;

import com.raphael.core.VirtualClock;

/**
 * 서비스 할당을 측정하는, 마이크로초 단위의 가상 시간 반개구간 {@code [start, end)} 이다.
 *
 * @param shortWindowLength 단기 공정성 측정을 위해 관측 구간을 나누는 하위 구간 길이(μs).
 *     장기 공정성은 {@code [start, end)} 전체에서, 단기 공정성은 이 길이의 연속 하위 구간마다
 *     따로 계산한다.
 */
public record ObservationWindow(long start, long end, long shortWindowLength) {

  /** 단기 구간 길이 1초의 v0.2 호환 생성자. */
  public ObservationWindow(long start, long end) {
    this(start, end, VirtualClock.MICROS_PER_SECOND);
  }

  public ObservationWindow {
    if (start < 0 || end <= start) {
      throw new IllegalArgumentException("Invalid window [" + start + ", " + end + ")");
    }
    if (shortWindowLength <= 0) {
      throw new IllegalArgumentException("shortWindowLength must be positive");
    }
  }

  public boolean contains(long timestamp) {
    return timestamp >= start && timestamp < end;
  }

  /** 하위 구간 개수 (마지막 구간은 짧을 수 있다). */
  public int shortWindowCount() {
    return (int) Math.min(Integer.MAX_VALUE,
        (end - start + shortWindowLength - 1) / shortWindowLength);
  }
}
