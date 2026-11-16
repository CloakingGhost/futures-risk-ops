# KIS 일봉 수집 (daily_bar)

2026-11-16 Day 7 에 더했다. 일봉은 `tick_trade` 에서 계산하지 않는다. KIS 가 주는 값을 KIS 에서 받아 그대로 둔다.

## 무엇을 하나

| 항목 | 내용 |
|---|---|
| API | KIS 선물옵션기간별시세(일/주/월/년), `FHKIF03020100`, `GET /uapi/domestic-futureoption/v1/quotations/inquire-daily-fuopchartprice` (읽기 전용 시세 조회 하나뿐) |
| 명세 | `KIS-API/[국내선물옵션]_기본시세.xlsx` 의 '선물옵션기간별시세(일_주_월_년)' 시트(저장소 밖 로컬 자료) |
| 요청 | 시장 분류 코드 `F`(지수선물, 주간)와 `CM`(야간선물), 종목은 구독 중인 근월물(만기 전날부터는 다음 결제월물도), 기간 `D`, 시작·종료일 |
| 저장 | `daily_bar`(Flyway V7). 키 `(instrument_id, trade_date, market, source)`. 시가·고가·저가·종가(`futs_prpr`)·누적거래량·누적거래대금·`mod_yn`·`fetched_at`. 미결제 열은 없다 |
| 언제 | 거래일마다 `max(16:00, 주간장 마감 + 15분)`. 세션 캘린더가 휴장일과 마감 시각을 정한다(수능일처럼 마감이 늦으면 그날은 늦게 돈다) |
| 얼마나 | 오늘을 포함한 최근 N영업일(기본 5). 같은 구간을 다시 받으면 KIS 새 값으로 덮어쓴다(`ON CONFLICT DO UPDATE`). 며칠 놓쳐도 다음 실행이 메운다 |
| 켜는 곳 | `kis`·`kis-paper` 프로필. 시뮬레이터에는 REST 가 없어 꺼져 있다 |
| 인증 | 앱키·시크릿은 웹소켓 접속키와 같은 환경변수(`KIS_APP_KEY`/`KIS_APP_SECRET`, 모의투자 `KIS_PAPER_*`). REST 접근토큰은 `/oauth2/tokenP` 로 따로 받아 메모리에만 두고 만료 1시간 전에 다시 받는다 |
| 지표 | `collector_daily_close_last_success_timestamp_seconds` ([metrics.md](metrics.md)) |

설정(`collector.daily-close.*`, 기본값은 `application.yml`):

| 키 | 기본 | 뜻 |
|---|---|---|
| `enabled` | false (kis·kis-paper 는 true) | 켜면 KIS REST 접속 설정이 필요하다. 시뮬레이터 설정에서 켜면 시작할 때 실패한다 |
| `run-at` | 16:00 | 이 한국 시각부터 받는다 |
| `after-close` | 15m | 주간장 마감이 `run-at` 보다 늦은 날은 마감 + 이 시간 |
| `lookback-days` | 5 | 오늘 포함 영업일 수, 1~100 (KIS 가 한 번에 주는 최대 100건) |
| `markets` | `[F, CM]` | 하나라도 실패하면 그 실행은 성공으로 세지 않는다. CM 이 안 되는 것으로 확인되면 여기서 뺀다 |

## 가정과 확인 상태

공식 문서나 실제 호출로 확인하지 못한 것은 '가정'이다. 첫 실호출 뒤 아래 표를 날짜와 함께 고친다.

| 가정 | 상태 |
|---|---|
| `/oauth2/tokenP` 요청 본문은 `grant_type, appkey, appsecret`, 응답에 `access_token`·`expires_in`(초) | 확인함(2026-10-26 실전 도메인 curl, [dev-setup.md](dev-setup.md)). 유효 24시간(`expires_in` 86400) |
| 종목코드는 DB 의 단축코드(`A01612` 형식)를 그대로 보내면 된다 | 현재가 TR(`FHMIF10000000`)에서는 확인함(2026-10-26, 옛 형식 `101W12` 는 빈 결과). **일봉 TR 에서는 미확인**(명세 예시는 옛 형식 `101S06`) |
| 시장 코드 `CM`(야간선물)으로도 같은 방식으로 받아진다 | 미확인 |
| KIS 일봉은 주간장 기준이다(야간장 체결이 합쳐지지 않는다) | 미확인 |
| 응답의 현재가(`futs_prpr`)는 과거 일자에서 그날 종가다 | 미확인 |
| 거래가 없는 날은 행이 없거나 가격이 모두 0 이다(0 인 행은 버린다) | 미확인 |
| 주간장 마감 15분 뒤면 그날 일봉이 확정돼 있다 | 미확인 |
| 쿼리 이름을 명세 표의 대문자(`FID_COND_MRKT_DIV_CODE` ...)로 보내도 된다 | 미확인 |
| `mod_yn`(변경 여부)의 뜻 | 문서에 없음. 값만 보관한다 |
| 모의투자(`kis-paper`)도 일봉을 준다 | 문서상 지원. 미확인 |

