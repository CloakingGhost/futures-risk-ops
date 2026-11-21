# 2026-11-19 (목) Day 8 PostgreSQL 차트 리허설

`deploy/helm/postgres`(PR 12)를 `rehearsal` 네임스페이스에서 시험했다. KIS 접속이 없고, 계정과 행은 모두 **합성값**이다(실제 키·시세 아님). 시험이 끝난 뒤 네임스페이스를 지웠다.

## 환경
- 클러스터: k3d `margin-watch`(k3s v1.32.5), 기본 StorageClass `local-path`(reclaim `Delete`).
- 이미지: `postgres:16`(노드에 미리 받아 둠).

## 결과

| 단계 | 기대 | 결과 |
|---|---|---|
| 설치 `helm install postgres deploy/helm/postgres -n rehearsal` | `postgres-0` Ready, PVC Bound | 설치 뒤 13초에 Ready, PVC `data-postgres-0` 10Gi Bound |
| 서비스 이름 `postgres` 로 접속(다른 파드에서 `psql -h postgres`) | 접속, 합성 행 삽입 | 성공(headless Service 가 Ready 파드의 IP 로 풀린다) |
| `kubectl delete pod postgres-0` | 새 파드가 뜨고 행이 남는다 | 삭제 1초, 새 파드 약 7초 뒤 Ready, **행이 남았다**(같은 PVC 를 다시 붙임) |
| 종료 방식 | 즉시 종료인지, 유예 60초를 다 쓰는지 | **즉시 종료**(삭제 1초). 새 파드 로그가 `database system was shut down at ...`(정상 종료 뒤 시작, 복구 과정 없음) |
| 네임스페이스 삭제 | PVC·PV·데이터도 함께 사라진다 | PV 사라짐, 노드의 `/var/lib/rancher/k3s/storage` 비어 있음(`local-path` reclaim `Delete` 확인) |
| `PGDATA` 하위 폴더(`.../data/pgdata`) | 권한 문제 없음 | 문제 없이 초기화됨 |

## 실패한 명령과 원인
- 첫 시도의 `psql -c "insert ... $$text$$"`: 바깥 셸이 `$$`(프로세스 번호)를 풀어 버려 SQL 구문 오류가 났다. 차트와 무관한 시험 명령의 따옴표 실수였다. 행이 들어가지 않은 채 파드를 지워서 "행이 없다"는 결과가 나왔는데, 이것은 데이터 유실이 아니었다. 행을 표준 입력으로 넣는 방식으로 고쳐 다시 시험했다. 교훈: 지속성 시험은 **삭제 전에 행이 들어갔는지부터** 확인한다.

## 이 시험에서 확인하지 못한 것
- collector·risk 의 DB 연결 풀이 postgres 파드가 다시 뜬 뒤(IP 가 바뀜) 연결을 회복하는지. 실제 설치 뒤, 장이 닫힌 시간에 `postgres-0` 을 한 번 지워 확인한다.
- `postgres:16` 이미지 문서에 종료 신호 설명이 없어, 즉시 종료는 이번 관찰 한 번의 결과다.
