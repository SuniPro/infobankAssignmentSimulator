package com.raphael.scheduling.sharding;

import com.raphael.core.ResourceController.ResourceEvent;
import java.util.List;

/**
 * Shuffle Sharding 설정.
 *
 * @param shards 샤드(worker) 수. 각 샤드의 처리 용량은 provider 용량 / shards 이므로 모든 샤드가
 *     정상일 때 합계 용량이 시스템 용량과 같다
 * @param subsetSize 테넌트당 배정 샤드 수 (1 ≤ subsetSize ≤ shards)
 * @param seed 결정론적 배정 seed
 * @param failures 장애·복구 사건. 장애가 난 샤드는 반드시 이후에 복구되어야 한다 (메시지 보존)
 */
public record ShardingConfig(int shards, int subsetSize, long seed, List<ResourceEvent> failures) {

  public ShardingConfig {
    if (shards <= 0 || subsetSize <= 0 || subsetSize > shards) {
      throw new IllegalArgumentException("Require 0 < subsetSize <= shards");
    }
    failures = List.copyOf(failures);
    boolean[] down = new boolean[shards];
    long previous = -1;
    for (ResourceEvent event : failures) {
      if (event.resource() >= shards || event.time() < previous) {
        throw new IllegalArgumentException("Failure events must be time-ordered and in range");
      }
      if (down[event.resource()] == event.failure()) {
        throw new IllegalArgumentException("Failure/recovery events must alternate per shard");
      }
      down[event.resource()] = event.failure();
      previous = event.time();
    }
    for (boolean d : down) {
      if (d) {
        throw new IllegalArgumentException("Every failed shard must recover");
      }
    }
  }

  public ShardingConfig withFailures(List<ResourceEvent> events) {
    return new ShardingConfig(shards, subsetSize, seed, events);
  }
}
