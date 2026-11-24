package com.raphael.scheduling.wdedf;

import com.raphael.domain.Message;
import com.raphael.scheduling.DispatchGate;
import com.raphael.scheduling.SchedulingPolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Function;

/**
 * Weighted Deficit Earliest Departure First (Lim, Lee, Yeo 2005, Algorithm 1·2).
 *
 * <p>상세한 의미와 해석은 {@code docs/wdedf-specification.md} 에 있다. 요약:
 * <ul>
 *   <li>큐마다 식 (1) 의 quantum ω_i 와 부호 있는 deficit counter DC_i 를 둔다.</li>
 *   <li>ACTIVE 큐들 중 head 의 예상 출발 시각이 가장 이른 큐의 head 를 보낸다 (큐 내부는 FIFO).
 *       후보는 큐 head 뿐이며 메시지 전체를 heap 에 넣지 않는다.</li>
 *   <li>DC 가 head 를 감당하지 못하면 BLOCKED, 비면 WAITING (DC 를 라운드 끝까지 보존).</li>
 *   <li>ACTIVE 가 비면 전역 서비스 라운드가 끝난다: WAITING → INACTIVE, BLOCKED 는 ω 를 보충받고
 *       적격이면 ACTIVE 가 된다. 보충 후에도 적격 큐가 없으면 동치성이 증명된 빈 라운드 압축을
 *       한다 (명세 §3.1).</li>
 *   <li>선점 quantum 차입 (Algorithm 2): token PT 가 가리키는 BLOCKED 큐 하나가 ACTIVE head 보다
 *       엄격히 이른 출발 시각을 갖고 cost ≤ PQ, cost ≤ PDC 이면 그 큐가 다음 provider 슬롯을
 *       받는다. 차입한 cost 는 PQ, PDC 와 일반 DC 모두에서 차감되므로 DC 는 음수(부채)가 될 수
 *       있고, 부채는 큐가 backlog 상태인 동안 다음 라운드로 이월된다.</li>
 * </ul>
 *
 * <h2>복잡도</h2>
 * A 를 ACTIVE 큐 수, B 를 BLOCKED 큐 수, W 를 WAITING 큐 수라 하자.
 * <ul>
 *   <li>{@code enqueue}: O(1), 큐가 ACTIVE 가 되면 heap 삽입 O(log A).</li>
 *   <li>{@code selectNext} (라운드 전이 없음): 차입 검사 O(1) + heap poll/offer O(log A).</li>
 *   <li>라운드 전이: O(W + B log A). 비용/quantum 비율과 무관하다 (빈 라운드 압축).</li>
 *   <li>공간: O(Q + M). Q 는 메시지를 받은 적 있는 큐 수, M 은 대기 메시지 수.</li>
 * </ul>
 * 따라서 모든 {@code selectNext} 가 O(log N) 인 것은 아니다. 라운드 전이가 일어나는 호출은
 * O(W + B log A) 이다.
 *
 * <p>{@link #operationCount()} 는 후보 검사, heap offer/poll, 실제 보충 단계, 상태 전이, token
 * 연산의 합이다 (압축된 빈 라운드의 논리적 보충은 큐당 한 단계로 센다). 알고리즘 단계 수이며 CPU 명령 수가 아니다.
 */
public final class WdedfScheduler implements SchedulingPolicy {

  /** heap 원소. 큐가 ACTIVE 인 동안 head 는 바뀌지 않으므로 키는 불변이다. */
  private record Candidate(
      long departure, long arrival, long messageId, int queueIndex, WdedfQueue queue) {
  }

  /** 결정론적 순서: 출발 시각 → 도착 시각 → 메시지 id → 큐 생성 순서. */
  private static final Comparator<Candidate> ORDER = (left, right) -> {
    int c = Long.compare(left.departure, right.departure);
    if (c != 0) {
      return c;
    }
    c = Long.compare(left.arrival, right.arrival);
    if (c != 0) {
      return c;
    }
    c = Long.compare(left.messageId, right.messageId);
    return c != 0 ? c : Integer.compare(left.queueIndex, right.queueIndex);
  };

  private final WdedfConfig config;
  private final DepartureTimePolicy departurePolicy;
  private final Function<Message, String> classifier;
  private final Map<String, Long> quanta;
  private final long preemptiveQuantum;

