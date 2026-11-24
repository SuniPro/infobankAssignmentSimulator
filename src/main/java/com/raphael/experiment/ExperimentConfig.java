package com.raphael.experiment;

import com.raphael.scenario.Scenario;
import com.raphael.scheduling.DwrrScheduler;
import com.raphael.scheduling.EdfScheduler;
import com.raphael.scheduling.FifoScheduler;
import com.raphael.scheduling.QueueLayout;
import com.raphael.scheduling.SchedulingPolicy;
import com.raphael.scheduling.wdedf.BusinessDepartureTimePolicy;
import com.raphael.scheduling.wdedf.DepartureTimePolicy;
import com.raphael.scheduling.wdedf.IdleDebtPolicy;
import com.raphael.scheduling.wdedf.PaperDepartureTimePolicy;
import com.raphael.scheduling.wdedf.ReservationModel;
import com.raphael.scheduling.wdedf.WdedfConfig;
import com.raphael.scheduling.wdedf.WdedfScheduler;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * 실험 arm 하나: 식별자와, 실행마다 <em>새로운</em> 스케줄링 정책을 만드는 factory. 실행마다
 * 시나리오로부터 새 정책을 만들므로 실행 간·arm 간에 스케줄러 상태가 새어 나갈 수 없다. 새 정책은
 * 새 factory 로 추가하며 엔진은 바뀌지 않는다.
 *
 * <p>식별자 체계: E 시리즈(E0, E1, E2)는 아키텍처 실험열, D 시리즈는 진단 변형이다.
 *
 * @param experimentId "E0" 와 같은 식별자
 * @param label 사람이 읽을 수 있는 arm 설명
 * @param policyFactory 시나리오에 맞게 설정된 새 빈 정책을 만든다
 */
public record ExperimentConfig(
    String experimentId,
    String label,
    Function<Scenario, SchedulingPolicy> policyFactory,
    Map<String, String> settings) {

  public ExperimentConfig {
    settings = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(settings));
  }

  /** 설정 기록 없이 만드는 v0.3 호환 생성자. */
  public ExperimentConfig(
      String experimentId, String label, Function<Scenario, SchedulingPolicy> policyFactory) {
    this(experimentId, label, policyFactory, Map.of());
  }

  /** 같은 정책에 다른 식별자·설정 기록을 붙인다. */
  public ExperimentConfig with(String id, String newLabel, Map<String, String> extraSettings) {
    Map<String, String> merged = new java.util.LinkedHashMap<>(settings);
    merged.putAll(extraSettings);
    return new ExperimentConfig(id, newLabel, policyFactory, merged);
  }

  /** WDEDF·EDF 의 정렬 기준. */
  public enum DepartureMode {
    /** 논문 식 (2). */
    PAPER,
    /** 업무 deadline − 예상 배달 지연 [적응]. */
    BUSINESS
  }

  /**
   * WDEDF arm 설정.
   *
   * @param mode 출발 시각 정책
   * @param lambdaBasisPoints Λ (기저점)
   * @param idleDebtPolicy 비활성화 시 부채 처리
   * @param topology 큐 토폴로지
   * @param baseQuantum DWRR 과 같은 기본 quantum. {@code minimumQuantum} 이 없으면
   *     {@code ω_MIN = baseQuantum × min 큐 가중치} 로 두어 가중치 비례 예약에서 식 (1) 이 DWRR 과
   *     같은 quantum ({@code baseQuantum × w_i}) 을 내게 한다
   * @param minimumQuantum 명시적 ω_MIN (명시적 예약률 시나리오용)
   */
  public record WdedfArm(
      DepartureMode mode,
      int lambdaBasisPoints,
      IdleDebtPolicy idleDebtPolicy,
      QueueLayout.Topology topology,
      int baseQuantum,
      OptionalLong minimumQuantum) {
  }

  /** E0: 단일 공유 FIFO 큐. */
  public static ExperimentConfig fifo() {
    return new ExperimentConfig("E0", "Shared FIFO", scenario -> new FifoScheduler(),
        Map.of("scheduler", "FIFO"));
  }

  /** E1: 테넌트별 FIFO 큐 + DWRR. 가중치는 시나리오의 테넌트에서 가져온다. */
  public static ExperimentConfig dwrr(int baseQuantum) {
    return new ExperimentConfig(
        "E1",
        "Tenant queues + DWRR (base quantum " + baseQuantum + ")",
        scenario -> new DwrrScheduler(scenario.tenantWeights(), baseQuantum),
        Map.of("scheduler", "DWRR", "baseQuantum", String.valueOf(baseQuantum),
            "topology", "TENANT"));
  }

  /** 토폴로지를 지정한 DWRR (진단용 TENANT_AND_TYPE 비교). */
  public static ExperimentConfig dwrr(
      String id, String label, int baseQuantum, QueueLayout.Topology topology) {
    return new ExperimentConfig(id, label, scenario -> {
      QueueLayout layout = QueueLayout.of(topology, scenario.tenants(), scenario.messages());
      return new DwrrScheduler(layout.queueWeights(), baseQuantum, layout.classifier(),
          topology == QueueLayout.Topology.TENANT ? "DWRR" : "DWRR-CLASS");
    });
  }

  /** WDEDF arm. 모든 WDEDF 변형은 같은 {@link WdedfScheduler} 구현을 공유한다. */
  public static ExperimentConfig wdedf(String id, String label, String name, WdedfArm arm) {
    return new ExperimentConfig(id, label, scenario -> {
      QueueLayout layout = QueueLayout.of(arm.topology(), scenario.tenants(), scenario.messages());
      ReservationModel reservations = ScenarioReservations.forLayout(scenario, layout);
      long minimumQuantum = arm.minimumQuantum().orElseGet(() -> (long) arm.baseQuantum()
          * layout.queueWeights().values().stream().mapToInt(Integer::intValue).min()
              .orElseThrow());
      return new WdedfScheduler(
          reservations.equationOneQuanta(minimumQuantum).quanta(),
          departurePolicy(arm.mode(), scenario, reservations, layout),
          layout.classifier(),
          new WdedfConfig(name, arm.lambdaBasisPoints(), arm.idleDebtPolicy()));
    }, Map.of("scheduler", "WDEDF", "departure", arm.mode().name(),
        "lambdaBasisPoints", String.valueOf(arm.lambdaBasisPoints()),
        "idleDebtPolicy", arm.idleDebtPolicy().name(), "topology", arm.topology().name(),
        "baseQuantum", String.valueOf(arm.baseQuantum()),
        "minimumQuantum", arm.minimumQuantum().isPresent()
            ? String.valueOf(arm.minimumQuantum().getAsLong()) : "derived"));
  }

  /** 진단용 전역 메시지 EDF. */
  public static ExperimentConfig edf(String id, String label, DepartureMode mode) {
    return new ExperimentConfig(id, label, scenario -> {
      QueueLayout layout = QueueLayout.of(
          QueueLayout.Topology.TENANT, scenario.tenants(), scenario.messages());
      return new EdfScheduler(mode == DepartureMode.PAPER ? "EDF-PAPER" : "EDF",
          departurePolicy(mode, scenario, ScenarioReservations.forLayout(scenario, layout),
              layout));
    });
  }

  private static DepartureTimePolicy departurePolicy(DepartureMode mode, Scenario scenario,
      ReservationModel reservations, QueueLayout layout) {
    return mode == DepartureMode.PAPER
        ? new PaperDepartureTimePolicy(reservations, layout.classifier())
        : new BusinessDepartureTimePolicy(scenario.deliveryDelay());
  }
}
