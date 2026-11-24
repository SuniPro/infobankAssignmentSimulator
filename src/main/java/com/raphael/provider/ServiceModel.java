package com.raphael.provider;

/**
 * provider 가 메시지 하나에 대해 링크를 점유하는 시간을 정하는 모델.
 *
 * <p>하나의 실험에서 비교하는 모든 정책은 같은 서비스 모델을 사용해야 한다.
 */
public enum ServiceModel {

  /**
   * v0.1/v0.2 의 의미: 메시지마다 {@code 1s / capacity} 슬롯 하나를 점유하며 scheduling cost 는
   * 점유 시간에 영향을 주지 않는다. 기존 모든 시나리오의 기본값이다.
   */
  MESSAGE_RATE,

  /**
   * 논문형 실험용: 메시지가 {@code cost / capacity} 초 동안 링크를 점유한다 (cost = 패킷 길이 bit,
   * capacity = 링크 bit/s). 패킷 길이와 전송 시간의 물리적 관계를 재현한다.
   */
  COST_RATE
}
