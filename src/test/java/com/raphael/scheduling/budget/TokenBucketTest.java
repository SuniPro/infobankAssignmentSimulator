package com.raphael.scheduling.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

  @Test
  void refillsExactlyAndCapsAtBurst() {
    TokenBucket bucket = new TokenBucket(10, 3);           // 10/s, 최대 3
    assertTrue(bucket.consume(3, 0));
    assertFalse(bucket.admits(1, 0));
    assertEquals(100_000, bucket.reopenTime(1, 0));        // 1 단위 = 100 ms
    assertEquals(200_000, bucket.reopenTime(2, 50_000));
    assertTrue(bucket.admits(1, 100_000));
    assertTrue(bucket.admits(3, 10_000_000));               // 오래 쉬어도 burst 3 까지만
    assertFalse(bucket.admits(4, 10_000_000));
    assertEquals(Long.MAX_VALUE, bucket.reopenTime(4, 10_000_000));
    assertFalse(new TokenBucket(1, 1).consume(2, 0));       // 부족한 소비는 감사용으로 false
  }
}
