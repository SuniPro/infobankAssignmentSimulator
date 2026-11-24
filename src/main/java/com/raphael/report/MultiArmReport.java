package com.raphael.report;

import com.raphael.experiment.ExecutionProfile;
import com.raphael.experiment.ExperimentResult;
import com.raphael.metrics.GroupMetrics;
import com.raphael.metrics.SimulationResult;
import com.raphael.metrics.WindowedFairness;
import com.raphael.scenario.Scenario;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 여러 arm (E0, E1, E2, D 시리즈 …) 을 한 시나리오에서 비교하는 보고서. arm 이 많으므로 행 = arm,
 * 열 = 지표로 배치한다. 모든 값은 측정 결과에서 읽으며, 표본이 없거나 적용되지 않는 값은 "n/a" 로
 * 표시한다. v0.2 의 {@link ComparisonReport} 는 그대로 유지된다.
 */
public final class MultiArmReport {

  /** 진단 표에 보여 줄 스케줄러 계수 (있는 것만). */
  private static final List<String> DIAGNOSTICS = List.of(
      "rounds", "skippedRounds", "activationGrants", "waitingReactivations",
      "retainedCreditReused", "retainedCreditExpired", "debtForgiven", "borrowAttempts",
      "borrowSuccesses", "borrowedCost", "borrowRejectedNotMoreUrgent", "borrowRejectedPq",
      "borrowRejectedPdc", "pqPerRound", "pqGranted", "debtEvents", "minDeficit", "maxDeficit",
      "maxActiveQueues", "maxBlockedQueues", "heapOffers", "heapPolls", "candidateExaminations",
      "replenishmentSteps", "tokenAdvances", "heapOperations");

  private MultiArmReport() {
  }

  public static String render(Scenario scenario, List<ExperimentResult> arms) {
    StringBuilder out = new StringBuilder();
    out.append(String.format(Locale.ROOT, "%n=== %s: %s ===%n", scenario.name(),
        scenario.description()));
    out.append(String.format(Locale.ROOT,
        "Messages: %,d | Service model: %s | Capacity: %,d/s | Delivery delay: %s%n",
        scenario.messages().size(), scenario.serviceModel(), scenario.providerCapacity(),
        duration(scenario.deliveryDelay())));
    for (ExperimentResult arm : arms) {
      out.append(String.format(Locale.ROOT, "  %-6s %-14s %s%n", arm.config().experimentId(),
          arm.result().policyName(), arm.config().label()));
    }

    Set<String> types = new LinkedHashSet<>();
    arms.forEach(a -> types.addAll(a.result().messageTypeGroups().keySet()));
    for (String type : types) {
      out.append("\nMessage type ").append(type).append('\n');
      out.append(groupTable(arms, r -> r.messageTypeGroups().get(type)).render("  "));
    }
    Set<String> tenantTypes = arms.get(0).result().tenantTypeGroups().keySet();
    if (tenantTypes.size() > 1 && tenantTypes.size() <= 8) {
      for (String key : tenantTypes) {
        out.append("\nTenant/type ").append(key).append('\n');
        out.append(groupTable(arms, r -> r.tenantTypeGroups().get(key)).render("  "));
      }
    } else if (tenantTypes.size() > 8) {
      out.append(spreadTable(arms, types));
    }
    out.append(fairness(arms));
    out.append(totals(arms));
    out.append(diagnostics(arms));
    out.append(execution(arms));
    return out.toString();
  }

  private interface GroupSelector {
    GroupMetrics select(SimulationResult result);
  }

  private static TextTable groupTable(List<ExperimentResult> arms, GroupSelector selector) {
    TextTable table = new TextTable(List.of("arm", "received", "delivered", "miss rate",
        "paper T̂_D viol", "E2E p50", "E2E p99", "E2E max", "queue max", "slack min",
        "lateness max", "last delivery"));
    for (ExperimentResult arm : arms) {
      GroupMetrics g = selector.select(arm.result());
      if (g == null) {
        continue;
      }
      table.row(List.of(armName(arm), count(g.received()), count(g.delivered()),
          rate(g.deadlineMissRate()), rate(g.paperDepartureViolationRate()),
          g.endToEndLatency().isEmpty() ? "n/a" : duration(g.endToEndLatency().p50()),
          g.endToEndLatency().isEmpty() ? "n/a" : duration(g.endToEndLatency().p99()),
          g.endToEndLatency().isEmpty() ? "n/a" : duration(g.endToEndLatency().max()),
          g.queueLatency().isEmpty() ? "n/a" : duration(g.queueLatency().max()),
          g.slackAtDispatch().isEmpty() ? "n/a" : signedDuration(g.slackAtDispatch().min()),
          g.lateness().isEmpty() ? "n/a" : duration(g.lateness().max()),
          optionalDuration(g.lastDeliveryTime())));
    }
    return table;
  }

