package com.raphael.scheduling.budget;

import com.raphael.domain.Message;
import com.raphael.scheduling.DispatchGate;
import java.util.HashMap;
import java.util.Map;

/**
 * 테넌트 전역 서비스 예산 (하드 리밋). 테넌트마다 토큰 버킷 하나를 두고, 그 테넌트의 모든 큐·클래스·
 * 샤드가 같은 객체를 공유한다. 단위는 메시지 cost 이다.
 */
public final class TenantBudget implements DispatchGate {

  private final Map<String, TokenBucket> buckets = new HashMap<>();
  private final long rate;
  private final long burst;
  private long consumptions;
  private long violations;

  /**
   * @param rate 테넌트당 초당 cost 단위
   * @param burst 테넌트당 최대 누적 cost 단위
   */
  public TenantBudget(long rate, long burst) {
    if (rate <= 0 || burst <= 0) {
      throw new IllegalArgumentException("rate and burst must be positive");
    }
    this.rate = rate;
    this.burst = burst;
  }

  @Override
  public long reopenTime(Message message, long now) {
    return bucket(message.tenantId()).reopenTime(message.cost(), now);
  }

  @Override
  public void consume(Message message, long now) {
    consumptions++;
    if (!bucket(message.tenantId()).consume(message.cost(), now)) {
      violations++;
    }
  }

  public long rate() {
    return rate;
  }

  public long burst() {
    return burst;
  }

  /** 게이트를 통과하지 않은 소비 횟수 (정상이면 0). */
  public long violations() {
    return violations;
  }

  public long consumptions() {
    return consumptions;
  }

  private TokenBucket bucket(String tenant) {
    TokenBucket bucket = buckets.get(tenant);
    if (bucket == null) {
      bucket = new TokenBucket(rate, burst);
      buckets.put(tenant, bucket);
    }
    return bucket;
  }
}
