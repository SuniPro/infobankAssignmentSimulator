package com.raphael.legacy;

import java.util.*;

/**
 * v0.1 단일 파일 시뮬레이터(이전 com.raphael.SimulatorApplication)를 그대로 복사한 것으로,
 * 리팩터링된 FIFO 기준선에 대한 차등 검증 오라클로만 테스트 범위에 남겨 둔다.
 * 추가된 것은 "오라클 접근자"로 표시된 읽기 전용 접근자뿐이다.
 */
public class LegacySimulatorReference {

  private static final long SECOND = 1_000_000L;

  enum MessageType {
    ADVERTISEMENT,
    OTP
  }

  enum EventType {
    DELIVERED,
    ARRIVAL,
    DISPATCH_READY
  }

  record Message(
      long id,
      String tenantId,
      MessageType type,
      long arrivalTime,
      long deadline
  ) {}

  record SimulationEvent(
      long timestamp,
      long sequence,
      EventType type,
      Message message
  ) {}

  record Scenario(
      int campaignMessages,
      int providerTps,
      long otpArrivalTime,
      long otpTtl,
      long deliveryDelay
  ) {
    Scenario {
      if (campaignMessages < 0
          || providerTps <= 0
          || providerTps > SECOND
          || otpArrivalTime < 0
          || otpTtl <= 0
          || deliveryDelay < 0) {
        throw new IllegalArgumentException(
            "Invalid scenario parameters");
      }
    }
  }

  static final class VirtualClock {

    private long now;

    public long now() {
      return now;
    }

    public void advanceTo(long timestamp) {
      if (timestamp < now) {
        throw new IllegalStateException(
            "Virtual clock cannot move backwards");
      }

      now = timestamp;
    }
  }

  interface SchedulingPolicy {

    void enqueue(Message message);

    Optional<Message> selectNext(long now);

    boolean isEmpty();
  }

  static final class FifoScheduler
      implements SchedulingPolicy {

    private final Deque<Message> queue =
        new ArrayDeque<>();

    @Override
    public void enqueue(Message message) {
      queue.addLast(message);
    }

    @Override
    public Optional<Message> selectNext(long now) {
      return Optional.ofNullable(queue.pollFirst());
    }

    @Override
    public boolean isEmpty() {
      return queue.isEmpty();
    }
  }

  static final class ProviderSimulator {

    private final int tps;
    private final long deliveryDelay;

    private long nextAvailableTime;
    private long remainder;

    ProviderSimulator(int tps, long deliveryDelay) {
      this.tps = tps;
      this.deliveryDelay = deliveryDelay;
    }

    public long nextAvailableTime() {
      return nextAvailableTime;
    }

    public long dispatch(long now) {
      if (now < nextAvailableTime) {
        throw new IllegalStateException(
            "Provider rate limit exceeded");
      }

      // Preserve fractional microseconds to avoid
      // cumulative rate-limit rounding errors.
      long numerator = SECOND + remainder;

      nextAvailableTime = now + numerator / tps;
      remainder = numerator % tps;

      return now + deliveryDelay;
    }
  }

  static final class MetricsCollector {

    static final class TenantMetrics {

      long received;
      long dispatched;
      long delivered;
      long deadlineMisses;
      long deliveryLatencySum;
      long lastDelivery;

      final List<Long> queueLatencies =
          new ArrayList<>();
    }

    private final Map<String, TenantMetrics> metrics =
        new TreeMap<>();

    // 오라클 접근자
    Map<String, TenantMetrics> tenants() {
      return metrics;
    }

    private TenantMetrics get(Message message) {
      return metrics.computeIfAbsent(
          message.tenantId(),
          ignored -> new TenantMetrics()
      );
    }

    public void onArrival(Message message) {
      get(message).received++;
    }

    public void onDispatch(Message message, long now) {
      TenantMetrics metric = get(message);

      metric.dispatched++;
      metric.queueLatencies.add(
          now - message.arrivalTime()
      );
    }

    public void onDelivery(Message message, long now) {
      TenantMetrics metric = get(message);

      metric.delivered++;
      metric.lastDelivery = now;
      metric.deliveryLatencySum +=
          now - message.arrivalTime();

      if (now > message.deadline()) {
        metric.deadlineMisses++;
      }
    }

    private double percentile(
        List<Long> sorted,
        double percentile
    ) {
      if (sorted.isEmpty()) {
        return 0;
      }

      int index = Math.max(
          0,
          (int) Math.ceil(
              sorted.size() * percentile
          ) - 1
      );

      return sorted.get(index) / (double) SECOND;
    }

