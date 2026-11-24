package com.raphael.report;

import com.raphael.core.VirtualClock;
import com.raphael.experiment.ExperimentResult;
import com.raphael.metrics.LatencySummary;
import com.raphael.metrics.SimulationResult;
import com.raphael.metrics.TenantMetrics;
import com.raphael.metrics.WindowedFairness;
import com.raphael.scenario.Scenario;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * 한 시나리오에 대한 실험 arm들을 나란히 비교하는 리포트를 렌더링한다. 첫 번째 arm이 기준선이며,
 * 나머지 각 arm에는 기준선 대비 절대 차이 열과 상대 차이 열이 붙는다.
 *
 * <p>모든 수치는 측정된 결과에서 읽어 온다. 샘플이 없거나 적용할 수 없는 지표는 0이 아니라
 * {@code n/a}로 표시한다. 기준선이 0일 때의 상대 변화도 {@code n/a}다.
 */
public final class ComparisonReport {

  /**
   * 별도의 주요 섹션을 할당받는 테넌트들.
   *
   * @param otpTenantId 지연 시간에 민감한 테넌트, 없으면 {@code null}
   * @param campaignTenantId 대량 발송 테넌트, 없으면 {@code null}
   */
  public record Focus(String otpTenantId, String campaignTenantId) {
    public static final Focus NONE = new Focus(null, null);
  }

  private enum Kind { DURATION, COUNT, RATE, INDEX, MILLIS, DECIMAL }

  private record Row(String label, Kind kind, List<Double> values) {
  }

  private ComparisonReport() {
  }

  public static String render(Scenario scenario, Focus focus, List<ExperimentResult> arms) {
    if (arms.isEmpty()) {
      throw new IllegalArgumentException("No experiment results to report");
    }
    StringBuilder out = new StringBuilder();
    out.append(String.format(Locale.ROOT, "%n=== %s: %s ===%n", scenario.name(),
        scenario.description()));
    out.append(String.format(Locale.ROOT,
        "Messages: %,d | Provider: %,d TPS | Delivery delay: %s%n",
        scenario.messages().size(), scenario.providerTps(),
        formatDuration(scenario.deliveryDelay())));
    for (ExperimentResult arm : arms) {
      out.append(String.format(Locale.ROOT, "  %s = %s [%s]%n", arm.config().experimentId(),
          arm.config().label(), arm.result().policyName()));
    }

    List<Row> rows = new ArrayList<>();
    if (focus.otpTenantId() != null) {
      rows.add(section("OTP tenant " + focus.otpTenantId()));
      addOtpRows(rows, arms, focus.otpTenantId());
    }
    if (focus.campaignTenantId() != null) {
      rows.add(section("Campaign tenant " + focus.campaignTenantId()));
      addCampaignRows(rows, arms, focus.campaignTenantId());
    }
    rows.add(section("Totals"));
    rows.add(count(arms, "received", SimulationResult::totalReceived));
    rows.add(count(arms, "delivered", SimulationResult::totalDelivered));
    rows.add(duration(arms, "virtual elapsed time", r -> OptionalLong.of(r.virtualElapsed())));
    rows.add(count(arms, "provider capacity violations",
        SimulationResult::providerCapacityViolations));
    rows.add(section("Scheduler work (deterministic)"));
    rows.add(count(arms, "selections", SimulationResult::schedulerSelections));
    rows.add(count(arms, "operations", SimulationResult::schedulerOperations));
    rows.add(new Row("  operations / selection", Kind.DECIMAL, arms.stream()
        .map(a -> ratio(a.result().schedulerOperations(), a.result().schedulerSelections()))
        .toList()));
    addFairnessRows(rows, arms);
    addExecutionRows(rows, arms);

    out.append(renderTable(arms, rows));
    out.append(renderTenantDetails(arms));
    out.append(renderFairnessDetails(arms));
    out.append(String.format(Locale.ROOT,
        "Execution timing: median of %d measured runs after %d warm-up run(s). Scheduler time is"
            + " elapsed time summed per call (not CPU time); 'corrected' subtracts a calibrated"
            + " %.1f ns timer bias per call. Timing differences below run-to-run spread are not"
            + " evidence of a performance difference.%n",
        arms.get(0).measuredRuns().size(), arms.get(0).warmupRuns(),
        arms.get(0).measuredRuns().get(0).timerBiasNanosPerCall()));
    return out.toString();
  }

