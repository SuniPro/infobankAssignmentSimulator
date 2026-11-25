package com.raphael;

import static com.raphael.core.VirtualClock.MICROS_PER_SECOND;

import com.raphael.core.ResourceController.ResourceEvent;
import com.raphael.experiment.ArchitectureArms;
import com.raphael.experiment.BlastRadius;
import com.raphael.experiment.ExperimentConfig;
import com.raphael.experiment.ExperimentResult;
import com.raphael.experiment.ExperimentRunner;
import com.raphael.experiment.StandardArms;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v1.0 최종 평가. 새 알고리즘 없이 기존 arm factory 를 다른 설정으로 조합한다. E0–E5 식별자는 v0.4 와
 * 같은 의미를 유지하고, 민감도 구성은 진단 식별자를 쓴다:
 * <ul>
 *   <li>P1 Service Budget 민감도 (S12, 부하 0.8/1.0/1.2): E1, E2, B37/B55/B73/B91 (OTP:광고 예약)</li>
 *   <li>P2 Shuffle Sharding 민감도 (S2, F1): E3 (샤딩 없음), SH4x2/SH8x2/SH8x4/SH16x4</li>
 *   <li>P3 장애 격리 (F1, 배정 seed 1–10): FS{seed} (정상) / FS{seed}-F (샤드 1 장애 [10 s, 40 s))</li>
 *   <li>P4 WDEDF 정당화 (S7, S8, S6-10000): DWRR vs WDEDF, 같은 base quantum</li>
 * </ul>
 * 공통: 450 TPS, 배달 지연 100 ms, Λ = 5%, 테넌트 상한 80% (burst 50). quick 모드는 대형 워크로드만 줄인다.
 */
final class V10Suite {

  private static final int QUANTUM = 10;
  private static final List<ResourceEvent> FAILURES = V04Suite.FAILURES;

  private final ExperimentRunner runner;
  private final PrintStream out;
  private final boolean quick;
  private final List<ResultWriter.Entry> entries = new ArrayList<>();

  V10Suite(ExperimentRunner runner, PrintStream out, boolean quick) {
    this.runner = runner;
    this.out = out;
    this.quick = quick;
  }

  Path run(Path outputDir, Map<String, String> runSettings) throws IOException {
    serviceBudgetSensitivity();
    shardingSensitivity();
    faultIsolation();
    wdedfJustification();
    Path dir = ResultWriter.write(outputDir, quick ? "v10-quick" : "v10", runSettings, entries);
    out.println("\nResults written to " + dir.toAbsolutePath());
    return dir;
  }

  private void serviceBudgetSensitivity() {
    banner("P1 Service Budget sensitivity (S12 urgent overload, seed 7)");
    int[][] ratios = {{3, 7}, {5, 5}, {7, 3}, {9, 1}};
    for (double load : new double[] {0.8, 1.0, 1.2}) {
      Scenario s12 = MessageScenarios.urgentOverload(load, 7);
      List<ExperimentConfig> arms = new ArrayList<>(List.of(
          ExperimentConfig.dwrr(QUANTUM), StandardArms.e2WdedfMessage(QUANTUM)));
      for (int[] r : ratios) {
        ServiceBudgetConfig budget = new ServiceBudgetConfig(r[0], r[1], 0, 0, 8_000, 50);
        arms.add(ArchitectureArms.e3(QUANTUM, budget).with("B" + r[0] + r[1],
            "E3 with OTP:ads reservation " + r[0] + ":" + r[1], Map.of()));
      }
      record(s12, Map.of("priority", "P1", "load", String.valueOf(load), "seed", "7"), arms);
    }
  }

  private void shardingSensitivity() {
    banner("P2 Shuffle Sharding sensitivity (total capacity fixed at 450 TPS)");
    int[][] shapes = {{4, 2}, {8, 2}, {8, 4}, {16, 4}};
    ServiceBudgetConfig budget = ServiceBudgetConfig.defaults();
    List<ExperimentConfig> arms = new ArrayList<>(List.of(ArchitectureArms.e3(QUANTUM, budget)));
    for (int[] shape : shapes) {
      arms.add(ArchitectureArms.e4(QUANTUM, budget,
              new ShardingConfig(shape[0], shape[1], 42, List.of()))
          .with("SH" + shape[0] + "x" + shape[1], "E4 with " + shape[0] + " shards, subset "
              + shape[1] + " (tenant-accessible capacity " + (450 * shape[1] / shape[0])
              + " msg/s)", Map.of()));
    }
    int campaign = quick ? 20_000 : 800_000;
    record(ScenarioGenerator.noisyNeighbor(campaign, 450),
        Map.of("priority", "P2", "campaign_messages", String.valueOf(campaign)), arms);
    record(f1(), Map.of("priority", "P2", "aggressor", "T00"), arms);
  }

