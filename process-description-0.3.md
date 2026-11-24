# 프로세스 설명서 — 멀티테넌트 메시지 전송 시뮬레이터 (v0.3)

이 문서는 시뮬레이터가 **어떤 순서로 동작하는지**, **패키지와 메서드가 각각 무엇을 하는지**, 그리고
**어떻게 빌드·실행·확장하는지** 를 설명한다.

관련 문서:
* `docs/wdedf-specification.md` — WDEDF 알고리즘 명세와 논문 해석 (A1–A26)
* `docs/v0.3-results.md` — 실험 결과와 결론
* `docs/results/v0.3-full-run.txt` — 결과 문서의 근거가 된 실제 실행 출력

---

## 1. 한눈에 보기

시뮬레이터는 **단일 스레드, 결정론적 이산 사건 시뮬레이션** 이다. 실제 시간을 기다리지 않고, 가상
시계(μs 단위)를 사건(event) 시각으로 건너뛰며 진행한다. 같은 입력은 항상 같은 결과를 낸다.

```
Scenario (불변 입력: 테넌트, 메시지, provider 용량·지연)
   │
   ▼
ExperimentRunner ── 실행마다 새로 생성 ──► SchedulingPolicy (FIFO / DWRR / WDEDF / EDF)
   │                                       ProviderSimulator
   │                                       MetricsCollector
   ▼                                       SimulationEngine (VirtualClock + 사건 큐)
SimulationEngine.run()
   │   ARRIVAL ──► metrics.onArrival ─► policy.enqueue ─► (필요하면) DISPATCH_READY 예약
   │   DISPATCH_READY ──► policy.selectNext ─► metrics.onDispatch ─► provider.dispatch
   │                       ─► DELIVERED 예약 ─► 다음 DISPATCH_READY 예약
   │   DELIVERED ──► metrics.onDelivery
   ▼
SimulationResult (결정론적 지표) + ExecutionProfile (시간 측정)
   │
   ▼
ComparisonReport / MultiArmReport (텍스트 보고서)
```

핵심 원칙:
* **엔진** 은 시간과 사건만 관리하고, **정책** 은 "다음에 어떤 메시지를 보낼지" 만 정하며, **provider**
  는 "언제 보낼 수 있는지" 만 정한다. **지표** 는 수집만 하고 출력하지 않는다.
* 모든 실행(warm-up 포함)은 엔진·정책·provider·지표 객체를 **새로** 만든다. 공유되는 것은 불변
  `Scenario` 뿐이다.

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
   * **DISPATCH_READY**: `policy.selectNext(now)` → 지표 기록 → `provider.dispatch(now, cost)` 가 돌려준
     배달 시각에 `DELIVERED` 예약 → 정책에 메시지가 남아 있으면 다음 `DISPATCH_READY` 예약.
   * **DELIVERED**: 지표 기록 (deadline 을 넘겨도 배달하고 miss 로 집계한다).
5. 사건 큐가 비면 `metrics.buildResult(...)` 로 불변 `SimulationResult` 를 만든다.
6. Runner 가 **검증** 한다: 수신 = dispatch = 배달 (메시지 보존), provider 용량 감사 위반 0.
7. 반복 실행(`run`)에서는 모든 측정 반복의 `SimulationResult` 가 `equals` 여야 한다 (결정론 검증).

엔진의 안전장치:
* 정책이 메시지를 가지고 있는데 `selectNext` 가 `null` 을 돌려주면 즉시 예외 (무한 루프 방지).
* 같은 가상 시각에 두 번 dispatch 하면 즉시 예외.
* 엔진은 일회용이다 (`run()` 두 번 호출 시 예외).

---

## 3. 패키지와 클래스·메서드 설명

### 3.1 `com.raphael` — 진입점

