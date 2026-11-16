# 2026-11-16 쿠버네티스 도구(k3d, helm) 설치 기록

Day 7 PR 11(k3d 클러스터, Helm 차트)에 필요한 `k3d` 와 `helm` 이 이 PC 에 없어서 설치했다. 실행한 명령, 실패한 명령, 원인, 대응을 순서대로 적는다.
이 PC 를 다시 세팅하거나 같은 도구를 CI(Day 11)에 맞출 때 그대로 따라 할 수 있게 하려는 기록이다. 키·계정 값은 이 과정에 나오지 않는다.

## 환경

| 항목 | 값 |
|---|---|
| OS | Windows 11 Pro (한국 표준시) |
| 터미널 | Git Bash(작업), PowerShell(winget) |
| 패키지 관리자 | winget 1.29.380, Chocolatey 2.5.1 (Scoop 없음) |
| Docker | CPU 16개, 메모리 약 14.5GB |
| 설치 전에 있던 것 | `kubectl` 클라이언트 v1.32.2 |
| 설치 전에 없던 것 | `k3d`, `helm` |

## 결과 요약

| 도구 | 설치한 버전 | 방법 | 설치 위치 |
|---|---|---|---|
| helm | v4.3.2 (`v4.3.2+g8346aaa`) | `winget install --id Helm.Helm` | `%LOCALAPPDATA%\Microsoft\WinGet\Packages\Helm.Helm_Microsoft.Winget.Source_8wekyb3d8bbwe\windows-amd64` |
| k3d | v5.9.0 (기본 k3s 이미지 v1.35.5-k3s1) | `winget install --id k3d.k3d` | `%LOCALAPPDATA%\Microsoft\WinGet\Packages\k3d.k3d_Microsoft.Winget.Source_8wekyb3d8bbwe` |

Helm 4 는 메이저 버전이 3 과 다르다. 이 저장소 차트는 `apiVersion: v2`(Helm 3·4 공통)로 만든다. Day 11 CI 는 같은 버전(4.3.2)으로 맞춘다.

## 순서대로 기록

### 1. 설치 전 상태 확인

```bash
k3d version; helm version --short; kubectl version --client; docker info --format 'cpus={{.NCPU}} mem={{.MemTotal}}'
```

- 결과: `k3d: command not found`, `helm: command not found`, kubectl `v1.32.2`, Docker CPU 16 / 메모리 약 14.5GB.
- 판단: 두 도구를 설치해야 한다.

```bash
winget --version; scoop --version; choco --version
```

- 결과: winget `v1.29.380`, scoop 없음(`command not found`), choco `2.5.1`.
- 판단: choco 는 보통 관리자 권한이 필요하고 winget 은 사용자 권한으로 설치되므로 **winget 을 먼저 쓴다**. winget 에 패키지가 없을 때만 choco 를 쓰기로 했다.

### 2. winget 에서 패키지 찾기

```powershell
winget search --id Helm.Helm --source winget --accept-source-agreements
winget search k3d --source winget --accept-source-agreements
```

- 결과: `Helm.Helm 4.3.2`, `k3d.k3d 5.9.0`(같이 나온 `CrystalMark 3D25` 는 이름만 비슷한 다른 프로그램이라 무시).
- 판단: 둘 다 winget 에 있어 choco 는 쓰지 않았다.

### 3. 설치

```powershell
winget install --id Helm.Helm -e --source winget --accept-package-agreements --accept-source-agreements
winget install --id k3d.k3d -e --source winget --accept-package-agreements --accept-source-agreements
```

- 결과: 둘 다 `설치 성공`. 다운로드 주소는 공식 배포처(`get.helm.sh`, GitHub `k3d-io/k3d` releases)였고 설치 관리자 해시 확인이 통과했다.
- 안내 문구: `경로 환경 변수 수정됨; 셸을 다시 시작하여 새 값을 사용합니다.`, `명령줄 별칭이 추가되었습니다.`

### 4. 실패 1: 설치 직후 같은 터미널에서 명령을 찾지 못함

```bash
helm version --short; k3d version
```

- 결과: `helm: command not found`, `k3d: command not found`.
- 원인: 설치 안내대로 **이미 열려 있던 셸은 설치 전 PATH 를 그대로 들고 있다.** 새 PATH 는 새로 연 터미널부터 적용된다.

### 5. 실패 2: `WinGet\Links` 별칭이 없음

별칭이 `%LOCALAPPDATA%\Microsoft\WinGet\Links` 에 생겼을 것이라 생각하고 전체 경로로 실행했다.

```powershell
& "$env:LOCALAPPDATA\Microsoft\WinGet\Links\helm.exe" version --short
& "$env:LOCALAPPDATA\Microsoft\WinGet\Links\k3d.exe" version
```

- 결과: 둘 다 `is not recognized as the name of a cmdlet...`. `Links` 폴더는 비어 있었고, 사용자 PATH 에도 `Links` 가 없었다.
- 원인(추정): 설치 안내의 "명령줄 별칭이 추가되었습니다"를 `Links` 폴더의 링크로 짐작했지만, 실제로는 `Links` 에 아무것도 생기지 않았다. 관찰한 사실은 아래 확인 결과와 같고, 이 두 패키지는 **링크 대신 패키지 폴더 자체를 사용자 PATH 에 추가**했다(압축 파일·단일 실행 파일 형식 패키지라서 그렇게 된 것으로 보이나 winget 문서로 확인하지는 않았다).
- 확인한 명령:

