# 2026-11-19 (목) Day 8 클러스터 전환

compose 의 옛 collector·risk 를 끄고 k3d 클러스터(네임스페이스 `margin-watch`)의 collector·risk 로 옮겼다. 사용자 결정: 되돌리지 않고 클러스터에서 고친다, 하루 이틀의 수집 구멍은 감수한다. 키·웹훅 값과 실제 시세 값(가격·거래량)은 이 문서에 없다. 행 수와 시각만 적는다.

## 전환 전 검증(게이트) — 모두 통과 (14:20 무렵)
| # | 확인 | 결과 |
|---|---|---|
| 코드 | 6개 브랜치를 로컬 통합 브랜치 `deploy/day8-day9` 로 합침(충돌 0, 푸시 안 함) | `./gradlew test` 322개, skipped 0, failures 0, errors 0 |
| 검사 | `deploy/helm/check.sh`, `deploy/monitoring/check.sh`, `check-dashboards.sh`, `check-logging.sh` | 모두 종료 코드 0 |
| G1 | 통합 코드로 이미지 빌드 → 노드에 import | 노드 이미지의 마지막 레이어가 빌드와 일치(collector, risk) |
| G2 | Secret `kis-credentials`·`postgres-credentials` 의 키 이름 | 기대와 일치(값은 출력하지 않음) |
| G3 | postgres 파드 | Ready, 재시작 0, 로그인 가능, 공개 테이블 0개(빈 DB) |
| G4 | `helm install --dry-run=server` (collector, risk) | 둘 다 통과, 아무것도 만들지 않음 |
| G5 | 렌더링한 collector Deployment | `replicas: 1`, `Recreate` 유지 |
| G6 | 모니터링 스택 | 파드 8개 전부 Running |

## 전환 (모두 로컬 시계, KST)
| 시각 | 사건 |
|---|---|
| 14:24:18 | 옛 collector 의 마지막 `ingest stats`(정지 직전) |
| 14:24:27 | `docker compose stop collector risk` 완료. `docker ps` 에 collector·risk 없음(db 만 남김) |
| 14:24:29 | `helm install collector`(`runOnStart=true`, `lookbackDays=15`, ServiceMonitor 켬) |
| 14:24:35 | Flyway V1~V7 적용(`flyway_schema_history` 7건 성공), 접근 토큰 발급(로그: 유효 24시간, 만료 11/20 14:24:36), 일봉 F 15행(요청 1, 실패 0) |
| 14:24:36 | KIS 접속, `H0IFCNT0`·`H0IFASP0` 구독 성공 → 상태 `SUBSCRIBED` |
| 14:24:40 | collector Ready |
| 14:25 무렵 | `helm install risk`(ServiceMonitor 켬), Ready |

**수집 공백**: 옛 collector 정지(14:24:27)에서 새 구독(14:24:36)까지 약 9초, 마지막 체결 기록 시각(14:24:18)부터 따지면 약 18초. 주간 장중에 전환했다.

## 인수 검사 (전환 약 2분 뒤)
| # | 확인 | 결과 |
|---|---|---|
| A1 | 파드 | collector·risk·postgres 모두 Ready, 재시작 0 |
| A2 | 클러스터 DB 의 체결 | `source=KIS`, `session=DAY`, 거래일 2026-11-19 행이 쌓임(첫 52초에 322건), `daily_bar` 15행, Flyway 7 |
| A3 | risk | `/actuator/health` 200, `/actuator/health/readiness` 200, `/actuator/prometheus` 200, `/api/risk/basis` 200, `/actuator/env` 404 |
| A4 | Prometheus target | collector, risk 모두 `up` |
| A5 | 알람 | 전환 중 잠깐 `KubePodNotReady`·`KubeDeploymentReplicasMismatch` 가 pending 이었다가 해소. 약 1분 뒤 울리는 알람 0 |
| A6 | 대시보드 패널 쿼리 42개(`verify-queries.sh`) | OK 40, EMPTY 2(risk 5xx 비율 — 오류 없음, 울린 알람 이력 — 알람 없음, 둘 다 정상), ERROR 0 |
| A7 | 메모리 | collector 약 195Mi, risk 약 190Mi, postgres 약 86Mi(한도 512Mi/512Mi/1Gi 안) |