  private static void addOtpRows(List<Row> rows, List<ExperimentResult> arms, String tenant) {
    rows.add(tenantCount(arms, tenant, "received", TenantMetrics::received));
    rows.add(tenantCount(arms, tenant, "delivered", TenantMetrics::delivered));
    rows.add(tenantCount(arms, tenant, "deadline misses", TenantMetrics::deadlineMisses));
    rows.add(tenantRate(arms, tenant, "deadline miss rate", TenantMetrics::deadlineMissRate));
    rows.add(latency(arms, tenant, "E2E p50", TenantMetrics::endToEndLatency, Pct.P50));
    rows.add(latency(arms, tenant, "E2E p95", TenantMetrics::endToEndLatency, Pct.P95));
    rows.add(latency(arms, tenant, "E2E p99", TenantMetrics::endToEndLatency, Pct.P99));
    rows.add(latency(arms, tenant, "E2E max", TenantMetrics::endToEndLatency, Pct.MAX));
    rows.add(latency(arms, tenant, "queue p99", TenantMetrics::queueLatency, Pct.P99));
    rows.add(latency(arms, tenant, "queue max", TenantMetrics::queueLatency, Pct.MAX));
  }

  private static void addCampaignRows(List<Row> rows, List<ExperimentResult> arms, String t) {
    rows.add(tenantCount(arms, t, "delivered", TenantMetrics::delivered));
    rows.add(tenantRate(arms, t, "deadline miss rate", TenantMetrics::deadlineMissRate));
    rows.add(latency(arms, t, "queue p99", TenantMetrics::queueLatency, Pct.P99));
    rows.add(latency(arms, t, "queue max", TenantMetrics::queueLatency, Pct.MAX));
    rows.add(new Row("  campaign completion (last delivery)", Kind.DURATION, arms.stream()
        .map(a -> toDouble(a.result().tenant(t).lastDeliveryTime())).toList()));
  }

  private static void addFairnessRows(List<Row> rows, List<ExperimentResult> arms) {
    if (arms.stream().noneMatch(a -> a.result().fairness().isPresent())) {
      return;
    }
    rows.add(section("Weighted fairness in observation window (Jain, weight-normalised)"));
    rows.add(new Row("  Jain index by dispatched count", Kind.INDEX, arms.stream()
        .map(a -> a.result().fairness().map(f -> toDouble(f.jainIndexByCount())).orElse(null))
        .toList()));
    rows.add(new Row("  Jain index by scheduling cost", Kind.INDEX, arms.stream()
        .map(a -> a.result().fairness().map(f -> toDouble(f.jainIndexByCost())).orElse(null))
        .toList()));
  }

  private static void addExecutionRows(List<Row> rows, List<ExperimentResult> arms) {
    rows.add(section("Execution (wall-clock, non-deterministic)"));
    rows.add(new Row("  wall-clock median", Kind.MILLIS,
        arms.stream().map(a -> (Double) a.medianWallClockNanos()).toList()));
    rows.add(new Row("  wall-clock min", Kind.MILLIS,
        arms.stream().map(a -> (Double) a.minWallClockNanos()).toList()));
    rows.add(new Row("  thread CPU median", Kind.MILLIS,
        arms.stream().map(a -> toDouble(a.medianThreadCpuNanos())).toList()));
    rows.add(new Row("  scheduler elapsed median (raw)", Kind.MILLIS,
        arms.stream().map(a -> (Double) a.medianSchedulerElapsedNanos()).toList()));
    rows.add(new Row("  scheduler elapsed median (corrected)", Kind.MILLIS,
        arms.stream().map(a -> (Double) a.medianSchedulerElapsedNanosCorrected()).toList()));
  }

