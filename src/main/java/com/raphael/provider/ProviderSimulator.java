package com.raphael.provider;

import com.raphael.core.VirtualClock;

/**
 * 고정 용량과 고정 배달 지연을 갖는 외부 배달 provider.
 *
 * <p>{@link ServiceModel#MESSAGE_RATE} 에서는 각 발송이 provider 를 {@code 1s / capacity} 동안
 * 점유한다 (v0.1 과 동일). {@link ServiceModel#COST_RATE} 에서는 {@code cost / capacity} 초 동안
 * 점유한다. 두 경우 모두 마이크로초 미만의 나머지는 {@code remainder} 에 이월되므로 반올림 오차가
 * 누적되지 않는다. {@code MESSAGE_RATE} 는 cost 를 1 로 고정한 {@code COST_RATE} 와 같은 식이다.
 */
public final class ProviderSimulator {

  private final ServiceModel serviceModel;
  private final int capacity;
  private final long deliveryDelay;

  private long nextAvailableTime;
  private long remainder;

  /** v0.2 호환 생성자: {@link ServiceModel#MESSAGE_RATE}, {@code tps} 메시지/초. */
  public ProviderSimulator(int tps, long deliveryDelay) {
    this(ServiceModel.MESSAGE_RATE, tps, deliveryDelay);
  }

  /**
   * @param serviceModel 점유 시간 모델
   * @param capacity 초당 처리 단위 (MESSAGE_RATE: 메시지, COST_RATE: cost 단위). MESSAGE_RATE 는
   *     모든 슬롯이 최소 1us 가 되도록 {@code [1, 1_000_000]} 범위여야 한다
   * @param deliveryDelay 마이크로초 단위의 고정 외부 지연. 음수가 아니어야 한다
   */
  public ProviderSimulator(ServiceModel serviceModel, int capacity, long deliveryDelay) {
    if (serviceModel == null) {
      throw new IllegalArgumentException("serviceModel is required");
    }
    if (capacity <= 0
        || (serviceModel == ServiceModel.MESSAGE_RATE
            && capacity > VirtualClock.MICROS_PER_SECOND)) {
      throw new IllegalArgumentException("Invalid capacity for " + serviceModel + ": " + capacity);
    }
    if (deliveryDelay < 0) {
      throw new IllegalArgumentException("deliveryDelay must be non-negative: " + deliveryDelay);
    }
    this.serviceModel = serviceModel;
    this.capacity = capacity;
    this.deliveryDelay = deliveryDelay;
  }

  /** 다음 발송이 허용되는 가장 이른 가상 시각. */
  public long nextAvailableTime() {
    return nextAvailableTime;
  }

  /** v0.2 호환: 단위 cost 메시지 하나를 발송한다. */
  public long dispatch(long now) {
    return dispatch(now, 1);
  }

  /**
   * {@code now} 에 메시지 하나를 provider 에 넘긴다.
   *
   * @param cost 메시지의 scheduling cost. MESSAGE_RATE 에서는 무시된다
   * @return 메시지가 배달되는 가상 시각. MESSAGE_RATE: {@code now + deliveryDelay}.
   *     COST_RATE: {@code now + 전송 시간 + deliveryDelay}
   * @throws IllegalStateException {@link #nextAvailableTime()} 이전에 호출되었거나, COST_RATE 에서
   *     점유 시간이 1us 미만이어서 같은 시각 연속 발송이 생길 수 있는 경우
   */
  public long dispatch(long now, int cost) {
    if (now < nextAvailableTime) {
      throw new IllegalStateException("Provider rate limit exceeded");
    }
    long units = serviceModel == ServiceModel.MESSAGE_RATE ? 1 : cost;
    // 마이크로초 미만의 나머지를 보존하여 누적 반올림 오차를 막는다.
    long numerator = Math.addExact(
        Math.multiplyExact(units, VirtualClock.MICROS_PER_SECOND), remainder);
    long occupancy = numerator / capacity;
    if (occupancy == 0) {
      throw new IllegalStateException(
          "Service time below 1us (cost=" + cost + ", capacity=" + capacity + ")");
    }
    nextAvailableTime = now + occupancy;
    remainder = numerator % capacity;
    // COST_RATE: 전송이 끝나야 배달된다 (논문 Fig.1 의 T_C = T_D + L/C). MESSAGE_RATE 는 v0.1 의미.
    return serviceModel == ServiceModel.COST_RATE
        ? now + occupancy + deliveryDelay
        : now + deliveryDelay;
  }

  public ServiceModel serviceModel() {
    return serviceModel;
  }

  public int capacity() {
    return capacity;
  }

  public long deliveryDelay() {
    return deliveryDelay;
  }
}
