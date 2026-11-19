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
- **17:00**: 수능일 보정 일봉 실행을 클러스터 collector 가 하는지(로그 `next daily close at 2026-11-19T08:00:00Z`).
- **18:59~19:00**: 수능일 야간 접속과 첫 야간 체결(`session=NIGHT`, 거래일 11/20).
- **11/20 06:10**: 06:00:00 체결이 NIGHT 로 분류됐는지, 접속이 06:00:10 쯤 끊겼는지, 재시작 0.
- **11/20 14:24 전후**: 접근 토큰이 만료되는 시각. 새 토큰 발급(KIS 카카오톡 안내가 이 시각으로 옮겨 간다)과 그 뒤 일봉·접속이 정상인지.
- 이 시각 이후 collector 를 재시작하면 새 토큰을 받는다(잦은 발급은 KIS 가 제한할 수 있다). 20:24 이후에는 불필요한 재시작을 피한다.
- compose DB 는 첫 야간(11/20 06:10) 확인 뒤 정지만 한다(볼륨 유지). 옛 collector·risk 컨테이너는 정지 상태로 남아 있다.
