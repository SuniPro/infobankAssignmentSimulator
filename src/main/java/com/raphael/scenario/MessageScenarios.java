package com.raphael.scenario;

import static com.raphael.core.VirtualClock.MICROS_PER_MILLISECOND;
import static com.raphael.core.VirtualClock.MICROS_PER_SECOND;

import com.raphael.domain.MessageType;
import com.raphael.domain.Tenant;
import com.raphael.metrics.ObservationWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * v0.3 메시지 도메인 시나리오 (S5 ~ S12). 모두 기존 S1–S4 와 같은 MESSAGE_RATE provider (기본 450 TPS,
 * 배달 지연 100 ms) 를 쓴다. 각 메서드의 파라미터 유도 근거는 주석에 적었다. 기대값은 이론적 예측일
 * 뿐이며 테스트나 보고서는 실제 측정값만 사용한다.
 */
public final class MessageScenarios {

  public static final int TPS = 450;
  public static final long DELAY = ScenarioGenerator.DEFAULT_DELIVERY_DELAY;
  private static final long LONG_TTL = ScenarioGenerator.ADVERTISEMENT_TTL;

  private MessageScenarios() {
  }

  /**
   * S5 테넌트 내부 head-of-line blocking. 테넌트 A 와 B 가 각각 광고 {@code adsPerTenant} 건을 t=0 에
   * 제출하고, A 는 1s 부터 {@code otpInterval} 마다 {@code otpDurationSeconds} 동안 OTP (TTL 60s) 를
   * 보낸다. 테넌트 큐가 FIFO 이므로 A 의 OTP 는 A 의 광고 뒤에 선다. 동일 가중치에서 A 의 몫은 225/s
   * 이므로 광고 10만 건이 비는 데 약 444 s 가 걸려 TTL 60s 를 넘는다 (이론적 예측).
   */
  public static Scenario intraTenantHeadOfLine(int adsPerTenant, long otpInterval,
      int otpDurationSeconds) {
    MessageListBuilder b = new MessageListBuilder();
    for (int i = 0; i < adsPerTenant; i++) {
      b.add("A", MessageType.ADVERTISEMENT, 0, LONG_TTL, 1);
      b.add("B", MessageType.ADVERTISEMENT, 0, LONG_TTL, 1);
    }
    long end = MICROS_PER_SECOND + otpDurationSeconds * MICROS_PER_SECOND;
    for (long t = MICROS_PER_SECOND; t < end; t += otpInterval) {
      b.add("A", MessageType.OTP, t, t + ScenarioGenerator.OTP_TTL, 1);
    }
    return new Scenario("S5", "Intra-tenant HOL: tenant A's OTPs behind its own " + adsPerTenant
        + " ads", List.of(new Tenant("A", 1), new Tenant("B", 1)), b.build(), TPS, DELAY,
        Optional.empty());
  }

  /**
   * S6 대규모 활성 테넌트 집단 (파라미터 유도형). 광고 테넌트 {@code adTenants} 개가 t=0 에 합계 약
   * {@code totalAds} 건을 나눠 제출하고 (모두 backlog), OTP 전용 테넌트 50 개가 서로 엇갈려 OTP 를
   * 240 s 동안 보낸다. 모든 가중치는 1.
   *
   * <p>유도: 광고 테넌트가 모두 backlog 상태이면 DWRR 한 라운드는 약 {@code (adTenants + 활성 OTP
   * 테넌트) / 450} 초이다. OTP 테넌트 하나의 제출 간격을 라운드 길이의 3 배 이상 (최소 2 s) 으로 두어
   * 각 OTP 테넌트의 제출률이 가중치 몫보다 작게 한다 — 그래야 지연이 개별 테넌트 과부하가 아니라 집단
   * 크기(라운드 길이)의 효과만 반영한다. 100 / 1,000 / 10,000 테넌트에서 라운드는 약 0.33 / 2.3 /
   * 22.3 s 이다. 비어 있던 OTP 테넌트는 DWRR 에서 최대 한 라운드를 기다리므로, TTL 10 s 는 10,000
   * 테넌트에서만 이론상 실행 불가능하다 (이론적 예측).
   */
  public static Scenario tenantPopulation(int adTenants, int totalAds, long otpTtl) {
    int otpTenants = 50;
    long round = (long) (adTenants + otpTenants) * MICROS_PER_SECOND / TPS;
    long interval = Math.max(2 * MICROS_PER_SECOND, 3 * round);
    return population("S6-" + adTenants + "-ttl" + otpTtl / MICROS_PER_SECOND,
        String.format(java.util.Locale.ROOT,
            "%d backlogged ad tenants + %d OTP tenants (one OTP per %.1f s each, below fair"
                + " share), TTL %d s", adTenants, otpTenants, interval / 1e6,
            otpTtl / MICROS_PER_SECOND),
        adTenants, totalAds, otpTenants, interval, otpTtl);
  }

