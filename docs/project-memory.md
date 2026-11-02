# 프로젝트 메모리 요약

2026-11-01 기준. claude.ai 프로젝트 "마진워치"의 메모리를 옮긴 것이다. 이 파일은 `CLAUDE.md` 에서 `@` 로 불러와 세션마다 읽힌다. 그래서 짧게 두고, 자세한 내용은 아래 `docs/history/` 파일에 나눴다. 그 파일들은 필요할 때만 읽는다.

## 목표

KIS API 로 코스피200 선물·옵션을 다루는 개인 개발자용 셀프 호스팅 오픈소스. 실시간 수집, 증거금·마진콜 계산, 대시보드·알람, 사람이 승인해야 움직이는 AI 원인 분석 에이전트. v0.1 은 선물만(옵션 제외, 사용자 결정).

## 지금 어디까지 왔나

| 개발일 | 상태 | 자세히 |
|---|---|---|
| Day 0 | 완료, PR 1 병합(2026-10-26) | [day0-2.md](history/day0-2.md) |
| Day 1 | PR 2 병합 | [day0-2.md](history/day0-2.md) |
| Day 2 | PR 3 병합(2026-10-29) | [day0-2.md](history/day0-2.md) |
| Day 3 | PR #5 병합(2026-10-30) | [day3.md](history/day3.md) |
| Day 4 | PR #6·#7 병합(2026-10-30) | [day4.md](history/day4.md) |
| Day 5 | 아직. `main` 에서 새 브랜치로 시작 | [open-items.md](history/open-items.md) |
| Day 6 | 아직 | |

일정상 2026-11-01 은 Day 6 를 시작할 날이지만, 밀린 Day 5 까지 포함해서 작업한다(사용자 결정, 2026-11-01). Day 5 를 먼저 끝내고 Day 6 로 넘어간다.

작업 폴더: VSCode 는 `margin-watch/` 를 연다. 소스와 GitHub 대상은 `margin-watch/futures-risk-ops/` 하나다.

## 꼭 기억할 결정

- 저장소 `CloakingGhost/futures-risk-ops` 는 공개로 둔다(사용자 결정).
- 옵션은 v0.1 에서 뺐다. 선물 먼저.
- 베이시스는 지수를 따로 받지 않고 체결 메시지(H0IFCNT0/H0MFCNT0) 12~14번 필드로 푼다. 체결 조회 TR 은 쓰지 않는다(이름에 '주문'이 있어 절대 규칙 1 과 부딪힘).
- compose 기본은 DB·collector·risk 3개이고 KIS 모의투자에 붙는다. 시뮬레이터는 `--profile sim` 으로 테스트·데모·장애 실험 때만 띄운다.
- 증거금률은 상품마다 다르고 KRX 가 매월 조정한다. 코스피200 선물은 2026-10 기준 위탁 21.750%, 유지 14.50%. 주식선물(SK하이닉스, 삼성전자 등)은 나중에 추가 예정. [margin-rates.md](history/margin-rates.md)

## 상세 파일 목록

| 파일 | 내용 |
|---|---|
| [history/day0-2.md](history/day0-2.md) | Day 0~2 진행, PR 1~3 |
| [history/day3.md](history/day3.md) | 근월물·롤오버 설계, PR 5·6 → #5, 지표 |
| [history/day4.md](history/day4.md) | 리스크 계산·API, Docker compose, PR #6·#7, 계획 문서 수정 |
| [history/kis-findings.md](history/kis-findings.md) | KIS TR·종목코드·마스터 파일에 대해 확인한 것과 가정 |
| [history/market-calendar.md](history/market-calendar.md) | 만기일, 야간장 거래일, 휴장일, 수능일 |
| [history/margin-rates.md](history/margin-rates.md) | 증거금률과 변경 이력 |
| [history/environment.md](history/environment.md) | Windows·줄바꿈·Git Bash·Docker 29·Testcontainers |
| [history/research.md](history/research.md) | 문제 사례 조사와 순위 |
| [history/open-items.md](history/open-items.md) | 남은 일과 확인 안 된 가정 |
