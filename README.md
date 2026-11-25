# 시뮬레이터 동작 설명서 (v1.0) — `SimulatorApplication` 기점

이 문서는 `SimulatorApplication.main()` 에서 출발해, 시뮬레이션 한 번이 끝나고 결과가 출력·저장될
때까지 실행되는 **모든 로직을 호출 순서대로** 설명한다. 뒤쪽 (§11) 에는 Service Budget 설정처럼 시뮬레이터를
실행하는 데 반드시 알아야 하는 설정을 따로 정리했다.

* 이전 문서: `process-description-0.3.md`, `process-description-0.4.md` (패키지별 참조표)
* 알고리즘 명세: `docs/wdedf-specification.md` · 결과: `docs/v0.4-results.md`, `docs/v1.0-results.md`

---

## 0. 한 장 요약

```
SimulatorApplication.main(args)
 ├─ parseOptions(args)                        --key=value 파싱, 모르는 옵션은 예외
 ├─ suite 선택 ──┬─ history  → ComparisonReport (FIFO/DWRR) + WdedfExperimentSuite.history()
 │               ├─ paper    → WdedfExperimentSuite.paper()      (논문 검증 P1–P4)
 │               ├─ message  → WdedfExperimentSuite.message()    (S5–S12)
 │               ├─ sweep    → WdedfExperimentSuite.lambdaSweep()
 │               ├─ all      → 위 네 개 전부
 │               ├─ v04      → V04Suite.run()   (E0–E5 + CSV/JSON)
 │               └─ v10      → V10Suite.run()   (최종 평가 + CSV/JSON)
 │
 └─ 각 suite:  Scenario 생성 ──► ExperimentRunner.runAll(scenario, arms)
                                   └─ arm 마다 run(): warm-up N 회 + 측정 M 회
                                        └─ runOnce():
                                             policy   = config.policyFactory().apply(scenario)
                                             provider = new ProviderSimulator(...)
                                             metrics  = new MetricsCollector(...)
                                             engine   = new SimulationEngine(policy, provider, metrics, ...)
                                             engine.scheduleArrival(모든 메시지)
                                             result   = engine.run()       ◄── 이산 사건 루프
                                             validate(result)              보존·용량·예산·샤드 검증
                ◄── List<ExperimentResult>
               MultiArmReport / ComparisonReport 로 콘솔 출력
               (v04·v10) ResultWriter.write(...) 로 CSV·JSON 저장
```

핵심 원칙:
1. **가상 시간** (μs 정수) 만 쓴다. 실제로 기다리지 않는다. 단일 스레드, 결정론적이다.
2. **역할 분리**: 엔진 = 시간과 사건, 정책 = "무엇을 보낼지", provider·예산·샤드 = "언제 보낼 수 있는지",
   지표 = 수집만, 보고서·저장 = 출력만.
3. **격리**: 모든 실행 (warm-up 포함) 은 정책·provider·지표·엔진을 새로 만든다. 공유되는 것은 불변 `Scenario`
   뿐이다.

---

## 1. 진입점 — `SimulatorApplication.main`

### 1.1 옵션 파싱

`parseOptions(args)` 는 모든 인자가 `--key=value` 형식인지, key 가 `KNOWN_OPTIONS` 에 있는지 확인한다.
아니면 `IllegalArgumentException` 으로 즉시 중단한다. 이어서 공통 값을 읽는다.

| 옵션 | 기본값 | 적용 대상 |
|---|---|---|
| `--suite` | `all` | 실행할 묶음 (`all`, `history`, `paper`, `message`, `sweep`, `v04`, `v10`) |
| `--scenario` | `all` | history 의 v0.2 시나리오 선택 (`s1`–`s4`) |
| `--campaign`, `--tps` | 800000, 450 | **history 의 S2·S3·S4 에만** 적용 |
| `--otp-interval-ms`, `--otp-duration-s` | 333, 1200 | history 의 S3 |
| `--fairness-messages`, `--window-start-s`, `--window-end-s` | 200000, 30, 330 | history 의 S4 |
| `--base-quantum` | history·message: 1 / v04: 10 | DWRR·WDEDF 기본 quantum. **v10 은 무시** (코드 상수 10) |
| `--warmup`, `--runs` | 1, 3 | arm 마다 warm-up 횟수와 측정 횟수 |
| `--quick` | false | v04·v10 전용: warm-up 0, 측정 1, 대형 워크로드 축소 |
| `--output-dir` | `results` | v04·v10 전용: 결과 파일 상위 디렉터리 |

