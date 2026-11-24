package com.raphael;

import static com.raphael.core.VirtualClock.MICROS_PER_MILLISECOND;
import static com.raphael.core.VirtualClock.MICROS_PER_SECOND;

import com.raphael.core.SimulationObserver;
import com.raphael.domain.Message;
import com.raphael.domain.Tenant;
import com.raphael.experiment.ExperimentConfig;
import com.raphael.experiment.ExperimentRunner;
import com.raphael.metrics.ObservationWindow;
import com.raphael.report.ComparisonReport;
import com.raphael.scenario.Scenario;
import com.raphael.scenario.ScenarioGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 명령줄 진입점이다. 시나리오 S1~S4에 대해 FIFO(E0)와 DWRR(E1)을 실행하고 비교 리포트를
 * 출력한다.
 *
 * <p>옵션은 모두 선택 사항이며 {@code --key=value} 형식이다.
 * <pre>
 *   --suite=all|history|paper|message|sweep  (기본값 all)
 *                                history: v0.2 S1–S4b FIFO/DWRR 보고서 + 같은 시나리오의 v0.3 arm
 *                                paper: 논문 검증 P1–P4, message: S5–S12, sweep: Λ 민감도
 *   --scenario=all|s1|s2|s3|s4   history 에서 실행할 v0.2 시나리오 (기본값 all)
 *   --campaign=800000            S2와 S3의 광고 메시지 수
 *   --tps=450                    S2, S3, S4의 provider 용량
 *   --otp-interval-ms=333        S3 OTP 도착 간격(아래 참고)
 *   --otp-duration-s=1200        S3 OTP 생성 지속 시간
 *   --fairness-messages=200000   S4 테넌트당 메시지 수
 *   --window-start-s=30          S4 관측 윈도우 시작
 *   --window-end-s=330           S4 관측 윈도우 끝(미포함)
 *   --base-quantum=1             DWRR 기본 quantum
 *   --warmup=1 --runs=3          arm당 warm-up 반복 수와 측정 반복 수
 * </pre>
 *
 * <p>S3의 기본 간격은 500 ms가 아니라 333 ms다. 450 TPS에서 500 ms는 정확히 provider 슬롯
 * 225개이므로, 모든 OTP가 정확히 슬롯 경계에 도착하게 되어 측정된 큐 지연 시간 분포가 위상이
 * 고정된 단일 값으로 붕괴한다. 333 ms는 149.85 슬롯이므로 도착 위상이 슬롯 간격 전체에 고르게
 * 퍼진다.
 */
public final class SimulatorApplication {

  private static final Set<String> KNOWN_OPTIONS = Set.of(
      "suite", "scenario", "campaign", "tps", "otp-interval-ms", "otp-duration-s",
      "fairness-messages",
      "window-start-s", "window-end-s", "base-quantum", "warmup", "runs");

  private SimulatorApplication() {
  }

