package com.raphael.scheduling.sharding;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 결정론적 테넌트 → 샤드 부분집합 배정. 테넌트마다 {@code seed} 와 테넌트 id 의
 * {@link String#hashCode()} (명세상 고정된 값) 로 만든 난수열로 부분 Fisher–Yates 섞기를 해서
 * {@code subsetSize} 개 샤드를 고르고 오름차순으로 정렬한다. 같은 설정은 항상 같은 배정을 낸다.
 */
public final class ShardAssignment {

  private final int shards;
  private final int subsetSize;
  private final long seed;
  private final Map<String, int[]> cache = new HashMap<>();

  public ShardAssignment(int shards, int subsetSize, long seed) {
    if (shards <= 0 || subsetSize <= 0 || subsetSize > shards) {
      throw new IllegalArgumentException("Require 0 < subsetSize <= shards");
    }
    this.shards = shards;
    this.subsetSize = subsetSize;
    this.seed = seed;
  }

  /** 테넌트의 샤드 부분집합 (오름차순, 복사본 아님 — 수정 금지). */
  public int[] subset(String tenantId) {
    int[] subset = cache.get(tenantId);
    if (subset == null) {
      Random random = new Random(seed * 1_000_003L + tenantId.hashCode());
      int[] all = new int[shards];
      for (int i = 0; i < shards; i++) {
        all[i] = i;
      }
      for (int i = 0; i < subsetSize; i++) {
        int j = i + random.nextInt(shards - i);
        int t = all[i];
        all[i] = all[j];
        all[j] = t;
      }
      subset = Arrays.copyOf(all, subsetSize);
      Arrays.sort(subset);
      cache.put(tenantId, subset);
    }
    return subset;
  }
}
