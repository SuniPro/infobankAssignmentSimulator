package com.raphael.metrics;

import com.raphael.core.VirtualClock;
import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.domain.Tenant;
import com.raphael.provider.ServiceModel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.ToLongFunction;

/**
 * 시뮬레이션 1회 실행 동안 측정값을 누적한다. 수집만 담당하며, 불변 {@link SimulationResult} 를
 * 만들어 낼 뿐 출력은 하지 않는다.
 *
 * <p>집계 단위는 세 가지다: 테넌트, 메시지 유형, 테넌트×유형. 같은 테넌트의 OTP 와 광고를 따로
 * 평가할 수 있도록 하기 위함이다. 테넌트 단위의 {@link TenantMetrics} 는 v0.2 와 같은 정의를
 * 유지한다.
 *
 * <p>품질 지표 외에 서로 독립적인 두 가지 감사를 수행한다.
 * <ul>
 *   <li><b>Provider 용량 감사</b>: MESSAGE_RATE 에서는 최근 {@code capacity} 개 디스패치
 *       타임스탬프를 담은 링 버퍼로, 반개구간 1초 윈도우 안에 {@code capacity} 개를 초과하는
 *       디스패치를 센다. COST_RATE 에서는 연속 디스패치 간격이 직전 메시지의 최소 전송 시간
 *       {@code floor(cost × 1s / capacity)} 보다 짧은 경우를 센다. provider 자체 검사와 독립이다.</li>
 *   <li><b>Backlog 추적</b>: 테넌트는 수신했지만 아직 디스패치되지 않은 메시지가 있는 동안
 *       backlog 상태다. 공정성 평가의 전제 조건(관측 구간 전체 backlog)을 판정한다.</li>
 * </ul>
 *
 * <p>일회용이며 스레드 안전하지 않다.
 */
public final class MetricsCollector {

  private final List<Tenant> tenants;
  private final Map<String, TenantState> states = new HashMap<>();
  private final EnumMap<MessageType, Group> typeGroups = new EnumMap<>(MessageType.class);
  private final ObservationWindow window;
  private final ServiceModel serviceModel;
  private final int capacity;
  private final long deliveryDelay;
  private final ToLongFunction<Message> paperDepartureTime;
  private final long totalWeight;
  private final boolean trackShortWindows;

  private final long[] recentDispatches;
  private long dispatchCount;
  private long capacityViolations;
  private long previousDispatchTime = -1;
  private int previousDispatchCost;
  private long windowTotalCost;

  /** v0.2 호환 생성자: MESSAGE_RATE, 배달 지연 0 으로 slack 계산, 논문 출발 지표 없음. */
  public MetricsCollector(
      List<Tenant> tenants, int providerTps, Optional<ObservationWindow> window) {
    this(tenants, ServiceModel.MESSAGE_RATE, providerTps, 0, window, null);
  }

  /**
   * @param tenants 메시지를 제출할 수 있는 모든 테넌트(보고 순서대로)
   * @param serviceModel provider 점유 모델 (용량 감사 방식 결정)
   * @param capacity provider 용량
   * @param deliveryDelay slack 계산에 쓰는 예상 배달 지연 (μs)
   * @param window 서비스 할당을 측정할 관측 구간, 없으면 empty
   * @param paperDepartureTime 메시지의 논문 식 (2) T̂_D. {@code null} 이면 위반을 계수하지 않는다
   */
  public MetricsCollector(
      List<Tenant> tenants,
      ServiceModel serviceModel,
      int capacity,
      long deliveryDelay,
      Optional<ObservationWindow> window,
      ToLongFunction<Message> paperDepartureTime) {
    this.tenants = List.copyOf(tenants);
    long weights = 0;
    for (Tenant tenant : this.tenants) {
      states.put(tenant.id(), new TenantState(tenant));
      weights += tenant.weight();
    }
    for (MessageType type : MessageType.values()) {
      typeGroups.put(type, new Group());
    }
    this.totalWeight = weights;
    this.window = window.orElse(null);
    this.serviceModel = serviceModel;
    this.capacity = capacity;
    this.deliveryDelay = deliveryDelay;
    this.paperDepartureTime = paperDepartureTime;
    this.recentDispatches =
        serviceModel == ServiceModel.MESSAGE_RATE ? new long[capacity] : new long[0];
    // 하위 구간 cost 표는 테넌트 수 × 하위 구간 수 만큼의 메모리를 쓰므로 상한을 둔다.
    this.trackShortWindows = this.window != null
        && (long) this.tenants.size() * this.window.shortWindowCount() <= 4_000_000L;
    if (trackShortWindows) {
      for (TenantState state : states.values()) {
        state.shortWindowCost = new long[this.window.shortWindowCount()];
      }
    }
  }

  public void onArrival(Message message, long now) {
    TenantState state = stateFor(message);
    state.group.received++;
    state.typeGroup(message.type()).received++;
    typeGroups.get(message.type()).received++;
    if (state.firstArrival < 0) {
      state.firstArrival = now;
    }
    if (state.pending++ == 0) {
      state.backlogStart = now;
    }
  }

