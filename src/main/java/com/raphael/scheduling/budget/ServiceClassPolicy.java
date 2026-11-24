package com.raphael.scheduling.budget;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.scheduling.DispatchGate;
import com.raphael.scheduling.SchedulingPolicy;
import com.raphael.scheduling.wdedf.AgingDeparturePolicy;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 서비스 클래스 (OTP, 광고) 별 하위 스케줄러 위에서 클래스 예약·하드 리밋과 테넌트 전역 예산을 적용한다.
 *
 * <p>하위 스케줄러는 클래스마다 하나이며 (테넌트×유형 큐 = 클래스로 분할된 테넌트 큐), 같은 테넌트
 * 예산 게이트를 공유한다. 선택 절차 ({@link #selectNext}):
 * <ol>
 *   <li>후보 클래스 = 하위 스케줄러가 지금 선택 가능 ({@code nextEligibleTime == now}) 하고 클래스
 *       하드 리밋 버킷에 토큰이 있는 클래스. 확인만 하며 아무것도 소비하지 않는다.</li>
 *   <li>후보가 하나면 그 클래스 (예약분 빌려 쓰기 = 작업 보존). 둘이면 smooth weighted round robin:
 *       후보마다 credit += weight, credit 최대 클래스 선택 (동률은 enum 순서), 선택된 클래스 credit −=
 *       후보 weight 합. 후보가 아닌 클래스는 credit 이 쌓이지 않으므로 유휴 뒤 몰아 쓰기가 없다.</li>
 *   <li>선택된 클래스의 하위 스케줄러에서 메시지를 꺼낸 뒤 클래스 리밋 1 메시지, 테넌트 예산 cost 를
 *       소비한다.</li>
 * </ol>
 * 결과: 두 클래스가 모두 선택 가능하면 dispatch 슬롯이 weight 비율로 나뉜다 (예약). 한쪽이 비면 다른
 * 쪽이 전부 쓴다. 하드 리밋은 빌려 줄 수 없다.
 */
public final class ServiceClassPolicy implements SchedulingPolicy, TenantBudgetHolder {

  private final String name;
  private final EnumMap<MessageType, SchedulingPolicy> classes;
  private final EnumMap<MessageType, Integer> weights;
  private final EnumMap<MessageType, TokenBucket> classLimits;
  private final DispatchGate tenantGate;
  private final AgingDeparturePolicy aging;
  private final EnumMap<MessageType, Long> credit = new EnumMap<>(MessageType.class);
  private final EnumMap<MessageType, Long> dispatchedByClass = new EnumMap<>(MessageType.class);
  private long borrowedSlots;
  private long agingPromotions;
  private long classLimitDeferrals;

  /**
   * @param classes 클래스별 하위 스케줄러 (모든 {@link MessageType} 포함)
   * @param weights 클래스 예약 weight (양수)
   * @param classLimits 클래스 하드 리밋 버킷 (없는 클래스는 키 없음). 샤드 간 공유될 수 있다
   * @param tenantGate 테넌트 전역 예산 (하위 스케줄러와 같은 객체). 없으면 {@link DispatchGate#OPEN}
   * @param aging 승격 계수용 aging 정책, 없으면 {@code null}
   */
  public ServiceClassPolicy(String name, EnumMap<MessageType, SchedulingPolicy> classes,
      EnumMap<MessageType, Integer> weights, EnumMap<MessageType, TokenBucket> classLimits,
      DispatchGate tenantGate, AgingDeparturePolicy aging) {
    for (MessageType type : MessageType.values()) {
      if (!classes.containsKey(type) || weights.getOrDefault(type, 0) <= 0) {
        throw new IllegalArgumentException("Every class needs a policy and a positive weight");
      }
      credit.put(type, 0L);
      dispatchedByClass.put(type, 0L);
    }
    this.name = name;
    this.classes = classes;
    this.weights = weights;
    this.classLimits = classLimits;
    this.tenantGate = tenantGate;
    this.aging = aging;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public TenantBudget tenantBudget() {
    return tenantGate instanceof TenantBudget budget ? budget : null;
  }

  @Override
  public void enqueue(Message message) {
    classes.get(message.type()).enqueue(message);
  }

  @Override
  public long nextEligibleTime(long now) {
    long earliest = Long.MAX_VALUE;
    for (MessageType type : MessageType.values()) {
      SchedulingPolicy policy = classes.get(type);
      if (policy.isEmpty()) {
        continue;
      }
      long inner = policy.nextEligibleTime(now);
      TokenBucket limit = classLimits.get(type);
      long limitReopen = limit == null ? now : limit.reopenTime(1, now);
      long candidate = Math.max(inner, limitReopen);
      if (candidate == now) {
        return now;
      }
      earliest = Math.min(earliest, candidate);
    }
    return earliest;
  }

  @Override
  public Message selectNext(long now) {
    MessageType chosen = null;
    long eligibleWeight = 0;
    int eligibleCount = 0;
    for (MessageType type : MessageType.values()) {
      if (isEligible(type, now)) {
        eligibleCount++;
        eligibleWeight += weights.get(type);
        credit.put(type, credit.get(type) + weights.get(type));
        if (chosen == null || credit.get(type) > credit.get(chosen)) {
          chosen = type;
        }
      }
    }
    if (chosen == null) {
      throw new IllegalStateException("selectNext called while no service class is eligible");
    }
    credit.put(chosen, credit.get(chosen) - eligibleWeight);
    if (eligibleCount == 1 && hasOtherPending(chosen)) {
      borrowedSlots++;
    }
    Message message = classes.get(chosen).selectNext(now);
    TokenBucket limit = classLimits.get(chosen);
    if (limit != null) {
      limit.consume(1, now);
    }
    tenantGate.consume(message, now);
    dispatchedByClass.put(chosen, dispatchedByClass.get(chosen) + 1);
    if (aging != null && aging.isPromoted(message, now)) {
      agingPromotions++;
    }
    return message;
  }

  @Override
  public boolean isEmpty() {
    for (SchedulingPolicy policy : classes.values()) {
      if (!policy.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  @Override
  public long pendingCount() {
    long pending = 0;
    for (SchedulingPolicy policy : classes.values()) {
      pending += policy.pendingCount();
    }
    return pending;
  }

  @Override
  public long operationCount() {
    long operations = 0;
    for (SchedulingPolicy policy : classes.values()) {
      operations += policy.operationCount();
    }
    return operations;
  }

  @Override
  public Map<String, Long> diagnostics() {
    Map<String, Long> d = new LinkedHashMap<>();
    for (MessageType type : MessageType.values()) {
      d.put("class." + type + ".dispatched", dispatchedByClass.get(type));
    }
    d.put("class.borrowedSlots", borrowedSlots);
    d.put("class.limitDeferrals", classLimitDeferrals);
    d.put("agingPromotions", agingPromotions);
    for (SchedulingPolicy policy : classes.values()) {
      mergeDiagnostics(d, policy.diagnostics());
    }
    return d;
  }

  /** 하위 진단값 합치기: min* 는 최솟값, max* 는 최댓값, 나머지는 합. */
  public static void mergeDiagnostics(Map<String, Long> target, Map<String, Long> source) {
    source.forEach((key, value) -> target.merge(key, value,
        key.startsWith("min") ? Math::min : key.startsWith("max") ? Math::max : Long::sum));
  }

  private boolean isEligible(MessageType type, long now) {
    SchedulingPolicy policy = classes.get(type);
    if (policy.isEmpty() || policy.nextEligibleTime(now) != now) {
      return false;
    }
    TokenBucket limit = classLimits.get(type);
    if (limit != null && !limit.admits(1, now)) {
      classLimitDeferrals++;
      return false;
    }
    return true;
  }

  private boolean hasOtherPending(MessageType chosen) {
    for (MessageType type : MessageType.values()) {
      if (type != chosen && !classes.get(type).isEmpty()) {
        return true;
      }
    }
    return false;
  }
}
