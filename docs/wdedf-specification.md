# WDEDF 알고리즘 명세 (v0.3)

## 0. 참조 문헌

Teck Meng Lim, Bu Sung Lee, Chai Kiat Yeo, "Weighted deficit earliest departure first scheduling",
*Computer Communications* 28 (2005) 1711–1720, DOI 10.1016/j.comcom.2004.12.015.
이하 "논문". 절·식·알고리즘 번호는 논문 기준이다. WDEDF 는 **Weighted Deficit Earliest
Departure First** 이며 deadline 이 아니라 *expected departure time* 을 기준으로 한다.

이 문서는 세 층위를 구분한다.

| 표기 | 의미 |
|---|---|
| **[논문]** | 논문의 식·의사코드·본문이 명시한 의미 |
| **[해석]** | 논문이 모순되거나 불충분하여 이 구현이 선택한 해석 (근거와 테스트 명시) |
| **[적응]** | 패킷 네트워크 → 메시지 전송 도메인으로 옮기며 추가한 것 (논문에 없음) |

---

## 1. 수학적 정의

### 1.1 가중 quantum — 식 (1) [논문]

```
ω_i = R_i / R_MIN × ω_MIN ,   R_MIN = min_j R_j
```

* `R_i` : 큐 i 의 예약 전송률 (자원 단위/초). `ω_MIN` : 최소 quantum (자원 단위).
* **[해석 A1]** `R_MIN` 과 `ω_i` 는 *설정된 모든 큐* 에 대해 한 번 계산하며, 큐가
  활성/비활성으로 바뀌어도 재계산하지 않는다 (안정적인 기준 전송률).
* **[해석 A1]** 정수 deficit 회계를 위해 `ω_i = floor(R_i × ω_MIN / R_MIN)` 로 계산한다 (정확한
  유리수 연산, `BigInteger` 중간값; long 범위를 넘으면 `ArithmeticException`). 나머지가 0 이 아니면
  `exact=false` 로 보고한다. `R_i ≥ R_MIN` 이므로 `ω_i ≥ ω_MIN ≥ 1` 이 항상 성립한다.
* 구현: `com.raphael.scheduling.wdedf.ReservationModel#equationOneQuanta`.

### 1.2 예상 출발 시각 — 식 (2) [논문]

```
T̂_D = T_A + L / R − L / C
```

* `T_A` 도착 시각, `L` 패킷 길이, `R` 큐 예약률, `C` 서버(링크) 용량. Fig. 1 에서 `T_D` 는
  패킷이 스케줄러를 떠나야(전송을 시작해야) 하는 시각이고 `T_C = T_D + L/C = T_A + L/R` 은
  예상 완료 시각이다.
* **[해석 A22]** `0 < R ≤ C` 를 요구한다 (`R > C` 이면 `T̂_D < T_A`).
* **[해석]** 단위: 시간은 가상 μs. `T̂_D = T_A + floor( L × 10^6 × (C·q − p) / (p·C) )`,
  여기서 `R = p/q` 는 유리수 예약률. 내림(floor)은 논문 Table 1 의 B1 값(116.67 → 116)과
  일치한다. long 곱셈이 overflow 하면 `BigInteger` 로 같은 값을 계산한다(결과는 동일, 결정론적).
* `T̂_D` 는 enqueue 시점에 한 번 계산되어 큐 원소와 함께 저장되며 이후 바뀌지 않는다.
* 구현: `PaperDepartureTimePolicy`.

### 1.3 메시지 도메인 dispatch 목표 시각 [적응]

```
dispatchTargetTime = absoluteBusinessDeadline − estimatedProviderDeliveryDelay
```

* 현재 provider 는 고정 지연이므로 `estimatedProviderDeliveryDelay = scenario.deliveryDelay`.
  미래에 실현될 지연 값은 사용하지 않는다(현재 모델에서는 둘이 같다).
* `Message.deadline` (업무 deadline) 은 그대로 보존된다. 논문 `T̂_D` 와 업무 deadline 은
  별도 지표로 보고한다 (§7).