  /**
   * S6-OVL 개별 테넌트 과부하 변형 (처음 설계에서 발견된 경우를 명시적으로 남긴 것). OTP 테넌트 10 개가
   * 각각 2 s 마다 OTP 를 보낸다. 테넌트 수가 많으면 가중치 몫 {@code 450 / (adTenants + 10)} msg/s 가
   * 제출률 0.5 msg/s 보다 작아져 (1,000 테넌트: 0.446, 10,000 테넌트: 0.045) OTP 테넌트 큐가 끝없이
   * 자란다. 집단 크기 효과가 아니라 가중치 몫 초과 효과를 측정한다.
   */
  public static Scenario tenantPopulationOverload(int adTenants, int totalAds, long otpTtl) {
    return population("S6OVL-" + adTenants + "-ttl" + otpTtl / MICROS_PER_SECOND,
        String.format(java.util.Locale.ROOT,
            "PER-TENANT OVERLOAD: %d ad tenants + 10 OTP tenants at 0.5 msg/s each (fair share"
                + " %.3f msg/s), TTL %d s", adTenants, (double) TPS / (adTenants + 10),
            otpTtl / MICROS_PER_SECOND),
        adTenants, totalAds, 10, 2 * MICROS_PER_SECOND, otpTtl);
  }

  private static Scenario population(String name, String description, int adTenants,
      int totalAds, int otpTenants, long interval, long otpTtl) {
    MessageListBuilder b = new MessageListBuilder();
    List<Tenant> tenants = new ArrayList<>();
    int perTenant = Math.max(1, (totalAds + adTenants - 1) / adTenants);
    for (int i = 0; i < adTenants; i++) {
      String id = String.format("AD%05d", i);
      tenants.add(new Tenant(id, 1));
      for (int k = 0; k < perTenant; k++) {
        b.add(id, MessageType.ADVERTISEMENT, 0, LONG_TTL, 1);
      }
    }
    for (int j = 0; j < otpTenants; j++) {
      String id = String.format("OTP%02d", j);
      tenants.add(new Tenant(id, 1));
      for (long t = MICROS_PER_SECOND + j * interval / otpTenants;
          t < 241 * MICROS_PER_SECOND; t += interval) {
        b.add(id, MessageType.OTP, t, t + otpTtl, 1);
      }
    }
    return new Scenario(name, description, tenants, b.build(), TPS, DELAY, Optional.empty());
  }

  /**
   * S7 고가중치 비긴급 대 저가중치 긴급. H (가중치 8) 가 광고 20만 건을 t=0 에 제출하고, U (가중치 1)
   * 는 5s 부터 20s 마다 OTP {@code burst} 건을 한꺼번에 TTL {@code otpTtl} 로 보낸다 (15 회).
   *
   * <p>유도: 가중치 비례 몫은 U 가 450/9 = 50 msg/s 이므로 60 건 burst 는 약 1.2 s 가 걸린다. TTL 1 s
   * (배달 지연 100 ms 제외 시 0.9 s 예산) 이면 꼬리가 기한을 넘긴다. 선점 차입이 이 꼬리를 얼마나 줄이는지가
   * 관심사다 (이론적 예측).
   */
  public static Scenario weightVersusUrgency(int burst, long otpTtl) {
    MessageListBuilder b = new MessageListBuilder();
    for (int i = 0; i < 200_000; i++) {
      b.add("H", MessageType.ADVERTISEMENT, 0, LONG_TTL, 1);
    }
    for (int k = 0; k < 15; k++) {
      long t = 5 * MICROS_PER_SECOND + k * 20 * MICROS_PER_SECOND;
      for (int i = 0; i < burst; i++) {
        b.add("U", MessageType.OTP, t, t + otpTtl, 1);
      }
    }
    return new Scenario("S7", "High-weight ads (w=8) vs low-weight urgent bursts (w=1, " + burst
        + " OTPs, TTL " + otpTtl / MICROS_PER_MILLISECOND + " ms)",
        List.of(new Tenant("H", 8), new Tenant("U", 1)), b.build(), TPS, DELAY,
        Optional.of(new ObservationWindow(5 * MICROS_PER_SECOND, 300 * MICROS_PER_SECOND)));
  }

