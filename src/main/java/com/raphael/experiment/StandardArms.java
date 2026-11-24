package com.raphael.experiment;

import com.raphael.scheduling.QueueLayout.Topology;
import com.raphael.scheduling.wdedf.IdleDebtPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * v0.3 실험 arm 정의. E 시리즈(아키텍처 실험열)와 D 시리즈(진단 변형)를 구분한다.
 *
 * <p>Λ = 5% 는 논문이 정한 값이 아니라 이 실험의 선택값이다. 민감도는 {@link #lambdaSweep} 로
 * 따로 측정한다.
 */
public final class StandardArms {

  /** E2 와 D 시리즈의 기본 Λ (기저점). 실험 선택값. */
  public static final int DEFAULT_LAMBDA_BP = 500;

  private StandardArms() {
  }

  public static ExperimentConfig e2WdedfMessage(int baseQuantum) {
    return ExperimentConfig.wdedf("E2", "WDEDF, business dispatch target, Λ=5%", "WDEDF-MESSAGE",
        arm(ExperimentConfig.DepartureMode.BUSINESS, DEFAULT_LAMBDA_BP, IdleDebtPolicy.PAPER_RESET,
            Topology.TENANT, baseQuantum));
  }

  public static ExperimentConfig d1WdedfPaper(int baseQuantum) {
    return ExperimentConfig.wdedf("D1", "WDEDF, paper Eq.2 departure, Λ=5%", "WDEDF-PAPER",
        arm(ExperimentConfig.DepartureMode.PAPER, DEFAULT_LAMBDA_BP, IdleDebtPolicy.PAPER_RESET,
            Topology.TENANT, baseQuantum));
  }

  public static ExperimentConfig d2WdedfNoBorrowing(int baseQuantum) {
    return ExperimentConfig.wdedf("D2", "WDEDF, business target, Λ=0 (no borrowing)",
        "WDEDF-NB", arm(ExperimentConfig.DepartureMode.BUSINESS, 0, IdleDebtPolicy.PAPER_RESET,
            Topology.TENANT, baseQuantum));
  }

  public static ExperimentConfig d3Edf() {
    return ExperimentConfig.edf("D3", "Global message EDF (business target)",
        ExperimentConfig.DepartureMode.BUSINESS);
  }

  public static ExperimentConfig d4WdedfRetainDebt(int baseQuantum) {
    return ExperimentConfig.wdedf("D4", "WDEDF-MESSAGE with RETAIN_DEBT idle policy",
        "WDEDF-DEBT", arm(ExperimentConfig.DepartureMode.BUSINESS, DEFAULT_LAMBDA_BP,
            IdleDebtPolicy.RETAIN_DEBT, Topology.TENANT, baseQuantum));
  }

  public static ExperimentConfig d5DwrrClass(int baseQuantum) {
    return ExperimentConfig.dwrr("D5", "DWRR, tenant×type queues (partitioned weight)",
        baseQuantum, Topology.TENANT_AND_TYPE);
  }

  public static ExperimentConfig d6WdedfClass(int baseQuantum) {
    return ExperimentConfig.wdedf("D6", "WDEDF-MESSAGE, tenant×type queues (partitioned)",
        "WDEDF-CLASS", arm(ExperimentConfig.DepartureMode.BUSINESS, DEFAULT_LAMBDA_BP,
            IdleDebtPolicy.PAPER_RESET, Topology.TENANT_AND_TYPE, baseQuantum));
  }

  /** E0, E1, E2 + D1~D3 (일반 메시지 도메인 비교). */
  public static List<ExperimentConfig> messageDomain(int baseQuantum) {
    return List.of(ExperimentConfig.fifo(), ExperimentConfig.dwrr(baseQuantum),
        e2WdedfMessage(baseQuantum), d1WdedfPaper(baseQuantum), d2WdedfNoBorrowing(baseQuantum),
        d3Edf());
  }

  /** 같은 출발 정책에서 Λ 만 바꾼 WDEDF arm 들 (민감도 분석). */
  public static List<ExperimentConfig> lambdaSweep(int baseQuantum, int... lambdaBasisPoints) {
    List<ExperimentConfig> arms = new ArrayList<>();
    arms.add(ExperimentConfig.dwrr(baseQuantum));
    for (int bp : lambdaBasisPoints) {
      String pct = String.format(java.util.Locale.ROOT, "%.0f%%", bp / 100.0);
      arms.add(ExperimentConfig.wdedf("L" + bp, "WDEDF-MESSAGE Λ=" + pct, "WDEDF Λ=" + pct,
          arm(ExperimentConfig.DepartureMode.BUSINESS, bp, IdleDebtPolicy.PAPER_RESET,
              Topology.TENANT, baseQuantum)));
    }
    return arms;
  }

  static ExperimentConfig.WdedfArm arm(ExperimentConfig.DepartureMode mode, int lambdaBp,
      IdleDebtPolicy debt, Topology topology, int baseQuantum) {
    return new ExperimentConfig.WdedfArm(mode, lambdaBp, debt, topology, baseQuantum,
        OptionalLong.empty());
  }
}