> 주의: v04·v10 suite 는 `--tps`, `--campaign` 등을 쓰지 않는다. 그 suite 의 워크로드와 Service Budget·샤딩·
> Aging 설정은 코드에 있다 (§11 참고).

### 1.2 suite 분기

* **v04 / v10**: `quick` 여부에 따라 `ExperimentRunner(warmup, runs)` 를 만들고, 실행 설정 (quick, warmup,
  runs, base quantum) 을 `manifest.json` 에 기록할 맵에 담은 뒤 `V04Suite.run()` 또는 `V10Suite.run()` 을
  호출하고 종료한다.
* **그 외**: `ExperimentRunner(--warmup, --runs)` 하나와 `WdedfExperimentSuite` 를 만든 뒤,
  * `history` (또는 `all`): S1–S4b 를 E0 FIFO·E1 DWRR 로 실행해 `ComparisonReport` (v0.2 형식) 를 출력하고,
    S1 은 `printDispatchTrace` 로 OTP 가 몇 번째로 나갔는지 보여 준다. 이어서 같은 시나리오에 v0.3 arm
    (E0, E1, E2, D1–D3) 을 `MultiArmReport` 로 출력한다.
  * `paper`, `message`, `sweep` (또는 `all`): 해당 메서드를 호출한다.

---

## 2. 실험 묶음 (suite) — 무엇을 어떤 순서로 실행하는가

| suite 클래스 | 메서드 | 내용 |
|---|---|---|
| `WdedfExperimentSuite` | `history` | S1–S4b × (E0, E1, E2, D1 WDEDF-PAPER, D2 WDEDF-NB, D3 EDF) |
| | `paper` | P1 Example 1, P2 Example 2, P3a/P3b 공정 공유, P4 지연 차별화 (COST_RATE, bit 단위) |
| | `message` | S5 테넌트 내부 HOL, S6 테넌트 집단, S6OVL, S7, S8, S9, S10, S11, S12 |
| | `lambdaSweep` | S7·S11 에서 Λ = 0/1/5/10/20% |
| `V04Suite` | `run` | S2, S3, S5, S12, S13 × E0–E5; F1 × (E0–E5, E4-F, E5-F) + 장애 영향 범위; CSV·JSON 저장 |
| `V10Suite` | `run` | P1 예약 비율 (S12 × 부하 0.8/1.0/1.2), P2 샤드 구성 (S2, F1), P3 장애 격리 (F1 × seed 1–10), P4 WDEDF vs DWRR (S7, S8, S6); CSV·JSON 저장 |

모든 suite 는 같은 패턴을 반복한다: **시나리오 생성 → `runner.runAll(scenario, arms)` → 보고서 출력 (→ 저장
항목 추가)**. 이하 각 단계를 순서대로 설명한다.

---

## 3. 단계 1 — 시나리오 생성 (`com.raphael.scenario`)

### 3.1 `Scenario` (불변 입력)

| 필드 | 의미 |
|---|---|
| `name`, `description` | 식별자 (예: `S12-load1.0`), 설명 |
| `tenants` | `Tenant(id, weight)` 목록 (보고 순서) |
| `messages` | `Message` 목록. **id 가 엄격히 증가해야** 하며 목록 순서가 같은 시각 도착의 처리 순서가 된다 |
| `providerCapacity` | 초당 처리량 (MESSAGE_RATE: 메시지, COST_RATE: cost 단위) |
| `deliveryDelay` | 고정 외부 배달 지연 (μs) |
| `observationWindow` | 공정성 측정 구간 (선택) |
| `serviceModel` | `MESSAGE_RATE` (기본) / `COST_RATE` (논문 실험) |
| `reservationRates` | 논문 실험용 명시 예약률 (비어 있으면 가중치 비례) |

생성자에서 검증한다: 테넌트 중복 금지, 메시지 테넌트가 등록되어 있어야 함, id 증가, 용량 범위, 지연 ≥ 0.

`Message(id, tenantId, type, arrivalTime, deadline, cost)`: `type` 은 `OTP` / `ADVERTISEMENT`, `cost` ≥ 1.
`missesDeadline(t)` 는 `t > deadline` 일 때만 참 (정확히 deadline 에 배달되면 성공).

