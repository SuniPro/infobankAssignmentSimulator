package com.raphael.metrics;

/**
 * 부호 있는 값(예: dispatch 시점의 남은 slack)의 분포 요약. 하위 꼬리가 중요한 값을 위해 min,
 * p1, p5 를 포함한다. 백분위수는 {@link LatencySummary} 와 같은 nearest-rank 정의를 쓴다.
 * 표본이 없으면 {@link #isEmpty()} 가 참이며 값 접근자는 예외를 던진다.
 */
public record DistributionSummary(
    long count, long min, long p1, long p5, long p50, long p95, long p99, long max) {

  public static final DistributionSummary EMPTY = new DistributionSummary(0, 0, 0, 0, 0, 0, 0, 0);

  static DistributionSummary of(LongSamples samples) {
    if (samples.size() == 0) {
      return EMPTY;
    }
    long[] sorted = samples.sortedCopy();
    return new DistributionSummary(
        sorted.length,
        sorted[0],
        LatencySummary.nearestRank(sorted, 100),
        LatencySummary.nearestRank(sorted, 500),
        LatencySummary.nearestRank(sorted, 5_000),
        LatencySummary.nearestRank(sorted, 9_500),
        LatencySummary.nearestRank(sorted, 9_900),
        sorted[sorted.length - 1]);
  }

  public boolean isEmpty() {
    return count == 0;
  }

  @Override
  public long min() {
    requireSamples();
    return min;
  }

  @Override
  public long p1() {
    requireSamples();
    return p1;
  }

  @Override
  public long p50() {
    requireSamples();
    return p50;
  }

  @Override
  public long max() {
    requireSamples();
    return max;
  }

  private void requireSamples() {
    if (count == 0) {
      throw new IllegalStateException("No samples");
    }
  }
}
