# collector — 모듈 규칙

루트 `CLAUDE.md` 의 절대 규칙과 범위 규칙을 먼저 따른다. 이 파일은 collector 에만 해당하는 규칙이다.

## 지키는 것

- **한 번에 하나만 띄운다.** KIS 웹소켓 접속은 하나라서 collector 를 두 개 띄우면 접속 충돌과 중복 적재가 생긴다. 배포 설정도 복제본 1개, 재시작은 기존 것을 내린 뒤 새로 띄우는 방식으로 둔다. Helm 차트는 `deploy/helm/collector` 다: `replicas: 1` 은 템플릿에 박혀 있어 values 로 바꿀 수 없고(`values.schema.json` 이 거절), strategy 는 Recreate, HPA 는 없다. `deploy/helm/check.sh` 가 렌더링 결과로 이를 확인한다(Day 11 CI 가 부른다). 차트를 고칠 때 이 규칙을 풀지 않는다. compose collector 와 클러스터 collector 를 동시에 띄우지 않는다(KIS 접속이 둘이 된다). 클러스터에 설치하기 전에 compose collector 를 먼저 끄고 `docker ps` 로 확인한다(순서는 [docs/deploy-k3d.md](../docs/deploy-k3d.md)).
- **장 시간은 `config/session-calendar.yaml` 한 곳에만 적는다.** 세션 시각, 휴장일, 특수 개장, 야간장 거래일 규칙을 Java 코드에 직접 쓰지 않는다. collector 는 빌드할 때 이 파일을 jar 에 넣고, `collector.session.calendar=file:경로` 로 바꿔 읽을 수 있다.
- **지표 이름은 `docs/metrics.md` 에 고정했다.** 이름·라벨을 바꾸면 그 문서와 `CollectorMetricsTest` 를 같은 커밋에서 고친다.
- **결제월·만기 규칙은 `FuturesExpiry`, 종목코드 규칙은 `FuturesCode` 한 곳에만 둔다.**
- **새 데이터가 필요하면 받는 메시지부터 본다.** 체결 메시지(H0IFCNT0·H0MFCNT0)에는 지금 저장하지 않는 필드가 많다. 필드 표는 `docs/kis-h0ifcnt0.md`. 새 TR 을 구독하기 전에 이 표로 풀 수 있는지 먼저 확인한다.
- **일봉은 계산하지 않고 KIS 에서 받는다.** `FHKIF03020100` 을 `collector.daily-close` 스케줄로 받아 `daily_bar` 에 upsert 한다. tick_trade 로 일봉을 다시 만들지 않는다. 첫 실호출로 확인할 가정과 결과는 `docs/kis-daily-bar.md` 에 날짜와 함께 적는다.
- **KIS 문서의 '모의 미지원' 표시는 실제와 다를 수 있다.** 새 TR 은 모의투자에서 실제로 받아 본 뒤 그 날짜를 `docs/kis-h0ifcnt0.md` 에 적는다. 확인 전에는 '가정'으로 적는다.

## 테스트

- PostgreSQL 이 필요한 테스트는 Testcontainers 로 임시 DB 를 띄운다. Docker 가 없으면 그 테스트만 건너뛴다(skipped).
- 시각에 따라 달라지는 동작(세션 전환, 롤오버, 접속키 만료)은 `Clock` 을 주입해서 시험한다. 실제 시각을 기다리지 않는다.