### 3.2 생성기

* `ScenarioGenerator`: v0.1–v0.2 시나리오 (S1–S4). 테넌트 A (광고), B (OTP).
* `MessageScenarios`: 메시지 도메인 (S5–S13, F1). 내부적으로 `MessageListBuilder` 를 써서 메시지를
  (도착 시각, 추가 순서) 로 정렬한 뒤 id 를 0 부터 부여한다. Poisson 도착은 seed 고정 `java.util.Random`
  으로 만든다 → **같은 파라미터는 항상 같은 워크로드**.
* `PaperScenarios`: 논문 검증 (P1–P4).

---

## 4. 단계 2 — 실험 arm 과 정책 객체 만들기 (`com.raphael.experiment`)

`ExperimentConfig(experimentId, label, policyFactory, settings)` 는 "실행할 때마다 새 정책을 만드는 함수" 와
기록용 설정 맵이다. `with(id, label, extra)` 로 같은 정책에 다른 식별자를 붙인다 (예: `B91`, `FS3-F`).

arm 별로 만들어지는 객체 트리:

```
E0  fifo()             → FifoScheduler
E1  dwrr(q)            → DwrrScheduler(tenantWeights, q)
E2  e2WdedfMessage(q)  → WdedfScheduler(quanta = 식(1), BusinessDepartureTimePolicy, Λ=5%)

E3  ArchitectureArms.e3(q, budget)
    → ServiceClassPolicy
        ├─ OTP 클래스:  WdedfScheduler(키 "테넌트/OTP",  gate = TenantBudget)
        ├─ 광고 클래스: WdedfScheduler(키 "테넌트/ADVERTISEMENT", gate = TenantBudget)
        ├─ 클래스 예약 weight (OTP : 광고), 클래스 리밋 TokenBucket (선택)
        └─ TenantBudget (테넌트별 TokenBucket, 모든 클래스가 공유)

E4  ArchitectureArms.e4(q, budget, sharding)
    → ShardedPolicy
        ├─ ShardAssignment (테넌트 → 샤드 부분집합)
        ├─ ShardPool       (샤드별 독립 용량, 장애 상태)
        └─ 샤드마다 ServiceClassPolicy (위 E3 구조) — TenantBudget·클래스 리밋은 모든 샤드가 공유

E5  ArchitectureArms.e5(q, budget, sharding, aging)
    → E4 와 같고, 출발 정책이 AgingDeparturePolicy(Business, threshold)
```

* quanta: `ReservationModel.proportionalToWeights(weights, capacity).equationOneQuanta(q × 최소 가중치)` →
  가중치 w 인 큐의 quantum = q × w (DWRR 과 같은 값).
* 업무 출발 목표: `deadline − deliveryDelay`.
* E3 의 큐 키는 `QueueLayout.TENANT_AND_TYPE` 의 키 형식 (`A/OTP`) 을 쓰지만, 테넌트 가중치를 자식 큐에
  나누지 않고 클래스별 WDEDF 로 분할한다.

---

## 5. 단계 3 — 실행기 (`ExperimentRunner`)

### 5.1 `runAll(scenario, arms)` → `run(scenario, config)`

1. `warmupRuns` 번 `runOnce` (결과 버림, JIT 예열).
2. `measuredRuns` 번: `System.gc()` 요청 → `runOnce` → 첫 결과를 기준으로 이후 결과가 `equals` 인지 비교
   (다르면 "Non-deterministic result" 예외). 실행 시간 (`ExecutionProfile`) 은 반복마다 모은다.
3. `ExperimentResult(scenario 이름, config, 결정론적 결과 1 개, warm-up 수, 측정 프로필 목록)` 반환.

### 5.2 `runOnce(scenario, config, observer)`

1. `policy = config.policyFactory().apply(scenario)` — 새 정책 (비어 있지 않으면 예외).
2. `MetricsCollector(테넌트, 서비스 모델, 용량, 배달 지연, 관측 구간, 논문 T̂_D 함수)` 생성.
3. 정책이 `TenantBudgetHolder` 이고 예산이 있으면 `metrics.enableTenantBudgetAudit(rate, burst)` — 스케줄러와
   **독립된** 버킷으로 dispatch 를 재검사한다.
