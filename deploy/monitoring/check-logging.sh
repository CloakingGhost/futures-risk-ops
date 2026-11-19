#!/usr/bin/env bash
# 로그 수집(Loki + Alloy) 설정 검사(Day 9). 클러스터를 건드리지 않는다: helm template(차트는 저장소에서 내려받는다), Docker 로 설정 문법 검사, grep 만 쓴다.
# deploy/monitoring/check.sh 는 건드리지 않고 별도로 둔 파일이다.
#
#   ./deploy/monitoring/check-logging.sh
#
# 필요한 것: helm, 그리고 helm 저장소 grafana (helm repo add grafana https://grafana.github.io/helm-charts).
# 차트를 내려받으므로 처음에는 네트워크가 필요하다. 저장소가 없거나 내려받지 못하면 이유를 알리고 실패한다.
# 설정 문법 검사(Loki -verify-config, Alloy validate)는 Docker 로 돌린다. Docker 가 없거나 이미지를 받지 못하면
# 'UNVERIFIED' 로 알리고 넘어간다(실패가 아니다. 나머지 검사는 그대로 한다).
#
# 확인하는 것:
#   Loki       고정 버전 렌더링, SingleBinary(StatefulSet 1개, replicas 1, -target=all), 보존 72h 와 compactor 보존 켬, 인증 끔,
#              파일시스템 저장(tsdb), PVC 5Gi local-path, 메모리 requests·limits, ClusterIP, 불필요한 구성요소 없음(Deployment·DaemonSet·캐시·게이트웨이)
#   Alloy      고정 버전 렌더링, DaemonSet 1개, 설정에 네임스페이스 margin-watch 만, 권한은 margin-watch Role 하나(ClusterRole 없음), 서비스 없음, 메모리 한도 128~256Mi
#   연결       Alloy 가 보내는 URL, Grafana 데이터소스 URL 이 Loki 서비스 이름·포트와 같다. 데이터소스 라벨이 sidecar 라벨과 같다
#   비밀       세 파일에 비밀번호·토큰·웹훅 같은 값이 없다
# 시험용 환경변수(일부러 틀린 파일로 검사가 실패하는지 볼 때): LOKI_VALUES, ALLOY_VALUES, DATASOURCE
set -euo pipefail

cd "$(dirname "$0")/../.."
LOKI_VALUES="${LOKI_VALUES:-deploy/monitoring/loki-values.yaml}"
ALLOY_VALUES="${ALLOY_VALUES:-deploy/monitoring/alloy-values.yaml}"
DATASOURCE="${DATASOURCE:-deploy/monitoring/grafana-datasource-loki.yaml}"
STACK_VALUES=deploy/monitoring/kube-prometheus-stack-values.yaml
NS=monitoring
APP_NS=margin-watch

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

unverified() {
    echo "UNVERIFIED: $*" >&2
}

command -v helm >/dev/null 2>&1 || fail "'helm' is not in PATH (see docs/ops-log/2026-11-16-k8s-tools-install.md)"
for f in "$LOKI_VALUES" "$ALLOY_VALUES" "$DATASOURCE" "$STACK_VALUES"; do
    [ -f "$f" ] || fail "$f not found"
done
helm repo list 2>/dev/null | grep -q '^grafana[[:space:]]' || fail "helm repo 'grafana' is not added: helm repo add grafana https://grafana.github.io/helm-charts && helm repo update"

# 값 파일 머리의 '# 차트: grafana/<이름> <버전> (' 줄에서 고정 버전을 읽는다
chart_version() {
    local line
    line="$(grep -E "^# 차트: grafana/$2 [0-9]+\.[0-9]+\.[0-9]+ " "$1" | head -1)"
    [ -n "$line" ] || fail "chart version line '# 차트: grafana/$2 X.Y.Z' not found in $1"
    echo "$line" | sed -E 's/^# 차트: grafana\/[a-z]+ ([0-9]+\.[0-9]+\.[0-9]+) .*/\1/'
}
LOKI_VERSION="$(chart_version "$LOKI_VALUES" loki)"
ALLOY_VERSION="$(chart_version "$ALLOY_VALUES" alloy)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
# docker.exe 는 Git Bash 의 /tmp/... 를 읽지 못한다. Windows 형식 경로(C:/...)로 바꾼다
tmp_win="$(cd "$tmp" && (pwd -W 2>/dev/null || pwd))"