  /** 테넌트가 많을 때: 유형별로 테넌트 간 미스율의 최소·최대와 미스가 있는 테넌트 수. */
  private static String spreadTable(List<ExperimentResult> arms, Set<String> types) {
    StringBuilder out = new StringBuilder();
    for (String type : types) {
      TextTable table = new TextTable(List.of("arm", "tenants", "tenants with miss",
          "min tenant miss", "max tenant miss", "max tenant E2E p99"));
      for (ExperimentResult arm : arms) {
        double min = Double.MAX_VALUE;
        double max = 0;
        long withMiss = 0;
        long tenants = 0;
        long worstP99 = -1;
        for (Map.Entry<String, GroupMetrics> e : arm.result().tenantTypeGroups().entrySet()) {
          if (!e.getKey().endsWith("/" + type) || e.getValue().received() == 0) {
            continue;
          }
          tenants++;
          double miss = e.getValue().deadlineMissRate().orElse(0);
          min = Math.min(min, miss);
          max = Math.max(max, miss);
          withMiss += miss > 0 ? 1 : 0;
          if (!e.getValue().endToEndLatency().isEmpty()) {
            worstP99 = Math.max(worstP99, e.getValue().endToEndLatency().p99());
          }
        }
        table.row(List.of(armName(arm), count(tenants), count(withMiss),
            tenants == 0 ? "n/a" : pct(min), tenants == 0 ? "n/a" : pct(max),
            worstP99 < 0 ? "n/a" : duration(worstP99)));
      }
      out.append("\nPer-tenant spread for ").append(type).append('\n').append(table.render("  "));
    }
    return out.toString();
  }

  private static String fairness(List<ExperimentResult> arms) {
    if (arms.stream().noneMatch(a -> a.result().fairness().isPresent())) {
      return "";
    }
    WindowedFairness first = arms.get(0).result().fairness().orElseThrow();
    List<String> header = new ArrayList<>(List.of("arm", "Jain count", "Jain cost",
        "short Jain min", "short Jain mean", "max gap", "max lag behind"));
    boolean perTenant = first.shares().size() <= 8;
    if (perTenant) {
      first.shares().forEach(s -> header.add(s.tenantId() + " share (w" + s.weight() + ")"));
      first.shares().forEach(s -> header.add(s.tenantId() + " /s"));
    }
    TextTable table = new TextTable(header);
    double windowSeconds = (first.window().end() - first.window().start()) / 1e6;
    long totalWeight = first.shares().stream().mapToLong(WindowedFairness.TenantShare::weight)
        .sum();
    for (ExperimentResult arm : arms) {
      WindowedFairness f = arm.result().fairness().orElseThrow();
      long totalCost = f.shares().stream()
          .mapToLong(WindowedFairness.TenantShare::dispatchedCost).sum();
      List<String> row = new ArrayList<>(List.of(armName(arm), index(f.jainIndexByCount()),
          index(f.jainIndexByCost()), index(f.shortWindowJainMinByCost()),
          index(f.shortWindowJainMeanByCost()),
          duration(f.shares().stream().mapToLong(WindowedFairness.TenantShare::maxServiceGap)
              .max().orElse(0)),
          String.format(Locale.ROOT, "%.1f", f.shares().stream()
              .mapToDouble(WindowedFairness.TenantShare::maxLagBehind).max().orElse(0))));
      if (perTenant) {
        f.shares().forEach(s -> row.add(totalCost == 0 ? "n/a"
            : String.format(Locale.ROOT, "%.3f%%", 100.0 * s.dispatchedCost() / totalCost)));
        f.shares().forEach(s -> row.add(String.format(Locale.ROOT, "%,.1f",
            s.dispatchedCost() / windowSeconds)));
      }
      table.row(row);
    }
    String backlogNote = arms.stream().allMatch(a -> a.result().fairness().orElseThrow()
        .allTenantsBacklogged())
        ? ""
        : "  (Jain/lag 'n/a' or not meaningful where a tenant was not backlogged for the whole"
            + " window; shares are still reported)\n";
    StringBuilder weights = new StringBuilder();
    if (perTenant) {
      first.shares().forEach(s -> weights.append(String.format(Locale.ROOT, " %s=%.3f%%",
          s.tenantId(), 100.0 * s.weight() / totalWeight)));
    }
    return String.format(Locale.ROOT,
        "%nService in window [%s, %s), short windows of %s (cost units; lag in cost units)%n",
        duration(first.window().start()), duration(first.window().end()),
        duration(first.window().shortWindowLength()))
        + (perTenant ? "  weight shares:" + weights + "\n" : "")
        + table.render("  ") + backlogNote + (perTenant ? "" : wideShares(arms, windowSeconds));
  }

