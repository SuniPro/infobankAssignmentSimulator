package com.raphael.scenario;

import static com.raphael.core.VirtualClock.MICROS_PER_MILLISECOND;
import static com.raphael.core.VirtualClock.MICROS_PER_SECOND;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.domain.Tenant;
import com.raphael.metrics.ObservationWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v0.2 scenario를 만드는 결정론적 팩토리. 생성 과정에 무작위성을 쓰지 않는다. 메시지 id는
 * 리스트 순서대로 순차 부여되므로, 같은 파라미터는 항상 동일한 scenario를 만든다.
 */
public final class ScenarioGenerator {

  public static final String CAMPAIGN_TENANT = "A";
  public static final String OTP_TENANT = "B";

  /** 도착 시각 기준 광고 메시지의 deadline(v0.1 baseline 값). */
  public static final long ADVERTISEMENT_TTL = 3_600 * MICROS_PER_SECOND;
  /** 도착 시각 기준 OTP의 deadline(v0.1 baseline 값). */
  public static final long OTP_TTL = 60 * MICROS_PER_SECOND;
  /** 고정 외부 배달 지연(v0.1 baseline 값). */
  public static final long DEFAULT_DELIVERY_DELAY = 100 * MICROS_PER_MILLISECOND;
  /** 첫 OTP 도착 시각(v0.1 baseline 값). */
  public static final long OTP_START = MICROS_PER_SECOND;

  private ScenarioGenerator() {
  }

  /** S1: 10 TPS provider에서 광고 100건과 1s 시점의 OTP 1건. */
  public static Scenario basicCorrectness() {
    return campaignWithSingleOtp(
        "S1", "Basic scheduling correctness", 100, 10, OTP_START, OTP_TTL,
        DEFAULT_DELIVERY_DELAY);
  }

  /** S2: v0.1 noisy-neighbour baseline(광고 800,000건, OTP 1건, 450 TPS). */
  public static Scenario noisyNeighbor(int campaignMessages, int providerTps) {
    return campaignWithSingleOtp(
        "S2", "Noisy neighbour (v0.1 baseline)", campaignMessages, providerTps, OTP_START,
        OTP_TTL, DEFAULT_DELIVERY_DELAY);
  }

  /**
   * tenant A가 시각 0에 광고 {@code campaignMessages}건을 제출한 뒤, tenant B가 OTP 1건을
   * 제출한다. id, 순서, deadline은 v0.1 generator와 정확히 일치한다.
   */
  public static Scenario campaignWithSingleOtp(
      String name,
      String description,
      int campaignMessages,
      int providerTps,
      long otpArrivalTime,
      long otpTtl,
      long deliveryDelay) {
    requireNonNegative(campaignMessages, "campaignMessages");
    List<Message> messages = new ArrayList<>(campaignMessages + 1);
    long id = addCampaign(messages, 0, campaignMessages);
    messages.add(new Message(
        id, OTP_TENANT, MessageType.OTP, otpArrivalTime, otpArrivalTime + otpTtl, 1));
    return new Scenario(
        name, description, campaignTenants(), messages, providerTps, deliveryDelay,
        Optional.empty());
  }

  /**
   * S3: tenant A가 시각 0에 광고 {@code campaignMessages}건을 제출한다. tenant B는 1s부터
   * {@code otpInterval}마다 OTP 1건을 제출하며, 도착 시각이 {@code 1s + otpGenerationDuration}
   * 보다 엄격히 이전인 것까지만 생성한다.
   */
  public static Scenario continuousOtp(
      int campaignMessages, int providerTps, long otpInterval, long otpGenerationDuration) {
    requireNonNegative(campaignMessages, "campaignMessages");
    if (otpInterval <= 0 || otpGenerationDuration <= 0) {
      throw new IllegalArgumentException("OTP interval and duration must be positive");
    }
    List<Message> messages = new ArrayList<>();
    long id = addCampaign(messages, 0, campaignMessages);
    long generationEnd = OTP_START + otpGenerationDuration;
    for (long arrival = OTP_START; arrival < generationEnd; arrival += otpInterval) {
      messages.add(new Message(id++, OTP_TENANT, MessageType.OTP, arrival, arrival + OTP_TTL, 1));
    }
    return new Scenario(
        "S3",
        String.format("Continuous OTP traffic (one OTP every %.3f s for %.0f s)",
            otpInterval / (double) MICROS_PER_SECOND,
            otpGenerationDuration / (double) MICROS_PER_SECOND),
        campaignTenants(), messages, providerTps, DEFAULT_DELIVERY_DELAY, Optional.empty());
  }

  /**
   * S4: 모든 tenant가 시각 0에 광고 {@code messagesPerTenant}건을 제출하므로, 모든 tenant는 자기
   * 큐가 빌 때까지 backlog 상태를 유지한다. 메시지는 tenant 단위 블록으로 생성되며(첫 tenant의
   * 메시지 전부, 그다음 두 번째 tenant의 메시지 전부, ...), 이는 대량 캠페인 업로드를 모델링한다.
   * 따라서 FIFO는 tenant를 차례로 하나씩 처리한다.
   *
   * @param messageCosts tenant별 스케줄링 cost. 목록에 없는 tenant는 cost 1을 사용한다
   */
  public static Scenario weightedFairness(
      String name,
      List<Tenant> tenants,
      Map<String, Integer> messageCosts,
      int messagesPerTenant,
      int providerTps,
      ObservationWindow window) {
    requireNonNegative(messagesPerTenant, "messagesPerTenant");
    List<Message> messages = new ArrayList<>(tenants.size() * messagesPerTenant);
    long id = 0;
    StringBuilder weights = new StringBuilder();
    StringBuilder costs = new StringBuilder();
    for (Tenant tenant : tenants) {
      int cost = messageCosts.getOrDefault(tenant.id(), 1);
      for (int i = 0; i < messagesPerTenant; i++) {
        messages.add(new Message(
            id++, tenant.id(), MessageType.ADVERTISEMENT, 0, ADVERTISEMENT_TTL, cost));
      }
      weights.append(weights.isEmpty() ? "" : ":").append(tenant.weight());
      costs.append(costs.isEmpty() ? "" : ":").append(cost);
    }
    return new Scenario(
        name,
        "Weighted fairness (weights " + weights + ", costs " + costs + ")",
        tenants, messages, providerTps, DEFAULT_DELIVERY_DELAY, Optional.of(window));
  }

  private static List<Tenant> campaignTenants() {
    return List.of(new Tenant(CAMPAIGN_TENANT, 1), new Tenant(OTP_TENANT, 1));
  }

  private static long addCampaign(List<Message> messages, long firstId, int count) {
    long id = firstId;
    for (int i = 0; i < count; i++) {
      messages.add(new Message(
          id++, CAMPAIGN_TENANT, MessageType.ADVERTISEMENT, 0, ADVERTISEMENT_TTL, 1));
    }
    return id;
  }

  private static void requireNonNegative(int value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be non-negative: " + value);
    }
  }
}
