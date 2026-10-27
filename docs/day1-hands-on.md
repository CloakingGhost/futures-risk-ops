# Day 1 직접 해보기 안내와 구현 브리핑

대상: PR 2 (브랜치 `feat/day1-collector-ingest`). 명령은 모두 Git Bash 기준이고 저장소 폴더(`futures-risk-ops`)에서 실행한다.

## 1부. 직접 해보는 시나리오

### 0. 준비 (한 번만)

1. Docker Desktop을 켠다.
2. 브랜치를 맞춘다.

```bash
cd /c/Users/Lee/side-project/finance/margin-watch/futures-risk-ops
git checkout feat/day1-collector-ingest
```

3. DB를 올린다.

```bash
docker compose up -d
docker compose ps
```

`healthy`가 보이면 된다.

4. 터미널마다 `.env`의 값을 환경변수로 불러온다. 값은 화면에 찍히지 않는다. (Gradle과 스프링은 `.env`를 읽지 않는다. 새 Git Bash 창을 열 때마다 다시 실행해야 한다.)

```bash
set -a; . ./.env; set +a
```

### 1. 시나리오 A: 시뮬레이터 체결이 DB에 쌓이는 것 보기 (핵심)

터미널 1: 가짜 체결 서버 (초당 100건)

```bash
./gradlew :simulator:run --args="--rate.ticks-per-second=100"
```

`ws://127.0.0.1:18080/tryitout`로 안내하는 줄이 나오면 된다.

터미널 2: 수집기 (환경변수를 불러온 터미널)

```bash
./gradlew :collector:bootRun
```

로그에서 순서대로 확인한다.
- `Migrating schema "public" to version "2 ..."` : Flyway가 V2를 적용했다. (이미 적용된 DB에서는 "up to date")
- `connected, subscribed 1 symbols`
- `H0IFCNT0 A01612: SUBSCRIBE SUCCESS`

터미널 3: DB 조회 (환경변수를 불러온 터미널)

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d futures_risk_ops -c "select source, session, trade_date, count(*), min(acc_volume), max(acc_volume) from tick_trade group by 1,2,3"'
```

몇 초 간격으로 두 번 실행해 `count`가 늘어나는지 본다. 초당 약 100건씩 늘면 정상이다. 최근 체결을 보려면 `select traded_at, price, qty, acc_volume from tick_trade order by id desc limit 5` 를 `-c` 뒤에 넣는다.

종료는 수집기에서 Ctrl+C. `ingest stopped: trades=..., written=..., duplicates=0, dropped=0` 줄이 나오고, trades와 written이 같으면 유실이 없다. 시뮬레이터도 Ctrl+C.

### 2. 시나리오 B: 끊김과 재접속 보기

시뮬레이터를 10초마다 연결을 끊게 띄운다.

```bash
./gradlew :simulator:run --args="--rate.ticks-per-second=20 --disconnect.after-seconds=10"
```

수집기 로그에 `connection closed` → `reconnecting in 1000 ms` → `connected, subscribed` 가 반복되는지 본다. 재접속할 때마다 구독을 다시 보내므로 체결이 다시 들어온다. 갑자기 TCP를 끊는 경우는 `--disconnect.abrupt=true`를 더한다.

시뮬레이터 창을 통째로 닫았다가 다시 켜 봐도 된다. 수집기는 1초, 2초, 4초... 최대 30초 간격으로 계속 다시 붙는다.

### 3. 시나리오 C: PINGPONG 되돌림 보기

```bash
./gradlew :simulator:run --args="--pingpong.interval-seconds=3"
```

시뮬레이터가 3초마다 PINGPONG을 보내고, 수집기가 그대로 돌려보낸다. 시뮬레이터는 종료할 때 `pongs=` 숫자를 남기지만, 이 값은 WebSocket 프레임 단위 pong이라 이번에 추가한 "텍스트로 되돌린 PINGPONG" 횟수와는 다르다. 눈으로 확인하려면 `./gradlew :collector:test --tests "*RealtimeClientTest*"`의 PINGPONG 테스트를 본다.

### 4. 시나리오 D: 중복 방지 보기 (주의 있음)

시뮬레이터는 켤 때마다 누적거래량이 처음부터 다시 시작한다. 그래서 같은 날 시뮬레이터를 껐다 켜면, 새로 온 체결이 이미 쌓인 행과 `(종목, 거래일, 세션, 누적거래량)`이 겹쳐 `ON CONFLICT DO NOTHING`으로 버려진다. 수집기 종료 로그의 `duplicates=` 값이 0이 아니면 이 때문이다. 이것이 중복 방지가 실제로 동작하는 모습이다.

깨끗하게 다시 시작하려면 쌓인 시뮬레이터 데이터를 지운다.

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d futures_risk_ops -c "truncate tick_trade"'
```

