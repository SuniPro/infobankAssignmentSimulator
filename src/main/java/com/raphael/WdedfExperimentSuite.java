package com.raphael;

import static com.raphael.core.VirtualClock.MICROS_PER_MILLISECOND;
import static com.raphael.core.VirtualClock.MICROS_PER_SECOND;

import com.raphael.core.SimulationObserver;
import com.raphael.domain.Message;
import com.raphael.experiment.ExperimentConfig;
import com.raphael.experiment.ExperimentRunner;
import com.raphael.experiment.StandardArms;
import com.raphael.report.MultiArmReport;
import com.raphael.scenario.MessageScenarios;
import com.raphael.scenario.PaperScenarios;
import com.raphael.scenario.Scenario;
import com.raphael.scheduling.QueueLayout;
import com.raphael.scheduling.wdedf.IdleDebtPolicy;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * v0.3 실험 묶음. 논문 검증 (P 시리즈) 과 메시지 도메인 (S5 ~ S12) 을 별도 보고서로 출력한다.
 * 모든 수치는 실행 결과에서 계산되며, 이 클래스는 시나리오와 arm 을 고르는 일만 한다.
 */
final class WdedfExperimentSuite {

  private final ExperimentRunner runner;
  private final PrintStream out;

  WdedfExperimentSuite(ExperimentRunner runner, PrintStream out) {
    this.runner = runner;
    this.out = out;
  }

  /** 기존 S1–S4b 에 v0.3 arm 들을 추가로 실행한다 (v0.2 보고서는 별도로 그대로 출력된다). */
  void history(List<Scenario> scenarios, int baseQuantum) {
    banner("History scenarios S1–S4b with v0.3 arms");
    for (Scenario scenario : scenarios) {
      out.print(MultiArmReport.render(scenario,
          runner.runAll(scenario, StandardArms.messageDomain(baseQuantum))));
    }
  }

  /** 논문 검증 (P 시리즈). 모든 arm 이 COST_RATE 서비스 모델을 공유한다. */
  void paper() {
    banner("Paper validation (P series, COST_RATE, explicit reservations)");
    Scenario p1 = PaperScenarios.exampleOne();
    out.print(MultiArmReport.render(p1, runner.runAll(p1, paperArms(p1, 1_000))));
    printPaperTrace(p1, paperArms(p1, 1_000));
    Scenario p2 = PaperScenarios.exampleTwo();
    out.print(MultiArmReport.render(p2, runner.runAll(p2, paperArms(p2, 1_500))));
    printPaperTrace(p2, paperArms(p2, 1_500));
    Scenario p3a = PaperScenarios.fairSharing(11, 30 * MICROS_PER_SECOND);
    out.print(MultiArmReport.render(p3a, runner.runAll(p3a, withFifo(paperArms(p3a, 4_000)))));
    Scenario p3b = PaperScenarios.weightedSharing(12, 30 * MICROS_PER_SECOND);
    out.print(MultiArmReport.render(p3b, runner.runAll(p3b, withFifo(paperArms(p3b, 4_000)))));
    Scenario p4 = PaperScenarios.delayDifferentiation(13, 5 * MICROS_PER_SECOND);
    out.print(MultiArmReport.render(p4, runner.runAll(p4, withFifo(paperArms(p4, 4_000)))));
    printPerFlowDelay(p4, paperArms(p4, 4_000));
  }

