#!/usr/bin/env python3
"""
parse_nist_controls.py — Parse NIST 800-53 rev5 PDF or XLSX and emit
a subset of controls as JSONL.

Usage:
    python scripts/parse_nist_controls.py \\
        --input java-backend/seed/controls/nist_800_53_rev5.pdf \\
        --output java-backend/seed/controls/nist_subset.jsonl \\
        [--limit 50]

Families extracted: AC, IA, SC, AU, SI.
Falls back to hand-curated fixture if parser extracts <30 rows.
"""

import argparse
import json
import logging
import re
import sys
from pathlib import Path

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger(__name__)

FALLBACK_PATH = Path(__file__).parent / "fixtures" / "nist_subset_fallback.jsonl"

# Families we want
TARGET_FAMILIES = {"AC", "IA", "SC", "AU", "SI"}

FAMILY_NAMES = {
    "AC": "Access Control",
    "IA": "Identification and Authentication",
    "SC": "System and Communications Protection",
    "AU": "Audit and Accountability",
    "SI": "System and Information Integrity",
}

# Category heuristics based on control title keywords
_PREVENTIVE_KW = {"prevent", "enforce", "protect", "control", "restrict", "deny", "encrypt", "manage", "establish"}
_DETECTIVE_KW = {"monitor", "detect", "audit", "log", "review", "alert", "report", "inspect"}
_CORRECTIVE_KW = {"remediat", "recover", "correct", "restor", "respond"}


def _infer_category(title: str, description: str) -> str:
    text = (title + " " + description).lower()
    for kw in _CORRECTIVE_KW:
        if kw in text:
            return "corrective"
    for kw in _DETECTIVE_KW:
        if kw in text:
            return "detective"
    return "preventive"


def _map_standards(family: str, control_id: str) -> list[str]:
    standards = ["NIST-800-53"]
    # GDPR-relevant families/controls
    if family in ("SC", "AU") or control_id in ("AC-5", "IA-2", "IA-5", "IA-12", "SI-12", "SI-19"):
        standards.append("GDPR")
    # ISO 27001 maps broadly
    standards.append("ISO27001")
    # PCI-DSS maps to auth and boundary controls
    if control_id in ("AC-7", "IA-6", "IA-11", "SC-7", "SC-8", "AU-14", "SC-45"):
        standards.append("PCI-DSS")
    return standards


# ---------------------------------------------------------------------------
# PDF parser
# ---------------------------------------------------------------------------
_CONTROL_ID_RE = re.compile(r"\b([A-Z]{2}-\d+(?:\(\d+\))?)\b")
_SECTION_HEADER_RE = re.compile(
    r"^([A-Z]{2}-\d+(?:\(\d+\))?)\s+([A-Z][A-Z ,/\-]+?)\s*$"
)


def _parse_pdf(path: Path, limit: int) -> list[dict]:
    try:
        from pypdf import PdfReader
    except ImportError:
        log.error("pypdf not installed. Run: pip install pypdf")
        return []

    log.info("Reading PDF: %s", path)
    reader = PdfReader(str(path))
    log.info("  %d pages", len(reader.pages))

    controls: dict[str, dict] = {}
    current_id: str | None = None
    current_title: str = ""
    desc_lines: list[str] = []

    def _flush():
        nonlocal current_id, current_title, desc_lines
        if current_id and current_id[:2] in TARGET_FAMILIES:
            family_code = current_id[:2]
            description = " ".join(desc_lines).strip()
            controls[current_id] = {
                "control_id": current_id,
                "family": FAMILY_NAMES.get(family_code, family_code),
                "title": current_title.strip().title(),
                "description": description[:500] if description else f"{current_title} control.",
                "category": _infer_category(current_title, description),
                "mapped_standards": _map_standards(family_code, current_id),
            }
        current_id = None
        current_title = ""
        desc_lines = []

    for page in reader.pages:
        text = page.extract_text() or ""
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue

            # Try to detect a control section header (e.g. "AC-2 ACCOUNT MANAGEMENT")
            m = re.match(r"^([A-Z]{2}-\d+(?:\(\d+\))?)\s{2,}(.+)$", line)
            if m and m.group(1)[:2] in TARGET_FAMILIES:
                _flush()
                current_id = m.group(1)
                current_title = m.group(2).strip()
                continue

            if current_id:
                # Skip page numbers, headers, footers
                if re.match(r"^\d+$", line) or "NIST SP 800-53" in line:
                    continue
                desc_lines.append(line)

    _flush()

    result = list(controls.values())
    log.info("PDF parser extracted %d controls from target families", len(result))

    if limit:
        result = result[:limit]
    return result


