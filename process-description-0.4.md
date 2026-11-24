# 프로세스 설명서 — 멀티테넌트 메시지 전송 시뮬레이터 (v0.4)

이 문서는 시뮬레이터가 **어떤 순서로 동작하는지**, **패키지와 메서드가 각각 무엇을 하는지**, 그리고
**어떻게 빌드·실행·확장하는지** 를 설명한다. v0.3 문서 (`process-description-0.3.md`) 의 내용을 모두
포함하며, v0.4 에서 추가·변경된 부분은 **[v0.4]** 로 표시한다.

관련 문서:
* `docs/v0.4-notes.md` — v0.4 설정·가정·불변식·한계 요약
* `docs/wdedf-specification.md` — WDEDF 알고리즘 명세와 논문 해석 (A1–A26)
* `docs/v0.3-results.md`, `docs/results/v0.3-full-run.txt` — v0.3 실험 결과

v0.4 에서 추가된 것: Service Budget (`scheduling.budget`), Shuffle Sharding 과 샤드 장애·복구
(`scheduling.sharding`), Aging (`AgingDeparturePolicy`), E3–E5 실험 arm (`ArchitectureArms`), 장애 영향
범위 (`BlastRadius`), CSV·JSON 결과 저장 (`persistence`), `--suite=v04` 와 빠른 실행 모드.

---

## 1. 한눈에 보기

시뮬레이터는 **단일 스레드, 결정론적 이산 사건 시뮬레이션** 이다. 실제 시간을 기다리지 않고, 가상
시계(μs 단위)를 사건(event) 시각으로 건너뛰며 진행한다. 같은 입력은 항상 같은 결과를 낸다.

```
Scenario (불변 입력: 테넌트, 메시지, provider 용량·지연)
   │
   ▼
ExperimentRunner ── 실행마다 새로 생성 ──► SchedulingPolicy
   │                                         FIFO / DWRR / WDEDF / EDF            (E0–E2)
   │                                         ServiceClassPolicy (+예산)            (E3)   [v0.4]
   │                                         ShardedPolicy (+샤드·장애, +Aging)   (E4, E5) [v0.4]
   │                                       ProviderSimulator
   │                                       MetricsCollector (+예산 감사 [v0.4])
   ▼                                       SimulationEngine (VirtualClock + 사건 큐)
SimulationEngine.run()
   │   ARRIVAL ──► metrics.onArrival ─► policy.enqueue ─► DISPATCH_READY 예약
   │   DISPATCH_READY ──► policy.nextEligibleTime(now) [v0.4]
   │        ├ == now  ─► policy.selectNext ─► metrics.onDispatch ─► provider.dispatch
   │        │            ─► DELIVERED 예약 ─► 다음 DISPATCH_READY 예약
   │        ├ 미래 시각 ─► 그 시각에 DISPATCH_READY 재예약 (예산 보충·샤드 슬롯)
   │        └ MAX      ─► 대기 (도착·복구 사건이 다시 예약)
   │   DELIVERED ──► metrics.onDelivery
   │   RESOURCE_FAILURE / RESOURCE_RECOVERY [v0.4] ──► 샤드 장애·복구 (복구 후 dispatch 재예약)
   ▼
SimulationResult (결정론적 지표) + ExecutionProfile (시간 측정)
   │
   ├─► ComparisonReport / MultiArmReport (콘솔 보고서)
   └─► ResultWriter (CSV·JSON 파일) [v0.4]
```

핵심 원칙:
* **엔진** 은 시간과 사건만 관리하고, **정책** 은 "다음에 어떤 메시지를 보낼지" 만 정하며, **provider**
  는 "언제 보낼 수 있는지" 만 정한다. **지표** 는 수집만 하고 출력하지 않는다.
* 모든 실행(warm-up 포함)은 엔진·정책·provider·지표 객체를 **새로** 만든다. 공유되는 것은 불변
  `Scenario` 뿐이다. [v0.4] 테넌트 예산·클래스 리밋·샤드 풀도 실행마다 새로 만들어지며, **한 실행 안에서는**
  모든 큐·샤드가 같은 객체를 공유한다.
* [v0.4] 예산·샤드는 "언제 보낼 수 있는지" 를 제한하는 자원이고, 스케줄러는 여전히 "무엇을 보낼지" 만
  정한다. 정책은 메시지를 꺼내거나 deficit 을 쓰기 전에 자원 상태를 확인만 한다.

---

## 2. 한 번의 시뮬레이션 실행 흐름

`ExperimentRunner.runOnce(scenario, config, observer)` 기준:

1. `config.policyFactory().apply(scenario)` 로 새 스케줄링 정책을 만든다 (비어 있는지 확인).
2. `ProviderSimulator(serviceModel, capacity, deliveryDelay)` 와 `MetricsCollector(...)` 를 만든다.
   지표 수집기에는 논문 식 (2) 의 출발 시각 함수가 함께 전달된다 (모든 정책에 같은 기준으로 위반 계산).
3. `SimulationEngine` 을 만들고 시나리오의 모든 메시지를 `scheduleArrival` 로 사건 큐에 넣는다.
4. `engine.run()`:
   * 사건 큐에서 가장 이른 사건을 꺼내 `VirtualClock.advanceTo` 로 시각을 옮긴다.
   * 같은 시각이면 `DELIVERED → ARRIVAL → DISPATCH_READY` 순서, 그 다음 삽입 순서로 처리한다
     (`EventType` 선언 순서 = tie-break. 같은 시각에 도착한 메시지가 그 시각의 슬롯을 쓸 수 있다).
   * **ARRIVAL**: 지표 기록 → `policy.enqueue` → 대기 중인 dispatch 가 없으면
     `max(now, provider.nextAvailableTime())` 에 `DISPATCH_READY` 예약.
   * **DISPATCH_READY**: [v0.4] 먼저 `policy.nextEligibleTime(now)` 를 묻는다. `now` 이면
     `policy.selectNext(now)` → 지표 기록 → `provider.dispatch(now, cost)` 가 돌려준 배달 시각에
     `DELIVERED` 예약 → 정책에 메시지가 남아 있으면 다음 `DISPATCH_READY` 예약. 미래 시각이면 그 시각
     (과 provider 가능 시각 중 늦은 쪽) 에 재예약하고, `MAX` 이면 아무것도 예약하지 않는다.
   * **RESOURCE_FAILURE / RESOURCE_RECOVERY** [v0.4]: 정책이 `ResourceController` 이면 실행 시작 시 장애·
     복구 사건을 예약한다. 복구 후에는 dispatch 를 다시 예약한다. 같은 시각이면 dispatch 다음에 처리된다.
   * **DELIVERED**: 지표 기록 (deadline 을 넘겨도 배달하고 miss 로 집계한다).