  /** 메시지 도메인 (S5 ~ S12). */
  void message(int baseQuantum) {
    banner("Message-domain scenarios (S5–S12, MESSAGE_RATE 450 TPS)");
    Scenario s5 = MessageScenarios.intraTenantHeadOfLine(100_000, MICROS_PER_SECOND, 120);
    List<ExperimentConfig> s5Arms = new ArrayList<>(StandardArms.messageDomain(baseQuantum));
    s5Arms.add(StandardArms.d5DwrrClass(baseQuantum));
    s5Arms.add(StandardArms.d6WdedfClass(baseQuantum));
    out.print(MultiArmReport.render(s5, runner.runAll(s5, s5Arms)));

    for (long ttl : new long[] {60 * MICROS_PER_SECOND, 10 * MICROS_PER_SECOND}) {
      for (int tenants : new int[] {100, 1_000, 10_000}) {
        Scenario s6 = MessageScenarios.tenantPopulation(tenants, 135_000, ttl);
        out.print(MultiArmReport.render(s6,
            runner.runAll(s6, StandardArms.messageDomain(baseQuantum))));
      }
    }
    for (int tenants : new int[] {1_000, 10_000}) {
      Scenario overload =
          MessageScenarios.tenantPopulationOverload(tenants, 135_000, 10 * MICROS_PER_SECOND);
      out.print(MultiArmReport.render(overload,
          runner.runAll(overload, StandardArms.messageDomain(baseQuantum))));
    }
    for (int quantum : new int[] {1, 10}) {
      Scenario s7 = MessageScenarios.weightVersusUrgency(60, MICROS_PER_SECOND);
      out.printf(Locale.ROOT, "%n[S7 with base quantum %d]%n", quantum);
      out.print(MultiArmReport.render(s7, runner.runAll(s7, List.of(
          ExperimentConfig.dwrr(quantum), StandardArms.e2WdedfMessage(quantum),
          StandardArms.d2WdedfNoBorrowing(quantum), StandardArms.d1WdedfPaper(quantum),
          StandardArms.d3Edf()))));
    }
    for (double load : new double[] {0.9, 1.0, 1.1}) {
      for (long seed = 1; seed <= 3; seed++) {
        Scenario s8 = MessageScenarios.heterogeneousDeadlines(load, seed);
        out.print(MultiArmReport.render(s8, runner.runAll(s8, List.of(
            ExperimentConfig.dwrr(baseQuantum), StandardArms.e2WdedfMessage(baseQuantum),
            StandardArms.d1WdedfPaper(baseQuantum),
            StandardArms.d2WdedfNoBorrowing(baseQuantum), StandardArms.d3Edf()))));
      }
    }
    for (long ttl : new long[] {1_500 * MICROS_PER_MILLISECOND, 1_200 * MICROS_PER_MILLISECOND}) {
      Scenario s9 = MessageScenarios.competingUrgentQueues(20, 30, ttl);
      out.print(MultiArmReport.render(s9, runner.runAll(s9, List.of(
          ExperimentConfig.dwrr(baseQuantum), StandardArms.e2WdedfMessage(baseQuantum),
          StandardArms.d2WdedfNoBorrowing(baseQuantum), StandardArms.d3Edf()))));
    }
    for (int quantum : new int[] {1, 4}) {
      Scenario s10 = MessageScenarios.waitingCredit(5 * MICROS_PER_MILLISECOND);
      out.printf(Locale.ROOT, "%n[S10 with base quantum %d]%n", quantum);
      out.print(MultiArmReport.render(s10, runner.runAll(s10, List.of(
          ExperimentConfig.dwrr(quantum), StandardArms.d2WdedfNoBorrowing(quantum),
          StandardArms.e2WdedfMessage(quantum)))));
    }
    Scenario s11 = MessageScenarios.borrowingChurn(20);
    out.printf(Locale.ROOT, "%n[S11 with base quantum 10, Λ=20%%]%n");
    out.print(MultiArmReport.render(s11, runner.runAll(s11, churnArms(10, 2_000))));
    for (double load : new double[] {0.5, 0.9, 1.0, 1.2}) {
      Scenario s12 = MessageScenarios.urgentOverload(load, 7);
      out.print(MultiArmReport.render(s12, runner.runAll(s12, List.of(
          ExperimentConfig.dwrr(baseQuantum), StandardArms.e2WdedfMessage(baseQuantum),
          StandardArms.d2WdedfNoBorrowing(baseQuantum), StandardArms.d3Edf()))));
    }
  }

  /** Λ 민감도: 같은 출발 정책·워크로드에서 Λ 만 바꾼다. */
  void lambdaSweep() {
    banner("Borrowing-ratio sensitivity (Λ = 0%, 1%, 5%, 10%, 20%; experimental values)");
    Scenario s7 = MessageScenarios.weightVersusUrgency(60, MICROS_PER_SECOND);
    out.printf(Locale.ROOT, "%n[S7 sweep, base quantum 10]%n");
    out.print(MultiArmReport.render(s7,
        runner.runAll(s7, StandardArms.lambdaSweep(10, 0, 100, 500, 1_000, 2_000))));
    Scenario s11 = MessageScenarios.borrowingChurn(20);
    out.printf(Locale.ROOT, "%n[S11 sweep, base quantum 10]%n");
    out.print(MultiArmReport.render(s11,
        runner.runAll(s11, StandardArms.lambdaSweep(10, 0, 100, 500, 1_000, 2_000))));
  }

  private static List<ExperimentConfig> churnArms(int quantum, int lambdaBp) {
    return List.of(
        ExperimentConfig.dwrr(quantum),
        ExperimentConfig.wdedf("D2", "WDEDF, Λ=0", "WDEDF-NB",
            arm(ExperimentConfig.DepartureMode.BUSINESS, 0, IdleDebtPolicy.PAPER_RESET, quantum)),
        ExperimentConfig.wdedf("E2*", "WDEDF-MESSAGE, Λ=20%, paper idle reset", "WDEDF-MESSAGE",
            arm(ExperimentConfig.DepartureMode.BUSINESS, lambdaBp, IdleDebtPolicy.PAPER_RESET,
                quantum)),
        ExperimentConfig.wdedf("D4*", "WDEDF-MESSAGE, Λ=20%, RETAIN_DEBT", "WDEDF-DEBT",
            arm(ExperimentConfig.DepartureMode.BUSINESS, lambdaBp, IdleDebtPolicy.RETAIN_DEBT,
                quantum)));
  }

