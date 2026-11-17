#!/usr/bin/env bash
# 로컬 k3d 클러스터를 만든다. Git Bash 에서 실행한다.
#
#   ./deploy/k3d/up.sh             클러스터를 만들고 노드가 Ready 가 될 때까지 기다린다(이미 있으면 그대로 둔다)
#   ./deploy/k3d/up.sh --import    위에 더해 collector·risk 이미지를 클러스터에 넣는다(`k3d image import`)
#
# 클러스터에 앱을 설치하지는 않는다(Day 8). 지울 때는 down.sh.
set -euo pipefail

NAME=margin-watch
case "${1:-}" in
    "" | --import) ;;
    *)
        echo "usage: $0 [--import]" >&2
        exit 2
        ;;
esac
DIR="$(cd "$(dirname "$0")" && pwd)"
IMAGES=(futures-risk-ops-collector:local futures-risk-ops-risk:local)

need() {
    command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' is not in PATH (see docs/ops-log/2026-11-16-k8s-tools-install.md)" >&2; exit 1; }
}
need docker
need k3d
need kubectl

docker info >/dev/null 2>&1 || { echo "ERROR: docker is not running" >&2; exit 1; }

if k3d cluster list "$NAME" >/dev/null 2>&1; then
    echo "cluster '$NAME' already exists (not recreated)"
    # 정지돼 있으면(k3d cluster stop 뒤 등) 시작한다. 이미 돌고 있으면 아무 일도 하지 않는다
    k3d cluster start "$NAME"
else
    echo "creating cluster '$NAME' from $DIR/cluster.yaml"
    k3d cluster create --config "$DIR/cluster.yaml"
fi

kubectl --context "k3d-$NAME" wait --for=condition=Ready node --all --timeout=180s

if [ "${1:-}" = "--import" ]; then
    for image in "${IMAGES[@]}"; do
        if docker image inspect "$image" >/dev/null 2>&1; then
            echo "importing $image"
            k3d image import "$image" --cluster "$NAME"
        else
            echo "WARN: image $image is not built (docker compose --profile app build); skipped" >&2
        fi
    done
fi

kubectl --context "k3d-$NAME" get nodes
