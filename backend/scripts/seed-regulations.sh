#!/usr/bin/env bash
# Usage: [BASE_URL=http://...] [PDF_DIR=path/to/pdfs] bash seed-regulations.sh [regulations.yaml]
# PDF_DIR: directory containing placeholder PDFs named by filename field. Missing PDFs are skipped.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
YAML="${1:-backend/seed/regulations.yaml}"
PDF_DIR="${PDF_DIR:-backend/seed/pdfs}"

# Parse yaml via python3 (expected on dev machines) into lines: id|filename|title|kind|jurisdiction1,jurisdiction2,...
entries=$(python3 - "$YAML" <<'PYEOF'
import sys, yaml, json

with open(sys.argv[1]) as f:
    doc = yaml.safe_load(f)

sections = []
for key in ("regulations", "policies", "controls"):
    items = doc.get(key) or []
    sections.extend(items)

for item in sections:
    jurs = ",".join(item.get("jurisdictions", []))
    title = item.get("title", "")
    print(f"{item['id']}|{item['filename']}|{title}|{item['kind']}|{jurs}")
PYEOF
)

# Pick sha256 utility
if command -v sha256sum >/dev/null 2>&1; then
  sha256_file() { sha256sum "$1" | awk '{print $1}'; }
else
  sha256_file() { shasum -a 256 "$1" | awk '{print $1}'; }
fi

total=0
seeded=0

while IFS='|' read -r id filename title kind jurisdictions; do
  total=$((total + 1))
  pdf="$PDF_DIR/$filename"

  if [[ ! -f "$pdf" ]]; then
    echo "WARN: missing PDF $filename — skipping (drop a placeholder PDF in $PDF_DIR/)"
    continue
  fi

  # Compute SHA-256 hex and base64
  hex=$(sha256_file "$pdf")
  b64=$(python3 -c "import binascii,base64,sys; print(base64.b64encode(binascii.unhexlify(sys.argv[1])).decode())" "$hex")

  # Build jurisdictions JSON array
  jurisdictions_json=$(echo "$jurisdictions" | tr ',' '\n' | sed 's/.*/"&"/' | paste -sd ',' -)

  # Step 1: presign
  presign_resp=$(curl -sS -X POST "$BASE_URL/documents/presign" \
    -H 'content-type: application/json' \
    -d "$(cat <<EOF
{"filename": "$filename", "contentType": "application/pdf"}
EOF
)")

  incoming_key=$(echo "$presign_resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['incomingKey'])")
  upload_url=$(echo "$presign_resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['uploadUrl'])")

  # Step 2: upload PDF bytes
  curl -sS -X PUT "$upload_url" \
    -H 'content-type: application/pdf' \
    -H "x-amz-sdk-checksum-algorithm: SHA256" \
    -H "x-amz-checksum-sha256: $b64" \
    --data-binary "@$pdf" > /dev/null

  # Step 3: finalize
  curl -sS -X POST "$BASE_URL/documents/finalize" \
    -H 'content-type: application/json' \
    -d "$(cat <<EOF
{
  "incomingKey": "$incoming_key",
  "filename": "$filename",
  "contentType": "application/pdf",
  "kind": "$kind",
  "jurisdictions": [$jurisdictions_json]
}
EOF
)" > /dev/null

  echo "OK: $id ($kind) — $filename"
  seeded=$((seeded + 1))
done <<< "$entries"

echo "Seeded $seeded of $total entries."