  /** P 시리즈 arm: DWRR (= 논문의 WDRR), WDEDF-PAPER Λ=0 / 5%, 논문 T̂_D 기준 전역 EDF. */
  private static List<ExperimentConfig> paperArms(Scenario scenario, long minimumQuantum) {
    int base = PaperScenarios.dwrrBaseQuantum(scenario, minimumQuantum);
    return List.of(
        ExperimentConfig.dwrr(base),
        ExperimentConfig.wdedf("P-W0", "WDEDF-PAPER, ω_MIN=" + minimumQuantum + ", Λ=0",
            "WDEDF-PAPER", paperArm(0, minimumQuantum)),
        ExperimentConfig.wdedf("P-W5", "WDEDF-PAPER, ω_MIN=" + minimumQuantum + ", Λ=5%",
            "WDEDF-PAPER", paperArm(StandardArms.DEFAULT_LAMBDA_BP, minimumQuantum)),
        ExperimentConfig.edf("P-EDF", "Global EDF on paper T̂_D",
            ExperimentConfig.DepartureMode.PAPER));
  }

  private static List<ExperimentConfig> withFifo(List<ExperimentConfig> arms) {
    List<ExperimentConfig> all = new ArrayList<>();
    all.add(ExperimentConfig.fifo());
    all.addAll(arms);
    return all;
  }

  private static ExperimentConfig.WdedfArm paperArm(int lambdaBp, long minimumQuantum) {
    return new ExperimentConfig.WdedfArm(ExperimentConfig.DepartureMode.PAPER, lambdaBp,
        IdleDebtPolicy.PAPER_RESET, QueueLayout.Topology.TENANT, 1,
        OptionalLong.of(minimumQuantum));
  }

  private static ExperimentConfig.WdedfArm arm(ExperimentConfig.DepartureMode mode, int lambdaBp,
      IdleDebtPolicy debt, int quantum) {
    return new ExperimentConfig.WdedfArm(mode, lambdaBp, debt, QueueLayout.Topology.TENANT,
        quantum, OptionalLong.empty());
  }

  /** 마이크로 시나리오의 dispatch 순서와 완료 시각 (T_C = dispatch + L/C) 을 출력한다. */
  private void printPaperTrace(Scenario scenario, List<ExperimentConfig> arms) {
    out.printf(Locale.ROOT, "%n%s dispatch trace (dispatch → completion = dispatch + L/C)%n",
        scenario.name());
    for (ExperimentConfig arm : arms) {
      StringBuilder line = new StringBuilder();
      int[] shown = {0};
      runner.runOnce(scenario, arm, new SimulationObserver() {
        @Override
        public void onDispatch(Message m, long now) {
          if (shown[0]++ < 12) {
            long transmission = (long) m.cost() * MICROS_PER_SECOND / scenario.providerCapacity();
            line.append(String.format(Locale.ROOT, " %s#%d@%d→%d", m.tenantId(), m.id(), now,
                now + transmission));
          }
        }
      });
      out.printf(Locale.ROOT, "  %-6s%s%s%n", arm.experimentId(), line,
          shown[0] > 12 ? " …" : "");
    }
  }

  /** P4: 흐름별 평균·최악 대기 지연과 T̂_D 준수율 (논문 Fig. 5, Table 3 에 대응). */
  private void printPerFlowDelay(Scenario scenario, List<ExperimentConfig> arms) {
    out.printf(Locale.ROOT, "%nP4 per-flow queueing delay (avg / worst, ms) and %% dispatched"
        + " by T̂_D%n");
    List<ExperimentRunner.Run> runs = new ArrayList<>();
    StringBuilder header = new StringBuilder(String.format(Locale.ROOT, "  %-5s", "flow"));
    for (ExperimentConfig arm : arms) {
      runs.add(runner.runOnce(scenario, arm, SimulationObserver.NONE));
      header.append(String.format(Locale.ROOT, " | %-30s", arm.experimentId()));
    }
    out.println(header);
    for (var tenant : scenario.tenants()) {
      StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "  %-5s", tenant.id()));
      for (ExperimentRunner.Run run : runs) {
        var g = run.result().tenantGroups().get(tenant.id());
        if (g.queueLatency().isEmpty()) {
          row.append(String.format(Locale.ROOT, " | %-30s", "n/a"));
          continue;
        }
        row.append(String.format(Locale.ROOT, " | %8.2f / %8.2f / %7.3f%%",
            g.averageQueueLatency().getAsDouble() / 1e3, g.queueLatency().max() / 1e3,
            100.0 * (1 - g.paperDepartureViolationRate().getAsDouble())));
      }
      out.println(row);
    }
  }

  private void banner(String title) {
    out.printf(Locale.ROOT, "%n%s%n%s%n", "#".repeat(100), "# " + title);
  }
}