| 클래스 | 메서드 | 동작 |
|---|---|---|
| `SimulatorApplication` | `main(String[])` | `--key=value` 옵션을 파싱하고 suite 별로 실험을 실행·출력한다 (§5). |
| | `printDispatchTrace` | S1 에서 OTP 가 dispatch 순서의 몇 번째인지 출력. |
| | `parseOptions` / `intOption` / `longOption` | 알 수 없는 옵션은 예외로 거부. |
| `WdedfExperimentSuite` | `history(scenarios, q)` | 기존 S1–S4b 에 v0.3 arm (E0, E1, E2, D1–D3) 을 실행. |
| | `paper()` | 논문 검증 P1–P4 실행, P1/P2 dispatch 추적, P4 흐름별 지연 표. |
| | `message(q)` | 메시지 도메인 S5–S12 실행. |
| | `lambdaSweep()` | 차입 비율 Λ = 0, 1, 5, 10, 20% 민감도 (S7, S11). |

### 3.2 `com.raphael.core` — 시뮬레이션 엔진

| 클래스 | 메서드 | 동작 |
|---|---|---|
| `VirtualClock` | `now()`, `advanceTo(t)` | μs 가상 시계. 뒤로 가면 예외. 상수 `MICROS_PER_SECOND`, `MICROS_PER_MILLISECOND`. |
| `EventType` | — | `DELIVERED`, `ARRIVAL`, `DISPATCH_READY` (선언 순서가 같은 시각의 처리 순서). |
| `SimulationEvent` | `ORDER` | 시각 → 유형 → 삽입 번호의 전순서 비교자. |
| `SimulationObserver` | `onArrival/onDispatch/onDelivery` | 읽기 전용 추적 hook (테스트·추적용). 기본은 `NONE`. |
| `SimulationEngine` | `scheduleArrival(m)` | 도착 사건 예약 (`run` 전에만). |
| | `run()` | §2 의 사건 루프. 끝나면 `SimulationResult` 반환. |
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
| `selectNext(now)` | 다음 메시지를 꺼내 반환. **비어 있지 않으면 반드시 메시지를 반환** (작업 보존 계약). 비어 있으면 `IllegalStateException`. |
| `isEmpty()`, `pendingCount()` | 대기 메시지 |
| `operationCount()` | 알고리즘 단계 수 (CPU 명령 수 아님) |
| `diagnostics()` | 정책별 계수 (WDEDF: 라운드, 차입, heap 연산 등) |

| 클래스 | 로직 |
|---|---|
| `FifoScheduler` (E0) | 단일 `ArrayDeque`. 테넌트·가중치·cost 무시. O(1). |
| `DwrrScheduler` (E1) | 큐별 FIFO (`TenantQueue`) + active ring (`ArrayDeque`). 방문 시작에 `deficit += baseQuantum × weight`, head cost ≤ deficit 인 동안 발송 (방문은 여러 provider 슬롯에 걸쳐 유지). 부족하면 ring 꼬리로 회전하며 deficit 보존. 큐가 비면 ring 에서 빠지고 deficit = 0. 한 바퀴 내내 아무도 못 보내면 필요한 빈 라운드를 한 번에 건너뛴다 (`skipIdleRounds`). 분류기 생성자로 큐 토폴로지를 바꿀 수 있다. |
| `TenantQueue` | DWRR 의 큐별 상태 (메시지 deque, quantum, deficit, active). |
| `EdfScheduler` (D3, 진단) | 모든 메시지를 하나의 heap 에 넣는 전역 EDF. 출발 기준 시각 → 도착 → id 순. 테넌트 공정성 없음. |
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
| `WdedfQueue` | `push/pop/headMessage/headDeparture/headCost` | 메시지·출발 시각을 원형 배열 두 개에 저장 (메시지당 객체 할당 없음). DC, PDC, 상태, blocked 목록 링크를 가진다. |
| `BlockedList` | `append`, `unlink`, `tokenQueue`, `removeTokenQueue`, `advanceToken`, `resetToken` | 논문의 Blocked List + 선점 token PT. 큐에 내장된 이중 연결 리스트라 모든 연산 O(1). 인덱스 의미: 제거 후 PT 는 다음 큐, 끝을 넘으면 0. |
| `WdedfScheduler` | 아래 표 | 상태기계·라운드·차입을 한 구현으로 공유 (모든 WDEDF 변형이 같은 클래스). |

`WdedfScheduler` 의 메서드별 로직 (논문 대응):

