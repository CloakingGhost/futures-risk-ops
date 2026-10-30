# db — 모듈 규칙

루트 `CLAUDE.md` 의 절대 규칙과 범위 규칙을 먼저 따른다. 이 파일은 db 에만 해당하는 규칙이다.

- **이 모듈은 Flyway 마이그레이션 파일만 담는다.** 실행은 collector 한 곳에서만 한다. risk 는 Flyway 를 켜지 않는다. risk 에 테이블이 필요해도 여기에 마이그레이션 파일을 더한다.
- **번호가 붙은 파일(`src/main/resources/db/migration/V*.sql`)은 고치지 않는다.** 바꿀 때는 다음 번호 파일을 더한다.
- **이미 쌓인 데이터가 있는 테이블에 열을 더할 때는 nullable 로 더한다.** 지난 행을 채워야 하면 채우는 방법(예: collector 의 backfill 옵션)을 같은 PR 에 적는다.
- **`source` 열로 데이터 출처(KIS, KIS_PAPER, SIMULATOR, REPLAY)를 섞지 않는다.** 새 테이블에도 같은 열과 CHECK 를 둔다.
- 새 마이그레이션 파일을 더하면 `MigrationFilesTest` 에 그 파일의 핵심 내용을 확인하는 테스트를 더한다.