5. 사건 큐가 비면 `metrics.buildResult(...)` 로 불변 `SimulationResult` 를 만든다.
6. Runner 가 **검증** 한다: 수신 = dispatch = 배달 (메시지 보존), provider 용량 감사 위반 0,
   [v0.4] 독립 감사로 본 테넌트 예산 위반 0, 샤드 용량 위반 0.
7. 반복 실행(`run`)에서는 모든 측정 반복의 `SimulationResult` 가 `equals` 여야 한다 (결정론 검증).

엔진의 안전장치:
* 정책이 메시지를 가지고 있는데 `selectNext` 가 `null` 을 돌려주면 즉시 예외 (무한 루프 방지).
* 같은 가상 시각에 두 번 dispatch 하면 즉시 예외.
* 엔진은 일회용이다 (`run()` 두 번 호출 시 예외).
* [v0.4] dispatch 판단 사건은 항상 하나만 유효하다 (`scheduledDispatchTime`). 더 이른 판단이 필요해지면
  새 사건이 이전 사건을 대체하고, 대체된 사건은 무시된다. 같은 시각에 판단을 다시 예약하지 않으므로
  같은 시각 무한 루프가 생기지 않는다. 정책이 과거 시각을 돌려주면 예외.
* [v0.4] 새 기능을 쓰지 않는 정책 (FIFO, DWRR, 게이트 없는 WDEDF, EDF) 은 v0.3 과 사건 순서가 같다
  (골든 테스트로 검증).

---

## 3. 패키지와 클래스·메서드 설명

### 3.1 `com.raphael` — 진입점

| 클래스 | 메서드 | 동작 |
|---|---|---|
| `SimulatorApplication` | `main(String[])` | `--key=value` 옵션을 파싱하고 suite 별로 실험을 실행·출력한다 (§5). [v0.4] `--suite=v04` 이면 `V04Suite` 를 실행한다. |
| | `printDispatchTrace` | S1 에서 OTP 가 dispatch 순서의 몇 번째인지 출력. |
| | `parseOptions` / `intOption` / `longOption` | 알 수 없는 옵션은 예외로 거부. |
| `WdedfExperimentSuite` | `history(scenarios, q)` | 기존 S1–S4b 에 v0.3 arm (E0, E1, E2, D1–D3) 을 실행. |
| | `paper()` | 논문 검증 P1–P4 실행, P1/P2 dispatch 추적, P4 흐름별 지연 표. |
| | `message(q)` | 메시지 도메인 S5–S12 실행. |
| | `lambdaSweep()` | 차입 비율 Λ = 0, 1, 5, 10, 20% 민감도 (S7, S11). |
| `V04Suite` [v0.4] | `run(outputDir, runSettings)` | E0–E5 를 S2, S3, S5, S12, S13, F1 에서 실행하고 콘솔 보고서 출력 후 CSV·JSON 저장. |
| | `standard(...)` | 한 시나리오에 E0–E5 실행 → 보고서 → 저장 항목 추가. |
| | `failure(...)` | F1 에서 E0–E5 + E4-F·E5-F (샤드 1 장애) 실행, 같은 arm 의 장애 유무로 영향 범위 계산. |

### 3.2 `com.raphael.core` — 시뮬레이션 엔진

| 클래스 | 메서드 | 동작 |
|---|---|---|
| `VirtualClock` | `now()`, `advanceTo(t)` | μs 가상 시계. 뒤로 가면 예외. 상수 `MICROS_PER_SECOND`, `MICROS_PER_MILLISECOND`. |
| `EventType` | — | `DELIVERED`, `ARRIVAL`, `DISPATCH_READY`, [v0.4] `RESOURCE_FAILURE`, `RESOURCE_RECOVERY` (선언 순서가 같은 시각의 처리 순서). |
| `SimulationEvent` | `ORDER` | 시각 → 유형 → 삽입 번호의 전순서 비교자. [v0.4] 자원 사건용 `resource` (샤드 번호) 필드. |
| `ResourceController` [v0.4] | `resourceEvents()`, `fail(r, now)`, `recover(r, now)` | 장애·복구 가능한 자원을 가진 정책이 구현. `ResourceEvent(time, resource, failure)`. |
| `SimulationObserver` | `onArrival/onDispatch/onDelivery` | 읽기 전용 추적 hook (테스트·추적용). 기본은 `NONE`. |
| `SimulationEngine` | `scheduleArrival(m)` | 도착 사건 예약 (`run` 전에만). |
| | `run()` | §2 의 사건 루프. 끝나면 `SimulationResult` 반환. |
| | `scheduleDispatch()` / `scheduleDispatchAt(t)` [v0.4] | 유효한 dispatch 판단 사건 하나만 유지 (더 이른 시각이면 대체). |
| | `handleDispatch()` [v0.4] | `nextEligibleTime` 확인 후 선택·발송 또는 재예약. |
| | `schedulerElapsedNanos()`, `schedulerTimedCalls()` | `enqueue`·`selectNext` 에 쓴 경과 시간(측정 시). |

### 3.3 `com.raphael.domain` — 도메인 객체 (불변)

| 클래스 | 내용 |
|---|---|
| `Message(id, tenantId, type, arrivalTime, deadline, cost)` | `missesDeadline(t)`: `t > deadline` 이면 miss (정확히 deadline 이면 성공). cost 는 양수. |
| `MessageType` | `ADVERTISEMENT`, `OTP`. 스케줄러는 유형을 직접 보지 않는다 (토폴로지 분류기 제외). |
| `Tenant(id, weight)` | weight 는 양의 정수 (가중 정책의 몫). |

