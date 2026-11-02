# Day 4 이력 (2026-10-30)

## 계획
- 사용자 승인 전에는 코딩하지 않았다.
- 첫 계획과 대체 계획(MVP) 두 개가 있었고, 대체 계획이 승인됐다(10/30). 링크는 저장소 밖 로컬 `margin-watch/docs/links.md`.
- 핵심: H0IFCNT0/H0MFCNT0 12~14번 필드 hts_thpr·mrkt_basis·dprt 를 Flyway V6 로 tick_trade 에 저장한다 → 지수 수집과 새 TR 이 필요 없다. mrkt_basis = 선물 − 지수 는 가정.
- 사용자는 범위 확대에 민감하다: MVP 만, 운영에 치명적인 엣지 케이스만 처리, 새 테이블·TR 전에 기존 데이터부터 확인. 이 내용은 `CLAUDE.md` 범위 규칙으로 들어갔다.
- 체결 조회 TR 은 이름에 '주문'이 있어 절대 규칙 1 과 부딪힌다 → 제외(사용자 결정).
- 계좌 연동은 90일 계획 범위다.
- PR 7(계산·API)과 PR 8(Docker)을 나누는 것이 승인됐다.
- 지수 테이블을 둘지는 장단점을 쉬운 설명으로 들은 뒤 사용자가 정한다(미정).
- 폴더별 CLAUDE.md 는 당시 제안 단계였다. 지금 저장소에는 `collector/`, `db/`, `risk/`, `simulator/` 에 있다.

## 구현
- PR 7 = `feat/day4-risk-calc`: …3a4b909 리포지토리, 384e93f API 3개 + springdoc + health, ea6030f Testcontainers 1.21.4. 줄바꿈 커밋 fd8d871, 3140e17 도 이 브랜치([environment.md](environment.md)).
- PR 8 = `feat/day4-docker-compose`(250dca6, PR 7 위에 쌓음): Dockerfile 3개, compose `--profile app`, SIMULATOR_HOST, `SERVER_ADDRESS=0.0.0.0` 은 이미지에서만.
- 30e05c7: compose 기본은 KIS 모의투자(kis-paper), 시뮬레이터는 `--profile sim` 일 때만. compose 기본 서비스는 DB·collector·risk 3개(사용자 결정: 시뮬레이터는 평소 안 띄우고 테스트·데모·장애 실험용).
- 2026-10-30 사용자 승인 후 푸시. draft PR #6(PR 7, main 대상)과 #7(PR 8, base `feat/day4-risk-calc`)을 열었고 둘 다 병합됐다(#7 은 #6 브랜치로, #6 은 main 으로).
- PC 작업 폴더는 `feat/day4-docker-compose` 에 체크아웃된 상태였다(2026-11-01 확인).
- 실행 명령(Git Bash) 정리는 저장소 밖 로컬 `margin-watch/docs/` 에 있다.

## 계획 문서 수정 (2026-10-30, 원문 보존 `[계획 V2]`)
- day-04: 베이시스 경로, 사례 20개, compose 프로필, PR 브랜치.
- day-09: collector_market_session_open.
- day-17: 옵션 만기 → 선물 기준.
- 00_plan-overview: 점검표 5번, 변경 기록.
- 백업: `margin-watch\implement-test\plan-backup-2026-10-30`.
- 남은 Day 5 이후 점검: Day 7 단일 인스턴스, Day 10, 11, 15.
