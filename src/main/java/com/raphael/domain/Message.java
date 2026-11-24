package com.raphael.domain;

/**
 * tenant가 제출한 불변 메시지. 모든 시각은 가상 마이크로초 단위이다.
 *
 * @param id 고유 메시지 식별자
 * @param tenantId 소유 tenant
 * @param type 업무 분류
 * @param arrivalTime 메시지가 시스템에 들어오는 시각
 * @param deadline 성공으로 간주되는 가장 늦은 배달 시각(경계 포함)
 * @param cost tenant의 DWRR deficit에서 차감되는 양수 스케줄링 비용. cost는 스케줄링 추상화일
 *     뿐이다. provider는 cost와 무관하게 모든 메시지에 rate-limit 슬롯 하나를 소비시킨다.
 */
public record Message(
    long id, String tenantId, MessageType type, long arrivalTime, long deadline, int cost) {

  public Message {
    if (tenantId == null || type == null) {
      throw new IllegalArgumentException("tenantId and type are required");
    }
    if (arrivalTime < 0 || deadline < arrivalTime) {
      throw new IllegalArgumentException(
          "Invalid message times: arrival=" + arrivalTime + ", deadline=" + deadline);
    }
    if (cost <= 0) {
      throw new IllegalArgumentException("Message cost must be positive: " + cost);
    }
  }

  /** deadline과 정확히 같은 시각의 배달은 성공이며, 엄밀히 그보다 늦은 배달만 deadline 초과다. */
  public boolean missesDeadline(long deliveryTime) {
    return deliveryTime > deadline;
  }
}