  private final Map<String, WdedfQueue> queues = new HashMap<>();
  private final PriorityQueue<Candidate> active = new PriorityQueue<>(ORDER);
  private final BlockedList blocked = new BlockedList();

  /** v0.4 예산 게이트. {@code null} 이면 v0.3 과 동일하게 동작한다. */
  private final DispatchGate gate;

  /** 게이트가 닫혀 heap 에서 잠시 빠진 ACTIVE 큐 (메시지는 꺼내지 않는다). 재개 시각 순. */
  private record Parked(long reopen, int queueIndex, WdedfQueue queue) {
  }

  private final PriorityQueue<Parked> parked = new PriorityQueue<>(
      (a, b) -> a.reopen != b.reopen ? Long.compare(a.reopen, b.reopen)
          : Integer.compare(a.queueIndex, b.queueIndex));
  private long budgetParks;
  private long rejectedBudget;
  private final List<WdedfQueue> waiting = new ArrayList<>();

  /** 현재 전역 서비스 라운드 번호 (1 부터). */
  private long round = 1;
  /** 현재 라운드에 남은 전역 선점 quantum PQ. */
  private long pq;
  private long pending;

  private final long[][] transitions =
      new long[QueueState.values().length][QueueState.values().length];
  private long rounds = 1;
  private long skippedRounds;
  /** 논리적 보충 횟수 (압축된 라운드 포함: 큐당 라운드당 1). */
  private long quantumReplenishments;
  /** 실제 수행한 보충 단계 수 (압축 여부와 무관하게 큐당 보충 호출당 1). */
  private long replenishmentSteps;
  private long activationGrants;
  private long waitingReactivations;
  private long retainedCreditReused;
  private long retainedCreditExpired;
  private long debtForgiven;
  private long borrowAttempts;
  private long borrowSuccesses;
  private long borrowedCost;
  private long rejectedNotMoreUrgent;
  private long rejectedPq;
  private long rejectedPdc;
  private long pqGranted;
  private long debtEvents;
  private long minDeficit;
  private long maxDeficit;
  private long heapOffers;
  private long heapPolls;
  private long candidateExaminations;
  private long maxActive;
  private long maxBlocked;

  /**
   * @param quanta 큐 키 → 식 (1) 의 ω_i (양수). 설정된 모든 큐를 포함해야 하며 Ω 는 이 값들의 합이다
   * @param departurePolicy 큐 head 정렬 기준 (논문 식 (2) 또는 업무 dispatch 목표)
   * @param classifier 메시지 → 큐 키
   * @param config 이름, Λ, 부채 정책
   */
  public WdedfScheduler(
      Map<String, Long> quanta,
      DepartureTimePolicy departurePolicy,
      Function<Message, String> classifier,
      WdedfConfig config) {
    this(quanta, departurePolicy, classifier, config, null);
  }

  /**
   * v0.4: {@code gate} 가 있으면 큐 head 가 게이트를 통과할 때만 선택·차입한다. 게이트 확인은 메시지를
   * 꺼내거나 deficit 을 소비하기 전에 한다. 막힌 ACTIVE 큐는 heap 에서 빠져 재개 시각까지 주차된다.
   */
  public WdedfScheduler(
      Map<String, Long> quanta,
      DepartureTimePolicy departurePolicy,
      Function<Message, String> classifier,
      WdedfConfig config,
      DispatchGate gate) {
    this.gate = gate;
    if (quanta.isEmpty()) {
      throw new IllegalArgumentException("At least one queue is required");
    }
    long omega = 0;
    for (Map.Entry<String, Long> entry : quanta.entrySet()) {
      if (entry.getValue() == null || entry.getValue() <= 0) {
        throw new IllegalArgumentException("Quantum must be positive: " + entry.getKey());
      }
      omega = Math.addExact(omega, entry.getValue());
    }
    this.quanta = new LinkedHashMap<>(quanta);
    this.departurePolicy = departurePolicy;
    this.classifier = classifier;
    this.config = config;
    // PQ = floor(Ω × Λ) (명세 §1.4).
    this.preemptiveQuantum = Math.multiplyExact(omega, (long) config.lambdaBasisPoints()) / 10_000;
    this.pq = preemptiveQuantum;
    this.pqGranted = preemptiveQuantum;
  }

