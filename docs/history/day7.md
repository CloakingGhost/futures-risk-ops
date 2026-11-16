# Day 7 이력 (2026-11-16 ~ 11-17)

Day 7 은 원래 2026-11-02 계획이었다. 개발일이 밀려 11/16 에 시작했다. 계획서는 저장소 밖 로컬 `margin-watch/docs/day7/day7-implementation-plan.md`.

## 계획 (사용자 결정, 2026-11-16)
- 웹소켓 수집기는 그대로 둔다. 일봉은 직접 계산하지 않고 KIS REST 선물옵션기간별시세(`FHKIF03020100`)로 받는다. Spring Batch·메타 테이블·집계 SQL 은 만들지 않는다. 기준: KIS 가 주는 값은 KIS 에서 받고 다시 계산하지 않는다.
- 수집은 실전 앱키(`kis`), 개발·테스트는 모의(`kis-paper`)와 시뮬레이터. 계좌번호는 어디서도 읽지 않는다. 미결제 API 는 바꾸지 않는다. GHCR 푸시는 Day 11 CI 로 미룬다.
- 계획 문서(저장소 밖): `day-07`, `00_plan-overview` 에 `[계획 V2]` 로 기록했다(원문 보존). 백업 `margin-watch\implement-test\plan-backup-2026-11-16`.

## 구현
PR 10 = `feat/day7-daily-close`(일봉 수집), PR 11 = `feat/day7-k3d-helm`(PR 10 위에 쌓음). 푸시와 PR 생성은 확인이 끝난 뒤에 한다.

| 구분 | 커밋 | 내용 |
|---|---|---|
| PR 10 | 4ed6a7e | REST 접근토큰 클라이언트(`/oauth2/tokenP`) |
| PR 10 | 1852800 | 일봉 클라이언트(`FHKIF03020100`, F·CM) |
| PR 10 | 96579fd | Flyway V7 `daily_bar` |
| PR 10 | 429114b | 거래일 주간장 마감 뒤 일봉 upsert, 설정 `collector.daily-close.*` |
| PR 10 | 7594918 | 지표 `collector_daily_close_last_success_timestamp_seconds`(10종) |
| PR 10 | ae6eac1 | compose 기본 프로필을 `kis` 로, `docs/kis-daily-bar.md` |
| PR 10 | d4e9475 | 시작 직후 실행 옵션, 장중에는 오늘 제외, `markets` 기본 F |
| PR 10 | 9d168d8, 4e5c9b0 | 확정 시점은 스냅샷과 비교, 첫 실호출 기록, compose 에서 lookback 변경 |
| PR 10 | b4beba5 | 정해진 시각 타이머 경로를 시계를 옮겨 테스트로 확인 |
| PR 10 | c8b15bb | 접근토큰 만료를 `access_token_token_expired` 로 계산(실제 버그, 아래) |
| PR 10 | ee3cbff | CM 은 v0.1 에서 쓰지 않기로 하고 오늘 확인 일정 정리 |
| PR 11 | 8e246a1 | `deploy/k3d` 클러스터 설정·`up.sh`·`down.sh` |
| PR 11 | b7ad96a | ADR 0001 로컬 클러스터, k3d·helm 설치 기록 |
| PR 11 | f014785 | `/actuator/health/liveness·readiness` 를 쿠버네티스 밖에서도 열기 |
| PR 11 | 3edd082, 733f155 | collector(replicas 1 고정·Recreate)·risk Helm 차트 |
| PR 11 | e350b35, 51e6bc2 | `deploy/helm/check.sh`, CLAUDE.md·dev-setup 반영 |

테스트는 298개 통과, skipped 0(2026-11-17 기준).

