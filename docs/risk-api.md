# 리스크 API

`risk` 모듈이 여는 REST API 다. collector 가 쌓은 `tick_trade` 를 읽기만 하고, 계좌와 연동하지 않는다.
결과는 사용자가 넣은 가상 포지션을 계산한 값뿐이다. 매수·매도 판단은 하지 않는다.

## 실행

```
# DB 계정은 collector 와 같은 환경변수로 받는다. 값은 이 문서에 적지 않는다.
./gradlew :risk:bootRun
```

- 주소: `http://127.0.0.1:8082` (이 PC 에서만 보인다. 컨테이너에서는 `SERVER_ADDRESS=0.0.0.0`)
- 화면: `http://127.0.0.1:8082/swagger-ui.html`, 문서 JSON: `/v3/api-docs`
- 상태: `/actuator/health` (actuator 는 health 하나만 연다)
- 읽을 데이터 출처: `risk.source` (기본 `SIMULATOR`). KIS 모의투자 데이터를 보려면 `--risk.source=KIS_PAPER` 로 띄운다. 출처는 섞지 않는다.
- 종목을 안 주면 마스터의 최근월물(`month_class = 1`), 마스터가 없으면 가장 최근에 체결된 종목을 쓴다.

## API

| 요청 | 하는 일 | 응답의 시각 |
|---|---|---|
| `GET /api/risk/basis?symbol=` | 최신 체결의 선물가, 시장 베이시스, 거꾸로 구한 코스피200 지수, 이론가, 괴리율 | `tradedAt`(그 체결 시각) |
| `GET /api/risk/open-interest/daily?symbol=&days=5` | 거래일별 마지막 미결제약정과 전 거래일 대비 증감(최신이 먼저, days 1~60) | 거래일마다 `lastTradeAt` |
| `POST /api/risk/margin/simulate` | 가상 포지션의 손익, 위탁·유지증거금, 평가예탁금, 마진콜, 추가납부액, 마진콜 가격, 남은 폭 | `priceAt`(최신 체결을 썼을 때) |

- 베이시스·이론가·괴리율은 KIS 가 체결 메시지에 담아 보낸 값이다(Flyway V6 부터 저장). 그 전 행이면 `null` 이다.
- 지수 = 선물가 - 시장 베이시스 는 가정이다([kis-h0ifcnt0.md](kis-h0ifcnt0.md)). 야간장에는 주식시장이 닫혀 지수가 움직이지 않는다.
- 시각이 오래됐으면 수집이 멈춘 것일 수 있다. 화면에 값을 보여줄 때 시각을 같이 보여준다.
- 체결이 하나도 없으면 404 와 `{"error": "..."}`, 입력이 틀리면 400 이다.

### 가상 포지션 예시

```
curl -s -X POST http://127.0.0.1:8082/api/risk/margin/simulate \
  -H 'Content-Type: application/json' \
  -d '{"side":"LONG","contracts":1,"basePrice":400.00,"deposit":30000000,"price":398.50}'
```

| 필드 | 뜻 |
|---|---|
| `side` | `LONG`(매수) 또는 `SHORT`(매도) |
| `contracts` | 계약 수(1 이상) |
| `basePrice` | 기준가. 처음에는 진입가, 일일정산 뒤에는 직전 정산가 |
| `deposit` | 기준가 시점의 예탁금(원, 0 이상) |
| `price` | 평가할 가격. 빼면 최신 체결가를 쓰고 `priceSource` 가 `LATEST_TRADE` 가 된다 |
| `symbol` | `price` 를 뺐을 때 최신 체결가를 읽을 종목. 빼면 기본 종목 |

계산 규칙과 사례는 [margin-cases.md](margin-cases.md) 에 있다. 증거금률은 `risk/src/main/resources/application.yml` 의 `risk.margin.*` 이고, 거래소가 거의 매달 바꾸므로 매달 확인한다.
