package com.raphael.core;

import com.raphael.domain.Message;
import com.raphael.metrics.MetricsCollector;
import com.raphael.metrics.SimulationResult;
import com.raphael.provider.ProviderSimulator;
import com.raphael.scheduling.SchedulingPolicy;
import java.util.PriorityQueue;

/**
 * 단일 스레드 이산 사건(discrete-event) 시뮬레이션 엔진.
 *
 * <p>엔진은 가상 시간과 이벤트 큐를 소유한다. {@link SchedulingPolicy}에는 provider가 준비되었을 때
 * <em>어떤</em> 메시지를 보낼지 결정할 때만 문의하며, <em>언제</em> 준비되는지는 provider가
 * 결정한다. 미처리 {@link EventType#DISPATCH_READY} 이벤트는 어느 시점에든 최대 하나이며, 정책이
 * 대기 메시지를 보유하고 있는 동안에는 항상 하나가 존재한다. 따라서 정책이 계약을 지키는 한
 * 엔진은 work-conserving하다.
 *
 * <p>엔진 인스턴스는 일회용이다. 실행마다 새 인스턴스(와 새 협력 객체)를 생성해야 한다.
 */
public final class SimulationEngine {

  private final VirtualClock clock = new VirtualClock();
  private final PriorityQueue<SimulationEvent> events =
      new PriorityQueue<>(SimulationEvent.ORDER);

  private final SchedulingPolicy scheduler;
  private final ProviderSimulator provider;
  private final MetricsCollector metrics;
  private final SimulationObserver observer;
  private final boolean measureSchedulerTime;

  private long sequence;
  private boolean dispatchScheduled;
  private boolean started;
  private long lastDispatchTime = -1;

  private long schedulerSelections;
  private long schedulerTimedCalls;
  private long schedulerElapsedNanos;

  public SimulationEngine(
      SchedulingPolicy scheduler,
      ProviderSimulator provider,
      MetricsCollector metrics,
      SimulationObserver observer,
      boolean measureSchedulerTime) {
    this.scheduler = requireNonNull(scheduler, "scheduler");
    this.provider = requireNonNull(provider, "provider");
    this.metrics = requireNonNull(metrics, "metrics");
    this.observer = requireNonNull(observer, "observer");
    this.measureSchedulerTime = measureSchedulerTime;
  }

  /** {@code message}의 도착을 해당 도착 시각에 예약한다. */
  public void scheduleArrival(Message message) {
    if (started) {
      throw new IllegalStateException("Arrivals must be scheduled before run()");
    }
    schedule(message.arrivalTime(), EventType.ARRIVAL, message);
  }

  /**
   * 이벤트 큐가 빌 때까지 이벤트를 처리한다.
   *
   * @return 결정론적 시뮬레이션 지표. 실행 시간 측정값은
   *     {@link #schedulerElapsedNanos()}와 {@link #schedulerTimedCalls()}로 조회한다
   */
  public SimulationResult run() {
    if (started) {
      throw new IllegalStateException("SimulationEngine instances are single-use");
    }
    started = true;

    SimulationEvent event;
    while ((event = events.poll()) != null) {
      clock.advanceTo(event.timestamp());
      switch (event.type()) {
        case ARRIVAL -> handleArrival(event.message());
        case DISPATCH_READY -> handleDispatch();
        case DELIVERED -> handleDelivery(event.message());
      }
    }

    if (!scheduler.isEmpty()) {
      throw new IllegalStateException(
          "Simulation ended with " + scheduler.pendingCount() + " undispatched messages");
    }
    return metrics.buildResult(
        scheduler.name(), clock.now(), schedulerSelections, scheduler.operationCount());
  }

  /** 측정한 경우, enqueue와 selectNext 내부에서 소요된 총 경과 시간(CPU 시간 아님, 나노초). */
  public long schedulerElapsedNanos() {
    return schedulerElapsedNanos;
  }

  /** 시간을 측정한 스케줄러 호출 횟수(enqueue 횟수와 선택 횟수의 합). */
  public long schedulerTimedCalls() {
    return schedulerTimedCalls;
  }

  private void schedule(long timestamp, EventType type, Message message) {
    if (timestamp < clock.now()) {
      throw new IllegalArgumentException(
          "Cannot schedule a past event: now=" + clock.now() + ", timestamp=" + timestamp);
    }
    events.offer(new SimulationEvent(timestamp, sequence++, type, message));
  }

  private void scheduleDispatch() {
    if (dispatchScheduled || scheduler.isEmpty()) {
      return;
    }
    dispatchScheduled = true;
    schedule(Math.max(clock.now(), provider.nextAvailableTime()), EventType.DISPATCH_READY, null);
  }

  private void handleArrival(Message message) {
    long now = clock.now();
    metrics.onArrival(message, now);
    observer.onArrival(message, now);
    if (measureSchedulerTime) {
      long startedAt = System.nanoTime();
      scheduler.enqueue(message);
      schedulerElapsedNanos += System.nanoTime() - startedAt;
      schedulerTimedCalls++;
    } else {
      scheduler.enqueue(message);
    }
    scheduleDispatch();
  }

  private void handleDispatch() {
    dispatchScheduled = false;
    long now = clock.now();

    // 방어적 불변식: provider는 발송 간격을 최소 floor(1s / tps) >= 1us로 벌리므로, 두 발송이
    // 같은 timestamp를 가질 수 없다. 이 검사는 향후의 회귀를 같은 timestamp에서 끝없이 도는
    // 발송 루프 대신 즉시 실패로 드러나게 한다.
    if (now <= lastDispatchTime) {
      throw new IllegalStateException("Repeated dispatch at virtual time " + now);
    }

    Message message;
    if (measureSchedulerTime) {
      long startedAt = System.nanoTime();
      message = scheduler.selectNext(now);
      schedulerElapsedNanos += System.nanoTime() - startedAt;
      schedulerTimedCalls++;
    } else {
      message = scheduler.selectNext(now);
    }
    schedulerSelections++;

    if (message == null) {
      // DISPATCH_READY 이벤트는 정책이 대기 메시지가 있다고 보고하는 동안에만 존재하므로,
      // null 선택은 정책이 work-conserving 계약을 위반했음을 뜻한다.
      throw new IllegalStateException(
          scheduler.name() + " returned no message while " + scheduler.pendingCount()
              + " messages are pending");
    }

    lastDispatchTime = now;
    metrics.onDispatch(message, now);
    observer.onDispatch(message, now);
    schedule(provider.dispatch(now), EventType.DELIVERED, message);
    scheduleDispatch();
  }

  private void handleDelivery(Message message) {
    long now = clock.now();
    metrics.onDelivery(message, now);
    observer.onDelivery(message, now);
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }
}
