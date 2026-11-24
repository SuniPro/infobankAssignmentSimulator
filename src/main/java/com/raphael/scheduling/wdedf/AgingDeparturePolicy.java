package com.raphael.scheduling.wdedf;

import com.raphael.domain.Message;

/**
 * 최소 Aging 규칙 (v0.4): {@code 유효 출발 시각 = min(기본 출발 시각, 도착 + threshold)}.
 *
 * <p>의미: 메시지가 {@code threshold} 이상 기다리면, 그 뒤로는 출발 시각이 "도착 + threshold" 인
 * 메시지처럼 정렬된다. 시간이 지날수록 이 키는 새로 도착하는 메시지들의 키보다 상대적으로 앞서므로
 * 오래 기다린 메시지의 긴급도가 올라간다. 키가 enqueue 시점에 고정되므로 heap 키를 바꿀 필요가 없고
 * heap 순서가 항상 올바르다. 업무 deadline ({@link Message#deadline()}) 은 바뀌지 않으므로 품질 지표는
 * 그대로 측정된다. 정렬만 바꾸며 예산·샤드·provider 한도를 우회하지 않는다. 실행 불가능한 부하에서
 * 기아 방지를 보장하지 않는다.
 */
public final class AgingDeparturePolicy implements DepartureTimePolicy {

  private final DepartureTimePolicy base;
  private final long threshold;

  public AgingDeparturePolicy(DepartureTimePolicy base, long thresholdMicros) {
    if (thresholdMicros <= 0) {
      throw new IllegalArgumentException("Aging threshold must be positive");
    }
    this.base = base;
    this.threshold = thresholdMicros;
  }

  @Override
  public String name() {
    return base.name() + "+aging";
  }

  @Override
  public long departureTime(Message message) {
    return Math.min(base.departureTime(message), message.arrivalTime() + threshold);
  }

  /**
   * Aging 승격: 이 메시지의 순서 키가 aging 으로 정해졌고 ({@code 도착 + threshold < 기본 출발}),
   * 실제로 {@code threshold} 보다 오래 기다린 뒤 dispatch 되었다.
   */
  public boolean isPromoted(Message message, long dispatchTime) {
    long aged = message.arrivalTime() + threshold;
    return aged < base.departureTime(message) && dispatchTime > aged;
  }

  public long threshold() {
    return threshold;
  }
}
