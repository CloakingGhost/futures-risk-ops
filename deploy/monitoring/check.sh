#!/usr/bin/env bash
# 모니터링 설정 검사(Day 9). 클러스터를 건드리지 않는다: helm lint·template, promtool(Docker), grep 만 쓴다.
# deploy/helm/check.sh 는 건드리지 않고 별도로 둔 파일이다(둘 다 CI 가 부른다).
#
#   ./deploy/monitoring/check.sh
#   CHECK_STACK=1 ./deploy/monitoring/check.sh    # 스택 values 를 실제 차트로 렌더링도 해 본다(차트를 내려받으므로 네트워크 필요)
#
# 확인하는 것:
#   규칙      promtool check rules, promtool test rules(합성 시계열 단위 시험), 규칙 차트 helm lint·template(알람 8개)
#   수집      collector·risk 차트의 ServiceMonitor: 기본값에서는 안 나오고, 켜고 CRD 가 있을 때만 나온다(포트·경로 확인)
#   비밀      스택 values 에 비밀번호·웹훅 같은 값이 없다(Grafana 는 Secret 이름만)
#   Slack     create-slack-secret.sh 시험(가짜 kubectl, 합성값): 값이 출력·인자에 안 나오고, 형식이 틀리면 실패, 임시 파일이 안 남는다
#   기존      deploy/helm/check.sh 가 그대로 통과한다
# Docker 가 없거나 꺼져 있으면 이유를 알리고 실패한다(promtool 을 Docker 로 돌리므로).
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT="$(pwd -W 2>/dev/null || pwd)"   # Git Bash 에서는 Windows 형식 경로(C:/...). docker.exe 는 /c/... 를 읽지 못한다
HELM=deploy/helm
RULES="$HELM/margin-watch-rules/files/margin-watch.rules.yaml"
VALUES=deploy/monitoring/kube-prometheus-stack-values.yaml
CALENDAR=config/session-calendar.yaml
PROMTOOL_IMAGE=prom/prometheus:v3.0.1
EXPECTED_ALERTS="CollectorNoTradeInSession CollectorIngestLatencyP95High CollectorReconnectsFrequent CollectorMissingVolume CollectorDailyCloseStale NodeDiskUsageHigh DbConnectionsHigh PodRestartingRepeatedly"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

command -v helm >/dev/null 2>&1 || fail "'helm' is not in PATH (see docs/ops-log/2026-11-16-k8s-tools-install.md)"
command -v docker >/dev/null 2>&1 || fail "'docker' is not in PATH: promtool runs in a Docker container ($PROMTOOL_IMAGE)"
docker info >/dev/null 2>&1 || fail "Docker is not running: start Docker Desktop (promtool runs in a container)"
docker image inspect "$PROMTOOL_IMAGE" >/dev/null 2>&1 || echo "note: $PROMTOOL_IMAGE is not pulled yet; docker will pull it now"
[ -f "$RULES" ] || fail "$RULES not found"
[ -f "$VALUES" ] || fail "$VALUES not found"
[ -f "$CALENDAR" ] || fail "$CALENDAR not found"