  @Override
  public String name() {
    return config.name();
  }

  /** Algorithm 1 En-queue 모듈 (명세 해석 A9: 두 분기 모두 enqueue 후 상태 갱신). */
  @Override
  public void enqueue(Message message) {
    WdedfQueue queue = queueFor(classifier.apply(message));
    queue.push(message, departurePolicy.departureTime(message));
    pending++;
    if (queue.state == QueueState.INACTIVE) {
      // Fig. 2 Inactive → Active: DC = ω (PAPER_RESET 에서는 DC 가 0 이므로 DC + ω 와 같다).
      grantQuantum(queue, 1);
      activationGrants++;
      updateQueueStatus(queue);
    } else if (queue.state == QueueState.WAITING) {
      // 같은 라운드 안의 재도착: 보존된 DC 를 재사용한다 (§2.1).
      waitingReactivations++;
      retainedCreditReused += Math.max(0, queue.deficit);
      updateQueueStatus(queue);
    }
  }

  /** Algorithm 2 Get_Packet + Algorithm 1 De-queue. */
  @Override
  public Message selectNext(long now) {
    if (pending == 0) {
      throw new IllegalStateException("selectNext called on an empty WDEDF scheduler");
    }
    if (gate != null && nextEligibleTime(now) != now) {
      throw new IllegalStateException("selectNext called while no queue passes the gate");
    }
    if (active.isEmpty()) {
      startNewRound(now);
    }
    maxActive = Math.max(maxActive, active.size());
    maxBlocked = Math.max(maxBlocked, blocked.size());

    Candidate top = active.peek();
    candidateExaminations++;
    if (!blocked.isEmpty()) {
      Message borrowed = tryBorrow(top.departure(), now);
      if (borrowed != null) {
        return borrowed;
      }
    }
    active.poll();
    heapPolls++;
    Message message = dequeueFrom(top.queue());
    updateQueueStatus(top.queue());
    return message;
  }

  /**
   * 게이트가 없으면 대기 메시지가 있는 한 {@code now}. 게이트가 있으면 (1) 재개 시각이 지난 주차 큐를
   * heap 에 되돌리고, (2) heap top 이 막혀 있으면 주차시키며, (3) heap 이 비면 게이트를 통과하는
   * BLOCKED 큐가 있을 때만 라운드를 넘긴다. 아무도 통과하지 못하면 라운드를 넘기지 않고 가장 이른
   * 재개 시각을 돌려준다 — 시간이 멈춘 채 quantum 이 계속 쌓이는 일이 없다. 반복 횟수는 라운드마다
   * 최소 한 BLOCKED 큐가 ACTIVE 가 되므로 BLOCKED 큐 수로 제한된다.
   */
  @Override
  public long nextEligibleTime(long now) {
    if (pending == 0) {
      return Long.MAX_VALUE;
    }
    if (gate == null) {
      return now;
    }
    while (!parked.isEmpty() && parked.peek().reopen() <= now) {
      WdedfQueue queue = parked.poll().queue();
      queue.parked = false;
      offer(queue);
    }
    while (true) {
      while (!active.isEmpty()) {
        Candidate top = active.peek();
        long reopen = gate.reopenTime(top.queue().headMessage(), now);
        if (reopen <= now) {
          return now;
        }
        active.poll();
        heapPolls++;
        top.queue().parked = true;
        parked.add(new Parked(reopen, top.queue().index, top.queue()));
        budgetParks++;
      }
      long earliestParked = parked.isEmpty() ? Long.MAX_VALUE : parked.peek().reopen();
      if (blocked.isEmpty()) {
        return earliestParked;
      }
      long blockedReopen = Long.MAX_VALUE;
      for (WdedfQueue q = blocked.first(); q != null; q = q.nextBlocked) {
        blockedReopen = Math.min(blockedReopen, gate.reopenTime(q.headMessage(), now));
      }
      if (blockedReopen > now) {
        return Math.min(earliestParked, blockedReopen);
      }
      startNewRound(now);
    }
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
    long stateTransitions = 0;
    for (long[] row : transitions) {
      for (long count : row) {
        stateTransitions += count;
      }
    }
    return candidateExaminations + heapOffers + heapPolls + replenishmentSteps
        + stateTransitions + blocked.tokenAdvances();
  }

