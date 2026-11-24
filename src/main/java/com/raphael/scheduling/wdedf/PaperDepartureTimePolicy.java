package com.raphael.scheduling.wdedf;

import com.raphael.core.VirtualClock;
import com.raphael.domain.Message;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 논문 식 (2): {@code T̂_D = T_A + L/R − L/C}.
 *
 * <p>단위: {@code L} 은 메시지 cost (자원 단위), {@code R} 과 {@code C} 는 초당 자원 단위, 시간은
 * 가상 μs. 예약률이 유리수 {@code R = p/q} 일 때
 * {@code L/R − L/C = L × (C·q − p) / (p·C)} 초이므로
 * {@code T̂_D = T_A + floor(L × 10^6 × (C·q − p) / (p·C))} μs 이다.
 *
 * <p>반올림은 내림(floor)이며 논문 Table 1 의 B1 값 (166.67 − 50 → 116) 과 일치한다. 큐별 계수
 * {@code a/b = 10^6 (C·q − p) / (p·C)} 는 생성 시 기약분수로 만들어 두고, 메시지별 곱셈이 long 을
 * 넘으면 같은 값을 {@link BigInteger} 로 계산한다. 결과는 항상 동일하고 결정론적이다.
 */
public final class PaperDepartureTimePolicy implements DepartureTimePolicy {

  private final Function<Message, String> queueKey;
  private final Map<String, long[]> coefficients = new HashMap<>();

  /**
   * @param reservations 큐별 예약률과 서버 용량
   * @param queueKey 메시지가 속한 큐(예약률)의 키
   */
  public PaperDepartureTimePolicy(
      ReservationModel reservations, Function<Message, String> queueKey) {
    this.queueKey = queueKey;
    BigInteger capacity = BigInteger.valueOf(reservations.capacity());
    BigInteger denominator = BigInteger.valueOf(reservations.denominator());
    BigInteger micros = BigInteger.valueOf(VirtualClock.MICROS_PER_SECOND);
    for (String key : reservations.keys()) {
      BigInteger p = BigInteger.valueOf(reservations.rateNumerator(key));
      BigInteger a = micros.multiply(capacity.multiply(denominator).subtract(p));
      BigInteger b = p.multiply(capacity);
      BigInteger gcd = a.gcd(b);
      if (gcd.signum() != 0) {
        a = a.divide(gcd);
        b = b.divide(gcd);
      }
      if (a.bitLength() > 62 || b.bitLength() > 62) {
        throw new ArithmeticException("Departure coefficient too large for queue " + key);
      }
      coefficients.put(key, new long[] {a.longValueExact(), b.longValueExact()});
    }
  }

  @Override
  public String name() {
    return "paper-eq2";
  }

  @Override
  public long departureTime(Message message) {
    long[] coefficient = coefficients.get(queueKey.apply(message));
    if (coefficient == null) {
      throw new IllegalArgumentException("No reservation for message " + message.id());
    }
    return Math.addExact(message.arrivalTime(), offset(message.cost(), coefficient));
  }

  /** {@code floor(cost × a / b)}; a ≥ 0, b > 0 이므로 내림은 정수 나눗셈과 같다. */
  static long offset(long cost, long[] coefficient) {
    long a = coefficient[0];
    long b = coefficient[1];
    if (a == 0) {
      return 0;
    }
    if (Math.multiplyHigh(cost, a) == 0 && cost * a >= 0) {
      return cost * a / b;
    }
    return BigInteger.valueOf(cost).multiply(BigInteger.valueOf(a))
        .divide(BigInteger.valueOf(b)).longValueExact();
  }
}