4. `SimulationEngine(policy, ProviderSimulator(서비스 모델, 용량, 지연), metrics, observer, 시간측정=true)`.
5. 시나리오의 모든 메시지를 `engine.scheduleArrival(m)`.
6. 스레드 CPU 시간 (ThreadMXBean) 과 wall-clock (nanoTime) 을 재며 `engine.run()`.
7. `validate()` — 하나라도 어기면 예외로 실험 중단:
   * 수신 수 = 시나리오 메시지 수, 그리고 수신 = dispatch = 배달 (메시지 보존)
   * provider 용량 위반 0
   * 테넌트 예산 위반 0, 샤드 용량 위반 0

---

## 6. 단계 4 — 이산 사건 엔진 (`SimulationEngine.run`)

### 6.1 사건과 처리 순서

사건 큐는 `PriorityQueue<SimulationEvent>` 이며 순서는 **시각 → 사건 유형 → 삽입 번호** 이다. 같은 시각이면
유형 선언 순서대로 처리된다:

`DELIVERED → ARRIVAL → DISPATCH_READY → RESOURCE_FAILURE → RESOURCE_RECOVERY`

(같은 시각에 도착한 메시지가 그 시각에 열리는 provider 슬롯을 쓸 수 있도록 ARRIVAL 이 DISPATCH_READY 보다
먼저다.)

### 6.2 루프

```
run():
  정책이 ResourceController 이면 장애·복구 사건을 큐에 넣는다
  while 사건 있음:
    clock.advanceTo(사건 시각)          (뒤로 가면 예외)
    ARRIVAL          → handleArrival
    DISPATCH_READY   → 시각이 scheduledDispatchTime 과 같을 때만 handleDispatch (아니면 대체된 사건이라 무시)
    DELIVERED        → metrics.onDelivery
    RESOURCE_FAILURE → policy.fail(샤드)
    RESOURCE_RECOVERY→ policy.recover(샤드) 후 scheduleDispatch()
  정책에 메시지가 남아 있으면 예외 ("undispatched messages")
  metrics.buildResult(...) 반환
```

* **handleArrival**: `metrics.onArrival` → `policy.enqueue(m)` → `scheduleDispatch()`.
* **scheduleDispatch**: 정책이 비어 있지 않으면 `max(now, provider.nextAvailableTime())` 에 판단을 예약한다.
  `scheduleDispatchAt(t)` 는 이미 같거나 더 이른 판단이 있으면 아무것도 안 하고, 더 이르면 새 사건으로
  대체한다 → 유효한 판단 사건은 항상 하나.
* **handleDispatch**:
  1. `eligibleAt = policy.nextEligibleTime(now)`
  2. `eligibleAt > now` 이면: MAX 가 아니면 `max(eligibleAt, provider 가능 시각)` 에 재예약하고 끝.
     MAX 이면 도착·복구 사건을 기다린다. (같은 시각 재예약은 하지 않는다 → 무한 루프 없음)
  3. `eligibleAt == now` 이면: 같은 시각 두 번 dispatch 가 아닌지 확인 → `policy.selectNext(now)` (null 이면
     계약 위반 예외) → `metrics.onDispatch` → `delivery = provider.dispatch(now, cost)` → DELIVERED 예약 →
     `scheduleDispatch()`.

`nextEligibleTime` 기본 구현은 "대기가 있으면 now" 이므로 FIFO·DWRR·WDEDF(게이트 없음)·EDF 는 v0.3 과
사건 순서가 똑같다.

---

## 7. 단계 5 — 스케줄링 정책 내부

### 7.1 공통 계약 `SchedulingPolicy`

`enqueue`, `nextEligibleTime(now)`, `selectNext(now)` (eligible 일 때 반드시 메시지 반환), `isEmpty`,
`pendingCount`, `operationCount` (알고리즘 단계 수), `diagnostics` (정책별 계수).

### 7.2 FIFO (E0)

하나의 `ArrayDeque`. 도착 순서대로 꺼낸다.

### 7.3 DWRR (E1)

테넌트별 FIFO 큐와 active ring. 방문을 시작할 때 `deficit += q × weight`, head cost ≤ deficit 인 동안 보내며
방문은 여러 provider 슬롯에 걸쳐 유지된다. 부족하면 ring 꼬리로 회전 (deficit 보존). 큐가 비면 ring 에서
빠지고 deficit = 0. 모두 부족한 한 바퀴가 지나면 필요한 빈 라운드를 한 번에 건너뛴다.

