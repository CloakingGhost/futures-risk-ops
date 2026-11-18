#!/usr/bin/env bash
# 로컬 k3d 클러스터를 지운다. Git Bash 에서 실행한다. 이름이 margin-watch 인 클러스터만 지운다.
# 클러스터 안의 데이터(PVC 포함)도 함께 사라진다. 다시 만들 때는 up.sh.
set -euo pipefail

NAME=margin-watch

command -v k3d >/dev/null 2>&1 || { echo "ERROR: 'k3d' is not in PATH (see docs/ops-log/2026-11-16-k8s-tools-install.md)" >&2; exit 1; }

if k3d cluster list "$NAME" >/dev/null 2>&1; then
    k3d cluster delete "$NAME"
else
    echo "cluster '$NAME' does not exist (nothing to delete)"
fi
