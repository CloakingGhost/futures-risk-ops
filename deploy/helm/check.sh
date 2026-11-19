#!/usr/bin/env bash
# Helm 차트 검사. helm lint, helm template 을 돌리고 렌더링 결과가 약속대로인지 확인한다.
# Day 11 CI 가 이 파일을 그대로 부른다. 하나라도 어긋나면 0 이 아닌 값으로 끝난다.
#
#   ./deploy/helm/check.sh
#
# 확인하는 것:
#   collector  replicas 1, strategy Recreate, HPA 없음, values 로 바꿀 수 없음(replicas 등을 넘기면 거절),
#              probe(liveness·readiness, 8081), 리소스(256Mi/512Mi), MaxRAMPercentage=75, 키·DB 계정은 secretKeyRef 만, 캘린더는 ConfigMap
#   risk       probe(8082), collector 와 같은 리소스, DB 계정은 secretKeyRef 만
set -euo pipefail

cd "$(dirname "$0")"
CALENDAR=../../config/session-calendar.yaml

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

command -v helm >/dev/null 2>&1 || fail "'helm' is not in PATH (see docs/ops-log/2026-11-16-k8s-tools-install.md)"
[ -f "$CALENDAR" ] || fail "$CALENDAR not found"

echo "== create-secrets.sh test (fake kubectl, synthetic values)"
bash ../k3d/test-create-secrets.sh

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "== helm lint"
helm lint collector --set-file sessionCalendar="$CALENDAR"
helm lint risk

echo "== helm template"
helm template collector collector --set-file sessionCalendar="$CALENDAR" > "$tmp/collector.yaml"
helm template risk risk > "$tmp/risk.yaml"
helm template collector collector --set-file sessionCalendar="$CALENDAR" --show-only templates/deployment.yaml > "$tmp/collector-deployment.yaml"
helm template risk risk --show-only templates/deployment.yaml > "$tmp/risk-deployment.yaml"

echo "== collector: one at a time"
grep -qx '  replicas: 1' "$tmp/collector-deployment.yaml" || fail "collector Deployment is not 'replicas: 1'"
grep -qx '    type: Recreate' "$tmp/collector-deployment.yaml" || fail "collector Deployment strategy is not Recreate"
if grep -q 'HorizontalPodAutoscaler' "$tmp/collector.yaml"; then fail "collector must not have a HorizontalPodAutoscaler"; fi
# values 로 바꿀 수 없다: 아래를 넘기면 values.schema.json 이 거절해야 한다
for opt in replicas=3 replicaCount=3 autoscaling.enabled=true strategy.type=RollingUpdate; do
    if helm template collector collector --set-file sessionCalendar="$CALENDAR" --set "$opt" >/dev/null 2>&1; then
        fail "collector accepted --set $opt (it must stay at 1 replica with Recreate)"
    fi
done
# 캘린더 없이는 설치되지 않는다
if helm template collector collector >/dev/null 2>&1; then fail "collector rendered without sessionCalendar"; fi

echo "== collector: probes, resources, env"
grep -q 'containerPort: 8081' "$tmp/collector-deployment.yaml" || fail "collector port is not 8081"
grep -q 'path: /actuator/health/liveness' "$tmp/collector-deployment.yaml" || fail "collector liveness path missing"
grep -q 'path: /actuator/health/readiness' "$tmp/collector-deployment.yaml" || fail "collector readiness path missing"
grep -q 'memory: 256Mi' "$tmp/collector-deployment.yaml" || fail "collector requests.memory is not 256Mi"
grep -q 'memory: 512Mi' "$tmp/collector-deployment.yaml" || fail "collector limits.memory is not 512Mi"
grep -q 'XX:MaxRAMPercentage=75' "$tmp/collector-deployment.yaml" || fail "collector MaxRAMPercentage=75 missing"
grep -q 'kind: ConfigMap' "$tmp/collector.yaml" || fail "collector session calendar ConfigMap missing"
grep -q 'name: collector-session-calendar' "$tmp/collector-deployment.yaml" || fail "collector does not mount the session calendar ConfigMap"

echo "== risk: probes, resources"
grep -q 'containerPort: 8082' "$tmp/risk-deployment.yaml" || fail "risk port is not 8082"
grep -q 'path: /actuator/health/liveness' "$tmp/risk-deployment.yaml" || fail "risk liveness path missing"
grep -q 'path: /actuator/health/readiness' "$tmp/risk-deployment.yaml" || fail "risk readiness path missing"
grep -q 'memory: 256Mi' "$tmp/risk-deployment.yaml" || fail "risk requests.memory is not 256Mi"
grep -q 'memory: 512Mi' "$tmp/risk-deployment.yaml" || fail "risk limits.memory is not 512Mi"
grep -q 'XX:MaxRAMPercentage=75' "$tmp/risk-deployment.yaml" || fail "risk MaxRAMPercentage=75 missing"

echo "== secrets are only referenced, never written (absolute rule 2)"
# 키·DB 계정 환경변수 바로 다음 줄이 'value:' 이면 값을 직접 쓴 것이다. 'valueFrom:'(secretKeyRef)여야 한다
for file in collector-deployment.yaml risk-deployment.yaml; do
    if grep -A1 -E '^ +- name: (POSTGRES_USER|POSTGRES_PASSWORD|KIS_APP_KEY|KIS_APP_SECRET|KIS_PAPER_APP_KEY|KIS_PAPER_APP_SECRET)$' "$tmp/$file" | grep -qE '^ +value:'; then
        fail "$file writes a credential as a plain value (use secretKeyRef)"
    fi
done
grep -q 'secretKeyRef' "$tmp/collector-deployment.yaml" || fail "collector has no secretKeyRef"
grep -q 'secretKeyRef' "$tmp/risk-deployment.yaml" || fail "risk has no secretKeyRef"
# 모의투자 프로필은 모의투자 키 이름을, 실전은 실전 키 이름만 읽는다
helm template collector collector --set-file sessionCalendar="$CALENDAR" --set profile=kis-paper --show-only templates/deployment.yaml > "$tmp/paper.yaml"
grep -q 'key: KIS_PAPER_APP_KEY' "$tmp/paper.yaml" || fail "kis-paper profile does not read KIS_PAPER_APP_KEY"
if grep -qE 'key: KIS_APP_(KEY|SECRET)' "$tmp/paper.yaml"; then fail "kis-paper profile reads the real KIS_APP_* keys"; fi
if grep -qE 'key: KIS_PAPER_APP_(KEY|SECRET)' "$tmp/collector-deployment.yaml"; then fail "kis profile reads the paper keys"; fi

echo "OK: helm lint and template checks passed"
