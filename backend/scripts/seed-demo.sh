#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"

post_launch() {
  local name="$1"; local kind="$2"; local brief="$3"; local license="$4"; shift 4
  local jurisdictions_json
  jurisdictions_json=$(printf '"%s",' "$@" | sed 's/,$//')
  curl -sS -X POST "$BASE_URL/launches" \
    -H 'content-type: application/json' \
    -d "$(cat <<EOF
{
  "name": "$name",
  "kind": "$kind",
  "brief": "$brief",
  "license": "$license",
  "jurisdictions": [$jurisdictions_json]
}
EOF
)"
  echo
}

echo "Seeding launch 1/3: Crypto Debit Card (PRODUCT)"
post_launch "Crypto Debit Card" "PRODUCT" \
  "EU debit card backed by USDC with instant spend and per-transaction sanctions screening" \
  "EMI" NL DE FR UK US

echo "Seeding launch 2/3: ToC §5.3 Sanctions Screening (POLICY)"
post_launch "ToC Section 5.3 Sanctions Screening" "POLICY" \
  "Policy amendment mandating real-time OFAC/EU/UN list checks on every outbound counterparty" \
  "EMI" NL DE FR IE

echo "Seeding launch 3/3: KYC Onboarding Flow (PROCESS)"
post_launch "KYC Onboarding Flow" "PROCESS" \
  "Process overhaul for enhanced due diligence on new customer onboarding in EU and US" \
  "EMI" NL DE UK US IE

echo "Done. GET $BASE_URL/launches to verify."
