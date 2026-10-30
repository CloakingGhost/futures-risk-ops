# 로컬 개발 환경

## 필요한 것

- JDK 17 이상(Gradle 래퍼가 Gradle 8.14.3 을 내려받는다)
- Docker Desktop (PostgreSQL, 앱 컨테이너, DB 테스트용). DB 테스트는 Testcontainers 로 임시 PostgreSQL 을 띄우고, Docker 에 못 붙으면 실패하지 않고 건너뛴다. `./gradlew test` 뒤 `build/reports/tests/test/index.html` 에서 skipped 가 0 인지 본다.
- 작업 폴더 경로에 한글이 없어야 한다. Windows 에서 테스트 실행기가 한글 경로의 클래스를 못 찾는 문제가 있었다.

## .env

`.env.example` 의 이름을 `.env` 로 복사해 값을 채운다. `.env` 는 커밋하지 않는다.

| 이름 | 쓰는 곳 |
|---|---|
| `KIS_APP_KEY`, `KIS_APP_SECRET` | KIS 실전 API (`kis` 프로필) |
| `KIS_PAPER_APP_KEY`, `KIS_PAPER_APP_SECRET` | KIS 모의투자 API (`kis-paper` 프로필) |

시뮬레이터는 키가 필요 없다.
| `POSTGRES_USER`, `POSTGRES_PASSWORD` | docker compose 와 collector |

`docker compose` 는 `.env` 를 자동으로 읽는다. Gradle 과 스프링은 읽지 않으므로 같은 터미널에서 환경변수로 넣는다.

```
# PowerShell
$env:POSTGRES_USER = "..."
$env:POSTGRES_PASSWORD = "..."
```

## DB 띄우고 마이그레이션 확인

```
docker compose up -d
docker compose ps                    # postgres 가 healthy 인지
./gradlew :collector:bootRun         # 로그에 Flyway 가 V1 을 적용했다는 줄이 나온다
docker compose exec postgres psql -U <POSTGRES_USER> -d futures_risk_ops -c "\dt"
```

테이블 4개(`instrument`, `futures_quote_snapshot`, `tick_trade`, `flyway_schema_history`)가 보이면 된다.
마이그레이션 파일은 `db` 모듈에 있고, 실행은 collector 만 한다. 지금 버전은 V2(`tick_trade` 의 `trade_date`·`session`, 중복 방지 키)다.

## 시뮬레이터로 수집해 보기

터미널 두 개를 쓴다.

```
./gradlew :simulator:run --args="--rate.ticks-per-second=100"   # 터미널 1
./gradlew :collector:bootRun                                     # 터미널 2 (DB 환경변수를 넣은 터미널)
docker compose exec postgres psql -U <POSTGRES_USER> -d futures_risk_ops -c "select source, session, trade_date, count(*) from tick_trade group by 1,2,3"
```

- 기본 설정은 `ws://127.0.0.1:18080/tryitout`, 종목 `A01612`, `source=SIMULATOR` 다(`collector/src/main/resources/application.yml`).
- 수집기는 10초마다 `ingest stats: trades=..., written=..., duplicates=..., dropped=...` 를 로그에 남긴다.
- 끌 때는 Ctrl+C 다. 정상 종료하면 큐에 남은 체결을 다 쓰고 `ingest stopped: ...` 를 남기지만, Windows 에서 `gradlew bootRun` 을 Ctrl+C 로 끄면 종료 훅이 돌지 않아 그 줄이 안 나오고 마지막 배치(최대 0.2초 분량)가 빠질 수 있다. 그때는 `ingest stats` 줄을 본다.
- `trade_date` 는 지금 임시 규칙(수신 한국 날짜, 자정 넘김 보정)이다. Day 2 세션 캘린더가 넘겨받는다.

## 앱을 컨테이너로 띄우기 (compose)

`docker compose up -d` 는 지금처럼 PostgreSQL 만 띄운다. 앱까지 띄울 때만 `--profile app` 을 붙인다.

