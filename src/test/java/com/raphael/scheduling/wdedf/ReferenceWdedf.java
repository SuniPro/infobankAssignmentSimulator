package com.raphael.scheduling.wdedf;

import com.raphael.domain.Message;
import com.raphael.scheduling.SchedulingPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 테스트 전용 기준 WDEDF. 최적화를 모두 뺀 직역 구현으로 {@link WdedfScheduler} 와 차등 비교한다.
 * <ul>
 *   <li>Active List: 선형 탐색 (heap 없음)</li>
 *   <li>Blocked List: {@code ArrayList} + 정수 인덱스 PT (명세 해석 A13 의 {@code PT ≥ size → 0})</li>
 *   <li>PDC: 새 라운드마다 모든 큐를 즉시 재설정 (지연 stamp 없음)</li>
 *   <li>빈 라운드: 압축 없이 한 라운드씩 진행</li>
 * </ul>
 */
final class ReferenceWdedf implements SchedulingPolicy {

  private static final class Q {
    final String key;
    final int index;
    final long quantum;
    long deficit;
    long pdc;
    QueueState state = QueueState.INACTIVE;
    final ArrayDeque<Message> messages = new ArrayDeque<>();
    final ArrayDeque<Long> departures = new ArrayDeque<>();

    Q(String key, int index, long quantum) {
      this.key = key;
      this.index = index;
      this.quantum = quantum;
      this.pdc = quantum;
    }
  }

  private final Map<String, Long> quanta;
  private final DepartureTimePolicy policy;
  private final java.util.function.Function<Message, String> classifier;
  private final IdleDebtPolicy debtPolicy;
  private final long pqMax;
  private final Map<String, Q> queues = new LinkedHashMap<>();
  private final List<Q> active = new ArrayList<>();
  private final List<Q> blocked = new ArrayList<>();
  private final List<Q> waiting = new ArrayList<>();
  private int pt;
  private long pq;
  private long pending;

  ReferenceWdedf(Map<String, Long> quanta, DepartureTimePolicy policy,
      java.util.function.Function<Message, String> classifier, int lambdaBp,
      IdleDebtPolicy debtPolicy) {
    this.quanta = quanta;
    this.policy = policy;
    this.classifier = classifier;
    this.debtPolicy = debtPolicy;
    this.pqMax = quanta.values().stream().mapToLong(Long::longValue).sum() * lambdaBp / 10_000;
    this.pq = pqMax;
  }

  @Override
  public String name() {
    return "reference";
  }

  @Override
  public void enqueue(Message message) {
    String key = classifier.apply(message);
    Q q = queues.computeIfAbsent(key, k -> new Q(k, queues.size(), quanta.get(k)));
    q.messages.addLast(message);
    q.departures.addLast(policy.departureTime(message));
    pending++;
    if (q.state == QueueState.INACTIVE) {
      q.deficit += q.quantum;
      update(q);
    } else if (q.state == QueueState.WAITING) {
      update(q);
    }
  }

  @Override
  public Message selectNext(long now) {
    while (active.isEmpty()) {
      newRound();
    }
    Q top = active.get(0);
    for (Q q : active) {
      if (before(q, top)) {
        top = q;
      }
    }
    if (!blocked.isEmpty()) {
      Q candidate = blocked.get(pt);
      int cost = candidate.messages.peekFirst().cost();
      if (top.departures.peekFirst() > candidate.departures.peekFirst()
          && cost <= pq && cost <= candidate.pdc) {
        blocked.remove(pt);
        if (pt >= blocked.size()) {
          pt = 0;
        }
        pq -= cost;
        candidate.pdc -= cost;
        Message m = dequeue(candidate);
        update(candidate);
        return m;
      }
      pt++;
      if (pt >= blocked.size()) {
        pt = 0;
      }
    }
    active.remove(top);
    Message m = dequeue(top);
    update(top);
    return m;
  }

  private boolean before(Q a, Q b) {
    int c = Long.compare(a.departures.peekFirst(), b.departures.peekFirst());
    if (c == 0) {
      c = Long.compare(a.messages.peekFirst().arrivalTime(), b.messages.peekFirst().arrivalTime());
    }
    if (c == 0) {
      c = Long.compare(a.messages.peekFirst().id(), b.messages.peekFirst().id());
    }
    if (c == 0) {
      c = Integer.compare(a.index, b.index);
    }
    return c < 0;
  }

  private void newRound() {
    for (Q q : waiting) {
      if (q.state == QueueState.WAITING) {
        q.deficit = debtPolicy == IdleDebtPolicy.RETAIN_DEBT ? Math.min(0, q.deficit) : 0;
        q.state = QueueState.INACTIVE;
      }
    }
    waiting.clear();
    pq = pqMax;
    pt = 0;
    for (Q q : queues.values()) {
      q.pdc = q.quantum;
    }
    List<Q> stillBlocked = new ArrayList<>();
    for (Q q : blocked) {
      q.deficit += q.quantum;
      if (q.messages.peekFirst().cost() <= q.deficit) {
        q.state = QueueState.ACTIVE;
        active.add(q);
      } else {
        stillBlocked.add(q);
      }
    }
    blocked.clear();
    blocked.addAll(stillBlocked);
  }

  private Message dequeue(Q q) {
    q.departures.pollFirst();
    Message m = q.messages.pollFirst();
    q.deficit -= m.cost();
    pending--;
    return m;
  }

  private void update(Q q) {
    if (q.messages.isEmpty()) {
      q.state = QueueState.WAITING;
      if (!waiting.contains(q)) {
        waiting.add(q);
      }
    } else if (q.messages.peekFirst().cost() <= q.deficit) {
      q.state = QueueState.ACTIVE;
      active.add(q);
    } else {
      q.state = QueueState.BLOCKED;
      blocked.add(q);
    }
  }

  long deficitOf(String key) {
    Q q = queues.get(key);
    return q == null ? 0 : q.deficit;
  }

  @Override
  public boolean isEmpty() {
    return pending == 0;
  }

  @Override
  public long pendingCount() {
    return pending;
  }

  @Override
  public long operationCount() {
    return 0;
  }
}