* 구현: `BusinessDepartureTimePolicy`. 두 정책은 같은 `DepartureTimePolicy` 인터페이스를
  구현하고 **같은 `WdedfScheduler` 상태기계** 를 공유한다.

### 1.4 선점 quantum [논문 + 해석]

```
Ω  = Σ_i ω_i                (설정된 모든 큐)            [해석 A15]
PQ = floor(Ω × Λ)  ,  Λ = lambdaBasisPoints / 10_000      [해석: 기저점 정수 표현]
PDC_i = ω_i                 (라운드마다 재설정)           [논문 Alg.1 초기화 + 해석 A12]
```

* 논문은 Λ 의 기본값을 제시하지 않는다. 이 구현은 Λ 를 설정값으로 두고 0%, 1%, 5%, 10%, 20%
  민감도를 실험한다. Λ = 0 이면 PQ = 0 이므로 차입이 원천적으로 불가능하다(진단 변형).
* 정수 내림 때문에 quantum 이 작은 설정(예: 모든 ω = 1, Ω = 9)에서는 Λ < 1/Ω 일 때 PQ = 0 이 된다.
  이것은 구현 결함이 아니라 정수 deficit 회계의 결과이며 실험에서 명시적으로 보고한다.

---

## 2. 큐 상태기계 (Fig. 2)

| 상태 | 정의 | 소속 구조 |
|---|---|---|
| INACTIVE | backlog 없음, 직전 라운드에서 넘어온 credit 없음 | 없음 |
| ACTIVE | head 의 비용 ≤ DC | active 후보 heap (정확히 1 개 항목) |
| WAITING | 현재 라운드 중에 비었음, DC 를 라운드 끝까지 보존 | waiting 목록 |
| BLOCKED | 메시지가 있으나 head 의 비용 > DC | blocked 목록 (정확히 1 회) |

전이 (구현 `WdedfScheduler`, 각 전이는 `transitionCount[from][to]` 로 계수):

| 사건 | 전이 | DC 변화 |
|---|---|---|
| INACTIVE 에 도착 | → ACTIVE 또는 BLOCKED | `DC ← ω` [논문]; RETAIN_DEBT 모드는 `DC ← min(DC,0) + ω` |
| WAITING 에 도착 (같은 라운드) | → ACTIVE (L ≤ DC) / BLOCKED | 보존된 DC 재사용 [논문 §2.1] |
| ACTIVE/BLOCKED 에 도착 | 변화 없음 (head 불변) | 없음 |
| 일반 dispatch / 차입 dispatch | → ACTIVE / BLOCKED / WAITING (Update_Queue_Status) | `DC ← DC − L` |
| 라운드 전이: WAITING | → INACTIVE | `DC ← 0` [논문 Fig.2]; RETAIN_DEBT 모드는 `DC ← min(DC,0)` |
| 라운드 전이: BLOCKED | → ACTIVE (L ≤ DC+ω) / BLOCKED 유지 | `DC ← DC + ω` (라운드당 정확히 1 회) |

불변식(테스트 `WdedfInvariantTest`): 비어 있지 않은 큐는 ACTIVE 또는 BLOCKED 이고, ACTIVE 큐는
heap 에 정확히 한 번, BLOCKED 큐는 blocked 목록에 정확히 한 번 존재한다. 빈 큐는 WAITING 또는
INACTIVE 이다. INACTIVE 큐의 DC 는 0 (논문 모드) 또는 ≤ 0 (RETAIN_DEBT 모드) 이다.

---

## 3. 전역 서비스 라운드

* **[논문]** 라운드는 Active List 가 비었을 때 Get_Packet 이 호출되면 끝난다. 따라서 라운드
  종료는 *dispatch 기회* 에서만 판정된다. 대기 메시지가 하나도 없으면 Get_Packet 이 호출되지
  않으므로 빈 라운드가 끝없이 생기지 않는다 (이 구현의 `selectNext` 는 빈 상태에서 호출되면
  다른 정책과 같은 계약으로 `IllegalStateException` 을 던지며, 라운드를 만들지 않는다).