| 메서드 | 논문 | 동작 |
|---|---|---|
| 생성자 | Alg.1 Initialise | DC=0, 모든 큐 INACTIVE, Ω = Σω, PQ = floor(Ω×Λ), 라운드 1. |
| `enqueue(m)` | Alg.1 En-queue | 출발 시각 계산·저장 후 큐에 추가. INACTIVE 면 `DC += ω` (논문 `DC = ω`) 후 상태 갱신, WAITING 이면 **보존된 DC 재사용** 후 상태 갱신. ACTIVE/BLOCKED 는 head 가 안 바뀌므로 그대로. |
| `selectNext(now)` | Alg.2 Get_Packet | ACTIVE heap 이 비었으면 `startNewRound()`. heap 의 최소 head (출발 시각 → 도착 → id → 큐 번호) 를 후보로 보고, BLOCKED 가 있으면 먼저 `tryBorrow`. 차입이 없으면 heap 에서 꺼내 `dequeueFrom` + `updateQueueStatus`. |
| `tryBorrow(activeDeparture)` | Alg.2 차입 분기 | PT 가 가리키는 BLOCKED 큐 **하나만** 검사: `activeHead.T̂_D > blocked.T̂_D` (엄격), `cost ≤ PQ`, `cost ≤ PDC`. 성공하면 PQ·PDC·**일반 DC** 에서 차감 (DC 음수 = 부채), 실패하면 사유를 기록하고 PT 한 칸 진행. |
| `dequeueFrom(q)` | Alg.1 De-queue | head 를 꺼내 `DC -= cost`. |
| `updateQueueStatus(q)` | Alg.1 Update_Queue_Status | 비었으면 WAITING (DC 보존), head 를 감당하면 ACTIVE (heap 삽입), 아니면 BLOCKED (목록 꼬리). |
| `startNewRound()` | Alg.2 새 라운드 | WAITING → INACTIVE (DC 재설정 정책 적용), PQ·PT·모든 PDC 재설정, BLOCKED 큐마다 `DC += ω` 후 적격이면 ACTIVE. 그래도 적격 큐가 없으면 필요한 라운드 수 K 의 최솟값만큼 한 번에 진행 (동치성 증명: 명세 §3.1). |
| `grantQuantum(q, n)` | — | 큐당 라운드당 quantum 1 회 적용을 강제 (위반 시 예외). |
| `checkInvariants()` (테스트용) | 명세 §2 | 모든 큐가 정확히 한 상태·한 구조에 속하는지 검증. |

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
| `MessageScenarios` | `intraTenantHeadOfLine` (S5), `tenantPopulation` (S6), `tenantPopulationOverload` (S6OVL), `weightVersusUrgency` (S7), `heterogeneousDeadlines` (S8), `competingUrgentQueues` (S9), `waitingCredit` (S10), `borrowingChurn` (S11), `urgentOverload` (S12) | 메시지 도메인. 각 메서드 Javadoc 에 파라미터 유도 근거가 있다. |
| `PaperScenarios` | `exampleOne` (P1), `exampleTwo` (P2), `fairSharing` (P3a), `weightedSharing` (P3b), `delayDifferentiation` (P4), `dwrrBaseQuantum` | 논문 검증 (COST_RATE, bit 단위). |
| `MessageListBuilder` | `add`, `build`, `exponentialGap` | (도착, 추가 순서) 로 안정 정렬 후 id 부여. Poisson 간격은 seed 고정 `Random`. |

### 3.8 `com.raphael.metrics` — 지표 수집 (출력 없음)

