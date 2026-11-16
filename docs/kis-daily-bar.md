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
| `markets` | `[F]` | F 주간, CM 야간. CM 은 첫 실호출로 되는 것을 확인한 뒤 `[F, CM]` 으로 켠다(2026-11-16 결정). 하나라도 실패하면 그 실행은 성공으로 세지 않는다 |
| `run-on-start` | false | true 면 시작 직후 한 번 받는다. 장중이거나 오늘 실행 시각 전이면 오늘은 빼고 직전 거래일까지만 받는다(휴장일·주말에 켜면 마지막 거래일까지) |

compose 에서는 `.env` 의 `DAILY_CLOSE_RUN_ON_START=true`, `DAILY_CLOSE_MARKETS=F,CM`, `DAILY_CLOSE_LOOKBACK_DAYS=15` 로 바꾼다(비우면 기본값). `./gradlew` 로 띄울 때는 `--collector.daily-close.run-on-start=true` 를 붙인다.

## 일봉에 담기는 것과 하루 흐름

- **담기는 것**: 영업일, 시가, 고가, 저가, 종가(응답의 현재가 필드), 누적 거래량, 누적 거래대금, `mod_yn`, 받은 시각. 미결제·베이시스·전일대비는 응답에 있어도 저장하지 않는다.
- **하루에 한 번, 장 마감 뒤에 KIS 가 만든 일봉을 가져온다.** 하루 동안 우리가 집계하지 않는다. 장중에는 `daily_bar` 의 가장 최근 행이 전 거래일이다.
- **진행 중인 오늘 일봉은 저장하지 않는다.** 일봉 API 의 종가 필드 이름이 '현재가'라 장중에 부르면 진행 중인 값이 올 수 있다(가정). 그래서 시작 직후 실행은 오늘 실행 시각 전에는 직전 거래일까지만 받는다.
- **장중 일봉이 필요해지면**: 체결 메시지(H0IFCNT0)에 오늘의 시가·최고가·최저가·현재가·누적거래량이 매 체결마다 실려 온다(`docs/kis-h0ifcnt0.md` 5~8번). 지금은 현재가만 저장한다. 소비처(API·대시보드)가 생길 때 이 값을 읽는 방법을 따로 정한다(이번 범위 밖).

## 가정과 확인 상태

공식 문서나 실제 호출로 확인하지 못한 것은 '가정'이다. 첫 실호출 뒤 아래 표를 날짜와 함께 고친다.

| 가정 | 상태 |
|---|---|
| `/oauth2/tokenP` 요청 본문은 `grant_type, appkey, appsecret`, 응답에 `access_token`·`expires_in`(초) | 확인함(2026-10-26 실전 도메인 curl, [dev-setup.md](dev-setup.md)). **공식 문서(2026-11-17 확인)**: 유효 24시간, 6시간 안에 다시 요청하면 직전 토큰을 돌려준다. **실호출(2026-11-17)**: 직전 토큰이 돌아와도 `expires_in` 은 86400 그대로였고 실제 만료는 응답의 `access_token_token_expired`(한국 시각)였다. 그래서 코드는 이 필드를 우선 쓴다 |
| 종목코드는 DB 의 단축코드(`A01612` 형식)를 그대로 보내면 된다 | **확인함(2026-11-16, 일봉 TR 도 `A01612` 로 받아진다)**. 현재가 TR 에서는 2026-10-26 에 확인했다(옛 형식 `101W12` 는 빈 결과) |
| 시장 코드 `CM`(야간선물)으로도 같은 방식으로 받아진다 | **확인함(2026-11-16, `A01612` 로 요청 성공)**. F 와 다른 별도 시리즈다(거래량이 F 의 약 23~25%). 기본 `markets` 는 아래 날짜 기준이 정해질 때까지 `[F]` 로 둔다 |
| CM 일봉의 날짜는 야간장이 열린 날이다(우리 `tick_trade` 야간 `trade_date` 는 '다음 거래일' 가정이라 다르다) | 추정(2026-11-16). 그날 밤 진행 중인 야간장의 값이 11/16 행으로 왔고 1분 사이에 거래량이 늘었다. **2026-11-17 아침(06:00 이후)에 11/16 CM 행을 다시 받아 거래량이 F 의 약 25% 수준으로 확정되는지 본다** |
| 장중에 일봉 API 를 부르면 오늘 행은 진행 중인 값이다 | **확인함(2026-11-16, CM 에서)**. 그래서 시작 직후 실행은 오늘 실행 시각 전에는 오늘을 뺀다. 남은 일: CM 은 오늘 행이 06:00 다음 날까지 진행 중이라 CM 을 켜면 `to` 를 전 거래일로 잡아야 한다 |
| KIS 일봉(F)은 주간장 기준이다(야간장 체결이 합쳐지지 않는다) | **미판정(2026-11-16)**. 같은 날 KIS 실전 체결이 DB 에 없어 판정 기준(종가 일치 + 거래량 차이 1% 이내)을 못 채웠다. 참고로 모의투자 체결과 비교하면 11/2 는 종가 일치·거래량 차이 0.57%, 11/3 은 종가 일치·1.69%(그날 14:57 에 수집 시작)이고, 10/29·10/30 은 체결이 15:34 에 끊겨 비교할 수 없다. 거래량 차이가 야간 합산이라고 하기엔 작아서 주간 기준 쪽으로 보이지만 근거가 약하다. **2026-11-17 에 collector 를 켜 둔 채 16:00 일봉과 그날 KIS 체결을 비교해 판정한다** |
| 응답의 현재가(`futs_prpr`)는 과거 일자에서 그날 종가다 | 부분 확인(2026-11-16). 11/2·11/3 은 일봉 종가가 그날 마지막 체결가(모의투자 체결)와 일치했다 |
| 거래가 없는 날은 행이 없거나 가격이 모두 0 이다(0 인 행은 버린다) | 미확인. 2026-10-27~11-16 구간에는 휴장일이 없어 영업일 15개에 행 15개가 왔다 |
| 주간장 마감 15분 뒤면 그날 일봉이 확정돼 있다 | 미확인 |
| 쿼리 이름을 명세 표의 대문자(`FID_COND_MRKT_DIV_CODE` ...)로 보내도 된다 | 확인함(2026-11-16, 요청 성공) |
| `mod_yn`(변경 여부)의 뜻 | 문서에 없음. 값만 보관한다. 2026-11-16 에 받은 F·CM 행은 모두 `N` 이었다 |
| 모의투자(`kis-paper`)도 일봉을 준다 | 문서상 지원. 미확인 |

