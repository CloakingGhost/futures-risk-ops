#!/usr/bin/env bash
# Grafana 대시보드 검사(Day 9). 클러스터와 Docker 를 쓰지 않는다: python(-I), helm lint·template, grep 만 쓴다.
# deploy/monitoring/check.sh 와 deploy/helm/check.sh 는 건드리지 않고 별도로 둔 파일이다.
#
#   ./deploy/monitoring/check-dashboards.sh
#
# 대시보드 JSON 원본은 deploy/helm/margin-watch-dashboards/files/ 한 곳뿐이다(차트가 저장소 밖 경로를 읽지 못하므로
# 규칙 차트(margin-watch-rules/files/)와 같은 방식으로 원본을 차트 안에 둔다. 복사본이 없으니 서로 어긋날 일이 없다).
#
# 확인하는 것:
#   JSON      문법, 대시보드 4개, uid 중복·빈 값 없음, 패널 6~10개, 패널마다 title·description·unit·expr
#   지표 이름 모든 expr 의 지표 이름이 허용 목록에 속한다: docs/metrics.md 의 collector 지표 10종(+히스토그램 접미사),
#             스프링 자동 지표(jvm_, hikaricp_, http_server_requests_, process_), 쿠버네티스 지표 3종, node_, up, ALERTS
#   차트      helm lint, helm template -> ConfigMap 4개(라벨 grafana_dashboard: "1", 네임스페이스 monitoring),
#             ConfigMap 안의 JSON 이 원본과 같다, 1MiB 한도 아래, 모르는 값 거절
#   비밀      JSON 에 비밀번호·토큰·웹훅 같은 값이 없다
set -euo pipefail

cd "$(dirname "$0")/../.."
HELM=deploy/helm
CHART="$HELM/margin-watch-dashboards"
DIR="$CHART/files"
METRICS_DOC=docs/metrics.md
EXPECTED="collector-ingest collector-latency jvm-db risk-events"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

PY=python
command -v "$PY" >/dev/null 2>&1 || PY=python3
command -v "$PY" >/dev/null 2>&1 || fail "'python' is not in PATH (used with -I for JSON checks)"
command -v helm >/dev/null 2>&1 || fail "'helm' is not in PATH (see docs/ops-log/2026-11-16-k8s-tools-install.md)"
[ -d "$DIR" ] || fail "$DIR not found"
[ -f "$METRICS_DOC" ] || fail "$METRICS_DOC not found"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "== single source: no second copy of the dashboards"
[ ! -e deploy/monitoring/dashboards ] || fail "deploy/monitoring/dashboards exists: the JSON must live only in $DIR"
for name in $EXPECTED; do
    [ -f "$DIR/$name.json" ] || fail "$DIR/$name.json is missing"
done
count="$(find "$DIR" -type f | wc -l | tr -d ' ')"
[ "$count" = "4" ] || fail "expected 4 files in $DIR, found $count"