  /**
   * S8 이질적 deadline (seed 고정 무작위). 동일 가중치 테넌트 4 개가 각자 Poisson 으로
   * {@code load × 450 / 4} msg/s 를 120 s 동안 보낸다. 메시지 TTL 은 {2 s (OTP), 10 s, 60 s} 중 균등
   * 선택이다. 늦게 도착했지만 더 이른 deadline 을 가진 메시지가 같은 테넌트 큐 안에 섞인다.
   * {@code load} 0.9 / 1.0 / 1.1 은 각각 실행 가능 / 임계 / 실행 불가능 부하이다.
   */
  public static Scenario heterogeneousDeadlines(double load, long seed) {
    Random random = new Random(seed);
    MessageListBuilder b = new MessageListBuilder();
    List<Tenant> tenants = new ArrayList<>();
    double meanGap = MICROS_PER_SECOND / (load * TPS / 4.0);
    long[] ttls = {2 * MICROS_PER_SECOND, 10 * MICROS_PER_SECOND, 60 * MICROS_PER_SECOND};
    for (int i = 0; i < 4; i++) {
      String id = "T" + i;
      tenants.add(new Tenant(id, 1));
      for (long t = MessageListBuilder.exponentialGap(random, meanGap);
          t < 120 * MICROS_PER_SECOND; t += MessageListBuilder.exponentialGap(random, meanGap)) {
        int pick = random.nextInt(3);
        b.add(id, pick == 0 ? MessageType.OTP : MessageType.ADVERTISEMENT, t, t + ttls[pick], 1);
      }
    }
    return new Scenario(String.format(java.util.Locale.ROOT, "S8-load%.1f-seed%d", load, seed),
        String.format(java.util.Locale.ROOT,
            "Heterogeneous TTL {2,10,60}s mixed in tenant queues, load %.2f, seed %d", load, seed),
        tenants, b.build(), TPS, DELAY, Optional.empty());
  }

  /**
   * S9 같은 라운드 안에서 경쟁하는 여러 긴급 큐. 긴급 테넌트 {@code urgentTenants} 개가 10s 부터 30s
   * 마다 각각 OTP {@code burst} 건을 동시에 보내고 (5 회), 광고 테넌트 하나가 10만 건 backlog 를 가진다.
   * 모두 가중치 1. 유도: 20 × 30 = 600 건을 OTP 몫 20/21 × 450 ≈ 429 msg/s 로 보내면 약 1.4 s 가
   * 걸리므로, TTL 1.5 s (예산 1.4 s) 는 부분적으로만 실행 가능하다 (이론적 예측).
   */
  public static Scenario competingUrgentQueues(int urgentTenants, int burst, long otpTtl) {
    MessageListBuilder b = new MessageListBuilder();
    List<Tenant> tenants = new ArrayList<>();
    tenants.add(new Tenant("ADS", 1));
    for (int i = 0; i < 100_000; i++) {
      b.add("ADS", MessageType.ADVERTISEMENT, 0, LONG_TTL, 1);
    }
    for (int u = 0; u < urgentTenants; u++) {
      tenants.add(new Tenant(String.format("U%02d", u), 1));
    }
    for (int k = 0; k < 5; k++) {
      long t = 10 * MICROS_PER_SECOND + k * 30 * MICROS_PER_SECOND;
      for (int u = 0; u < urgentTenants; u++) {
        for (int i = 0; i < burst; i++) {
          b.add(String.format("U%02d", u), MessageType.OTP, t, t + otpTtl, 1);
        }
      }
    }
    return new Scenario("S9-ttl" + otpTtl / MICROS_PER_MILLISECOND,
        urgentTenants + " urgent tenants × " + burst
        + " simultaneous OTPs, TTL " + otpTtl / MICROS_PER_MILLISECOND + " ms, + ads backlog",
        tenants, b.build(), TPS, DELAY, Optional.empty());
  }