```powershell
winget list --id Helm.Helm            # 설치되어 있음(4.3.2, 원본 winget)
winget list --id k3d.k3d              # 설치되어 있음(5.9.0, 원본 winget)
Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Directory    # Helm.Helm_..., k3d.k3d_... 폴더가 있음
Get-ChildItem <위 폴더> -Recurse -Filter helm.exe    # windows-amd64\helm.exe
[Environment]::GetEnvironmentVariable('Path','User') -split ';' | Where-Object { $_ -match 'WinGet|Helm|k3d' }
```

- 사용자 PATH 에서 `...\Helm.Helm_Microsoft.Winget.Source_8wekyb3d8bbwe\windows-amd64` 와 `...\k3d.k3d_Microsoft.Winget.Source_8wekyb3d8bbwe` 두 줄을 찾았다. 즉 설치는 정상이고 PATH 도 이미 등록되어 있었다.

### 6. 대응: 이 세션에만 PATH 를 임시로 추가

시스템 설정은 더 건드리지 않고, 작업 중인 셸에서만 위 두 폴더를 PATH 에 더했다.

```bash
export PATH="$PATH:/c/Users/Lee/AppData/Local/Microsoft/WinGet/Packages/Helm.Helm_Microsoft.Winget.Source_8wekyb3d8bbwe/windows-amd64:/c/Users/Lee/AppData/Local/Microsoft/WinGet/Packages/k3d.k3d_Microsoft.Winget.Source_8wekyb3d8bbwe"
helm version --short; k3d version; kubectl version --client
```

- 결과: `v4.3.2+g8346aaa`, `k3d version v5.9.0` / `k3s version v1.35.5-k3s1 (default)`, kubectl `v1.32.2`.
- 도구가 작업용 셸마다 초기화되는 환경이라 **명령 앞에 이 `export` 를 매번 붙였다.** 사용자가 새로 연 터미널에서는 필요 없다.

### 7. 발견: k3s 기본 버전과 kubectl 의 마이너 버전 차이

- 문제: k3d 기본 k3s 는 `v1.35.5` 인데 kubectl 은 `v1.32.2` 다. kubectl 은 서버와 마이너 버전이 ±1 까지만 지원되므로 3 단계 차이는 맞지 않는다. 또 기본 이미지는 시간이 지나면 바뀌어 "지웠다 다시 만들어도 같은 상태"가 깨진다.
- 대응: kubectl 과 같은 마이너인 k3s 이미지를 고정하기로 하고, 태그가 실제로 있는지 확인했다.

```bash
docker manifest inspect rancher/k3s:v1.32.5-k3s1   # 있음
docker manifest inspect rancher/k3s:v1.33.1-k3s1   # 있음
```

- 실패 3: 처음에는 위 태그 네 개를 `for` 반복문 한 줄로 확인하려 했는데, 작업 환경이 반복문 속 변수로 도커를 실행하는 명령을 안전하게 검사할 수 없다고 거절했다. 대응: **명령을 반복문 없이 하나씩 따로 실행**했다.
- 결정: `rancher/k3s:v1.32.5-k3s1` 로 고정(`deploy/k3d/cluster.yaml`, ADR 0001).

### 8. 설치 확인용 클러스터 실행

설치가 쓸 만한지 `deploy/k3d/up.sh`, `down.sh` 로 확인했다(자세한 값은 [ADR 0001](../adr/0001-local-cluster.md)).

| 동작 | 결과 |
|---|---|
| `up.sh`(처음) | 34초, 노드 1개 Ready, API `127.0.0.1:6550` 만 열림, Traefik 없음 |
| `down.sh` | 2초 |
| `up.sh`(다시) | 19초, 지우기 전과 같은 상태 |
| `up.sh --import` | `futures-risk-ops-collector:local`, `futures-risk-ops-risk:local` 이 노드 안에 들어감(각 약 138MB) |

## 새 터미널에서 쓰기

새로 연 터미널(Git Bash 또는 PowerShell)이라면 바로 된다.

```bash
helm version --short
k3d version
./deploy/k3d/up.sh        # 클러스터 만들기
./deploy/k3d/down.sh      # 지우기
```

그래도 `command not found` 이면 사용자 PATH 에 위 5절의 두 폴더가 있는지 확인한다(`[Environment]::GetEnvironmentVariable('Path','User')`).
이미 열려 있던 터미널은 닫고 새로 연다.

## 지우기

```powershell
winget uninstall --id Helm.Helm
winget uninstall --id k3d.k3d
```

## 남은 일

- Day 11 CI 에서는 helm 4.3.2, k3d 5.9.0, k3s v1.32.5-k3s1 로 맞춘다.
- kubectl 을 올릴 때는 `deploy/k3d/cluster.yaml` 의 k3s 이미지도 같은 마이너로 올린다.
