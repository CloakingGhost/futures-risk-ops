# Day 2 직접 해보기 안내와 구현 브리핑

대상: 브랜치 `feat/day2-reconnect-calendar`. 명령은 모두 Git Bash 기준이고 저장소 폴더(`futures-risk-ops`)에서 실행한다. 준비(Docker, DB, `.env` 불러오기)는 `docs/day1-hands-on.md` 0번과 같다.

```bash
cd /c/Users/Lee/side-project/finance/margin-watch/futures-risk-ops
git checkout feat/day2-reconnect-calendar
docker compose up -d
set -a; . ./.env; set +a
```

Day 1과 로그가 조금 달라졌다. 연결 상태가 바뀔 때마다 `ws state CONNECTING -> SUBSCRIBED (...)` 한 줄이 남고, 10초마다 나오는 `ingest stats` 줄에 항목이 늘었다.

| 항목 | 뜻 |
|---|---|
| `state` | 지금 연결 상태: `IDLE`(세션 밖·종료), `CONNECTING`, `SUBSCRIBED`, `BACKOFF`(다시 붙기 전 대기) |
| `disconnects`, `recoveries` | 구독 중 끊긴 횟수, 그 뒤 다시 구독까지 돌아온 횟수 |
| `last-recovery-ms`, `max-recovery-ms` | 끊긴 뒤 다시 구독까지 걸린 시간(마지막, 가장 긴 것). 30000 보다 작아야 한다 |
| `reconnect-gaps`, `reconnect-missing`, `reconnect-missing-rate` | 재접속 직후 빈틈: 끊긴 동안 놓친 체결(확실한 누락). 횟수, 놓친 수량, 놓친 수량 / 늘어난 수량. 알람은 이쪽에 건다 |
| `stream-gaps`, `stream-missing` | 연결 중 빈틈: 연결이 이어지는 동안 누적거래량이 체결 수량보다 더 늘어난 횟수와 수량. 조용한 중단으로 버려진 체결이거나, KIS 가 몰린 체결을 묶어 보낸 것(가정)일 수 있다 |
| `repeats` | 이미 받은 누적거래량이 다시 온 횟수. DB 의 `duplicates` 와 같아야 한다 |
| `stalls` | 연결은 살아 있는데 체결이 `stall-threshold`(시뮬레이터 5초, KIS 60초) 넘게 안 온 횟수 |
| `out-of-session-connects` | 세션 밖에서 접속을 시작한 횟수. 0 이어야 한다 |

## 1부. 직접 해보는 시나리오

### A. 끊김 뒤 자동 복구 3번 (오늘의 핵심)

터미널 1: 10초마다 연결을 끊는 시뮬레이터

```bash
./gradlew :simulator:run --args="--rate.ticks-per-second=50 --disconnect.after-seconds=10"
```

터미널 2: 수집기

```bash
./gradlew :collector:bootRun
```

40초쯤 두면 아래가 세 번 반복된다.

```
ws state SUBSCRIBED -> BACKOFF (connection closed (1001 ...); retry in 1xxx ms)
ws state BACKOFF -> CONNECTING (connecting to ws://127.0.0.1:18080/tryitout)
ws state CONNECTING -> SUBSCRIBED (subscription accepted for A01612)
recovered in 1xxx ms after disconnect
missed trades on A01612 ...: acc_volume rose by .. but this trade has qty .. (missing .., gap #n)
```

볼 것: `recovered in` 이 매번 30000 ms 보다 작다(첫 대기 1초 + 지터 0~0.5초라 보통 1~1.6초). 끊긴 1초 남짓 동안 시뮬레이터가 만든 체결은 받지 못했으므로 `missed trades ... while disconnected` 경고와 `reconnect-missing` 이 늘고, `stream-gaps` 는 0 이다. 시뮬레이터는 이제 구독자가 없어도 시세를 계속 만든다(실제 시장처럼).

`--disconnect.abrupt=true` 를 더하면 close 프레임 없이 TCP 를 끊는다. 로그 첫 줄이 `connection failed: ...` 로 바뀌는 것 말고는 같다.