  @Override
  public Map<String, Long> diagnostics() {
    Map<String, Long> d = new LinkedHashMap<>();
    d.put("rounds", rounds);
    d.put("skippedRounds", skippedRounds);
    d.put("quantumReplenishments", quantumReplenishments);
    d.put("replenishmentSteps", replenishmentSteps);
    d.put("activationGrants", activationGrants);
    d.put("waitingReactivations", waitingReactivations);
    d.put("retainedCreditReused", retainedCreditReused);
    d.put("retainedCreditExpired", retainedCreditExpired);
    d.put("debtForgiven", debtForgiven);
    d.put("borrowAttempts", borrowAttempts);
    d.put("borrowSuccesses", borrowSuccesses);
    d.put("borrowedCost", borrowedCost);
    d.put("borrowRejectedNotMoreUrgent", rejectedNotMoreUrgent);
    d.put("borrowRejectedPq", rejectedPq);
    d.put("borrowRejectedPdc", rejectedPdc);
    d.put("borrowRejectedBudget", rejectedBudget);
    d.put("budgetParks", budgetParks);
    d.put("pqPerRound", preemptiveQuantum);
    d.put("pqGranted", pqGranted);
    d.put("debtEvents", debtEvents);
    d.put("minDeficit", minDeficit);
    d.put("maxDeficit", maxDeficit);
    d.put("heapOffers", heapOffers);
    d.put("heapPolls", heapPolls);
    d.put("candidateExaminations", candidateExaminations);
    d.put("tokenAdvances", blocked.tokenAdvances());
    d.put("tokenWraps", blocked.tokenWraps());
    d.put("maxActiveQueues", maxActive);
    d.put("maxBlockedQueues", maxBlocked);
    for (QueueState from : QueueState.values()) {
      for (QueueState to : QueueState.values()) {
        long count = transitions[from.ordinal()][to.ordinal()];
        if (count > 0) {
          d.put("transition." + from + "->" + to, count);
        }
      }
    }
    return d;
  }

  /**
   * Algorithm 2 의 차입 분기. token 이 가리키는 BLOCKED 큐 하나만 검사한다 (전역 탐색 없음).
   *
   * @return 차입으로 보낸 메시지, 거절되면 {@code null} (token 은 한 칸 진행)
   */
  private Message tryBorrow(long activeHeadDeparture, long now) {
    borrowAttempts++;
    candidateExaminations++;
    WdedfQueue candidate = blocked.tokenQueue();
    int cost = candidate.headCost();
    long pdc = currentPdc(candidate);
    if (!(activeHeadDeparture > candidate.headDeparture())) {
      rejectedNotMoreUrgent++;
    } else if (cost > pq) {
      rejectedPq++;
    } else if (cost > pdc) {
      rejectedPdc++;
    } else if (gate != null && gate.reopenTime(candidate.headMessage(), now) > now) {
      rejectedBudget++;
    } else {
      blocked.removeTokenQueue();
      candidate.pdc = pdc - cost;
      candidate.pdcRound = round;
      pq -= cost;
      borrowSuccesses++;
      borrowedCost += cost;
      Message message = dequeueFrom(candidate);
      updateQueueStatus(candidate);
      return message;
    }
    blocked.advanceToken();
    return null;
  }

  /** Algorithm 1 De-queue: {@code DC_i = DC_i − L_k}. 차입 dispatch 에서도 같은 차감을 한다. */
  private Message dequeueFrom(WdedfQueue queue) {
    Message message = queue.pop();
    queue.deficit -= message.cost();
    pending--;
    if (queue.deficit < 0) {
      debtEvents++;
    }
    minDeficit = Math.min(minDeficit, queue.deficit);
    return message;
  }

  /** Algorithm 1 Update_Queue_Status: 빈 큐 → WAITING, 적격 → ACTIVE, 그 외 → BLOCKED. */
  private void updateQueueStatus(WdedfQueue queue) {
    if (queue.isEmpty()) {
      transition(queue, QueueState.WAITING);
      if (!queue.waitingListed) {
        queue.waitingListed = true;
        waiting.add(queue);
      }
    } else if (queue.headCost() <= queue.deficit) {
      transition(queue, QueueState.ACTIVE);
      offer(queue);
    } else {
      transition(queue, QueueState.BLOCKED);
      blocked.append(queue);
    }
  }