echo "== JSON syntax"
for f in "$DIR"/*.json; do
    "$PY" -I -m json.tool "$f" >/dev/null || fail "$f is not valid JSON"
done

echo "== metric names (docs/metrics.md fixes the 10 collector metrics)"
# 고정 10종은 문서 표의 첫 열에서 읽는다(이름을 여기에 따로 적지 않는다)
grep -oE '^\| [0-9]+ \| `collector_[a-z_]+`' "$METRICS_DOC" | sed -E 's/.*`(collector_[a-z_]+)`/\1/' > "$tmp/collector-metrics.txt"
n="$(wc -l < "$tmp/collector-metrics.txt" | tr -d ' ')"
[ "$n" = "10" ] || fail "expected 10 collector metrics in $METRICS_DOC, found $n"

"$PY" -I - "$DIR" "$tmp/collector-metrics.txt" <<'PY'
import glob, json, os, re, sys

dir_, metrics_file = sys.argv[1], sys.argv[2]
collector = set(open(metrics_file, encoding="utf-8").read().split())
# 히스토그램 두 개는 _bucket, _sum, _count, _max 가 함께 나온다
histograms = {"collector_trade_to_receive_seconds", "collector_receive_to_commit_seconds"}
assert histograms <= collector, "histogram metric missing from docs/metrics.md"
allowed_exact = set(collector) | {
    "kube_pod_container_status_restarts_total",
    "container_memory_working_set_bytes",
    "container_cpu_usage_seconds_total",
    "up",
    "ALERTS",
}
for h in histograms:
    allowed_exact |= {h + s for s in ("_bucket", "_sum", "_count", "_max")}
allowed_prefix = ("jvm_", "hikaricp_", "http_server_requests_", "process_", "node_")
keywords = {"by", "without", "on", "ignoring", "group_left", "group_right", "and", "or", "unless", "bool", "offset"}

errors = []


def metric_names(expr):
    s = re.sub(r'"(?:[^"\\]|\\.)*"', '""', expr)          # 문자열
    s = re.sub(r"\{[^}]*\}", "", s)                       # 라벨 조건
    s = re.sub(r"\[[^\]]*\]", "", s)                      # 범위
    s = re.sub(r"\b(?:by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)", "", s)  # 라벨 목록
    names = []
    for m in re.finditer(r"[A-Za-z_:][A-Za-z0-9_:]*", s):
        word = m.group(0)
        rest = s[m.end():].lstrip()
        if rest.startswith("("):                           # 함수 이름
            continue
        if word in keywords:
            continue
        names.append(word)
    return names


uids = {}
files = sorted(glob.glob(os.path.join(dir_, "*.json")))
if len(files) != 4:
    errors.append("expected 4 dashboards, found %d" % len(files))
total_exprs = 0
for path in files:
    base = os.path.basename(path)
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    uid = d.get("uid")
    if not uid or not isinstance(uid, str):
        errors.append("%s: uid is missing" % base)
    elif uid in uids:
        errors.append("%s: uid %s is also used by %s" % (base, uid, uids[uid]))
    else:
        uids[uid] = base
    if not d.get("title"):
        errors.append("%s: title is missing" % base)
    if not isinstance(d.get("schemaVersion"), int):
        errors.append("%s: schemaVersion is missing" % base)
    panels = d.get("panels", [])
    if not 6 <= len(panels) <= 10:
        errors.append("%s: expected 6..10 panels, found %d" % (base, len(panels)))
    ids = [p.get("id") for p in panels]
    if len(set(ids)) != len(ids):
        errors.append("%s: duplicate panel ids" % base)
    for p in panels:
        where = "%s / %s" % (base, p.get("title", "(no title)"))
        if not p.get("title"):
            errors.append("%s: panel without title" % base)
        if not p.get("description"):
            errors.append("%s: description is missing" % where)
        if "unit" not in p.get("fieldConfig", {}).get("defaults", {}):
            errors.append("%s: unit is missing" % where)
        targets = p.get("targets", [])
        if not targets:
            errors.append("%s: no query" % where)
        for t in targets:
            ds = t.get("datasource") or p.get("datasource") or {}
            if ds.get("type") != "prometheus" or ds.get("uid") != "prometheus":
                errors.append("%s: datasource must be the default Prometheus (uid prometheus, kube-prometheus-stack 기본값)" % where)
            expr = t.get("expr")
            if not expr:
                errors.append("%s: target without expr" % where)
                continue
            total_exprs += 1
            names = metric_names(expr)
            if not names:
                errors.append("%s: no metric name found in: %s" % (where, expr))
            for name in names:
                if name in allowed_exact or name.startswith(allowed_prefix):
                    continue
                errors.append("%s: metric '%s' is not on the allowed list (expr: %s)" % (where, name, expr))
if errors:
    for e in errors:
        print("FAIL: " + e, file=sys.stderr)
    sys.exit(1)
print("ok: %d dashboards, %d queries, uids %s" % (len(files), total_exprs, ", ".join(sorted(uids))))
PY

echo "== secrets and values (absolute rule 2, 3)"
if grep -nEi 'password|secret|api[_-]?key|app[_-]?key|bearer|token|hooks\.slack\.com|xox[a-z]-|BEGIN [A-Z ]*PRIVATE KEY' "$DIR"/*.json; then
    fail "a dashboard JSON contains a secret-like word"
fi

echo "== chart: helm lint, template"
helm lint "$CHART"
helm template margin-watch-dashboards "$CHART" -n monitoring > "$tmp/rendered.yaml"
n="$(grep -c '^kind: ConfigMap$' "$tmp/rendered.yaml")"
[ "$n" = "4" ] || fail "expected 4 ConfigMaps, rendered $n"
n="$(grep -c '^    grafana_dashboard: "1"$' "$tmp/rendered.yaml")"
[ "$n" = "4" ] || fail "expected 4 ConfigMaps labelled grafana_dashboard: \"1\", found $n"
n="$(grep -c '^  namespace: monitoring$' "$tmp/rendered.yaml")"
[ "$n" = "4" ] || fail "expected 4 ConfigMaps in namespace monitoring, found $n"
for name in $EXPECTED; do
    grep -q "^  name: margin-watch-dashboard-$name\$" "$tmp/rendered.yaml" || fail "ConfigMap margin-watch-dashboard-$name is missing"
done
# 다른 종류는 만들지 않는다
if grep '^kind: ' "$tmp/rendered.yaml" | grep -qv '^kind: ConfigMap$'; then fail "the chart rendered something other than ConfigMaps"; fi

# ConfigMap 안의 JSON 이 원본과 같은지, 1MiB 한도 아래인지
"$PY" -I - "$DIR" "$tmp/rendered.yaml" <<'PY'
import json, os, re, sys

dir_, rendered = sys.argv[1], sys.argv[2]
text = open(rendered, encoding="utf-8").read()
docs = [d for d in text.split("\n---\n") if "kind: ConfigMap" in d]
seen = set()
for doc in docs:
    m = re.search(r"^data:\n  ([A-Za-z0-9_.-]+\.json): \|-\n((?:    .*\n?|\n)+)", doc, re.M)
    if not m:
        print("FAIL: ConfigMap without a data block", file=sys.stderr)
        sys.exit(1)
    fname, block = m.group(1), m.group(2)
    body = "\n".join(line[4:] for line in block.rstrip("\n").split("\n"))
    original = open(os.path.join(dir_, fname), encoding="utf-8").read()
    if json.loads(body) != json.loads(original):
        print("FAIL: ConfigMap content differs from files/%s" % fname, file=sys.stderr)
        sys.exit(1)
    size = len(body.encode("utf-8"))
    if size > 900 * 1024:
        print("FAIL: %s is %d bytes, too close to the 1MiB ConfigMap limit" % (fname, size), file=sys.stderr)
        sys.exit(1)
    seen.add(fname)
if len(seen) != 4:
    print("FAIL: expected 4 distinct dashboards in the render, found %d" % len(seen), file=sys.stderr)
    sys.exit(1)
print("ok: rendered JSON equals the originals, sizes under the limit")
PY

# 값 검증: 모르는 키는 거절한다
if helm template margin-watch-dashboards "$CHART" --set bogus=1 >/dev/null 2>&1; then fail "the chart accepted an unknown value key"; fi
# 라벨 값을 바꾸면 따라간다(sidecar 설정을 바꿨을 때)
helm template margin-watch-dashboards "$CHART" --set sidecar.label=other_label --set-string sidecar.labelValue=x | grep -q '^    other_label: "x"$' \
    || fail "sidecar label values are not used"

echo "OK: dashboard checks passed"