  private static String renderTenantDetails(List<ExperimentResult> arms) {
    List<Row> rows = new ArrayList<>();
    for (String tenant : arms.get(0).result().tenants().keySet()) {
      rows.add(section("Tenant " + tenant + " (weight "
          + arms.get(0).result().tenant(tenant).weight() + ")"));
      rows.add(tenantCount(arms, tenant, "received", TenantMetrics::received));
      rows.add(tenantCount(arms, tenant, "dispatched", TenantMetrics::dispatched));
      rows.add(tenantCount(arms, tenant, "delivered", TenantMetrics::delivered));
      rows.add(tenantCount(arms, tenant, "deadline misses", TenantMetrics::deadlineMisses));
      rows.add(tenantRate(arms, tenant, "deadline miss rate", TenantMetrics::deadlineMissRate));
      rows.add(latency(arms, tenant, "queue p50", TenantMetrics::queueLatency, Pct.P50));
      rows.add(latency(arms, tenant, "queue p95", TenantMetrics::queueLatency, Pct.P95));
      rows.add(latency(arms, tenant, "queue p99", TenantMetrics::queueLatency, Pct.P99));
      rows.add(latency(arms, tenant, "queue max", TenantMetrics::queueLatency, Pct.MAX));
      rows.add(latency(arms, tenant, "E2E p50", TenantMetrics::endToEndLatency, Pct.P50));
      rows.add(latency(arms, tenant, "E2E p95", TenantMetrics::endToEndLatency, Pct.P95));
      rows.add(latency(arms, tenant, "E2E p99", TenantMetrics::endToEndLatency, Pct.P99));
      rows.add(latency(arms, tenant, "E2E max", TenantMetrics::endToEndLatency, Pct.MAX));
      rows.add(new Row("  E2E average", Kind.DURATION, arms.stream()
          .map(a -> toDouble(a.result().tenant(tenant).averageEndToEndLatency())).toList()));
      rows.add(new Row("  last delivery", Kind.DURATION, arms.stream()
          .map(a -> toDouble(a.result().tenant(tenant).lastDeliveryTime())).toList()));
    }
    return String.format(Locale.ROOT, "%nPer-tenant detail%n") + renderTable(arms, rows);
  }

  private static String renderFairnessDetails(List<ExperimentResult> arms) {
    StringBuilder out = new StringBuilder();
    for (ExperimentResult arm : arms) {
      if (arm.result().fairness().isEmpty()) {
        continue;
      }
      WindowedFairness fairness = arm.result().fairness().get();
      long totalCost = fairness.shares().stream()
          .mapToLong(WindowedFairness.TenantShare::dispatchedCost).sum();
      long totalWeight = fairness.shares().stream()
          .mapToLong(WindowedFairness.TenantShare::weight).sum();
      out.append(String.format(Locale.ROOT,
          "%nService allocation in window [%s, %s) for %s%n", formatDuration(fairness.window()
              .start()), formatDuration(fairness.window().end()), arm.config().experimentId()));
      out.append(String.format(Locale.ROOT, "  %-8s %6s %12s %12s %12s %12s %10s%n",
          "tenant", "weight", "dispatched", "cost", "cost share", "weight share", "backlogged"));
      for (WindowedFairness.TenantShare share : fairness.shares()) {
        out.append(String.format(Locale.ROOT, "  %-8s %6d %,12d %,12d %12s %11.4f%% %10s%n",
            share.tenantId(), share.weight(), share.dispatchedCount(), share.dispatchedCost(),
            totalCost == 0 ? "n/a" : String.format(Locale.ROOT, "%.4f%%",
                100.0 * share.dispatchedCost() / totalCost),
            100.0 * share.weight() / totalWeight,
            share.backloggedThroughoutWindow() ? "yes" : "NO"));
      }
      if (!fairness.allTenantsBacklogged()) {
        out.append("  Fairness index not applicable: not every tenant was continuously"
            + " backlogged for the whole window.\n");
      }
    }
    return out.toString();
  }

  private static String renderTable(List<ExperimentResult> arms, List<Row> rows) {
    List<String> header = new ArrayList<>();
    header.add("Metric");
    for (ExperimentResult arm : arms) {
      header.add(arm.config().experimentId() + " " + arm.result().policyName());
    }
    for (int i = 1; i < arms.size(); i++) {
      String id = arms.get(i).config().experimentId();
      header.add("Δ " + id + "−" + arms.get(0).config().experimentId());
      header.add("Δ% " + id);
    }
    List<List<String>> table = new ArrayList<>();
    table.add(header);
    for (Row row : rows) {
      List<String> cells = new ArrayList<>();
      cells.add(row.label());
      if (row.kind() == null) {
        table.add(cells);
        continue;
      }
      for (Double value : row.values()) {
        cells.add(format(row.kind(), value));
      }
      Double base = row.values().get(0);
      for (int i = 1; i < row.values().size(); i++) {
        Double value = row.values().get(i);
        cells.add(formatDelta(row.kind(), base, value));
        cells.add(formatRelative(base, value));
      }
      table.add(cells);
    }
    int columns = header.size();
    int[] widths = new int[columns];
    for (List<String> cells : table) {
      if (cells.size() == 1) {
        continue;
      }
      for (int c = 0; c < cells.size(); c++) {
        widths[c] = Math.max(widths[c], cells.get(c).length());
      }
    }
    StringBuilder out = new StringBuilder();
    for (List<String> cells : table) {
      if (cells.size() == 1) {
        out.append(cells.get(0)).append('\n');
        continue;
      }
      for (int c = 0; c < columns; c++) {
        String cell = cells.get(c);
        out.append(c == 0 ? padRight(cell, widths[c]) : padLeft(cell, widths[c] + 2));
      }
      out.append('\n');
    }
    return out.toString();
  }

