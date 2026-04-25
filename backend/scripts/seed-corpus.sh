#!/usr/bin/env bash
# Upload ~44 PDFs from three source directories to the LaunchLens backend.
# Usage:
#   bash backend/scripts/seed-corpus.sh
#   BASE_URL=http://localhost:8080 bash backend/scripts/seed-corpus.sh
#   DRY_RUN=1 bash backend/scripts/seed-corpus.sh   # preview only, no API calls
set -uo pipefail

BASE_URL="${BASE_URL:-https://la-61db88c947f74eb39d26bed4a5484a43.ecs.eu-central-1.on.aws}"
DRY_RUN="${DRY_RUN:-0}"

# Hard-coded source directories and their metadata
PRIMARY_EU="D:/hackathon/Bunq/eu-regulatory-corpus/docs/primary"
PRIMARY_IE="D:/hackathon/Bunq/eu-regulatory-corpus/docs/national/ireland/primary"
POLICIES="D:/hackathon/backend/java-backend/seed/policies"

# ---------------------------------------------------------------------------
# Dependency checks
# ---------------------------------------------------------------------------
for cmd in curl jq; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: '$cmd' is required but not found in PATH." >&2
    exit 1
  fi
done

# Pick sha256 utility
if command -v sha256sum >/dev/null 2>&1; then
  sha256_file() { sha256sum "$1" | awk '{print $1}'; }
else
  sha256_file() { shasum -a 256 "$1" | awk '{print $1}'; }
fi

echo "BASE_URL: $BASE_URL"
[[ "$DRY_RUN" == "1" ]] && echo "DRY_RUN=1 — printing tuples only, no API calls."
echo ""

# ---------------------------------------------------------------------------
# Counters
# ---------------------------------------------------------------------------
eu_ok=0;  eu_total=0
ie_ok=0;  ie_total=0
pol_ok=0; pol_total=0
deduped_count=0
fail_count=0

# ---------------------------------------------------------------------------
# Core upload function
#   upload_file <pdf_path> <kind> <jurisdictions_json_array> <counter_prefix>
# ---------------------------------------------------------------------------
upload_file() {
  local pdf="$1"
  local kind="$2"
  local jurs_json="$3"   # e.g. '["EU"]' or '["NL","DE","FR","UK","US","IE"]'
  local filename
  filename="$(basename "$pdf")"

  if [[ "$DRY_RUN" == "1" ]]; then
    echo "[DRY_RUN] kind=$kind jurisdictions=$jurs_json filename=$filename"
    return 0
  fi

  # Compute SHA-256 hex and base64
  local hex b64
  hex=$(sha256_file "$pdf")
  b64=$(python3 -c "import binascii,base64,sys; print(base64.b64encode(binascii.unhexlify(sys.argv[1])).decode())" "$hex")

  # Step 1: presign
  local presign_resp presign_status
  presign_resp=$(curl -sS -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/documents/presign" \
    -H "Content-Type: application/json" \
    -d "{\"filename\":\"$filename\",\"contentType\":\"application/pdf\",\"sha256\":\"$b64\"}")
  presign_status=$(printf '%s' "$presign_resp" | tail -n1)
  presign_resp=$(printf '%s' "$presign_resp" | head -n -1)

  if [[ "$presign_status" != 2* ]]; then
    local snippet="${presign_resp:0:200}"
    echo "[FAIL] $filename step=presign status=$presign_status body=$snippet"
    fail_count=$((fail_count + 1))
    return 1
  fi

  local incoming_key upload_url
  incoming_key=$(printf '%s' "$presign_resp" | jq -r '.incomingKey')
  upload_url=$(printf '%s' "$presign_resp" | jq -r '.uploadUrl')

  # Step 2: PUT file bytes to S3
  local put_status
  put_status=$(curl -sS -o /dev/null -w "%{http_code}" -X PUT "$upload_url" \
    -H "Content-Type: application/pdf" \
    -H "x-amz-sdk-checksum-algorithm: SHA256" \
    -H "x-amz-checksum-sha256: $b64" \
    --data-binary "@$pdf")

  if [[ "$put_status" != 2* ]]; then
    echo "[FAIL] $filename step=put status=$put_status body=(binary upload, no body)"
    fail_count=$((fail_count + 1))
    return 1
  fi

  # Step 3: finalize
  local finalize_resp finalize_status
  finalize_resp=$(curl -sS -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/documents/finalize" \
    -H "Content-Type: application/json" \
    -d "{\"incomingKey\":\"$incoming_key\",\"filename\":\"$filename\",\"contentType\":\"application/pdf\",\"kind\":\"$kind\",\"jurisdictions\":$jurs_json}")
  finalize_status=$(printf '%s' "$finalize_resp" | tail -n1)
  finalize_resp=$(printf '%s' "$finalize_resp" | head -n -1)

  if [[ "$finalize_status" != 2* ]]; then
    local snippet="${finalize_resp:0:200}"
    echo "[FAIL] $filename step=finalize status=$finalize_status body=$snippet"
    fail_count=$((fail_count + 1))
    return 1
  fi

  local doc_id deduped
  doc_id=$(printf '%s' "$finalize_resp" | jq -r '.document.id')
  deduped=$(printf '%s' "$finalize_resp" | jq -r '.deduped')

  echo "[OK] $kind $jurs_json $filename -> $doc_id (deduped=$deduped)"
  [[ "$deduped" == "true" ]] && deduped_count=$((deduped_count + 1))
  return 0
}

# ---------------------------------------------------------------------------
# Process EU primary regulations
# ---------------------------------------------------------------------------
for f in "$PRIMARY_EU"/*.pdf; do
  [[ -f "$f" ]] || continue
  eu_total=$((eu_total + 1))
  if upload_file "$f" "regulation" '["EU"]'; then
    eu_ok=$((eu_ok + 1))
  fi
done

# ---------------------------------------------------------------------------
# Process Ireland primary regulations
# ---------------------------------------------------------------------------
for f in "$PRIMARY_IE"/*.pdf; do
  [[ -f "$f" ]] || continue
  ie_total=$((ie_total + 1))
  if upload_file "$f" "regulation" '["IE"]'; then
    ie_ok=$((ie_ok + 1))
  fi
done

# ---------------------------------------------------------------------------
# Process bunq policies
# ---------------------------------------------------------------------------
for f in "$POLICIES"/*.pdf; do
  [[ -f "$f" ]] || continue
  pol_total=$((pol_total + 1))
  if upload_file "$f" "policy" '["NL","DE","FR","UK","US","IE"]'; then
    pol_ok=$((pol_ok + 1))
  fi
done

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "=== SUMMARY ==="
echo "EU regulations:   $eu_ok/$eu_total"
echo "IE regulations:   $ie_ok/$ie_total"
echo "bunq policies:    $pol_ok/$pol_total"
echo "deduped: $deduped_count      failures: $fail_count"

[[ "$fail_count" -eq 0 ]] && exit 0 || exit 1