## 첫 실호출 절차와 판정 기준 (2026-11-16 결정)

장이 끝난 거래일(주간장 마감 15분 뒤 이후, 15:45 마감이면 16:00 이후)에 한다. 실전 앱키로 읽기 전용 시세 조회 하나만 부른다.

1. `.env` 에 `KIS_APP_KEY`, `KIS_APP_SECRET`(실전)이 있는지 본다. `DAILY_CLOSE_RUN_ON_START=true` 를 넣고 `docker compose --profile app up -d --build`(또는 gradlew 로 `kis` 프로필 + `--collector.daily-close.run-on-start=true`). compose collector 하나만 띄운다(KIS 접속은 하나).
2. collector 로그에서 `daily bars F A01612 ...: n rows` 와 `daily close ...: requests=..., failures=...` 를 본다. 같은 시각에 `collector_daily_close_last_success_timestamp_seconds` 가 0 이 아닌지 본다.
3. 아래 '조회 쿼리' 의 3번(종가 비교)과 4번(거래량 비교)을 돌린다.
4. **확정 시점을 보려면 지금 `daily_bar` 를 복사해 둔다**(같은 키를 다시 받으면 덮어써서 이전 값이 남지 않는다):
   `CREATE TABLE daily_bar_snap_<오늘날짜> AS SELECT * FROM daily_bar;`. **같은 날 저녁(예: 23시 이후)에 시작 직후 실행으로 다시 받은 뒤** 스냅샷과 같은 키의 값이 달라졌는지 비교한다(로컬 `docs/db-queries.md` 8절 마지막 조회). 하루를 기다릴 필요는 없다: 16:00 에 받은 값과 몇 시간 뒤 받은 값이 같으면 16:00 에 이미 확정된 것이다. 비교가 끝나면 스냅샷 표를 지운다.
5. 되는 것이 확인되면 `DAILY_CLOSE_MARKETS=F,CM` 으로 한 번 더 돌려 CM 을 본다.
6. 결과를 아래 '첫 실호출 기록' 표에 날짜와 함께 적고, '가정과 확인 상태' 표를 고친다. 값(가격)은 옮기지 않는다.

| 가정 | 실패하면 |
|---|---|
| 종목코드 `A01612` | **멈추고 결과를 보고한다.** 자동으로 다른 코드를 시도하지 않는다. 실패 응답(`rt_cd`, `msg_cd`, 행 수)만 보고 형식을 정한 뒤 코드 변환을 한 곳에 더한다. 그동안 지표는 0 이라 일봉이 안 들어오는 것이 바로 보인다 |
| 주간 기준 | 같은 날 같은 출처에서 **종가가 일치하고 누적거래량 차이가 1% 이내**(`diff_pct`)이면 '주간 기준'으로 적는다. 며칠 치를 비교해 모두 만족해야 한다. 종가가 다르거나 일봉 거래량이 우리 주간 누적보다 1% 넘게 크면(야간 합산 의심) 멈추고 숫자를 보고한다. 어느 쪽이든 `daily_bar` 에는 KIS 값을 그대로 둔다 |
| 확정 시점 | **15분 유지.** 다음 거래일 실행이 최근 5영업일을 다시 받아 덮어쓰므로, 확정 전에 받았어도 값이 최대 하루 늦게 맞춰진다. 16:00 스케줄 직후 만든 스냅샷과 같은 날 저녁에 다시 받은 값을 비교해 달라졌는지만 기록한다(위 절차 4번). 달라졌다면 `after-close` 를 늘린다 |

