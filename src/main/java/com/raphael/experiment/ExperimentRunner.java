package com.raphael.experiment;

import com.raphael.core.SimulationEngine;
import com.raphael.core.SimulationObserver;
import com.raphael.domain.Message;
import com.raphael.metrics.MetricsCollector;
import com.raphael.metrics.SimulationResult;
import com.raphael.provider.ProviderSimulator;
import com.raphael.scenario.Scenario;
import com.raphael.scheduling.SchedulingPolicy;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * 동일한 불변 {@link Scenario}에 대해 실험 arm들을 실행한다.
 *
 * <p>격리: warm-up 실행을 포함한 모든 실행은 새 엔진(새 가상 시계와 이벤트 큐를 소유),
 * 스케줄러, provider, 지표 수집기를 만든다. 공유되는 입력은 불변 시나리오뿐이다.
 *
 * <p>재현성: 한 arm의 모든 반복은 {@code equals}로 같은 {@link SimulationResult}를 만들어야
 * 한다. 차이가 있으면 예외를 던진다. 각 결과는 또한 메시지를 보존하고 provider 용량 감사를
 * 통과해야 한다.
 *
 * <p>시간 측정: JIT가 핫 패스를 컴파일할 수 있도록 측정하지 않는 {@code warmupRuns}회 실행을
 * 먼저 수행한다. 그다음 {@code measuredRuns}회 실행의 시간을 잰다. 호출자는 단일 샘플이 아니라
 * 중앙값과 분산 정도를 보고해야 한다. 각 측정 실행 전에 {@link System#gc()}를 요청한다. 이는
 * 이전 실행에서 넘어온 가비지를 줄이기 위한 힌트일 뿐이다.
 */
public final class ExperimentRunner {

  private final int warmupRuns;
  private final int measuredRuns;
  private final double timerBiasNanosPerCall;

  public ExperimentRunner(int warmupRuns, int measuredRuns) {
    if (warmupRuns < 0 || measuredRuns < 1) {
      throw new IllegalArgumentException("warmupRuns >= 0 and measuredRuns >= 1 required");
    }
    this.warmupRuns = warmupRuns;
    this.measuredRuns = measuredRuns;
    this.timerBiasNanosPerCall = calibrateTimerBias();
  }

  /** 같은 시나리오에 대해 각 arm을 순서대로 실행한다. */
  public List<ExperimentResult> runAll(Scenario scenario, List<ExperimentConfig> configs) {
    List<ExperimentResult> results = new ArrayList<>(configs.size());
    for (ExperimentConfig config : configs) {
      results.add(run(scenario, config));
    }
    return results;
  }

  public ExperimentResult run(Scenario scenario, ExperimentConfig config) {
    for (int i = 0; i < warmupRuns; i++) {
      runOnce(scenario, config, SimulationObserver.NONE);
    }
    SimulationResult reference = null;
    List<ExecutionProfile> profiles = new ArrayList<>(measuredRuns);
    for (int i = 0; i < measuredRuns; i++) {
      System.gc();
      Run run = runOnce(scenario, config, SimulationObserver.NONE);
      if (reference == null) {
        reference = run.result();
      } else if (!reference.equals(run.result())) {
        throw new IllegalStateException(
            "Non-deterministic result for " + scenario.name() + "/" + config.experimentId());
      }
      profiles.add(run.profile());
    }
    return new ExperimentResult(scenario.name(), config, reference, warmupRuns, profiles);
  }

  /** 완전히 격리되고 검증된 실행 1회를 수행한다. */
  public Run runOnce(Scenario scenario, ExperimentConfig config, SimulationObserver observer) {
    SchedulingPolicy policy = config.policyFactory().apply(scenario);
    if (!policy.isEmpty()) {
      throw new IllegalStateException("Policy factory must return a new, empty policy");
    }
    SimulationEngine engine = new SimulationEngine(
        policy,
        new ProviderSimulator(
            scenario.serviceModel(), scenario.providerCapacity(), scenario.deliveryDelay()),
        new MetricsCollector(
            scenario.tenants(), scenario.serviceModel(), scenario.providerCapacity(),
            scenario.deliveryDelay(), scenario.observationWindow(),
            ScenarioReservations.paperDepartureForMetrics(scenario)),
        observer,
        true);
    for (Message message : scenario.messages()) {
      engine.scheduleArrival(message);
    }

    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    boolean cpuTimeSupported = threads.isCurrentThreadCpuTimeSupported();
    long cpuStart = cpuTimeSupported ? threads.getCurrentThreadCpuTime() : -1;
    long wallStart = System.nanoTime();

    SimulationResult result = engine.run();

    long wallNanos = System.nanoTime() - wallStart;
    OptionalLong cpuNanos = cpuTimeSupported && cpuStart >= 0
        ? OptionalLong.of(threads.getCurrentThreadCpuTime() - cpuStart)
        : OptionalLong.empty();

    validate(scenario, config, result);
    return new Run(result, new ExecutionProfile(
        wallNanos, cpuNanos, engine.schedulerElapsedNanos(), engine.schedulerTimedCalls(),
        timerBiasNanosPerCall));
  }

  public double timerBiasNanosPerCall() {
    return timerBiasNanosPerCall;
  }

  /** 실행 1회의 결정적 결과와 그 실행 프로파일. */
  public record Run(SimulationResult result, ExecutionProfile profile) {
  }

  private static void validate(Scenario scenario, ExperimentConfig config, SimulationResult r) {
    String arm = scenario.name() + "/" + config.experimentId();
    if (r.totalReceived() != scenario.messages().size() || !r.isConserved()) {
      throw new IllegalStateException("Message conservation violated in " + arm);
    }
    if (r.providerCapacityViolations() != 0) {
      throw new IllegalStateException("Provider capacity exceeded in " + arm);
    }
  }

  /**
   * 빈 {@code nanoTime()} 구간이 보고하는 평균값, 즉 시간을 재는 모든 스케줄러 호출에 더해지는
   * 체계적 편향을 추정한다. 간섭을 줄이기 위해 여러 배치 중 최솟값을 취한다. 이는 추정치일 뿐
   * 정확한 보정이 아니다.
   */
  private static double calibrateTimerBias() {
    final int iterations = 200_000;
    double best = Double.MAX_VALUE;
    long sink = 0;
    for (int batch = 0; batch < 5; batch++) {
      long total = 0;
      for (int i = 0; i < iterations; i++) {
        long start = System.nanoTime();
        total += System.nanoTime() - start;
      }
      sink += total;
      best = Math.min(best, total / (double) iterations);
    }
    if (sink < 0) {
      throw new IllegalStateException("nanoTime is not monotonic");
    }
    return best;
  }
}