render() {   # render <릴리스> <차트> <버전> <values> [helm 인자...]
    local rel="$1" chart="$2" ver="$3" vals="$4"
    shift 4
    helm template "$rel" "grafana/$chart" --version "$ver" -n "$NS" -f "$vals" "$@" 2>"$tmp/helm.err" \
        || { cat "$tmp/helm.err" >&2; fail "helm template grafana/$chart --version $ver failed (network? values key renamed in this chart version?)"; }
}

# ConfigMap 안의 '  <키>: |' 블록을 파일로 꺼낸다(들여쓰기 4칸을 걷어 낸다)
extract_block() {   # extract_block <렌더링 파일> <키>
    awk -v k1="  $2: |" -v k2="  $2: |-" '$0==k1 || $0==k2 {p=1; next} p && /^    / {sub(/^    /, ""); print; next} p && /^[[:space:]]*$/ {print ""; next} p {exit}' "$1"
}

# ---- Loki ---------------------------------------------------------------------------------------
echo "== loki: grafana/loki $LOKI_VERSION"
render loki loki "$LOKI_VERSION" "$LOKI_VALUES" > "$tmp/loki.yaml"
render loki loki "$LOKI_VERSION" "$LOKI_VALUES" --show-only templates/config.yaml > "$tmp/loki-cm.yaml"
render loki loki "$LOKI_VERSION" "$LOKI_VALUES" --show-only templates/single-binary/statefulset.yaml > "$tmp/loki-sts.yaml"
render loki loki "$LOKI_VERSION" "$LOKI_VALUES" --show-only templates/single-binary/service.yaml > "$tmp/loki-svc.yaml"
extract_block "$tmp/loki-cm.yaml" config.yaml > "$tmp/loki-config.yaml"
[ -s "$tmp/loki-config.yaml" ] || fail "could not extract config.yaml from the rendered Loki ConfigMap"

# 단일 바이너리: StatefulSet 하나, replicas 1, 모든 모듈을 한 프로세스로
[ "$(grep -c '^kind: StatefulSet$' "$tmp/loki.yaml")" = "1" ] || fail "loki: expected exactly one StatefulSet"
grep -qE '^  replicas: 1$' "$tmp/loki-sts.yaml" || fail "loki: StatefulSet replicas is not 1"
grep -q -- '-target=all' "$tmp/loki-sts.yaml" || fail "loki: not SingleBinary (-target=all missing)"
# 단일 노드에 필요 없는 구성요소가 없어야 한다(게이트웨이·캐시·카나리·시험은 Deployment/DaemonSet/Pod 로 나온다)
for kind in Deployment DaemonSet Job CronJob; do
    if grep -q "^kind: $kind\$" "$tmp/loki.yaml"; then fail "loki: unexpected $kind (gateway, cache, canary or test not disabled?)"; fi
done
if grep -qE 'memcached|loki-canary|loki-gateway' "$tmp/loki.yaml"; then fail "loki: memcached, canary or gateway rendered"; fi
if grep -q '^kind: \(ClusterRole\|ClusterRoleBinding\)$' "$tmp/loki.yaml"; then fail "loki: cluster-wide RBAC rendered (rules sidecar not disabled?)"; fi