* 라운드 전이 절차 (`startNewRound`):
  1. `round ← round + 1`
  2. WAITING 큐 → INACTIVE (DC 재설정, §2)
  3. `PQ ← floor(Ω × Λ)`, `PT ← 0`, 모든 `PDC_i ← ω_i` (라운드 번호 stamp 로 O(1) 지연 재설정)
  4. BLOCKED 큐마다 `DC ← DC + ω` 후 적격이면 ACTIVE heap 으로 이동, 아니면 순서를 유지한 채 BLOCKED
  5. 그래도 ACTIVE 가 비었으면 *빈 라운드 압축* (§3.1)
* **[해석 A11] 즉시(eager) 보충.** 논문은 `Active List = Blocked List` 후 큐가 선택될 때
  `Activate_i` 플래그로 `DC += ω` 를 지연 적용하며, 이때 `L ≤ DC` 를 다시 확인하지 않는다.
  논문 가정(ω_i ≥ MTU ≥ L 이고 라운드 시작 DC ≥ 0)에서는 모든 blocked 큐가 보충 후 적격이므로
  지연 보충과 즉시 보충의 선택 순서는 같다(둘 다 head `T̂_D` 순 heap). 그러나 차입으로 DC < 0
  이거나 ω < L 이면 논문 의사코드는 DC 가 부족한 큐에서 dequeue 하여 무한정 부채를 만든다.
  이 구현은 보충 후 적격성을 확인하고 부족하면 BLOCKED 로 남긴다. (테스트:
  `paperAssumptionMakesEveryBlockedQueueEligibleAfterOneReplenishment`,
  `debtorStaysBlockedUntilReplenishmentCoversHead`)
* quantum 은 큐당 라운드당 최대 한 번만 적용된다 (`lastReplenishedRound` 검사; 위반 시 예외).

### 3.1 빈 라운드 압축 [해석 A18]

보충 후에도 적격 큐가 없으면 (ω < L 이거나 부채), 각 blocked 큐가 필요로 하는 추가 라운드 수
`k_i = ceil((L_i − DC_i)/ω_i)` 의 최솟값 `K` 만큼 라운드를 한 번에 진행한다:
모든 blocked 큐에 `K·ω_i` 를 더하고 `round += K`.
**동치성 증명:** 이 K 개의 논리 라운드 동안 (a) active 가 비어 있으므로 dispatch 와 차입이 없고,
(b) 같은 가상 시각 안에서 일어나므로 도착이 없으며, (c) WAITING 목록은 첫 전이에서 이미 비었고
dispatch 가 없으므로 다시 채워지지 않고, (d) PQ/PDC/PT 재설정은 매 라운드 같은 값이다.
따라서 유일한 상태 변화는 blocked 큐마다 라운드당 ω_i 가 더해지는 것뿐이며, K 번째 라운드에서
처음으로 적격 큐가 생긴다. 가상 시간 지연은 없다. (테스트: 압축 vs 라운드 단위 진행 차등 테스트)

---

## 4. 최조 출발 선택

* 후보는 **큐 head 만** 이다. heap 원소 `Candidate(departure, arrival, messageId, queueIndex)`
  는 불변(immutable) 이다. ACTIVE 큐의 head 는 큐가 선택되기 전에는 바뀌지 않으므로(도착은 꼬리에
  추가) heap 키가 변할 일이 없고 stale 항목도 없다. 큐는 선택될 때 heap 에서 제거되고 재분류 후
  필요하면 새 키로 다시 삽입된다.
* 결정론적 비교 순서: `T̂_D` → 도착 시각 → 메시지 id → 큐 생성 순서 index.
* 큐 내부는 FIFO 이다. 전역 메시지 단위 EDF (`EdfScheduler`, 진단용)와 구별된다.

---

## 5. 선점 quantum 차입 (Algorithm 2)

Active 가 비어 있지 않고 Blocked 가 비어 있지 않을 때, dispatch 기회마다:

