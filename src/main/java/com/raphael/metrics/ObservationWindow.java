package com.raphael.metrics;

/**
 * 서비스 할당을 측정하는, 마이크로초 단위의 가상 시간 반개구간 {@code [start, end)}이다.
 */
public record ObservationWindow(long start, long end) {

  public ObservationWindow {
    if (start < 0 || end <= start) {
      throw new IllegalArgumentException("Invalid window [" + start + ", " + end + ")");
    }
  }

  public boolean contains(long timestamp) {
    return timestamp >= start && timestamp < end;
  }
}
