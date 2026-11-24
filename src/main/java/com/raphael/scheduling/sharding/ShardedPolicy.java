package com.raphael.scheduling.sharding;

import com.raphael.core.ResourceController;
import com.raphael.domain.Message;
import com.raphael.scheduling.SchedulingPolicy;
import com.raphael.scheduling.budget.ServiceClassPolicy;
import com.raphael.scheduling.budget.TenantBudget;
import com.raphael.scheduling.budget.TenantBudgetHolder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shuffle Sharding. 샤드마다 독립 하위 스케줄러와 독립 처리 용량 ({@link ShardPool}) 을 둔다.
 *
 * <ul>
 *   <li><b>라우팅</b> (도착 시 한 번): 테넌트에 배정된 부분집합 중 정상 샤드에서 대기 메시지가 가장
 *       적은 샤드 (동률은 낮은 번호). 부분집합이 모두 장애이면 대기가 가장 적은 배정 샤드에 넣고
 *       복구를 기다린다. 메시지는 샤드를 옮기지 않으므로 보존된다.</li>
 *   <li><b>선택</b>: 회전 포인터부터 샤드를 순서대로 보며, 정상이고 슬롯이 비었고 하위 스케줄러가 지금
 *       선택 가능한 첫 샤드에서 꺼낸다. 테넌트 예산과 클래스 리밋은 모든 샤드가 공유하는 객체이다.</li>
 *   <li><b>장애</b>: {@link ResourceController} 로 엔진이 장애·복구를 알린다. 장애 샤드는 새 dispatch 와
 *       새 라우팅에서 제외된다.</li>
 * </ul>
 */
public final class ShardedPolicy
    implements SchedulingPolicy, ResourceController, TenantBudgetHolder {

  private final String name;
  private final ShardAssignment assignment;
  private final ShardPool pool;
  private final List<SchedulingPolicy> shardPolicies;
  private final List<ResourceEvent> events;
  private final long[] routed;
  private int pointer;
  private long pending;
  private long routedToFailedSubset;

  public ShardedPolicy(String name, ShardAssignment assignment, ShardPool pool,
      List<SchedulingPolicy> shardPolicies, List<ResourceEvent> events) {
    if (shardPolicies.size() != pool.shards()) {
      throw new IllegalArgumentException("One policy per shard is required");
    }
    this.name = name;
    this.assignment = assignment;
    this.pool = pool;
    this.shardPolicies = List.copyOf(shardPolicies);
    this.events = List.copyOf(events);
    this.routed = new long[pool.shards()];
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public TenantBudget tenantBudget() {
    return shardPolicies.get(0) instanceof TenantBudgetHolder holder
        ? holder.tenantBudget() : null;
  }

  @Override
  public void enqueue(Message message) {
    int[] subset = assignment.subset(message.tenantId());
    int best = -1;
    for (int shard : subset) {
      if (pool.isHealthy(shard) && (best < 0
          || shardPolicies.get(shard).pendingCount() < shardPolicies.get(best).pendingCount())) {
        best = shard;
      }
    }
    if (best < 0) {
      routedToFailedSubset++;
      for (int shard : subset) {
        if (best < 0
            || shardPolicies.get(shard).pendingCount() < shardPolicies.get(best).pendingCount()) {
          best = shard;
        }
      }
    }
    shardPolicies.get(best).enqueue(message);
    routed[best]++;
    pending++;
  }

  @Override
  public long nextEligibleTime(long now) {
    long earliest = Long.MAX_VALUE;
    for (int shard = 0; shard < shardPolicies.size(); shard++) {
      SchedulingPolicy policy = shardPolicies.get(shard);
      if (!pool.isHealthy(shard) || policy.isEmpty()) {
        continue;
      }
      long inner = policy.nextEligibleTime(now);
      if (inner == Long.MAX_VALUE) {
        continue;
      }
      long candidate = Math.max(inner, pool.nextAvailableTime(shard));
      if (candidate <= now) {
        return now;
      }
      earliest = Math.min(earliest, candidate);
    }
    return earliest;
  }

  @Override
  public Message selectNext(long now) {
    int count = shardPolicies.size();
    for (int i = 0; i < count; i++) {
      int shard = (pointer + i) % count;
      SchedulingPolicy policy = shardPolicies.get(shard);
      if (pool.isAvailable(shard, now) && !policy.isEmpty()
          && policy.nextEligibleTime(now) == now) {
        Message message = policy.selectNext(now);
        pool.occupy(shard, now, message.cost());
        pointer = (shard + 1) % count;
        pending--;
        return message;
      }
    }
    throw new IllegalStateException("selectNext called while no shard is eligible");
  }

  @Override
  public boolean isEmpty() {
    return pending == 0;
  }

  @Override
  public long pendingCount() {
    return pending;
  }

  @Override
  public long operationCount() {
    long operations = 0;
    for (SchedulingPolicy policy : shardPolicies) {
      operations += policy.operationCount();
    }
    return operations;
  }

  @Override
  public Map<String, Long> diagnostics() {
    Map<String, Long> d = new LinkedHashMap<>();
    d.put("shardCapacityViolations", pool.violations());
    d.put("routedToFailedSubset", routedToFailedSubset);
    for (int shard = 0; shard < shardPolicies.size(); shard++) {
      d.put("shard." + shard + ".routed", routed[shard]);
      d.put("shard." + shard + ".dispatches", pool.dispatches(shard));
      d.put("shard." + shard + ".failures", pool.failures(shard));
      d.put("shard." + shard + ".downtimeMicros", pool.downtime(shard));
    }
    for (SchedulingPolicy policy : shardPolicies) {
      ServiceClassPolicy.mergeDiagnostics(d, policy.diagnostics());
    }
    return d;
  }

  @Override
  public List<ResourceEvent> resourceEvents() {
    return events;
  }

  @Override
  public void fail(int resource, long now) {
    pool.fail(resource, now);
  }

  @Override
  public void recover(int resource, long now) {
    pool.recover(resource, now);
  }
}