## 이번에 확인한 KIS 사실 (첫 실호출 2026-11-16 23:34 KST)
자세한 표는 [kis-daily-bar.md](../kis-daily-bar.md), 요약은 [kis-findings.md](kis-findings.md).
- 일봉 TR 에도 종목코드 `A01612` 가 통한다(명세 예시는 옛 형식 `101S06`).
- 접근토큰: 유효 24시간, 6시간 안에 다시 요청하면 직전 토큰을 돌려준다(공식 문서). 직전 토큰이 돌아와도 `expires_in` 은 86400 그대로이고 실제 만료는 `access_token_token_expired`(한국 시각)다.
- 접속키(웹소켓): 유효 24시간이지만 연결할 때 한 번만 쓰므로 연결이 이어지는 동안 만료는 상관없다(공식 문서).
- 시장 코드 `CM` 은 KIS 명세의 'FID 조건 시장 분류 코드' 값으로 `CM: 야간선물` 이다. 약자 풀이는 문서에 없다. 응답은 F 와 다른 별도 시리즈(거래량 F 의 약 23~25%)이고, 날짜는 야간장이 열린 날로 보이며, 진행 중인 야간장 값이 실시간으로 온다.
- 실전 야간 체결(H0MFCNT0)이 `A01612` 로 실제 수신된다(2026-11-16 23:35 부터).
- `mod_yn` 의 뜻은 KIS 전체 문서(332 시트)에도 설명이 없다. 값만 보관한다.

## 사용자 결정 (인터뷰)
| 날짜 | 결정 |
|---|---|
| 2026-11-16 | 최근 5영업일, 종목코드가 안 되면 멈추고 보고, 기본 markets=F, 주간 기준 판정은 종가 일치 + 거래량 차이 1% 이내, 확정 시점은 15분 유지, 장중 일봉은 이번 범위 밖, 도구는 제가 설치하고 과정을 기록, PR 11 은 PR 10 위에 쌓기, 캘린더는 `--set-file` |
| 2026-11-17 | CM 은 v0.1 에서 쓰지 않는다. 오늘 확인은 저녁 한 번(이미지 재빌드 포함)으로 합친다. 증거금률 11월 값은 10월과 같다(사용자 확인). Day 8 은 compose DB 를 이관하지 않고 빈 DB 로 시작한다. collector 메모리는 계획대로 512Mi 로 가서 Day 8 에 관찰한다. 10/28 체결 12,665건은 실전이 맞다. 야간 거래일 규칙은 현상 유지하고 사용자가 실제 앱 화면과 해석을 보내 준다. PC 는 노트북이고 전원을 항상 연결하며 절전·덮개 동작을 끄고 쓴다 |

## 실수와 고친 것
- **시각 오인**: Git Bash 의 `date` 가 UTC 를 보여 줘서 23:18 KST 를 '14:18 KST, 주간장 진행 중'이라고 말했다. KST 는 PowerShell 로 확인한다([environment.md](environment.md)).
- **불필요한 하루**: 확정 시점 비교를 '다음 거래일'로 적었지만 16:00 값과 같은 날 저녁 값을 비교해도 같은 답이 나온다. 같은 날로 고쳤다.
- **비교 불가 절차**: 같은 키를 다시 받으면 덮어써서 이전 값이 남지 않는다. 스냅샷을 먼저 만들어 두는 절차를 더했다.
- **probe 404**: 스프링은 쿠버네티스 안에서만 liveness·readiness 를 자동으로 켠다. compose 에서 불러 보니 404 였다. 명시 설정으로 고쳤다.
- **토큰 만료 계산 버그**: `expires_in` 에 현재 시각을 더하면 재시작할 때마다 만료를 최대 6시간 늦게 잡아, 그 사이 만료된 토큰으로 일봉을 요청하다 실패한다. 공식 문서와 실호출로 발견해 `access_token_token_expired` 를 쓰도록 고쳤다(c8b15bb). **고치기 전 이미지로 떠 있는 collector 는 2026-11-17 23:35 에 토큰이 만료된다.** 11/17 밤에 이미지를 다시 빌드해 재시작한다.
- **k3s 버전**: k3d 기본 k3s(v1.35)와 kubectl(v1.32)의 마이너 차이가 3 단계였다. `v1.32.5-k3s1` 로 고정했다.

