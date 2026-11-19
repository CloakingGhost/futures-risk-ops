#!/usr/bin/env bash
# create-secrets.sh 테스트. 단독으로 실행한다(통과하면 0, 하나라도 어긋나면 0 이 아닌 값).
#
#   ./deploy/k3d/test-create-secrets.sh
#
# 진짜 kubectl 은 부르지 않는다. 임시 폴더에 가짜 kubectl 을 만들어 PATH 맨 앞에 둔다. 가짜 kubectl 은
#   - 받은 인자를 calls.log 에 남기고
#   - `create secret generic NAME --from-env-file=F` 의 F 내용을 env.NAME 으로 복사하고(이게 "넘어간 값"이다)
#   - `get secret` 은 환경변수 FAKE_POSTGRES_EXISTS 가 있으면 존재하는 것처럼 답한다.
# 아래 값은 전부 **합성값**이다(실제 키·비밀번호가 아니다). .env 는 CRLF·따옴표·= 를 일부러 섞어 만든다.
# 스크립트를 일부러 틀리게 고친 복사본으로 이 테스트를 돌리려면 CREATE_SECRETS=그 경로 로 지정한다.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="${CREATE_SECRETS:-$DIR/create-secrets.sh}"

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/tmp"

# 합성값 (SYNTHETIC: 실제 값 아님). 따옴표·=·공백·# 를 섞었다.
V_KIS_KEY='synthetic-key-AAA=='
V_KIS_SECRET='synthetic-secret+/BBB='
V_PAPER_KEY='synthetic-paper-key-CCC'
V_PAPER_SECRET='synthetic-paper-secret=DDD'
V_PG_USER='synthetic_user'
V_PG_PASS='synthetic pass=with#chars EEE'
V_SLACK='synthetic-slack-FFF'
ALL_VALUES=("$V_KIS_KEY" "$V_KIS_SECRET" "$V_PAPER_KEY" "$V_PAPER_SECRET" "$V_PG_USER" "$V_PG_PASS" "$V_SLACK")

cat > "$T/bin/kubectl" <<'FAKE'
#!/usr/bin/env bash
# 테스트용 가짜 kubectl (진짜 클러스터에 아무것도 보내지 않는다)
d="${FAKE_DIR:?}"
printf '%s\n' "$*" >> "$d/calls.log"
case " $* " in
    *" get secret "*)
        [ -n "${FAKE_POSTGRES_EXISTS:-}" ] && echo "secret/postgres-credentials"
        exit 0
        ;;
    *" create secret generic "*)
        [ -z "${FAKE_FAIL_CREATE:-}" ] || { echo "fake kubectl: create failed" >&2; exit 1; }
        name=""; file=""; prev=""
        for a in "$@"; do
            [ "$prev" = generic ] && name="$a"
            case "$a" in --from-env-file=*) file="${a#*=}" ;; esac
            prev="$a"
        done
        cp "$file" "$d/env.$name"
        echo "fake-yaml:$name"
        ;;
    *" create namespace "*)
        echo "fake-yaml:namespace"
        ;;
    *" apply "*)
        cat >> "$d/stdin.log"
        echo "applied (fake)"
        ;;
esac
FAKE
chmod +x "$T/bin/kubectl"

FAILED=0
CASE=0
fail() {
    echo "  FAIL: $*" >&2
    FAILED=$((FAILED + 1))
}
ok() { echo "  ok: $*"; }
check() { # check "설명" 명령... : 명령이 성공이면 통과
    local msg="$1"
    shift
    if "$@"; then ok "$msg"; else fail "$msg"; fi
}
check_not() { # 명령이 실패해야 통과
    local msg="$1"
    shift
    if "$@"; then fail "$msg"; else ok "$msg"; fi
}

# 새 시나리오: 가짜 kubectl 의 기록 폴더와 임시 폴더를 비운다
new_case() {
    CASE=$((CASE + 1))
    echo "== case $CASE: $1"
    export FAKE_DIR="$T/fake$CASE"
    mkdir -p "$FAKE_DIR"
    rm -rf "$T/tmp"
    mkdir -p "$T/tmp"
    unset FAKE_POSTGRES_EXISTS FAKE_FAIL_CREATE || true
    OUT="$FAKE_DIR/out.txt"
    ERR="$FAKE_DIR/err.txt"
}

# 스크립트를 돌린다. 종료 코드는 RC 에, 출력은 OUT/ERR 에 남는다
run() {
    set +e
    PATH="$T/bin:$PATH" TMPDIR="$T/tmp" bash "$SCRIPT" "$@" > "$OUT" 2> "$ERR"
    RC=$?
    set -e
}

