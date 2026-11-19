# 2026-11-19 (목) Day 9 모니터링 스택 설치

`monitoring` 네임스페이스에 kube-prometheus-stack, 알람 규칙, 대시보드, Loki·Alloy 를 설치했다. 클러스터에 collector·risk 는 아직 없고(옛 collector 가 compose 에서 수집 중) 옛 collector 는 설치 중에도 영향이 없었다(healthy, 재시작 0). 키·웹훅 주소·실제 시세 값은 이 문서에 없다.

## 설치 순서와 결과

| 시각(KST) | 한 일 | 결과 |
|---|---|---|
| 13:28 | 설치 전 기준값 기록 | Docker VM 13.6GiB 중 컨테이너 전체 약 2GiB, 옛 collector healthy·재시작 0 |
| 13:29 | `monitoring` 네임스페이스, Secret `grafana-admin`(무작위 비밀번호, 화면 출력 없음), `helm install kube-prometheus-stack` 92.18.0 | **실패**: 10분 대기 뒤 `Deployment kube-prometheus-stack-grafana not ready`(타임아웃). 릴리스 상태 `failed` |
| 13:39 | 원인 확인 | Grafana 컨테이너가 **OOMKilled(종료 코드 137)** 로 CrashLoopBackOff(6회). 값 파일의 Grafana 메모리 limits 256Mi 가 너무 작았다. 다른 파드는 정상 |
| 13:41 | limits 256Mi → 768Mi(requests 256Mi), `helm upgrade` | 성공. Grafana 3/3 Ready. 평소 사용량 약 460Mi(처음 한도가 부족했음이 확인됨) |
| 13:45 | Secret `alertmanager-slack` 만들기, Alertmanager 에 Slack 수신처 연결, `helm upgrade` | 설정이 읽혔다(`api_url_file` 사용, 설정 본문에 웹훅 주소 없음), 마운트 확인 |
| 13:46 | 합성 테스트 알람 1건(알람 이름은 라우팅 규칙에 걸리게 `CollectorMissingVolume`, 문구에 '합성'을 명시, 2분 뒤 해소) | Alertmanager 기준 Slack 전달 1건, 실패 0. 채널 도착은 사용자가 확인 |
| 13:47 | `margin-watch-rules`(알람 8개), `margin-watch-dashboards`(4개) 설치 | Prometheus 에 규칙 8개 모두 로드(`health ok`), Grafana 에 대시보드 4개 |
| 13:48 | 대시보드 데이터소스 uid 확인 | 스택의 Prometheus 데이터소스 uid 는 소문자 `prometheus`. JSON 은 `Prometheus` 였다 → 고치고 대시보드 차트 upgrade |
| 13:50 | Loki 7.5.0, Alloy 1.15.0, Loki 데이터소스 ConfigMap | Loki 37Mi, Alloy 45Mi. Grafana 에 데이터소스 `Loki` |
| 13:52 | 로그 검색 확인 | 합성 오류 로그 한 줄(존재하지 않는 DB 계정으로 접속 시도) 을 만들어 `{namespace="margin-watch", app="postgres"}`, `|= "FATAL"` 검색이 1줄을 돌려줬다 |

## 실패와 원인 정리
- **Grafana OOMKilled**: 값 파일은 `helm template` 과 `check.sh` 를 통과했지만 메모리 한도가 실제 시작에 모자란 것은 설치해 봐야 알 수 있었다. 카드의 '관찰 후 조정'에 해당. 값 파일 주석에 이유를 적고 768Mi 로 올렸다.
- **`helm install --wait` 가 실패해도 릴리스가 남는다**: 상태가 `failed` 인 채로 있어서 `helm upgrade`(같은 이름)로 복구했다.
- **검사 스크립트가 옛 uid 를 기대**: 대시보드 uid 를 고칠 때 `check-dashboards.sh` 가 실패하는데 파이프(`| tail`) 때문에 종료 코드를 놓치고 커밋이 먼저 됐다. 곧바로 스크립트를 고치고 통과 상태로 다시 커밋했다. 교훈: 검사 결과를 파이프에 물리면 종료 코드가 사라진다. 종료 코드를 먼저 확인한다.

## 확인하지 못한 것 / 다음에 볼 것
- collector·risk 가 클러스터에 없어서 대시보드의 collector·risk 패널은 비어 있다. 전환 뒤 `deploy/monitoring/verify-queries.sh` 로 확인한다.
- `NodeDiskUsageHigh`(노드 디스크)는 k3d 노드 안에서 node-exporter 가 실제로 보는 마운트를 확인하지 못했다(`overlay` 제외 필터 때문에 아무것도 못 볼 수 있음).
- Slack 채널에 메시지가 실제로 도착했는지는 사용자가 확인한다(Alertmanager 의 전달 성공 기록은 1건).
- Loki 보존 72시간이 실제로 지워지는지는 3일 뒤에 확인한다.
