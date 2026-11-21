#!/usr/bin/env bash
# .env 의 값으로 쿠버네티스 Secret 둘을 만든다. Git Bash 에서 실행한다.
#
#   ./deploy/k3d/create-secrets.sh [--env-file 경로] [--namespace 이름] [--profile kis|kis-paper] [--dry-run] [--force]
#
#   --env-file   읽을 파일. 기본은 저장소 루트의 .env
#   --namespace  Secret 을 만들 네임스페이스(없으면 만든다). 기본 margin-watch
#   --profile    kis(실전, 기본) 또는 kis-paper(모의투자). kis-credentials 에 들어갈 키 이름이 달라진다
#   --dry-run    클러스터에 아무것도 보내지 않고, 만들 Secret 이름과 키 이름만 출력한다
#   --force      이미 있는 postgres-credentials 도 덮어쓴다
#
# 만드는 Secret(차트가 이 이름과 키를 읽는다: deploy/helm/collector, deploy/helm/risk)
#   kis-credentials       kis: KIS_APP_KEY, KIS_APP_SECRET / kis-paper: KIS_PAPER_APP_KEY, KIS_PAPER_APP_SECRET
#   postgres-credentials  POSTGRES_USER, POSTGRES_PASSWORD
#
# 값은 화면·명령줄 인자·로그·예외 메시지에 나오지 않는다(저장소 절대 규칙 2). 오류 메시지에는 키 이름만 쓴다.
# 값은 .env 에서 필요한 키만 골라 임시 파일(mktemp, 권한 600)에 쓰고 `kubectl create secret --from-env-file` 로 넘긴다.
# 임시 파일은 끝나면(실패해도) 지운다. 프로세스 치환 <(...) 은 Windows 의 kubectl.exe 가 읽지 못해서 쓰지 않는다.
#
# .env 읽기 규칙: 줄 끝의 \r(CRLF) 제거, 빈 줄과 # 주석 무시, 앞의 `export ` 허용,
#   KEY=값 에서 첫 번째 = 로만 나누므로 값에 =가 있어도 된다, 값 전체가 "…" 또는 '…' 면 따옴표를 벗긴다
#   (따옴표 없는 값은 앞뒤 공백을 지운다. 줄 끝 주석(# …)은 해석하지 않는다: 비밀번호에 # 가 있을 수 있다),
#   같은 키가 여러 번 나오면 마지막 것을 쓴다. 가정: 값에 줄바꿈이 없다.
#
# Postgres 비밀번호는 데이터 폴더를 처음 만들 때만 적용된다. Secret 만 바꾸면 DB 와 어긋나므로,
# postgres-credentials 가 이미 있으면 --force 없이는 덮어쓰지 않는다. kis-credentials 는 덮어써도 된다.
set -euo pipefail

CONTEXT=k3d-margin-watch
DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$DIR/../../.env"
NAMESPACE=margin-watch
PROFILE=kis
DRY_RUN=0
FORCE=0

usage() {
    echo "usage: $0 [--env-file PATH] [--namespace NAME] [--profile kis|kis-paper] [--dry-run] [--force]" >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --env-file)
            [ $# -ge 2 ] || usage
            ENV_FILE="$2"
            shift 2
            ;;
        --namespace)
            [ $# -ge 2 ] || usage
            NAMESPACE="$2"
            shift 2
            ;;
        --profile)
            [ $# -ge 2 ] || usage
            PROFILE="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        --force)
            FORCE=1
            shift
            ;;
        *) usage ;;
    esac
done

case "$PROFILE" in
    kis) KIS_KEYS=(KIS_APP_KEY KIS_APP_SECRET) ;;
    kis-paper) KIS_KEYS=(KIS_PAPER_APP_KEY KIS_PAPER_APP_SECRET) ;;
    *) usage ;;
esac
DB_KEYS=(POSTGRES_USER POSTGRES_PASSWORD)

# 네임스페이스는 DNS 라벨(소문자·숫자·하이픈, 63자 이하)이어야 한다
if ! [[ "$NAMESPACE" =~ ^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$ ]]; then
    usage
fi

[ -f "$ENV_FILE" ] || { echo "ERROR: env file not found: $ENV_FILE" >&2; exit 1; }
[ -r "$ENV_FILE" ] || { echo "ERROR: env file is not readable: $ENV_FILE" >&2; exit 1; }

