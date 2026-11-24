package com.raphael.core;

/**
 * 마이크로초 해상도의 단조(monotonic) 가상 시계.
 *
 * <p>가상 시간은 엔진이 이벤트를 처리할 때만 전진하며, 절대 뒤로 가지 않는다.
 */
public final class VirtualClock {

  /** 1초당 가상 시간 단위(마이크로초) 수. */
  public static final long MICROS_PER_SECOND = 1_000_000L;

  /** 1밀리초당 가상 시간 단위(마이크로초) 수. */
  public static final long MICROS_PER_MILLISECOND = 1_000L;

  private long now;

  public long now() {
    return now;
  }

  /**
   * 시계를 {@code timestamp}로 이동한다.
   *
   * @throws IllegalStateException {@code timestamp}가 현재 시각보다 이전인 경우
   */
  public void advanceTo(long timestamp) {
    if (timestamp < now) {
      throw new IllegalStateException(
          "Virtual clock cannot move backwards: now=" + now + ", requested=" + timestamp);
    }
    now = timestamp;
  }
}
