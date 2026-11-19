#!/usr/bin/env bash
# create-slack-secret.sh 테스트. 단독으로 실행한다(통과하면 0, 하나라도 어긋나면 0 이 아닌 값).
#
#   ./deploy/monitoring/test-create-slack-secret.sh              # 검사 전부
#   ./deploy/monitoring/test-create-slack-secret.sh --mutation   # 스크립트를 일부러 틀리게 고친 복사본마다 검사가 실패하는지 확인
#
# 진짜 kubectl 은 부르지 않는다. 임시 폴더에 가짜 kubectl 을 만들어 PATH 맨 앞에 둔다. 가짜 kubectl 은
#   - 받은 인자를 calls.log 에 남기고
#   - `create secret generic NAME --from-file=KEY=F` 의 F 내용을 file.NAME.KEY 로 복사한다(이게 "넘어간 값"이다)
#   - FAKE_FAIL_CREATE 가 있으면 create secret 에서 실패한다.
# 아래 값은 전부 **합성값**이다(실제 웹훅·키가 아니다). 주소 모양만 같고 Slack 이 발급한 것이 아니다.
# .env 는 CRLF·따옴표·BOM·export·= 를 일부러 섞어 만든다.
# 다른 스크립트로 돌려 보려면 CREATE_SLACK_SECRET=그 경로 로 지정한다(--mutation 이 이것을 쓴다).
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
SELF="$DIR/$(basename "$0")"
ORIGINAL="$DIR/create-slack-secret.sh"
SCRIPT="${CREATE_SLACK_SECRET:-$ORIGINAL}"

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/tmp"

# 합성값 (SYNTHETIC: 실제 값 아님). 소문자·하이픈이라 실제 웹훅 모양(T…/B…/…)과도 다르다.
URL_PREFIX='https://hooks.slack.com/services/'
V_URL="${URL_PREFIX}synthetic-team/synthetic-bot/synthetic-token-0001"
V_OLD="${URL_PREFIX}synthetic-old/synthetic-old/synthetic-old-0002"
V_COMMENTED="${URL_PREFIX}synthetic-commented/x/y"
V_OTHER='synthetic-other-AAA=='
printf '%s\n' "$V_URL" "$V_OLD" "$V_COMMENTED" "$V_OTHER" synthetic > "$T/leak-patterns"

cat > "$T/bin/kubectl" <<'FAKE'
#!/usr/bin/env bash
# 테스트용 가짜 kubectl (진짜 클러스터에 아무것도 보내지 않는다)
d="${FAKE_DIR:?}"
printf '%s\n' "$*" >> "$d/calls.log"
case " $* " in
    *" create secret generic "*)
        [ -z "${FAKE_FAIL_CREATE:-}" ] || { echo "fake kubectl: create failed" >&2; exit 1; }
        name=""; spec=""; prev=""
        for a in "$@"; do
            [ "$prev" = generic ] && name="$a"
            case "$a" in --from-file=*) spec="${a#--from-file=}" ;; esac
            prev="$a"
        done
        key="${spec%%=*}"
        file="${spec#*=}"
        cp "$file" "$d/file.$name.$key"
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

