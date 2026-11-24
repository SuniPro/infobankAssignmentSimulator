package com.raphael;

import static com.raphael.core.VirtualClock.MICROS_PER_MILLISECOND;
import static com.raphael.core.VirtualClock.MICROS_PER_SECOND;

import com.raphael.core.ResourceController.ResourceEvent;
import com.raphael.experiment.ArchitectureArms;
import com.raphael.experiment.BlastRadius;
import com.raphael.experiment.ExperimentConfig;
import com.raphael.experiment.ExperimentResult;
import com.raphael.experiment.ExperimentRunner;
import com.raphael.persistence.ResultWriter;
import com.raphael.report.MultiArmReport;
import com.raphael.scenario.MessageScenarios;
import com.raphael.scenario.Scenario;
import com.raphael.scenario.ScenarioGenerator;
import com.raphael.scheduling.budget.ServiceBudgetConfig;
import com.raphael.scheduling.sharding.ShardingConfig;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v0.4 통합 suite: E0–E5 를 재사용 시나리오 (S2, S3, S5, S12) 와 신규 시나리오 (S13 aging, F1 샤드
 * 장애) 에서 실행하고, 콘솔 보고서와 CSV·JSON 결과를 남긴다. quick 모드는 warm-up 0, 측정 1 회,
 * 축소된 워크로드를 쓴다 (개발·회귀용).
 */
final class   V04Suite {

  /** 장애 실험: 샤드 1 이 10 s 에 장애, 40 s 에 복구. */
  static final List<ResourceEvent> FAILURES = List.of(
      new ResourceEvent(10 * MICROS_PER_SECOND, 1, true),
      new ResourceEvent(40 * MICROS_PER_SECOND, 1, false));

  private final ExperimentRunner runner;
  private final PrintStream out;
  private final boolean quick;
  private final int baseQuantum;
  private final ServiceBudgetConfig budget = ServiceBudgetConfig.defaults();
  private final ShardingConfig sharding = new ShardingConfig(4, 2, 42, List.of());
  private final long agingThreshold = 30 * MICROS_PER_SECOND;

  V04Suite(ExperimentRunner runner, PrintStream out, boolean quick, int baseQuantum) {
    this.runner = runner;
    this.out = out;
    this.quick = quick;
    this.baseQuantum = baseQuantum;
  }

  /** 모든 실험을 실행하고 결과 디렉터리를 돌려준다. 파일 I/O 는 실행이 끝난 뒤에만 한다. */
  Path run(Path outputDir, Map<String, String> runSettings) throws IOException {
    List<ResultWriter.Entry> entries = new ArrayList<>();
    List<ExperimentConfig> arms =
        ArchitectureArms.e0ToE5(baseQuantum, budget, sharding, agingThreshold);

    int campaign = quick ? 20_000 : 800_000;
    standard(entries, arms, ScenarioGenerator.noisyNeighbor(campaign, 450),
        Map.of("campaign_messages", String.valueOf(campaign), "tps", "450"));
    standard(entries, arms, ScenarioGenerator.continuousOtp(campaign, 450,
        333 * MICROS_PER_MILLISECOND, (quick ? 60 : 1_200) * MICROS_PER_SECOND),
        Map.of("campaign_messages", String.valueOf(campaign), "otp_interval_ms", "333",
            "otp_duration_s", quick ? "60" : "1200"));
    int hol = quick ? 5_000 : 100_000;
    standard(entries, arms, MessageScenarios.intraTenantHeadOfLine(hol, MICROS_PER_SECOND,
        quick ? 20 : 120), Map.of("ads_per_tenant", String.valueOf(hol)));
    standard(entries, arms, MessageScenarios.urgentOverload(1.0, 7),
        Map.of("load", "1.0", "seed", "7"));
    standard(entries, arms, MessageScenarios.prolongedWaiting(quick ? 5_000 : 30_000, 300,
        quick ? 60 : 300), Map.of("stream_rate", "300"));
    failure(entries, arms);

    Path dir = ResultWriter.write(outputDir, quick ? "v04-quick" : "v04", runSettings, entries);
    out.println("\nResults written to " + dir.toAbsolutePath());
    return dir;
  }

  private void standard(List<ResultWriter.Entry> entries, List<ExperimentConfig> arms,
      Scenario scenario, Map<String, String> workload) {
    List<ExperimentResult> results = runner.runAll(scenario, arms);
    out.print(MultiArmReport.render(scenario, results));
    results.forEach(r -> entries.add(new ResultWriter.Entry(scenario, workload, r, null)));
  }

  /** F1: E0–E5 (장애 없음) + E4-F, E5-F (샤드 1 장애). 영향 범위는 같은 arm 의 장애 유무 비교. */
  private void failure(List<ResultWriter.Entry> entries, List<ExperimentConfig> arms) {
    Scenario f1 = MessageScenarios.shardFailureWorkload(quick ? 20_000 : 100_000, 300);
    Map<String, String> workload = Map.of("aggressor", "T00", "failure",
        "shard 1 down [10 s, 40 s)");
    List<ExperimentConfig> all = new ArrayList<>(arms);
    ShardingConfig failing = sharding.withFailures(FAILURES);
    all.add(ArchitectureArms.e4(baseQuantum, budget, failing)
        .with("E4-F", "E4 with shard 1 failure", Map.of()));
    all.add(ArchitectureArms.e5(baseQuantum, budget, failing, agingThreshold)
        .with("E5-F", "E5 with shard 1 failure", Map.of()));
    List<ExperimentResult> results = runner.runAll(f1, all);
    out.print(MultiArmReport.render(f1, results));
    Map<String, ExperimentResult> byId = new LinkedHashMap<>();
    results.forEach(r -> byId.put(r.config().experimentId(), r));
    for (ExperimentResult r : results) {
      BlastRadius blast = null;
      String id = r.config().experimentId();
      if (id.endsWith("-F")) {
        ExperimentResult baseline = byId.get(id.substring(0, id.length() - 2));
        blast = BlastRadius.compare(id, baseline.result(), r.result(), Set.of("T00"));
        out.printf("Blast radius %s vs %s: %d of %d non-aggressor tenants affected,"
                + " additional misses %d%n", id, baseline.config().experimentId(),
            blast.affectedTenants(), blast.nonAggressorTenants(), blast.additionalMisses());
      }
      entries.add(new ResultWriter.Entry(f1, workload, r, blast));
    }
  }
}