  private enum Pct { P50, P95, P99, MAX }

  private static Row section(String title) {
    return new Row(title, null, List.of());
  }

  private static Row count(
      List<ExperimentResult> arms, String label, Function<SimulationResult, Long> metric) {
    return new Row("  " + label, Kind.COUNT,
        arms.stream().map(a -> (double) metric.apply(a.result())).toList());
  }

  private static Row duration(
      List<ExperimentResult> arms, String label, Function<SimulationResult, OptionalLong> metric) {
    return new Row("  " + label, Kind.DURATION,
        arms.stream().map(a -> toDouble(metric.apply(a.result()))).toList());
  }

  private static Row tenantCount(List<ExperimentResult> arms, String tenant, String label,
      Function<TenantMetrics, Long> metric) {
    return new Row("  " + label, Kind.COUNT,
        arms.stream().map(a -> (double) metric.apply(a.result().tenant(tenant))).toList());
  }

  private static Row tenantRate(List<ExperimentResult> arms, String tenant, String label,
      Function<TenantMetrics, OptionalDouble> metric) {
    return new Row("  " + label, Kind.RATE,
        arms.stream().map(a -> toDouble(metric.apply(a.result().tenant(tenant)))).toList());
  }

  private static Row latency(List<ExperimentResult> arms, String tenant, String label,
      Function<TenantMetrics, LatencySummary> summary, Pct pct) {
    List<Double> values = new ArrayList<>();
    for (ExperimentResult arm : arms) {
      LatencySummary s = summary.apply(arm.result().tenant(tenant));
      if (s.isEmpty()) {
        values.add(null);
        continue;
      }
      values.add((double) switch (pct) {
        case P50 -> s.p50();
        case P95 -> s.p95();
        case P99 -> s.p99();
        case MAX -> s.max();
      });
    }
    return new Row("  " + label, Kind.DURATION, values);
  }

  private static Double ratio(long numerator, long denominator) {
    return denominator == 0 ? null : numerator / (double) denominator;
  }

  private static Double toDouble(OptionalLong value) {
    return value.isPresent() ? (double) value.getAsLong() : null;
  }

  private static Double toDouble(OptionalDouble value) {
    return value.isPresent() ? value.getAsDouble() : null;
  }

  private static String format(Kind kind, Double value) {
    if (value == null) {
      return "n/a";
    }
    return switch (kind) {
      case DURATION -> formatDuration(value);
      case COUNT -> String.format(Locale.ROOT, "%,.0f", value);
      case RATE -> String.format(Locale.ROOT, "%.4f%%", 100 * value);
      case INDEX -> String.format(Locale.ROOT, "%.6f", value);
      case MILLIS -> String.format(Locale.ROOT, "%.3f ms", value / 1e6);
      case DECIMAL -> String.format(Locale.ROOT, "%.4f", value);
    };
  }

  private static String formatDelta(Kind kind, Double base, Double value) {
    if (base == null || value == null) {
      return "n/a";
    }
    double delta = value - base;
    return switch (kind) {
      case DURATION -> (delta >= 0 ? "+" : "-") + formatDuration(Math.abs(delta));
      case COUNT -> String.format(Locale.ROOT, "%+,.0f", delta);
      case RATE -> String.format(Locale.ROOT, "%+.4f pp", 100 * delta);
      case INDEX -> String.format(Locale.ROOT, "%+.6f", delta);
      case MILLIS -> String.format(Locale.ROOT, "%+.3f ms", delta / 1e6);
      case DECIMAL -> String.format(Locale.ROOT, "%+.4f", delta);
    };
  }

  private static String formatRelative(Double base, Double value) {
    if (base == null || value == null) {
      return "n/a";
    }
    if (base == 0) {
      return value == 0 ? "0.00%" : "n/a";
    }
    return String.format(Locale.ROOT, "%+.2f%%", 100 * (value - base) / base);
  }

  /** 가상 마이크로초를 마이크로초 정밀도를 그대로 유지한 초 단위로 포맷한다. */
  private static String formatDuration(double micros) {
    return String.format(Locale.ROOT, "%.6f s", micros / VirtualClock.MICROS_PER_SECOND);
  }

  private static String padRight(String text, int width) {
    return text.length() >= width ? text : text + " ".repeat(width - text.length());
  }

  private static String padLeft(String text, int width) {
    return text.length() >= width ? text : " ".repeat(width - text.length()) + text;
  }
}