### 3.4 `com.raphael.scheduling` — 스케줄링 정책

공통 계약 `SchedulingPolicy`:

| 메서드 | 의미 |
|---|---|
| `name()` | 보고서용 이름 |
| `enqueue(m)` | 새 메시지 수용 |
| `selectNext(now)` | 다음 메시지를 꺼내 반환. `nextEligibleTime(now) == now` 일 때만 호출되며, 그때는 **반드시 메시지를 반환**. 비어 있으면 `IllegalStateException`. |
| `nextEligibleTime(now)` [v0.4] | 지금 선택 가능하면 `now`, 아니면 선택 가능성이 바뀔 가장 이른 미래 시각, 외부 사건 없이는 안 바뀌면 `MAX`. 메시지·deficit 을 소비하지 않으며 같은 `now` 로 반복 호출해도 결과가 같다. 기본 구현: 대기가 있으면 `now`. |
| `isEmpty()`, `pendingCount()` | 대기 메시지 |
| `operationCount()` | 알고리즘 단계 수 (CPU 명령 수 아님) |
| `diagnostics()` | 정책별 계수 (WDEDF: 라운드, 차입, heap 연산 등) |

| 클래스 | 로직 |
|---|---|
| `FifoScheduler` (E0) | 단일 `ArrayDeque`. 테넌트·가중치·cost 무시. O(1). |
| `DwrrScheduler` (E1) | 큐별 FIFO (`TenantQueue`) + active ring (`ArrayDeque`). 방문 시작에 `deficit += baseQuantum × weight`, head cost ≤ deficit 인 동안 발송 (방문은 여러 provider 슬롯에 걸쳐 유지). 부족하면 ring 꼬리로 회전하며 deficit 보존. 큐가 비면 ring 에서 빠지고 deficit = 0. 한 바퀴 내내 아무도 못 보내면 필요한 빈 라운드를 한 번에 건너뛴다 (`skipIdleRounds`). 분류기 생성자로 큐 토폴로지를 바꿀 수 있다. |
| `TenantQueue` | DWRR 의 큐별 상태 (메시지 deque, quantum, deficit, active). |
| `EdfScheduler` (D3, 진단) | 모든 메시지를 하나의 heap 에 넣는 전역 EDF. 출발 기준 시각 → 도착 → id 순. 테넌트 공정성 없음. |
| `DispatchGate` [v0.4] | 메시지를 지금 보내도 되는지 판단하는 비파괴 게이트. `reopenTime(m, now)` (≤ now 면 허용), `consume(m, now)` (선택 후 차감). `OPEN` 은 항상 허용. |
| `QueueLayout` | 큐 토폴로지. `TENANT` (테넌트당 큐 1 개) 또는 `TENANT_AND_TYPE` (테넌트×유형 자식 큐, 테넌트 가중치를 자식에게 **분할**: `w × lcm(k) / k`). `classifier()` 는 메시지 → 큐 키 (할당 없는 표 조회). |

### 3.5 `com.raphael.scheduling.wdedf` — WDEDF (논문 Lim·Lee·Yeo 2005)

| 클래스 | 메서드 | 로직 |
|---|---|---|
| `ReservationModel` | `proportionalToWeights(weights, C)` | `R_i = w_i / Σw × C` (유리수로 정확히 표현). |
| | `explicitRates(rates, C)` | 명시적 예약률 (논문 예제용). `0 < R_i ≤ C` 검증. |
| | `equationOneQuanta(ω_MIN)` | **식 (1)** `ω_i = floor(R_i / R_MIN × ω_MIN)`. 내림이 있었으면 `exact=false`. |
| `DepartureTimePolicy` | `departureTime(m)` | 큐 head 정렬 기준 시각. enqueue 시 한 번 계산·저장. |
| `PaperDepartureTimePolicy` | `departureTime(m)` | **식 (2)** `T_A + L/R − L/C` 를 μs 로 내림. 큐별 계수를 기약분수로 미리 계산, overflow 시 `BigInteger`. |
| `BusinessDepartureTimePolicy` | `departureTime(m)` | 메시지 도메인 적응: `deadline − 예상 배달 지연`. `Message.deadline` 은 바뀌지 않는다. |
| `QueueState` | — | `INACTIVE`, `ACTIVE`, `WAITING`, `BLOCKED` (논문 Fig. 2). |
| `IdleDebtPolicy` | — | `PAPER_RESET` (논문: 비활성화 시 DC 재설정 → 차입 부채 탕감), `RETAIN_DEBT` (진단: 부채 유지). |
| `WdedfConfig(name, lambdaBasisPoints, idleDebtPolicy)` | — | Λ 는 기저점 (500 = 5%). `PQ = floor(Ω × Λ)`. |
| `AgingDeparturePolicy` [v0.4] | `departureTime(m)` | `min(기본 출발 시각, 도착 + threshold)`. 키가 enqueue 시 고정되므로 heap 순서가 항상 올바르다. deadline 은 바꾸지 않는다. |
| | `isPromoted(m, dispatchTime)` | 키가 aging 으로 정해졌고 실제로 threshold 보다 오래 기다린 뒤 dispatch 되었으면 참 (승격 계수용). |
| `WdedfQueue` | `push/pop/headMessage/headDeparture/headCost` | 메시지·출발 시각을 원형 배열 두 개에 저장 (메시지당 객체 할당 없음). DC, PDC, 상태, blocked 목록 링크를 가진다. |
| `BlockedList` | `append`, `unlink`, `tokenQueue`, `removeTokenQueue`, `advanceToken`, `resetToken` | 논문의 Blocked List + 선점 token PT. 큐에 내장된 이중 연결 리스트라 모든 연산 O(1). 인덱스 의미: 제거 후 PT 는 다음 큐, 끝을 넘으면 0. |
| `WdedfScheduler` | 아래 표 | 상태기계·라운드·차입을 한 구현으로 공유 (모든 WDEDF 변형이 같은 클래스). |

`WdedfScheduler` 의 메서드별 로직 (논문 대응):

