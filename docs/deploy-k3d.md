# 클러스터에 앱 올리기 (runbook)

k3d 클러스터(`k3d-margin-watch`)에 postgres, collector, risk 를 올리는 순서와 단계마다 확인할 것. Git Bash 에서 저장소 루트 기준으로 실행한다.
클러스터를 만드는 것까지는 [dev-setup.md](dev-setup.md) 의 '로컬 쿠버네티스', 왜 k3d 인지는 [adr/0001-local-cluster.md](adr/0001-local-cluster.md).

이 문서의 값은 모두 합성 예시이고 키·비밀번호·실제 시세는 없다. 명령은 아래 표기를 쓴다.

- 모든 `kubectl` 은 `--context k3d-margin-watch -n margin-watch`, 모든 `helm` 은 `--kube-context k3d-margin-watch -n margin-watch` 를 붙인다. 컨텍스트를 빼면 지금 선택된 클러스터에 실행되므로 빼지 않는다.
- 각 명령 옆의 **확인함(날짜)** 은 그날 이 PC 에서 실제로 돌려 본 것, **미확인** 은 계획과 문서에서 옮겨 적었고 아직 돌려 보지 않은 것이다. 미확인 명령은 처음 쓸 때 결과를 보고 이 문서를 고친다.

## 먼저 알아 둘 것

- **KIS 접속은 하나다.** compose 의 옛 collector 가 떠 있는 동안 `helm install collector` 를 하지 않는다(접속 충돌, 중복 적재). 4단계에서 먼저 끄고 `docker ps` 로 확인한 뒤에만 5단계로 간다.
- **PC 를 옮기지 않는다.** 컨테이너가 멈추고 네트워크가 바뀌어 그 시간이 수집 공백이 된다(2026-11-18 밤 사건). 야간 수집 중에는 한곳에 두고 전원을 연결한다.
- **collector 는 Recreate 라서** 설치·업그레이드 때 파드가 다시 뜨고 그동안 수집이 멈춘다. 장 시간(주간 08:45~15:45, 야간 18:00~06:00. 정확한 값은 `config/session-calendar.yaml`)에는 하지 않는다.
- **`local-path` 저장소는 PVC 를 지우면 데이터도 지워진다**(reclaim Delete). `down.sh`, `helm uninstall` 뒤의 PVC 삭제, 네임스페이스 삭제 전에 백업(아래 '백업과 복원')을 먼저 한다. 2026-11-19 리허설에서 네임스페이스를 지우자 PV 와 노드의 데이터 폴더가 함께 사라졌다.
- **Secret 은 암호화가 아니라 base64 다.** `kubectl get secret -o yaml` 로 누구나 읽는다. 클러스터 API 가 `127.0.0.1:6550` 에만 열려 있다는 것에 기댄다. 값을 화면에 찍지 않는다.
- **이미지 태그가 `local` 고정이다.** 같은 태그로 다시 import 해도 이미 떠 있는 파드는 옛 이미지를 그대로 쓴다. 새 이미지를 쓰려면 파드를 다시 띄운다(1단계).

## 순서

### 1. 이미지 다시 빌드하고 클러스터에 넣기

```bash
docker compose -p futures-risk-ops --env-file <원 저장소 경로>/.env --profile app build   # 미확인
./deploy/k3d/up.sh --import                                                                # 확인함(2026-11-16, Day 7)
docker exec k3d-margin-watch-server-0 crictl images | grep futures-risk-ops                # 미확인
```

- 빌드는 실행 중인 컨테이너를 다시 만들지 않는다. 이미지만 새로 만든다.
- `up.sh --import` 는 `futures-risk-ops-collector:local`, `futures-risk-ops-risk:local` 을 `k3d image import` 로 노드에 넣는다. 클러스터가 이미 있으면 그대로 두고 이미지만 넣는다.
- **확인**: 노드 안 이미지가 방금 빌드한 것인지 본다. 이미지 ID(앞 12자리)를 `docker images` 와 `crictl images` 에서 비교하는 것이 가장 쉽다. ID 가 형식이 달라 비교가 어려우면 `docker image inspect futures-risk-ops-collector:local --format '{{.RootFS.Layers}}'` 의 마지막 레이어 해시를 `crictl inspecti` 결과와 맞춘다(미확인).
- 이미 collector·risk 파드가 떠 있다면 이미지를 넣은 뒤 `kubectl --context k3d-margin-watch -n margin-watch rollout restart deploy/risk` 처럼 다시 띄워야 새 이미지가 쓰인다(미확인). collector 는 5단계의 순서와 시간 제약을 지킨다.