```
q  = Blocked[PT]                       (token 이 가리키는 큐 하나만 검사; 전역 탐색 없음)
if T̂_D(activeHead) > T̂_D(q.head) && L ≤ PQ && L ≤ PDC_q:      [논문, 엄격한 >]
    Blocked 에서 q 제거 (PT 는 다음 큐를 가리킴, 끝이면 0)
    PDC_q −= L ; PQ −= L ; DC_q −= L (Dequeue 모듈) → DC_q 는 음수가 될 수 있음
    Update_Queue_Status(q)  → 비어 있으면 WAITING, 아니면 (DC 부족하므로) Blocked 꼬리
else:
    PT ← PT + 1 ; PT ≥ |Blocked| 이면 0        [해석 A13]
    Active head 를 일반 dispatch
```

* **[해석 A13]** 의사코드는 `PT > Size` 일 때만 0 으로 되돌려 PT = Size (범위 밖)를 허용한다.
  0 기반 인덱스로 보고 `PT ≥ Size` 에서 되돌린다. 제거 후 PT 는 같은 인덱스(=다음 큐)를 가리킨다.
* **[해석 A14]** 거절 사유와 무관하게 PT 를 한 칸 진행한다 (논문). 거절 사유는 우선순위
  `NOT_MORE_URGENT → INSUFFICIENT_PQ → INSUFFICIENT_PDC` 로 첫 번째 실패 조건을 기록한다.
* 구현: blocked 목록은 큐 객체에 내장된 이중 연결 리스트 + token 노드. 제거/추가/진행 O(1).
* 차입은 provider 용량을 만들지 않는다. 다음 provider 슬롯에 어느 큐를 보낼지만 바꾼다
  (이미 dispatch 된 메시지를 중단하지 않는다).
* **[해석 A17] 유휴 재설정의 부채 탕감.** 논문에서 INACTIVE 큐가 활성화되면 `DC = ω` 이므로,
  차입으로 생긴 음수 DC 가 큐가 비었다가(WAITING) 라운드를 넘기면(INACTIVE) 사라진다. 큐를
  반복적으로 비우는(churn) 흐름은 라운드마다 PDC 만큼 추가 서비스를 받을 수 있다. 기본은 논문
  의미(`PAPER_RESET`)이고, 부채를 유지하는 `RETAIN_DEBT` 진단 변형으로 영향을 측정한다(S11).

---

## 6. 논문 내 불일치·불충분 명세 목록