  /**
   * S10 WAITING 상태 credit 보존. B, C 가 광고 backlog 를 가지고, A 는 1s 부터 매 초 OTP 두 건을
   * {@code pairGap} 간격으로 보낸다 (TTL 60 s). 가중치는 모두 1. A 의 첫 OTP 가 나가 A 가 비면,
   * WDEDF 는 같은 라운드에서 두 번째 OTP 에 남은 DC 를 재사용하고, DWRR 은 deficit 을 0 으로 만든 뒤
   * 링 꼬리에서 다음 방문을 기다리게 한다. base quantum 을 키울수록(라운드가 길수록) 차이가 커진다.
   */
  public static Scenario waitingCredit(long pairGap) {
    MessageListBuilder b = new MessageListBuilder();
    for (int i = 0; i < 100_000; i++) {
      b.add("B", MessageType.ADVERTISEMENT, 0, LONG_TTL, 1);
      b.add("C", MessageType.ADVERTISEMENT, 0, LONG_TTL, 1);
    }
    for (int s = 1; s <= 200; s++) {
      long t = s * MICROS_PER_SECOND;
      b.add("A", MessageType.OTP, t, t + ScenarioGenerator.OTP_TTL, 1);
      b.add("A", MessageType.OTP, t + pairGap, t + pairGap + ScenarioGenerator.OTP_TTL, 1);
    }
    return new Scenario("S10", "Waiting-state credit: OTP pairs " + pairGap / MICROS_PER_MILLISECOND
        + " ms apart vs two ad backlogs", List.of(new Tenant("A", 1), new Tenant("B", 1),
        new Tenant("C", 1)), b.build(), TPS, DELAY, Optional.empty());
  }

  /**
   * S11 차입과 queue churn (명세 A17). H (가중치 8) 가 광고 backlog 를 가지고, X (가중치 1) 는 0.25 s
   * 마다 OTP {@code burst} 건 (TTL 2 s) 을 보낸다. X 의 제출률 (burst × 4/s) 은 가중치 몫 (50/s) 보다
   * 크지만, 매 라운드 비울 수 있을 만큼 작게 잡으면 논문의 유휴 재설정이 차입 부채를 탕감한다.
   * RETAIN_DEBT 진단 변형과 비교해 X 가 가중치 몫을 넘어 받는 서비스를 측정한다.
   */
  public static Scenario borrowingChurn(int burst) {
    MessageListBuilder b = new MessageListBuilder();
    for (int i = 0; i < 200_000; i++) {
      b.add("H", MessageType.ADVERTISEMENT, 0, LONG_TTL, 1);
    }
    for (long t = MICROS_PER_SECOND; t < 241 * MICROS_PER_SECOND;
        t += 250 * MICROS_PER_MILLISECOND) {
      for (int i = 0; i < burst; i++) {
        b.add("X", MessageType.OTP, t, t + 2 * MICROS_PER_SECOND, 1);
      }
    }
    return new Scenario("S11", "Borrowing churn: X sends " + burst + " OTPs every 250 ms (w=1)"
        + " vs H ads (w=8)", List.of(new Tenant("H", 8), new Tenant("X", 1)), b.build(), TPS,
        DELAY, Optional.of(new ObservationWindow(10 * MICROS_PER_SECOND,
            240 * MICROS_PER_SECOND)));
  }