### 7.4 WDEDF (E2, 그리고 E3–E5 의 클래스 큐)

논문 Algorithm 1·2 구현. 큐 상태 `INACTIVE / ACTIVE / WAITING / BLOCKED`.

* `enqueue`: 출발 시각 (`DepartureTimePolicy`) 을 계산해 저장. INACTIVE 면 `DC += ω` 후 상태 갱신,
  WAITING 이면 같은 라운드에서 보존된 DC 재사용.
* `selectNext`: ACTIVE 큐 head 중 출발 시각이 가장 이른 큐를 고른다 (heap). BLOCKED 큐가 있으면 token PT 가
  가리키는 큐 하나에 차입 기회: (활성 head 보다 엄격히 급함 ∧ cost ≤ PQ ∧ cost ≤ PDC ∧ 게이트 통과) 이면
  그 큐가 슬롯을 받고 PQ·PDC·DC 에서 cost 를 뺀다 (DC 는 음수 가능).
* 보낸 뒤 `updateQueueStatus`: 비면 WAITING, head 감당 가능하면 ACTIVE, 아니면 BLOCKED.
* ACTIVE 가 비면 새 라운드: WAITING → INACTIVE, PQ·PT·PDC 재설정, BLOCKED 큐마다 `DC += ω`.
* **게이트 (E3–E5)**: `nextEligibleTime` 에서 heap top 의 head 가 예산 게이트에 막히면 **메시지를 꺼내지 않고**
  주차 목록으로 옮겼다가 재개 시각에 되돌린다. 게이트를 통과하는 큐가 없으면 라운드를 넘기지 않고 가장 이른
  재개 시각을 돌려준다.

### 7.5 `ServiceClassPolicy` (E3)

1. `nextEligibleTime`: 클래스마다 (하위 WDEDF 가 지금 가능 ∧ 클래스 리밋 토큰 있음) 이면 now.
2. `selectNext`: 후보 클래스에 smooth weighted round robin — 후보마다 `credit += weight`, credit 최대 클래스
   선택, 선택된 클래스 `credit −= 후보 weight 합`. 후보가 하나면 그 클래스가 전부 쓴다 (예약분 빌려 쓰기).
3. 선택된 클래스의 WDEDF 에서 꺼낸 뒤 클래스 리밋 1 메시지, 테넌트 예산 cost 를 소비하고 aging 승격을 센다.

### 7.6 `ShardedPolicy` (E4, E5)

* `enqueue`: 테넌트 부분집합 중 정상 샤드에서 대기가 가장 적은 샤드 (동률은 낮은 번호). 모두 장애면 대기가
  가장 적은 배정 샤드에서 복구를 기다린다.
* `nextEligibleTime`: 정상 샤드마다 `max(하위 정책 가능 시각, 샤드 슬롯 시각)` 의 최솟값.
* `selectNext`: 회전 포인터부터 (정상 ∧ 슬롯 비었음 ∧ 하위 정책 가능) 인 첫 샤드에서 꺼내고 `ShardPool.occupy`.
* `fail` / `recover`: 장애 샤드는 새 dispatch·라우팅에서 빠지고, 대기 메시지는 그대로 보존된다.

### 7.7 Aging (E5)

`AgingDeparturePolicy`: 순서 키 = `min(업무 목표, 도착 + threshold)`. 키는 enqueue 시 고정되고 deadline 은
바뀌지 않는다.

### 7.8 EDF (진단 D3)

모든 메시지를 하나의 heap 에 넣고 출발 시각이 가장 이른 메시지를 보낸다 (테넌트 공정성 없음).

---

## 8. 단계 6 — provider (`ProviderSimulator`)

* MESSAGE_RATE: 메시지마다 `1s / capacity` 동안 점유, 배달 시각 = `now + deliveryDelay`.
* COST_RATE: `cost / capacity` 초 동안 점유, 배달 시각 = `now + 전송시간 + deliveryDelay`.
* μs 미만 나머지는 이월되어 누적 오차가 없다. 가능 시각 전에 호출되면 예외.

---

## 9. 단계 7 — 지표 (`MetricsCollector` → `SimulationResult`)

* `onArrival`: 수신 수, backlog 시작 기록.
* `onDispatch`: 대기 지연 (= dispatch − 도착), slack (= deadline − 지연 − dispatch), 논문 T̂_D 위반, 관측 구간
  서비스·간격·lag, **provider 용량 감사** (1 초 슬라이딩 창), **테넌트 예산 감사** (켜진 경우).