| ID | 위치 | 내용 | 선택 | 테스트/실험 |
|---|---|---|---|---|
| A1 | §2, 식(1) | "ω_MIN 을 MTU 로 제한하지 않는다" 와 "ω_MIN ≥ MTU 로 한 라운드에 최소 1 패킷 보장" 이 상충 | ω_MIN > 0 일반화; L > ω 이면 라운드 누적으로 적격. 논문 가정 모드는 테스트로 따로 검증 | `WdedfSchedulerTest` |
| A2 | Example 1 | 예약률이 2:3 인데 두 큐 quantum 을 모두 1000 bit 로 둠 (식(1)이면 1000/1500) | 식(1)을 구현; 예제는 두 설정 모두로 순서 검증 | `PaperExamplesTest` |
| A3 | Table 1 | B1 대기시간 166 (정확히 166.67) | floor 반올림 → T̂_D = t_A+216 재현 | `DepartureTimePolicyTest` |
| A4 | Table 2 각주 | "예상 완료 = T_A + L/C" 이나 표의 값은 T_A + L/R | T_C = T_D + L/C = T_A + L/R | `PaperExamplesTest` |
| A5 | Example 1 | 서비스 시작 t = t_A+162 > T̂_D(A1)=t_A+160 → A1 은 두 스케줄러 모두에서 2μs 늦음; 논문은 B1·A2 만 언급 | 그대로 재현하고 보고 | `PaperExamplesTest` |
| A6 | Example 2 | quantum 1500/3450/4950 은 식(1)의 1500/3500/5000 과 다름 | 원리(대기 상태 credit 유지)만 재현 | P2 |
| A7 | Example 2 | 시간 단위 ms 로 표기되었으나 수치는 μs (600 bit / 20 Mbps = 30 μs) | μs 로 해석 | P2 |
| A8 | Example 2 | 잔액 1100 (1500−600=900 이어야 함); C 는 4950 으로 800bit 6 개 가능한데 4 개; 라운드 종료 시각 계산이 A1 완료가 아닌 A2 도착 기준 | 수치 재현하지 않음 | P2 |
| A9 | Alg.1 En-queue | INACTIVE 분기에서 enqueue(i,k)·Update_Queue_Status(i) 가 빠짐 | 두 분기 모두 enqueue 후 상태 갱신 (Fig.2 Inactive→Active) | 상태 전이 테스트 |
| A10 | Alg.1/2 | `B_i = Block` 이 "초기/비활성" 과 "blocked" 를 겸함; "Waiting List 를 Block 으로" = 비활성화 | 4 상태 enum 으로 명시 | 상태 전이 테스트 |
| A11 | Alg.2 | 지연 보충(Activate_i) 시 적격성 재확인 없음 → 부채/ω<L 에서 무한 부채 | 즉시 보충 + 적격성 확인 (§3) | §3 테스트 |
| A12 | Alg.2 | 새 라운드에 PDC 를 선택된 큐 하나만 재설정 | 모든 PDC 를 라운드마다 재설정 (본문: 큐별 라운드 선점 한도) | 차입 테스트 |
| A13 | Alg.2 | PT 경계 `PT > Size` (off-by-one) | `PT ≥ Size` → 0 | token 테스트 |
| A14 | Alg.2 | token 은 blocked 큐 하나에만 기회를 줌 | 보존 (전역 정렬 탐색으로 대체하지 않음) | token 테스트 |
| A15 | §2 | "총 quantum" Ω 미정의 | 설정된 모든 큐의 ω 합 (안정) | 차입 테스트 |
| A16 | Alg.2 | Active 가 비었을 때 차입 없음; T̂_D 동률이면 `>` 이므로 차입 없음 | 보존 | 차입 테스트 |
| A17 | Alg.1 | 비활성 활성화 시 DC = ω 로 부채 탕감 → churn 으로 반복 차입 가능 | 논문 기본 + RETAIN_DEBT 진단 | churn 테스트, S11 |
| A18 | Alg.2 | 보충 후에도 모든 큐가 부족한 경우 미정의 | 동치성이 증명된 빈 라운드 압축 | §3.1 테스트 |
| A19 | §4.2.1 | deadline miss = "T̂_D 이후에 서비스됨" | dispatch 시각 > T̂_D 를 논문 출발 위반으로 계수 (업무 deadline miss 와 별도) | 지표 테스트 |
| A20 | Alg.1 | Out_of_Buffer/Free_Buffer (buffer stealing) | 범위 밖; §4 와 같이 무한 큐 | — |
| A21 | §4 | 4.2 링크 용량 미기재; 4.1 가중 실험(예약 합 210 kbps, 링크 4 Mbps?)과 Fig.4(b) 의 f kbps 처리량이 모순; ns2 시뮬레이션 시간·seed·헤더 크기 미기재 | 정확한 수치 재현 불가; 질적 목표만 재현하며 파라미터는 명시적 가정으로 표기 | P3, P4 |
| A22 | 식(2) | R > C 의 의미 없음 | 0 < R ≤ C 강제 | `DepartureTimePolicyTest` |
| A23 | §2 | 복잡도 O(log N) | dispatch 당 heap 연산은 O(log N) 이지만 라운드 전이는 O(B log N + W) (§8) | 연산 계수 지표 |
| A24 | 식(2) 적응 | MESSAGE_RATE 에서는 메시지가 cost 와 무관하게 슬롯 하나를 쓰므로 `L/C` 는 물리적 전송시간이 아니다 | 비용 1 시나리오에서는 `L/C = 1/TPS` 로 일치; cost ≠ 1 이면 "비용 비례 가상 시간" 으로만 해석 | §7 |
| A25 | Fig.1 | 패킷 완료 시각 `T_C = T_D + L/C` | COST_RATE 의 배달 시각 = dispatch + `cost/C` + 지연. MESSAGE_RATE 는 v0.1 의미 유지 (골든 테스트) | `ProviderSimulatorTest` |
| A26 | (구현 과정) | S6 첫 설계에서 OTP 테넌트 제출률 0.5/s 가 1,000·10,000 테넌트의 가중치 몫(0.446, 0.045/s)을 넘어 집단 효과 대신 개별 과부하를 측정했다 | 제출 간격 ≥ 3 × 라운드로 재유도; 원래 설계는 `S6OVL` 로 명시 보존 | `v0.3-results.md` §S6 |