| 메서드 | 논문 | 동작 |
|---|---|---|
| 생성자 | Alg.1 Initialise | DC=0, 모든 큐 INACTIVE, Ω = Σω, PQ = floor(Ω×Λ), 라운드 1. [v0.4] 선택적 `DispatchGate` (없으면 v0.3 과 동일). |
| `nextEligibleTime(now)` [v0.4] | — | 게이트가 없으면 대기가 있는 한 `now`. 게이트가 있으면: 재개 시각이 지난 주차 큐를 heap 에 되돌리고, heap top 이 막혀 있으면 **메시지를 꺼내지 않고** 주차 목록으로 옮기며, heap 이 비면 게이트를 통과하는 BLOCKED 큐가 있을 때만 라운드를 넘긴다. 아무도 통과하지 못하면 가장 이른 재개 시각을 돌려준다 (시간이 멈춘 채 quantum 이 쌓이지 않음). |
| `enqueue(m)` | Alg.1 En-queue | 출발 시각 계산·저장 후 큐에 추가. INACTIVE 면 `DC += ω` (논문 `DC = ω`) 후 상태 갱신, WAITING 이면 **보존된 DC 재사용** 후 상태 갱신. ACTIVE/BLOCKED 는 head 가 안 바뀌므로 그대로. |
| `selectNext(now)` | Alg.2 Get_Packet | ACTIVE heap 이 비었으면 `startNewRound()`. heap 의 최소 head (출발 시각 → 도착 → id → 큐 번호) 를 후보로 보고, BLOCKED 가 있으면 먼저 `tryBorrow`. 차입이 없으면 heap 에서 꺼내 `dequeueFrom` + `updateQueueStatus`. |
| `tryBorrow(activeDeparture, now)` | Alg.2 차입 분기 | PT 가 가리키는 BLOCKED 큐 **하나만** 검사: `activeHead.T̂_D > blocked.T̂_D` (엄격), `cost ≤ PQ`, `cost ≤ PDC`, [v0.4] 게이트 통과. 성공하면 PQ·PDC·**일반 DC** 에서 차감 (DC 음수 = 부채), 실패하면 사유를 기록하고 PT 한 칸 진행. |
| `dequeueFrom(q)` | Alg.1 De-queue | head 를 꺼내 `DC -= cost`. |
| `updateQueueStatus(q)` | Alg.1 Update_Queue_Status | 비었으면 WAITING (DC 보존), head 를 감당하면 ACTIVE (heap 삽입), 아니면 BLOCKED (목록 꼬리). |
| `startNewRound(now)` | Alg.2 새 라운드 | WAITING → INACTIVE (DC 재설정 정책 적용), PQ·PT·모든 PDC 재설정, BLOCKED 큐마다 `DC += ω` 후 적격이면 ACTIVE. 그래도 적격 큐가 없으면 필요한 라운드 수 K 만큼 한 번에 진행 (동치성 증명: 명세 §3.1). [v0.4] K 는 게이트를 통과하는 큐들 기준이며, 각 큐는 `min(K, k_q)` 라운드분만 받는다 (게이트 없으면 v0.3 과 동일). |
| `grantQuantum(q, n)` | — | 큐당 라운드당 quantum 1 회 적용을 강제 (위반 시 예외). |
| `checkInvariants()` (테스트용) | 명세 §2 | 모든 큐가 정확히 한 상태·한 구조에 속하는지 검증. |

### 3.5a `com.raphael.scheduling.budget` — Service Budget [v0.4]

| 클래스 | 메서드 | 로직 |
|---|---|---|
| `TokenBucket(rate, burst)` | `reopenTime(units, now)`, `admits`, `consume` | 가상 시간 토큰 버킷. 토큰을 μ단위 정수로 저장해 오차가 없다. 처음엔 가득 참. 하드 리밋 (빌려 쓸 수 없음). `consume` 이 부족하면 `false` (감사용). |
| `ServiceBudgetConfig` | `defaults()` | `otpWeight : advertisementWeight` = 클래스 **예약** (빌려 줄 수 있음), `otp/advertisementLimitBasisPoints` = 클래스 **하드 리밋**, `tenantRateBasisPoints`, `tenantBurst` = 테넌트 전역 **하드 리밋**. 기본 3 : 7, 리밋 없음, 테넌트 80%·burst 50 (실험 선택값). |
| `TenantBudget` | `reopenTime`, `consume`, `violations()` | 테넌트마다 토큰 버킷 하나. 그 테넌트의 모든 클래스 큐·샤드가 **같은 객체**를 공유하므로 어느 큐·샤드도 테넌트 예산 전체를 독립적으로 쓸 수 없다. `DispatchGate` 구현. |
| `TenantBudgetHolder` | `tenantBudget()` | 실행기가 독립 예산 감사를 켜는 데 쓴다. |
| `ServiceClassPolicy` | `nextEligibleTime(now)` | 클래스마다 (하위 WDEDF 가 지금 선택 가능 ∧ 클래스 리밋 토큰 있음) 이면 `now`, 아니면 가장 이른 미래 시각. |
| | `selectNext(now)` | 후보 클래스 중 smooth weighted round robin 으로 선택 (후보마다 credit += weight, 최대 credit 선택, 선택된 클래스 credit −= 후보 weight 합). 후보가 하나면 그 클래스가 남는 예약분을 빌려 쓴다. 선택 후 클래스 리밋 1 메시지와 테넌트 예산 cost 를 소비하고 aging 승격을 센다. |
| | `diagnostics()`, `mergeDiagnostics` | `class.<유형>.dispatched`, `class.borrowedSlots`, `class.limitDeferrals`, `agingPromotions` + 하위 WDEDF 진단 (min* 는 최솟값, max* 는 최댓값, 나머지는 합). |

### 3.5b `com.raphael.scheduling.sharding` — Shuffle Sharding 과 장애 [v0.4]