* `onDelivery`: E2E 지연 (= 배달 − 도착), deadline miss (배달 > deadline), lateness.
* 집계 단위: 테넌트 (`TenantMetrics`), 메시지 유형, 테넌트×유형 (`GroupMetrics`).
* `buildResult`: 백분위 (nearest-rank), 공정성 (`WindowedFairness`: 모든 테넌트가 구간 내내 backlog 일 때만
  Jain 지수, 아니면 n/a), 스케줄러 진단, 예산 위반 수를 담은 불변 `SimulationResult`.

miss 율 분모는 그 그룹의 **수신 수** 이다 (미배달도 miss 로 센다).

---

## 10. 단계 8 — 출력

* `ComparisonReport.render` (history 의 v0.2 형식), `MultiArmReport.render` (arm 행 × 지표 열): 콘솔 출력.
* `BlastRadius.compare(id, 정상 결과, 장애 결과, aggressor 집합)`: 비-aggressor 테넌트가 (miss 율 +1 %p 초과)
  또는 (E2E p99 > 기준 × 1.5 이면서 +1 s) 이면 영향받음.
* `ResultWriter.write(outputDir, suite, runSettings, entries)` (v04·v10): 모든 실행이 끝난 뒤
  `<outputDir>/<suite>[-quick]-<UTC 시각>[-n]/` 에 7 개 파일을 쓴다 (기존 디렉터리를 덮어쓰지 않음):
  `manifest.json`, `experiment-config.json`, `experiment-results.json`, `experiment-summary.csv`,
  `tenant-metrics.csv`, `message-type-metrics.csv`, `scheduler-metrics.csv`.
  가상 시간은 정수 μs, 실행 시간은 정수 ns, 값 없음은 CSV 빈 칸 / JSON `null`.

---

## 11. 실행에 반드시 필요한 설정

### 11.1 실행 환경

| 항목 | 요구 사항 |
|---|---|
| JDK | **21** (Gradle toolchain 이 21 을 요구). 기본 `java` 가 17 이면 `JAVA_HOME` 지정 |
| 빌드 | `./gradlew clean test` (105 개 테스트), `./gradlew installDist` |
| 메모리 | 80만 건 시나리오는 지연 표본을 모두 메모리에 둔다. 배포 스크립트 실행 시 `JAVA_OPTS="-Xmx6g"` 권장 (테스트는 Gradle 이 3 GB 사용) |

```bash
export JAVA_HOME=/Users/ariel/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home
./gradlew clean test
./gradlew run --args="--suite=v04 --quick=true"                 # 스모크
./gradlew installDist
JAVA_OPTS="-Xmx6g" build/install/infobankAssignmentSimulator/bin/infobankAssignmentSimulator \
    --suite=v10 --output-dir=results                            # 최종 평가
```

### 11.2 Service Budget 설정 (`ServiceBudgetConfig`)

```java
new ServiceBudgetConfig(
    int otpWeight,                     // 클래스 예약: OTP 쪽 weight (> 0)
    int advertisementWeight,           // 클래스 예약: 광고 쪽 weight (> 0)
    int otpLimitBasisPoints,           // OTP 클래스 하드 리밋, 용량 대비 기저점 (0 = 없음)
    int advertisementLimitBasisPoints, // 광고 클래스 하드 리밋 (0 = 없음)
    int tenantRateBasisPoints,         // 테넌트 전역 하드 리밋, 용량 대비 기저점 (0 = 없음)
    long tenantBurst)                  // 테넌트 버킷 최대 누적량 (cost 단위)
```

기저점: 10,000 = 100%. 기본값 `ServiceBudgetConfig.defaults()` = `(3, 7, 0, 0, 8000, 50)`.

**세 설정의 의미는 서로 다르다.**

| 설정 | 종류 | 동작 |
|---|---|---|
| 클래스 예약 (weight) | **예약, 빌려 줄 수 있음** | 두 클래스 모두 보낼 메시지가 있으면 dispatch 슬롯을 weight 비율로 나눈다. 한 클래스가 비면 다른 클래스가 그 몫을 전부 쓴다. 엄격한 OTP 우선순위가 아니다 |
| 클래스 리밋 | **하드 리밋, 빌려 줄 수 없음** | 그 클래스의 dispatch 율 상한. 용량이 남아도 넘지 못한다. 메시지 단위로 센다 |
| 테넌트 전역 리밋 | **하드 리밋** | 테넌트의 모든 클래스 큐·샤드가 버킷 하나를 공유한다. 어떤 큐·샤드도 테넌트 예산 전체를 따로 쓸 수 없다 |

