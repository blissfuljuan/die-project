#!/usr/bin/env bash
# Smoke test of a RUNNING DIE stack (P0). Usage:  set -a; . ./.env; set +a; bash scripts/smoke.sh
# Exit 0 = all passed, 1 = a check failed, 2 = missing credentials. Needs: bash, curl, python3 (or python).
set -u
FRONT="${SMOKE_FRONTEND_URL:-http://localhost:5173}"
API="${SMOKE_API_URL-http://localhost:8080}"
EMAIL="${SMOKE_ADMIN_EMAIL:-${DIE_BOOTSTRAP_ADMIN_EMAIL:-}}"
PASS_="${SMOKE_ADMIN_PASSWORD:-${DIE_BOOTSTRAP_ADMIN_PASSWORD:-}}"
TIMEOUT="${SMOKE_JOB_TIMEOUT:-90}"
CURL_OPTS="${SMOKE_CURL_OPTS:-}"
PY="$(command -v python3 || command -v python || true)"
[ -n "$PY" ] || { echo "python3/python is required for JSON parsing"; exit 2; }
if [ -z "$EMAIL" ] || [ -z "$PASS_" ]; then
  echo "Set DIE_BOOTSTRAP_ADMIN_EMAIL/_PASSWORD (or SMOKE_ADMIN_EMAIL/_PASSWORD)"; exit 2
fi

JAR="$(mktemp)"; trap 'rm -f "$JAR"' EXIT
FAILED=0
RID="smoke-$(date +%s)-$RANDOM"
ok()    { echo "  PASS  $*"; }
bad()   { echo "  FAIL  $*"; FAILED=1; }
check() { if [ "$1" = "0" ]; then ok "$2"; else bad "$2"; fi; }

# jget <dotted.path>  (reads JSON on stdin; numeric path parts index lists)
jget() {
  "$PY" -c '
import sys, json
try:
    d = json.load(sys.stdin)
    for k in sys.argv[1].split("."):
        d = d[int(k)] if isinstance(d, list) else d.get(k)
        if d is None:
            break
    print("" if d is None else d)
except Exception:
    print("")
' "$1"
}

# req METHOD URL [JSON]  -> body, then a last line "HTTP <code>"
req() {
  local m="$1" u="$2" d="${3:-}" xsrf
  xsrf="$(awk '$6=="XSRF-TOKEN"{print $7}' "$JAR" | tail -n1)"
  local args=(-sS -m 30 -b "$JAR" -c "$JAR" -X "$m" "$u" -H "X-Request-Id: $RID" -w '\nHTTP %{http_code}\n')
  [ -n "$xsrf" ] && args+=(-H "X-XSRF-TOKEN: $xsrf")
  [ -n "$d" ] && args+=(-H "Content-Type: application/json" --data "$d")
  # shellcheck disable=SC2086
  curl $CURL_OPTS "${args[@]}" || printf '\nHTTP 000\n'
}
code() { printf '%s\n' "$1" | tail -n1 | awk '{print $2}'; }
body() { printf '%s\n' "$1" | sed '$d'; }

echo "DIE smoke test  frontend=$FRONT  api=${API:-<skipped>}"

if [ -n "$API" ]; then
  # shellcheck disable=SC2086
  r="$(curl -sS $CURL_OPTS -m 15 "$API/actuator/health/readiness" -w '\nHTTP %{http_code}\n' || printf '\nHTTP 000\n')"
  [ "$(code "$r")" = 200 ] && body "$r" | grep -q '"UP"'; check $? "API readiness is UP (Postgres + RabbitMQ)"
  # shellcheck disable=SC2086
  r="$(curl -sS $CURL_OPTS -m 15 "$API/actuator/health/liveness" -w '\nHTTP %{http_code}\n' || printf '\nHTTP 000\n')"
  [ "$(code "$r")" = 200 ] && body "$r" | grep -q '"UP"'; check $? "API liveness is UP"
fi

