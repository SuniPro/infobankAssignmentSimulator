package com.raphael.scheduling.sharding;

import com.raphael.core.VirtualClock;
import com.raphael.provider.ServiceModel;

/**
 * 샤드별 독립 처리 용량 (worker 슬롯). 샤드 하나의 용량은 {@code capacity / shards} (유리수) 이며,
 * provider 와 같은 방식으로 μs 미만 나머지를 이월한다. 전역 provider 한도는 별도로 계속 적용된다.
 * 장애 중인 샤드는 dispatch 를 받지 않는다.
 */
public final class ShardPool {

  private final int shards;
  private final ServiceModel serviceModel;
  private final long capacity;
  private final long[] nextAvailable;
  private final long[] remainder;
  private final boolean[] failed;
  private final long[] failedSince;
  private final long[] downtime;
  private final long[] dispatches;
  private final long[] failures;
  private long violations;

  public ShardPool(int shards, ServiceModel serviceModel, long capacity) {
    if (shards <= 0 || capacity <= 0) {
      throw new IllegalArgumentException("shards and capacity must be positive");
    }
    this.shards = shards;
    this.serviceModel = serviceModel;
    this.capacity = capacity;
    this.nextAvailable = new long[shards];
    this.remainder = new long[shards];
    this.failed = new boolean[shards];
    this.failedSince = new long[shards];
    this.downtime = new long[shards];
    this.dispatches = new long[shards];
    this.failures = new long[shards];
  }

  public int shards() {
    return shards;
  }

  public boolean isHealthy(int shard) {
    return !failed[shard];
  }

  public boolean isAvailable(int shard, long now) {
    return !failed[shard] && nextAvailable[shard] <= now;
  }

  public long nextAvailableTime(int shard) {
    return nextAvailable[shard];
  }

  /** 샤드 슬롯 하나를 점유한다. 장애 중이거나 슬롯이 아직 비지 않았으면 위반으로 센다. */
  public void occupy(int shard, long now, int cost) {
    if (failed[shard] || now < nextAvailable[shard]) {
      violations++;
    }
    long units = serviceModel == ServiceModel.MESSAGE_RATE ? 1 : cost;
    long numerator = Math.addExact(Math.multiplyExact(
        Math.multiplyExact(units, (long) shards), VirtualClock.MICROS_PER_SECOND),
        remainder[shard]);
    nextAvailable[shard] = Math.max(now, nextAvailable[shard]) + numerator / capacity;
    remainder[shard] = numerator % capacity;
    dispatches[shard]++;
  }

  public void fail(int shard, long now) {
    if (!failed[shard]) {
      failed[shard] = true;
      failedSince[shard] = now;
      failures[shard]++;
    }
  }

  public void recover(int shard, long now) {
    if (failed[shard]) {
      failed[shard] = false;
      downtime[shard] += now - failedSince[shard];
    }
  }

  public long dispatches(int shard) {
    return dispatches[shard];
  }

  public long downtime(int shard) {
    return downtime[shard];
  }

  public long failures(int shard) {
    return failures[shard];
  }

  /** 샤드 용량 위반 횟수 (정상이면 0). */
  public long violations() {
    return violations;
  }
}