**토큰 버킷으로 바뀌는 계산** (`ArchitectureArms.build`):

* 테넌트 버킷: `rate = max(1, 용량 × tenantRateBasisPoints / 10000)` (cost/초), `burst = tenantBurst`.
  예) 450 TPS, 8000 → 360/초, burst 50.
* 클래스 리밋 버킷: `rate = max(1, 용량 × limitBasisPoints / 10000)` (메시지/초),
  `burst = max(1, rate / 10)` (자동). 예) 450 TPS, 2000 → 90/초, burst 9.
* 버킷은 처음에 가득 차 있다. 그래서 시작 직후에는 burst 만큼 한꺼번에 나갈 수 있다.

**검증 규칙** (어기면 예외):
* weight 는 양수, 기저점은 0–10000.
* `tenantRateBasisPoints > 0` 이면 `tenantBurst > 0`.
* `tenantBurst` 는 시나리오의 최대 메시지 cost 이상이어야 한다 (아니면 그 메시지는 영원히 못 나간다).

**설정할 때 주의할 점 (v1.0 측정 결과)**
* 클래스 예약은 테넌트 가중치보다 우선한다. OTP 수요 비중보다 예약을 작게 잡으면 OTP 가 크게 나빠진다
  (S12 부하 1.0 에서 3:7 → OTP miss 98.2%, E2 는 78.0%). 예약은 실제 OTP 수요 비중 이상으로 정한다.
* 하드 리밋은 작업 보존적이지 않다. 다른 테넌트가 없어도 용량이 남는다 (S2 캠페인 완료 +25%).

**설정을 바꾸는 위치** (CLI 옵션은 없다):
* v04: `V04Suite` 의 `private final ServiceBudgetConfig budget = ServiceBudgetConfig.defaults();`
* v10: `V10Suite.serviceBudgetSensitivity()` 의 `new ServiceBudgetConfig(r[0], r[1], 0, 0, 8_000, 50)`
  와 다른 메서드의 `ServiceBudgetConfig.defaults()`
* 직접 실험: `ArchitectureArms.e3/e4/e5(q, budget, …)` 에 원하는 설정을 넘긴다 (§12).

### 11.3 Shuffle Sharding 설정 (`ShardingConfig`)

```java
new ShardingConfig(
    int shards,                 // 샤드 수 N. 샤드당 용량 = provider 용량 / N (합계 고정)
    int subsetSize,             // 테넌트당 배정 샤드 수 K (1 ≤ K ≤ N)
    long seed,                  // 배정 seed (같은 seed → 같은 배정)
    List<ResourceEvent> failures)
```

* 테넌트가 쓸 수 있는 최대 용량 = provider 용량 × K / N. 예) 450 TPS, 4×2 → 225/초.
* 장애 사건: `new ResourceEvent(시각μs, 샤드번호, true=장애/false=복구)`. 시각 순이어야 하고, 샤드마다 장애·복구가
  번갈아야 하며, **모든 장애는 복구되어야 한다** (복구 없는 장애는 메시지 보존을 깨므로 생성 시 거부).
* 같은 배정으로 장애 유무만 바꿀 때: `sharding.withFailures(events)`.
* 위치: `V04Suite` 의 `sharding` 필드와 `FAILURES` (샤드 1, [10 s, 40 s)), `V10Suite` 의 P2·P3 메서드.

### 11.4 Aging 설정

* threshold (μs, > 0): `ArchitectureArms.e5(q, budget, sharding, thresholdMicros)`. v04 기본 30 s.
* 순서 키 = `min(업무 목표, 도착 + threshold)`. deadline 과 품질 지표는 바뀌지 않는다.

### 11.5 WDEDF 파라미터

