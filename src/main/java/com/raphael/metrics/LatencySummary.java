package com.raphael.metrics;

/**
 * 마이크로초 단위의 정확한 지연 시간 분포 요약이다.
 *
 * <p>백분위수는 nearest-rank 정의를 따른다. 즉 {@code ceil(n * p)}번째로 작은 샘플(1부터 셈)이며,
 * basis point 단위의 정확한 정수 연산으로 계산한다. 이는 v0.1의 정의에서 부동소수점 반올림을
 * 제거한 것이다. {@link #count()}를 제외한 접근자는 샘플이 없으면 예외를 던지므로, 샘플이 없는
 * 상황이 지연 시간 0으로 보고되는 일은 결코 없다.
 */
public record LatencySummary(long count, long p50, long p95, long p99, long max) {

  public static final LatencySummary EMPTY = new LatencySummary(0, 0, 0, 0, 0);

  static LatencySummary of(LongSamples samples) {
    if (samples.size() == 0) {
      return EMPTY;
    }
    long[] sorted = samples.sortedCopy();
    return new LatencySummary(
        sorted.length,
        nearestRank(sorted, 5_000),
        nearestRank(sorted, 9_500),
        nearestRank(sorted, 9_900),
        sorted[sorted.length - 1]);
  }

  /** {@code p = basisPoints / 10_000}일 때의 nearest-rank 백분위수. */
  static long nearestRank(long[] sorted, int basisPoints) {
    long rank = ((long) sorted.length * basisPoints + 9_999) / 10_000;
    return sorted[(int) Math.max(0, rank - 1)];
  }

  public boolean isEmpty() {
    return count == 0;
  }

  @Override
  public long p50() {
    requireSamples();
    return p50;
  }

  @Override
  public long p95() {
    requireSamples();
    return p95;
  }

  @Override
  public long p99() {
    requireSamples();
    return p99;
  }

  @Override
  public long max() {
    requireSamples();
    return max;
  }

  private void requireSamples() {
    if (count == 0) {
      throw new IllegalStateException("No latency samples");
    }
  }
}