### 2. Secret 만들기

```bash
./deploy/k3d/create-secrets.sh --dry-run    # 확인함(2026-11-19, 진짜 kubectl): Secret 이름과 키 이름만 출력한다
./deploy/k3d/create-secrets.sh              # 확인함(2026-11-19): kis-credentials, postgres-credentials 생성, 저장된 값이 .env 와 일치
```

- 기본은 저장소 루트의 `.env`, 네임스페이스 `margin-watch`. 다른 파일이면 `--env-file 경로`, 모의투자 키면 `--profile kis-paper`.
- `.env` 에서 필요한 키 이름(`KIS_APP_KEY`, `KIS_APP_SECRET`, `POSTGRES_USER`, `POSTGRES_PASSWORD`)만 읽는다. 값이 없거나 비면 이름을 알리며 실패한다. 값은 화면에 나오지 않는다.
- `postgres-credentials` 가 이미 있으면 덮어쓰지 않고 종료 코드 1 로 끝난다(확인함 2026-11-19). 왜냐하면 **Postgres 비밀번호는 데이터 폴더를 처음 만들 때만 적용된다**. Secret 만 바꾸면 이미 만들어진 DB 의 비밀번호와 어긋나 앱이 접속하지 못한다. `--force` 는 DB 가 그 비밀번호와 맞는 것을 알 때만 쓴다.
- **확인**: `kubectl --context k3d-margin-watch -n margin-watch get secret` 에 두 Secret 이 보인다(값은 보지 않는다).

### 3. postgres 설치

```bash
helm install postgres deploy/helm/postgres --kube-context k3d-margin-watch -n margin-watch --create-namespace   # 미확인(이 문서를 쓰는 세션은 실행하지 않았다. 2026-11-19 리허설은 rehearsal 네임스페이스에서 같은 차트로 확인)
kubectl --context k3d-margin-watch -n margin-watch rollout status statefulset/postgres
kubectl --context k3d-margin-watch -n margin-watch get pods,pvc
```

- 차트는 StatefulSet 하나와 PVC, Service `postgres`:5432 만 만든다. DB 이름 `futures_risk_ops`. 스키마는 collector 가 시작할 때 Flyway 로 만든다(여기서 만들지 않는다).
- **확인**: `postgres-0` 이 `1/1 Running`, PVC `data-postgres-0` 이 `Bound`.

**리허설에서 확인한 것(2026-11-19, `rehearsal` 네임스페이스, 합성 데이터)**

- postgres 파드를 지우면 1초 안에 끝난다(종료 유예 60초를 다 쓰지 않는다). 새 파드가 같은 PVC 를 붙여서 **행이 남아 있다**.
- 계정 비밀번호는 데이터 폴더를 처음 만들 때만 적용된다(그래서 2단계의 덮어쓰기 방지가 있다).
- 네임스페이스를 지우면 PVC, PV, 노드의 데이터 폴더가 모두 사라졌다.

**DB 보기**(확인함 2026-11-19. 계정이 명령줄에 나오지 않는다)

```bash
kubectl --context k3d-margin-watch -n margin-watch exec postgres-0 -- sh -c 'psql -U "$POSTGRES_USER" -d futures_risk_ops'
```

`-it` 가 필요하면 붙인다(대화형 psql). 결과를 파일로 받는 명령에는 `-t` 를 쓰지 않는다(아래 '백업과 복원').

### 4. 옛 collector·risk(compose)를 먼저 끈다

```bash
docker compose -p futures-risk-ops --env-file <원 저장소 경로>/.env --profile app stop collector risk   # 미확인
docker ps                                                                                                  # 미확인
```

- **확인**: `docker ps` 에 collector, risk 컨테이너가 없다. 있으면 5단계로 가지 않는다.
- 17:00 일봉 수집이 옛 collector 에서 끝난 뒤에 끈다(지표 `collector_daily_close_last_success_timestamp_seconds` 가 오늘 시각으로 갱신됐는지, [metrics.md](metrics.md)).
- compose DB(`postgres` 서비스)는 지우지 않는다. 볼륨 `futures-risk-ops_pgdata` 도 남긴다. 클러스터 DB 는 빈 DB 로 시작하고 옛 데이터를 옮기지 않는다.

