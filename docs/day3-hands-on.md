# Day 3 직접 해보기 안내와 구현 브리핑

대상: 브랜치 `feat/day3-near-month-quote`. 명령은 Git Bash 기준이고 저장소 폴더에서 실행한다. 준비(Docker, DB, `.env` 불러오기)는 `docs/day1-hands-on.md` 0번과 같다.

```bash
cd /c/Users/Lee/side-project/finance/margin-watch/futures-risk-ops
git checkout feat/day3-near-month-quote
docker compose up -d
set -a; . ./.env; set +a
./gradlew test        # Docker Desktop 을 켜 두면 DB 테스트까지 돈다
```

`ingest stats` 줄에 `front`(근월물 결제월), `subs`(구독 수), `quotes`, `quotes-written`, `quotes-dropped` 가 늘었다. 구독 목록이 바뀌면 다음 줄이 남는다.

```
subscriptions for DAY 2026-12-09 (trade date 2026-12-09, front 2026-12, codes from master): [H0IFCNT0:A01612, H0IFASP0:A01612, H0IFCNT0:A01703, H0IFASP0:A01703]
front month rolled over 2026-12 -> 2027-03
```

## 1부. 직접 해보는 시나리오

### A. 시뮬레이터로 체결과 호가 함께 받기

```bash
./gradlew :simulator:run --args="--rate.ticks-per-second=20"     # 터미널 1
./gradlew :collector:bootRun                                     # 터미널 2
```

볼 것: `subscriptions for ... [H0IFCNT0:A01612, H0IFASP0:A01612]`(밤에 돌리면 `H0MFCNT0:A01612` 하나), `quotes-written` 이 늘어난다.

```sql
SELECT count(*), min(quoted_at), max(quoted_at) FROM tick_quote;
SELECT quoted_at, ask_prices, bid_prices, ask_qtys, bid_qtys FROM tick_quote ORDER BY id DESC LIMIT 3;
```

### B. 만기 롤오버를 장 밖에서 보기 (시작 시각 설정)

두 프로그램의 시계를 12월물 만기일 주간장 끝 직전으로 맞춘다. 시뮬레이터 전용 설정이다(KIS 프로필에서 `collector.clock-start` 를 쓰면 시작할 때 거절한다).

```bash
./gradlew :simulator:run --args="--rate.ticks-per-second=20 --clock.start=2026-12-10T15:43:00"
./gradlew :collector:bootRun --args="--collector.clock-start=2026-12-10T15:43:00"
```

볼 것:
1. 시작 직후 `front 2026-12`, 구독 4개(12월물·3월물 각각 체결과 호가). 만기 전날부터 겹쳐 구독한다.
2. 약 2분 뒤(15:45 주간장 끝) `front month rolled over 2026-12 -> 2027-03`, `subscriptions for NIGHT 2026-12-10 ... [H0MFCNT0:A01703]`.
3. `ws state` 줄이 새로 생기지 않는다. 구독 변경은 연결을 끊지 않고 해제(tr_type 2)와 등록(tr_type 1)만 보낸다.

`--clock.start=2026-12-09T08:50:00` 으로 시작하면 만기 전날 아침부터 두 월물이 함께 구독되는 것을 볼 수 있다.

### C. KIS 종목 마스터 받기 (장 밖에서도 된다)

```bash
./gradlew :collector:bootRun --args="--spring.profiles.active=kis-paper"
```

볼 것: `master parsed: lines=..., kospi200-futures=7, other-types=..., invalid=0`. 경고 `differs from the code rule` 이나 `master says the front month is` 가 없어야 한다.

```sql
SELECT symbol, standard_code, name, contract_month, month_class, expiry_date, master_updated_at
  FROM instrument WHERE contract_month IS NOT NULL ORDER BY contract_month;
```

`expiry_date` 는 마스터에 없는 값이라 collector 가 결제월 둘째 목요일(휴장이면 앞당김)로 계산해 넣는다. 2026-12 는 2026-12-10 이어야 한다.

### D. 오늘 18:00 야간장: H0MFCNT0 와 야간 코드 확인

```bash
./gradlew :collector:bootRun --args="--spring.profiles.active=kis-paper"
```

볼 것:
- 17:59 `subscriptions for NIGHT ... [H0MFCNT0:A01612]` 뒤 `H0MFCNT0 A01612: SUBSCRIBE SUCCESS` 인지, `rejected (rt_cd=...)` 인지.
- 체결이 오면 `trades` 가 늘고 `parse-errors=0` 이어야 한다(49필드 파싱 확인).
- KIS 문서는 H0MFCNT0 를 '모의투자 미지원'이라 적었다. 거절되면 실전 프로필(`kis`)로 같은 확인을 한다. 둘 다 거절되면 코드 형식 문제일 수 있으니 로그 줄(값은 빼고 rt_cd·msg_cd)을 알려 준다.

### E. 내일 08:44 주간장: 호가 확인

모의투자에서 H0IFASP0 가 오는지 본다. 오지 않으면 `quotes=0` 으로 남고 체결 수집은 그대로 된다. 그때는 `--collector.instruments.quotes=false` 로 끈다.

## 2부. 오늘 구현 브리핑

- **한 줄 요약**: 사람이 종목 코드를 바꾸지 않아도 근월물을 골라 받고, 만기에는 다음 결제월물을 하루 먼저 겹쳐 받은 뒤 넘어가며, 주간·야간 TR 을 세션에 맞춰 바꾸고 호가까지 쌓는다.
- **데이터 흐름**: KIS 마스터 다운로드 → zip 풀기 → 바이트 줄 단위 cp949 디코딩·필드 수 검증(`KisMasterParser`) → 코스피200 선물만 `FuturesContract` → instrument Upsert(`InstrumentRepository`) → 메모리 목록 교체(`MasterRefresher`). 1초마다 `SubscriptionPlanner` 가 지금(또는 다음) 세션의 구독 목록을 만들고, 바뀌었으면 `RealtimeClient.setSubscriptions` 가 차이만 보낸다.
- **만기 규칙**: 결제월 3·6·9·12월 둘째 목요일, 휴장이면 앞의 거래일(`FuturesExpiry`). 휴장일은 `config/session-calendar.yaml` 을 본다.
- **롤오버**: 만기 전날(앞 거래일) 세션부터 만기일 주간장까지 두 월물, 만기일 야간장부터 다음 결제월물만(`NearMonthPlanner`).
- **마스터를 못 받으면**: DB 에 있던 값 → 그것도 없으면 코드 규칙(`A01` + 연도 끝자리 + 월). 수집은 멈추지 않는다.
- **새 테이블·열 (Flyway V5)**: instrument 에 `standard_code`, `contract_month`, `month_class`, `master_updated_at`. `tick_quote`(5단계 호가 배열). 호가는 중복 판별 키가 없어 UNIQUE 를 두지 않았다.

### 가정과 아직 모르는 것

- 야간 종목코드 = 주간과 같은 `A01612` 형식(야간 마스터로 확인, 실수신은 D 에서 확인).
- 만기일 야간장에서는 만기 종목이 거래되지 않는다.
- 마스터 파일은 새벽에 갱신된다고 보고 07:30 에 받는다(`collector.instruments.master.refresh-at`).
- 웹소켓 구독 해제(tr_type 2)를 KIS 가 공식 샘플처럼 받아 준다.
- H0IFASP0·H0MFCNT0 의 모의투자 제공 여부.