```
docker compose --profile app up -d --build   # PostgreSQL + simulator + collector + risk. 처음 빌드는 몇 분 걸린다
docker compose --profile app ps              # collector, risk 가 (healthy) 인지
curl http://127.0.0.1:8082/api/risk/basis    # 리스크 API (docs/risk-api.md)
docker compose --profile app logs -f collector
docker compose --profile app down            # 끄기. DB 데이터는 남는다(-v 를 붙이면 DB 볼륨까지 지운다)
```

- 기본은 시뮬레이터에 붙는다. KIS 모의투자는 `.env` 에 `COLLECTOR_PROFILE=kis-paper`, `RISK_SOURCE=KIS_PAPER` 를 넣고 다시 `up -d` 한다.
- 이미지에는 키가 없다. `.dockerignore` 가 `.env` 를 빌드에서 빼고, 키와 DB 계정은 compose 가 실행할 때 환경변수로만 넘긴다.
- 앱 포트(collector 8081, risk 8082)는 이 PC(127.0.0.1)에만 연다. gradlew 로 띄운 collector·risk 가 있으면 먼저 끈다(포트가 겹친다).
- 이미지 하나만 빌드할 때는 저장소 루트에서 `docker build -f collector/Dockerfile -t futures-risk-ops-collector:local .` 처럼 한다. 테스트는 이미지 안에서 돌리지 않는다.

## KIS 실시간 체결 받기

환경변수를 불러온 터미널에서 모의투자를 먼저 확인한다. 자세한 절차와 볼 것은 `docs/day1-hands-on.md` 7번.

```
./gradlew :collector:bootRun --args="--spring.profiles.active=kis-paper"   # 모의투자
./gradlew :collector:bootRun --args="--spring.profiles.active=kis"         # 실전
```

- 도메인(공식 샘플 `kis_devlp.yaml`): 모의투자 웹소켓 `ws://ops.koreainvestment.com:31000`, REST `https://openapivts.koreainvestment.com:29443` / 실전 웹소켓 `:21000`, REST `https://openapi.koreainvestment.com:9443`. 접속키는 `/oauth2/Approval` 로 받는다.
- 프로필마다 읽는 환경변수가 다르다. `kis` 는 `KIS_APP_KEY`/`KIS_APP_SECRET`, `kis-paper` 는 `KIS_PAPER_APP_KEY`/`KIS_PAPER_APP_SECRET` 이라, 두 쌍을 `.env` 에 같이 두고 프로필만 바꿔도 된다. 쓰는 쪽 값이 없으면 시작할 때 그 환경변수 이름을 알려 주며 실패한다.
- 구독 키(`tr_key`)가 `A01612` 형식으로 되는지 확인한다. 안 되면 `--collector.symbols=<다른 형식>` 으로 바꿔 본다.
- 받은 시세는 로컬 DB(`source=KIS`)에만 쌓인다. 파일로 남길 때는 `local-data/` 에만 둔다(`.gitignore`).

## KIS 연결 확인 (curl)

2026-10-26 실전 도메인 `openapi.koreainvestment.com:9443`에서 확인했다. 응답 본문은 저장소에 넣지 않는다.

1. 토큰: `POST /oauth2/tokenP`, 본문 `{"grant_type":"client_credentials","appkey":...,"appsecret":...}`. 유효 24시간(`expires_in` 86400).
2. 선물 현재가: `GET /uapi/domestic-futureoption/v1/quotations/inquire-price`, 헤더 `tr_id: FHMIF10000000`, `custtype: P`, 질의 `FID_COND_MRKT_DIV_CODE=F`, `FID_INPUT_ISCD=<종목코드>`.
   - 종목코드는 `A01612`(코스피200 F 202612) 형식으로 조회됐다. `101W12` 같은 옛 형식은 `rt_cd=0`이지만 빈 결과가 온다.
   - 앱키·시크릿은 `.env`에서 읽어 헤더로만 넣는다.
