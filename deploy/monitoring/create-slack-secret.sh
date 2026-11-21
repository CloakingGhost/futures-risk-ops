#!/usr/bin/env bash
# .env 의 Slack 웹훅 주소로 Alertmanager 가 읽는 쿠버네티스 Secret 하나를 만든다. Git Bash 에서 실행한다.
#
#   ./deploy/monitoring/create-slack-secret.sh [--env-file 경로] [--namespace 이름] [--dry-run] [--force]
#
#   --env-file   읽을 파일. 기본은 저장소 루트의 .env
#   --namespace  Secret 을 만들 네임스페이스(없으면 만든다). 기본 monitoring
#   --dry-run    클러스터에 아무것도 보내지 않고, 만들 Secret 이름·키 이름·네임스페이스만 출력한다
#   --force      받기만 한다(효과 없음). deploy/k3d/create-secrets.sh 와 옵션 모양을 맞추려고 둔다.
#                웹훅 주소는 바꿔도 안전해서(Postgres 비밀번호와 달리) 이미 있는 Secret 을 늘 덮어쓴다
#
# 만드는 Secret(deploy/monitoring/kube-prometheus-stack-values.yaml 의 alertmanager 절이 이 이름과 키를 읽는다)
#   alertmanager-slack   키 webhook-url  <- .env 의 SLACK_ALERTS_WEBHOOKS
#
# 값은 화면·명령줄 인자·로그·예외 메시지에 나오지 않는다(저장소 절대 규칙 2). 오류 메시지에는 변수 이름만 쓴다.
# 값은 임시 파일(mktemp, 권한 600)에 줄바꿈 없이 쓰고 `kubectl create secret --from-file=webhook-url=<파일>` 로 넘긴다.
# (--from-env-file 은 쓰지 않는다: 키 이름이 환경변수 이름 규칙에 묶이고, 웹훅 주소는 한 값뿐이라 파일이 더 단순하다.)
# 임시 파일은 끝나면(실패해도) 지운다. 프로세스 치환 <(...) 은 Windows 의 kubectl.exe 가 읽지 못해서 쓰지 않는다.
#
# .env 읽기 규칙: 줄 끝의 \r(CRLF) 제거, 빈 줄과 # 주석 무시, 앞의 `export ` 허용,
#   KEY=값 에서 첫 번째 = 로만 나누므로 값에 =가 있어도 된다, 값 전체가 "…" 또는 '…' 면 따옴표를 벗긴다
#   (따옴표 없는 값은 앞뒤 공백을 지운다. 줄 끝 주석(# …)은 해석하지 않는다),
#   같은 키가 여러 번 나오면 마지막 것을 쓴다. 가정: 값에 줄바꿈이 없다.
#
# 값 형식 검사: https://hooks.slack.com/services/ 로 시작하고 그 뒤에 영문·숫자·_ - / 만 있어야 한다.
#   공백·쉼표·따옴표가 끼면 실패한다(웹훅 하나만 받는다. 여러 개를 쉼표로 잇는 것은 지원하지 않는다).
#   실패해도 값은 출력하지 않는다. 가정: Slack Incoming Webhook 주소 모양이 위와 같다.
set -euo pipefail

CONTEXT=k3d-margin-watch
DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$DIR/../../.env"
NAMESPACE=monitoring
DRY_RUN=0

VAR_NAME=SLACK_ALERTS_WEBHOOKS
SECRET_NAME=alertmanager-slack
SECRET_KEY=webhook-url
URL_PREFIX="https://hooks.slack.com/services/"

usage() {
    echo "usage: $0 [--env-file PATH] [--namespace NAME] [--dry-run] [--force]" >&2
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
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        --force)
            shift
            ;;
        *) usage ;;
    esac
done

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

# ENV_VALUE 가 웹훅 주소 모양인지 본다. 아니면 변수 이름만 알리고 끝낸다(값은 말하지 않는다).
validate_webhook() {
    case "$ENV_VALUE" in
        "$URL_PREFIX"?*) ;;
        *)
            echo "ERROR: $VAR_NAME in $ENV_FILE is not in webhook URL format (expected to start with $URL_PREFIX)" >&2
            exit 1
            ;;
    esac
    case "${ENV_VALUE#"$URL_PREFIX"}" in
        *[!A-Za-z0-9_/-]*)
            echo "ERROR: $VAR_NAME in $ENV_FILE contains characters a webhook URL does not have (space, comma, quote, ...)" >&2
            exit 1
            ;;
    esac
}

HOOK_FILE="$WORK/$SECRET_KEY"
if ! read_env_value "$VAR_NAME" || [ -z "$ENV_VALUE" ]; then
    echo "ERROR: missing or empty in $ENV_FILE: $VAR_NAME" >&2
    exit 1
fi
validate_webhook
printf '%s' "$ENV_VALUE" > "$HOOK_FILE"
ENV_VALUE=""

if [ "$DRY_RUN" = 1 ]; then
    echo "dry-run: nothing is sent to the cluster"
    echo "context:   $CONTEXT"
    echo "namespace: $NAMESPACE"
    echo "secret $SECRET_NAME: $SECRET_KEY (from $VAR_NAME)"
    exit 0
fi

command -v kubectl >/dev/null 2>&1 || { echo "ERROR: 'kubectl' is not in PATH (see docs/ops-log/2026-11-16-k8s-tools-install.md)" >&2; exit 1; }
kc() { kubectl --context "$CONTEXT" "$@"; }

# 네임스페이스가 없으면 만든다(있으면 그대로)
kc create namespace "$NAMESPACE" --dry-run=client -o yaml | kc apply -f -

# YAML(값 포함)은 파이프로만 흐른다. 화면에는 apply 가 찍는 Secret 이름만 나온다. 이미 있으면 덮어쓴다.
kc -n "$NAMESPACE" create secret generic "$SECRET_NAME" --from-file="$SECRET_KEY=$(native_path "$HOOK_FILE")" --dry-run=client -o yaml \
    | kc apply -f -

echo "done: $SECRET_NAME ($SECRET_KEY) in namespace $NAMESPACE"
