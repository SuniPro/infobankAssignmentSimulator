package com.raphael.experiment;

import com.raphael.domain.Message;
import com.raphael.scenario.Scenario;
import com.raphael.scheduling.QueueLayout;
import com.raphael.scheduling.wdedf.PaperDepartureTimePolicy;
import com.raphael.scheduling.wdedf.ReservationModel;
import java.util.function.ToLongFunction;

/** 시나리오와 큐 배치로부터 논문 예약 모델을 만든다. */
public final class ScenarioReservations {

  private ScenarioReservations() {
  }

  /**
   * 큐별 예약 모델. 시나리오에 명시적 예약률이 있으면 (TENANT 토폴로지에서만) 그대로 쓰고,
   * 없으면 큐 가중치에 비례한다 ({@code R_i = w_i / Σw × C}).
   */
  public static ReservationModel forLayout(Scenario scenario, QueueLayout layout) {
    if (!scenario.reservationRates().isEmpty()) {
      if (layout.topology() != QueueLayout.Topology.TENANT) {
        throw new IllegalArgumentException(
            "Explicit reservation rates are only defined for the TENANT topology");
      }
      return ReservationModel.explicitRates(
          ordered(scenario), scenario.providerCapacity());
    }
    return ReservationModel.proportionalToWeights(
        layout.queueWeights(), scenario.providerCapacity());
  }

  /**
   * 지표용 논문 T̂_D: 스케줄러와 무관하게 테넌트 단위 예약 모델로 계산한다. 그래서 FIFO·DWRR 에
   * 대해서도 같은 기준의 논문 출발 위반을 보고할 수 있다.
   */
  public static ToLongFunction<Message> paperDepartureForMetrics(Scenario scenario) {
    QueueLayout tenantLayout =
        QueueLayout.of(QueueLayout.Topology.TENANT, scenario.tenants(), scenario.messages());
    PaperDepartureTimePolicy policy = new PaperDepartureTimePolicy(
        forLayout(scenario, tenantLayout), Message::tenantId);
    return policy::departureTime;
  }

  private static java.util.Map<String, Long> ordered(Scenario scenario) {
    java.util.Map<String, Long> rates = new java.util.LinkedHashMap<>();
    scenario.tenants().forEach(t -> rates.put(t.id(), scenario.reservationRates().get(t.id())));
    return rates;
  }
}
