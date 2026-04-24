#!/usr/bin/env bash
# download_corpus.sh — Download MVP corpus files for LaunchLens Bedrock KB seeding.
#
# Usage:
#   bash scripts/download_corpus.sh [--force]
#
# Files land under java-backend/seed/{regulations,policies,controls}/
# Re-runnable: existing files are skipped unless --force is passed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# python-backend/scripts/ → go up two levels to get backend/ root
JAVA_BACKEND="$(cd "${SCRIPT_DIR}/../.." && pwd)/java-backend"
SEED_ROOT="${JAVA_BACKEND}/seed"

FORCE=false
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=true ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
done

REG_DIR="${SEED_ROOT}/regulations"
POL_DIR="${SEED_ROOT}/policies"
CTL_DIR="${SEED_ROOT}/controls"

mkdir -p "${REG_DIR}" "${POL_DIR}" "${CTL_DIR}"

# ---------------------------------------------------------------------------
# Helper: download file if not present (or --force)
# ---------------------------------------------------------------------------
download() {
  local url="$1"
  local dest="$2"
  local desc="${3:-$dest}"

  if [[ -f "${dest}" && "${FORCE}" == "false" ]]; then
    echo "[SKIP] Already present: ${dest}"
    return 0
  fi

  echo "[DOWNLOAD] ${desc}"
  echo "  URL: ${url}"
  if curl -fsSL --connect-timeout 30 --max-time 120 -o "${dest}" "${url}"; then
    local size
    size=$(wc -c < "${dest}")
    echo "  OK: ${size} bytes → ${dest}"
  else
    local exit_code=$?
    echo "[WARNING] Failed to download (exit ${exit_code}): ${url}"
    echo "          Manually place the file at: ${dest}"
    # Remove empty/partial file
    [[ -f "${dest}" ]] && rm -f "${dest}"
    return 0  # non-fatal
  fi
}

# ---------------------------------------------------------------------------
# Regulations
# ---------------------------------------------------------------------------
echo ""
echo "=== Regulations ==="

# GDPR — Eur-Lex official PDF (CELEX:32016R0679)
download \
  "https://eur-lex.europa.eu/legal-content/EN/TXT/PDF/?uri=CELEX:32016R0679" \
  "${REG_DIR}/gdpr.pdf" \
  "GDPR full text PDF (Eur-Lex)"

# ---------------------------------------------------------------------------
# Policies
# ---------------------------------------------------------------------------
echo ""
echo "=== Policies ==="

# bunq Terms & Conditions (personal account, English)
# NOTE: bunq does not publish a stable direct PDF URL. Try the known path.
# If this 404s, manually download from https://www.bunq.com/terms-and-conditions
# and place at: java-backend/seed/policies/bunq_tc.pdf
download \
  "https://www.bunq.com/assets/media/legal/en/20240408_Terms_and_Conditions_for_a_Personal_Account.pdf" \
  "${POL_DIR}/bunq_tc.pdf" \
  "bunq Terms & Conditions (personal account)"

# bunq Privacy Policy (English)
# If this 404s, manually download from https://www.bunq.com/terms-and-conditions
# (look for "Privacy Policy" section)
# NOTE: local PDFs already exist in seed/policies — this downloads the English version
download \
  "https://www.bunq.com/assets/media/legal/en/20240408_Privacy_Policy_for_Personal_Account.pdf" \
  "${POL_DIR}/bunq_privacy.pdf" \
  "bunq Privacy Policy"

# Starling Bank personal account terms (known stable PDF)
download \
  "https://www.starlingbank.com/docs/legal/terms/starling-personal-account-general-terms-feb-2025.pdf" \
  "${POL_DIR}/starling_tc.pdf" \
  "Starling Bank T&C (personal)"

# Wise Global Privacy Policy (known stable PDF)
download \
  "https://wise.com/imaginary-v2/images/6d342507cae53950a5f700d4af349d19-GlobalPrivacyPolicy-Wise-Eng.pdf" \
  "${POL_DIR}/wise_privacy.pdf" \
  "Wise Global Privacy Policy"

# ---------------------------------------------------------------------------
# Controls
# ---------------------------------------------------------------------------
echo ""
echo "=== Controls ==="

# NIST 800-53 rev5 PDF (always available from NIST)
download \
  "https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-53r5.pdf" \
  "${CTL_DIR}/nist_800_53_rev5.pdf" \
  "NIST SP 800-53 rev5 PDF"

# NIST 800-53 rev5 XLSX control catalog (direct link; may rotate — use PDF as primary)
# Place at controls/nist_800_53_rev5.xlsx if XLSX parsing preferred over PDF
download \
  "https://csrc.nist.gov/files/pubs/sp/800/53/r5/upd1/final/docs/sp800-53r5-control-catalog.xlsx" \
  "${CTL_DIR}/nist_800_53_rev5.xlsx" \
  "NIST SP 800-53 rev5 control catalog XLSX"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "=== Downloaded files ==="
find "${SEED_ROOT}" -type f \( -name "*.pdf" -o -name "*.html" -o -name "*.xlsx" \) \
  -exec ls -lh {} \; 2>/dev/null | awk '{print $5, $9}' | sort

echo ""
echo "Done. Run 'cd java-backend/infra && terraform apply' to ingest files into Bedrock KBs."