  private void faultIsolation() {
    banner("P3 Fault isolation (F1, shard 1 down [10 s, 40 s), assignment seeds 1-10)");
    Scenario f1 = f1();
    ServiceBudgetConfig budget = ServiceBudgetConfig.defaults();
    List<ExperimentConfig> arms = new ArrayList<>();
    for (long seed = 1; seed <= 10; seed++) {
      ShardingConfig healthy = new ShardingConfig(4, 2, seed, List.of());
      arms.add(ArchitectureArms.e4(QUANTUM, budget, healthy)
          .with("FS" + seed, "E4 (4x2), assignment seed " + seed, Map.of()));
      arms.add(ArchitectureArms.e4(QUANTUM, budget, healthy.withFailures(FAILURES))
          .with("FS" + seed + "-F", "E4 (4x2), seed " + seed + ", shard 1 failure", Map.of()));
    }
    List<ExperimentResult> results = runner.runAll(f1, arms);
    out.print(MultiArmReport.render(f1, results));
    List<Integer> affected = new ArrayList<>();
    List<Long> extraMisses = new ArrayList<>();
    for (int i = 0; i < results.size(); i += 2) {
      ExperimentResult healthy = results.get(i);
      ExperimentResult failed = results.get(i + 1);
      BlastRadius blast = BlastRadius.compare(failed.config().experimentId(),
          healthy.result(), failed.result(), Set.of("T00"));
      double worstRatio = blast.impacts().stream()
          .filter(t -> t.baselineE2eP99() > 0 && t.failureE2eP99() >= 0)
          .mapToDouble(t -> t.failureE2eP99() / (double) t.baselineE2eP99()).max().orElse(1);
      out.printf(Locale.ROOT, "  %-7s affected %d/%d, additional misses %d, worst non-aggressor"
              + " p99 ratio %.3f, aggressor completion %.3f s -> %.3f s%n",
          failed.config().experimentId(), blast.affectedTenants(), blast.nonAggressorTenants(),
          blast.additionalMisses(), worstRatio,
          healthy.result().tenant("T00").lastDeliveryTime().getAsLong() / 1e6,
          failed.result().tenant("T00").lastDeliveryTime().getAsLong() / 1e6);
      affected.add(blast.affectedTenants());
      extraMisses.add(blast.additionalMisses());
      Map<String, String> workload = Map.of("priority", "P3", "aggressor", "T00",
          "assignment_seed", String.valueOf(i / 2 + 1));
      entries.add(new ResultWriter.Entry(f1, workload, healthy, null));
      entries.add(new ResultWriter.Entry(f1, workload, failed, blast));
    }
    List<Integer> sorted = affected.stream().sorted().toList();
    out.printf(Locale.ROOT, "  Blast radius across 10 seeds: min %d, median %.1f, max %d"
            + " (of 15 non-aggressor tenants); additional misses total %d%n",
        sorted.get(0), (sorted.get(4) + sorted.get(5)) / 2.0, sorted.get(9),
        extraMisses.stream().mapToLong(Long::longValue).sum());
  }

  private void wdedfJustification() {
    banner("P4 WDEDF vs DWRR (same base quantum)");
    for (int quantum : new int[] {1, QUANTUM}) {
      record(MessageScenarios.weightVersusUrgency(60, MICROS_PER_SECOND),
          Map.of("priority", "P4", "burst", "60", "otp_ttl_us", "1000000",
              "base_quantum", String.valueOf(quantum)),
          List.of(ExperimentConfig.dwrr(quantum).with("W-DWRR-q" + quantum, "DWRR q=" + quantum,
                  Map.of()),
              StandardArms.e2WdedfMessage(quantum).with("W-WDEDF-q" + quantum,
                  "WDEDF q=" + quantum, Map.of())));
    }
    List<ExperimentConfig> pair = List.of(
        ExperimentConfig.dwrr(QUANTUM).with("W-DWRR", "DWRR q=10", Map.of()),
        StandardArms.e2WdedfMessage(QUANTUM).with("W-WDEDF", "WDEDF q=10", Map.of()));
    for (double load : new double[] {1.0, 1.1}) {
      for (long seed = 1; seed <= 3; seed++) {
        record(MessageScenarios.heterogeneousDeadlines(load, seed), Map.of("priority", "P4",
            "load", String.valueOf(load), "seed", String.valueOf(seed)), pair);
      }
    }
    record(MessageScenarios.tenantPopulation(quick ? 1_000 : 10_000, 135_000,
        10 * MICROS_PER_SECOND), Map.of("priority", "P4", "otp_ttl_us", "10000000"), pair);
  }

  private Scenario f1() {
    return MessageScenarios.shardFailureWorkload(quick ? 20_000 : 100_000, 300);
  }

  private void record(Scenario scenario, Map<String, String> workload,
      List<ExperimentConfig> arms) {
    List<ExperimentResult> results = runner.runAll(scenario, arms);
    out.print(MultiArmReport.render(scenario, results));
    results.forEach(r -> entries.add(new ResultWriter.Entry(scenario, workload, r, null)));
  }

  private void banner(String title) {
    out.printf(Locale.ROOT, "%n%s%n# %s%n", "#".repeat(100), title);
  }
}