r="$(req GET "$FRONT/")"
[ "$(code "$r")" = 200 ] && body "$r" | grep -qi '<div id="root"'; check $? "frontend serves the SPA shell"
r="$(req GET "$FRONT/some/deep/link")"
[ "$(code "$r")" = 200 ] && body "$r" | grep -qi '<div id="root"'; check $? "SPA history fallback (deep link returns index.html)"
r="$(req GET "$FRONT/api/v1/status")"
[ "$(code "$r")" = 200 ] && [ "$(body "$r" | jget status)" = UP ]; check $? "GET /api/v1/status through the proxy: UP"
r="$(req GET "$FRONT/api/v1/auth/csrf")"
grep -q 'XSRF-TOKEN' "$JAR"; check $? "CSRF cookie issued through the proxy"

# A mutation WITHOUT the CSRF header must be refused.
# shellcheck disable=SC2086
r="$(curl -sS $CURL_OPTS -m 15 -b "$JAR" -X POST "$FRONT/api/v1/auth/login" -H 'Content-Type: application/json' \
      --data '{"email":"x@x.x","password":"x"}' -w '\nHTTP %{http_code}\n' || printf '\nHTTP 000\n')"
[ "$(code "$r")" = 403 ]; check $? "POST without CSRF header is refused (403)"

LOGIN_JSON="$("$PY" -c 'import json,sys; print(json.dumps({"email": sys.argv[1], "password": sys.argv[2]}))' "$EMAIL" "$PASS_")"
r="$(req POST "$FRONT/api/v1/auth/login" "$LOGIN_JSON")"
[ "$(code "$r")" = 200 ]; check $? "admin login"

if [ "$(code "$r")" = 200 ]; then
  r="$(req GET "$FRONT/api/v1/auth/me")"
  [ "$(body "$r" | jget role)" = ADMIN ]; check $? "session is an ADMIN session"

  r="$(req GET "$FRONT/api/v1/ops/health")"
  h="$(body "$r")"
  [ "$(code "$r")" = 200 ] && [ "$(printf '%s' "$h" | jget database.status)" = UP ]; check $? "ops health: database UP"
  [ "$(printf '%s' "$h" | jget broker.status)" = UP ]; check $? "ops health: broker UP"
  printf '%s' "$h" | "$PY" -c '
import sys, json
try:
    w = json.load(sys.stdin).get("workers") or []
    sys.exit(0 if len(w) >= 2 and all(x.get("status") == "UP" and x.get("heartbeatFresh") for x in w) else 1)
except Exception:
    sys.exit(1)
'
  check $? "ops health: orchestrator and document-worker UP with fresh heartbeats"

  r="$(req POST "$FRONT/api/v1/ops/test-jobs" '{"note":"smoke","simulateFailure":false}')"
  JOB="$(body "$r" | jget id)"
  [ "$(code "$r")" = 202 ] && [ -n "$JOB" ]; check $? "test job accepted (202) id=$JOB request id $RID"

  state=""; corr=""; end=$(( $(date +%s) + TIMEOUT ))
  while [ -n "$JOB" ] && [ "$(date +%s)" -lt "$end" ]; do
    r="$(req GET "$FRONT/api/v1/ops/test-jobs/$JOB")"
    state="$(body "$r" | jget state)"; corr="$(body "$r" | jget correlationId)"
    if [ "$state" = SUCCEEDED ] || [ "$state" = FAILED ]; then break; fi
    sleep 1
  done
  [ "$state" = SUCCEEDED ]; check $? "test job reached SUCCEEDED (last state: ${state:-none})"
  [ "$corr" = "$RID" ]; check $? "request id preserved as the job's correlation id"

  r="$(req POST "$FRONT/api/v1/auth/logout")"
  c="$(code "$r")"; [ "$c" = 200 ] || [ "$c" = 204 ]; check $? "logout"
fi

if [ "$FAILED" = 0 ]; then
  echo; echo "SMOKE TEST PASSED  (correlation id: $RID)"; exit 0
fi
echo; echo "SMOKE TEST FAILED"; exit 1