### 5. collector 설치

```bash
# 첫 기동: 일봉을 15일치 채운다
helm install collector deploy/helm/collector --kube-context k3d-margin-watch -n margin-watch \
  --set-file sessionCalendar=config/session-calendar.yaml \
  --set dailyClose.runOnStart=true --set dailyClose.lookbackDays=15                                  # 미확인

kubectl --context k3d-margin-watch -n margin-watch rollout status deploy/collector                  # 미확인
kubectl --context k3d-margin-watch -n margin-watch logs deploy/collector | grep -i flyway           # 미확인

# 일봉이 채워진 것을 확인한 뒤 평소 값으로 돌린다. 파드가 다시 뜬다(Recreate)
helm upgrade collector deploy/helm/collector --kube-context k3d-margin-watch -n margin-watch \
  --reuse-values --set dailyClose.runOnStart=false --set dailyClose.lookbackDays=5                    # 미확인
```

- 캘린더는 차트에 복사하지 않는다. 설치할 때 원본(`config/session-calendar.yaml`)을 `--set-file` 로 넣는다. 빠지면 설치가 거절된다.
- 첫 기동에만 `runOnStart=true`: 재시작마다 KIS 일봉을 부르지 않게 하려고 채운 뒤 끈다. 빈 DB 라서 일봉이 없는 상태에서 시작하기 때문이다.
- **확인**: 로그에 Flyway 마이그레이션 적용(V1~V7), 장 시간이 아니면 접속 대기 로그(`session gate closed`), 장 시간이면 접속과 구독 로그. `kubectl ... get pods` 의 RESTARTS 가 0.
- `daily_bar` 행 수는 3단계의 psql 로 센다: `select count(*) from daily_bar;`(행 수만 보고 값은 적지 않는다).
- helm 이 `--kube-context` 와 `--set-file` 경로를 못 찾으면 저장소 루트에서 실행했는지 본다.

### 6. risk 설치

```bash
helm install risk deploy/helm/risk --kube-context k3d-margin-watch -n margin-watch                  # 미확인
kubectl --context k3d-margin-watch -n margin-watch rollout status deploy/risk                       # 미확인
kubectl --context k3d-margin-watch -n margin-watch port-forward svc/risk 8082:8082                  # 미확인
```

- 포트 포워딩은 그 터미널이 열려 있는 동안만 유지된다. 다른 터미널에서 `curl http://127.0.0.1:8082/actuator/health` 가 `UP` 이면 된다. 체결이 아직 없으면 계산 API 는 빈 값을 낸다. 포트 8082 를 compose risk 가 쓰고 있으면 4단계에서 끄지 않은 것이다.
- 바깥에는 열지 않는다(Service 는 ClusterIP, port-forward 는 이 PC 에서만).

### 7. 확인

```bash
kubectl --context k3d-margin-watch -n margin-watch get pods                         # 미확인(RESTARTS 0, READY 1/1)
kubectl --context k3d-margin-watch -n margin-watch top pod                          # 미확인(metrics-server 필요)
kubectl --context k3d-margin-watch -n margin-watch logs deploy/collector --tail=50  # 미확인
```

- 재시작이 0 이고 `OOMKilled` 가 없다. 메모리는 collector, risk 256Mi/512Mi, postgres 256Mi/1Gi 로 시작해 `top pod` 와 재시작 횟수를 보고 조정한다. 값은 운영 일지에 적는다.
- 체결이 쌓이는지: 3단계의 psql 로 `select session, count(*) from tick_trade group by session;`(행 수만).
- 야간(18:00~06:00)에는 collector 로그에 야간 TR 구독이 보이고 `tick_trade` 에 `session=NIGHT` 행이 늘어난다.

## 장애가 나면

순서는 `describe → logs → events` 다. 되돌리지 않고 원인을 찾아 클러스터에서 고친다.

```bash
kubectl --context k3d-margin-watch -n margin-watch describe pod <파드 이름>             # 미확인: 마지막 상태, 종료 이유(OOMKilled 등), probe 실패
kubectl --context k3d-margin-watch -n margin-watch logs <파드 이름>                     # 미확인: 지금 컨테이너
kubectl --context k3d-margin-watch -n margin-watch logs <파드 이름> --previous          # 미확인: 재시작 전 컨테이너(재시작이 있을 때)
kubectl --context k3d-margin-watch -n margin-watch get events --sort-by=.lastTimestamp  # 미확인: 이미지 없음, 스케줄 실패, 마운트 실패
```

