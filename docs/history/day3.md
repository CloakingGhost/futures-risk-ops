# Day 3 이력 (2026-10-29 ~ 10-30)

## 설계
- 선물 만기는 3·6·9·12월 둘째 목요일이다(매달이 아님, 사용자 지적).
- 롤오버: 만기 전날부터 두 월물을 같이 구독하고, 만기일 장이 끝난 뒤 구월물을 해제한다.
- 종목 마스터 `fo_idx_code_mts.mst.zip`(cp949, `|` 구분, 월물구분코드 1=최근월물)를 매일 장 전에 Upsert 한다.
- 야간 마스터 `fo_cme_code.mst` 도 A01612 형식이다 → 야간 H0MFCNT0 tr_key 도 A01612 로 가정(실수신 확인 필요).
- 클라우드 컨테이너와 디바이스 VM 모두 KIS 다운로드 서버에서 403 을 받아서, 마스터 파일은 사용자가 직접 받는다. 위치는 `margin-watch\ref`.
- 옵션은 v0.1 에서 제외(사용자 결정, 선물 먼저).

## PR 진행 방식 (사용자 지시 2026-10-29)
- PR 5 는 단독 PR 로 열지 않았다. `feat/day3-near-month-quote` 에서 새 브랜치로 PR 6(지표)을 작업하고 다시 합친 뒤, 그날 결과를 PR 하나로 올렸다.
- 이 지시는 계획이었고, 진행은 따로 지시를 받았다.

## 구현
- PR 5: 브랜치 `feat/day3-near-month-quote`, 커밋 ff4126f(사용자 승인 후 푸시).
- PR 6: 지표 9종, 커밋 4d850db(`feat/day3-metrics`) → near-month-quote 에 no-ff 병합(e41a2ea), 승인 후 푸시.
- 최종 draft PR #5(`feat/day3-near-month-quote` → main, 2026-10-29), 2026-10-30 병합.
- 지표는 127.0.0.1:8081 `/actuator/prometheus`, 이름은 `docs/metrics.md` 에 고정.
- 접속키 만료 24시간 가정은 지표로만 본다. 자동 재발급은 없다(남은 일).

## 마감 (2026-10-30)
- 사용자 확인 완료: 롤오버 시뮬레이션(clock-start 12/10 15:43, 구독 4→1, 재접속 없음), 야간장 수집.
- 그때 남은 사용자 몫: 로컬 Docker 로 `./gradlew test`(DB 테스트 14개), PR 의 AI 활용 '직접 바꾼 것'·'30분 설명' 체크, ready·병합. (병합은 끝남. DB 테스트는 [environment.md](environment.md) 의 Docker 29 문제 참고)
