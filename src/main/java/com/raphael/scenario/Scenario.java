package com.raphael.scenario;

import com.raphael.domain.Message;
import com.raphael.domain.Tenant;
import com.raphael.metrics.ObservationWindow;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 불변이며 재현 가능한 실험 입력. 하나의 scenario로 평가되는 모든 정책은 완전히 동일한
 * 메시지(id, 도착 시각, deadline, cost), provider 용량, 배달 지연을 본다.
 *
 * @param name "S2"와 같은 짧은 식별자
 * @param description 사람이 읽을 수 있는 설명
 * @param tenants 보고 순서대로 나열된 tenant와 각 weight
 * @param messages id가 엄격히 증가하는 순서의 메시지 목록. 리스트 순서는 도착 이벤트가 예약되는
 *     순서이기도 하며, 도착 timestamp가 같을 때의 tie-breaker 역할을 한다.
 * @param providerTps provider 처리량 한도
 * @param deliveryDelay 마이크로초 단위의 고정 외부 배달 지연
 * @param observationWindow 서비스 할당량과 공정성 측정을 위한 관측 구간(있는 경우)
 */
public record Scenario(
    String name,
    String description,
    List<Tenant> tenants,
    List<Message> messages,
    int providerTps,
    long deliveryDelay,
    Optional<ObservationWindow> observationWindow) {

  public Scenario {
    tenants = List.copyOf(tenants);
    messages = List.copyOf(messages);
    if (tenants.isEmpty()) {
      throw new IllegalArgumentException("A scenario needs at least one tenant");
    }
    Set<String> tenantIds = new HashSet<>();
    for (Tenant tenant : tenants) {
      if (!tenantIds.add(tenant.id())) {
        throw new IllegalArgumentException("Duplicate tenant " + tenant.id());
      }
    }
    long previousId = Long.MIN_VALUE;
    for (Message message : messages) {
      if (message.id() <= previousId) {
        throw new IllegalArgumentException("Message ids must be strictly increasing");
      }
      if (!tenantIds.contains(message.tenantId())) {
        throw new IllegalArgumentException("Unknown tenant " + message.tenantId());
      }
      previousId = message.id();
    }
    if (providerTps <= 0 || providerTps > 1_000_000) {
      throw new IllegalArgumentException("providerTps must be in [1, 1_000_000]");
    }
    if (deliveryDelay < 0) {
      throw new IllegalArgumentException("deliveryDelay must be non-negative");
    }
    observationWindow = observationWindow == null ? Optional.empty() : observationWindow;
  }

  /** tenant 순서대로 나열된 tenant weight. */
  public Map<String, Integer> tenantWeights() {
    Map<String, Integer> weights = new LinkedHashMap<>();
    for (Tenant tenant : tenants) {
      weights.put(tenant.id(), tenant.weight());
    }
    return weights;
  }
}