## 첫 실호출 기록

**실제 응답 원문·가격 값은 옮기지 않는다**(절대 규칙 3). 행 수, 되고 안 되고, 날짜 범위, 비율 같은 사실만 적는다.

| 날짜 | 프로필 | 확인한 것 | 결과 |
|---|---|---|---|
| 2026-11-16 23:34 KST | `kis` | 접근토큰 `/oauth2/tokenP` | 발급 성공, 유효 24시간 |
| 2026-11-16 23:34 | `kis` | 일봉 F `A01612`, 최근 5영업일(11/10~11/16) | 요청 1건 실패 0, 5행. `mod_yn` 모두 N. 시가·고가·저가·종가 일관성 위반 0건. 주간장 마감 뒤라 11/16 행 포함 |
| 2026-11-16 23:36 | `kis` | 일봉 F, 15영업일(10/27~11/16) | 실패 0, 15행(영업일 수와 같음) |
| 2026-11-16 23:37 | `kis` | 일봉 CM `A01612`, 5영업일 | 실패 0, 5행. F 와 모든 날짜에서 값이 다름(별도 시리즈), 거래량은 F 의 23~25%(11/16 만 약 9%). 11/16 행은 진행 중인 야간장 값이고 1분 사이 거래량이 늘었다 |
| 2026-11-16 23:35~ | `kis` | 지표 `collector_daily_close_last_success_timestamp_seconds` | 실행 성공 시각으로 갱신됨. collector 를 재시작하면 0 으로 돌아간다(메모리 값) |
| 2026-11-16 23:36 | `kis` | 주간 기준 판정(F) | **미판정**. 같은 날 KIS 실전 체결이 없음(가정과 확인 상태 표 참고). 11/17 에 collector 를 켜 두고 16:00 일봉과 비교 |

남은 확인(모두 2026-11-17 안에 끝난다):

| 확인 | 시각 | 방법 |
|---|---|---|
| ④ 11/16 CM 행 확정 | 06:00 이후(야간장 종료) | 시작 직후 실행(`markets=F,CM`)으로 받아 거래량이 F 의 약 25% 수준으로 굳었는지, 한 번 더 받아도 같은지 |
| ① 16:00 스케줄이 도는지 | 16:00 | 로그 `daily close ...: requests=1, failures=0` 와 지표 갱신. 타이머 경로는 테스트(`DailyCloseSchedulerTest`)로 미리 확인했다 |
| ② 주간 기준 판정 | 16:05 이후 | 11/17 F 일봉과 그날 KIS 체결 비교(종가 일치, 거래량 차이 1% 이내). **낮 동안 collector 를 재시작하지 않는다**(재시작하면 체결 구멍이 생겨 비교가 흐려진다) |
| ③ 확정 시점 | 16:05 스냅샷, 23시 이후 재수신 | 위 절차 4번 |

②가 구멍 때문에 결론이 안 나면 그때만 하루를 더 쓴다(깨끗한 수집일 하나가 필요하다).

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

-- 일봉 누적거래량과 그날 tick_trade 의 마지막 누적거래량 비교(주간장만). diff_pct 는 일봉이 우리 누적보다 몇 % 큰가
-- 판정: 종가 일치 + |diff_pct| 1% 이내이면 주간 기준. 크게 양수이면 야간 합산을 의심한다(우리 수집 빈틈이면 양수가 조금 나올 수 있다)
SELECT b.trade_date, i.symbol, b.acc_volume AS bar_volume, t.last_acc AS tick_last_acc,
       round(100.0 * (b.acc_volume - t.last_acc) / nullif(b.acc_volume, 0), 2) AS diff_pct
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

- collector 가 16:00 에 꺼져 있었으면 그날은 건너뛴다. 다음 거래일 실행이 최근 N영업일을 다시 받아 메운다. 5영업일을 넘게 꺼 두었다면 `run-on-start=true` 로 한 번 띄우면 직전 거래일까지 다시 받는다(`lookback-days` 가 모자라면 같이 늘린다).
- 일부 요청만 실패해도 나머지는 저장된다. 지표 값이 멈추면 로그의 `daily close failed for ...` 줄을 본다(메시지에는 HTTP 코드나 `rt_cd`/`msg_cd` 만 있고 키·토큰은 없다).
- 401·403 을 받으면 접근토큰을 버리고 다음 실행에서 새로 받는다.
- 한 번에 100건을 넘게 요청하지 않는다. 이 구현은 연속조회(`tr_cont`)를 하지 않는다.
- DB 를 옮길 때(Day 8)는 일봉을 따로 옮기지 않아도 된다. 다음 실행이 최근 N영업일을 다시 받는다. 그보다 오래된 일봉은 옮기지 않으면 사라진다.
