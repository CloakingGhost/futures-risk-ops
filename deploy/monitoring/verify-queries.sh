#!/usr/bin/env bash
# 대시보드 패널 쿼리 검증(Day 9). 스택을 설치한 뒤 Prometheus API 로 모든 패널의 expr 를 실행해 본다.
# 클러스터를 직접 건드리지 않는다: 주소(PROM_URL)로 읽기 전용 /api/v1/query 만 부르고, curl 과 python -I 만 쓴다.
#
#   kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090   # 다른 터미널에서
#   PROM_URL=http://127.0.0.1:9090 ./deploy/monitoring/verify-queries.sh
#   ./deploy/monitoring/verify-queries.sh --help
#
# 출력: 쿼리마다 한 줄(대시보드, 패널, refId, 결과). 값 자체는 출력하지 않고 시리즈 수만 보인다.
#   OK       시리즈가 하나 이상 있다
#   EMPTY    쿼리는 맞지만 결과가 비었다(빈 패널). 장외·체결 없음·아직 없는 지표(risk 지표 등)이면 정상일 수 있다
#   ERROR    Prometheus 가 쿼리를 거절했거나 접속하지 못했다(문법·함수 오류, 주소 오류)
# 끝 코드: ERROR 가 하나라도 있으면 1. EMPTY 는 기본으로 실패가 아니다(--fail-on-empty 를 주면 실패).
set -euo pipefail

cd "$(dirname "$0")/../.."
DIR=deploy/helm/margin-watch-dashboards/files
FAIL_ON_EMPTY=0

usage() {
    cat <<'EOF'
Usage: PROM_URL=http://127.0.0.1:9090 verify-queries.sh [--fail-on-empty]

Runs every panel query of the dashboards (deploy/helm/margin-watch-dashboards/files/*.json)
against the Prometheus HTTP API (/api/v1/query) and prints, per query, whether the result is
OK (has series), EMPTY (no series: an empty panel) or ERROR (rejected or unreachable).
Values are never printed. Exit code 1 if any query is ERROR (or EMPTY with --fail-on-empty).

Environment:
  PROM_URL   base URL of Prometheus, for example http://127.0.0.1:9090 (required)
EOF
}

for arg in "$@"; do
    case "$arg" in
        -h|--help) usage; exit 0 ;;
        --fail-on-empty) FAIL_ON_EMPTY=1 ;;
        *) echo "Unknown option: $arg" >&2; usage >&2; exit 2 ;;
    esac
done

fail() {
    echo "FAIL: $*" >&2
    exit 2
}

[ -n "${PROM_URL:-}" ] || { usage >&2; fail "PROM_URL is not set"; }
PROM_URL="${PROM_URL%/}"
PY=python
command -v "$PY" >/dev/null 2>&1 || PY=python3
command -v "$PY" >/dev/null 2>&1 || fail "'python' is not in PATH"
command -v curl >/dev/null 2>&1 || fail "'curl' is not in PATH"
[ -d "$DIR" ] || fail "$DIR not found"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# 모든 expr 를 탭으로 나눈 줄로 뽑는다: 대시보드<TAB>패널<TAB>refId<TAB>expr
"$PY" -I - "$DIR" > "$tmp/queries.tsv" <<'PY'
import glob, json, os, sys

for path in sorted(glob.glob(os.path.join(sys.argv[1], "*.json"))):
    d = json.load(open(path, encoding="utf-8"))
    for p in d.get("panels", []):
        for t in p.get("targets", []):
            expr = " ".join(t["expr"].split())            # 줄바꿈·탭을 공백 하나로
            print("\t".join([d["uid"], p["title"].replace("\t", " "), t.get("refId", "A"), expr]))
PY

# 응답(JSON) 한 개를 읽어 'OK|EMPTY|ERROR<TAB>설명' 한 줄로 바꾼다
classify() {
    "$PY" -I -c '
import json, sys
raw = sys.stdin.read()
try:
    r = json.loads(raw)
except ValueError:
    print("ERROR\tnot a Prometheus JSON response")
    sys.exit(0)
if r.get("status") != "success":
    print("ERROR\t%s: %s" % (r.get("errorType", "error"), str(r.get("error", ""))[:120]))
    sys.exit(0)
res = r.get("data", {}).get("result", [])
print(("OK\t%d series" % len(res)) if res else "EMPTY\tno series (empty panel)")
'
}

printf '%-22s %-44s %-3s %-6s %s\n' DASHBOARD PANEL REF RESULT DETAIL
TAB=$'	'
ok=0; empty=0; err=0
while IFS=$'\t' read -r uid title ref expr; do
    # 쿼리 실패(연결 거부 등)도 한 줄로 보고 계속한다. 400 응답에도 JSON 본문이 있어 -f 는 쓰지 않는다
    if body="$(curl -sS --max-time 30 --get --data-urlencode "query=$expr" "$PROM_URL/api/v1/query" 2>"$tmp/curl.err")"; then
        verdict="$(printf '%s' "$body" | classify)"
    else
        verdict="ERROR${TAB}curl failed: $(head -c 100 "$tmp/curl.err" | tr -d '\r\n')"
    fi
    status="${verdict%%"$TAB"*}"
    detail="${verdict#*"$TAB"}"
    case "$status" in
        OK) ok=$((ok + 1)) ;;
        EMPTY) empty=$((empty + 1)) ;;
        *) err=$((err + 1)) ;;
    esac
    printf '%-22s %-44s %-3s %-6s %s\n' "$uid" "${title:0:44}" "$ref" "$status" "$detail"
done < "$tmp/queries.tsv"

echo
echo "summary: OK=$ok EMPTY=$empty ERROR=$err"
[ "$err" = "0" ] || exit 1
if [ "$FAIL_ON_EMPTY" = "1" ] && [ "$empty" != "0" ]; then exit 1; fi