# ---------------------------------------------------------------- 스크립트를 일부러 틀리게 고친 복사본 확인
if [ "${1:-}" = "--mutation" ]; then
    MUT_FAILED=0
    mkdir -p "$T/mut"
    ORIG_TEXT="$(cat "$ORIGINAL"; echo x)"
    ORIG_TEXT="${ORIG_TEXT%x}"

    # mutant 이름 "기대하는 실패 문구" < 입력: 바꿀 원문, 한 줄 =====, 새 문구. 여러 군데를 한꺼번에 고치려면 묶음 사이에 한 줄 -----
    # MUT_ONLY="이름 이름" 이면 그 복사본만 돌린다
    mutant() {
        local name="$1" expect="$2" spec block old new text rest copy log rc
        if [ -n "${MUT_ONLY:-}" ] && [[ " $MUT_ONLY " != *" $name "* ]]; then cat > /dev/null; return; fi
        spec="$(cat; echo x)"
        spec="${spec%x}"
        text="$ORIG_TEXT"
        while [ -n "$spec" ]; do
            if [[ "$spec" == *$'\n-----\n'* ]]; then
                block="${spec%%$'\n-----\n'*}"
                spec="${spec#*$'\n-----\n'}"
            else
                block="${spec%$'\n'}"
                spec=""
            fi
            old="${block%%$'\n'=====$'\n'*}"
            new="${block#*$'\n'=====$'\n'}"
            rest="${text//"$old"/}"
            if [ $(( ${#text} - ${#rest} )) -ne "${#old}" ]; then
                echo "  FAIL: mutant $name: the text to replace must appear exactly once in the script" >&2
                MUT_FAILED=$((MUT_FAILED + 1))
                return
            fi
            text="${text/"$old"/"$new"}"
        done
        copy="$T/mut/$name.sh"
        printf '%s' "$text" > "$copy"
        log="$T/mut/$name.log"
        set +e
        FAIL_FAST=1 CREATE_SLACK_SECRET="$copy" bash "$SELF" > "$log" 2>&1
        rc=$?
        set -e
        if [ "$rc" -eq 0 ]; then
            echo "  FAIL: mutant $name survived (the tests still pass)" >&2
            MUT_FAILED=$((MUT_FAILED + 1))
        elif ! grep -qF -- "FAIL: $expect" "$log"; then
            echo "  FAIL: mutant $name failed, but not on the expected check '$expect'" >&2
            grep 'FAIL:' "$log" | head -3 >&2 || true
            MUT_FAILED=$((MUT_FAILED + 1))
        else
            echo "  ok: mutant $name is caught by '$expect' ($(grep -c 'FAIL:' "$log") failing check(s))"
        fi
    }

    echo "== mutants (each must make the tests fail)"
    # 줄 끝 CR 제거만 빼는 복사본은 만들지 않는다: 값 앞뒤 공백 제거가 CR 도 지워서 결과가 같다(동치 변형).
    # 그래서 CR 제거와 공백 제거를 같이 뺀다 -> 형식 검사가 CR 이 붙은 값을 거절한다
    mutant no-cr-no-trim "exit 0" <<'XEOF'
        line="${line%$'\r'}"
=====
        : # CR not removed
-----
        val="${val%"${val##*[![:space:]]}"}"
        if [ "${#val}" -ge 2 ]; then
=====
        if [ "${#val}" -ge 2 ]; then
XEOF
    # 형식 검사까지 없으면 CR 이 붙은 값이 그대로 kubectl 로 넘어간다
    mutant no-cr-no-trim-no-validate "value passed to kubectl equals input" <<'XEOF'
        line="${line%$'\r'}"
=====
        : # CR not removed
-----
        val="${val%"${val##*[![:space:]]}"}"
        if [ "${#val}" -ge 2 ]; then
=====
        if [ "${#val}" -ge 2 ]; then
-----
validate_webhook
printf
=====
:
printf
XEOF
    mutant no-trim "export, spaces around = and after the value: exit 0" <<'XEOF'
        val="${val%"${val##*[![:space:]]}"}"
        if [ "${#val}" -ge 2 ]; then
=====
        if [ "${#val}" -ge 2 ]; then
XEOF
    mutant echo-value "no value in stdout/stderr/kubectl args" <<'EOF'
printf '%s' "$ENV_VALUE" > "$HOOK_FILE"
=====
printf '%s' "$ENV_VALUE" > "$HOOK_FILE"
echo "debug value: $ENV_VALUE"
EOF
    mutant no-format-check "non-webhook value is rejected" <<'EOF'
validate_webhook
printf
=====
:
printf
EOF
    mutant no-prefix-check "non-webhook value is rejected" <<'EOF'
        "$URL_PREFIX"?*) ;;
=====
        *) ;;
EOF
    mutant no-charset-check "non-webhook value is rejected" <<'EOF'
        *[!A-Za-z0-9_/-]*)
=====
        __never__)
EOF
    mutant no-trap "no temp files left" <<'EOF'
trap cleanup EXIT
=====
: # trap removed
EOF
    mutant dry-run-prints-value "prints no digits besides the context line" <<'EOF'
    echo "secret $SECRET_NAME: $SECRET_KEY (from $VAR_NAME)"
=====
    echo "secret $SECRET_NAME: $SECRET_KEY (from $VAR_NAME) $(cat "$HOOK_FILE")"
EOF
    mutant context-not-fixed "context is fixed to k3d-margin-watch on every call" <<'EOF'
kc() { kubectl --context "$CONTEXT" "$@"; }
=====
kc() { kubectl "$@"; }
EOF
    mutant value-on-argv "value passed to kubectl equals input" <<'EOF'
--from-file="$SECRET_KEY=$(native_path "$HOOK_FILE")"
=====
--from-literal="$SECRET_KEY=$(cat "$HOOK_FILE")"
EOF
    mutant empty-accepted "stderr says missing or empty" <<'EOF'
if ! read_env_value "$VAR_NAME" || [ -z "$ENV_VALUE" ]; then
=====
if ! read_env_value "$VAR_NAME"; then
EOF
    mutant no-namespace-check "'--namespace Bad_NS' -> exit 2" <<'EOF'
=~ ^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$ ]]
=====
=~ .* ]]
EOF
    mutant no-namespace-create "namespace is created idempotently" <<'EOF'
