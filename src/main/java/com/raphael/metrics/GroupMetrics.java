package com.raphael.metrics;

import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * 메시지 그룹(테넌트, 메시지 유형, 또는 테넌트×유형) 하나의 품질 지표. 모든 시간은 가상 μs.
 *
 * <p>두 종류의 "기한" 을 구분한다.
 * <ul>
 *   <li><b>업무 deadline miss</b>: 배달 시각 &gt; {@code Message.deadline} (v0.2 와 같은 정의).</li>
 *   <li><b>논문 출발 위반</b>: dispatch 시각 &gt; 논문 식 (2) 의 예상 출발 시각 T̂_D. 논문
 *       §4.2.1 의 "T̂_D 이후에 서비스되면 deadline miss" 정의이며, 스케줄러가 어떤 출발 시각
 *       정책을 쓰든 시나리오의 예약 모델로 같은 방식으로 계산한다.</li>
 * </ul>
 *
 * @param slackAtDispatch {@code deadline − deliveryDelay − dispatchTime}: dispatch 시점에 업무
 *     deadline 까지 남은 여유. 음수이면 dispatch 순간 이미 deadline 준수가 불가능했다
 * @param lateness 업무 deadline 을 넘긴 메시지들의 {@code delivery − deadline} 분포
 */
public record GroupMetrics(
    String key,
    long received,
    long dispatched,
    long delivered,
    long deadlineMisses,
    long paperDepartureViolations,
    long dispatchedCost,
    LatencySummary queueLatency,
    long queueLatencySum,
    LatencySummary endToEndLatency,
    long endToEndLatencySum,
    DistributionSummary slackAtDispatch,
    LatencySummary lateness,
    OptionalLong lastDeliveryTime) {

  public long undelivered() {
    return received - delivered;
  }

  /** (늦은 배달 + 미배달) / 수신. 수신이 없으면 empty. */
  public OptionalDouble deadlineMissRate() {
    return received == 0
        ? OptionalDouble.empty()
        : OptionalDouble.of((deadlineMisses + undelivered()) / (double) received);
  }

  /** (T̂_D 위반 dispatch + 미dispatch) / 수신. 수신이 없으면 empty. */
  public OptionalDouble paperDepartureViolationRate() {
    return received == 0
        ? OptionalDouble.empty()
        : OptionalDouble.of(
            (paperDepartureViolations + received - dispatched) / (double) received);
  }

  /** 평균 대기 지연 (dispatch − 도착). dispatch 가 없으면 empty. */
  public OptionalDouble averageQueueLatency() {
    return dispatched == 0
        ? OptionalDouble.empty()
        : OptionalDouble.of(queueLatencySum / (double) dispatched);
  }

  public OptionalDouble averageEndToEndLatency() {
    return delivered == 0
        ? OptionalDouble.empty()
        : OptionalDouble.of(endToEndLatencySum / (double) delivered);
  }
}