---

## 7. 메시지 도메인 적응 [적응]

* 패킷 길이 `L` → 메시지 scheduling cost. 예약률 `R_i` → (명시값이 없으면) 가중치 비례
  `R_i = w_i / Σw × C`. `C` → provider 용량.
* **서비스 모델**: 기존 시나리오(S1–S4b 및 신규 S 시리즈)는 `MESSAGE_RATE` (메시지당 1 슬롯,
  비용 무관) 를 유지한다. 논문형 실험(P 시리즈)은 `COST_RATE` (메시지가 `cost/C` 초 동안 링크
  점유) 를 쓴다. 한 실험의 모든 정책은 같은 서비스 모델을 쓴다. `MESSAGE_RATE` 에서 cost ≠ 1 이면
  식(2)의 `L/C` 는 물리적 전송시간이 아니다 — 이 경우 논문 T̂_D 는 "비용 비례 가상 시간" 으로만
  해석한다.
* **큐 토폴로지**: 기본은 테넌트당 큐 1 개 (`TENANT`). 진단 토폴로지 `TENANT_AND_TYPE` 는
  테넌트를 메시지 유형별 자식 큐로 나누되 테넌트 예약을 자식들에게 **분할** 한다 (자식 가중치 =
  `w_t × L / k_t`, `k_t` 는 테넌트의 유형 수, `L = lcm(k_t)`). 같은 토폴로지를 DWRR 에도 적용해
  토폴로지 효과와 스케줄링 효과를 분리한다.

---

## 8. 자료구조와 복잡도

| 구조 | 구현 | 비용 |
|---|---|---|
| 큐별 FIFO + 저장된 T̂_D | 원형 배열 (`Message[]` + `long[]`) | enqueue/dequeue O(1) 상각, 메시지당 할당 없음 |
| 큐 조회 | `HashMap<String, WdedfQueue>` | O(1) 기대 |
| ACTIVE 후보 | `PriorityQueue<Candidate>` (큐당 최대 1 항목) | O(log A) |
| BLOCKED 목록 + PT | 큐 내장 이중 연결 리스트 + token 노드 | 추가/제거/진행 O(1) |
| WAITING 목록 | `ArrayList` + listed 플래그 (중복 방지) | O(1) 추가, 라운드 전이 시 O(W) |
| PDC 재설정 | 라운드 stamp 로 지연 재설정 | O(1) |

* `enqueue`: O(1) + (활성화 시) heap 삽입 O(log A).
* `selectNext` (라운드 전이 없음): 차입 검사 O(1) + heap poll/offer O(log A) → O(log A).
* 라운드 전이: O(W + B log A) — WAITING 정리와 BLOCKED 보충·재삽입. 각 BLOCKED 큐는 라운드당 한
  번 보충되며, 한 라운드에서 적어도 한 번의 dispatch 가 일어나므로 상각하면 dispatch 당
  O((B/D_r) log A) (D_r 은 라운드당 dispatch 수). 모든 큐가 quantum 이상의 head 비용을 가질
  때(논문 가정) 각 보충은 다음 라운드의 dispatch 로 이어지므로 상각 O(log A).
