package com.raphael.metrics;

import java.util.Arrays;

/**
 * 지연 시간 샘플을 담는 크기 가변 원시 {@code long} 버퍼다. 테넌트당 800,000개 이상의 샘플을
 * 박싱하지 않도록 한다. 샘플당 {@code List<Long>}의 약 24바이트 대신 8바이트만 사용한다.
 */
final class LongSamples {

  private long[] values = new long[64];
  private int size;

  void add(long value) {
    if (size == values.length) {
      values = Arrays.copyOf(values, values.length * 2);
    }
    values[size++] = value;
  }

  int size() {
    return size;
  }

  /** 샘플을 정렬한 복사본. 버퍼 자체는 변경하지 않는다. */
  long[] sortedCopy() {
    long[] sorted = Arrays.copyOf(values, size);
    Arrays.sort(sorted);
    return sorted;
  }
}