| 클래스 | 메서드 | 로직 |
|---|---|---|
| `ShardingConfig(shards, subsetSize, seed, failures)` | `withFailures(events)` | 장애 사건은 시각 순, 샤드별로 장애·복구가 번갈아야 하며 모든 장애는 복구되어야 한다 (메시지 보존). |
| `ShardAssignment` | `subset(tenantId)` | `seed` 와 테넌트 id 의 `String.hashCode()` 로 만든 난수열로 부분 Fisher–Yates → `subsetSize` 개 샤드 (오름차순). 같은 설정이면 항상 같은 배정. |
| `ShardPool` | `isAvailable`, `nextAvailableTime`, `occupy`, `fail`, `recover` | 샤드별 독립 용량 = provider 용량 / 샤드 수 (μs 미만 나머지 이월). 합계 = 시스템 용량. 전역 provider 한도는 별도로 계속 적용. 장애 중이거나 슬롯이 안 비었는데 점유하면 위반으로 센다. 샤드별 dispatch 수·장애 횟수·다운타임 기록. |
| `ShardedPolicy` | `enqueue(m)` | 배정 부분집합의 정상 샤드 중 대기가 가장 적은 샤드 (동률은 낮은 번호). 모두 장애면 대기가 가장 적은 배정 샤드에 넣고 복구를 기다린다. 메시지는 샤드를 옮기지 않는다. |
| | `nextEligibleTime(now)` | 정상이고 대기가 있는 샤드마다 `max(하위 정책 가능 시각, 샤드 슬롯 시각)` 의 최솟값. 장애 샤드는 제외 (복구 사건이 dispatch 를 다시 예약). |
| | `selectNext(now)` | 회전 포인터부터 (정상 ∧ 슬롯 비었음 ∧ 하위 정책 지금 선택 가능) 인 첫 샤드에서 꺼내고 샤드 슬롯을 점유한다. |
| | `fail`, `recover`, `resourceEvents` | `ResourceController` 구현. |
| | `diagnostics()` | `shardCapacityViolations`, `routedToFailedSubset`, `shard.<i>.routed/dispatches/failures/downtimeMicros` + 하위 진단 합산. |

### 3.5c `com.raphael.persistence` — CSV·JSON 저장 [v0.4]

| 클래스 | 메서드 | 로직 |
|---|---|---|
| `ResultWriter` | `write(baseDir, suite, runSettings, entries)` | `baseDir/<suite>-<UTC 시각>[-n]/` 를 새로 만들어 (기존 디렉터리는 덮어쓰지 않음) 7 개 파일을 쓴다. 모든 실행이 끝난 뒤 호출되므로 시간 측정에 포함되지 않는다. `Entry(scenario, workload, result, blastRadius)`. |
| `Json` (패키지 내부) | `write(value)` | 의존성 없는 JSON 직렬화. 실수는 `BigDecimal.toPlainString()` (로케일 무관), 값 없음은 `null`. |

### 3.6 `com.raphael.provider` — 외부 전송 provider

| 클래스 | 메서드 | 로직 |
|---|---|---|
| `ServiceModel` | — | `MESSAGE_RATE` (메시지당 `1s/capacity`, v0.1 의미), `COST_RATE` (메시지가 `cost/capacity` 초 동안 링크 점유, 논문 실험용). |
| `ProviderSimulator` | `nextAvailableTime()` | 다음 발송 가능 시각. |
| | `dispatch(now, cost)` | 점유 시간 계산 (μs 미만 나머지는 이월 → 누적 오차 없음). 반환값 = 배달 시각: MESSAGE_RATE `now + 지연`, COST_RATE `now + 전송시간 + 지연`. 이른 호출이나 1μs 미만 점유는 예외. |

### 3.7 `com.raphael.scenario` — 재현 가능한 입력

| 클래스 | 메서드 | 내용 |
|---|---|---|
| `Scenario` (record) | `tenantWeights()` | 이름, 설명, 테넌트, 메시지 (id 엄격 증가), 용량, 배달 지연, 관측 구간, 서비스 모델, 명시적 예약률. 생성 시 모두 검증. |
| `ScenarioGenerator` | `basicCorrectness()` (S1), `noisyNeighbor(n, tps)` (S2), `continuousOtp(...)` (S3), `weightedFairness(...)` (S4a/b) | v0.1/v0.2 시나리오 (변경 없음). |
| `MessageScenarios` | `intraTenantHeadOfLine` (S5), `tenantPopulation` (S6), `tenantPopulationOverload` (S6OVL), `weightVersusUrgency` (S7), `heterogeneousDeadlines` (S8), `competingUrgentQueues` (S9), `waitingCredit` (S10), `borrowingChurn` (S11), `urgentOverload` (S12), [v0.4] `prolongedWaiting` (S13, aging), `shardFailureWorkload` (F1, aggressor T00 + 15 테넌트) | 메시지 도메인. 각 메서드 Javadoc 에 파라미터 유도 근거가 있다. 장애 자체는 시나리오가 아니라 arm 의 샤드 설정으로 넣는다 (같은 워크로드로 장애 유무 비교). |
| `PaperScenarios` | `exampleOne` (P1), `exampleTwo` (P2), `fairSharing` (P3a), `weightedSharing` (P3b), `delayDifferentiation` (P4), `dwrrBaseQuantum` | 논문 검증 (COST_RATE, bit 단위). |
| `MessageListBuilder` | `add`, `build`, `exponentialGap` | (도착, 추가 순서) 로 안정 정렬 후 id 부여. Poisson 간격은 seed 고정 `Random`. |

### 3.8 `com.raphael.metrics` — 지표 수집 (출력 없음)

