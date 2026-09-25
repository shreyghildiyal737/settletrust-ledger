#!/usr/bin/env bash
# One invoice, draft to settled, over HTTP against a running service.
#
#   BASE=https://settletrust-ledger.onrender.com KEY=... scripts/smoke.sh
#
# Every step asserts the status code it expects, so a run that reaches the end has proved
# the path rather than merely walked it. Needs only bash and curl.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
KEY="${KEY:-}"
RUN="$(date +%s)"
BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT

# call METHOD PATH EXPECTED [JSON] [EXTRA_HEADER]
call() {
    local method="$1" path="$2" expected="$3" data="${4:-}" extra="${5:-}"
    local args=(-s -o "$BODY" -w '%{http_code}' -X "$method" "$BASE$path"
        -H 'Content-Type: application/json')
    [ -n "$KEY" ] && args+=(-H "X-Api-Key: $KEY")
    [ -n "$extra" ] && args+=(-H "$extra")
    [ -n "$data" ] && args+=(-d "$data")

    local got
    got="$(curl "${args[@]}")"
    if [ "$got" != "$expected" ]; then
        echo "FAIL  $method $path  wanted $expected, got $got" >&2
        cat "$BODY" >&2; echo >&2
        exit 1
    fi
    printf 'ok    %-4s %-60s %s\n' "$method" "$path" "$got"
}

# expect FRAGMENT: the last response body must contain it.
expect() {
    if ! grep -q -- "$1" "$BODY"; then
        echo "FAIL  body lacks $1" >&2
        cat "$BODY" >&2; echo >&2
        exit 1
    fi
}

house="house-$RUN"; buyer="buyer-$RUN"; seller="seller-$RUN"; invoice="inv-$RUN"

call GET /actuator/health/readiness 200

call POST /api/v1/accounts 201 "{\"id\":\"$house\",\"currency\":\"EUR\",\"kind\":\"HOUSE\"}"
call POST /api/v1/accounts 201 "{\"id\":\"$buyer\",\"currency\":\"EUR\",\"kind\":\"CUSTOMER\"}"
call POST /api/v1/accounts 201 "{\"id\":\"$seller\",\"currency\":\"EUR\",\"kind\":\"CUSTOMER\"}"

# The buyer's float, and the same request again to prove the replay is recognised.
float="{\"from\":\"$house\",\"to\":\"$buyer\",\"amountMinor\":50000,\"currency\":\"EUR\"}"
call POST /api/v1/transfers 201 "$float" "Idempotency-Key: float-$RUN"
call POST /api/v1/transfers 200 "$float" "Idempotency-Key: float-$RUN"
expect '"replayed":true'

# A customer account may not go negative, and says why in a form a client can branch on.
call POST /api/v1/transfers 422 \
    "{\"from\":\"$buyer\",\"to\":\"$seller\",\"amountMinor\":999999,\"currency\":\"EUR\"}" \
    "Idempotency-Key: overdraw-$RUN"
expect 'INSUFFICIENT_FUNDS'

call POST /api/v1/invoices 201 \
    "{\"id\":\"$invoice\",\"reference\":\"SMOKE-$RUN\",\"sellerId\":\"$seller\",\"buyerId\":\"$buyer\",\"amountMinor\":25000,\"currency\":\"EUR\"}"

for to in submitted buyer_accepted escrow_pending; do
    call POST "/api/v1/invoices/$invoice/transitions" 201 "{\"to\":\"$to\",\"reason\":\"smoke\"}"
done

# Funded is a claim about money, so the generic endpoint refuses to assert it.
call POST "/api/v1/invoices/$invoice/transitions" 422 '{"to":"escrow_funded"}'
expect 'MONEY_MOVEMENT_REQUIRED'

call POST "/api/v1/invoices/$invoice/escrow-funding" 201 "{\"account\":\"$buyer\"}"
call POST "/api/v1/invoices/$invoice/escrow-funding" 200 "{\"account\":\"$buyer\"}"
expect '"replayed":true'

for to in shipment_pending delivery_confirmed settlement_pending; do
    call POST "/api/v1/invoices/$invoice/transitions" 201 "{\"to\":\"$to\",\"reason\":\"smoke\"}"
done

call POST "/api/v1/invoices/$invoice/settlement" 201 "{\"account\":\"$seller\"}"

call GET "/api/v1/invoices/$invoice" 200
expect '"status":"settled"'
call GET "/api/v1/accounts/$seller" 200
expect '"balanceMinor":25000'
call GET "/api/v1/accounts/$buyer" 200
expect '"balanceMinor":25000'

# Reconciled from stored rows, deep, so the verdict covers the whole book.
call POST '/api/v1/reconciliation/runs?deep=true' 201
expect '"discrepancies":\[\]'
call GET /api/v1/reconciliation/findings/open 200

echo "smoke passed: invoice $invoice settled against $BASE"
