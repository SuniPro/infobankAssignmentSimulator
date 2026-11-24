package com.raphael.scheduling;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.domain.Tenant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 메시지를 스케줄링 큐에 배치하는 방식 (큐 토폴로지)과 큐별 정수 가중치.
 *
 * <ul>
 *   <li>{@link Topology#TENANT}: 테넌트당 큐 하나 (v0.2 와 같음). 큐 가중치 = 테넌트 가중치.</li>
 *   <li>{@link Topology#TENANT_AND_TYPE}: 진단용. 테넌트를 메시지 유형별 자식 큐로 나눈다. 테넌트의
 *       가중치를 자식들에게 <b>분할</b> 하여 테넌트 전체 몫이 늘어나지 않게 한다: 테넌트 t 가
 *       {@code k_t} 개 유형을 쓰면 {@code L = lcm(k_t)} 로 두고 자식 가중치를
 *       {@code w_t × L / k_t} 로 정한다. 그러면 모든 테넌트의 자식 가중치 합은 {@code w_t × L}
 *       이 되어 테넌트 간 비율이 보존된다. (계층적 부모 예산은 v0.3 범위 밖이다.)</li>
 * </ul>
 *
 * <p>분류기는 미리 계산한 키 표를 조회하므로 메시지마다 문자열을 만들지 않는다.
 */
public final class QueueLayout {

  /** 큐 토폴로지. */
  public enum Topology { TENANT, TENANT_AND_TYPE }

  private final Topology topology;
  private final Map<String, Integer> queueWeights;
  private final Map<String, String[]> keysByTenant;

  private QueueLayout(Topology topology, Map<String, Integer> queueWeights,
      Map<String, String[]> keysByTenant) {
    this.topology = topology;
    this.queueWeights = Collections.unmodifiableMap(queueWeights);
    this.keysByTenant = keysByTenant;
  }

  public static QueueLayout of(Topology topology, List<Tenant> tenants, List<Message> messages) {
    Map<String, Integer> weights = new LinkedHashMap<>();
    Map<String, String[]> keys = new HashMap<>();
    if (topology == Topology.TENANT) {
      for (Tenant tenant : tenants) {
        weights.put(tenant.id(), tenant.weight());
        String[] row = new String[MessageType.values().length];
        java.util.Arrays.fill(row, tenant.id());
        keys.put(tenant.id(), row);
      }
      return new QueueLayout(topology, weights, keys);
    }
    Map<String, Set<MessageType>> typesByTenant = new HashMap<>();
    for (Message message : messages) {
      typesByTenant.computeIfAbsent(message.tenantId(), t -> EnumSet.noneOf(MessageType.class))
          .add(message.type());
    }
    long lcm = 1;
    for (Set<MessageType> types : typesByTenant.values()) {
      lcm = lcm(lcm, types.size());
    }
    for (Tenant tenant : tenants) {
      Set<MessageType> types = typesByTenant.getOrDefault(
          tenant.id(), EnumSet.of(MessageType.ADVERTISEMENT));
      String[] row = new String[MessageType.values().length];
      for (MessageType type : MessageType.values()) {
        row[type.ordinal()] = tenant.id() + "/" + type;
      }
      keys.put(tenant.id(), row);
      long childWeight = Math.multiplyExact((long) tenant.weight(), lcm) / types.size();
      for (MessageType type : types) {
        weights.put(row[type.ordinal()], Math.toIntExact(childWeight));
      }
    }
    return new QueueLayout(topology, weights, keys);
  }

  public Topology topology() {
    return topology;
  }

  /** 큐 키 → 정수 가중치 (설정 순서). */
  public Map<String, Integer> queueWeights() {
    return queueWeights;
  }

  /** 메시지 → 큐 키. 할당 없는 표 조회. */
  public Function<Message, String> classifier() {
    return message -> {
      String[] row = keysByTenant.get(message.tenantId());
      if (row == null) {
        throw new IllegalArgumentException("Unknown tenant " + message.tenantId());
      }
      return row[message.type().ordinal()];
    };
  }

  private static long lcm(long a, long b) {
    long x = a;
    long y = b;
    while (y != 0) {
      long t = x % y;
      x = y;
      y = t;
    }
    return Math.multiplyExact(a / x, b);
  }
}