kc create namespace "$NAMESPACE" --dry-run=client -o yaml | kc apply -f -
=====
:
EOF
    mutant wrong-secret-name "value passed to kubectl equals input" <<'EOF'
SECRET_NAME=alertmanager-slack
=====
SECRET_NAME=slack-webhook
EOF
    mutant wrong-key-name "value passed to kubectl equals input" <<'EOF'
SECRET_KEY=webhook-url
=====
SECRET_KEY=url
EOF
    mutant trailing-newline "value passed to kubectl equals input" <<'EOF'
printf '%s' "$ENV_VALUE" > "$HOOK_FILE"
=====
printf '%s\n' "$ENV_VALUE" > "$HOOK_FILE"
EOF

    echo
    if [ "$MUT_FAILED" -gt 0 ]; then
        echo "FAILED: $MUT_FAILED mutant check(s)" >&2
        exit 1
    fi
    echo "all mutants were caught"
    exit 0
fi

# ---------------------------------------------------------------- 검사 도구
FAILED=0
CASE=0
fail() {
    echo "  FAIL: $*" >&2
    FAILED=$((FAILED + 1))
    # --mutation 은 첫 실패에서 멈춘다(복사본마다 전체 검사를 돌리면 너무 오래 걸린다)
    [ -z "${FAIL_FAST:-}" ] || { echo "FAILED (fail-fast)" >&2; exit 1; }
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
    unset FAKE_FAIL_CREATE || true
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
    local f files=()
    for f in "$OUT" "$ERR" "$FAKE_DIR/calls.log" "$FAKE_DIR/stdin.log"; do
        [ -f "$f" ] && files+=("$f")
    done
    [ "${#files[@]}" -gt 0 ] || return 0
    # 값 전부와, 합성값에 공통으로 들어 있는 낱말(synthetic)을 한 번에 찾는다(grep 을 적게 불러 빠르게)
    ! grep -qiF -f "$T/leak-patterns" "${files[@]}"
}

no_temp_left() { [ -z "$(ls -A "$T/tmp")" ]; }
no_kubectl_calls() { [ ! -s "$FAKE_DIR/calls.log" ]; }
has_text() { grep -qF -- "$2" "$1"; }
has_no_cr() { ! grep -q $'\r' "$1"; }

