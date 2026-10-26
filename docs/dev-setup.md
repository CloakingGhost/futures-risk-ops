# 로컬 개발 환경

## 필요한 것

- JDK 17 이상(Gradle 래퍼가 Gradle 8.14.3 을 내려받는다)
- Docker Desktop (PostgreSQL 용)
- 작업 폴더 경로에 한글이 없어야 한다. Windows 에서 테스트 실행기가 한글 경로의 클래스를 못 찾는 문제가 있었다.

## .env

`.env.example` 의 이름을 `.env` 로 복사해 값을 채운다. `.env` 는 커밋하지 않는다.

| 이름 | 쓰는 곳 |
|---|---|
| `KIS_APP_KEY`, `KIS_APP_SECRET` | KIS API (시뮬레이터는 필요 없음) |
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

## KIS 연결 확인 (curl)

2026-10-26 실전 도메인 `openapi.koreainvestment.com:9443`에서 확인했다. 응답 본문은 저장소에 넣지 않는다.

1. 토큰: `POST /oauth2/tokenP`, 본문 `{"grant_type":"client_credentials","appkey":...,"appsecret":...}`. 유효 24시간(`expires_in` 86400).
2. 선물 현재가: `GET /uapi/domestic-futureoption/v1/quotations/inquire-price`, 헤더 `tr_id: FHMIF10000000`, `custtype: P`, 질의 `FID_COND_MRKT_DIV_CODE=F`, `FID_INPUT_ISCD=<종목코드>`.
   - 종목코드는 `A01612`(코스피200 F 202612) 형식으로 조회됐다. `101W12` 같은 옛 형식은 `rt_cd=0`이지만 빈 결과가 온다.
   - 앱키·시크릿은 `.env`에서 읽어 헤더로만 넣는다.