### 5. 시나리오 E: 조용한 중단 (현재의 한계 확인)

```bash
./gradlew :simulator:run --args="--rate.ticks-per-second=20 --stall.after-seconds=10 --stall.duration-seconds=15"
```

연결은 살아 있는데 체결만 15초 동안 멈춘다. 지금 수집기는 이것을 알아채지 못한다. 로그에 아무 경고가 없고 `count`만 멈춘다. 수신 공백 감지는 이후 날짜의 일이다.

### 6. 전체 테스트

```bash
./gradlew test
```

81개가 통과해야 한다. Docker가 꺼져 있으면 PostgreSQL 테스트는 실패하지 않고 건너뛴다(skipped).

### 7. KIS 연결: 모의투자 먼저, 그다음 실전

`.env`의 `KIS_APP_KEY`, `KIS_APP_SECRET`은 지금 쓰는 쪽(모의투자 또는 실전)의 값이어야 한다. 모의투자 키로 실전 서버에 붙거나 그 반대로 붙으면 접속키 발급에서 거절된다. 환경변수를 불러온 터미널에서:

```bash
# 모의투자 (먼저)
./gradlew :collector:bootRun --args="--spring.profiles.active=kis-paper"

# 실전 (야간장 등에서)
./gradlew :collector:bootRun --args="--spring.profiles.active=kis"
```

| 프로필 | 웹소켓 | 접속키 발급(REST) |
|---|---|---|
| `kis-paper` | `ws://ops.koreainvestment.com:31000/tryitout` | `https://openapivts.koreainvestment.com:29443/oauth2/Approval` |
| `kis` | `ws://ops.koreainvestment.com:21000/tryitout` | `https://openapi.koreainvestment.com:9443/oauth2/Approval` |

도메인은 공식 샘플 `kis_devlp.yaml`의 값이다. 이 계정(모의투자 계좌)으로 주문하는 코드는 이 저장소에 없다. 수집기는 시세 구독만 한다.

볼 것:
- 접속키(`approval_key`)를 받았는지. 키가 없으면 시작 단계에서 분명한 오류로 실패하고, 발급이 거절되면 HTTP 상태 코드만 로그에 남는다(키 값은 남지 않는다).
- `connected, subscribed 1 symbols`와 `SUBSCRIBE SUCCESS`가 오는지, 아니면 `rejected (rt_cd=1 ...)` 경고가 나오는지. 거절 메시지(`msg1`)가 원인을 알려 준다.
- 구독 키(`tr_key`)가 `A01612` 형식으로 통하는지. 안 통하면 `--collector.symbols=<다른 형식>`으로 바꿔 본다.
- 모의투자 서버가 `H0IFCNT0` 체결을 실제로 내보내는지. 구독은 성공했는데 체결이 오지 않으면 장 시간(모의투자도 장중에만 시세가 온다)이나 모의투자 서버의 제공 범위를 의심한다. 이 경우 수집기 쪽 문제가 아니다.
- 체결이 오면 `tick_trade.source`가 `KIS`로 쌓인다. 모의투자 데이터도 `KIS`로 적히므로, 실전 데이터와 섞이지 않게 확인이 끝나면 지운다(`truncate tick_trade`).
- 확인한 사실(키 형식, 체결 제공 여부, 누적거래량이 야간장에서 0부터 시작하는지)은 `docs/kis-h0ifcnt0.md`의 "확인할 것"에 적는다.

