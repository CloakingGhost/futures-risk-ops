# Day 8·9 이력 (2026-11-19)

Day 8(클러스터로 이사)과 Day 9(모니터링)를 같은 날 했다. 계획서는 저장소 밖 로컬 `margin-watch/docs/day8/`, `docs/day9/`. 오늘 일을 말로 풀어 쓴 운영 보고서는 `margin-watch/docs/reports/2026-11-19-운영보고서.md`. 키·웹훅 주소·실제 시세 값은 없다.

## 한 일
| PR | 내용 |
|---|---|
| #12 | `deploy/helm/postgres`(StatefulSet+PVC, headless Service `postgres`, replicas 1 고정), `check.sh` 확장, 리허설 일지 |
| #13 | `deploy/k3d/create-secrets.sh`(+테스트), `docs/deploy-k3d.md`(설치 runbook), 전환 일지 |
| #14 | risk 에 `/actuator/prometheus` |
| #15 | kube-prometheus-stack 92.18.0 값, ServiceMonitor, 알람 8개(`margin-watch-rules`), promtool 시험, Alertmanager→Slack, `create-slack-secret.sh` |
| #16 | 대시보드 4종 JSON 과 배포 차트(#15 위) |
| #17 | Loki 7.5.0·Alloy 1.15.0 값, Grafana 데이터소스(#15 위) |

## 전환 (14:24, 주간 장중)
옛 collector 정지 14:24:27, 클러스터 collector 구독 14:24:36(수집 공백 약 10~18초). Flyway V1~V7, 일봉 15행(요청 1, 실패 0), 체결 적재 시작(첫 52초 322건). 인수 검사: 파드 재시작 0, risk API 200, Prometheus target 2개 up, 울리는 알람 0, 대시보드 쿼리 42개 중 OK 40·EMPTY 2(정상)·ERROR 0. 옛 compose collector·risk 는 정지만, compose DB(과거 체결 약 84만 건)는 가동 중이며 사용자 결정으로 그대로 둔다.

## 알람 8개
①장중 2분 수집 중단(종가 단일가 주간 15:35~15:45·야간 05:50~06:00 KST 제외) ②적재 p95 1초 5분 ③10분 재접속 3회 ④끊긴 동안 누락 ⑤노드 디스크 80% ⑥DB 연결 80% ⑦파드 재시작 반복 ⑧일봉 정체(마지막 성공 100시간 초과). 토큰(접속키) 만료 알람은 만들지 않는다.

## 실패와 고친 것
- Grafana 가 메모리 한도 256Mi 로 OOMKilled(137) 되어 CrashLoop → 768Mi(평소 약 460Mi). `helm template`·검사로는 잡히지 않고 설치해 봐야 알았다.
- `helm install --wait` 실패 시 릴리스가 `failed` 로 남는다 → 같은 이름 `helm upgrade` 로 복구.
- 대시보드 데이터소스 uid 가 스택 기본값(`prometheus`)과 달라 고침.
- docker 이미지 ID(인덱스 해시)와 `crictl` ID(구성 해시)가 달라 마지막 레이어 해시로 비교.
- Git Bash 프로세스 치환 `<(...)` 과 `/etc/...` 경로 변환을 Windows `kubectl.exe` 가 처리하지 못함 → 임시 파일 + `cygpath -m`, `MSYS_NO_PATHCONV=1`.

## 사용자 결정(2026-11-19)
Day 8 성공 기준(야간 체결 적재, 재시작 0, 재현 가능한 코드·문서, 운영 일지), 되돌리지 않고 클러스터에서 고친다, 하루 이틀 수집 구멍은 감수, 옛 DB 는 그대로, 알람 8개(토큰 알람 제외·일봉 정체 포함), Loki·Alloy 는 마지막, 알람은 Slack 웹훅(`SLACK_ALERTS_WEBHOOKS`).

## KIS 카카오톡 알림
'접근 토큰이 발급되었습니다' 안내다(발급 때마다 옴, 1일 1회 원칙, 잦은 발급은 이용 제한 가능). 갱신 알림도 일봉 성공 알림도 아니다. 전환 뒤 발급 시각이 16:00 에서 collector 가 토큰을 받는 시각(11/19 14:24 → 11/20 14:24 전후)으로 옮겨 간다고 예상한다.

## 남은 일
[open-items.md](open-items.md) 의 '다음에 할 일'. 첫 야간 확인(11/20 06:10), `runOnStart=false` 업그레이드, Day 10(알람 점검·런북·MCP).