  public void onDispatch(Message message, long now) {
    TenantState state = stateFor(message);
    long queueLatency = now - message.arrivalTime();
    long slack = message.deadline() - deliveryDelay - now;
    boolean violation = paperDepartureTime != null
        && now > paperDepartureTime.applyAsLong(message);
    state.group.recordDispatch(message, queueLatency, slack, violation);
    state.typeGroup(message.type()).recordDispatch(message, queueLatency, slack, violation);
    typeGroups.get(message.type()).recordDispatch(message, queueLatency, slack, violation);
    state.lastDispatch = now;
    if (window != null && window.contains(now)) {
      recordWindowService(state, message.cost(), now);
    }
    if (--state.pending == 0) {
      closeBacklogInterval(state, now);
    }
    auditCapacity(now, message.cost());
  }

  public void onDelivery(Message message, long now) {
    TenantState state = stateFor(message);
    long latency = now - message.arrivalTime();
    boolean missed = message.missesDeadline(now);
    long lateness = now - message.deadline();
    state.group.recordDelivery(latency, missed, lateness, now);
    state.typeGroup(message.type()).recordDelivery(latency, missed, lateness, now);
    typeGroups.get(message.type()).recordDelivery(latency, missed, lateness, now);
  }

  /** v0.2 호환: 스케줄러 진단값 없이 결과를 만든다. */
  public SimulationResult buildResult(
      String policyName, long virtualElapsed, long schedulerSelections, long schedulerOperations) {
    return buildResult(policyName, virtualElapsed, schedulerSelections, schedulerOperations,
        Map.of());
  }

  /** 수집한 데이터를 불변 결과로 고정한다. */
  public SimulationResult buildResult(
      String policyName,
      long virtualElapsed,
      long schedulerSelections,
      long schedulerOperations,
      Map<String, Long> schedulerDiagnostics) {
    Map<String, TenantMetrics> perTenant = new LinkedHashMap<>();
    Map<String, GroupMetrics> tenantGroups = new LinkedHashMap<>();
    Map<String, GroupMetrics> tenantTypeGroups = new LinkedHashMap<>();
    Map<String, GroupMetrics> byType = new LinkedHashMap<>();
    List<WindowedFairness.TenantShare> shares = new ArrayList<>();
    long[][] shortWindowCost = trackShortWindows ? new long[tenants.size()][] : null;
    int index = 0;
    for (Tenant tenant : tenants) {
      TenantState state = states.get(tenant.id());
      Group g = state.group;
      perTenant.put(tenant.id(), new TenantMetrics(
          tenant.id(),
          tenant.weight(),
          g.received,
          g.dispatched,
          g.delivered,
          g.deadlineMisses,
          g.dispatchedCost,
          LatencySummary.of(g.queueLatencies),
          LatencySummary.of(g.endToEndLatencies),
          g.endToEndSum,
          optional(state.firstArrival),
          optional(state.lastDispatch),
          optional(g.lastDelivery)));
      tenantGroups.put(tenant.id(), g.freeze(tenant.id()));
      for (Map.Entry<MessageType, Group> entry : state.byType.entrySet()) {
        String key = tenant.id() + "/" + entry.getKey();
        tenantTypeGroups.put(key, entry.getValue().freeze(key));
      }
      if (window != null) {
        boolean stillBacklogged = state.pending > 0 && state.backlogStart <= window.start();
        long finalGap = window.end() - Math.max(state.lastWindowDispatch, window.start());
        double finalBehind = windowTotalCost * (double) tenant.weight() / totalWeight
            - state.windowCost;
        shares.add(new WindowedFairness.TenantShare(
            tenant.id(), tenant.weight(), state.windowDispatched, state.windowCost,
            state.coversWindow || stillBacklogged,
            Math.max(state.maxServiceGap, finalGap),
            Math.max(state.maxLagBehind, finalBehind),
            state.maxLagAhead));
        if (shortWindowCost != null) {
          shortWindowCost[index] = state.shortWindowCost;
        }
      }
      index++;
    }
    for (Map.Entry<MessageType, Group> entry : typeGroups.entrySet()) {
      if (entry.getValue().received > 0) {
        byType.put(entry.getKey().name(), entry.getValue().freeze(entry.getKey().name()));
      }
    }
    Optional<WindowedFairness> fairness = window == null
        ? Optional.empty()
        : Optional.of(WindowedFairness.evaluate(window, shares, shortWindowCost));
    return new SimulationResult(
        policyName, virtualElapsed, perTenant, schedulerSelections, schedulerOperations,
        capacityViolations, fairness, tenantGroups, byType, tenantTypeGroups,
        schedulerDiagnostics);
  }

  private TenantState stateFor(Message message) {
    TenantState state = states.get(message.tenantId());
    if (state == null) {
      throw new IllegalArgumentException("Unknown tenant " + message.tenantId());
    }
    return state;
  }