## 첫 실호출 기록

(아직 없다. 장이 끝난 거래일에 `kis` 프로필로 collector 를 띄우고, 로그의 `daily bars F A01612 ...: n rows` 와 `daily close ...: requests=..., failures=...` 줄, 아래 조회 결과를 보고 날짜와 함께 적는다. **실제 응답 원문·가격 값은 옮기지 않는다**(절대 규칙 3). 행 수, 되고 안 되고, 날짜 범위 같은 사실만 적는다.)

| 날짜 | 프로필 | 확인한 것 | 결과 |
|---|---|---|---|
| | | | |

## 조회 쿼리

모두 읽기 전용이다. 접속 방법은 로컬 `docs/db-queries.md`(저장소에 올리지 않는 파일)나 [dev-setup.md](dev-setup.md) 를 본다.

```sql
-- 최근 일봉 20건(종목·시장·출처별)
SELECT i.symbol, b.market, b.source, b.trade_date,
       b.open_price, b.high_price, b.low_price, b.close_price,
       b.acc_volume, b.acc_trade_value, b.mod_yn,
       b.fetched_at AT TIME ZONE 'Asia/Seoul' AS fetched_kst
FROM daily_bar b
JOIN instrument i ON i.id = b.instrument_id
ORDER BY b.trade_date DESC, i.symbol, b.market, b.source
LIMIT 20;

-- 거래일·시장·출처별 행 수. 최근 N영업일이 빠짐없이 들어왔는지(휴장일은 행이 없는 것이 맞다)
SELECT trade_date, market, source, count(*) AS bars, max(fetched_at AT TIME ZONE 'Asia/Seoul') AS last_fetched_kst
FROM daily_bar
GROUP BY trade_date, market, source
ORDER BY trade_date DESC, market, source;

-- 일봉(KIS) 종가와 그날 마지막 체결가 비교(tick_trade 는 비교용으로만 본다. 일봉을 이것으로 만들지 않는다)
-- 주간 일봉(F)과 주간장(DAY) 마지막 체결을 같은 출처끼리 비교한다. 다르면 일봉 기준 가정을 의심한다
SELECT b.trade_date, i.symbol, b.close_price AS bar_close, t.price AS last_trade_price
FROM daily_bar b
JOIN instrument i ON i.id = b.instrument_id
JOIN LATERAL (
    SELECT price FROM tick_trade
    WHERE instrument_id = b.instrument_id AND trade_date = b.trade_date AND session = 'DAY' AND source = b.source
    ORDER BY traded_at DESC, acc_volume DESC
    LIMIT 1
) t ON true
WHERE b.market = 'F'
ORDER BY b.trade_date DESC, i.symbol
LIMIT 20;

-- 일봉 누적거래량과 그날 tick_trade 의 마지막 누적거래량 비교(주간장만, 수집 빈틈 확인용)
SELECT b.trade_date, i.symbol, b.acc_volume AS bar_volume, t.last_acc AS tick_last_acc
FROM daily_bar b
JOIN instrument i ON i.id = b.instrument_id
JOIN LATERAL (
    SELECT max(acc_volume) AS last_acc FROM tick_trade
    WHERE instrument_id = b.instrument_id AND trade_date = b.trade_date AND session = 'DAY' AND source = b.source
) t ON true
WHERE b.market = 'F'
ORDER BY b.trade_date DESC, i.symbol
LIMIT 20;
```

## 운영에서 알아 둘 것

- collector 가 16:00 에 꺼져 있었으면 그날은 건너뛴다. 다음 거래일 실행이 최근 N영업일을 다시 받아 메운다. 5영업일을 넘게 꺼 두었다면 `lookback-days` 를 늘려 한 번 돌린다(남은 일: 시작 직후 보충 실행).
- 일부 요청만 실패해도 나머지는 저장된다. 지표 값이 멈추면 로그의 `daily close failed for ...` 줄을 본다(메시지에는 HTTP 코드나 `rt_cd`/`msg_cd` 만 있고 키·토큰은 없다).
- 401·403 을 받으면 접근토큰을 버리고 다음 실행에서 새로 받는다.
- 한 번에 100건을 넘게 요청하지 않는다. 이 구현은 연속조회(`tr_cont`)를 하지 않는다.
- DB 를 옮길 때(Day 8)는 일봉을 따로 옮기지 않아도 된다. 다음 실행이 최근 N영업일을 다시 받는다. 그보다 오래된 일봉은 옮기지 않으면 사라진다.
