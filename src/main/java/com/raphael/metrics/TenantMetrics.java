package com.raphael.metrics;

import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * 불변 테넌트별 결과다. 모든 시간은 가상 마이크로초다.
 *
 * @param queueLatency 디스패치 시각에서 도착 시각을 뺀 값, 디스패치된 메시지 대상
 * @param endToEndLatency 전달 시각에서 도착 시각을 뺀 값, 전달된 메시지 대상(마감 시한 이후에
 *     전달된 메시지 포함)
 * @param endToEndLatencySum 정확한 평균을 구하기 위한 end-to-end 지연 시간의 합
 * @param lastDeliveryTime 테넌트의 마지막 전달 시각("캠페인 완료 시각")
 */
public record TenantMetrics(
    String tenantId,
    int weight,
    long received,
    long dispatched,
    long delivered,
    long deadlineMisses,
    long dispatchedCost,
    LatencySummary queueLatency,
    LatencySummary endToEndLatency,
    long endToEndLatencySum,
    OptionalLong firstArrivalTime,
    OptionalLong lastDispatchTime,
    OptionalLong lastDeliveryTime) {

  /** 수신했지만 끝내 전달되지 않은 메시지 수. */
  public long undelivered() {
    return received - delivered;
  }

  /**
   * 수신한 메시지 중 마감 시한을 놓친 비율이다. 늦게 전달된 메시지와 전달되지 않은 메시지의 합을
   * 수신한 전체 메시지 수로 나눈다. 수신한 메시지가 없으면 empty다.
   */
  public OptionalDouble deadlineMissRate() {
    if (received == 0) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of((deadlineMisses + undelivered()) / (double) received);
  }

  /** 마이크로초 단위의 평균 end-to-end 지연 시간. 전달된 메시지가 없으면 empty다. */
  public OptionalDouble averageEndToEndLatency() {
    if (delivered == 0) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(endToEndLatencySum / (double) delivered);
  }

  /** 수신한 모든 메시지가 정확히 한 번 디스패치되고 한 번 전달된 경우에만 {@code true}. */
  public boolean isConserved() {
    return received == dispatched && dispatched == delivered;
  }
}
