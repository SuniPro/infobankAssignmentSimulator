package com.raphael.support;

import com.raphael.core.SimulationObserver;
import com.raphael.experiment.ExperimentConfig;
import com.raphael.experiment.ExperimentRunner;
import com.raphael.scenario.Scenario;

/** 검증된 단일 실행을 위한 테스트 헬퍼. */
public final class Runs {

  private static final ExperimentRunner RUNNER = new ExperimentRunner(0, 1);

  private Runs() {
  }

  public static ExperimentRunner.Run run(Scenario scenario, ExperimentConfig config) {
    return RUNNER.runOnce(scenario, config, SimulationObserver.NONE);
  }

  public static TraceRecorder trace(Scenario scenario, ExperimentConfig config) {
    TraceRecorder recorder = new TraceRecorder();
    RUNNER.runOnce(scenario, config, recorder);
    return recorder;
  }
}
