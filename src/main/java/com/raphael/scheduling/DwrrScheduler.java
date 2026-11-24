package com.raphael.scheduling;

import com.raphael.domain.Message;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Deficit Weighted Round Robin (tenant별 quantum을 적용한 Shreedhar &amp; Varghese DRR).
 *
 * <h2>알고리즘</h2>
 * <ul>
 *   <li>각 tenant는 자신만의 FIFO 큐, {@code baseQuantum * weight} 크기의 quantum,
 *       deficit(결손 카운터)을 가진다.</li>
 *   <li>backlog가 있는 tenant들은 <em>active ring</em>({@link ArrayDeque})을 이룬다. tenant는
 *       큐가 빈 상태에서 비어 있지 않은 상태로 바뀔 때 정확히 한 번 ring에 추가된다.</li>
 *   <li>ring의 head가 현재 방문 중인 tenant이다. 방문이 시작될 때 deficit이 quantum만큼
 *       증가한다. head 메시지의 cost가 deficit 안에 들어오는 동안 그 메시지를 발송하고 cost를
 *       차감한다. 하나의 방문은 provider 슬롯당 한 번씩, 여러 번의 {@code selectNext} 호출에 걸쳐
 *       이어진다. deficit은 다음 방문 전까지 다시 보충되지 않는다.</li>
 *   <li>head 메시지가 deficit 안에 들어오지 않으면 방문이 끝난다. tenant는 ring의 tail로 이동하며
 *       사용하지 않은 deficit은 다음 라운드를 위해 유지한다.</li>
 *   <li>tenant의 큐가 비면 ring에서 빠지고 deficit은 0으로 초기화되므로, 유휴 tenant는 크레딧을
 *       적립할 수 없다. 이후 도착이 발생하면 tail에 다시 추가된다.</li>
 * </ul>
 *
 * <h2>Work conservation과 유휴 라운드 압축</h2>
 * 모든 활성 tenant가 연달아 한 번씩 실패하면(발송 없이 ring을 한 바퀴 돌면), 각 tenant는 발송
 * 가능해지기까지 {@code k_i = ceil((headCost_i - deficit_i) / quantum_i)}번의 방문이 더 필요하다.
 * {@code K = min k_i}라 하면, 이어지는 {@code K - 1}번의 순회는 모든 deficit에 {@code quantum_i}를
 * 더하는 것 외에는 아무것도 바꾸지 않는다. 따라서 스케줄러는 모든 활성 deficit에
 * {@code (K - 1) * quantum_i}를 한 번에 더한 뒤 ring 순회를 정상적으로 계속한다. 그 결과 발송
 * 순서는 교과서적인 루프와 동일하다(naive 구현과의 differential test로 검증됨). {@code k_i}는 각
 * 실패한 방문 중에 계산하므로, 건너뛸 것이 없는 순회({@code K = 1})에는 추가 스캔 비용이 없다.
 * {@code selectNext}는 스케줄러가 비어 있지 않으면 항상 메시지를 반환하므로, deficit이 부족한
 * 라운드 뒤에도 빈 선택을 반환하는 일이 없다.
 *
 * <h2>복잡도</h2>
 * {@code A}를 활성 tenant 수, {@code C}를 head cost, {@code Q}를 quantum이라 하자.
 * <ul>
 *   <li>{@code enqueue}: O(1) (HashMap 조회 1회와 deque 추가 최대 2회).</li>
 *   <li>{@code selectNext} 최악의 경우: 실패한 방문 최대 {@code A}회, O(A) fast-forward 1회,
 *       이후 방문 최대 {@code A}회. 즉 O(A)이며 {@code C / Q}와 무관하다. 압축이 없다면 최악의
 *       경우는 O(A * ceil(C_max / Q_min))이 된다.</li>
 *   <li>모든 tenant에 대해 {@code Q_i >= C_max}일 때의 amortized 비용: 보충이 일어나는 모든 방문이
 *       최소 한 개의 메시지를 발송하므로, 실패한 방문 수는 발송 수를 넘지 않는다. selectNext는
 *       amortized O(1)이며, 이는 고전적인 DRR과 같다.</li>
 *   <li>quantum이 cost에 비해 작을 때({@code Q < C})의 amortized 비용: tenant는 메시지 하나당 약
 *       {@code C / Q}번의 방문이 필요하므로, 각 발송 앞에 다른 tenant들의 실패한 방문이 최대
 *       {@code A - 1}회 선행될 수 있다. 압축은 이를 선택당 O(A * C / Q)가 아닌 O(A)로 제한한다.
 *       그래도 작은 quantum은 발송당 작업량을 O(A) 쪽으로 끌어올리며, 단기 서비스 지연(service
 *       lag)을 키운다(라운드마다 tenant당 최대 cost 하나 정도까지).</li>
 *   <li>공간: 한 번이라도 도착한 tenant T개와 대기 메시지 N개에 대해 O(T + N). 선택 과정은 활성
 *       tenant만 다루므로, 설정만 되어 있고 유휴 상태인 tenant는 비용이 들지 않는다.</li>
 * </ul>
 *
 * <p>{@link #operationCount()}는 검사한 tenant 방문 수(성공이든 실패든 ring head를 한 번 검사할
 * 때마다 1)에, 유휴 라운드 건너뛰기에서 크레딧을 받은 tenant당 1 step을 더한 값이다.
 */