  /** 테넌트가 많을 때 (최대 64): 행 = 테넌트, 열 = arm 의 구간 처리량 (cost 단위/초). */
  private static String wideShares(List<ExperimentResult> arms, double windowSeconds) {
    List<WindowedFairness.TenantShare> reference =
        arms.get(0).result().fairness().orElseThrow().shares();
    if (reference.size() > 64) {
      return "";
    }
    List<String> header = new ArrayList<>(List.of("tenant", "weight", "backlogged(all arms)"));
    arms.forEach(a -> header.add(armName(a) + " /s"));
    TextTable table = new TextTable(header);
    for (int i = 0; i < reference.size(); i++) {
      final int index = i;
      boolean backlogged = arms.stream().allMatch(a -> a.result().fairness().orElseThrow()
          .shares().get(index).backloggedThroughoutWindow());
      List<String> row = new ArrayList<>(List.of(reference.get(i).tenantId(),
          String.valueOf(reference.get(i).weight()), backlogged ? "yes" : "no"));
      for (ExperimentResult arm : arms) {
        row.add(String.format(Locale.ROOT, "%,.1f", arm.result().fairness().orElseThrow()
            .shares().get(index).dispatchedCost() / windowSeconds));
      }
      table.row(row);
    }
    return "  Per-tenant service rate in window (cost units per second)\n" + table.render("  ");
  }

  private static String totals(List<ExperimentResult> arms) {
    TextTable table = new TextTable(List.of("arm", "delivered", "conserved", "capacity viol",
        "virtual elapsed", "cost/s", "selections", "operations", "ops/selection"));
    for (ExperimentResult arm : arms) {
      SimulationResult r = arm.result();
      table.row(List.of(armName(arm), count(r.totalDelivered()), r.isConserved() ? "yes" : "NO",
          count(r.providerCapacityViolations()), duration(r.virtualElapsed()),
          String.format(Locale.ROOT, "%,.1f", r.deliveredCostPerSecond()),
          count(r.schedulerSelections()), count(r.schedulerOperations()),
          r.schedulerSelections() == 0 ? "n/a" : String.format(Locale.ROOT, "%.3f",
              r.schedulerOperations() / (double) r.schedulerSelections())));
    }
    return "\nTotals and scheduler work (deterministic)\n" + table.render("  ");
  }

  private static String diagnostics(List<ExperimentResult> arms) {
    List<String> header = new ArrayList<>(List.of("diagnostic"));
    arms.forEach(a -> header.add(armName(a)));
    TextTable table = new TextTable(header);
    for (String key : DIAGNOSTICS) {
      if (arms.stream().noneMatch(a -> a.result().schedulerDiagnostics().containsKey(key))) {
        continue;
      }
      List<String> row = new ArrayList<>(List.of(key));
      for (ExperimentResult arm : arms) {
        Long value = arm.result().schedulerDiagnostics().get(key);
        row.add(value == null ? "-" : count(value));
      }
      table.row(row);
    }
    return table.isEmpty() ? "" : "\nScheduler diagnostics (algorithmic counts, not CPU"
        + " instructions)\n" + table.render("  ");
  }

  private static String execution(List<ExperimentResult> arms) {
    TextTable table = new TextTable(List.of("arm", "runs", "wall median", "wall min",
        "wall max", "spread", "thread CPU median", "sched elapsed (corr.)"));
    for (ExperimentResult arm : arms) {
      double max = arm.measuredRuns().stream().mapToLong(ExecutionProfile::wallClockNanos)
          .max().orElse(0);
      double min = arm.minWallClockNanos();
      double median = arm.medianWallClockNanos();
      OptionalDouble cpu = arm.medianThreadCpuNanos();
      table.row(List.of(armName(arm), String.valueOf(arm.measuredRuns().size()), millis(median),
          millis(min), millis(max),
          median == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100 * (max - min) / median),
          cpu.isPresent() ? millis(cpu.getAsDouble()) : "n/a",
          millis(arm.medianSchedulerElapsedNanosCorrected())));
    }
    return "\nExecution (wall-clock, non-deterministic; simulator cost, not production cost)\n"
        + table.render("  ");
  }

  private static String armName(ExperimentResult arm) {
    return arm.config().experimentId() + " " + arm.result().policyName();
  }

  private static String count(long value) {
    return String.format(Locale.ROOT, "%,d", value);
  }

  private static String rate(OptionalDouble value) {
    return value.isPresent() ? pct(value.getAsDouble()) : "n/a";
  }

  private static String pct(double fraction) {
    return String.format(Locale.ROOT, "%.3f%%", 100 * fraction);
  }

  private static String index(OptionalDouble value) {
    return value.isPresent() ? String.format(Locale.ROOT, "%.6f", value.getAsDouble()) : "n/a";
  }

  private static String millis(double nanos) {
    return String.format(Locale.ROOT, "%.1f ms", nanos / 1e6);
  }

  private static String optionalDuration(OptionalLong micros) {
    return micros.isPresent() ? duration(micros.getAsLong()) : "n/a";
  }

  private static String signedDuration(long micros) {
    return micros < 0 ? "-" + duration(-micros) : duration(micros);
  }

  /** 1 초 미만은 ms (μs 정밀도), 이상은 s (ms 정밀도). */
  static String duration(long micros) {
    return micros < 1_000_000
        ? String.format(Locale.ROOT, "%.3f ms", micros / 1e3)
        : String.format(Locale.ROOT, "%.3f s", micros / 1e6);
  }
}