| 클래스 | 메서드 | 내용 |
|---|---|---|
| `MetricsCollector` | `onArrival/onDispatch/onDelivery` | 테넌트·유형·테넌트×유형 단위로 누적. 대기 지연 = dispatch − 도착, E2E = 배달 − 도착, slack = deadline − 지연 − dispatch, 논문 출발 위반 = dispatch > T̂_D. backlog 구간 추적, 관측 구간 서비스·간격·lag·단기 구간 cost, provider 용량 감사. |
| | `buildResult(...)` | 불변 `SimulationResult` 생성. |
| | `enableTenantBudgetAudit(rate, burst)` [v0.4] | 스케줄러와 **독립된** 테넌트별 토큰 버킷으로 dispatch 를 재검사해 예산 위반을 센다. |
| `SimulationResult` | `tenant(id)`, `messageType(t)`, `totalReceived/Dispatched/Delivered`, `isConserved`, `deliveredCostPerSecond` | 결정론적 결과 (시간 측정 없음 → `equals` 로 결정론 검증). [v0.4] `serviceBudgetViolations` 필드. |
| `TenantMetrics` | `deadlineMissRate`, `averageEndToEndLatency`, `isConserved` | v0.2 와 같은 정의 (골든 테스트로 보호). miss 율 분모 = 수신 수. |
| `GroupMetrics` | `deadlineMissRate`, `paperDepartureViolationRate`, `averageQueueLatency`, `averageEndToEndLatency` | v0.3 확장 지표 (업무 miss 와 논문 위반 분리). |
| `LatencySummary`, `DistributionSummary` | `p50/p95/p99/max`, `min/p1/...` | 정수 nearest-rank 백분위. 표본이 없으면 접근자가 예외 (0 으로 위장하지 않음). |
| `ObservationWindow(start, end, shortWindowLength)` | `contains`, `shortWindowCount` | 공정성 측정 구간 `[start, end)` 와 단기 하위 구간 길이. |
| `WindowedFairness` | `jainIndex`, `allTenantsBacklogged` | 가중치 정규화 Jain (count·cost), 단기 Jain 최소·평균, 테넌트별 몫·최대 간격·lag. 모든 테넌트가 구간 내내 backlog 일 때만 지수를 계산. |

### 3.9 `com.raphael.experiment` — 실험 프레임워크

| 클래스 | 메서드 | 내용 |
|---|---|---|
| `ExperimentConfig(id, label, policyFactory, settings)` | `fifo()`, `dwrr(q)`, `dwrr(id, label, q, topology)`, `wdedf(id, label, name, WdedfArm)`, `edf(id, label, mode)`, [v0.4] `with(id, label, extraSettings)` | 실험 arm. factory 는 실행마다 새 정책을 만든다. `WdedfArm` = 출발 정책 (PAPER/BUSINESS), Λ, 부채 정책, 토폴로지, base quantum, 선택적 ω_MIN. |
| `StandardArms` | `e2WdedfMessage`, `d1WdedfPaper`, `d2WdedfNoBorrowing`, `d3Edf`, `d4WdedfRetainDebt`, `d5DwrrClass`, `d6WdedfClass`, `messageDomain(q)`, `lambdaSweep(q, bp...)` | 표준 arm 묶음. E 시리즈 = 아키텍처 실험열, D 시리즈 = 진단. |
| `ArchitectureArms` [v0.4] | `e3(q, budget)`, `e4(q, budget, sharding)`, `e5(q, budget, sharding, agingThreshold)`, `e0ToE5(...)` | E3 = 클래스별 WDEDF (업무 목표, Λ=5%) + `ServiceClassPolicy` + 테넌트 예산. E4 = 샤드마다 E3 구조 + `ShardedPolicy`. E5 = E4 + aging. 테넌트×유형 큐 키 (`A/OTP`) 를 재사용하되 테넌트 가중치를 자식에게 나누지 않는다 (v0.3 S5 의 몫 누출 방지). 설정은 `settings` 맵에 기록된다. |
| `BlastRadius` [v0.4] | `compare(id, baseline, failure, aggressors)` | 같은 arm 의 장애 유무 비교. 비-aggressor 테넌트가 (miss 율 증가 > 1 %p, 분모 = 그 테넌트 수신 수) 또는 (E2E p99 > 기준 × 1.5 이면서 +1 s) 이면 영향받음. 영향 테넌트 수, 추가 miss 수, 테넌트별 비교. |
| `ScenarioReservations` | `forLayout`, `paperDepartureForMetrics` | 시나리오 → 예약 모델, 지표용 논문 T̂_D 함수. |
| `ExperimentRunner(warmup, runs)` | `runOnce`, `run`, `runAll` | §2 의 격리된 실행, 결정론·보존·용량 검증, warm-up 후 반복 측정. nanoTime 측정 편향을 보정값으로 함께 보고. [v0.4] 정책이 테넌트 예산을 가지면 독립 예산 감사를 켜고, 예산·샤드 용량 위반이 있으면 예외. |
| `ExperimentResult` | `medianWallClockNanos`, `minWallClockNanos`, `medianThreadCpuNanos`, ... | 결정론적 결과 + 반복별 `ExecutionProfile`. |
| `ExecutionProfile` | `schedulerElapsedNanosCorrected` | wall-clock, 스레드 CPU 시간 (ThreadMXBean), 스케줄러 경과 시간. |

### 3.10 `com.raphael.report` — 보고서

| 클래스 | 내용 |
|---|---|
| `ComparisonReport.render(scenario, focus, arms)` | v0.2 형식: 지표 행 × arm 열 + 기준 대비 Δ, Δ%. |
| `MultiArmReport.render(scenario, arms)` | v0.3 형식: arm 행 × 지표 열. 유형별·테넌트×유형별 품질, 테넌트 간 miss 분포, 관측 구간 공정성·몫·처리량, 총계·보존, 스케줄러 진단, 실행 시간 분산. 없는 값은 `n/a`. |
| `TextTable` | 고정폭 정렬 표. |

---

## 4. 빌드와 테스트

요구사항: **JDK 21** (Gradle toolchain 이 21 을 요구한다). 기본 `java` 가 17 이라면 `JAVA_HOME` 을
21 로 지정한다.

```bash
export JAVA_HOME=/Users/ariel/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home

./gradlew clean test        # 전체 테스트 (105 개, 약 5–10 초)
./gradlew build             # 컴파일 + 테스트 + 배포 아카이브
```

테스트 결과 HTML: `build/reports/tests/test/index.html`.

v0.4 기능만 빠르게 확인:

```bash
./gradlew test --tests '*V04*' --tests '*Sharding*' --tests '*TokenBucket*' \
               --tests '*WdedfGateAndAging*' --tests '*ResultWriter*'
```

골든 파일 재생성 (FIFO/DWRR 기존 결과를 **의도적으로** 바꿀 때만):

```bash
./gradlew test --tests '*HistoricalResultsGoldenTest' -Dgolden.record=true
```

---

## 5. 실행 방법

### 5.1 Gradle 로 실행