| 증상 | 먼저 볼 것 |
|---|---|
| `ErrImageNeverPull` | 이미지 pullPolicy 가 `Never` 다. 1단계의 `up.sh --import` 를 했는지 |
| `CreateContainerConfigError` | Secret 이 없다. 2단계. `describe pod` 의 이벤트에 어떤 Secret 인지 나온다 |
| postgres 접속 실패(인증) | Secret 의 비밀번호와 DB 의 비밀번호가 다르다. 데이터 폴더를 처음 만들 때의 비밀번호가 기준이다 |
| collector 가 접속하지 않음 | 장 시간 밖이면 정상(`session gate closed`). 장 시간이면 옛 collector 가 꺼졌는지(4단계), KIS 키 |
| 재시작이 반복됨 | `logs --previous`. 재시작마다 접근토큰을 다시 요청하므로 오래 두지 않는다 |

## 백업과 복원

수동으로 한다(자동화 없음). **`down.sh`, PVC 삭제, 네임스페이스 삭제 전에 반드시 백업한다.** 파일은 저장소 밖 `margin-watch/local-data/` 에 둔다(저장소 안에 두면 실제 시세가 올라갈 수 있다). 아래 경로는 합성 예시다.

```bash
# 백업(확인함 2026-11-19, 합성 데이터)
kubectl --context k3d-margin-watch -n margin-watch exec postgres-0 -- sh -c 'pg_dump -U "$POSTGRES_USER" -Fc futures_risk_ops' > "<margin-watch 폴더>/local-data/backup-example.dump"

# 복원 확인: 새 DB 를 만들고 거기에 복원한다(원래 DB 를 덮어쓰지 않는다)
kubectl --context k3d-margin-watch -n margin-watch exec postgres-0 -- sh -c 'psql -U "$POSTGRES_USER" -d postgres -c "create database restore_check"'          # 확인함 2026-11-19
kubectl --context k3d-margin-watch -n margin-watch exec -i postgres-0 -- sh -c 'pg_restore -U "$POSTGRES_USER" -d restore_check' < "<margin-watch 폴더>/local-data/backup-example.dump"   # 확인함 2026-11-19

# 복원한 DB 를 확인하고(행 수만) 지운다
kubectl --context k3d-margin-watch -n margin-watch exec postgres-0 -- sh -c 'psql -U "$POSTGRES_USER" -d restore_check'                                          # 확인함 2026-11-19
kubectl --context k3d-margin-watch -n margin-watch exec postgres-0 -- sh -c 'psql -U "$POSTGRES_USER" -d postgres -c "drop database restore_check"'            # 확인함 2026-11-19(정리)
```

- **백업을 파일로 받을 때 `kubectl exec` 에 `-t` 를 쓰지 않는다.** tty 가 켜지면 줄바꿈이 바뀌고 출력이 섞여 덤프가 깨진다. 복원처럼 입력을 넘길 때는 `-i` 가 필요하다(없으면 입력이 전달되지 않는다).
- `pg_dump -Fc` 는 압축된 사용자 지정 형식이라 `pg_restore` 로만 읽는다. 확인: 파일이 비어 있지 않고 종료 코드가 0 이다.
- 복원은 새 DB 에서 확인한 뒤 필요할 때만 원래 DB 에 한다. 계정은 환경변수 `POSTGRES_USER` 를 컨테이너 안 셸이 읽으므로 명령줄에 값이 나오지 않는다(작은따옴표 안의 `$POSTGRES_USER` 가 호스트가 아니라 파드 안에서 풀린다).
- 덤프 파일에는 실제 시세가 들어간다. 저장소, 이슈, PR, 문서에 올리지 않는다.

## 지울 때

```bash
./deploy/k3d/down.sh   # 클러스터와 그 안의 PVC·데이터가 함께 사라진다. 먼저 백업한다
```

앱만 내릴 때는 `helm uninstall <이름> --kube-context k3d-margin-watch -n margin-watch`. postgres 차트를 내려도 PVC 는 남는다(StatefulSet 의 PVC 는 자동으로 지워지지 않는다). PVC 를 직접 지우면 데이터가 지워진다(미확인: uninstall 뒤 PVC 가 남는 것은 쿠버네티스 기본 동작으로 알려져 있으나 이 클러스터에서 확인하지 않았다).