# 파일 내용이 정확히 $2(줄바꿈 없이) 인지
file_equals() {
    printf '%s' "$2" > "$FAKE_DIR/expected.tmp"
    cmp -s "$FAKE_DIR/expected.tmp" "$1"
}

# env 파일을 쓴다: write_env 파일 "줄 끝" 값줄... (줄 끝은 \r\n 이나 \n). 마지막 줄에는 줄바꿈을 붙이지 않는다.
write_lines() {
    local f="$1" eol="$2" n i
    shift 2
    n=$#
    : > "$f"
    i=0
    for line in "$@"; do
        i=$((i + 1))
        if [ "$i" -lt "$n" ]; then printf '%s%s' "$line" "$eol" >> "$f"; else printf '%s' "$line" >> "$f"; fi
    done
}

# 다른 변수와 주석을 섞은 CRLF .env (합성)
ENVF="$T/test.env"
write_lines "$ENVF" $'\r\n' \
    '# synthetic test env (not real)' \
    '' \
    "#SLACK_ALERTS_WEBHOOKS=$V_COMMENTED" \
    "KIS_APP_KEY=\"$V_OTHER\"" \
    'SLACK_ALERTS_CHANNEL=synthetic-channel' \
    "SLACK_ALERTS_WEBHOOKS=\"$V_URL\"" \
    'SLACK_MEMBER_ID=synthetic-member'

# ---------------------------------------------------------------- 1
new_case "CRLF .env with quotes: the value passes through exactly, nothing else"
run --env-file "$ENVF"
check "exit 0" test "$RC" -eq 0
check "value passed to kubectl equals input (no CR, no quotes, no newline)" file_equals "$FAKE_DIR/file.alertmanager-slack.webhook-url" "$V_URL"
check "secret name is alertmanager-slack and key is webhook-url (only that file was made)" bash -c "ls '$FAKE_DIR' | grep '^file\.' | grep -qx 'file.alertmanager-slack.webhook-url' && [ \$(ls '$FAKE_DIR' | grep -c '^file\.') -eq 1 ]"
check "no CR in the file kubectl received" has_no_cr "$FAKE_DIR/file.alertmanager-slack.webhook-url"
check "no value in stdout/stderr/kubectl args" no_value_leak
check_not "stdout does not even show the host name" has_text "$OUT" "hooks.slack"
check "no temp files left" no_temp_left
check "context is fixed to k3d-margin-watch on every call" bash -c "! grep -v -- '--context k3d-margin-watch' '$FAKE_DIR/calls.log' | grep -q ."
check "default namespace is monitoring" has_text "$FAKE_DIR/calls.log" "-n monitoring create secret generic alertmanager-slack"
check "namespace is created idempotently (dry-run | apply)" has_text "$FAKE_DIR/calls.log" "create namespace monitoring --dry-run=client -o yaml"
check "namespace and secret are piped to apply -f -" bash -c "grep -c 'apply -f -' '$FAKE_DIR/calls.log' | grep -qx 2"
check_not "no value on kubectl args (only the --from-file path)" grep -q -- '--from-literal' "$FAKE_DIR/calls.log"
check "stdout names the secret and namespace" bash -c "grep -q alertmanager-slack '$OUT' && grep -q monitoring '$OUT'"

