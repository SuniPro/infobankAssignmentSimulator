package com.raphael.report;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.domain.Tenant;
import com.raphael.experiment.ExperimentConfig;
import com.raphael.experiment.ExperimentRunner;
import com.raphael.scenario.Scenario;
import com.raphael.scenario.ScenarioGenerator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ComparisonReportTest {

  private final ExperimentRunner runner = new ExperimentRunner(0, 1);
  private final List<ExperimentConfig> arms =
      List.of(ExperimentConfig.fifo(), ExperimentConfig.dwrr(1));

  @Test
  void reportsMeasuredValuesAndRelativeChange() {
    // 광고 1,000 건 @ 10 TPS → FIFO 에서 OTP 는 약 100 초 대기하여 60 초 TTL 을 넘긴다.
    Scenario scenario = ScenarioGenerator.campaignWithSingleOtp(
        "R", "report", 1_000, 10, 1_000_000, 60_000_000, 100_000);
    String report = ComparisonReport.render(scenario,
        new ComparisonReport.Focus("B", "A"), runner.runAll(scenario, arms));
    assertTrue(report.contains("E0 FIFO"));
    assertTrue(report.contains("E1 DWRR"));
    // OTP 미스율: 100% → 0% (측정값에서 계산).
    assertTrue(report.lines().anyMatch(l -> l.contains("deadline miss rate")
        && l.contains("100.0000%") && l.contains("-100.0000 pp") && l.contains("-100.00%")),
        report);
    // 캠페인 미스율은 양쪽 모두 0 → 0 기준 대비 변화율은 0.00%.
    assertTrue(report.contains("0.00%"), report);
  }

  @Test
  void missingSamplesAndNonApplicableMetricsRenderAsNa() {
    // 테넌트 C 는 메시지가 없으므로 지연시간·미스율은 0 이 아니라 n/a 로 표시되어야 한다.
    Scenario scenario = new Scenario("X", "no traffic for C",
        List.of(new Tenant("A", 1), new Tenant("C", 1)),
        List.of(new Message(0, "A", MessageType.OTP, 0, 1_000_000, 1)), 10, 0, Optional.empty());
    String report = ComparisonReport.render(scenario, new ComparisonReport.Focus("C", null),
        runner.runAll(scenario, arms));
    assertTrue(report.lines().anyMatch(l -> l.contains("E2E p99") && l.contains("n/a")), report);
    assertTrue(report.lines().anyMatch(
        l -> l.contains("deadline miss rate") && l.contains("n/a")), report);
    // 관측 창이 없는 시나리오에는 공정성 섹션이 없다.
    assertTrue(!report.contains("Jain index"), report);
  }
}
