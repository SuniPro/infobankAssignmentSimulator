package com.raphael.scenario;

import static com.raphael.core.VirtualClock.MICROS_PER_SECOND;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import com.raphael.domain.Tenant;
import com.raphael.metrics.ObservationWindow;
import com.raphael.provider.ServiceModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 논문 검증용 결정론적 시나리오 (P 시리즈). 모두 {@link ServiceModel#COST_RATE} 를 쓴다: cost 는
 * 패킷 길이(bit), 용량은 링크 bit/s, 예약률은 명시값(bit/s) 이다. 테넌트 가중치는 예약률을 최대공약수로
 * 나눈 값이므로 DWRR 의 {@code baseQuantum × w_i} 가 식 (1) 의 quantum 과 같아진다.
 *
 * <p>메시지의 업무 deadline 은 논문의 예상 완료 시각 {@code T_A + L/R} (내림) 로 둔다. 논문형 실험의
 * 주 지표는 dispatch 시각과 식 (2) T̂_D 를 비교하는 논문 출발 위반이다.
 *
 * <p>정확한 수치 재현의 한계 (명세 A21): 논문은 ns2 시뮬레이션 시간, seed, 헤더 크기, §4.2 링크 용량을
 * 밝히지 않는다. 여기의 해당 파라미터는 명시적 가정이며, 결과는 질적 목표와만 비교해야 한다.
 */
public final class PaperScenarios {

  private PaperScenarios() {
  }

  /**
   * P1 Example 1: 10 Mbps, A 2 Mbps, B 3 Mbps. A1(400bit, t_A), B1(500bit, t_A+100),
   * A2(400bit, t_A+160). 논문은 t_A+162 에 서비스가 시작된다고 가정하므로, 차단용 테넌트 C (10 Mbps
   * 예약, 1620bit = 162us 전송) 가 t_A 에 먼저 링크를 점유하게 한다. ω_MIN = 1000 bit.
   */
  public static Scenario exampleOne() {
    Map<String, Long> rates = rates("C", 10_000_000, "A", 2_000_000, "B", 3_000_000);
    MessageListBuilder b = new MessageListBuilder();
    add(b, rates, "C", 0, 1_620);
    add(b, rates, "A", 0, 400);
    add(b, rates, "B", 100, 500);
    add(b, rates, "A", 160, 400);
    return scenario("P1", "Paper Example 1 (10 Mbps, t_A+162 service start)", rates, b.build(),
        10_000_000, Optional.empty());
  }

  /**
   * P2 Example 2 의 원리: 20 Mbps, A 3 / B 7 / C 10 Mbps, ω_MIN = 1500 bit (식 (1) → 1500/3500/5000).
   * A1(600bit) 이 t=0 에 먼저 나가 A 가 비고, B(1500bit)·C(800bit) backlog 가 t=1us 에 도착하며,
   * A2(600bit) 가 t=200us 에 3 Mbps 에 맞게 도착한다. 논문의 ms 표기는 μs 로 해석한다 (A7).
   */
  public static Scenario exampleTwo() {
    Map<String, Long> rates = rates("A", 3_000_000, "B", 7_000_000, "C", 10_000_000);
    MessageListBuilder b = new MessageListBuilder();
    add(b, rates, "A", 0, 600);
    for (int i = 0; i < 40; i++) {
      add(b, rates, "B", 1, 1_500);
      add(b, rates, "C", 1, 800);
    }
    add(b, rates, "A", 200, 600);
    return scenario("P2", "Paper Example 2 principle (waiting-state credit)", rates, b.build(),
        20_000_000, Optional.empty());
  }

  /**
   * P3a §4.1 공정 대역 공유와 오작동 흐름 보호: 20 흐름, 4 Mbps 링크, 각 200 kbps 예약, Poisson 도착,
   * 크기 64–500 byte 균등. 흐름 9 는 300 kbps 로 보낸다. ω_MIN = 4000 bit (MTU 500 byte).
   */
  public static Scenario fairSharing(long seed, long durationMicros) {
    Map<String, Long> rates = new LinkedHashMap<>();
    double[] sendRates = new double[20];
    for (int f = 1; f <= 20; f++) {
      rates.put(flow(f), 200_000L);
      sendRates[f - 1] = f == 9 ? 300_000 : 200_000;
    }
    return poissonFlows("P3a", "§4.1 fair sharing, flow 9 misbehaves (300 kbps), 4 Mbps",
        rates, sendRates, 4_000_000, seed, durationMicros);
  }

  /**
   * P3b §4.1 가중 대역 공유: 흐름 f 는 f kbps 예약 (합 210 kbps). 논문의 링크 용량이 모호하므로 (A21)
   * 링크를 예약 합인 210 kbps 로 가정하고, 모든 흐름이 200 kbps (흐름 9 는 300 kbps) 로 보내 계속
   * backlog 상태가 되게 한다. Fig. 4(b) 의 기대값은 흐름 f 가 f kbps 를 받는 것이다.
   */
  public static Scenario weightedSharing(long seed, long durationMicros) {
    Map<String, Long> rates = new LinkedHashMap<>();
    double[] sendRates = new double[20];
    for (int f = 1; f <= 20; f++) {
      rates.put(flow(f), f * 1_000L);
      sendRates[f - 1] = f == 9 ? 300_000 : 200_000;
    }
    return poissonFlows("P3b", "§4.1 weighted sharing (f kbps reserved, 210 kbps link, assumed)",
        rates, sendRates, 210_000, seed, durationMicros);
  }

  /**
   * P4 §4.2 지연 차별화: 흐름 1–10 은 CBR 500 byte 를 1–10 Mbps 로, 흐름 11–20 은 Poisson
   * 64–500 byte 를 1–10 Mbps 로 보낸다. 예약률 = 송신률. 링크 용량은 논문에 없으므로 (A21) 112 Mbps
   * (부하 약 98.2%) 로 가정한다. CBR 흐름의 위상은 seed 로 정한 첫 주기 안의 무작위 오프셋이다.
   */
  public static Scenario delayDifferentiation(long seed, long durationMicros) {
    Map<String, Long> rates = new LinkedHashMap<>();
    for (int f = 1; f <= 20; f++) {
      rates.put(flow(f), (long) (f <= 10 ? f : f - 10) * 1_000_000L);
    }
    Random random = new Random(seed);
    MessageListBuilder b = new MessageListBuilder();
    for (int f = 1; f <= 10; f++) {
      long period = 4_000L * MICROS_PER_SECOND / rates.get(flow(f));
      for (long t = random.nextLong(period); t < durationMicros; t += period) {
        add(b, rates, flow(f), t, 4_000);
      }
    }
    for (int f = 11; f <= 20; f++) {
      double meanGap = 2_256.0 * MICROS_PER_SECOND / rates.get(flow(f));
      for (long t = MessageListBuilder.exponentialGap(random, meanGap); t < durationMicros;
          t += MessageListBuilder.exponentialGap(random, meanGap)) {
        add(b, rates, flow(f), t, 8 * (64 + random.nextInt(437)));
      }
    }
    return scenario("P4", "§4.2 delay differentiation, CBR 1–10 + VBR 11–20 (112 Mbps assumed)",
        rates, b.build(), 112_000_000, Optional.empty());
  }

  private static Scenario poissonFlows(String name, String description, Map<String, Long> rates,
      double[] sendRates, int capacity, long seed, long durationMicros) {
    Random random = new Random(seed);
    MessageListBuilder b = new MessageListBuilder();
    for (int f = 1; f <= sendRates.length; f++) {
      // 평균 패킷 크기 282 byte = 2256 bit.
      double meanGap = 2_256.0 * MICROS_PER_SECOND / sendRates[f - 1];
      for (long t = MessageListBuilder.exponentialGap(random, meanGap); t < durationMicros;
          t += MessageListBuilder.exponentialGap(random, meanGap)) {
        add(b, rates, flow(f), t, 8 * (64 + random.nextInt(437)));
      }
    }
    long warmup = Math.min(5 * MICROS_PER_SECOND, durationMicros / 4);
    return scenario(name, description, rates, b.build(), capacity,
        Optional.of(new ObservationWindow(warmup, durationMicros)));
  }

  private static void add(MessageListBuilder b, Map<String, Long> rates, String tenant,
      long arrival, int bits) {
    long expectedCompletion = arrival + bits * MICROS_PER_SECOND / rates.get(tenant);
    b.add(tenant, MessageType.ADVERTISEMENT, arrival, expectedCompletion, bits);
  }

  private static Scenario scenario(String name, String description, Map<String, Long> rates,
      List<Message> messages, int capacity, Optional<ObservationWindow> window) {
    long gcd = 0;
    for (long rate : rates.values()) {
      gcd = gcd(gcd, rate);
    }
    List<Tenant> tenants = new ArrayList<>();
    for (Map.Entry<String, Long> entry : rates.entrySet()) {
      tenants.add(new Tenant(entry.getKey(), Math.toIntExact(entry.getValue() / gcd)));
    }
    return new Scenario(name, description, tenants, messages, capacity, 0, window,
        ServiceModel.COST_RATE, rates);
  }

  /** 예약률의 최대공약수로 나눈 가중치에서 DWRR 이 식 (1) 과 같은 quantum 을 내는 기본 quantum. */
  public static int dwrrBaseQuantum(Scenario scenario, long minimumQuantum) {
    int minWeight = scenario.tenants().stream().mapToInt(Tenant::weight).min().orElseThrow();
    if (minimumQuantum % minWeight != 0) {
      throw new IllegalArgumentException("ω_MIN must be divisible by the minimum weight");
    }
    return Math.toIntExact(minimumQuantum / minWeight);
  }

  private static String flow(int f) {
    return String.format("F%02d", f);
  }

  private static Map<String, Long> rates(Object... pairs) {
    Map<String, Long> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
    }
    return map;
  }

  private static long gcd(long a, long b) {
    return b == 0 ? a : gcd(b, a % b);
  }
}