# ---------------------------------------------------------------- 2
new_case "other ways to write the same line (the value must still be exact)"
i=0
variant() { # variant "설명" "줄 끝" 줄...
    local msg="$1" eol="$2"
    shift 2
    i=$((i + 1))
    write_lines "$T/v$i.env" "$eol" "$@"
    FAKE_DIR="$T/fake$CASE.$i"
    mkdir -p "$FAKE_DIR"
    OUT="$FAKE_DIR/out.txt"
    ERR="$FAKE_DIR/err.txt"
    rm -rf "$T/tmp"
    mkdir -p "$T/tmp"
    export FAKE_DIR
    run --env-file "$T/v$i.env"
    check "$msg: exit 0" test "$RC" -eq 0
    check "$msg: value equals input" file_equals "$FAKE_DIR/file.alertmanager-slack.webhook-url" "$V_URL"
    check "$msg: no value in output" no_value_leak
    check "$msg: no temp files left" no_temp_left
}
variant "LF, unquoted" $'\n' "SLACK_ALERTS_WEBHOOKS=$V_URL"
variant "LF, single quotes" $'\n' "SLACK_ALERTS_WEBHOOKS='$V_URL'"
variant "CRLF, no trailing newline, other vars after" $'\r\n' "SLACK_ALERTS_WEBHOOKS=$V_URL" 'OTHER=1'
variant "export, spaces around = and after the value" $'\n' "  export SLACK_ALERTS_WEBHOOKS = \"$V_URL\"   "
variant "BOM on the first line" $'\r\n' $'\xef\xbb\xbf'"SLACK_ALERTS_WEBHOOKS=$V_URL"
variant "duplicate key: the last one wins" $'\n' "SLACK_ALERTS_WEBHOOKS=$V_OLD" "SLACK_ALERTS_WEBHOOKS=$V_URL"
variant "a similar name (SLACK_ALERTS_WEBHOOK) is not used" $'\n' "SLACK_ALERTS_WEBHOOK=$V_OLD" "SLACK_ALERTS_WEBHOOKS=$V_URL"

# ---------------------------------------------------------------- 3
new_case "custom namespace"
run --env-file "$ENVF" --namespace rehearsal --force
check "exit 0 (--force is accepted)" test "$RC" -eq 0
check "namespace argument is used" has_text "$FAKE_DIR/calls.log" "-n rehearsal create secret generic alertmanager-slack"
check "namespace is created with that name" has_text "$FAKE_DIR/calls.log" "create namespace rehearsal --dry-run=client -o yaml"
check "no value in output" no_value_leak
check "no temp files left" no_temp_left

# ---------------------------------------------------------------- 4
missing_case() { # missing_case "설명" 줄...
    local msg="$1"
    shift
    new_case "$msg"
    write_lines "$T/m$CASE.env" $'\r\n' "$@"
    run --env-file "$T/m$CASE.env"
    check_not "exit is not 0" test "$RC" -eq 0
    check "stderr says missing or empty" has_text "$ERR" "missing or empty"
    check "stderr names SLACK_ALERTS_WEBHOOKS" has_text "$ERR" "SLACK_ALERTS_WEBHOOKS"
    check "no value in output" no_value_leak
    check "kubectl was not called" no_kubectl_calls
    check "no temp files left" no_temp_left
}
missing_case "variable is absent" 'SLACK_ALERTS_CHANNEL=synthetic-channel' "KIS_APP_KEY=$V_OTHER"
missing_case "variable is empty" 'SLACK_ALERTS_WEBHOOKS=' "KIS_APP_KEY=$V_OTHER"
missing_case "variable is only quotes" 'SLACK_ALERTS_WEBHOOKS=""' "KIS_APP_KEY=$V_OTHER"
missing_case "variable is only spaces" 'SLACK_ALERTS_WEBHOOKS=   ' "KIS_APP_KEY=$V_OTHER"
missing_case "variable only commented out" "#SLACK_ALERTS_WEBHOOKS=$V_COMMENTED" "KIS_APP_KEY=$V_OTHER"
missing_case "only a similar name exists" "SLACK_ALERTS_WEBHOOK=$V_OLD" "KIS_APP_KEY=$V_OTHER"

new_case "empty file"
: > "$T/empty-file.env"
run --env-file "$T/empty-file.env"
check_not "exit is not 0" test "$RC" -eq 0
check "stderr names SLACK_ALERTS_WEBHOOKS" has_text "$ERR" "SLACK_ALERTS_WEBHOOKS"
check "kubectl was not called" no_kubectl_calls