## 아직 확인하지 못한 것 / 다음에 할 일
- **16:52 완료**: 주간 마감 뒤(`collector_market_session_open`=0, 상태 `IDLE`, `daily_bar` 15행) `helm upgrade collector --reuse-values --set dailyClose.runOnStart=false --set dailyClose.lookbackDays=5`. 리비전 2, Recreate 로 새 파드(16:52:37~16:52:50, 약 13초), 새 파드 재시작 0. 로그에 `next daily close at 2026-11-19T08:00:00Z`(17:00 KST)와 `session gate closed: staying disconnected until the next session NIGHT 2026-11-19T19:00+09:00` 가 찍혔다(수능일 야간 개장 19:00 설정과 일치). ServiceMonitor 는 켜진 채 유지됐다. 새 토큰 발급 로그는 없다(재시작이 토큰 발급 6시간 안이라 같은 토큰을 쓴다고 본다).
- **17:00 완료**: 수능일 보정 일봉을 클러스터 collector 가 실행했다(17:00:00, 요청 1, 실패 0, 5행 = 11/13~11/19). `daily_bar` 는 16행(10/29~11/19)이 됐고(15행 + 오늘 날짜 1행, 나머지는 같은 키로 덮어씀), 지표 `collector_daily_close_last_success_timestamp_seconds` 가 갱신됐다(17:05 기준 327초 전). 로그의 접근 토큰 만료 시각이 14:24 발급분과 같은 11/20 14:24:36 이라 **재시작 뒤에도 같은 토큰이 쓰였다**(새 발급 아님으로 본다). 다음 일봉 예약은 11/20 16:00 KST. 울리는 알람 0, 파드 재시작 0.
- **18:59~19:03 완료(수능일 야간 개장 확인)**: 18:59:00 `session gate open` → KIS 접속 → `H0MFCNT0 A01612: SUBSCRIBE SUCCESS`(18:59:00.7), 상태 `SUBSCRIBED`. 수능일 야간 19:00 개장 설정(접속 18:59)이 맞았다. 클러스터 DB 에 `KIS | NIGHT | 거래일 2026-11-20` 행이 첫 체결 **18:59:59**부터 쌓였다(19:03:06 까지 550건). **운영 시작 시각(첫 야간 체결 적재) = 2026-11-19 18:59:59 KST.** 같은 시각 지표: 수신 `H0MFCNT0` 만 약 1.2건/초(주간 TR 은 0), 적재 지연 p95(수신→커밋) 약 0.24초, 재접속 누락 거래량 0, 울리는 알람 0, 파드 재시작 0, 메모리 collector 227Mi·risk 220Mi·postgres 95Mi. 주간 체결은 `KIS | DAY | 2026-11-19` 22,200건(14:24:36~16:44:59). 값(가격·거래량)은 적지 않는다.
- **11/20 06:12 첫 야간 마감 확인 완료(모두 정상)**
  - **분류**: 클러스터 DB `KIS | NIGHT | 거래일 2026-11-20` 33,798건(수신 11/19 18:59:59~11/20 05:59:59), 주간 `DAY | 2026-11-19` 22,200건. 06:00:00 마감 체결 1건이 `NIGHT`·11/20 으로 분류됐고, 06:00~08:44 에 `DAY` 로 분류된 체결은 0건(11/18 수정 확인).
  - **마감 동작**: 06:00:00.2 구독이 야간 TR(`H0MFCNT0`)에서 주간 TR(`H0IFCNT0`·`H0IFASP0`)로 바뀌고, 06:00:10.2 `session gate closed`(다음 세션 DAY 11/20 08:45)로 접속이 끊겼다(06:00:10 기대와 일치).
  - **밤새**: 파드 재시작 0·OOMKilled 없음(`lastState` 없음), 재접속 0, 누락 거래량(`reconnect`·`stream`) 0, 수신→커밋 p95 약 0.24초, collector 작업 메모리 최대 약 243MiB(한도 512Mi), 지금 collector 240Mi·risk 225Mi·postgres 102Mi. 로그의 WARN 은 수신 공백 경고 2건(23:24 60초, 05:50 61초=야간 종가 단일가 구간)뿐이고 둘 다 저절로 이어졌다.
  - **알람 오탐 1건(수집 문제 아님)**: `CollectorNoTradeInSession` 이 **11/19 16:37:34 pending → 16:39:34~16:45:49 firing**, Slack 으로 실제 알림 1건과 해소 1건이 나갔다(Alertmanager Slack 전송 카운터 4 = 11/19 합성 테스트 2 + 이 알람 2). 원인: 수능일 주간 마감이 16:45 로 밀려(`config/session-calendar.yaml` 특수 개장) 종가 단일가가 **16:35~16:45** 였는데(DB 분당 체결: 16:34 에 285건 → 16:35~16:44 0건 → 16:45 1건), 규칙은 평소 **15:35~15:45** 만 제외한다(`margin-watch.rules.yaml`, UTC 변환한 395~405분). 특수 마감일에는 같은 오탐이 난다. 고치는 방법은 Day 10 계획에서 정한다(후보: 캘린더에서 종가 단일가 시각을 지표로 내보내기, 특수일 구간을 규칙에 더하기, `for` 늘리기).
  - **pg_dump 백업·복원(실데이터)**: `pg_dump -Fc` 1초, 1,677,562바이트(약 1.6MiB), 저장소 밖 `margin-watch/local-data/backup-2026-11-20-first-night.dump`. 별도 DB 에 `pg_restore` 2초, 5개 테이블 행 수가 원본과 모두 일치(`tick_trade` 55,998, `tick_quote` 30,704, `daily_bar` 16, `instrument` 7, `flyway_schema_history` 7). 임시 DB 는 지웠다. 값은 적지 않는다.
  - **옛 compose DB**: 사용자 결정대로 건드리지 않았다(가동 중, 과거 체결 약 84만 건).
- **11/20 14:24 전후**: 접근 토큰이 만료되는 시각. 새 토큰 발급(KIS 카카오톡 안내가 이 시각으로 옮겨 간다)과 그 뒤 일봉·접속이 정상인지.
- 이 시각 이후 collector 를 재시작하면 새 토큰을 받는다(잦은 발급은 KIS 가 제한할 수 있다). 20:24 이후에는 불필요한 재시작을 피한다.
- compose DB 는 첫 야간(11/20 06:10) 확인 뒤 정지만 한다(볼륨 유지). 옛 collector·risk 컨테이너는 정지 상태로 남아 있다.
