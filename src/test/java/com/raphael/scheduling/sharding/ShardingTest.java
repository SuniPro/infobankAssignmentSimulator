package com.raphael.scheduling.sharding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.core.ResourceController.ResourceEvent;
import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.provider.ServiceModel;
import com.raphael.scheduling.FifoScheduler;
import com.raphael.scheduling.SchedulingPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShardingTest {

  @Test
  void assignmentIsDeterministicSizedAndSeedDependent() {
    ShardAssignment a = new ShardAssignment(8, 3, 42);
    ShardAssignment b = new ShardAssignment(8, 3, 42);
    Set<String> distinct = new HashSet<>();
    boolean seedMatters = false;
    for (int t = 0; t < 50; t++) {
      String tenant = "T" + t;
      int[] subset = a.subset(tenant);
      assertArrayEquals(subset, b.subset(tenant));
      assertEquals(3, subset.length);
      assertEquals(3, Arrays.stream(subset).distinct().count());
      assertTrue(Arrays.stream(subset).allMatch(s -> s >= 0 && s < 8));
      distinct.add(Arrays.toString(subset));
      seedMatters |= !Arrays.equals(subset, new ShardAssignment(8, 3, 7).subset(tenant));
    }
    assertTrue(distinct.size() > 10, "subsets should be shuffled across tenants");
    assertTrue(seedMatters);
  }

  @Test
  void eachShardHasIndependentCapacitySummingToSystemCapacity() {
    ShardPool pool = new ShardPool(4, ServiceModel.MESSAGE_RATE, 100);
    // 샤드 하나 = 25 msg/s → 40 ms 간격. 네 샤드 합계 100 msg/s.
    pool.occupy(0, 0, 1);
    assertFalse(pool.isAvailable(0, 39_999));
    assertTrue(pool.isAvailable(0, 40_000));
    assertTrue(pool.isAvailable(1, 0));
    pool.occupy(0, 10_000, 1);                               // 너무 이른 점유 → 위반
    assertEquals(1, pool.violations());
  }

  private static Message msg(long id, String tenant) {
    return new Message(id, tenant, MessageType.ADVERTISEMENT, 0, 1_000_000_000L, 1);
  }

  private static ShardedPolicy policy(ShardPool pool, List<ResourceEvent> events) {
    List<SchedulingPolicy> shards = new ArrayList<>();
    for (int i = 0; i < pool.shards(); i++) {
      shards.add(new FifoScheduler());
    }
    return new ShardedPolicy("sharded", new ShardAssignment(pool.shards(), 2, 1), pool, shards,
        events);
  }

  @Test
  void failedShardTakesNoDispatchAndKeepsItsQueueUntilRecovery() {
    ShardPool pool = new ShardPool(2, ServiceModel.MESSAGE_RATE, 1_000);
    ShardedPolicy p = policy(pool, List.of());
    for (int i = 0; i < 10; i++) {
      p.enqueue(msg(i, "A"));                               // 최소 부하 라우팅 → 두 샤드에 5 건씩
    }
    assertEquals(5, p.diagnostics().get("shard.0.routed"));
    p.fail(0, 0);
    long now = 0;
    for (int i = 0; i < 5; i++) {
      now = p.nextEligibleTime(now);
      p.selectNext(now);
    }
    assertEquals(0, p.diagnostics().get("shard.0.dispatches"));
    assertEquals(5, p.diagnostics().get("shard.1.dispatches"));
    assertEquals(5, p.pendingCount());                      // 장애 샤드의 메시지는 보존
    assertEquals(Long.MAX_VALUE, p.nextEligibleTime(now + 1_000_000));
    p.enqueue(msg(10, "A"));                                // 새 도착은 정상 샤드로
    assertEquals(6, p.diagnostics().get("shard.1.routed"));
    p.recover(0, 5_000_000);
    assertEquals(5_000_000, p.nextEligibleTime(5_000_000));
    int drained = 0;
    now = 5_000_000;
    while (!p.isEmpty()) {
      now = p.nextEligibleTime(now);
      p.selectNext(now);
      drained++;
    }
    assertEquals(6, drained);
    assertEquals(5_000_000, p.diagnostics().get("shard.0.downtimeMicros"));
    assertEquals(0, p.diagnostics().get("shardCapacityViolations"));
  }

  @Test
  void configRequiresRecoveryForEveryFailure() {
    assertThrows(IllegalArgumentException.class, () -> new ShardingConfig(4, 2, 1,
        List.of(new ResourceEvent(10, 1, true))));
    assertThrows(IllegalArgumentException.class, () -> new ShardingConfig(4, 5, 1, List.of()));
    assertNotEquals(null, new ShardingConfig(4, 2, 1, List.of(new ResourceEvent(10, 1, true),
        new ResourceEvent(20, 1, false))));
  }
}