```bash
./gradlew run                                   # 모든 suite (약 90 초)
./gradlew run --args="--suite=history"          # v0.2 S1–S4b 보고서 + v0.3 arm
./gradlew run --args="--suite=paper"            # 논문 검증 P1–P4
./gradlew run --args="--suite=message"          # 메시지 도메인 S5–S12
./gradlew run --args="--suite=sweep"            # Λ 민감도
./gradlew run --args="--suite=history --scenario=s2 --runs=5 --warmup=2"

# [v0.4] E0–E5 통합 실행 + CSV·JSON 저장 (all 에는 포함되지 않음)
./gradlew run --args="--suite=v04 --quick=true"             # 스모크: warm-up 0, 측정 1, 축소 워크로드
./gradlew run --args="--suite=v04"                          # 전체 크기 (warm-up 1, 측정 3)
./gradlew run --args="--suite=v04 --output-dir=out --base-quantum=10"
```

### 5.2 배포 스크립트로 실행 (큰 heap 지정 가능)

```bash
./gradlew installDist
JAVA_OPTS="-Xmx6g" build/install/infobankAssignmentSimulator/bin/infobankAssignmentSimulator \
    --suite=message > message-run.txt
```

### 5.3 옵션

| 옵션 | 기본값 | 의미 |
|---|---|---|
| `--suite` | `all` | `all`, `history`, `paper`, `message`, `sweep`, [v0.4] `v04` (`all` 에 포함 안 됨) |
| `--quick` [v0.4] | `false` | v04: warm-up 0, 측정 1 회, 축소 워크로드 |
| `--output-dir` [v0.4] | `results` | v04: 결과 상위 디렉터리 (실행마다 새 하위 디렉터리) |
| `--scenario` | `all` | history 에서 실행할 v0.2 시나리오: `s1`, `s2`, `s3`, `s4` |
| `--campaign` | 800000 | S2·S3 광고 수 |
| `--tps` | 450 | S2·S3·S4 provider 용량 |
| `--otp-interval-ms` | 333 | S3 OTP 간격 (500 ms 는 450 TPS 슬롯과 위상이 맞아 분포가 붕괴한다) |
| `--otp-duration-s` | 1200 | S3 OTP 생성 기간 |
| `--fairness-messages` | 200000 | S4 테넌트당 메시지 수 |
| `--window-start-s`, `--window-end-s` | 30, 330 | S4 관측 구간 |
| `--base-quantum` | 1 (v04 는 10) | DWRR/WDEDF 기본 quantum |
| `--warmup`, `--runs` | 1, 3 | arm 마다 warm-up 횟수, 측정 반복 횟수 |

알 수 없는 옵션이나 suite 는 예외로 거부된다.

### 5.4 보고서 읽는 법 (`MultiArmReport`)

* **Message type OTP / ADVERTISEMENT**: 유형별 수신·배달, `miss rate` (업무 deadline), `paper T̂_D viol`
  (논문 식 2 기준 출발 위반), E2E p50/p99/max, 최대 대기, `slack min` (음수 = dispatch 순간 이미 실행 불가능),
  `lateness max`, 마지막 배달.
* **Tenant/type** 또는 **Per-tenant spread**: 테넌트가 8 개 이하면 테넌트×유형 표, 많으면 테넌트 간 miss 율
  최소·최대와 miss 가 난 테넌트 수.
* **Service in window**: 가중치 정규화 Jain (count, cost), 단기 Jain 최소·평균, 최대 서비스 간격, 이상 대비
  최대 lag, 테넌트별 몫·처리량. backlog 조건이 깨지면 지수는 `n/a`.
* **Totals**: 보존 여부 (`yes` 여야 함), 용량 위반 (0 이어야 함), 연산/선택.
* **Scheduler diagnostics**: 라운드 수, 건너뛴 라운드, 차입 시도·성공·거절 사유, PQ, 부채, heap 연산 등.
* **Execution**: wall-clock 중앙값·최소·최대·분산 (시뮬레이터 비용이며 운영 비용이 아니다).

---

### 5.5 결과 파일 [v0.4]

`--suite=v04` 는 `<output-dir>/v04[-quick]-<UTC 시각>[-n]/` 에 다음을 쓴다.

| 파일 | 내용 |
|---|---|
| `manifest.json` | 시뮬레이터 버전 (0.4.0), 스키마 버전 (1), suite, 생성 시각, Java 버전, 실행 설정, 시간 단위, 파일 목록 |
| `experiment-config.json` | 실험별 시나리오 id, arm id, arm 설정 (스케줄러·예산·샤드·장애·aging), 워크로드 (seed 포함) |
| `experiment-results.json` | 실험별 총계·위반·테넌트·메시지 그룹·공정성·스케줄러 진단·반복별 실행 시간 (ns)·영향 범위 |
| `experiment-summary.csv` | 실험 한 줄 요약 (OTP/광고 miss 율·p99·최대 대기, 캠페인 완료, Jain, aging 승격, 영향 테넌트 등) |
| `tenant-metrics.csv` | 실험 × 테넌트 |
| `message-type-metrics.csv` | 실험 × (유형 / 테넌트×유형) |
| `scheduler-metrics.csv` | 긴 형식 (지표, 값): 스케줄러 진단, `shard.N.utilization` |

규칙: UTF-8, 고정 필드 이름 (snake_case), 가상 시간은 정수 μs, 실행 시간은 정수 ns, 숫자는 로케일
무관, 값 없음은 CSV 빈 칸 / JSON `null`.

### 5.6 E0–E5 실험 arm [v0.4]

| ID | 구성 |
|---|---|
| E0 | FIFO |
| E1 | DWRR |
| E2 | WDEDF (업무 목표, Λ=5%) |
| E3 | E2 + 서비스 클래스별 큐 + Service Budget (예약 3:7, 테넌트 80%) |
| E4 | E3 + Shuffle Sharding (4 샤드, 부분집합 2, seed 42) |
| E5 | E4 + Aging (threshold 30 s) |
| E4-F, E5-F | F1 에서 E4/E5 + 샤드 1 장애 [10 s, 40 s) |

기본값은 최적값이 아니라 실험 선택값이다. 한계와 스모크 결과의 해석은 `docs/v0.4-notes.md` 참고.

## 6. 코드에서 사용하기

### 6.1 한 시나리오에서 여러 정책 비교