  /**
   * Algorithm 2 의 새 라운드 분기 (명세 §3). 즉시 보충 + 적격성 확인 (해석 A11), 모든 PDC 재설정
   * (해석 A12), 필요하면 빈 라운드 압축 (해석 A18).
   */
  private void startNewRound(long now) {
    round++;
    rounds++;
    for (WdedfQueue queue : waiting) {
      queue.waitingListed = false;
      if (queue.state == QueueState.WAITING) {
        retainedCreditExpired += Math.max(0, queue.deficit);
        if (config.idleDebtPolicy() == IdleDebtPolicy.RETAIN_DEBT) {
          queue.deficit = Math.min(0, queue.deficit);
        } else {
          debtForgiven += Math.max(0, -queue.deficit);
          queue.deficit = 0;
        }
        transition(queue, QueueState.INACTIVE);
      }
    }
    waiting.clear();
    pq = preemptiveQuantum;
    pqGranted += preemptiveQuantum;
    blocked.resetToken();
    if (blocked.isEmpty()) {
      throw new IllegalStateException("Pending messages but no ACTIVE or BLOCKED queue");
    }
    replenishBlocked(1);
    if (!active.isEmpty()) {
      return;
    }
    // 빈 라운드 압축: 각 BLOCKED 큐가 적격해지기까지 필요한 추가 라운드 수 k_q 중, 게이트를 통과하는
    // 큐들의 최솟값 K 만큼 진행한다 (게이트가 없으면 모든 큐). 큐 q 는 min(K, k_q) 라운드분만 받는다:
    // k_q < K 인 (게이트에 막힌) 큐는 k_q 번째 라운드에 ACTIVE 가 되어 그 뒤로는 보충받지 않기
    // 때문이다. 게이트가 없으면 모든 k_q ≥ K 이므로 v0.3 과 같다.
    long minRounds = Long.MAX_VALUE;
    long minAdmitted = Long.MAX_VALUE;
    for (WdedfQueue q = blocked.first(); q != null; q = q.nextBlocked) {
      long needed = roundsNeeded(q);
      minRounds = Math.min(minRounds, needed);
      if (gate == null || gate.reopenTime(q.headMessage(), now) <= now) {
        minAdmitted = Math.min(minAdmitted, needed);
      }
    }
    long skip = minAdmitted == Long.MAX_VALUE ? minRounds : minAdmitted;
    round += skip;
    rounds += skip;
    skippedRounds += skip;
    WdedfQueue queue = blocked.first();
    while (queue != null) {
      WdedfQueue next = queue.nextBlocked;
      long credited = Math.min(skip, roundsNeeded(queue));
      grantQuantum(queue, credited);
      quantumReplenishments += credited;
      replenishmentSteps++;
      if (queue.headCost() <= queue.deficit) {
        blocked.unlink(queue);
        transition(queue, QueueState.ACTIVE);
        offer(queue);
      }
      queue = next;
    }
  }

  private static long roundsNeeded(WdedfQueue q) {
    long shortfall = q.headCost() - q.deficit;
    return (shortfall + q.quantum - 1) / q.quantum;
  }

  /** 모든 BLOCKED 큐에 {@code roundsCredited × ω} 를 더하고, 적격해진 큐를 ACTIVE 로 옮긴다. */
  private void replenishBlocked(long roundsCredited) {
    WdedfQueue queue = blocked.first();
    while (queue != null) {
      WdedfQueue next = queue.nextBlocked;
      grantQuantum(queue, roundsCredited);
      quantumReplenishments += roundsCredited;
      replenishmentSteps++;
      if (queue.headCost() <= queue.deficit) {
        blocked.unlink(queue);
        transition(queue, QueueState.ACTIVE);
        offer(queue);
      }
      queue = next;
    }
  }

  /** 라운드당 quantum 1 회 적용을 강제하며 {@code count × ω} 를 더한다. */
  private void grantQuantum(WdedfQueue queue, long count) {
    if (queue.lastQuantumRound >= round) {
      throw new IllegalStateException("Quantum applied twice in round " + round + " to "
          + queue.key);
    }
    queue.lastQuantumRound = round;
    queue.deficit = Math.addExact(queue.deficit, Math.multiplyExact(count, queue.quantum));
    maxDeficit = Math.max(maxDeficit, queue.deficit);
  }