# 값이 stdout·stderr·kubectl 인자(calls.log)·YAML 로 쓰인 stdin 어디에도 없는지
no_value_leak() {
    local v f
    for f in "$OUT" "$ERR" "$FAKE_DIR/calls.log"; do
        [ -f "$f" ] || continue
        for v in "${ALL_VALUES[@]}"; do
            if grep -qF -- "$v" "$f"; then return 1; fi
        done
        # 합성값에 공통으로 들어 있는 낱말로도 한 번 더
        if grep -qi 'synthetic' "$f"; then return 1; fi
    done
    return 0
}

no_temp_left() { [ -z "$(ls -A "$T/tmp")" ]; }
no_kubectl_calls() { [ ! -s "$FAKE_DIR/calls.log" ]; }
has_text() { grep -qF -- "$2" "$1"; }

write_env() { # CRLF 로 쓴다: 주석, 빈 줄, 따옴표, export, 값 안의 =, 필요 없는 Slack 키
    local f="$1"
    {
        printf '# synthetic test env (not real)\r\n'
        printf '\r\n'
        printf 'KIS_APP_KEY="%s"\r\n' "$V_KIS_KEY"
        printf "KIS_APP_SECRET='%s'\r\n" "$V_KIS_SECRET"
        printf 'KIS_PAPER_APP_KEY=%s\r\n' "$V_PAPER_KEY"
        printf 'export KIS_PAPER_APP_SECRET=%s\r\n' "$V_PAPER_SECRET"
        printf '  # indented comment\r\n'
        printf 'POSTGRES_USER=%s\r\n' "$V_PG_USER"
        printf 'POSTGRES_PASSWORD="%s"\r\n' "$V_PG_PASS"
        printf 'SLACK_ALERTS_CHANNEL=%s\r\n' "$V_SLACK"
        printf 'SLACK_MEMBER_ID=%s' "$V_SLACK" # 마지막 줄은 줄바꿈 없음
    } > "$f"
}

ENVF="$T/test.env"
write_env "$ENVF"

# ---------------------------------------------------------------- 1
new_case "profile kis: values pass through exactly, nothing else"
run --env-file "$ENVF"
check "exit 0" test "$RC" -eq 0
printf 'KIS_APP_KEY=%s\nKIS_APP_SECRET=%s\n' "$V_KIS_KEY" "$V_KIS_SECRET" > "$FAKE_DIR/expected.kis"
printf 'POSTGRES_USER=%s\nPOSTGRES_PASSWORD=%s\n' "$V_PG_USER" "$V_PG_PASS" > "$FAKE_DIR/expected.db"
check "kis-credentials env file equals input (quotes stripped, = kept, no CR)" cmp -s "$FAKE_DIR/expected.kis" "$FAKE_DIR/env.kis-credentials"
check "postgres-credentials env file equals input" cmp -s "$FAKE_DIR/expected.db" "$FAKE_DIR/env.postgres-credentials"
check_not "no CR in the env files kubectl received" grep -q $'\r' "$FAKE_DIR/env.kis-credentials" "$FAKE_DIR/env.postgres-credentials"
check_not "Slack key is not passed" grep -q SLACK "$FAKE_DIR/env.kis-credentials" "$FAKE_DIR/env.postgres-credentials"
check_not "paper keys are not passed with profile kis" grep -q PAPER "$FAKE_DIR/env.kis-credentials"
check "no value in stdout/stderr/kubectl args" no_value_leak
check "no temp files left" no_temp_left
check "context is fixed to k3d-margin-watch on every call" bash -c "! grep -v -- '--context k3d-margin-watch' '$FAKE_DIR/calls.log' | grep -q ."
check "default namespace is margin-watch" has_text "$FAKE_DIR/calls.log" "-n margin-watch create secret generic kis-credentials"
check "namespace is created idempotently (dry-run | apply)" has_text "$FAKE_DIR/calls.log" "create namespace margin-watch --dry-run=client -o yaml"
check "secrets are piped to apply -f -" bash -c "grep -c 'apply -f -' '$FAKE_DIR/calls.log' | grep -qx 3"
check_not "values are not put on kubectl args (only --from-env-file path)" grep -q -- '--from-literal' "$FAKE_DIR/calls.log"

# ---------------------------------------------------------------- 2
new_case "profile kis-paper and custom namespace"
run --env-file "$ENVF" --profile kis-paper --namespace rehearsal
check "exit 0" test "$RC" -eq 0
printf 'KIS_PAPER_APP_KEY=%s\nKIS_PAPER_APP_SECRET=%s\n' "$V_PAPER_KEY" "$V_PAPER_SECRET" > "$FAKE_DIR/expected.paper"
check "kis-credentials holds only the KIS_PAPER_* keys" cmp -s "$FAKE_DIR/expected.paper" "$FAKE_DIR/env.kis-credentials"
check "namespace argument is used" has_text "$FAKE_DIR/calls.log" "-n rehearsal create secret generic"
check "no value in output" no_value_leak
check "no temp files left" no_temp_left