# ---------------------------------------------------------------- 5
format_case() { # format_case "설명" 값
    local msg="$1" value="$2"
    new_case "format: $msg"
    write_lines "$T/f$CASE.env" $'\r\n' "KIS_APP_KEY=$V_OTHER" "SLACK_ALERTS_WEBHOOKS=$value"
    run --env-file "$T/f$CASE.env"
    check_not "non-webhook value is rejected (exit is not 0)" test "$RC" -eq 0
    check "non-webhook value is rejected (stderr names SLACK_ALERTS_WEBHOOKS)" has_text "$ERR" "SLACK_ALERTS_WEBHOOKS"
    check "no value in output" no_value_leak
    check "kubectl was not called" no_kubectl_calls
    check "no temp files left" no_temp_left
}
format_case "other host" "https://hooks.slack.invalid/services/synthetic-a/synthetic-b"
format_case "http instead of https" "http://hooks.slack.com/services/synthetic-a/synthetic-b"
format_case "just a token" "synthetic-token-without-url"
format_case "prefix only" "$URL_PREFIX"
format_case "two webhooks joined by a comma" "${V_URL},${V_OLD}"
format_case "trailing comma" "${V_URL},"
format_case "space inside" "${URL_PREFIX}synthetic-a synthetic-b"
format_case "space inside, with quotes around" "\"${URL_PREFIX}synthetic-a synthetic-b\""
format_case "double quote inside" "${URL_PREFIX}synthetic-a\"synthetic-b"
format_case "single quote inside" "${URL_PREFIX}synthetic-a'synthetic-b"
format_case "query string" "${V_URL}?x=1"

# ---------------------------------------------------------------- 6
new_case "kubectl fails midway: temp files are still removed, nothing leaks"
FAKE_FAIL_CREATE=1 run --env-file "$ENVF"
check_not "exit is not 0" test "$RC" -eq 0
check "no value in output" no_value_leak
check "no temp files left" no_temp_left

new_case "interrupted by a format error after the temp dir exists: nothing is left"
write_lines "$T/late.env" $'\n' "SLACK_ALERTS_WEBHOOKS=${URL_PREFIX}synthetic a"
run --env-file "$T/late.env"
check_not "exit is not 0" test "$RC" -eq 0
check "no temp files left" no_temp_left

# ---------------------------------------------------------------- 7
new_case "--dry-run prints names only and does not touch the cluster"
run --env-file "$ENVF" --dry-run
check "exit 0" test "$RC" -eq 0
check "prints the secret name" has_text "$OUT" "alertmanager-slack"
check "prints the key name" has_text "$OUT" "webhook-url"
check "prints the namespace" has_text "$OUT" "namespace: monitoring"
check_not "prints no digits besides the context line (no value lengths)" bash -c "grep -v '^context:' '$OUT' | grep -qE '[0-9]'"
check_not "prints no host name" has_text "$OUT" "hooks.slack"
check "no value in output" no_value_leak
check "kubectl was not called" no_kubectl_calls
check "no temp files left" no_temp_left

new_case "--dry-run still fails on a missing or malformed value"
write_lines "$T/dry-bad.env" $'\n' 'SLACK_ALERTS_WEBHOOKS='
run --env-file "$T/dry-bad.env" --dry-run
check_not "empty: exit is not 0" test "$RC" -eq 0
check "empty: stderr names SLACK_ALERTS_WEBHOOKS" has_text "$ERR" "SLACK_ALERTS_WEBHOOKS"
write_lines "$T/dry-bad2.env" $'\n' 'SLACK_ALERTS_WEBHOOKS=synthetic-not-a-url'
run --env-file "$T/dry-bad2.env" --dry-run
check_not "malformed: exit is not 0" test "$RC" -eq 0
check "malformed: no value in output" no_value_leak

# ---------------------------------------------------------------- 8
new_case "bad arguments exit with usage (exit 2)"
for bad in "--bogus" "--profile kis" "--namespace Bad_NS" "--namespace -x" "--env-file" "--namespace" "positional"; do
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