  private long currentPdc(WdedfQueue queue) {
    return queue.pdcRound == round ? queue.pdc : queue.quantum;
  }

  private void offer(WdedfQueue queue) {
    Message head = queue.headMessage();
    active.offer(new Candidate(
        queue.headDeparture(), head.arrivalTime(), head.id(), queue.index, queue));
    heapOffers++;
  }

  private void transition(WdedfQueue queue, QueueState to) {
    transitions[queue.state.ordinal()][to.ordinal()]++;
    queue.state = to;
  }

  private WdedfQueue queueFor(String key) {
    WdedfQueue queue = queues.get(key);
    if (queue == null) {
      Long quantum = quanta.get(key);
      if (quantum == null) {
        throw new IllegalArgumentException("No quantum configured for queue " + key);
      }
      queue = new WdedfQueue(key, queues.size(), quantum);
      queues.put(key, queue);
    }
    return queue;
  }

  // ---- 테스트용 상태 조회 (패키지 비공개) ----

  QueueState stateOf(String key) {
    WdedfQueue queue = queues.get(key);
    return queue == null ? QueueState.INACTIVE : queue.state;
  }

  long deficitOf(String key) {
    WdedfQueue queue = queues.get(key);
    return queue == null ? 0 : queue.deficit;
  }

  long pdcOf(String key) {
    WdedfQueue queue = queues.get(key);
    return queue == null ? quanta.get(key) : currentPdc(queue);
  }

  long remainingPq() {
    return pq;
  }

  long preemptiveQuantum() {
    return preemptiveQuantum;
  }

  long round() {
    return round;
  }

  List<String> blockedOrder() {
    return blocked.keys();
  }

  int tokenIndex() {
    return blocked.isEmpty() ? 0 : blocked.tokenIndex();
  }

  /**
   * 모든 큐의 소속 불변식을 검사한다 (명세 §2). 위반 시 {@link IllegalStateException}.
   * O(Q + A + B) 이므로 테스트에서만 사용한다.
   */
  void checkInvariants() {
    Map<WdedfQueue, Integer> activeCount = new HashMap<>();
    for (Candidate candidate : active) {
      activeCount.merge(candidate.queue(), 1, Integer::sum);
      if (candidate.messageId() != candidate.queue().headMessage().id()) {
        throw new IllegalStateException("Stale candidate for " + candidate.queue().key);
      }
    }
    for (Parked p : parked) {
      activeCount.merge(p.queue(), 1, Integer::sum);
    }
    Map<WdedfQueue, Integer> blockedCount = new HashMap<>();
    for (WdedfQueue q = blocked.first(); q != null; q = q.nextBlocked) {
      blockedCount.merge(q, 1, Integer::sum);
    }
    long pendingSum = 0;
    for (WdedfQueue queue : queues.values()) {
      pendingSum += queue.size();
      int inActive = activeCount.getOrDefault(queue, 0);
      int inBlocked = blockedCount.getOrDefault(queue, 0);
      boolean ok = switch (queue.state) {
        case ACTIVE -> inActive == 1 && inBlocked == 0 && !queue.isEmpty()
            && queue.headCost() <= queue.deficit;
        case BLOCKED -> inActive == 0 && inBlocked == 1 && !queue.isEmpty()
            && queue.headCost() > queue.deficit;
        case WAITING -> inActive == 0 && inBlocked == 0 && queue.isEmpty()
            && queue.waitingListed;
        case INACTIVE -> inActive == 0 && inBlocked == 0 && queue.isEmpty()
            && (config.idleDebtPolicy() == IdleDebtPolicy.PAPER_RESET
                ? queue.deficit == 0 : queue.deficit <= 0);
      };
      if (!ok) {
        throw new IllegalStateException("Invariant violated for " + queue.key + " in "
            + queue.state + " (active=" + inActive + ", blocked=" + inBlocked + ", deficit="
            + queue.deficit + ")");
      }
    }
    if (pendingSum != pending || pq < 0) {
      throw new IllegalStateException("Pending/PQ accounting broken");
    }
  }
}
