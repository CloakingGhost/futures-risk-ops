# Day 0~2 이력

- Day 0 완료, PR 1 병합(2026-10-26).
- 브랜치 이름 규칙(type/short-description)은 2026-10-26 사용자 요청으로 정했다.
- PR 2(Day 1) 병합.
- PR 3(Day 2, 브랜치 `feat/day2-reconnect-calendar`) 병합(2026-10-29). 내용: 재접속, 누락·수신 공백 감지, 세션 캘린더, Flyway V3(source 포함 중복키), V4(KIS_PAPER 출처).
- 사용자가 10/29 데이터의 trade_date 와 source=KIS_PAPER 가 맞게 들어온 것을 확인했다.
- 10/28 아침 KIS 에서 받은 12,665건은 V4 전이라 source=KIS 로 남아 있다. 갱신할지는 사용자가 직접 정한다.
- Day 1~4 계획을 V2 로 고쳤다(원문 보존, `[계획 V2]` 표시). Day 4 PR 뒤에 Day 5 이후 재작업 위험을 다시 점검하기로 했다.