# ---------------------------------------------------------------- 3
new_case "empty value fails by name, without printing values"
sed 's/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=\r/' "$ENVF" > "$T/empty.env"
run --env-file "$T/empty.env"
check_not "exit is not 0" test "$RC" -eq 0
check "stderr names POSTGRES_PASSWORD" has_text "$ERR" "POSTGRES_PASSWORD"
check_not "stderr does not blame keys that are fine" has_text "$ERR" "POSTGRES_USER"
check "no value in output" no_value_leak
check "kubectl was not called" no_kubectl_calls
check "no temp files left" no_temp_left

new_case "missing key fails by name (profile kis-paper without KIS_PAPER_APP_KEY)"
grep -v '^KIS_PAPER_APP_KEY' "$ENVF" > "$T/missing.env"
run --env-file "$T/missing.env" --profile kis-paper
check_not "exit is not 0" test "$RC" -eq 0
check "stderr names KIS_PAPER_APP_KEY" has_text "$ERR" "KIS_PAPER_APP_KEY"
check "no value in output" no_value_leak
check "kubectl was not called" no_kubectl_calls
check "no temp files left" no_temp_left

# ---------------------------------------------------------------- 4
new_case "kubectl fails midway: temp files are still removed"
FAKE_FAIL_CREATE=1 run --env-file "$ENVF"
check_not "exit is not 0" test "$RC" -eq 0
check "no value in output" no_value_leak
check "no temp files left" no_temp_left

# ---------------------------------------------------------------- 5
new_case "existing postgres-credentials is not overwritten without --force"
FAKE_POSTGRES_EXISTS=1 run --env-file "$ENVF"
check_not "exit is not 0" test "$RC" -eq 0
check "stderr says postgres-credentials and --force" bash -c "grep -q 'postgres-credentials' '$ERR' && grep -q -- '--force' '$ERR'"
check_not "nothing was created or applied" grep -qE 'create |apply ' "$FAKE_DIR/calls.log"
check "no value in output" no_value_leak
check "no temp files left" no_temp_left

new_case "existing postgres-credentials is overwritten with --force"
FAKE_POSTGRES_EXISTS=1 run --env-file "$ENVF" --force
check "exit 0" test "$RC" -eq 0
check "postgres-credentials was created" test -f "$FAKE_DIR/env.postgres-credentials"
check "a warning is printed" has_text "$ERR" "WARN"
check "no value in output" no_value_leak

# ---------------------------------------------------------------- 6
new_case "--dry-run prints names only and does not touch the cluster"
run --env-file "$ENVF" --dry-run
check "exit 0" test "$RC" -eq 0
check "prints kis-credentials" has_text "$OUT" "kis-credentials"
check "prints postgres-credentials" has_text "$OUT" "postgres-credentials"
check "prints key names" bash -c "grep -q KIS_APP_KEY '$OUT' && grep -q POSTGRES_PASSWORD '$OUT'"
check_not "prints no digits (no value lengths)" bash -c "grep -v '^context:' '$OUT' | grep -qE '[0-9]'"
check "no value in output" no_value_leak
check "kubectl was not called" no_kubectl_calls
check "no temp files left" no_temp_left

new_case "--dry-run still fails on a missing key"
run --env-file "$T/empty.env" --dry-run
check_not "exit is not 0" test "$RC" -eq 0
check "stderr names POSTGRES_PASSWORD" has_text "$ERR" "POSTGRES_PASSWORD"

# ---------------------------------------------------------------- 7
new_case "bad arguments fail with usage (exit 2)"
for bad in "--bogus" "--profile nope" "--namespace Bad_NS" "--env-file" "--profile" "positional"; do
    # shellcheck disable=SC2086
    run $bad
    check "'$bad' -> exit 2" test "$RC" -eq 2
    check "'$bad' -> usage on stderr" has_text "$ERR" "usage:"
done
check "kubectl was not called" no_kubectl_calls

new_case "missing env file fails (exit 1)"
run --env-file "$T/does-not-exist.env"
check "exit 1" test "$RC" -eq 1
check "stderr says env file not found" has_text "$ERR" "env file not found"
check "kubectl was not called" no_kubectl_calls

echo
if [ "$FAILED" -gt 0 ]; then
    echo "FAILED: $FAILED check(s)" >&2
    exit 1
fi
echo "all checks passed"
