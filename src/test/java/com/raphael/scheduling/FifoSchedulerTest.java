package com.raphael.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import org.junit.jupiter.api.Test;

class FifoSchedulerTest {

  @Test
  void preservesGlobalArrivalOrderIgnoringTenantAndCost() {
    FifoScheduler fifo = new FifoScheduler();
    for (int i = 0; i < 6; i++) {
      fifo.enqueue(new Message(i, i % 2 == 0 ? "A" : "B", MessageType.OTP, 0, 0, 1 + i));
    }
    for (int i = 0; i < 6; i++) {
      assertEquals(i, fifo.selectNext(0).id());
    }
    assertTrue(fifo.isEmpty());
    assertEquals(6, fifo.operationCount());
    assertThrows(IllegalStateException.class, () -> fifo.selectNext(0));
  }
}
