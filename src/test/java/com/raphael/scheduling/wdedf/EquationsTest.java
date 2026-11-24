package com.raphael.scheduling.wdedf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 식 (1), 식 (2), 업무 dispatch 목표 시각, 결정론적 반올림. */
class EquationsTest {

  private static Message msg(long id, String tenant, long arrival, long deadline, int cost) {
    return new Message(id, tenant, MessageType.OTP, arrival, deadline, cost);
  }

  private static Map<String, Long> rates(Object... pairs) {
    Map<String, Long> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
    }
    return map;
  }

  @Test
  void equationOneScalesMinimumQuantumByReservationRatio() {
    // Example 1: 2 Mbps / 3 Mbps, ω_MIN = 1000 bit → 1000 / 1500.
    var example1 = ReservationModel.explicitRates(rates("A", 2_000_000, "B", 3_000_000),
        10_000_000).equationOneQuanta(1_000);
    assertEquals(Map.of("A", 1_000L, "B", 1_500L), example1.quanta());
    assertTrue(example1.exact());

    // Example 2: 3/7/10 Mbps, ω_MIN = 1500 → 식(1) 은 1500/3500/5000 (논문 수치 3450/4950 과 다름, A6).
    var example2 = ReservationModel.explicitRates(
        rates("A", 3_000_000, "B", 7_000_000, "C", 10_000_000), 20_000_000)
        .equationOneQuanta(1_500);
    assertEquals(Map.of("A", 1_500L, "B", 3_500L, "C", 5_000L), example2.quanta());
  }

  @Test
  void weightProportionalQuantaMatchDwrrAndInexactRatiosAreFlagged() {
    var weights = new LinkedHashMap<String, Integer>();
    weights.put("T1", 1);
    weights.put("T2", 2);
    weights.put("T4", 4);
    var quanta = ReservationModel.proportionalToWeights(weights, 450).equationOneQuanta(1);
    assertEquals(Map.of("T1", 1L, "T2", 2L, "T4", 4L), quanta.quanta());
    assertTrue(quanta.exact());

    var uneven = new LinkedHashMap<String, Integer>();
    uneven.put("A", 2);
    uneven.put("B", 3);
    var floored = ReservationModel.proportionalToWeights(uneven, 450).equationOneQuanta(1);
    assertEquals(Map.of("A", 1L, "B", 1L), floored.quanta()); // 1.5 → 1
    assertFalse(floored.exact());
  }

  @Test
  void reservationValidationRejectsNonPositiveAndAboveCapacityRates() {
    assertThrows(IllegalArgumentException.class,
        () -> ReservationModel.explicitRates(rates("A", 0), 10));
    assertThrows(IllegalArgumentException.class,
        () -> ReservationModel.explicitRates(rates("A", 11), 10));
    assertThrows(IllegalArgumentException.class,
        () -> ReservationModel.proportionalToWeights(Map.of("A", 0), 10));
    var model = ReservationModel.explicitRates(rates("A", 1, "B", Long.MAX_VALUE / 2),
        Long.MAX_VALUE / 2);
    assertThrows(ArithmeticException.class, () -> model.equationOneQuanta(Long.MAX_VALUE / 4));
    assertThrows(IllegalArgumentException.class, () -> model.equationOneQuanta(0));
  }

  @Test
  void equationTwoReproducesExampleOneDepartureTimes() {
    var model = ReservationModel.explicitRates(rates("A", 2_000_000, "B", 3_000_000),
        10_000_000);
    var policy = new PaperDepartureTimePolicy(model, Message::tenantId);
    // Table 1 (t_A = 0): A1 = 400bit@0 → 160, B1 = 500bit@100 → 216 (=100+166.67−50, 내림), A2 → 320.
    assertEquals(160, policy.departureTime(msg(1, "A", 0, 0, 400)));
    assertEquals(216, policy.departureTime(msg(2, "B", 100, 100, 500)));
    assertEquals(320, policy.departureTime(msg(3, "A", 160, 160, 400)));
  }

  @Test
  void equationTwoWithWeightProportionalRatesUsesExactRationalArithmetic() {
    var model = ReservationModel.proportionalToWeights(Map.of("A", 1, "B", 1), 450);
    var policy = new PaperDepartureTimePolicy(model, Message::tenantId);
    // R = 225/s, C = 450/s, L = 1 → 1/225 − 1/450 = 1/450 s = 2222.22us → 2222.
    assertEquals(5_002_222, policy.departureTime(msg(1, "A", 5_000_000, 5_000_000, 1)));
    // R = C 이면 L/R − L/C = 0.
    var full = ReservationModel.explicitRates(rates("A", 450), 450);
    assertEquals(7, new PaperDepartureTimePolicy(full, Message::tenantId)
        .departureTime(msg(1, "A", 7, 7, 3)));
  }

  @Test
  void overflowFallbackGivesTheSameFlooredValueAsBigInteger() {
    long[] coefficient = {Long.MAX_VALUE / 3, 7};
    long cost = 10;                                    // cost × a 는 long 을 넘지만 결과는 들어간다
    long expected = BigInteger.valueOf(cost).multiply(BigInteger.valueOf(coefficient[0]))
        .divide(BigInteger.valueOf(7)).longValueExact();
    assertEquals(expected, PaperDepartureTimePolicy.offset(cost, coefficient));
    assertEquals(3 * 10 / 4, PaperDepartureTimePolicy.offset(3, new long[] {10, 4}));
  }

  @Test
  void businessTargetIsDeadlineMinusEstimatedDelayAndKeepsMessageDeadline() {
    var policy = new BusinessDepartureTimePolicy(100_000);
    Message otp = msg(1, "B", 1_000_000, 61_000_000, 1);
    assertEquals(60_900_000, policy.departureTime(otp));
    assertEquals(61_000_000, otp.deadline());
    // deadline 이 배달 지연보다 가까우면 목표 시각은 도착 이전이다 (실행 불가능한 메시지).
    assertEquals(-50_000, policy.departureTime(msg(2, "B", 0, 50_000, 1)));
    assertThrows(IllegalArgumentException.class, () -> new BusinessDepartureTimePolicy(-1));
  }
}