| 클래스 | 메서드 | 내용 |
|---|---|---|
| `MetricsCollector` | `onArrival/onDispatch/onDelivery` | 테넌트·유형·테넌트×유형 단위로 누적. 대기 지연 = dispatch − 도착, E2E = 배달 − 도착, slack = deadline − 지연 − dispatch, 논문 출발 위반 = dispatch > T̂_D. backlog 구간 추적, 관측 구간 서비스·간격·lag·단기 구간 cost, provider 용량 감사. |
| | `buildResult(...)` | 불변 `SimulationResult` 생성. |
| `SimulationResult` | `tenant(id)`, `messageType(t)`, `totalReceived/Dispatched/Delivered`, `isConserved`, `deliveredCostPerSecond` | 결정론적 결과 (시간 측정 없음 → `equals` 로 결정론 검증). |
| `TenantMetrics` | `deadlineMissRate`, `averageEndToEndLatency`, `isConserved` | v0.2 와 같은 정의 (골든 테스트로 보호). miss 율 분모 = 수신 수. |
| `GroupMetrics` | `deadlineMissRate`, `paperDepartureViolationRate`, `averageQueueLatency`, `averageEndToEndLatency` | v0.3 확장 지표 (업무 miss 와 논문 위반 분리). |
| `LatencySummary`, `DistributionSummary` | `p50/p95/p99/max`, `min/p1/...` | 정수 nearest-rank 백분위. 표본이 없으면 접근자가 예외 (0 으로 위장하지 않음). |
| `ObservationWindow(start, end, shortWindowLength)` | `contains`, `shortWindowCount` | 공정성 측정 구간 `[start, end)` 와 단기 하위 구간 길이. |
| `WindowedFairness` | `jainIndex`, `allTenantsBacklogged` | 가중치 정규화 Jain (count·cost), 단기 Jain 최소·평균, 테넌트별 몫·최대 간격·lag. 모든 테넌트가 구간 내내 backlog 일 때만 지수를 계산. |

### 3.9 `com.raphael.experiment` — 실험 프레임워크

| 클래스 | 메서드 | 내용 |
|---|---|---|
| `ExperimentConfig(id, label, policyFactory)` | `fifo()`, `dwrr(q)`, `dwrr(id, label, q, topology)`, `wdedf(id, label, name, WdedfArm)`, `edf(id, label, mode)` | 실험 arm. factory 는 실행마다 새 정책을 만든다. `WdedfArm` = 출발 정책 (PAPER/BUSINESS), Λ, 부채 정책, 토폴로지, base quantum, 선택적 ω_MIN. |
| `StandardArms` | `e2WdedfMessage`, `d1WdedfPaper`, `d2WdedfNoBorrowing`, `d3Edf`, `d4WdedfRetainDebt`, `d5DwrrClass`, `d6WdedfClass`, `messageDomain(q)`, `lambdaSweep(q, bp...)` | 표준 arm 묶음. E 시리즈 = 아키텍처 실험열, D 시리즈 = 진단. |
| `ScenarioReservations` | `forLayout`, `paperDepartureForMetrics` | 시나리오 → 예약 모델, 지표용 논문 T̂_D 함수. |
| `ExperimentRunner(warmup, runs)` | `runOnce`, `run`, `runAll` | §2 의 격리된 실행, 결정론·보존·용량 검증, warm-up 후 반복 측정. nanoTime 측정 편향을 보정값으로 함께 보고. |
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

./gradlew clean test        # 전체 테스트 (89 개, 약 5–10 초)
./gradlew build             # 컴파일 + 테스트 + 배포 아카이브
```

테스트 결과 HTML: `build/reports/tests/test/index.html`.

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
| `--suite` | `all` | `all`, `history`, `paper`, `message`, `sweep` |
| `--scenario` | `all` | history 에서 실행할 v0.2 시나리오: `s1`, `s2`, `s3`, `s4` |
| `--campaign` | 800000 | S2·S3 광고 수 |
| `--tps` | 450 | S2·S3·S4 provider 용량 |
| `--otp-interval-ms` | 333 | S3 OTP 간격 (500 ms 는 450 TPS 슬롯과 위상이 맞아 분포가 붕괴한다) |
| `--otp-duration-s` | 1200 | S3 OTP 생성 기간 |
| `--fairness-messages` | 200000 | S4 테넌트당 메시지 수 |
| `--window-start-s`, `--window-end-s` | 30, 330 | S4 관측 구간 |
| `--base-quantum` | 1 | DWRR/WDEDF 기본 quantum (history·message suite) |
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

### 6.5 새 스케줄링 정책 추가

1. `SchedulingPolicy` 를 구현한다. `selectNext` 는 비어 있지 않으면 반드시 메시지를 반환해야 한다.
   시간·provider·지표는 건드리지 않는다.
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
