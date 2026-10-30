# simulator — 모듈 규칙

루트 `CLAUDE.md` 의 절대 규칙과 범위 규칙을 먼저 따른다. 이 파일은 simulator 에만 해당하는 규칙이다.

- **JDK 만 쓰는 독립 도구로 둔다.** 외부 의존성을 추가하려면 이유를 PR 에 적는다.
- **만드는 값은 모두 합성값이다.** 실제 시세를 넣거나 흉내 내려고 복사하지 않는다(루트 절대 규칙 3).
- **KIS 메시지 형식(필드 이름·순서·구분자)은 공식 샘플 저장소(`koreainvestment/open-trading-api`)를 따른다.** collector 파서와 같은 필드 표(`docs/kis-h0ifcnt0.md`)를 기준으로 한다.
- 속도, 지연, 끊김, 중단, 시작 시각은 설정으로 바꾼다. 설정 옵션을 더하면 `--help` 출력과 `simulator/README.md` 를 같이 고친다.
