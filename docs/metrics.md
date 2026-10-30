# collector 지표 (고정 목록)

collector 가 내보내는 지표 9종의 이름과 뜻이다. Day 9 알람, Day 10 Prometheus MCP 허용 템플릿, 대시보드가 이 이름을 그대로 쓰므로 **2026-10-29 에 이름을 고정한다**.
바꿔야 하면 이 문서, `CollectorMetrics`, `CollectorMetricsTest.NAMES`, 알람·대시보드를 한 커밋에서 같이 고치고 아래 변경 기록에 남긴다.

## 어디서 보나

```
http://127.0.0.1:8081/actuator/prometheus   # 지표 (Prometheus 텍스트 형식)
http://127.0.0.1:8081/actuator/health       # 상태 (UP/DOWN, DB 연결 포함)
```

- 이 PC 안(127.0.0.1)에서만 열린다. 다른 기기에서는 접속되지 않는다. 다른 actuator 엔드포인트(env, beans, heapdump 등)는 열지 않는다.
- 포트 8081 은 risk(8080)와 겹치지 않게 골랐다. 바꾸려면 `--server.port=...`.
- Docker 안의 Prometheus 가 읽어야 할 때의 주소는 Day 9 에서 정한다. 그 전까지는 주소를 넓히지 않는다.
- 스프링이 자동으로 붙이는 `jvm_*`, `process_*`, `hikaricp_*`, `tomcat_*`, `http_server_requests_*` 도 함께 나온다. 이것들은 고정 대상이 아니다.

## 지표 9종

카운터는 새로 세지 않고 기존 카운터를 읽는다. 그래서 로그 `ingest stats` 줄의 숫자와 같다(아래 '로그 항목').

| # | Prometheus 이름 | 종류 | 라벨 | 뜻 | 로그 항목 |
|---|---|---|---|---|---|
| 1 | `collector_messages_received_total` | counter | `kind`=`trade`\|`quote`, `tr_id`=`H0IFCNT0`\|`H0MFCNT0`\|`H0IFASP0` | 받아서 파싱한 체결·호가 건수(프레임이 아니라 레코드 한 건씩). 형식이 틀려 버린 프레임은 세지 않는다 | `trade` 합 = `trades`, `quote` = `quotes` |
| 2 | `collector_trade_to_receive_seconds` | histogram | | 체결 시각(bsop_hour)에서 수신까지. 체결마다 한 번 | |
| 3 | `collector_receive_to_commit_seconds` | histogram | | 수신에서 tick_trade 배치 INSERT 가 끝날 때까지. 체결만 잰다(호가 제외). 중복으로 버려진 체결도 그 배치에 들었으면 센다 | `_count` = `written` + `duplicates` |
| 4 | `collector_ws_state` | gauge | `state`=`IDLE`\|`CONNECTING`\|`SUBSCRIBED`\|`BACKOFF` | 지금 상태만 1, 나머지 0 | `state` |
| 5 | `collector_ws_reconnects_total` | counter | | 끊긴 뒤 다시 구독(SUBSCRIBED)까지 된 횟수 | `recoveries` |
| 6 | `collector_missing_volume_total` | counter | `kind`=`reconnect`\|`stream` | 누적거래량 빈틈으로 계산한 누락 계약 수. `reconnect` 는 끊긴 동안(확실한 누락), `stream` 은 연결 중(KIS 묶음 전송일 수 있음) | `reconnect-missing`, `stream-missing` |
| 7 | `collector_market_session_open` | gauge | | 세션 캘린더(`config/session-calendar.yaml`)로 지금 주간장·야간장 안이면 1. 세션 게이트 설정(`collector.session.gating`)과 상관없다 | |
| 8 | `collector_token_expiry_timestamp_seconds` | gauge | | 웹소켓 접속키 만료 시각(유닉스 초). 받은 시각 + `collector.approval.key-ttl`(기본 24h, 가정). 아직 받지 않았거나 시뮬레이터면 0 | |
| 9 | `collector_last_trade_received_timestamp_seconds` | gauge | | 마지막 체결 수신 시각(유닉스 초). 아직 없으면 0 | |

