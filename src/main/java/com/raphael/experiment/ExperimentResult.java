package com.raphael.experiment;

import com.raphael.metrics.SimulationResult;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.ToDoubleFunction;

/**
 * 한 시나리오에 대한 한 실험 arm의 결과다. 모든 반복에서 동일했던 결정적 시뮬레이션 결과와,
 * 측정한 각 반복의 실행 프로파일로 구성된다.
 */
public record ExperimentResult(
    String scenarioName,
    ExperimentConfig config,
    SimulationResult result,
    int warmupRuns,
    List<ExecutionProfile> measuredRuns) {

  public ExperimentResult {
    measuredRuns = List.copyOf(measuredRuns);
    if (measuredRuns.isEmpty()) {
      throw new IllegalArgumentException("At least one measured run is required");
    }
  }

  public double medianWallClockNanos() {
    return median(profile -> profile.wallClockNanos());
  }

  public double minWallClockNanos() {
    return measuredRuns.stream().mapToLong(ExecutionProfile::wallClockNanos).min().orElseThrow();
  }

  public OptionalDouble medianThreadCpuNanos() {
    if (measuredRuns.stream().anyMatch(profile -> profile.threadCpuNanos().isEmpty())) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(median(profile -> profile.threadCpuNanos().getAsLong()));
  }

  public double medianSchedulerElapsedNanos() {
    return median(profile -> profile.schedulerElapsedNanos());
  }

  public double medianSchedulerElapsedNanosCorrected() {
    return median(ExecutionProfile::schedulerElapsedNanosCorrected);
  }

  private double median(ToDoubleFunction<ExecutionProfile> metric) {
    double[] values = measuredRuns.stream().mapToDouble(metric).sorted().toArray();
    int middle = values.length / 2;
    return values.length % 2 == 1
        ? values[middle]
        : (values[middle - 1] + values[middle]) / 2.0;
  }

  @Override
  public String toString() {
    return "ExperimentResult[" + scenarioName + "/" + config.experimentId() + ", runs="
        + Arrays.toString(measuredRuns.stream().mapToLong(ExecutionProfile::wallClockNanos)
            .toArray()) + "]";
  }
}