# 보존 72시간: limits_config 의 기간과 compactor 의 삭제 켬이 둘 다 있어야 실제로 지운다
grep -qE '^[[:space:]]+retention_period: 72h$' "$tmp/loki-config.yaml" || fail "loki: retention_period is not 72h"
grep -qE '^[[:space:]]+retention_enabled: true$' "$tmp/loki-config.yaml" || fail "loki: compactor retention_enabled is not true (nothing would be deleted)"
grep -qE '^[[:space:]]+delete_request_store: filesystem$' "$tmp/loki-config.yaml" || fail "loki: compactor delete_request_store missing (required when retention is on)"
# 인증 끔, 복제 1, 파일시스템 + tsdb
grep -qE '^auth_enabled: false$' "$tmp/loki-config.yaml" || fail "loki: auth_enabled is not false"
grep -qE '^[[:space:]]+replication_factor: 1$' "$tmp/loki-config.yaml" || fail "loki: replication_factor is not 1"
grep -qE '^[[:space:]]+store: tsdb$' "$tmp/loki-config.yaml" || fail "loki: schema store is not tsdb"
grep -qE '^[[:space:]]+object_store: filesystem$' "$tmp/loki-config.yaml" || fail "loki: schema object_store is not filesystem"
grep -qE '^[[:space:]]+filesystem:$' "$tmp/loki-config.yaml" || fail "loki: common storage is not filesystem"
if grep -qE '^ +(s3|gcs|azure|swift):' "$tmp/loki-config.yaml"; then fail "loki: object storage config rendered (SingleBinary uses the filesystem)"; fi
# PVC 5Gi, 저장소 클래스 local-path
grep -qE '^ +storage: "5Gi"$' "$tmp/loki-sts.yaml" || fail "loki: PVC size is not 5Gi"
grep -qE '^ +storageClassName: local-path$' "$tmp/loki-sts.yaml" || fail "loki: storageClassName is not local-path"
# 메모리: 요청과 한도가 모두 있어야 한다
grep -qE '^ +memory: 256Mi$' "$tmp/loki-sts.yaml" || fail "loki: memory request 256Mi missing"
grep -qE '^ +memory: 768Mi$' "$tmp/loki-sts.yaml" || fail "loki: memory limit 768Mi missing"
# 서비스: ClusterIP, 이름과 포트는 아래에서 Alloy·데이터소스와 맞춘다
grep -qE '^  type: ClusterIP$' "$tmp/loki-svc.yaml" || fail "loki: Service is not ClusterIP"
LOKI_SVC="$(awk '/^metadata:/ {m=1; next} m && /^  name: / {print $2; exit}' "$tmp/loki-svc.yaml")"
LOKI_PORT="$(awk '/^    - name: http-metrics$/ {f=1; next} f && /^      port: / {print $2; exit}' "$tmp/loki-svc.yaml")"
[ -n "$LOKI_SVC" ] && [ -n "$LOKI_PORT" ] || fail "loki: could not read the Service name and http-metrics port from the render"
echo "   service: $LOKI_SVC.$NS.svc:$LOKI_PORT"
LOKI_PUSH="http://$LOKI_SVC.$NS.svc:$LOKI_PORT/loki/api/v1/push"
LOKI_BASE="http://$LOKI_SVC.$NS.svc:$LOKI_PORT"

# ---- Alloy --------------------------------------------------------------------------------------
echo "== alloy: grafana/alloy $ALLOY_VERSION"
render alloy alloy "$ALLOY_VERSION" "$ALLOY_VALUES" > "$tmp/alloy.yaml"
render alloy alloy "$ALLOY_VERSION" "$ALLOY_VALUES" --show-only templates/configmap.yaml > "$tmp/alloy-cm.yaml"
render alloy alloy "$ALLOY_VERSION" "$ALLOY_VALUES" --show-only templates/controllers/daemonset.yaml > "$tmp/alloy-ds.yaml"
render alloy alloy "$ALLOY_VERSION" "$ALLOY_VALUES" --show-only templates/rbac.yaml > "$tmp/alloy-rbac.yaml"
extract_block "$tmp/alloy-cm.yaml" config.alloy > "$tmp/config.alloy"
[ -s "$tmp/config.alloy" ] || fail "could not extract config.alloy from the rendered Alloy ConfigMap"

[ "$(grep -c '^kind: DaemonSet$' "$tmp/alloy.yaml")" = "1" ] || fail "alloy: expected exactly one DaemonSet"
for kind in Deployment StatefulSet Service ClusterRole ClusterRoleBinding CustomResourceDefinition; do
    if grep -q "^kind: $kind\$" "$tmp/alloy.yaml"; then fail "alloy: unexpected $kind (service, cluster-wide RBAC or CRD must stay off)"; fi
done
# 권한: margin-watch 네임스페이스의 Role 하나뿐이다
[ "$(grep -c '^kind: Role$' "$tmp/alloy-rbac.yaml")" = "1" ] || fail "alloy: expected exactly one Role"
grep -qE "^  namespace: $APP_NS\$" "$tmp/alloy-rbac.yaml" || fail "alloy: Role is not in namespace $APP_NS"
if grep -q 'secrets' "$tmp/alloy-rbac.yaml"; then fail "alloy: Role can read secrets"; fi
# 설정: 네임스페이스는 margin-watch 하나뿐이다(주석을 뺀 줄에서 본다)
grep -v '^[[:space:]]*//' "$tmp/config.alloy" > "$tmp/config.alloy.nocomment"
names="$(grep -E '^\s*names\s*=' "$tmp/config.alloy.nocomment" || true)"
[ "$(printf '%s\n' "$names" | grep -c .)" = "1" ] || fail "alloy: expected exactly one 'names =' namespace list in the config"
printf '%s\n' "$names" | grep -qE "names[[:space:]]*=[[:space:]]*\[\"$APP_NS\"\]" || fail "alloy: namespace list is not exactly [\"$APP_NS\"]"
grep -qE "regex[[:space:]]*=[[:space:]]*\"$APP_NS\"" "$tmp/config.alloy.nocomment" || fail "alloy: namespace keep rule for $APP_NS missing"
if grep -qE 'kube-system|"monitoring"|names[[:space:]]*=[[:space:]]*\[\]' "$tmp/config.alloy.nocomment"; then fail "alloy: config mentions another namespace"; fi
# 라벨 4종과 수집·전송 블록
for label in namespace pod container app; do
    grep -qE "target_label[[:space:]]*=[[:space:]]*\"$label\"" "$tmp/config.alloy.nocomment" || fail "alloy: label '$label' is not set"
