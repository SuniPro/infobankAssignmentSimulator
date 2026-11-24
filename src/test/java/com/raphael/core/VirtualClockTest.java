package com.raphael.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VirtualClockTest {

  @Test
  void startsAtZeroAndAdvancesMonotonically() {
    VirtualClock clock = new VirtualClock();
    assertEquals(0, clock.now());
    clock.advanceTo(5);
    clock.advanceTo(5); // 같은 시각으로의 이동은 허용된다 (동시 이벤트).
    clock.advanceTo(1_000_000);
    assertEquals(1_000_000, clock.now());
  }

  @Test
  void rejectsMovingBackwards() {
    VirtualClock clock = new VirtualClock();
    clock.advanceTo(10);
    assertThrows(IllegalStateException.class, () -> clock.advanceTo(9));
    assertEquals(10, clock.now());
  }
}