# ---------------------------------------------------------------------------
# XLSX parser
# ---------------------------------------------------------------------------

def _parse_xlsx(path: Path, limit: int) -> list[dict]:
    try:
        import openpyxl
    except ImportError:
        log.error("openpyxl not installed. Run: pip install openpyxl")
        return []

    log.info("Reading XLSX: %s", path)
    wb = openpyxl.load_workbook(str(path), read_only=True, data_only=True)

    # The NIST catalog XLSX has one sheet with columns including Control Identifier, Control Name, etc.
    controls = []
    for sheet_name in wb.sheetnames:
        ws = wb[sheet_name]
        rows = iter(ws.rows)
        header_row = next(rows, None)
        if header_row is None:
            continue
        headers = [str(c.value or "").strip().lower() for c in header_row]

        # Find column indices
        def col(name_fragments: list[str]) -> int:
            for i, h in enumerate(headers):
                if all(f in h for f in name_fragments):
                    return i
            return -1

        id_col = col(["control", "identifier"])
        if id_col == -1:
            id_col = col(["control id"])
        name_col = col(["control name"])
        if name_col == -1:
            name_col = col(["name"])
        desc_col = col(["control text"])
        if desc_col == -1:
            desc_col = col(["discussion"])
        if desc_col == -1:
            desc_col = col(["description"])

        if id_col == -1:
            continue

        for row in rows:
            cells = [c.value for c in row]
            raw_id = str(cells[id_col] or "").strip()
            if not raw_id or not re.match(r"^[A-Z]{2}-\d+", raw_id):
                continue
            family_code = raw_id[:2]
            if family_code not in TARGET_FAMILIES:
                continue

            title = str(cells[name_col] or "").strip() if name_col != -1 else ""
            description = str(cells[desc_col] or "").strip() if desc_col != -1 else ""

            controls.append({
                "control_id": raw_id,
                "family": FAMILY_NAMES.get(family_code, family_code),
                "title": title,
                "description": description[:500],
                "category": _infer_category(title, description),
                "mapped_standards": _map_standards(family_code, raw_id),
            })

            if limit and len(controls) >= limit:
                break

        if controls:
            break

    log.info("XLSX parser extracted %d controls from target families", len(controls))
    return controls


# ---------------------------------------------------------------------------
# Fallback
# ---------------------------------------------------------------------------

def _load_fallback(limit: int) -> list[dict]:
    if not FALLBACK_PATH.exists():
        log.error("Fallback JSONL not found: %s", FALLBACK_PATH)
        return []
    controls = []
    with FALLBACK_PATH.open() as f:
        for line in f:
            line = line.strip()
            if line:
                controls.append(json.loads(line))
    if limit:
        controls = controls[:limit]
    log.info("Loaded %d controls from fallback fixture.", len(controls))
    return controls


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Parse NIST 800-53 rev5 PDF/XLSX → JSONL subset."
    )
    parser.add_argument("--input", type=Path, required=True, help="PDF or XLSX input file.")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("java-backend/seed/controls/nist_subset.jsonl"),
        help="Output JSONL path.",
    )
    parser.add_argument(
        "--limit", type=int, default=50, help="Max controls to emit (default: 50)."
    )
    args = parser.parse_args()

    if not args.input.exists():
        log.error("Input file not found: %s", args.input)
        sys.exit(1)

    suffix = args.input.suffix.lower()
    if suffix == ".pdf":
        controls = _parse_pdf(args.input, args.limit)
    elif suffix in (".xlsx", ".xls"):
        controls = _parse_xlsx(args.input, args.limit)
    else:
        log.error("Unsupported file format: %s", suffix)
        sys.exit(1)

    # Fall back if we got too few rows
    if len(controls) < 30:
        log.info(
            "Parser extracted only %d controls (threshold: 30). Using fallback fixture.",
            len(controls),
        )
        controls = _load_fallback(args.limit)

    if not controls:
        log.error("No controls available (parser + fallback both empty). Aborting.")
        sys.exit(1)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w") as out:
        for control in controls:
            out.write(json.dumps(control, ensure_ascii=False) + "\n")

    log.info("Wrote %d controls to %s", len(controls), args.output)


if __name__ == "__main__":
    main()