  public static void main(String[] args) {
    Map<String, String> options = parseOptions(args);
    String selected = options.getOrDefault("scenario", "all").toLowerCase(Locale.ROOT);
    int campaign = intOption(options, "campaign", 800_000);
    int tps = intOption(options, "tps", 450);
    int baseQuantum = intOption(options, "base-quantum", 1);
    ExperimentRunner runner =
        new ExperimentRunner(intOption(options, "warmup", 1), intOption(options, "runs", 3));
    List<ExperimentConfig> arms =
        List.of(ExperimentConfig.fifo(), ExperimentConfig.dwrr(baseQuantum));
    ComparisonReport.Focus campaignFocus = new ComparisonReport.Focus(
        ScenarioGenerator.OTP_TENANT, ScenarioGenerator.CAMPAIGN_TENANT);

    String suite = options.getOrDefault("suite", "all").toLowerCase(Locale.ROOT);
    if (!List.of("all", "history", "paper", "message", "sweep").contains(suite)) {
      throw new IllegalArgumentException("Unknown suite " + suite);
    }
    WdedfExperimentSuite v03 = new WdedfExperimentSuite(runner, System.out);
    if (suite.equals("all") || suite.equals("history")) {
      List<Scenario> history = new ArrayList<>();
      if (matches(selected, "s1")) {
        Scenario s1 = ScenarioGenerator.basicCorrectness();
        history.add(s1);
        System.out.print(ComparisonReport.render(s1, campaignFocus, runner.runAll(s1, arms)));
        printDispatchTrace(runner, s1, arms);
      }
      if (matches(selected, "s2")) {
        Scenario s2 = ScenarioGenerator.noisyNeighbor(campaign, tps);
        history.add(s2);
        System.out.print(ComparisonReport.render(s2, campaignFocus, runner.runAll(s2, arms)));
      }
      if (matches(selected, "s3")) {
        Scenario s3 = ScenarioGenerator.continuousOtp(
            campaign, tps,
            longOption(options, "otp-interval-ms", 333) * MICROS_PER_MILLISECOND,
            longOption(options, "otp-duration-s", 1_200) * MICROS_PER_SECOND);
        history.add(s3);
        System.out.print(ComparisonReport.render(s3, campaignFocus, runner.runAll(s3, arms)));
      }
      if (matches(selected, "s4")) {
        int perTenant = intOption(options, "fairness-messages", 200_000);
        ObservationWindow window = new ObservationWindow(
            longOption(options, "window-start-s", 30) * MICROS_PER_SECOND,
            longOption(options, "window-end-s", 330) * MICROS_PER_SECOND);
        List<Tenant> tenants =
            List.of(new Tenant("T1", 1), new Tenant("T2", 2), new Tenant("T4", 4));
        Scenario unitCost = ScenarioGenerator.weightedFairness(
            "S4a", tenants, Map.of(), perTenant, tps, window);
        history.add(unitCost);
        System.out.print(ComparisonReport.render(
            unitCost, ComparisonReport.Focus.NONE, runner.runAll(unitCost, arms)));
        Scenario variableCost = ScenarioGenerator.weightedFairness(
            "S4b", tenants, Map.of("T1", 2, "T2", 3, "T4", 1), perTenant, tps, window);
        history.add(variableCost);
        System.out.print(ComparisonReport.render(
            variableCost, ComparisonReport.Focus.NONE, runner.runAll(variableCost, arms)));
      }
      v03.history(history, baseQuantum);
    }
    if (suite.equals("all") || suite.equals("paper")) {
      v03.paper();
    }
    if (suite.equals("all") || suite.equals("message")) {
      v03.message(baseQuantum);
    }
    if (suite.equals("all") || suite.equals("sweep")) {
      v03.lambdaSweep();
    }
  }

  /** S1 디스패치 순서 추적: 각 정책에서 OTP가 디스패치 순서의 어디에 위치하는지 보여 준다. */
  private static void printDispatchTrace(
      ExperimentRunner runner, Scenario scenario, List<ExperimentConfig> arms) {
    System.out.println("\nS1 dispatch-order trace");
    for (ExperimentConfig arm : arms) {
      List<Message> dispatched = new ArrayList<>();
      List<Long> times = new ArrayList<>();
      ExperimentRunner.Run run = runner.runOnce(scenario, arm, new SimulationObserver() {
        @Override
        public void onDispatch(Message message, long now) {
          dispatched.add(message);
          times.add(now);
        }
      });
      int otpIndex = -1;
      for (int i = 0; i < dispatched.size(); i++) {
        if (dispatched.get(i).tenantId().equals(ScenarioGenerator.OTP_TENANT)) {
          otpIndex = i;
        }
      }
      StringBuilder window = new StringBuilder();
      int from = Math.max(0, otpIndex - 3);
      int to = Math.min(dispatched.size(), otpIndex + 4);
      for (int i = from; i < to; i++) {
        Message m = dispatched.get(i);
        window.append(String.format(Locale.ROOT, " #%d:%s%d@%.3fs", i + 1, m.tenantId(), m.id(),
            times.get(i) / (double) MICROS_PER_SECOND));
      }
      System.out.printf(Locale.ROOT, "  %s %-5s OTP dispatched at position %d of %d;%s%n",
          arm.experimentId(), run.result().policyName(), otpIndex + 1,
          dispatched.size(), window);
    }
  }

  private static boolean matches(String selected, String scenario) {
    return selected.equals("all") || selected.equals(scenario);
  }

  private static Map<String, String> parseOptions(String[] args) {
    Map<String, String> options = new HashMap<>();
    for (String arg : args) {
      if (!arg.startsWith("--") || !arg.contains("=")) {
        throw new IllegalArgumentException("Expected --key=value, got: " + arg);
      }
      String key = arg.substring(2, arg.indexOf('='));
      if (!KNOWN_OPTIONS.contains(key)) {
        throw new IllegalArgumentException("Unknown option --" + key);
      }
      options.put(key, arg.substring(arg.indexOf('=') + 1));
    }
    return options;
  }

  private static int intOption(Map<String, String> options, String key, int defaultValue) {
    return options.containsKey(key) ? Integer.parseInt(options.get(key)) : defaultValue;
  }

  private static long longOption(Map<String, String> options, String key, long defaultValue) {
    return options.containsKey(key) ? Long.parseLong(options.get(key)) : defaultValue;
  }
}