```java
Scenario scenario = MessageScenarios.weightVersusUrgency(60, 1_000_000L);
ExperimentRunner runner = new ExperimentRunner(1, 3);
List<ExperimentConfig> arms = List.of(
    ExperimentConfig.dwrr(10),
    StandardArms.e2WdedfMessage(10),
    StandardArms.d2WdedfNoBorrowing(10));
List<ExperimentResult> results = runner.runAll(scenario, arms);
System.out.print(MultiArmReport.render(scenario, results));
```

### 6.2 dispatch 순서 추적

```java
new ExperimentRunner(0, 1).runOnce(scenario, ExperimentConfig.fifo(),
    new SimulationObserver() {
      @Override
      public void onDispatch(Message m, long now) {
        System.out.println(m.tenantId() + "#" + m.id() + " @" + now);
      }
    });
```

### 6.3 새 시나리오 만들기

```java
List<Tenant> tenants = List.of(new Tenant("A", 1), new Tenant("B", 3));
List<Message> messages = List.of(
    new Message(0, "A", MessageType.ADVERTISEMENT, 0, 3_600_000_000L, 1),
    new Message(1, "B", MessageType.OTP, 1_000_000, 61_000_000, 1));   // id 는 엄격 증가
Scenario scenario = new Scenario("X1", "custom", tenants, messages,
    450, 100_000, Optional.of(new ObservationWindow(0, 10_000_000)));
```

### 6.4 WDEDF 변형 직접 구성

```java
ExperimentConfig custom = ExperimentConfig.wdedf("X", "WDEDF Λ=10%, debt kept", "WDEDF-X",
    new ExperimentConfig.WdedfArm(
        ExperimentConfig.DepartureMode.BUSINESS,  // 또는 PAPER (식 2)
        1_000,                                    // Λ = 10%
        IdleDebtPolicy.RETAIN_DEBT,
        QueueLayout.Topology.TENANT,              // 또는 TENANT_AND_TYPE
        10,                                       // base quantum
        OptionalLong.empty()));                   // 명시적 ω_MIN (논문 시나리오용)
```

### 6.5 E3–E5 구성과 결과 저장 [v0.4]

```java
ServiceBudgetConfig budget = new ServiceBudgetConfig(
    3, 7,          // 클래스 예약 OTP : 광고 (빌려 줄 수 있음)
    2_000, 0,      // 클래스 하드 리밋: OTP ≤ 용량의 20%, 광고 없음
    5_000, 50);    // 테넌트 전역 하드 리밋: 용량의 50%, burst 50
ShardingConfig sharding = new ShardingConfig(8, 2, 42, List.of(
    new ResourceController.ResourceEvent(10_000_000, 3, true),    // 10 s 에 샤드 3 장애
    new ResourceController.ResourceEvent(40_000_000, 3, false))); // 40 s 에 복구

List<ExperimentConfig> arms = ArchitectureArms.e0ToE5(10, budget, sharding, 30_000_000);
Scenario scenario = MessageScenarios.shardFailureWorkload(20_000, 300);
List<ExperimentResult> results = new ExperimentRunner(0, 1).runAll(scenario, arms);

List<ResultWriter.Entry> entries = new ArrayList<>();
for (ExperimentResult r : results) {
  entries.add(new ResultWriter.Entry(scenario, Map.of("seed", "none"), r, null));
}
Path dir = ResultWriter.write(Path.of("results"), "custom", Map.of("quick", "true"), entries);
```

영향 범위: 같은 arm 을 장애 없는 `ShardingConfig` 로 한 번 더 실행한 뒤
`BlastRadius.compare("E4-F", baseline.result(), failed.result(), Set.of("T00"))`.

### 6.6 새 스케줄링 정책 추가

1. `SchedulingPolicy` 를 구현한다. `selectNext` 는 `nextEligibleTime(now) == now` 일 때 반드시 메시지를
   반환해야 한다. 일시적으로 보낼 수 없는 상태 (예산·자원) 가 있으면 `nextEligibleTime` 을 재정의해 다음
   가능 시각을 알린다. 시간·provider·지표는 건드리지 않는다.
2. 필요하면 `diagnostics()` 로 알고리즘 계수를 보고한다.
3. `new ExperimentConfig(id, label, scenario -> new MyPolicy(...))` 로 arm 을 만든다. 엔진은 수정하지 않는다.
4. 테스트에서 `Runs.trace(...)` 로 보존·용량·결정론을 확인한다 (`WdedfIntegrationTest` 참고).

---

## 7. 문제 해결

| 증상 | 원인·해결 |
|---|---|
| `UnsupportedClassVersionError (class file version 65.0)` | JDK 17 로 실행됨. `JAVA_HOME` 을 JDK 21 로 지정. |
| `OutOfMemoryError` (전체 suite) | `JAVA_OPTS="-Xmx6g"` 로 배포 스크립트 실행. 테스트는 Gradle 이 3 GB 를 쓴다. |
| `Non-deterministic result for ...` | 정책이 실행 간 상태를 공유하거나 비결정적 자료구조를 쓴다. factory 가 매번 새 객체를 만드는지 확인. |
| `Message conservation violated` / `Provider capacity exceeded` | 정책이 메시지를 잃거나 중복했거나 provider 를 우회했다. |
| `... returned no message while N messages are pending` | 정책이 작업 보존 계약을 어겼다 (§3.4). |
| `Quantum applied twice in round ...` | WDEDF 상태기계 불변식 위반 (버그). |
| `Service budget or shard capacity violated in ...` [v0.4] | 정책이 예산 게이트나 샤드 슬롯을 우회했다 (버그). |
| `reported a past eligibility time` [v0.4] | `nextEligibleTime` 이 `now` 보다 과거를 반환했다 (계약 위반). |
| `Simulation ended with N undispatched messages` [v0.4] | 메시지가 영영 선택 가능해지지 않았다: 예산 burst 가 메시지 cost 보다 작거나, 장애 샤드가 복구되지 않았다 (`ShardingConfig` 는 복구 없는 장애를 거부한다). |
| `tenantBurst must cover the largest message cost` [v0.4] | 테넌트 burst 를 시나리오의 최대 cost 이상으로 설정. |