## 확인 대기 (2026-11-17 안에 끝난다)
| 확인 | 시각 |
|---|---|
| ① 16:00 스케줄이 실제로 도는지(타이머 경로는 테스트로 확인함) | 16:00 |
| ② 주간 기준 판정(F 일봉과 그날 KIS 체결 비교) | 16:05 이후, 낮 동안 collector 를 재시작하지 않는다 |
| ③ 확정 시점(스냅샷 vs 저녁 재수신) | 16:05 스냅샷, 23시 이후 재수신(이미지 재빌드·재시작 한 번) |

②가 수집 구멍 때문에 결론이 안 나면 그때만 하루가 더 필요하다.

## Day 8 사전 점검 (2026-11-17)
Day 8 카드: 클러스터에 PostgreSQL(StatefulSet+PVC), Secret 스크립트, collector·risk Helm 배포, 17:30 까지 배포하고 18:00 야간장부터 클러스터 수집, 운영 시작 시각 기록. 오늘 만든 것과 맞물리는 점:

**선행 조건(데이터·설정 출처)**
- 이미지: 오늘 코드(probe 설정, 토큰 수정)가 들어간 이미지가 필요하다. `docker compose --profile app build` 후 `./deploy/k3d/up.sh --import`. 지금 k3d 노드 안의 이미지는 11/16 23시 빌드라 둘 다 빠져 있다.
- Secret 이름·키(차트가 기대하는 값): `kis-credentials`(`KIS_APP_KEY`, `KIS_APP_SECRET`, 모의투자면 `KIS_PAPER_APP_KEY`, `KIS_PAPER_APP_SECRET`), `postgres-credentials`(`POSTGRES_USER`, `POSTGRES_PASSWORD`). `.env` 에서 `--from-env-file` 로 만들어 값을 화면·저장소에 남기지 않는다.
- DB: 차트의 기본 접속 주소가 `jdbc:postgresql://postgres:5432/futures_risk_ops` 라서 PostgreSQL Service 이름은 `postgres`, DB 이름은 `futures_risk_ops`, 이미지는 compose 와 같은 `postgres:16`. 새 차트 `deploy/helm/postgres` 가 필요하다(PR 12).
- 캘린더: 설치할 때 `--set-file sessionCalendar=config/session-calendar.yaml`.
- 일봉: 빈 DB 로 시작하므로 첫 기동에 `dailyClose.runOnStart=true`, `lookbackDays` 를 넉넉히(예: 15) 줘서 한 번 채운다.

**카드와 달라지는 점**
- 카드의 '앱이 시작할 때 Flyway 마이그레이션이 돌게 한다'는 collector 가 이미 한다(시작할 때 Flyway 적용). DB 차트는 빈 DB 와 PVC 만 만들면 된다.
- DB 이관: 하지 않는다(사용자 결정). compose DB 볼륨(`futures-risk-ops_pgdata`)은 지우지 않고 남겨 둔다. 실시세 약 28만 행(KIS_PAPER)과 실전 약 1.7만 행이 거기에 남는다.
- collector 메모리: 512Mi 한도는 계획대로. compose collector 는 한도 없이 약 395MiB 를 쓴다. 첫 배포에서 OOMKilled 재시작이 생길 수 있어 `kubectl top pod`, `kubectl get pods` 로 보고, 생기면 운영 일지에 원인을 적고 한도를 올린다.

**순서와 주의**
1. 클러스터 collector 를 띄우기 전에 compose collector·risk 를 먼저 끈다(`docker compose --profile app stop collector risk`). KIS 접속은 하나다.
2. 거래일 17:30 까지 배포를 끝내고 18:00 야간장부터 수집을 시작한다. 야간 체결 수신은 오늘 이미 확인했다.
3. 파드 재시작 폭풍이 나면 접근토큰이 반복 요청된다. 6시간 안에는 같은 토큰이 오지만 6시간이 지난 뒤의 재시작마다 새 토큰을 받는다(KIS 는 1일 1회 발급을 원칙으로 한다). 재시작 횟수를 지켜본다.
4. 노트북 전원은 항상 연결한다. 전원이 빠지면(배터리 10분 절전) 수집이 끊긴다.
5. 클러스터를 지우면(`down.sh`) PVC 데이터도 사라진다.

## 남은 일
[open-items.md](open-items.md) 의 'Day 7 이후' 절.