### B. 재접속 직후 다시 받은 체결이 두 번 쌓이지 않는지

시뮬레이터가 다시 구독할 때 최근 체결 30건을 또 보내게 한다(이미 받은 체결이 섞인다).

```bash
./gradlew :simulator:run --args="--rate.ticks-per-second=50 --disconnect.after-seconds=10 --resend.on-subscribe=30"
```

볼 것: `ingest stats` 에서 `repeats` 와 `duplicates` 가 같은 숫자로 늘고, `reconnect-missing` 은 거의 늘지 않는다(재전송이 끊긴 사이를 메운다). DB 행 수와 서로 다른 누적거래량 수가 같은지 본다.

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d futures_risk_ops -c "select source, session, trade_date, count(*), count(distinct acc_volume) from tick_trade group by 1,2,3"'
```

주의: Day 1 시나리오 D 처럼 시뮬레이터를 껐다 켜면 누적거래량이 처음부터 다시 시작해 이미 쌓인 행과 겹친다. 그때 `duplicates` 는 `repeats` 보다 커진다. 깨끗하게 보려면 시작 전에 시뮬레이터 행을 지운다(지울지는 직접 판단한다).

### C. 조용한 중단 감지 (Day 1 시나리오 E 의 한계가 풀렸는지)

```bash
./gradlew :simulator:run --args="--rate.ticks-per-second=20 --stall.after-seconds=10 --stall.duration-seconds=15"
```

볼 것: 체결이 멈추고 5초쯤 뒤 `no trades for 5xxx ms while subscribed (stall #1)` 경고, 다시 오면 `trades resumed after 15xxx ms of silence` 와 멈춘 동안 놓친 수량만큼 `acc_volume gap ... while connected` 줄(`stream-gaps` 증가, 연결은 끊기지 않았으므로 재접속 빈틈이 아니다). 연결은 끊지 않는다(감지만 한다).

### D. 장 시간에만 접속 (KIS 프로필, 키를 쓰지 않고 확인)

세션 밖(예: 15:45~17:59, 06:00~08:43, 주말)에 모의투자 프로필로 켠다.

```bash
./gradlew :collector:bootRun --args="--spring.profiles.active=kis-paper"
```

볼 것: `session gate closed: staying disconnected until the next session NIGHT 2026-10-28T18:00+09:00[Asia/Seoul]` 처럼 다음 세션을 알려 주고, `ingest stats` 가 `state=IDLE, connects=0` 으로 유지된다. 접속키도 받지 않는다(KIS 에 아무 요청도 가지 않는다). 17:59 까지 켜 두면 `session gate open: connecting` 뒤 접속하고, 실제 체결 수신 확인(아래 E)으로 이어진다.

시뮬레이터로 게이트를 보고 싶으면 `--collector.session.gating=true` 를 더한다. 세션 밖이면 시뮬레이터가 켜져 있어도 붙지 않는다.

### E. KIS 모의투자 실연결 (PR 2 에서 넘어온 확인)

**주간장(08:45~15:45)에** D 와 같은 명령으로 켠다. 야간장 체결은 `H0IFCNT0` 가 아니라 별도 TR `H0MFCNT0`(KRX야간선물 실시간종목체결)로 오므로, 지금 수집기로는 야간장에 체결이 오지 않는 것이 정상이다(아래 '아직 모르는 것'). 확인할 것은 `docs/kis-h0ifcnt0.md` 의 "확인할 것" 목록이고, 확인한 사실은 그 문서에 적는다. 받은 실제 메시지는 `local-data/` 에만 둔다.

### F. 쌓인 행의 거래일 다시 계산

Day 1 은 거래일을 '수신 한국 날짜'로 채웠다. 오늘부터는 야간장 체결이 다음 거래일로 들어간다(가정). 이미 쌓인 행을 같은 규칙으로 맞추려면 한 번 실행한다.

```bash
./gradlew :collector:bootRun --args="--collector.enabled=false --collector.backfill-trade-dates=true"
```

`trade date backfill: scanned=..., updated=..., conflicts=...` 한 줄을 남기고 끝난다. `conflicts` 는 바꾸면 이미 있는 행과 같은 체결이 되는 행으로, 지우지 않고 그대로 둔다.

### G. 출처가 다른 체결은 서로 막지 않는다 (Flyway V3)

수집기를 한 번 켜면 V3 가 적용된다(`Migrating schema "public" to version "3 - tick trade dedup by source"`). 이제 중복 방지 키는 `(종목, 거래일, 세션, 출처, 누적거래량)` 이다. 시뮬레이터를 돌린 날 KIS 를 붙여도 KIS 체결이 시뮬레이터 행 때문에 버려지지 않으므로, 확인 전에 `truncate` 할 필요가 없다. 출처별로 보려면 아래 조회의 `source` 열을 본다.

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d futures_risk_ops -c "select source, session, trade_date, count(*) from tick_trade group by 1,2,3 order by 3,1"'
```

같은 출처 안의 재수신(시나리오 B)은 여전히 한 번만 쌓인다. 모의투자와 실전은 둘 다 `KIS` 라서 이 키로 구분되지 않으므로, 모의투자 확인이 끝난 데이터는 실전을 붙이기 전에 직접 지운다.

모의투자(`kis-paper`)는 Flyway V4 부터 `source=KIS_PAPER` 로 적혀 실전(`KIS`)과 나뉜다. V4 이전에 모의투자로 받은 행은 `KIS` 로 남아 있으므로, 모두 모의투자에서 왔다고 확실하면 직접 바꾼다.

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d futures_risk_ops -c "update tick_trade set source='"'"'KIS_PAPER'"'"' where source='"'"'KIS'"'"'"'
```

### H. 전체 테스트

```bash
./gradlew test
```

130개(simulator 48, db 5, collector 76, risk 1)가 통과해야 한다. Docker 가 꺼져 있으면 PostgreSQL 테스트 11개는 건너뛴다. 끊김 실험은 `ReconnectExperimentTest` 가 자동으로도 한다(세 번 끊고 복구 시간, 누락 수량 = DB 빈틈, 중복 = 재전송 수 확인).

## 2부. 오늘 구현의 브리핑

### 한 줄 요약

연결이 끊기면 스스로 다시 붙어 재구독하고, 끊긴 동안 놓친 체결을 숫자로 보여 주며, 장 시간 캘린더(YAML)를 보고 세션 안에서만 접속하고 체결의 거래일·세션을 정한다.

### 핵심 단계 네 가지

1. **재접속을 상태 머신으로 바꿨다.** `IDLE → CONNECTING → SUBSCRIBED`, 끊기면 `BACKOFF` 로 가서 1, 2, 4, 8 ... 초(최대 30초)에 0~500ms 지터를 더해 기다린다. 구독 응답이나 첫 체결이 와야 `SUBSCRIBED` 이고, 그때 대기 시간을 처음으로 되돌린다. 상태는 스레드 하나(`ws-control`)에서만 바꾸고, 연결마다 세대 번호를 붙여 지난 연결의 늦은 콜백이 새 연결을 건드리지 못하게 했다.
2. **누락을 누적거래량으로 센다(재접속 직후 / 연결 중으로 나눠서).** 체결마다 `누적거래량 증가분` 과 `이번 체결 수량` 을 비교한다. 증가분이 크면 그만큼 놓친 것(GAP), 이미 본 값이면 다시 받은 것(REPEAT)이다. REPEAT 은 DB UNIQUE 가 버릴 행과 정확히 같아서, 두 숫자를 맞춰 보면 중복 방지가 실제로 동작하는지 알 수 있다. 연결은 살아 있는데 체결만 멈추는 경우는 별도로 `stalls` 로 센다.
3. **중복 방지 키에 출처를 넣었다(Flyway V3, 사용자 결정).** 시뮬레이터·KIS·리플레이 체결이 누적거래량만 같으면 서로를 버리던 문제를 없앴다. 같은 출처 안의 재수신은 그대로 막는다.
4. **장 시간을 코드 밖 YAML 로 뺐다.** `config/session-calendar.yaml` 에 주간·야간 시각, 휴장일, 특수 개장, 야간장 거래일 규칙이 있다. 같은 파일을 jar 에 넣어 collector 가 읽고, Day 8 에는 ConfigMap, Day 9 알람과 Day 16 장애 주입 안전장치도 이 파일 하나를 본다. `SessionCalendar` 는 입력만 받아 답하는 순수 함수(`isOpen`, `nextOpen`, `shouldBeConnected`, `classify`)라 시계 없이 테스트한다.

### 왜 지금 구현했나

증거금·마진콜 계산은 "지금 가격"이 믿을 만해야 의미가 있다. 끊김을 사람이 알아채 다시 켜야 하거나, 놓친 체결을 모른 채 지나가면 계산 결과를 믿을 수 없다. 또 야간장은 자정을 넘기므로 체결이 어느 '하루'에 속하는지 정하지 않으면 Day 7 일마감 배치가 틀린 하루를 모은다. 장 시간 밖에 붙어 있으면 KIS 에 의미 없는 연결을 유지하고, 조용한 시간을 장애로 오해한다.

### 앞으로 어떻게 쓰이나

- **Day 3:** 지표로 내보낸다. `SessionCalendar.isOpen` 이 `collector_market_session_open`, `StallWatch.lastTradeAt` 이 `collector_last_trade_received_timestamp_seconds`, 상태·재접속·누락 카운터가 나머지 지표의 재료다. 근월물 롤오버 재구독은 Day 3 개발 1(종목 마스터)에서 이 상태 머신에 "구독 목록 바꾸기"를 더하는 방식으로 붙인다.
- **Day 7:** 일마감 배치가 `trade_date` 로 하루를 모은다.
- **Day 9:** 알람이 `missing-rate`, `stalls`, 세션 열림 지표를 본다. 세션 밖 조용함은 알람에서 뺀다.
- **Day 24:** 수능일 특수 개장은 YAML 의 `overrides` 한 줄만 고친다.

### 아직 모르는 것 (가정)

- 야간장 체결의 거래일. '다음 거래일'로 가정했다(`night_trade_date`). KRX 야간 파생상품시장 업무규정으로 확인하면 값만 바꾸고 F 로 다시 계산한다.
- 야간장이 휴장일 전날 저녁에도 열리는지. 거래일 저녁에는 모두 열린다고 가정했다.
- 2026-11-19 수능일 장 시간(09:45~16:45로 가정), 12/31 연말 휴장. Day 24 에 KRX 공지로 확인한다. 2027년 휴장일은 아직 넣지 않았다.
- 누적거래량이 세션마다 0부터 다시 시작하는지(Day 1 부터의 가정). 누락 감지는 세션이 바뀌면 기준을 새로 잡으므로 어느 쪽이어도 누락으로 잘못 세지 않는다.
- KIS 가 재구독 직후 직전 체결을 다시 보내는지. 시뮬레이터 `resend.on-subscribe` 는 그 경우를 흉내 낼 뿐이다.
- 야간장 체결 간격. 공백 기준 60초는 가정이고, 실제로 받아 보고 조정한다.
- 야간장 체결은 별도 TR `H0MFCNT0` 로 온다(공식 샘플 `examples_llm/domestic_futureoption/krx_ngt_futures_ccnl`, 종목코드 예시 `101W9000`). 아직 구독하지 않는다. 필드는 샘플 기준 49개로 `H0IFCNT0` 와 거의 같지만 따로 확인해야 한다. 세션별 구독 전환은 Day 3 근월물 재구독과 함께 한다.
- 2026-10-28 모의투자 주간장 35분(08:45~09:20)에서 연결 중 빈틈이 38번, 95계약(0.59%) 있었다. 체결이 몰린 초마다 1~4계약씩이라 KIS 가 같은 순간 체결을 묶어 보낸 것으로 보이지만 확정하지 못했다. 그래서 빈틈을 재접속·연결 중으로 나눴다.
- 공백을 감지해도 다시 붙지는 않는다. 자동으로 다시 붙을지는 Day 9~10(알람·승인형 에이전트)에서 정한다.