* 비용/quantum 비율 `L/ω` 가 큰 경우: 빈 라운드 압축 덕분에 라운드 전이 1 회는 O(B log A) 로
  `L/ω` 와 무관하다. 그러나 큐는 `L/ω` 라운드 동안 BLOCKED 로 남아 매 라운드 보충 대상이
  되므로 라운드 전이 작업 총량은 증가한다.
* 공간: O(Q + M) (Q: 한 번이라도 메시지를 받은 큐 수, M: 대기 메시지 수). 설정만 된 큐는
  quantum 표에만 존재한다.
* 보고되는 연산 수(heap offer/poll, 후보 검사, 상태 전이, 실제 보충 단계, token 연산)는 알고리즘
  단계 수이며 CPU 명령 수가 아니다. 압축된 빈 라운드의 논리적 보충(`quantumReplenishments`)과
  실제 수행 단계(`replenishmentSteps`)를 따로 보고한다 — 처음 구현은 논리적 보충 수를 연산 수에
  더해 비용 1,000,000 / ω 1 큐 하나에서 666,673 "연산" 을 보고했다 (테스트로 발견·수정).
* 측정된 연산 수: S2 (800,001 건) 에서 FIFO 1.0, DWRR 2.0, WDEDF 6.0 연산/선택. 메시지 도메인
  시나리오에서 WDEDF 는 4.0–8.0 연산/선택이다 (`docs/v0.3-results.md`).

---

## 9. 구현 추적표 (논문 → 코드)

| 논문 요소 | 코드 |
|---|---|
| 식 (1) | `ReservationModel#equationOneQuanta` |
| 식 (2) | `PaperDepartureTimePolicy#departureTime` |
| Alg.1 Initialise | `WdedfScheduler` 생성자 (DC=0, INACTIVE, ω/PDC, PQ, PT) |
| Alg.1 En-queue | `WdedfScheduler#enqueue` |
| Alg.1 De-queue | `WdedfScheduler#dequeueFrom` |
| Alg.1 Update_Queue_Status | `WdedfScheduler#updateQueueStatus` |
| Alg.2 Get_Packet (차입 분기) | `WdedfScheduler#tryBorrow` |
| Alg.2 Get_Packet (일반 분기) | `WdedfScheduler#selectNext` |
| Alg.2 Get_Packet (새 라운드) | `WdedfScheduler#startNewRound` |
| Fig. 2 | `QueueState` + `transition` |
| §4.2.1 deadline miss | `MetricsCollector` 의 paper departure violation |

---

## 10. 실험 체계

* 아키텍처 실험열 (E 시리즈): E0 FIFO, E1 DWRR, **E2 WDEDF-MESSAGE** (업무 dispatch 목표,
  Λ = 5% — 논문이 정한 값이 아닌 실험 선택값).
* 진단 변형 (D 시리즈, E 시리즈와 구분): D1 WDEDF-PAPER (식(2), Λ=5%), D2 WDEDF-NB (업무 목표,
  Λ=0), D3 EDF (업무 목표 전역 메시지 EDF), D4 WDEDF-DEBT (RETAIN_DEBT), D5/D6 TENANT_AND_TYPE
  토폴로지의 DWRR/WDEDF.
* 논문 검증 (P 시리즈)과 메시지 도메인 실험 (S 시리즈)은 별도 보고서로 출력한다.
* P 시리즈 arm: E1 DWRR (= 논문의 WDRR, `baseQuantum × w_i` 가 식 (1) 과 같도록 설정),
  P-W0/P-W5 WDEDF-PAPER (Λ = 0 / 5%, 명시적 ω_MIN), P-EDF (논문 T̂_D 기준 전역 EDF), 필요 시 E0.
* Λ 민감도 arm: L0, L100, L500, L1000, L2000 (기저점) — 같은 출발 정책·워크로드.
* 결과는 `docs/v0.3-results.md` 에 실제 실행 출력(`docs/results/v0.3-full-run.txt`)에서 옮겨 기록한다.
