#!/usr/bin/env bash
# backfill-display-names.sh
# Reads backend/seed/document-titles.yaml and sets display_name on each
# launchlens-documents DDB row matched by filename.
#
# Usage:
#   bash backend/scripts/backfill-display-names.sh
#   DRY_RUN=1 bash backend/scripts/backfill-display-names.sh
#
# Requirements: aws CLI, python3 (for YAML parsing via PyYAML or shyaml),
#               or yq (https://github.com/mikefarah/yq).

set -u -o pipefail
# NOTE: set -e intentionally omitted so a single bad row does not halt the run.

TABLE="launchlens-documents"
REGION="eu-central-1"
DRY_RUN="${DRY_RUN:-0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
YAML_FILE="${SCRIPT_DIR}/../seed/document-titles.yaml"

if [[ ! -f "$YAML_FILE" ]]; then
  echo "[ERROR] YAML file not found: $YAML_FILE" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Parse YAML with python3 (stdlib + optional PyYAML).
# Outputs tab-separated lines: filename\ttitle
# ---------------------------------------------------------------------------
parse_yaml() {
  python3 - "$YAML_FILE" <<'PYEOF'
import sys, json
yaml_file = sys.argv[1]

# Try PyYAML first, fall back to a minimal line-based parser.
try:
    import yaml
    with open(yaml_file) as f:
        data = yaml.safe_load(f)
    for entry in data.get("documents", []):
        print(f"{entry['filename']}\t{entry['title']}")
except ImportError:
    # Minimal fallback: assumes exactly the two-key-per-entry format in the YAML.
    filename = None
    with open(yaml_file) as f:
        for line in f:
            stripped = line.strip()
            if stripped.startswith("- filename:"):
                filename = stripped.split("- filename:", 1)[1].strip().strip('"')
            elif stripped.startswith("title:") and filename is not None:
                title = stripped.split("title:", 1)[1].strip().strip('"')
                print(f"{filename}\t{title}")
                filename = None
PYEOF
}

matched=0
skipped=0
failed=0

while IFS=$'\t' read -r filename title; do
  [[ -z "$filename" ]] && continue

  # Look up the document id by filename (full scan, no GSI — one-time operation).
  scan_result=$(aws dynamodb scan \
    --table-name "$TABLE" \
    --region "$REGION" \
    --filter-expression "filename = :f" \
    --expression-attribute-values "{\":f\":{\"S\":\"${filename}\"}}" \
    --projection-expression "id" \
    --output json 2>&1)

  if [[ $? -ne 0 ]]; then
    echo "[FAIL] $filename — DDB scan error: $scan_result"
    ((failed++)) || true
    continue
  fi

  count=$(echo "$scan_result" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('Count',0))")
  if [[ "$count" -eq 0 ]]; then
    echo "[SKIP] $filename (not found in DDB)"
    ((skipped++)) || true
    continue
  fi

  doc_id=$(echo "$scan_result" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['Items'][0]['id']['S'])")

  if [[ "$DRY_RUN" == "1" ]]; then
    echo "[DRY-RUN] Would update id=${doc_id} | ${filename} -> ${title}"
    ((matched++)) || true
    continue
  fi

  # Build JSON via python to safely encode Unicode (em-dashes etc.)
  eav=$(python3 -c "import json,sys; print(json.dumps({':n':{'S':sys.argv[1]}}))" "$title")
  key_json=$(python3 -c "import json,sys; print(json.dumps({'id':{'S':sys.argv[1]}}))" "$doc_id")

  update_result=$(aws dynamodb update-item \
    --table-name "$TABLE" \
    --region "$REGION" \
    --key "$key_json" \
    --update-expression "SET display_name = :n" \
    --expression-attribute-values "$eav" \
    --output json 2>&1)

  if [[ $? -ne 0 ]]; then
    echo "[FAIL] $filename — UpdateItem error: $update_result"
    ((failed++)) || true
  else
    echo "[OK] $filename -> $title"
    ((matched++)) || true
  fi
done < <(parse_yaml)

echo ""
echo "--- Summary ---"
echo "Matched/updated : $matched"
echo "Skipped (not in DDB): $skipped"
echo "Failed          : $failed"
