package com.raphael.persistence;

import com.raphael.experiment.BlastRadius;
import com.raphael.experiment.ExecutionProfile;
import com.raphael.experiment.ExperimentResult;
import com.raphael.metrics.GroupMetrics;
import com.raphael.metrics.LatencySummary;
import com.raphael.metrics.SimulationResult;
import com.raphael.metrics.TenantMetrics;
import com.raphael.metrics.WindowedFairness;
import com.raphael.scenario.Scenario;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * 실험 결과를 CSV·JSON 으로 저장한다 (v0.4). 실행이 모두 끝난 뒤 호출되므로 직렬화와 파일 I/O 는 성능
 * 측정에 포함되지 않는다. 실행마다 새 디렉터리를 만들고 (이미 있으면 번호를 붙임) 기존 결과를 덮어쓰지
 * 않는다.
 *
 * <p>규칙: UTF-8, 고정 필드 이름 (snake_case), 시간은 정수 μs (가상 시간), 실행 시간은 정수 ns, 숫자는
 * 로케일 무관 ({@link BigDecimal#toPlainString()}), 값이 없으면 CSV 는 빈 칸, JSON 은 {@code null}.
 */
public final class ResultWriter {

  public static final String SIMULATOR_VERSION = "0.4.0";
  public static final int SCHEMA_VERSION = 1;

  /**
   * 저장할 실험 하나.
   *
   * @param workload 워크로드 설정 (seed 포함; 시나리오 생성 파라미터)
   * @param blastRadius 장애 비교 결과, 없으면 {@code null}
   */
  public record Entry(Scenario scenario, Map<String, String> workload, ExperimentResult result,
      BlastRadius blastRadius) {
  }

  private ResultWriter() {
  }

  /** {@code baseDir/<suite>-<UTC 시각>[-n]} 에 7 개 파일을 쓰고 그 디렉터리를 돌려준다. */
  public static Path write(Path baseDir, String suite, Map<String, String> runSettings,
      List<Entry> entries) throws IOException {
    Files.createDirectories(baseDir);
    String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.of("Asia/Seoul"))
        .format(Instant.now());
    Path dir = null;
    for (int n = 1; dir == null; n++) {
      Path candidate = baseDir.resolve(suite + "-" + stamp + (n == 1 ? "" : "-" + n));
      try {
        dir = Files.createDirectory(candidate);
      } catch (FileAlreadyExistsException exists) {
        dir = null;
      }
    }
    List<String> files = List.of("manifest.json", "experiment-config.json",
        "experiment-results.json", "experiment-summary.csv", "tenant-metrics.csv",
        "message-type-metrics.csv", "scheduler-metrics.csv");

    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("simulator_version", SIMULATOR_VERSION);
    manifest.put("schema_version", SCHEMA_VERSION);
    manifest.put("suite", suite);
    manifest.put("created_at_utc", Instant.now().toString());
    manifest.put("java_version", System.getProperty("java.version"));
    manifest.put("time_units", Map.of("virtual_time", "microseconds",
        "execution_time", "nanoseconds"));
    manifest.put("run_settings", runSettings);
    manifest.put("experiment_count", entries.size());
    manifest.put("files", files);
    write(dir.resolve("manifest.json"), Json.write(manifest));

    List<Object> configs = new ArrayList<>();
    List<Object> results = new ArrayList<>();
    for (Entry e : entries) {
      configs.add(configJson(e));
      results.add(resultJson(e));
    }
    write(dir.resolve("experiment-config.json"), Json.write(configs));
    write(dir.resolve("experiment-results.json"), Json.write(results));
    write(dir.resolve("experiment-summary.csv"), summaryCsv(entries));
    write(dir.resolve("tenant-metrics.csv"), tenantCsv(entries));
    write(dir.resolve("message-type-metrics.csv"), typeCsv(entries));
    write(dir.resolve("scheduler-metrics.csv"), schedulerCsv(entries));
    return dir;
  }

  // ---------------------------------------------------------------- JSON

  private static Map<String, Object> configJson(Entry e) {
    Scenario s = e.scenario();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("scenario_id", s.name());
    m.put("experiment_id", e.result().config().experimentId());
    m.put("label", e.result().config().label());
    m.put("settings", e.result().config().settings());
    Map<String, Object> workload = new LinkedHashMap<>(e.workload());
    workload.put("description", s.description());
    workload.put("messages", s.messages().size());
    workload.put("provider_capacity", s.providerCapacity());
    workload.put("service_model", s.serviceModel().name());
    workload.put("delivery_delay_us", s.deliveryDelay());
    List<Object> tenants = new ArrayList<>();
    s.tenants().forEach(t -> tenants.add(Map.of("tenant_id", t.id(), "weight", t.weight())));
    workload.put("tenants", tenants);
    workload.put("observation_window", s.observationWindow().map(w -> Map.of(
        "start_us", w.start(), "end_us", w.end(), "short_window_us", w.shortWindowLength()))
        .orElse(null));
    m.put("workload", workload);
    return m;
  }

  private static Map<String, Object> resultJson(Entry e) {
    SimulationResult r = e.result().result();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("scenario_id", e.scenario().name());
    m.put("experiment_id", e.result().config().experimentId());
    m.put("policy", r.policyName());
    m.put("virtual_elapsed_us", r.virtualElapsed());
    m.put("received", r.totalReceived());
    m.put("dispatched", r.totalDispatched());
    m.put("delivered", r.totalDelivered());
    m.put("conserved", r.isConserved());
    m.put("provider_capacity_violations", r.providerCapacityViolations());
    m.put("service_budget_violations", r.serviceBudgetViolations());
    m.put("shard_capacity_violations",
        r.schedulerDiagnostics().getOrDefault("shardCapacityViolations", 0L));
    m.put("scheduler_selections", r.schedulerSelections());
    m.put("scheduler_operations", r.schedulerOperations());
    List<Object> tenants = new ArrayList<>();
    r.tenants().values().forEach(t -> tenants.add(tenantJson(t)));
    m.put("tenants", tenants);
    List<Object> types = new ArrayList<>();
    r.messageTypeGroups().values().forEach(g -> types.add(groupJson("TYPE", g)));
    r.tenantTypeGroups().values().forEach(g -> types.add(groupJson("TENANT_TYPE", g)));
    m.put("message_groups", types);
    m.put("fairness", r.fairness().map(ResultWriter::fairnessJson).orElse(null));
    m.put("scheduler_diagnostics", r.schedulerDiagnostics());
    Map<String, Object> execution = new LinkedHashMap<>();
    execution.put("warmup_runs", e.result().warmupRuns());
    List<Object> runs = new ArrayList<>();
    for (ExecutionProfile p : e.result().measuredRuns()) {
      Map<String, Object> run = new LinkedHashMap<>();
      run.put("wall_clock_ns", p.wallClockNanos());
      run.put("thread_cpu_ns", p.threadCpuNanos());
      run.put("scheduler_elapsed_ns", p.schedulerElapsedNanos());
      run.put("scheduler_timed_calls", p.schedulerTimedCalls());
      runs.add(run);
    }
    execution.put("measured_runs", runs);
    m.put("execution", execution);
    m.put("blast_radius", e.blastRadius() == null ? null : blastJson(e.blastRadius()));
    return m;
  }

  private static Map<String, Object> tenantJson(TenantMetrics t) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("tenant_id", t.tenantId());
    m.put("weight", t.weight());
    m.put("received", t.received());
    m.put("dispatched", t.dispatched());
    m.put("delivered", t.delivered());
    m.put("deadline_misses", t.deadlineMisses());
    m.put("deadline_miss_rate", t.deadlineMissRate());
    putLatency(m, "queue", t.queueLatency());
    putLatency(m, "e2e", t.endToEndLatency());
    m.put("e2e_avg_us", t.averageEndToEndLatency());
    m.put("last_delivery_us", t.lastDeliveryTime());
    return m;
  }

  private static Map<String, Object> groupJson(String kind, GroupMetrics g) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("group_kind", kind);
    m.put("group_key", g.key());
    m.put("received", g.received());
    m.put("dispatched", g.dispatched());
    m.put("delivered", g.delivered());
    m.put("deadline_misses", g.deadlineMisses());
    m.put("deadline_miss_rate", g.deadlineMissRate());
    m.put("paper_departure_violations", g.paperDepartureViolations());
    putLatency(m, "queue", g.queueLatency());
    putLatency(m, "e2e", g.endToEndLatency());
    m.put("last_delivery_us", g.lastDeliveryTime());
    return m;
  }

  private static Map<String, Object> fairnessJson(WindowedFairness f) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("window_start_us", f.window().start());
    m.put("window_end_us", f.window().end());
    m.put("all_backlogged", f.allTenantsBacklogged());
    m.put("jain_count", f.jainIndexByCount());
    m.put("jain_cost", f.jainIndexByCost());
    m.put("short_jain_min_cost", f.shortWindowJainMinByCost());
    m.put("short_jain_mean_cost", f.shortWindowJainMeanByCost());
    List<Object> shares = new ArrayList<>();
    for (WindowedFairness.TenantShare s : f.shares()) {
      Map<String, Object> share = new LinkedHashMap<>();
      share.put("tenant_id", s.tenantId());
      share.put("weight", s.weight());
      share.put("dispatched", s.dispatchedCount());
      share.put("cost", s.dispatchedCost());
      share.put("backlogged", s.backloggedThroughoutWindow());
      share.put("max_gap_us", s.maxServiceGap());
      shares.add(share);
    }
    m.put("shares", shares);
    return m;
  }

  private static Map<String, Object> blastJson(BlastRadius b) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("criterion", "affected if miss-rate increase > 1 pp (denominator: tenant received)"
        + " or E2E p99 > 1.5x baseline and +1 s");
    m.put("non_aggressor_tenants", b.nonAggressorTenants());
    m.put("affected_tenants", b.affectedTenants());
    m.put("additional_misses", b.additionalMisses());
    List<Object> impacts = new ArrayList<>();
    for (BlastRadius.TenantImpact i : b.impacts()) {
      Map<String, Object> impact = new LinkedHashMap<>();
      impact.put("tenant_id", i.tenantId());
      impact.put("baseline_miss_rate", i.baselineMissRate());
      impact.put("failure_miss_rate", i.failureMissRate());
      impact.put("baseline_e2e_p99_us", i.baselineE2eP99() < 0 ? null : i.baselineE2eP99());
      impact.put("failure_e2e_p99_us", i.failureE2eP99() < 0 ? null : i.failureE2eP99());
      impact.put("affected", i.affected());
      impacts.add(impact);
    }
    m.put("tenants", impacts);
    return m;
  }

  private static void putLatency(Map<String, Object> m, String prefix, LatencySummary s) {
    m.put(prefix + "_p50_us", s.isEmpty() ? null : s.p50());
    m.put(prefix + "_p99_us", s.isEmpty() ? null : s.p99());
    m.put(prefix + "_max_us", s.isEmpty() ? null : s.max());
  }

  // ---------------------------------------------------------------- CSV

  private static String summaryCsv(List<Entry> entries) {
    Csv csv = new Csv("scenario_id", "experiment_id", "policy", "received", "delivered",
        "conserved", "provider_capacity_violations", "service_budget_violations",
        "shard_capacity_violations", "virtual_elapsed_us", "otp_miss_rate", "otp_e2e_p99_us",
        "otp_queue_max_us", "ads_miss_rate", "ads_e2e_p99_us", "ads_queue_max_us",
        "ads_last_delivery_us", "jain_count", "jain_cost", "aging_promotions",
        "class_borrowed_slots", "non_aggressor_tenants", "affected_tenants",
        "additional_misses", "scheduler_operations", "wall_clock_median_ns",
        "thread_cpu_median_ns");
    for (Entry e : entries) {
      SimulationResult r = e.result().result();
      GroupMetrics otp = r.messageTypeGroups().get("OTP");
      GroupMetrics ads = r.messageTypeGroups().get("ADVERTISEMENT");
      WindowedFairness f = r.fairness().orElse(null);
      BlastRadius b = e.blastRadius();
      csv.row(e.scenario().name(), e.result().config().experimentId(), r.policyName(),
          r.totalReceived(), r.totalDelivered(), r.isConserved(), r.providerCapacityViolations(),
          r.serviceBudgetViolations(),
          r.schedulerDiagnostics().getOrDefault("shardCapacityViolations", 0L),
          r.virtualElapsed(),
          otp == null ? null : otp.deadlineMissRate(), otp == null ? null : p99(otp.endToEndLatency()),
          otp == null ? null : max(otp.queueLatency()),
          ads == null ? null : ads.deadlineMissRate(), ads == null ? null : p99(ads.endToEndLatency()),
          ads == null ? null : max(ads.queueLatency()),
          ads == null ? null : ads.lastDeliveryTime(),
          f == null ? null : f.jainIndexByCount(), f == null ? null : f.jainIndexByCost(),
          r.schedulerDiagnostics().get("agingPromotions"),
          r.schedulerDiagnostics().get("class.borrowedSlots"),
          b == null ? null : b.nonAggressorTenants(), b == null ? null : b.affectedTenants(),
          b == null ? null : b.additionalMisses(),
          r.schedulerOperations(), (long) e.result().medianWallClockNanos(),
          e.result().medianThreadCpuNanos().isPresent()
              ? (long) e.result().medianThreadCpuNanos().getAsDouble() : null);
    }
    return csv.toString();
  }

  private static String tenantCsv(List<Entry> entries) {
    Csv csv = new Csv("scenario_id", "experiment_id", "tenant_id", "weight", "received",
        "dispatched", "delivered", "deadline_misses", "deadline_miss_rate",
        "paper_departure_violations", "queue_p50_us", "queue_p99_us", "queue_max_us",
        "e2e_p50_us", "e2e_p99_us", "e2e_max_us", "e2e_avg_us", "last_delivery_us",
        "window_dispatched", "window_cost", "window_backlogged", "window_max_gap_us");
    for (Entry e : entries) {
      SimulationResult r = e.result().result();
      for (TenantMetrics t : r.tenants().values()) {
        GroupMetrics g = r.tenantGroups().get(t.tenantId());
        WindowedFairness.TenantShare share = r.fairness().flatMap(f -> f.shares().stream()
            .filter(s -> s.tenantId().equals(t.tenantId())).findFirst()).orElse(null);
        csv.row(e.scenario().name(), e.result().config().experimentId(), t.tenantId(),
            t.weight(), t.received(), t.dispatched(), t.delivered(), t.deadlineMisses(),
            t.deadlineMissRate(), g.paperDepartureViolations(), p50(t.queueLatency()),
            p99(t.queueLatency()), max(t.queueLatency()), p50(t.endToEndLatency()),
            p99(t.endToEndLatency()), max(t.endToEndLatency()), t.averageEndToEndLatency(),
            t.lastDeliveryTime(), share == null ? null : share.dispatchedCount(),
            share == null ? null : share.dispatchedCost(),
            share == null ? null : share.backloggedThroughoutWindow(),
            share == null ? null : share.maxServiceGap());
      }
    }
    return csv.toString();
  }

  private static String typeCsv(List<Entry> entries) {
    Csv csv = new Csv("scenario_id", "experiment_id", "group_kind", "group_key", "received",
        "dispatched", "delivered", "deadline_misses", "deadline_miss_rate",
        "paper_departure_violations", "queue_p50_us", "queue_p99_us", "queue_max_us",
        "e2e_p50_us", "e2e_p99_us", "e2e_max_us", "last_delivery_us");
    for (Entry e : entries) {
      SimulationResult r = e.result().result();
      List<Object[]> groups = new ArrayList<>();
      r.messageTypeGroups().values().forEach(g -> groups.add(new Object[] {"TYPE", g}));
      r.tenantTypeGroups().values().forEach(g -> groups.add(new Object[] {"TENANT_TYPE", g}));
      for (Object[] pair : groups) {
        GroupMetrics g = (GroupMetrics) pair[1];
        csv.row(e.scenario().name(), e.result().config().experimentId(), pair[0], g.key(),
            g.received(), g.dispatched(), g.delivered(), g.deadlineMisses(),
            g.deadlineMissRate(), g.paperDepartureViolations(), p50(g.queueLatency()),
            p99(g.queueLatency()), max(g.queueLatency()), p50(g.endToEndLatency()),
            p99(g.endToEndLatency()), max(g.endToEndLatency()), g.lastDeliveryTime());
      }
    }
    return csv.toString();
  }

  /** 긴 형식: 스케줄러 진단값과 샤드별 이용률 ({@code dispatches / (용량/샤드 × 경과 초)}). */
  private static String schedulerCsv(List<Entry> entries) {
    Csv csv = new Csv("scenario_id", "experiment_id", "metric", "value");
    for (Entry e : entries) {
      SimulationResult r = e.result().result();
      String scenario = e.scenario().name();
      String id = e.result().config().experimentId();
      csv.row(scenario, id, "scheduler_selections", r.schedulerSelections());
      csv.row(scenario, id, "scheduler_operations", r.schedulerOperations());
      r.schedulerDiagnostics().forEach((key, value) -> csv.row(scenario, id, key, value));
      String shards = e.result().config().settings().get("sharding.shards");
      if (shards != null && r.virtualElapsed() > 0) {
        int n = Integer.parseInt(shards);
        double perShardCapacity = e.scenario().providerCapacity() / (double) n;
        for (int s = 0; s < n; s++) {
          Long dispatches = r.schedulerDiagnostics().get("shard." + s + ".dispatches");
          if (dispatches != null) {
            csv.row(scenario, id, "shard." + s + ".utilization",
                dispatches / (perShardCapacity * r.virtualElapsed() / 1e6));
          }
        }
      }
    }
    return csv.toString();
  }

  private static Long p50(LatencySummary s) {
    return s.isEmpty() ? null : s.p50();
  }

  private static Long p99(LatencySummary s) {
    return s.isEmpty() ? null : s.p99();
  }

  private static Long max(LatencySummary s) {
    return s.isEmpty() ? null : s.max();
  }

  private static void write(Path path, String content) throws IOException {
    Files.writeString(path, content, StandardCharsets.UTF_8);
  }

  /** RFC 4180 형식 CSV. 빈 값은 빈 칸. */
  private static final class Csv {
    private final StringBuilder out = new StringBuilder();
    private final int columns;

    Csv(String... header) {
      columns = header.length;
      append((Object[]) header);
    }

    void row(Object... values) {
      if (values.length != columns) {
        throw new IllegalArgumentException("CSV row width " + values.length + " != " + columns);
      }
      append(values);
    }

    private void append(Object... values) {
      for (int i = 0; i < values.length; i++) {
        if (i > 0) {
          out.append(',');
        }
        out.append(cell(values[i]));
      }
      out.append('\n');
    }

    private static String cell(Object value) {
      Object v = value;
      if (v instanceof OptionalLong o) {
        v = o.isPresent() ? o.getAsLong() : null;
      } else if (v instanceof OptionalDouble o) {
        v = o.isPresent() ? o.getAsDouble() : null;
      }
      if (v == null) {
        return "";
      }
      String text = v instanceof Double d
          ? (Double.isFinite(d) ? BigDecimal.valueOf(d).toPlainString() : "")
          : v.toString();
      if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
        return '"' + text.replace("\"", "\"\"") + '"';
      }
      return text;
    }

    @Override
    public String toString() {
      return out.toString();
    }
  }
}