done
grep -q '^loki.source.kubernetes "' "$tmp/config.alloy.nocomment" || fail "alloy: loki.source.kubernetes block missing"
grep -q '^loki.write "' "$tmp/config.alloy.nocomment" || fail "alloy: loki.write block missing"
# 메모리 한도 128~256Mi: alloy 컨테이너의 첫 limits 메모리
limit="$(awk '/^ +limits:$/ {f=1; next} f && /memory:/ {gsub(/[^0-9]/, "", $2); print $2; exit}' "$tmp/alloy-ds.yaml")"
[ -n "$limit" ] || fail "alloy: memory limit not found"
{ [ "$limit" -ge 128 ] && [ "$limit" -le 256 ]; } || fail "alloy: memory limit ${limit}Mi is outside 128~256Mi"

# ---- 연결: 이름·포트 --------------------------------------------------------------------------
echo "== wiring: alloy -> loki, grafana -> loki"
alloy_url="$(sed -nE 's/^[[:space:]]*url[[:space:]]*=[[:space:]]*"([^"]*)".*/\1/p' "$tmp/config.alloy.nocomment")"
[ "$(printf '%s\n' "$alloy_url" | grep -c .)" = "1" ] || fail "alloy: expected exactly one url in the config"
[ "$alloy_url" = "$LOKI_PUSH" ] || fail "alloy pushes to '$alloy_url' but the Loki Service is '$LOKI_PUSH'"
ds_url="$(grep -E '^[[:space:]]+url:' "$DATASOURCE" | head -1 | sed -E 's/^[[:space:]]+url:[[:space:]]*//')"
[ "$ds_url" = "$LOKI_BASE" ] || fail "datasource url '$ds_url' but the Loki Service is '$LOKI_BASE'"
grep -qE '^[[:space:]]+type: loki$' "$DATASOURCE" || fail "datasource type is not loki"
grep -qE '^[[:space:]]+- name: Loki$' "$DATASOURCE" || fail "datasource name is not Loki"
grep -qE "^  namespace: $NS\$" "$DATASOURCE" || fail "datasource ConfigMap is not in namespace $NS (the Grafana sidecar only watches its own namespace)"
grep -qE '^    grafana_datasource: "1"$' "$DATASOURCE" || fail "datasource ConfigMap label grafana_datasource: \"1\" missing"
# 스택 쪽: sidecar 가 같은 라벨을 보는지(스택 values 가 datasources 라벨을 따로 정하지 않으면 차트 기본값을 쓴다)
if grep -qE '^[[:space:]]+datasources:' "$STACK_VALUES" && sed -n '/^[[:space:]]*datasources:/,/^[[:space:]]*[a-z]*:$/p' "$STACK_VALUES" | grep -qE '^[[:space:]]+label(Value)?:'; then
    fail "$STACK_VALUES sets a custom datasource sidecar label; update $DATASOURCE to match"
fi
if helm repo list 2>/dev/null | grep -q '^prometheus-community[[:space:]]'; then
    stack_ver="$(grep -E '^# 차트: .* [0-9]+\.[0-9]+\.[0-9]+ ' "$STACK_VALUES" | head -1 | sed -E 's/.* ([0-9]+\.[0-9]+\.[0-9]+) .*/\1/')"
    if helm template kube-prometheus-stack prometheus-community/kube-prometheus-stack --version "$stack_ver" -n "$NS" -f "$STACK_VALUES" > "$tmp/stack.yaml" 2>/dev/null; then
        grep -qE '^ +value: "grafana_datasource"$' "$tmp/stack.yaml" || fail "stack Grafana sidecar does not look for label grafana_datasource"
        grep -qE '^ +value: "1"$' "$tmp/stack.yaml" || fail "stack Grafana sidecar label value is not \"1\""
        echo "   stack sidecar label matches (kube-prometheus-stack $stack_ver)"
    else
        unverified "could not render kube-prometheus-stack $stack_ver; the datasource sidecar label was not compared with the real chart"
    fi
else
    unverified "helm repo 'prometheus-community' is not added; the datasource sidecar label was not compared with the real chart"
fi

# ---- 비밀 ---------------------------------------------------------------------------------------
echo "== no secrets (absolute rule 2)"
for f in "$LOKI_VALUES" "$ALLOY_VALUES" "$DATASOURCE"; do
    if grep -nEi '^[[:space:]-]*(adminUser|adminPassword|password|passwordHash|secret|secretKey|secretAccessKey|accessKey|accessKeyId|token|apikey|api_key|basicAuth|basic_auth|bearer_token|bearer_token_file|auth_password|username|tenant_id)[[:space:]]*:' "$f"; then
        fail "$f has a secret-like key"
    fi
    if grep -nEi 'xox[a-z]-|BEGIN [A-Z ]*PRIVATE KEY|hooks\.slack\.com|AKIA[0-9A-Z]{12}' "$f"; then fail "$f contains a token, webhook or key"; fi
done
# Alloy 설정(렌더링 결과)에도 인증 블록이 없어야 한다
if grep -nEi 'basic_auth|bearer_token|authorization|password|tenant_id' "$tmp/config.alloy.nocomment"; then fail "alloy config has an auth setting (Loki runs without auth, inside the cluster only)"; fi
# 값 파일 어디에도 NodePort·LoadBalancer·Ingress 로 바깥에 여는 설정이 없어야 한다(주석 제외)
if grep -vE '^[[:space:]]*#' "$LOKI_VALUES" "$ALLOY_VALUES" | grep -qE 'type:[[:space:]]*(NodePort|LoadBalancer)|ingress:[[:space:]]*$|enabled:[[:space:]]*true.*ingress'; then fail "values open a service to the outside"; fi

# ---- 설정 문법(Docker) --------------------------------------------------------------------------
echo "== config syntax (docker)"
docker_ready=1
if ! command -v docker >/dev/null 2>&1; then
    unverified "'docker' is not in PATH; Loki and Alloy config syntax were not checked"
    docker_ready=0
elif ! docker info >/dev/null 2>&1; then
    unverified "Docker is not running; Loki and Alloy config syntax were not checked"
    docker_ready=0
fi
if [ "$docker_ready" = 1 ]; then
    pull_or_skip() {   # pull_or_skip <이미지> : 없으면 받는다. 못 받으면 1
        docker image inspect "$1" >/dev/null 2>&1 || docker pull "$1" >/dev/null 2>&1
    }
    loki_image="$(grep -E '^ +image: ' "$tmp/loki-sts.yaml" | head -1 | sed -E 's/^ +image: //; s/"//g')"
    alloy_image="$(grep -E '^ +image: ' "$tmp/alloy-ds.yaml" | head -1 | sed -E 's/^ +image: //; s/"//g')"
    if pull_or_skip "$loki_image"; then
        cp "$tmp/loki-config.yaml" "$tmp/loki-config.verify.yaml"
        MSYS_NO_PATHCONV=1 docker run --rm -v "$tmp_win:/c" "$loki_image" -config.file=/c/loki-config.verify.yaml -verify-config >"$tmp/loki-verify.out" 2>&1 \
            || { cat "$tmp/loki-verify.out" >&2; fail "Loki rejected the rendered config ($loki_image -verify-config)"; }
        echo "   loki config is valid ($loki_image)"
    else
        unverified "could not pull $loki_image; Loki config syntax was not checked"
    fi
    if pull_or_skip "$alloy_image"; then
        MSYS_NO_PATHCONV=1 docker run --rm -v "$tmp_win:/c" "$alloy_image" validate /c/config.alloy >"$tmp/alloy-validate.out" 2>&1 \
            || { cat "$tmp/alloy-validate.out" >&2; fail "Alloy rejected the rendered config ($alloy_image validate)"; }
        echo "   alloy config is valid ($alloy_image)"
    else
        unverified "could not pull $alloy_image; Alloy config syntax was not checked"
    fi
fi

echo "OK: logging checks passed"