  /**
   * 관측 구간 안의 서비스 기록. 이상적 가중 할당 대비 지연(lag)은 해당 테넌트의 dispatch 직전
   * (뒤처짐 최대 후보)과 직후(앞섬 최대 후보)에만 바뀐 극값을 가지므로 O(1) 로 추적할 수 있다.
   */
  private void recordWindowService(TenantState state, int cost, long now) {
    double share = state.tenant.weight() / (double) totalWeight;
    state.maxLagBehind = Math.max(state.maxLagBehind,
        windowTotalCost * share - state.windowCost);
    state.maxServiceGap = Math.max(state.maxServiceGap,
        now - Math.max(state.lastWindowDispatch, window.start()));
    state.lastWindowDispatch = now;
    state.windowDispatched++;
    state.windowCost += cost;
    windowTotalCost += cost;
    state.maxLagAhead = Math.max(state.maxLagAhead,
        state.windowCost - windowTotalCost * share);
    if (state.shortWindowCost != null) {
      state.shortWindowCost[(int) ((now - window.start()) / window.shortWindowLength())] += cost;
    }
  }

  /**
   * backlog 구간 {@code [backlogStart, now]} 는 테넌트의 마지막 대기 메시지가 {@code now} 에
   * 디스패치되면서 끝났다. 이 구간이 관측 구간 시작 이전에 시작해 끝 이후에 끝났을 때에만
   * 테넌트는 {@code [start, end)} 내내 보낼 작업이 있었다.
   */
  private void closeBacklogInterval(TenantState state, long now) {
    if (window != null && state.backlogStart <= window.start() && now >= window.end()) {
      state.coversWindow = true;
    }
  }

  private void auditCapacity(long now, int cost) {
    if (serviceModel == ServiceModel.MESSAGE_RATE) {
      // 슬라이딩 윈도우 감사: capacity 칸 전의 디스패치는 현재 디스패치보다 최소 1초 앞서야 한다.
      int slot = (int) (dispatchCount % recentDispatches.length);
      if (dispatchCount >= recentDispatches.length
          && now - recentDispatches[slot] < VirtualClock.MICROS_PER_SECOND) {
        capacityViolations++;
      }
      recentDispatches[slot] = now;
    } else {
      long minimumGap = (long) previousDispatchCost * VirtualClock.MICROS_PER_SECOND / capacity;
      if (previousDispatchTime >= 0 && now - previousDispatchTime < minimumGap) {
        capacityViolations++;
      }
      previousDispatchTime = now;
      previousDispatchCost = cost;
    }
    dispatchCount++;
  }

  private static OptionalLong optional(long value) {
    return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
  }

  /** 한 집계 그룹의 누적값. */
  private static final class Group {
    long received;
    long dispatched;
    long delivered;
    long deadlineMisses;
    long paperViolations;
    long dispatchedCost;
    long endToEndSum;
    long queueLatencySum;
    long lastDelivery = -1;
    final LongSamples queueLatencies = new LongSamples();
    final LongSamples endToEndLatencies = new LongSamples();
    final LongSamples slack = new LongSamples();
    final LongSamples lateness = new LongSamples();

    void recordDispatch(Message message, long queueLatency, long slackAtDispatch,
        boolean paperViolation) {
      dispatched++;
      dispatchedCost += message.cost();
      queueLatencies.add(queueLatency);
      queueLatencySum += queueLatency;
      slack.add(slackAtDispatch);
      if (paperViolation) {
        paperViolations++;
      }
    }

    void recordDelivery(long latency, boolean missed, long lateBy, long now) {
      delivered++;
      lastDelivery = now;
      endToEndSum += latency;
      endToEndLatencies.add(latency);
      if (missed) {
        deadlineMisses++;
        lateness.add(lateBy);
      }
    }

    GroupMetrics freeze(String key) {
      return new GroupMetrics(key, received, dispatched, delivered, deadlineMisses,
          paperViolations, dispatchedCost, LatencySummary.of(queueLatencies), queueLatencySum,
          LatencySummary.of(endToEndLatencies), endToEndSum, DistributionSummary.of(slack),
          LatencySummary.of(lateness), optional(lastDelivery));
    }
  }

  /** 테넌트별 상태: 그룹 누적값, backlog 추적, 관측 구간 서비스. */
  private static final class TenantState {
    final Tenant tenant;
    final Group group = new Group();
    final EnumMap<MessageType, Group> byType = new EnumMap<>(MessageType.class);
    long firstArrival = -1;
    long lastDispatch = -1;

    long pending;
    long backlogStart;
    boolean coversWindow;
    long windowDispatched;
    long windowCost;
    long lastWindowDispatch = -1;
    long maxServiceGap;
    double maxLagBehind;
    double maxLagAhead;
    long[] shortWindowCost;

    TenantState(Tenant tenant) {
      this.tenant = tenant;
    }

    Group typeGroup(MessageType type) {
      Group g = byType.get(type);
      if (g == null) {
        g = new Group();
        byType.put(type, g);
      }
      return g;
    }
  }
}
