# 개발 환경

## 사용자 PC
- Windows 11, 터미널은 Git Bash.
- 작업 클론: `margin-watch\futures-risk-ops`. 계획 문서: `margin-watch-plan/sections/11_30day-plan/`. KIS 마스터 파일: `margin-watch\ref`.

## 줄바꿈 (2026-10-30)
- 커밋 fd8d871: `.gitattributes` 로 eol=lf, `.bat` 은 crlf.
- 커밋 3140e17: `.editorconfig`, `gradlew` 실행 권한 100755.
- PC 작업 폴더 파일 117개를 LF 로 변환했다.
- 사용자는 꼭 필요하지 않으면 설정을 더 바꾸지 않고 지금 상태를 유지하길 원한다.

## Git
- Claude 가 만든 커밋의 저자는 `Claude <noreply@anthropic.com>`.
- (claude.ai 디바이스 연결에서) git 은 잠금 파일 때문에 삭제 권한이 필요했다. `git reset --hard` 는 자동 모드에서 거부됐고, 브랜치 이동은 `checkout`/`branch -f` 로 했다.

## Docker·테스트 (2026-10-30 발견)
- Spring Boot 3.3.5 에 딸린 Testcontainers 1.19.8 은 Docker 29 에서 `client version 1.32 is too old` 오류가 난다. `disabledWithoutDocker` 때문에 DB 테스트가 실패하지 않고 조용히 skipped 된다.
- Testcontainers 1.21.4 로 올려 해결(ea6030f). 그 전에 로컬에서 돌린 DB 테스트도 건너뛰었을 수 있으니, 테스트 결과에서 skipped 0 을 확인한다.
- claude.ai 클라우드 컨테이너에서는 dockerd 를 직접 띄우면 Docker 를 쓸 수 있다. 이미지 빌드는 `--network host` 와 프록시 CA·gradle 미러를 넣은 테스트용 Dockerfile 사본으로 확인했다.

## 시각 확인 (2026-11-17)
- **Git Bash 의 `date` 는 UTC 를 보여 준다**(`TZ=Asia/Seoul` 을 줘도 바뀌지 않았다). 2026-11-16 에 23:18 KST 를 14:18 로 잘못 읽어 '주간장 진행 중'이라고 말했다. 한국 시각은 PowerShell 로 본다:
  `[TimeZoneInfo]::ConvertTime([DateTime]::UtcNow, [TimeZoneInfo]::FindSystemTimeZoneById('Korea Standard Time'))`.
- collector·risk 컨테이너는 `TZ=Asia/Seoul`, 로그 시각은 `+09:00` 이다. 설정·코드는 `Asia/Seoul` 을 명시한다.

## PC 전원 (2026-11-17, 사용자 확인)
- 노트북이고 전원을 항상 연결해 쓴다. 전원 연결일 때 절전이 꺼져 있고(화면만 꺼짐), 덮개를 닫아도 아무 동작을 하지 않게 했다. 발열 때문에 덮개는 열어 둔다.
- 배터리(DC)일 때는 600초(10분) 뒤 절전이다. 전원 어댑터가 빠지면 collector 가 끊길 수 있으므로 전원 연결이 전제다(설정은 바꾸지 않기로 했다).

## 도구 (2026-11-16)
- helm 4.3.2, k3d 5.9.0 을 winget 으로 설치했다. 설치 과정과 문제는 [../ops-log/2026-11-16-k8s-tools-install.md](../ops-log/2026-11-16-k8s-tools-install.md).