# 값(=, 따옴표, 공백이 있어도 그대로)을 전역 ENV_VALUE 에 담는다. 명령줄 인자로 넘기지 않으려고 함수 안에서 처리한다.
# 키가 없으면 1 로 돌아온다.
read_env_value() {
    local want="$1" line key val found=1 first=1
    ENV_VALUE=""
    while IFS= read -r line || [ -n "$line" ]; do
        if [ "$first" = 1 ]; then
            line="${line#$'\xef\xbb\xbf'}" # UTF-8 BOM
            first=0
        fi
        line="${line%$'\r'}"
        line="${line#"${line%%[![:space:]]*}"}" # 앞 공백
        case "$line" in
            "" | "#"*) continue ;;
            "export "*) line="${line#export }"; line="${line#"${line%%[![:space:]]*}"}" ;;
        esac
        case "$line" in
            *=*) ;;
            *) continue ;;
        esac
        key="${line%%=*}"
        key="${key%"${key##*[![:space:]]}"}"
        [ "$key" = "$want" ] || continue
        val="${line#*=}"
        val="${val#"${val%%[![:space:]]*}"}"
        val="${val%"${val##*[![:space:]]}"}"
        if [ "${#val}" -ge 2 ]; then
            case "$val" in
                \"*\") val="${val:1:${#val}-2}" ;;
                \'*\') val="${val:1:${#val}-2}" ;;
            esac
        fi
        ENV_VALUE="$val"
        found=0
    done < "$ENV_FILE"
    return "$found"
}

# 임시 파일은 전용 폴더(권한 700) 안에 만들고, 끝나면 폴더째 지운다
umask 077
WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT
trap 'exit 130' INT TERM

# kubectl.exe 에 넘길 경로. Git Bash 의 /tmp/… 를 Windows 경로로 바꾼다(cygpath 가 없으면 그대로)
native_path() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# 필요한 키만 골라 임시 env 파일을 만든다. 없거나 비면 이름을 모아 둔다.
# 사용: build_env_file 파일 KEY...   (모은 이름은 전역 BAD_KEYS)
BAD_KEYS=()
build_env_file() {
    local out="$1" k
    shift
    : > "$out"
    for k in "$@"; do
        if read_env_value "$k" && [ -n "$ENV_VALUE" ]; then
            printf '%s=%s\n' "$k" "$ENV_VALUE" >> "$out"
        else
            BAD_KEYS+=("$k")
        fi
    done
    ENV_VALUE=""
}

KIS_ENV="$WORK/kis.env"
DB_ENV="$WORK/db.env"
build_env_file "$KIS_ENV" "${KIS_KEYS[@]}"
build_env_file "$DB_ENV" "${DB_KEYS[@]}"
if [ "${#BAD_KEYS[@]}" -gt 0 ]; then
    echo "ERROR: missing or empty in $ENV_FILE: ${BAD_KEYS[*]}" >&2
    exit 1
fi

if [ "$DRY_RUN" = 1 ]; then
    echo "dry-run: nothing is sent to the cluster"
    echo "context:   $CONTEXT"
    echo "namespace: $NAMESPACE"
    echo "secret kis-credentials: ${KIS_KEYS[*]} (profile $PROFILE)"
    echo "secret postgres-credentials: ${DB_KEYS[*]}"
    exit 0
fi

command -v kubectl >/dev/null 2>&1 || { echo "ERROR: 'kubectl' is not in PATH (see docs/ops-log/2026-11-16-k8s-tools-install.md)" >&2; exit 1; }
kc() { kubectl --context "$CONTEXT" "$@"; }

# 이미 있는 postgres-credentials 는 --force 없이 덮어쓰지 않는다. 아무것도 바꾸기 전에 확인한다.
existing="$(kc -n "$NAMESPACE" get secret postgres-credentials --ignore-not-found -o name)" \
    || { echo "ERROR: cannot check for an existing postgres-credentials (is context $CONTEXT up?)" >&2; exit 1; }
if [ -n "$existing" ]; then
    if [ "$FORCE" != 1 ]; then
        echo "ERROR: secret postgres-credentials already exists in namespace $NAMESPACE; not overwritten." >&2
        echo "       The Postgres password only applies when the data directory is first created, so changing the Secret alone" >&2
        echo "       would no longer match the database. Use --force only if you know the database will match." >&2
        exit 1
    fi
    echo "WARN: overwriting postgres-credentials (--force); an existing database keeps its original password" >&2
fi

# 네임스페이스가 없으면 만든다(있으면 그대로)
kc create namespace "$NAMESPACE" --dry-run=client -o yaml | kc apply -f -

# YAML(값 포함)은 파이프로만 흐른다. 화면에는 apply 가 찍는 Secret 이름만 나온다.
apply_secret() {
    local name="$1" file="$2"
    kc -n "$NAMESPACE" create secret generic "$name" --from-env-file="$(native_path "$file")" --dry-run=client -o yaml \
        | kc apply -f -
}
apply_secret kis-credentials "$KIS_ENV"
apply_secret postgres-credentials "$DB_ENV"

echo "done: kis-credentials (${KIS_KEYS[*]}) and postgres-credentials (${DB_KEYS[*]}) in namespace $NAMESPACE"