public final class DwrrScheduler implements SchedulingPolicy {

  private final long baseQuantum;
  private final Map<String, Integer> tenantWeights;
  private final Function<Message, String> classifier;
  private final String name;
  private final Map<String, TenantQueue> queues = new HashMap<>();
  private final ArrayDeque<TenantQueue> activeRing = new ArrayDeque<>();

  /**
   * ring head가 현재 방문에 대한 quantum을 이미 받았을 때, 그리고 그때에만 {@code true}이다.
   * {@code selectNext} 호출 사이에도 유지되므로, 하나의 방문이 재보충 없이 여러 provider 슬롯에
   * 걸쳐 이어질 수 있다.
   */
  private boolean visitInProgress;

  private long pending;
  private long operations;
  private long fastForwards;

  /**
   * @param tenantWeights tenant별 양수 weight. 등록되지 않은 tenant의 메시지는 거부된다
   * @param baseQuantum 방문마다 weight 1단위당 더해지는 양수 deficit
   */
  public DwrrScheduler(Map<String, Integer> tenantWeights, int baseQuantum) {
    this(tenantWeights, baseQuantum, Message::tenantId, "DWRR");
  }

  /**
   * 큐 토폴로지를 바꿀 수 있는 생성자. {@code classifier} 가 메시지를 큐 키로 보내고
   * {@code queueWeights} 는 그 키별 가중치이다. 기본 생성자는 테넌트 id 를 키로 쓴다.
   */
  public DwrrScheduler(Map<String, Integer> queueWeights, int baseQuantum,
      Function<Message, String> classifier, String name) {
    if (baseQuantum <= 0) {
      throw new IllegalArgumentException("baseQuantum must be positive: " + baseQuantum);
    }
    queueWeights.forEach((tenant, weight) -> {
      if (weight == null || weight <= 0) {
        throw new IllegalArgumentException("Weight must be positive for tenant " + tenant);
      }
    });
    this.baseQuantum = baseQuantum;
    this.tenantWeights = Map.copyOf(queueWeights);
    this.classifier = classifier;
    this.name = name;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public void enqueue(Message message) {
    String key = classifier.apply(message);
    TenantQueue queue = queues.get(key);
    if (queue == null) {
      queue = createQueue(key);
    }
    queue.add(message);
    pending++;
    if (!queue.active) {
      // 활성화: 큐가 비어 있었으므로 tenant는 ring에 없고 deficit은 0이다.
      queue.active = true;
      activeRing.addLast(queue);
    }
  }

  @Override
  public Message selectNext(long now) {
    if (pending == 0) {
      throw new IllegalStateException("selectNext called on an empty DWRR scheduler");
    }
    int consecutiveFailedVisits = 0;
    long minVisitsNeeded = Long.MAX_VALUE;
    while (true) {
      TenantQueue tenant = activeRing.peekFirst();
      operations++;
      if (!visitInProgress) {
        tenant.deficit += tenant.quantum();
        visitInProgress = true;
      }

      Message head = tenant.head();
      if (head.cost() <= tenant.deficit) {
        tenant.removeHead();
        tenant.deficit -= head.cost();
        pending--;
        if (tenant.isEmpty()) {
          deactivateHead(tenant);
        }
        return head;
      }

      // 방문 종료: tail로 회전하고, 사용하지 않은 deficit은 다음 라운드를 위해 유지한다.
      // 실패한 tenant의 deficit은 이번 순회 동안 다시 바뀌지 않으므로, 앞으로 필요한 방문 수를
      // 지금 계산해 별도의 스캔을 피한다.
      long shortfall = head.cost() - tenant.deficit;
      minVisitsNeeded = Math.min(
          minVisitsNeeded, (shortfall + tenant.quantum() - 1) / tenant.quantum());
      activeRing.addLast(activeRing.pollFirst());
      visitInProgress = false;
      if (++consecutiveFailedVisits == activeRing.size()) {
        if (minVisitsNeeded > 1) {
          skipIdleRounds(minVisitsNeeded - 1);
        }
        consecutiveFailedVisits = 0;
        minVisitsNeeded = Long.MAX_VALUE;
      }
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
    return operations;
  }

  /** 수행된 유휴 라운드 압축 횟수(진단용). */
  public long fastForwardCount() {
    return fastForwards;
  }

  long deficitOf(String tenantId) {
    TenantQueue queue = queues.get(tenantId);
    return queue == null ? 0 : queue.deficit;
  }

  boolean isActive(String tenantId) {
    TenantQueue queue = queues.get(tenantId);
    return queue != null && queue.active;
  }

  /** 다음에 방문할 tenant부터 시작하는, ring 순서의 tenant id 목록. */
  List<String> activeRingOrder() {
    List<String> order = new ArrayList<>(activeRing.size());
    for (TenantQueue queue : activeRing) {
      order.add(queue.tenantId());
    }
    return order;
  }

  /** 서비스 방문이 진행 중인 tenant. 없으면 {@code null}. */
  String tenantInService() {
    return visitInProgress ? activeRing.peekFirst().tenantId() : null;
  }

  private TenantQueue createQueue(String tenantId) {
    Integer weight = tenantWeights.get(tenantId);
    if (weight == null) {
      throw new IllegalArgumentException("No weight configured for tenant " + tenantId);
    }
    TenantQueue queue = new TenantQueue(tenantId, baseQuantum * weight);
    queues.put(tenantId, queue);
    return queue;
  }

  private void deactivateHead(TenantQueue tenant) {
    activeRing.pollFirst();
    tenant.active = false;
    tenant.deficit = 0;
    visitInProgress = false;
  }

  /**
   * 어떤 tenant도 발송하지 못한 ring 한 바퀴 순회가 끝났고, 최소 한 번 더 순회해도 역시 아무것도
   * 발송되지 않을 때({@code K > 1}) 호출된다. 모든 활성 tenant에 {@code skippedRounds = K - 1}번의
   * 빈 순회에 해당하는 quantum을 적립한다. 그다음 순회는 정상적으로 수행되므로, 어느 tenant가
   * 먼저 발송할지는 여전히 ring 순서만으로 결정된다.
   */
  private void skipIdleRounds(long skippedRounds) {
    fastForwards++;
    for (TenantQueue tenant : activeRing) {
      operations++;
      tenant.deficit += skippedRounds * tenant.quantum();
    }
  }
}
