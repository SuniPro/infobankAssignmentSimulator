package com.raphael.scheduling.wdedf;

import com.raphael.domain.Message;

/**
 * 메시지 도메인 적응 [적응]: {@code dispatchTargetTime = 업무 deadline − 예상 provider 배달 지연}.
 *
 * <p>업무 deadline 은 배달 기준이므로, 배달 지연만큼 앞당긴 시각까지 provider 에 넘겨야 기한을
 * 지킬 수 있다. 예상 지연은 설정값이며 미래에 실현될 지연을 사용하지 않는다. 현재 provider 는 고정
 * 지연이므로 설정된 배달 지연과 같다. {@code Message.deadline} 은 바뀌지 않는다.
 */
public final class BusinessDepartureTimePolicy implements DepartureTimePolicy {

  private final long estimatedDeliveryDelay;

  public BusinessDepartureTimePolicy(long estimatedDeliveryDelay) {
    if (estimatedDeliveryDelay < 0) {
      throw new IllegalArgumentException("Estimated delivery delay must be non-negative");
    }
    this.estimatedDeliveryDelay = estimatedDeliveryDelay;
  }

  @Override
  public String name() {
    return "business-deadline";
  }

  @Override
  public long departureTime(Message message) {
    return message.deadline() - estimatedDeliveryDelay;
  }
}