- 1·4·6 은 라벨 조합을 처음부터 0 으로 내보낸다. 알람식이 '값 없음' 을 따로 다루지 않아도 된다.
- 2·3 의 `_count`, `_sum`, `_max`, `_bucket{le=...}` 가 함께 나온다.

### 히스토그램 칸

| 지표 | 칸 경계(초) |
|---|---|
| `collector_trade_to_receive_seconds` | 0.5, 1, 1.5, 2, 3, 5, 10, 30, 60 |
| `collector_receive_to_commit_seconds` | 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10 |

## 가정과 한계

- **체결→수신은 0~1초 부풀려진다.** bsop_hour 가 초 단위(HHMMSS)라 체결 시각을 그 초의 시작으로 본다. 그래서 1초 아래 칸을 잘게 나누지 않았다.
- **PC 시계에 따라 달라진다.** 수신 시각은 PC 시계, 체결 시각은 KIS 시계다. 계산이 음수면(PC 시계가 늦으면) 0 으로 센다. `_sum` 이 계속 0 에 가까우면 지연이 없는 것이 아니라 PC 시계가 늦을 수 있다. Windows 시간 동기화를 먼저 확인한다.
- **수신→커밋에는 배치 대기가 들어 있다.** 기본 설정(`collector.batch.flush-interval-ms=200`, `max-size=500`)이면 체결이 뜸할 때 최대 약 0.2초를 모았다가 쓴다.
- **접속키 만료 24시간은 가정이다.** 공식 문서로 확인하지 못했다. 접속키는 접속할 때만 쓰므로, collector 는 접속할 때 만료 1시간 전부터 새 키를 받는다. KIS 가 구독을 접속키 문제로 거절하면 키를 버리고 다시 붙는다(2026-10-30 Day 4 추가). 만료 시각 지표는 새 키를 받을 때마다 바뀐다.
- **재접속 횟수는 성공한 재접속만 센다.** 계속 실패하는 동안에는 늘지 않으니 `collector_ws_state{state="BACKOFF"}` 를 같이 본다.
- **세션 지표 이름**: 30일 계획 Day 9 본문은 `collector_session_active` 라고 적었지만, Day 3(계획 V2)에서 정한 `collector_market_session_open` 을 쓴다. 계획 문서는 Day 4 PR 뒤 점검 때 원문을 남긴 채 고친다.

## 알람식 예시 (Day 9 에서 확정)

아직 알람을 만들지 않았다. 이 지표로 Day 9 수집 쪽 알람을 계산할 수 있는지만 확인한 예시다.

```promql
# 장중 2분 넘게 체결 없음
collector_market_session_open == 1
  and time() - collector_last_trade_received_timestamp_seconds > 120

# 적재 p95 (5분)
histogram_quantile(0.95, sum by (le) (rate(collector_receive_to_commit_seconds_bucket[5m])))

# 10분 동안 재접속 3회 이상
increase(collector_ws_reconnects_total[10m]) >= 3

# 끊긴 동안 누락 발생
increase(collector_missing_volume_total{kind="reconnect"}[10m]) > 0

# 접속키 만료 1시간 전 (0 은 '없음' 이라 뺀다)
collector_token_expiry_timestamp_seconds > 0
  and collector_token_expiry_timestamp_seconds - time() < 3600
```

## 출력 예시 (합성: 시뮬레이터 값)

시뮬레이터(`--rate.ticks-per-second=20 --clock.start=2026-11-02T10:00:00`)에 붙인 collector 에서 받은 출력 일부다. 실제 시세가 아니다.

```
collector_messages_received_total{kind="quote",tr_id="H0IFASP0"} 199.0
collector_messages_received_total{kind="trade",tr_id="H0IFCNT0"} 199.0
collector_messages_received_total{kind="trade",tr_id="H0MFCNT0"} 0.0
collector_receive_to_commit_seconds_bucket{le="0.1"} 79
collector_receive_to_commit_seconds_bucket{le="0.25"} 198
collector_receive_to_commit_seconds_count 198
collector_ws_state{state="SUBSCRIBED"} 1.0
collector_market_session_open 1.0
collector_token_expiry_timestamp_seconds 0.0
```

## 변경 기록

- 2026-10-29: 처음 고정(Day 3 PR 6). 9종, 라벨, 히스토그램 칸.
