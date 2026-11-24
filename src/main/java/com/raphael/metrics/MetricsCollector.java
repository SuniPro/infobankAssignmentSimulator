package com.raphael.metrics;

import com.raphael.core.VirtualClock;
import com.raphael.domain.Message;
import com.raphael.domain.Tenant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 시뮬레이션 1회 실행 동안 테넌트별 측정값을 누적한다. 수집만 담당하며, 불변
 * {@link SimulationResult}를 만들어 낼 뿐 출력은 하지 않는다.
 *
 * <p>품질 지표 외에 서로 독립적인 두 가지 감사를 수행한다.
 * <ul>
 *   <li><b>Provider 용량 감사</b>: 최근 {@code tps}개 디스패치 타임스탬프를 담은 링 버퍼로,
 *       반개구간 1초 윈도우 안에 {@code tps}개를 초과하는 디스패치가 들어가게 만드는 디스패치를
 *       모두 센다. 이 감사는 provider 자체의 검사에 의존하지 않는다.</li>
 *   <li><b>Backlog 추적</b>: 테넌트는 수신했지만 아직 디스패치되지 않은 메시지가 있는 동안
 *       backlog 상태다. 이를 통해 공정성 평가의 전제 조건인, 테넌트가 관측 윈도우 전체에 걸쳐
 *       backlog 상태였는지를 판정한다.</li>
 * </ul>
 *
 * <p>일회용이며 스레드 안전하지 않다.
 */
public final class MetricsCollector {

  private final List<Tenant> tenants;
  private final Map<String, Accumulator> accumulators = new HashMap<>();
  private final ObservationWindow window;

  private final long[] recentDispatches;
  private long dispatchCount;
  private long capacityViolations;

  /**
   * @param tenants 메시지를 제출할 수 있는 모든 테넌트(보고 순서대로)
   * @param providerTps 감사 기준이 되는 provider 용량
   * @param window 서비스 할당을 측정할 관측 윈도우, 없으면 empty
   */
  public MetricsCollector(
      List<Tenant> tenants, int providerTps, Optional<ObservationWindow> window) {
    this.tenants = List.copyOf(tenants);
    for (Tenant tenant : this.tenants) {
      accumulators.put(tenant.id(), new Accumulator());
    }
    this.window = window.orElse(null);
    this.recentDispatches = new long[providerTps];
  }

  public void onArrival(Message message, long now) {
    Accumulator acc = accumulatorFor(message);
    acc.received++;
    if (acc.firstArrival < 0) {
      acc.firstArrival = now;
    }
    if (acc.pending++ == 0) {
      acc.backlogStart = now;
    }
  }

  public void onDispatch(Message message, long now) {
    Accumulator acc = accumulatorFor(message);
    acc.dispatched++;
    acc.dispatchedCost += message.cost();
    acc.lastDispatch = now;
    acc.queueLatencies.add(now - message.arrivalTime());
    if (window != null && window.contains(now)) {
      acc.windowDispatched++;
      acc.windowCost += message.cost();
    }
    if (--acc.pending == 0) {
      closeBacklogInterval(acc, now);
    }
    auditCapacity(now);
  }

  public void onDelivery(Message message, long now) {
    Accumulator acc = accumulatorFor(message);
    long latency = now - message.arrivalTime();
    acc.delivered++;
    acc.lastDelivery = now;
    acc.endToEndSum += latency;
    acc.endToEndLatencies.add(latency);
    if (message.missesDeadline(now)) {
      acc.deadlineMisses++;
    }
  }

  /** 수집한 데이터를 불변 결과로 고정한다. */
  public SimulationResult buildResult(
      String policyName, long virtualElapsed, long schedulerSelections, long schedulerOperations) {
    Map<String, TenantMetrics> perTenant = new LinkedHashMap<>();
    List<WindowedFairness.TenantShare> shares = new ArrayList<>();
    for (Tenant tenant : tenants) {
      Accumulator acc = accumulators.get(tenant.id());
      perTenant.put(tenant.id(), new TenantMetrics(
          tenant.id(),
          tenant.weight(),
          acc.received,
          acc.dispatched,
          acc.delivered,
          acc.deadlineMisses,
          acc.dispatchedCost,
          LatencySummary.of(acc.queueLatencies),
          LatencySummary.of(acc.endToEndLatencies),
          acc.endToEndSum,
          optional(acc.firstArrival),
          optional(acc.lastDispatch),
          optional(acc.lastDelivery)));
      if (window != null) {
        boolean stillBacklogged = acc.pending > 0 && acc.backlogStart <= window.start();
        shares.add(new WindowedFairness.TenantShare(
            tenant.id(), tenant.weight(), acc.windowDispatched, acc.windowCost,
            acc.coversWindow || stillBacklogged));
      }
    }
    Optional<WindowedFairness> fairness = window == null
        ? Optional.empty()
        : Optional.of(WindowedFairness.evaluate(window, shares));
    return new SimulationResult(
        policyName, virtualElapsed, perTenant, schedulerSelections, schedulerOperations,
        capacityViolations, fairness);
  }

  private Accumulator accumulatorFor(Message message) {
    Accumulator acc = accumulators.get(message.tenantId());
    if (acc == null) {
      throw new IllegalArgumentException("Unknown tenant " + message.tenantId());
    }
    return acc;
  }

  /**
   * 테넌트의 마지막 대기 메시지가 {@code now}에 디스패치되어 backlog 구간
   * {@code [backlogStart, now]}가 끝났다. 이 구간이 윈도우 시작 시점 이전(같은 시점 포함)에
   * 시작했고 윈도우 끝 시점 이후(같은 시점 포함)에 끝난 경우에만, 테넌트는
   * {@code [start, end)} 내내 처리할 작업을 가지고 있었던 것이다.
   */
  private void closeBacklogInterval(Accumulator acc, long now) {
    if (window != null && acc.backlogStart <= window.start() && now >= window.end()) {
      acc.coversWindow = true;
    }
  }

  /**
   * 슬라이딩 윈도우 감사: {@code tps}칸 이전의 디스패치는 현재 디스패치보다 최소 1초 앞서야
   * 한다. 그렇지 않으면 1초 안에 {@code tps + 1}개의 디스패치가 들어가게 된다.
   */
  private void auditCapacity(long now) {
    int slot = (int) (dispatchCount % recentDispatches.length);
    if (dispatchCount >= recentDispatches.length
        && now - recentDispatches[slot] < VirtualClock.MICROS_PER_SECOND) {
      capacityViolations++;
    }
    recentDispatches[slot] = now;
    dispatchCount++;
  }

  private static OptionalLong optional(long value) {
    return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
  }

  private static final class Accumulator {
    long received;
    long dispatched;
    long delivered;
    long deadlineMisses;
    long dispatchedCost;
    long endToEndSum;
    long firstArrival = -1;
    long lastDispatch = -1;
    long lastDelivery = -1;
    final LongSamples queueLatencies = new LongSamples();
    final LongSamples endToEndLatencies = new LongSamples();

    long pending;
    long backlogStart;
    boolean coversWindow;
    long windowDispatched;
    long windowCost;
  }
}