  /**
   * S12 긴급 트래픽 과부하. OTP 테넌트 5 개가 합계 {@code load × 450} msg/s 의 Poisson OTP (TTL 5 s)
   * 를 120 s 동안 보내고, 광고 테넌트 하나가 backlog 를 가진다. 모두 가중치 1. 가중치 몫으로 OTP 는
   * 최대 5/6 × 450 = 375 msg/s (load ≈ 0.83) 까지 받을 수 있다. load 0.5 는 실행 가능, 0.9·1.0 은 가중치
   * 몫을 넘는 부분 실행 가능, 1.2 는 provider 용량 자체를 넘는 실행 불가능 부하이다.
   */
  public static Scenario urgentOverload(double load, long seed) {
    Random random = new Random(seed);
    MessageListBuilder b = new MessageListBuilder();
    List<Tenant> tenants = new ArrayList<>();
    tenants.add(new Tenant("ADS", 1));
    for (int i = 0; i < 150_000; i++) {
      b.add("ADS", MessageType.ADVERTISEMENT, 0, LONG_TTL, 1);
    }
    double meanGap = MICROS_PER_SECOND / (load * TPS / 5.0);
    for (int i = 0; i < 5; i++) {
      String id = "OTP" + i;
      tenants.add(new Tenant(id, 1));
      for (long t = MessageListBuilder.exponentialGap(random, meanGap);
          t < 120 * MICROS_PER_SECOND; t += MessageListBuilder.exponentialGap(random, meanGap)) {
        b.add(id, MessageType.OTP, t, t + 5 * MICROS_PER_SECOND, 1);
      }
    }
    return new Scenario(String.format(java.util.Locale.ROOT, "S12-load%.1f", load),
        String.format(java.util.Locale.ROOT,
            "Urgent overload: 5 OTP tenants at %.2f × capacity (TTL 5 s) + ads backlog", load),
        tenants, b.build(), TPS, DELAY,
        Optional.of(new ObservationWindow(10 * MICROS_PER_SECOND, 120 * MICROS_PER_SECOND)));
  }

  /**
   * F1 샤드 장애 워크로드 (v0.4). T00 (aggressor) 이 광고 {@code aggressorAds} 건을 t=0 에 넣고,
   * T01–T15 는 각각 광고 {@code adsPerTenant} 건 (t=0) 과 1 s 부터 2 s 마다 OTP (TTL 10 s) 를 60 s
   * 동안 보낸다. 모든 가중치 1. 장애 자체는 실험 arm 의 샤드 설정으로 주입한다 (같은 워크로드로
   * 장애 유무를 비교하기 위함).
   */
  public static Scenario shardFailureWorkload(int aggressorAds, int adsPerTenant) {
    MessageListBuilder b = new MessageListBuilder();
    List<Tenant> tenants = new ArrayList<>();
    for (int t = 0; t < 16; t++) {
      String id = String.format("T%02d", t);
      tenants.add(new Tenant(id, 1));
      int ads = t == 0 ? aggressorAds : adsPerTenant;
      for (int i = 0; i < ads; i++) {
        b.add(id, MessageType.ADVERTISEMENT, 0, LONG_TTL, 1);
      }
      if (t > 0) {
        for (long at = MICROS_PER_SECOND + t * 100 * MICROS_PER_MILLISECOND;
            at < 61 * MICROS_PER_SECOND; at += 2 * MICROS_PER_SECOND) {
          b.add(id, MessageType.OTP, at, at + 10 * MICROS_PER_SECOND, 1);
        }
      }
    }
    return new Scenario("F1", "Aggressor T00 (" + aggressorAds + " ads) + 15 tenants with ads"
        + " and OTPs (TTL 10 s)", tenants, b.build(), TPS, DELAY, Optional.empty());
  }

  /**
   * S13 장기 대기 (Aging, v0.4). L 은 업무 deadline 24 h 인 광고 {@code oldAds} 건을 t=0 에 넣고,
   * S 는 deadline 300 s 인 광고를 초당 {@code streamRate} 건씩 {@code streamSeconds} 동안 계속 보낸다.
   * 같은 서비스 클래스, 같은 가중치. 업무 목표 순서에서는 S 의 새 메시지가 라운드마다 L 보다 먼저
   * 나가므로 L 의 메시지가 오래 기다린다.
   */
  public static Scenario prolongedWaiting(int oldAds, int streamRate, int streamSeconds) {
    MessageListBuilder b = new MessageListBuilder();
    for (int i = 0; i < oldAds; i++) {
      b.add("L", MessageType.ADVERTISEMENT, 0, 86_400 * MICROS_PER_SECOND, 1);
    }
    long gap = MICROS_PER_SECOND / streamRate;
    for (long at = 0; at < streamSeconds * MICROS_PER_SECOND; at += gap) {
      b.add("S", MessageType.ADVERTISEMENT, at, at + 300 * MICROS_PER_SECOND, 1);
    }
    return new Scenario("S13", "Prolonged waiting: L (24 h deadline, " + oldAds + " ads) vs"
        + " S stream (" + streamRate + "/s, 300 s deadline)",
        List.of(new Tenant("L", 1), new Tenant("S", 1)), b.build(), TPS, DELAY, Optional.empty());
  }
}