받은 실제 메시지를 파일로 남길 때는 `local-data/` 폴더에만 둔다. 저장소, 테스트, 문서, 이슈, PR에 올리지 않는다.

## 2부. 오늘 구현의 브리핑

### 한 줄 요약

시뮬레이터 → 웹소켓 → 파서 → 배치 적재 → PostgreSQL의 첫 수집 경로를 만들었고, 같은 코드가 KIS 실서버에도 붙도록 설정만 분리했다.

### 핵심 단계 세 가지

1. **DB 구조를 먼저 정했다 (계획 V2 변경).** 적재 코드를 쓰기 전에 마이그레이션을 `db` 모듈로 옮기고 Flyway V2를 만들었다. 체결 메시지에는 시각(HHMMSS)만 있고 날짜가 없다. 야간장은 자정을 넘기므로 `trade_date`와 `session`을 행마다 적어 둔다. 같은 체결이 두 번 들어가지 않게 `(종목, 거래일, 세션, 누적거래량)`에 UNIQUE를 걸었다.
2. **같은 코드로 시뮬레이터와 KIS에 붙는다.** 접속 주소와 접속키 발급 방식만 설정(`application.yml` / `application-kis.yml`)으로 갈린다. 장 시간이나 승인을 기다리지 않고 개발하고, 실데이터는 설정만 바꿔 연결한다는 `CLAUDE.md` 원칙을 코드로 옮긴 것이다.
3. **수신이 DB를 기다리지 않는다.** 웹소켓 수신 스레드는 큐에 넣기만 하고, 별도 스레드가 최대 500건 또는 0.2초마다 한 번에 넣는다. 큐가 차거나 DB 쓰기가 실패하면 버린 건수를 세어 드러낸다. 유실을 숨기지 않는 것이 이 구조의 목적이다.

### 왜 지금 구현했나 (정체성)

이 프로젝트는 "내 포지션 기준 증거금 여유와 마진콜 위험을 계산하는 셀프 호스팅 도구"다. 그 계산의 재료가 실시간 체결과 미결제약정이고, 수집이 끊기거나 중복되거나 날짜가 틀리면 그 위에 올라가는 모든 계산이 틀린다. 그래서 첫 기능이 화려한 화면이 아니라 "믿을 수 있는 수집"이다. 마진콜 경보 같은 기능은 이 데이터가 있어야 시작할 수 있다.

### 앞으로 어떻게 쓰이나

- **Day 2:** 세션 캘린더가 오늘의 임시 거래일 규칙을 넘겨받고, 재접속 중 누락 감지가 더해진다.
- **Day 7 무렵:** `tick_trade`를 모아 일마감 바(daily_bar)를 만드는 배치의 원재료가 된다. 거래일 열이 있어서 "하루"를 정할 때 쌓인 데이터를 고쳐 다시 채울 필요가 없다.
- **Day 16:** 저장된 체결을 같은 프레임으로 다시 내보내는 리플레이어가 시뮬레이터의 두 번째 체결 소스로 들어온다. 오늘 만든 `TradeSource` 인터페이스와 `source = REPLAY` 값이 그 자리다.
- **risk 모듈:** 베이시스, 미결제약정, 증거금, 마진콜 계산이 `tick_trade`를 읽는다. 마이그레이션은 collector 한 곳에서만 실행하도록 정리했기 때문에 두 앱이 서로 스키마를 건드리지 않는다.

### 아직 모르는 것 (가정)

- 모의투자 서버가 `H0IFCNT0` 체결을 내보내는지, 구독 키(`tr_key`)가 `A01612` 형식으로 통하는지. (웹소켓·REST 도메인은 공식 샘플로 확인했다.)
- 누적거래량이 주간·야간장마다 0부터 다시 시작하는지 (그래서 UNIQUE에 세션을 넣었다)
- 필드 값의 의미(부호, 단위). 형식만 공식 샘플 기준이고 값은 가정이다.
- 근월물 롤오버 자동 재구독과 수신 공백 감지는 아직 없다.