    public void printReport() {
      System.out.println("\n=== Simulation Report ===");

      for (var entry : metrics.entrySet()) {
        TenantMetrics metric = entry.getValue();

        metric.queueLatencies.sort(
            Comparator.naturalOrder()
        );

        long incomplete =
            metric.received - metric.delivered;

        double missRate =
            100.0 * (metric.deadlineMisses
                + incomplete)
                / metric.received;

        System.out.printf(
            "%nTenant: %s%n",
            entry.getKey()
        );

        System.out.printf(
            "Received: %,d%n",
            metric.received
        );

        System.out.printf(
            "Dispatched: %,d%n",
            metric.dispatched
        );

        System.out.printf(
            "Delivered: %,d%n",
            metric.delivered
        );

        System.out.printf(
            "Deadline miss rate: %.2f%%%n",
            missRate
        );

        System.out.printf(
            "Queue p50: %.3f s%n",
            percentile(metric.queueLatencies, 0.50)
        );

        System.out.printf(
            "Queue p95: %.3f s%n",
            percentile(metric.queueLatencies, 0.95)
        );

        System.out.printf(
            "Queue p99: %.3f s%n",
            percentile(metric.queueLatencies, 0.99)
        );

        System.out.printf(
            "Queue max: %.3f s%n",
            percentile(metric.queueLatencies, 1.0)
        );

        if (metric.delivered > 0) {
          System.out.printf(
              "Average E2E: %.3f s%n",
              metric.deliveryLatencySum
                  / (double) metric.delivered
                  / SECOND
          );
        }

        System.out.printf(
            "Last delivery: %.3f s%n",
            metric.lastDelivery / (double) SECOND
        );
      }
    }
  }

  static final class SimulationEngine {

    private final VirtualClock clock =
        new VirtualClock();

    private final PriorityQueue<SimulationEvent> events =
        new PriorityQueue<>(
            Comparator
                .comparingLong(
                    SimulationEvent::timestamp)
                .thenComparing(
                    SimulationEvent::type)
                .thenComparingLong(
                    SimulationEvent::sequence)
        );

    private final SchedulingPolicy scheduler;
    private final ProviderSimulator provider;

    private final MetricsCollector metrics =
        new MetricsCollector();

    private long sequence;
    private boolean dispatchScheduled;

    SimulationEngine(
        SchedulingPolicy scheduler,
        ProviderSimulator provider
    ) {
      this.scheduler = scheduler;
      this.provider = provider;
    }

    public void schedule(
        long timestamp,
        EventType type,
        Message message
    ) {
      if (timestamp < clock.now()) {
        throw new IllegalArgumentException(
            "Cannot schedule a past event");
      }

      events.offer(new SimulationEvent(
          timestamp,
          sequence++,
          type,
          message
      ));
    }

    private void scheduleDispatch() {
      if (dispatchScheduled || scheduler.isEmpty()) {
        return;
      }

      long timestamp = Math.max(
          clock.now(),
          provider.nextAvailableTime()
      );

      dispatchScheduled = true;

      schedule(
          timestamp,
          EventType.DISPATCH_READY,
          null
      );
    }

    private void handleArrival(Message message) {
      metrics.onArrival(message);
      scheduler.enqueue(message);
      scheduleDispatch();
    }

    private void handleDispatch() {
      dispatchScheduled = false;

      scheduler.selectNext(clock.now())
          .ifPresent(message -> {
            metrics.onDispatch(
                message,
                clock.now()
            );

            long deliveryTime =
                provider.dispatch(clock.now());

            schedule(
                deliveryTime,
                EventType.DELIVERED,
                message
            );
          });

      scheduleDispatch();
    }

    // 오라클 접근자
    MetricsCollector metrics() {
      return metrics;
    }

    // 오라클 접근자
    long now() {
      return clock.now();
    }

    public void run() {
      while (!events.isEmpty()) {
        SimulationEvent event = events.poll();

        clock.advanceTo(event.timestamp());

        switch (event.type()) {
          case ARRIVAL ->
              handleArrival(event.message());

          case DISPATCH_READY ->
              handleDispatch();

          case DELIVERED ->
              metrics.onDelivery(
                  event.message(),
                  clock.now()
              );
        }
      }

      metrics.printReport();

      System.out.printf(
          "%nVirtual elapsed time: %.3f s%n",
          clock.now() / (double) SECOND
      );
    }
  }

  static final class ScenarioGenerator {

    public void generate(
        Scenario scenario,
        SimulationEngine engine
    ) {
      long id = 0;

      for (int i = 0;
          i < scenario.campaignMessages();
          i++) {

        Message advertisement = new Message(
            id++,
            "A",
            MessageType.ADVERTISEMENT,
            0,
            3600 * SECOND
        );

        engine.schedule(
            advertisement.arrivalTime(),
            EventType.ARRIVAL,
            advertisement
        );
      }

      Message otp = new Message(
          id,
          "B",
          MessageType.OTP,
          scenario.otpArrivalTime(),
          scenario.otpArrivalTime()
              + scenario.otpTtl()
      );

      engine.schedule(
          otp.arrivalTime(),
          EventType.ARRIVAL,
          otp
      );
    }
  }

  public static void main(String[] args) {

    int campaignMessages = args.length > 0
        ? Integer.parseInt(args[0])
        : 800_000;

    int providerTps = args.length > 1
        ? Integer.parseInt(args[1])
        : 450;

    Scenario scenario = new Scenario(
        campaignMessages,
        providerTps,
        SECOND,
        60 * SECOND,
        100_000
    );

    SimulationEngine engine = new SimulationEngine(
        new FifoScheduler(),
        new ProviderSimulator(
            scenario.providerTps(),
            scenario.deliveryDelay()
        )
    );

    new ScenarioGenerator().generate(
        scenario,
        engine
    );

    long startedAt = System.nanoTime();

    engine.run();

    double executionSeconds =
        (System.nanoTime() - startedAt)
            / 1_000_000_000.0;

    System.out.printf(
        "Wall clock execution: %.3f s%n",
        executionSeconds
    );
  }
}