| 파라미터 | 위치 | 의미 |
|---|---|---|
| base quantum `q` | `--base-quantum` (history·message·v04), v10 은 상수 10 | quantum = q × 가중치. q 가 작으면 `PQ = floor(Ω × Λ)` 가 0 이 되어 차입이 불가능해진다 (v0.3 S7 q=1) |
| Λ (차입 비율) | `StandardArms.DEFAULT_LAMBDA_BP = 500` (5%) | 기저점. 0 이면 차입 없음 |
| 출발 정책 | `DepartureMode.BUSINESS` / `PAPER` | 업무: `deadline − 지연`, 논문: 식 (2) |
| 부채 정책 | `IdleDebtPolicy.PAPER_RESET` / `RETAIN_DEBT` | 유휴 전이 시 차입 부채 탕감 여부 |

### 11.6 시나리오 작성 규칙

* 메시지 id 는 목록 순서대로 엄격히 증가해야 한다 (`MessageListBuilder` 를 쓰면 자동).
* 모든 메시지의 테넌트는 `tenants` 에 있어야 한다. deadline ≥ 도착, cost ≥ 1.
* MESSAGE_RATE 의 용량은 1–1,000,000/초 (슬롯이 1 μs 이상이어야 함).
* 공정성을 보려면 `ObservationWindow(start, end)` 를 두고, 그 구간 동안 모든 테넌트가 backlog 상태가 되도록
  워크로드를 설계한다. 그렇지 않으면 Jain 지수는 n/a 로 나온다.

---

## 12. 코드에서 직접 실험하기 (예)

```java
ServiceBudgetConfig budget = new ServiceBudgetConfig(9, 1, 0, 0, 0, 0);   // 예약 9:1, 리밋 없음
ShardingConfig sharding = new ShardingConfig(8, 4, 7, List.of(
    new ResourceController.ResourceEvent(10_000_000, 3, true),
    new ResourceController.ResourceEvent(40_000_000, 3, false)));

Scenario scenario = MessageScenarios.urgentOverload(1.0, 7);
List<ExperimentConfig> arms = List.of(
    ExperimentConfig.dwrr(10),
    StandardArms.e2WdedfMessage(10),
    ArchitectureArms.e3(10, budget).with("X1", "E3, 9:1, no tenant cap", Map.of()),
    ArchitectureArms.e4(10, budget, sharding).with("X2", "E4 8x4 + failure", Map.of()));

ExperimentRunner runner = new ExperimentRunner(0, 1);      // quick: warm-up 0, 측정 1
List<ExperimentResult> results = runner.runAll(scenario, arms);
System.out.print(MultiArmReport.render(scenario, results));

List<ResultWriter.Entry> entries = new ArrayList<>();
results.forEach(r -> entries.add(new ResultWriter.Entry(scenario, Map.of("seed", "7"), r, null)));
ResultWriter.write(Path.of("results"), "custom", Map.of("quick", "true"), entries);
```

---

## 13. 자주 보는 오류

| 메시지 | 원인 |
|---|---|
| `UnsupportedClassVersionError (65.0)` | JDK 17 로 실행됨 → `JAVA_HOME` 을 JDK 21 로 |
| `Unknown option --…` / `Unknown suite …` | 옵션·suite 이름 오타 |
| `OutOfMemoryError` | `JAVA_OPTS="-Xmx6g"` |
| `tenantBurst must cover the largest message cost` | `tenantBurst` < 최대 cost |
| `Every failed shard must recover` | 장애 사건에 대응하는 복구 사건 누락 |
| `Simulation ended with N undispatched messages` | 메시지가 영원히 eligible 이 되지 못함 (예산·장애 설정 확인) |
| `Message conservation violated` / `Provider capacity exceeded` / `Service budget or shard capacity violated` | 정책 버그 — 실행기가 결과를 거부함 |
| `Non-deterministic result` | 정책이 실행 간 상태를 공유함 (factory 가 매번 새 객체를 만드는지 확인) |
| `… returned no message while N messages are pending` | `nextEligibleTime == now` 인데 `selectNext` 가 null (계약 위반) |

---

## 14. 알아 두어야 할 가정

* provider 용량 450 TPS·고정 지연·무손실이 기본이다. 샤딩은 "총 용량 고정" 에서만 비교했다.
* 측정 반복 (`--runs`) 은 JVM 시간 측정의 반복이다. 결정론적 결과는 매번 같으므로 통계적 반복이 아니다.
  통계적 반복은 워크로드 seed 와 샤드 배정 seed 를 바꿔서 얻는다.
* 실행 시간은 시뮬레이터 비용이며 운영 인프라 비용이 아니다.
