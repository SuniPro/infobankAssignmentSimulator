package com.raphael.experiment;

import com.raphael.scenario.Scenario;
import com.raphael.scheduling.DwrrScheduler;
import com.raphael.scheduling.FifoScheduler;
import com.raphael.scheduling.SchedulingPolicy;
import java.util.function.Function;

/**
 * 하나의 실험 arm이다. 식별자와, 실행마다 <em>새로운</em> 스케줄링 정책을 만드는 팩토리로
 * 구성된다. 매 실행마다 시나리오로부터 새 정책을 생성하므로, 실행 간이나 arm 간에 스케줄러
 * 상태가 새어 나갈 수 없다. 새 정책은 새 팩토리로 추가하며, 엔진은 변경하지 않는다.
 *
 * @param experimentId "E0" 같은 식별자
 * @param label 사람이 읽을 수 있는 arm 설명
 * @param policyFactory 시나리오에 맞게 설정된 비어 있는 새 정책을 생성한다
 */
public record ExperimentConfig(
    String experimentId, String label, Function<Scenario, SchedulingPolicy> policyFactory) {

  /** E0: 하나의 공유 FIFO 큐. */
  public static ExperimentConfig fifo() {
    return new ExperimentConfig("E0", "Shared FIFO", scenario -> new FifoScheduler());
  }

  /** E1: 테넌트별 FIFO 큐 + DWRR. 가중치는 시나리오의 테넌트에서 가져온다. */
  public static ExperimentConfig dwrr(int baseQuantum) {
    return new ExperimentConfig(
        "E1",
        "Tenant queues + DWRR (base quantum " + baseQuantum + ")",
        scenario -> new DwrrScheduler(scenario.tenantWeights(), baseQuantum));
  }
}
