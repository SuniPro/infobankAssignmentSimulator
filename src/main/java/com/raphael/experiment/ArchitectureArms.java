package com.raphael.experiment;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.domain.Tenant;
import com.raphael.scenario.Scenario;
import com.raphael.scheduling.DispatchGate;
import com.raphael.scheduling.QueueLayout;
import com.raphael.scheduling.SchedulingPolicy;
import com.raphael.scheduling.budget.ServiceBudgetConfig;
import com.raphael.scheduling.budget.ServiceClassPolicy;
import com.raphael.scheduling.budget.TenantBudget;
import com.raphael.scheduling.budget.TokenBucket;
import com.raphael.scheduling.sharding.ShardAssignment;
import com.raphael.scheduling.sharding.ShardPool;
import com.raphael.scheduling.sharding.ShardedPolicy;
import com.raphael.scheduling.sharding.ShardingConfig;
import com.raphael.scheduling.wdedf.AgingDeparturePolicy;
import com.raphael.scheduling.wdedf.BusinessDepartureTimePolicy;
import com.raphael.scheduling.wdedf.DepartureTimePolicy;
import com.raphael.scheduling.wdedf.IdleDebtPolicy;
import com.raphael.scheduling.wdedf.ReservationModel;
import com.raphael.scheduling.wdedf.WdedfConfig;
import com.raphael.scheduling.wdedf.WdedfScheduler;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.4 아키텍처 실험열 E3–E5.
 *
 * <ul>
 *   <li>E3 = WDEDF (업무 목표, Λ=5%) + 테넌트×유형 큐 + Service Budget</li>
 *   <li>E4 = E3 + Shuffle Sharding (샤드별 독립 용량, 합계 = provider 용량)</li>
 *   <li>E5 = E4 + Aging</li>
 * </ul>
 * 테넌트×유형 큐는 {@link QueueLayout.Topology#TENANT_AND_TYPE} 의 큐 키 ("A/OTP") 를 재사용하되,
 * v0.3 처럼 테넌트 가중치를 자식에게 나누지 않고 서비스 클래스별 WDEDF 로 분할한다: 클래스 간 몫은
 * 클래스 예약이, 테넌트 전체 몫은 공유 테넌트 예산이 정한다 (v0.3 S5 에서 측정된 몫 누출 방지).
 * 실행마다 예산·리밋·샤드 풀이 새로 만들어지며 한 실행 안에서는 모든 큐·샤드가 공유한다.
 */
public final class ArchitectureArms {

  private ArchitectureArms() {
  }

  public static ExperimentConfig e3(int baseQuantum, ServiceBudgetConfig budget) {
    return new ExperimentConfig("E3", "WDEDF + tenant×type queues + Service Budget",
        scenario -> build(scenario, baseQuantum, budget, null, 0),
        settings(baseQuantum, budget, null, 0));
  }

  public static ExperimentConfig e4(int baseQuantum, ServiceBudgetConfig budget,
      ShardingConfig sharding) {
    return new ExperimentConfig("E4", "E3 + Shuffle Sharding",
        scenario -> build(scenario, baseQuantum, budget, sharding, 0),
        settings(baseQuantum, budget, sharding, 0));
  }

  public static ExperimentConfig e5(int baseQuantum, ServiceBudgetConfig budget,
      ShardingConfig sharding, long agingThresholdMicros) {
    return new ExperimentConfig("E5", "E4 + Aging",
        scenario -> build(scenario, baseQuantum, budget, sharding, agingThresholdMicros),
        settings(baseQuantum, budget, sharding, agingThresholdMicros));
  }

  /** E0–E5 (같은 base quantum). */
  public static List<ExperimentConfig> e0ToE5(int baseQuantum, ServiceBudgetConfig budget,
      ShardingConfig sharding, long agingThresholdMicros) {
    return List.of(ExperimentConfig.fifo(), ExperimentConfig.dwrr(baseQuantum),
        StandardArms.e2WdedfMessage(baseQuantum), e3(baseQuantum, budget),
        e4(baseQuantum, budget, sharding), e5(baseQuantum, budget, sharding,
            agingThresholdMicros));
  }

  static SchedulingPolicy build(Scenario scenario, int baseQuantum, ServiceBudgetConfig budget,
      ShardingConfig sharding, long agingThreshold) {
    int maxCost = scenario.messages().stream().mapToInt(Message::cost).max().orElse(1);
    DispatchGate gate = DispatchGate.OPEN;
    if (budget.tenantRateBasisPoints() > 0) {
      if (budget.tenantBurst() < maxCost) {
        throw new IllegalArgumentException("tenantBurst must cover the largest message cost");
      }
      long rate = Math.max(1, (long) scenario.providerCapacity()
          * budget.tenantRateBasisPoints() / 10_000);
      gate = new TenantBudget(rate, budget.tenantBurst());
    }
    EnumMap<MessageType, TokenBucket> limits = new EnumMap<>(MessageType.class);
    addLimit(limits, MessageType.OTP, budget.otpLimitBasisPoints(), scenario);
    addLimit(limits, MessageType.ADVERTISEMENT, budget.advertisementLimitBasisPoints(), scenario);
    QueueLayout layout = QueueLayout.of(
        QueueLayout.Topology.TENANT_AND_TYPE, scenario.tenants(), scenario.messages());
    DepartureTimePolicy business = new BusinessDepartureTimePolicy(scenario.deliveryDelay());
    AgingDeparturePolicy aging = agingThreshold > 0
        ? new AgingDeparturePolicy(business, agingThreshold) : null;
    DepartureTimePolicy departure = aging != null ? aging : business;

    if (sharding == null) {
      return classPolicy("E3-WDEDF-BUDGET", scenario, baseQuantum, budget, gate, limits, layout,
          departure, aging);
    }
    List<SchedulingPolicy> shards = new ArrayList<>();
    for (int s = 0; s < sharding.shards(); s++) {
      shards.add(classPolicy("shard" + s, scenario, baseQuantum, budget, gate, limits, layout,
          departure, aging));
    }
    return new ShardedPolicy(aging != null ? "E5-SHARD-AGING" : "E4-SHARD",
        new ShardAssignment(sharding.shards(), sharding.subsetSize(), sharding.seed()),
        new ShardPool(sharding.shards(), scenario.serviceModel(), scenario.providerCapacity()),
        shards, sharding.failures());
  }

  private static ServiceClassPolicy classPolicy(String name, Scenario scenario, int baseQuantum,
      ServiceBudgetConfig budget, DispatchGate gate, EnumMap<MessageType, TokenBucket> limits,
      QueueLayout layout, DepartureTimePolicy departure, AgingDeparturePolicy aging) {
    EnumMap<MessageType, SchedulingPolicy> classes = new EnumMap<>(MessageType.class);
    int minWeight = scenario.tenants().stream().mapToInt(Tenant::weight).min().orElseThrow();
    for (MessageType type : MessageType.values()) {
      Map<String, Integer> weights = new LinkedHashMap<>();
      for (Tenant tenant : scenario.tenants()) {
        weights.put(tenant.id() + "/" + type, tenant.weight());
      }
      Map<String, Long> quanta = ReservationModel
          .proportionalToWeights(weights, scenario.providerCapacity())
          .equationOneQuanta((long) baseQuantum * minWeight).quanta();
      classes.put(type, new WdedfScheduler(quanta, departure, layout.classifier(),
          new WdedfConfig("WDEDF-" + type, StandardArms.DEFAULT_LAMBDA_BP,
              IdleDebtPolicy.PAPER_RESET), gate));
    }
    EnumMap<MessageType, Integer> classWeights = new EnumMap<>(MessageType.class);
    classWeights.put(MessageType.OTP, budget.otpWeight());
    classWeights.put(MessageType.ADVERTISEMENT, budget.advertisementWeight());
    return new ServiceClassPolicy(name, classes, classWeights, limits, gate, aging);
  }

  private static void addLimit(EnumMap<MessageType, TokenBucket> limits, MessageType type,
      int basisPoints, Scenario scenario) {
    if (basisPoints > 0) {
      long rate = Math.max(1, (long) scenario.providerCapacity() * basisPoints / 10_000);
      limits.put(type, new TokenBucket(rate, Math.max(1, rate / 10)));
    }
  }

  private static Map<String, String> settings(int baseQuantum, ServiceBudgetConfig budget,
      ShardingConfig sharding, long aging) {
    Map<String, String> s = new LinkedHashMap<>();
    s.put("scheduler", "WDEDF");
    s.put("departure", "BUSINESS");
    s.put("lambdaBasisPoints", String.valueOf(StandardArms.DEFAULT_LAMBDA_BP));
    s.put("topology", "TENANT_AND_TYPE (class-partitioned)");
    s.put("baseQuantum", String.valueOf(baseQuantum));
    s.put("budget.otpWeight", String.valueOf(budget.otpWeight()));
    s.put("budget.advertisementWeight", String.valueOf(budget.advertisementWeight()));
    s.put("budget.otpLimitBasisPoints", String.valueOf(budget.otpLimitBasisPoints()));
    s.put("budget.advertisementLimitBasisPoints",
        String.valueOf(budget.advertisementLimitBasisPoints()));
    s.put("budget.tenantRateBasisPoints", String.valueOf(budget.tenantRateBasisPoints()));
    s.put("budget.tenantBurst", String.valueOf(budget.tenantBurst()));
    s.put("sharding.enabled", String.valueOf(sharding != null));
    if (sharding != null) {
      s.put("sharding.shards", String.valueOf(sharding.shards()));
      s.put("sharding.subsetSize", String.valueOf(sharding.subsetSize()));
      s.put("sharding.seed", String.valueOf(sharding.seed()));
      s.put("failures", sharding.failures().toString());
    } else {
      s.put("failures", "[]");
    }
    s.put("aging.thresholdMicros", aging > 0 ? String.valueOf(aging) : "disabled");
    return s;
  }
}
