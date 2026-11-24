package com.raphael.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProviderSimulatorTest {

  @Test
  void backToBackDispatchesCarryFractionalMicroseconds() {
    ProviderSimulator provider = new ProviderSimulator(450, 100_000);
    long now = 0;
    for (int i = 0; i < 450; i++) {
      long interval = provider.nextAvailableTime() - now;
      assertEquals(now + 100_000, provider.dispatch(now));
      long next = provider.nextAvailableTime();
      // 1s/450 = 2222.22us → 간격은 항상 2222 또는 2223us 이다.
      assertTrue(next - now == 2_222 || next - now == 2_223, "interval " + (next - now));
      now = next;
      assertTrue(interval >= 0);
    }
    // 450 회 연속 발송은 정확히 1초를 차지한다 (반올림 오차 누적 없음).
    assertEquals(1_000_000, now);
  }

  @Test
  void rejectsDispatchBeforeNextAvailableTime() {
    ProviderSimulator provider = new ProviderSimulator(10, 0);
    provider.dispatch(0);
    assertThrows(IllegalStateException.class, () -> provider.dispatch(99_999));
    provider.dispatch(100_000);
  }

  @Test
  void everySlotIsAtLeastOneMicrosecond() {
    ProviderSimulator provider = new ProviderSimulator(1_000_000, 0);
    provider.dispatch(0);
    assertEquals(1, provider.nextAvailableTime());
  }

  @Test
  void rejectsInvalidConfiguration() {
    assertThrows(IllegalArgumentException.class, () -> new ProviderSimulator(0, 0));
    assertThrows(IllegalArgumentException.class, () -> new ProviderSimulator(1_000_001, 0));
    assertThrows(IllegalArgumentException.class, () -> new ProviderSimulator(10, -1));
  }
}
