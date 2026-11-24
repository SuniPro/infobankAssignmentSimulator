package com.raphael.domain;

/**
 * provider를 공유하는 tenant.
 *
 * @param id 고유 tenant 식별자
 * @param weight 가중치 기반 정책이 사용하는 양수 상대 서비스 비율(FIFO는 무시한다)
 */
public record Tenant(String id, int weight) {

  public Tenant {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Tenant id must not be blank");
    }
    if (weight <= 0) {
      throw new IllegalArgumentException("Tenant weight must be positive: " + id + "=" + weight);
    }
  }
}