promtool() {
    # MSYS_NO_PATHCONV: Git Bash 가 -w 같은 경로 인자를 멋대로 바꾸지 못하게 한다
    MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT:/work" -w /work --entrypoint promtool "$PROMTOOL_IMAGE" "$@"
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "== create-slack-secret.sh test (fake kubectl, synthetic values)"
bash deploy/monitoring/test-create-slack-secret.sh

echo "== promtool check rules"
promtool check rules "$RULES"

echo "== promtool test rules (synthetic series)"
# 시험 파일의 rule_files 가 상대 경로라서 시험 폴더에서 돌린다
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT:/work" -w /work/deploy/monitoring/tests --entrypoint promtool "$PROMTOOL_IMAGE" \
    test rules margin-watch.rules.test.yaml

echo "== rules chart: lint, template"
helm lint "$HELM/margin-watch-rules"
helm template margin-watch-rules "$HELM/margin-watch-rules" --api-versions monitoring.coreos.com/v1 > "$tmp/rules.yaml"
grep -q '^kind: PrometheusRule$' "$tmp/rules.yaml" || fail "rules chart did not render a PrometheusRule"
for name in $EXPECTED_ALERTS; do
    grep -q "alert: $name\$" "$tmp/rules.yaml" || fail "alert $name is missing from the rendered PrometheusRule"
done
count="$(grep -c '^    - alert: ' "$tmp/rules.yaml")"
[ "$count" = "8" ] || fail "expected 8 alerts, rendered $count"
# CRD 가 없는 클러스터에서는 아무것도 만들지 않는다
helm template margin-watch-rules "$HELM/margin-watch-rules" > "$tmp/rules-nocrd.yaml"
if grep -q '^kind: PrometheusRule$' "$tmp/rules-nocrd.yaml"; then fail "rules chart rendered a PrometheusRule without the CRD"; fi
# 토큰 만료 알람은 만들지 않는다(사용자 결정)
if grep -v '^ *#' "$RULES" | grep -q 'collector_token_expiry'; then fail "token expiry alert must not exist (decision 2026-11-19)"; fi

echo "== ServiceMonitor: collector, risk"
for app in collector risk; do
    set_calendar=()
    [ "$app" = collector ] && set_calendar=(--set-file "sessionCalendar=$CALENDAR")
    helm lint "$HELM/$app" "${set_calendar[@]}" --set monitoring.serviceMonitor.enabled=true
    # 기본값: 안 나온다(CRD 가 있어도)
    helm template "$app" "$HELM/$app" "${set_calendar[@]}" --api-versions monitoring.coreos.com/v1 > "$tmp/$app-default.yaml"
    if grep -q 'kind: ServiceMonitor' "$tmp/$app-default.yaml"; then fail "$app rendered a ServiceMonitor by default"; fi
    # 켜도 CRD 가 없으면 안 나온다(렌더링이 깨지지 않는다)
    helm template "$app" "$HELM/$app" "${set_calendar[@]}" --set monitoring.serviceMonitor.enabled=true > "$tmp/$app-nocrd.yaml"
    if grep -q 'kind: ServiceMonitor' "$tmp/$app-nocrd.yaml"; then fail "$app rendered a ServiceMonitor without the CRD"; fi
    # 켜고 CRD 가 있으면 나온다
    helm template "$app" "$HELM/$app" "${set_calendar[@]}" --set monitoring.serviceMonitor.enabled=true --set monitoring.serviceMonitor.interval=15s \
        --api-versions monitoring.coreos.com/v1 --show-only templates/servicemonitor.yaml > "$tmp/$app-sm.yaml"
    grep -q '^kind: ServiceMonitor$' "$tmp/$app-sm.yaml" || fail "$app did not render a ServiceMonitor when enabled"
    grep -q 'path: /actuator/prometheus' "$tmp/$app-sm.yaml" || fail "$app ServiceMonitor path is not /actuator/prometheus"
    grep -q 'interval: 15s' "$tmp/$app-sm.yaml" || fail "$app ServiceMonitor interval value is not used"
    # 값 검증: 이상한 interval, 모르는 키는 거절한다
    if helm template "$app" "$HELM/$app" "${set_calendar[@]}" --set monitoring.serviceMonitor.interval=soon >/dev/null 2>&1; then fail "$app accepted an invalid interval"; fi
    if helm template "$app" "$HELM/$app" "${set_calendar[@]}" --set monitoring.serviceMonitor.bogus=1 >/dev/null 2>&1; then fail "$app accepted an unknown monitoring key"; fi
    # Service 의 포트 이름이 ServiceMonitor 의 port 와 같아야 한다
    port="$(grep -E '^ +- port: ' "$tmp/$app-sm.yaml" | head -1 | sed -E 's/^ +- port: //')"
    helm template "$app" "$HELM/$app" "${set_calendar[@]}" --show-only templates/service.yaml > "$tmp/$app-svc.yaml"
    grep -q "name: $port\$" "$tmp/$app-svc.yaml" || fail "$app Service has no port named '$port'"
done
grep -q 'port: management' "$tmp/collector-sm.yaml" || fail "collector ServiceMonitor port is not 'management'"
grep -q 'port: http' "$tmp/risk-sm.yaml" || fail "risk ServiceMonitor port is not 'http'"
# 켜도 collector Deployment 는 그대로다(replicas 1, Recreate 는 deploy/helm/check.sh 가 따로 확인한다)
helm template collector "$HELM/collector" --set-file "sessionCalendar=$CALENDAR" --show-only templates/deployment.yaml > "$tmp/c-off.yaml"
helm template collector "$HELM/collector" --set-file "sessionCalendar=$CALENDAR" --set monitoring.serviceMonitor.enabled=true \
    --api-versions monitoring.coreos.com/v1 --show-only templates/deployment.yaml > "$tmp/c-on.yaml"
diff -B "$tmp/c-off.yaml" "$tmp/c-on.yaml" >/dev/null || fail "enabling the ServiceMonitor changed the collector Deployment"

echo "== stack values: no secrets (absolute rule 2)"
# 비밀번호·계정·웹훅 값을 쓰는 키가 없어야 한다. passwordKey 같은 '키 이름'은 허용(Secret 안의 키 이름일 뿐이다)
if grep -nEi '^[[:space:]]*(adminUser|adminPassword|password|secret|token|apikey|api_key|api_url|slack_api_url|webhook[a-z_]*|bearer_token|auth_password)[[:space:]]*:' "$VALUES"; then
    fail "$VALUES has a secret-like key (use an existing Secret name only)"
fi
if grep -nEi 'hooks\.slack\.com|xox[a-z]-|BEGIN [A-Z ]*PRIVATE KEY' "$VALUES"; then fail "$VALUES contains a webhook, token or key"; fi
grep -qE '^[[:space:]]+existingSecret: grafana-admin$' "$VALUES" || fail "Grafana must read the admin account from the 'grafana-admin' Secret"
# 주석이 아닌 줄에 'password' 가 들어간 곳은 passwordKey(키 이름)뿐이어야 한다
if grep -vE '^[[:space:]]*#' "$VALUES" | grep -i 'password' | grep -vqE '^[[:space:]]+passwordKey: admin-password$'; then
    grep -vE '^[[:space:]]*#' "$VALUES" | grep -in 'password'
    fail "$VALUES mentions 'password' outside passwordKey"
fi

if [ "${CHECK_STACK:-}" = "1" ]; then
    echo "== stack values render with the real chart (CHECK_STACK=1)"
    version="$(grep -E '^# 차트: .* [0-9]+\.[0-9]+\.[0-9]+ ' "$VALUES" | sed -E 's/.* ([0-9]+\.[0-9]+\.[0-9]+) .*/\1/')"
    [ -n "$version" ] || fail "chart version line not found in $VALUES"
    helm repo list 2>/dev/null | grep -q '^prometheus-community' || fail "helm repo 'prometheus-community' is not added"
    helm template kube-prometheus-stack prometheus-community/kube-prometheus-stack --version "$version" -n monitoring -f "$VALUES" > "$tmp/stack.yaml"
    grep -q 'name: grafana-admin' "$tmp/stack.yaml" || fail "rendered Grafana does not use the grafana-admin Secret"
    if grep -q 'prom-operator' "$tmp/stack.yaml"; then fail "the chart's default Grafana password leaked into the render"; fi
fi

echo "== existing chart checks (deploy/helm/check.sh)"
bash "$HELM/check.sh"

echo "OK: monitoring checks passed"
