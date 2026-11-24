package com.raphael.scheduling.wdedf;

import com.raphael.domain.Message;
import com.raphael.domain.MessageType;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** WDEDF 단위 테스트 도우미: 메시지별 출발 시각을 직접 지정하는 정책과 스케줄러 생성. */
final class WdedfTestSupport {

  /** 메시지 id → 출발 시각. 지정되지 않은 메시지는 도착 시각을 쓴다. */
  static final class FixedDepartures implements DepartureTimePolicy {
    final Map<Long, Long> byId = new HashMap<>();

    @Override
    public String name() {
      return "fixed";
    }

    @Override
    public long departureTime(Message message) {
      return byId.getOrDefault(message.id(), message.arrivalTime());
    }
  }

  private long nextId;
  final FixedDepartures departures = new FixedDepartures();

  Message msg(String queue, int cost, long departure) {
    Message m = new Message(nextId++, queue, MessageType.OTP, 0, 0, cost);
    departures.byId.put(m.id(), departure);
    return m;
  }

  static Map<String, Long> quanta(Object... pairs) {
    Map<String, Long> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
    }
    return map;
  }

  WdedfScheduler scheduler(Map<String, Long> quanta, int lambdaBp, IdleDebtPolicy debt) {
    return new WdedfScheduler(quanta, departures, Message::tenantId,
        new WdedfConfig("WDEDF-TEST", lambdaBp, debt));
  }

  WdedfScheduler scheduler(Map<String, Long> quanta, int lambdaBp) {
    return scheduler(quanta, lambdaBp, IdleDebtPolicy.PAPER_RESET);
  }
}
