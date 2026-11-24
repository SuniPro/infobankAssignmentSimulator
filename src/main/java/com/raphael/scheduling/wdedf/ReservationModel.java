package com.raphael.scheduling.wdedf;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 큐별 예약률 {@code R_i} 와 서버 용량 {@code C} (논문 §2). 예약률은 공통 분모를 갖는 유리수
 * {@code R_i = numerator_i / denominator} (초당 자원 단위)로 정확히 표현한다.
 *
 * <p>예약률은 설정된 모든 큐에 대해 한 번 정해지며, 큐의 활성/비활성 여부와 무관하게 고정이다
 * (명세 해석 A1). 모든 예약률은 {@code 0 < R_i ≤ C} 를 만족해야 한다 (명세 해석 A22).
 */
public final class ReservationModel {

  private final long capacity;
  private final long denominator;
  private final Map<String, Long> numerators;

  private ReservationModel(long capacity, long denominator, Map<String, Long> numerators) {
    if (capacity <= 0 || denominator <= 0 || numerators.isEmpty()) {
      throw new IllegalArgumentException("Capacity, denominator and rates must be positive");
    }
    for (Map.Entry<String, Long> entry : numerators.entrySet()) {
      long numerator = entry.getValue();
      if (numerator <= 0 || numerator > Math.multiplyExact(capacity, denominator)) {
        throw new IllegalArgumentException(
            "Reservation rate of " + entry.getKey() + " must be in (0, capacity]");
      }
    }
    this.capacity = capacity;
    this.denominator = denominator;
    this.numerators = Collections.unmodifiableMap(new LinkedHashMap<>(numerators));
  }

  /**
   * 메시지 도메인 매핑 [적응]: {@code R_i = w_i / Σw × C}. 가중치는 설정된 모든 큐의 합으로
   * 정규화되므로 큐가 활성화되거나 비활성화되어도 바뀌지 않는다.
   */
  public static ReservationModel proportionalToWeights(
      Map<String, Integer> weights, long capacity) {
    long totalWeight = 0;
    Map<String, Long> numerators = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : weights.entrySet()) {
      if (entry.getValue() == null || entry.getValue() <= 0) {
        throw new IllegalArgumentException("Weight must be positive: " + entry.getKey());
      }
      totalWeight = Math.addExact(totalWeight, entry.getValue());
      numerators.put(entry.getKey(), Math.multiplyExact((long) entry.getValue(), capacity));
    }
    return new ReservationModel(capacity, totalWeight, numerators);
  }

  /** 명시적 예약률 (초당 자원 단위). 논문 예제 재현용이다. */
  public static ReservationModel explicitRates(Map<String, Long> rates, long capacity) {
    return new ReservationModel(capacity, 1, rates);
  }

  public long capacity() {
    return capacity;
  }

  public long denominator() {
    return denominator;
  }

  public long rateNumerator(String key) {
    Long numerator = numerators.get(key);
    if (numerator == null) {
      throw new IllegalArgumentException("No reservation for queue " + key);
    }
    return numerator;
  }

  public Set<String> keys() {
    return numerators.keySet();
  }

  /** 보고용 예약률 근사값 (초당 자원 단위). 계산에는 쓰지 않는다. */
  public double rate(String key) {
    return rateNumerator(key) / (double) denominator;
  }

  /**
   * 식 (1): {@code ω_i = R_i / R_MIN × ω_MIN}. 정수 deficit 회계를 위해 내림하며, 나머지가 있으면
   * {@link QuantumAssignment#exact()} 가 거짓이 된다. 결과가 1 미만이면 설정 오류다.
   *
   * @param minimumQuantum {@code ω_MIN} (자원 단위, 양수)
   */
  public QuantumAssignment equationOneQuanta(long minimumQuantum) {
    if (minimumQuantum <= 0) {
      throw new IllegalArgumentException("Minimum quantum must be positive: " + minimumQuantum);
    }
    long minimumNumerator = numerators.values().stream().mapToLong(Long::longValue).min()
        .orElseThrow();
    Map<String, Long> quanta = new LinkedHashMap<>();
    boolean exact = true;
    for (Map.Entry<String, Long> entry : numerators.entrySet()) {
      BigInteger product = BigInteger.valueOf(entry.getValue())
          .multiply(BigInteger.valueOf(minimumQuantum));
      BigInteger[] division = product.divideAndRemainder(BigInteger.valueOf(minimumNumerator));
      if (division[0].bitLength() > 62) {
        throw new ArithmeticException("Quantum overflow for queue " + entry.getKey());
      }
      exact &= division[1].signum() == 0;
      quanta.put(entry.getKey(), division[0].longValueExact());
    }
    return new QuantumAssignment(Collections.unmodifiableMap(quanta), exact);
  }

  /**
   * 식 (1) 의 결과.
   *
   * @param quanta 큐 키 → ω_i (설정 순서 보존)
   * @param exact 모든 ω_i 가 내림 없이 정확한 정수였는지
   */
  public record QuantumAssignment(Map<String, Long> quanta, boolean exact) {
  }
}
