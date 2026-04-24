#!/usr/bin/env python3
"""
normalize_sanctions.py — Download, parse, normalize sanctions lists and
optionally upload to S3 / seed DynamoDB.

Usage:
    python scripts/normalize_sanctions.py --source ofac_sdn \\
        [--input <dir>] [--output-jsonl <path>] \\
        [--upload-s3] [--seed-dynamo] [--dry-run] \\
        [--aws-region eu-central-1]

Only ofac_sdn is fully implemented for MVP.
Stubs exist for: ofac_cons, eu_cons, un, uk.
"""

import argparse
import csv
import json
import logging
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator

import requests

# Local normalization helper (no sidecar deps)
sys.path.insert(0, str(Path(__file__).parent))
from _normalize import normalize_name

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
CACHE_DIR = Path(__file__).parent / ".cache" / "ofac"

OFAC_SDN_URLS = {
    "sdn": "https://www.treasury.gov/ofac/downloads/sdn.csv",
    "add": "https://www.treasury.gov/ofac/downloads/add.csv",
    "alt": "https://www.treasury.gov/ofac/downloads/alt.csv",
}

DEFAULT_S3_SANCTIONS_BUCKET = os.environ.get("S3_SANCTIONS_BUCKET", "launchlens-sanctions")

# OFAC sdnType → canonical type
SDN_TYPE_MAP = {
    "individual": "individual",
    "entity": "company",
    "vessel": "organization",
    "aircraft": "organization",
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _download_or_cache(url: str, cache_path: Path) -> Path:
    """Return cache_path, downloading from url if not already cached."""
    if cache_path.exists():
        log.info("Cache hit: %s", cache_path)
        return cache_path
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    log.info("Downloading %s → %s", url, cache_path)
    resp = requests.get(url, timeout=60, stream=True)
    resp.raise_for_status()
    cache_path.write_bytes(resp.content)
    log.info("Saved %d bytes", len(resp.content))
    return cache_path


# ---------------------------------------------------------------------------
# OFAC SDN parser
# ---------------------------------------------------------------------------

# Column definitions (0-indexed) per OFAC CSV layout
# sdn.csv columns (34 total):
SDN_COLS = [
    "ent_num", "sdn_name", "sdn_type", "program", "title", "call_sign",
    "vess_type", "tonnage", "grt", "vess_flag", "vess_owner", "remarks",
]

# add.csv columns:
ADD_COLS = [
    "ent_num", "add_num", "address", "city", "country", "add_remarks",
]

# alt.csv columns:
ALT_COLS = [
    "ent_num", "alt_num", "alt_type", "alt_name", "alt_remarks",
]


def _parse_csv_no_header(path: Path, col_names: list[str]) -> list[dict]:
    """Parse a CSV that has NO header row, mapping columns by position."""
    rows = []
    with path.open(encoding="latin-1", newline="") as f:
        reader = csv.reader(f)
        for line in reader:
            # Strip trailing blank cells and whitespace
            line = [c.strip() for c in line]
            if not any(line):
                continue
            row = {}
            for i, col in enumerate(col_names):
                row[col] = line[i].strip() if i < len(line) else ""
            rows.append(row)
    return rows


def _parse_ofac_sdn(input_dir: Path | None) -> Iterator[dict]:
    """
    Download (or read from input_dir) the 3 OFAC SDN CSVs, join them,
    and yield canonical entity dicts.
    """
    version_ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    # Resolve file paths
    if input_dir:
        sdn_path = input_dir / "sdn.csv"
        add_path = input_dir / "add.csv"
        alt_path = input_dir / "alt.csv"
    else:
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        sdn_path = _download_or_cache(OFAC_SDN_URLS["sdn"], CACHE_DIR / "sdn.csv")
        add_path = _download_or_cache(OFAC_SDN_URLS["add"], CACHE_DIR / "add.csv")
        alt_path = _download_or_cache(OFAC_SDN_URLS["alt"], CACHE_DIR / "alt.csv")

    # Parse CSVs
    log.info("Parsing SDN main CSV: %s", sdn_path)
    sdn_rows = _parse_csv_no_header(sdn_path, SDN_COLS)
    log.info("  %d SDN rows", len(sdn_rows))

    log.info("Parsing SDN addresses CSV: %s", add_path)
    add_rows = _parse_csv_no_header(add_path, ADD_COLS)
    log.info("  %d address rows", len(add_rows))

    log.info("Parsing SDN alt-names CSV: %s", alt_path)
    alt_rows = _parse_csv_no_header(alt_path, ALT_COLS)
    log.info("  %d alt-name rows", len(alt_rows))

    # Build lookup maps
    # country: use the first address row per ent_num
    country_map: dict[str, str] = {}
    for row in add_rows:
        eid = row["ent_num"]
        if eid and eid not in country_map and row.get("country"):
            country_map[eid] = row["country"].upper()[:2]

    # aliases: collect alt_name per ent_num
    aliases_map: dict[str, list[str]] = {}
    for row in alt_rows:
        eid = row["ent_num"]
        if eid and row.get("alt_name"):
            aliases_map.setdefault(eid, []).append(row["alt_name"].strip())

    # Yield entities
    for row in sdn_rows:
        eid = row.get("ent_num", "").strip()
        name = row.get("sdn_name", "").strip()
        if not eid or not name or name == "-0-":
            continue

        sdn_type_raw = row.get("sdn_type", "").strip().lower()
        entity_type = SDN_TYPE_MAP.get(sdn_type_raw, "unknown")

        aliases = aliases_map.get(eid, [])
        country = country_map.get(eid, "")

        yield {
            "list_source": "OFAC_SDN",
            "entity_name": name,
            "entity_name_normalized": normalize_name(name),
            "aliases": aliases,
            "country": country,
            "type": entity_type,
            "list_entry_id": f"sdn-{eid}",
            "list_version_timestamp": version_ts,
        }


# ---------------------------------------------------------------------------
# Stub parsers for future sources
# ---------------------------------------------------------------------------

def _parse_ofac_cons(input_dir: Path | None) -> Iterator[dict]:
    raise NotImplementedError("ofac_cons not yet implemented")


def _parse_eu_cons(input_dir: Path | None) -> Iterator[dict]:
    raise NotImplementedError("eu_cons not yet implemented")


def _parse_un(input_dir: Path | None) -> Iterator[dict]:
    raise NotImplementedError("un not yet implemented")


def _parse_uk(input_dir: Path | None) -> Iterator[dict]:
    raise NotImplementedError("uk not yet implemented")


PARSERS = {
    "ofac_sdn": _parse_ofac_sdn,
    "ofac_cons": _parse_ofac_cons,
    "eu_cons": _parse_eu_cons,
    "un": _parse_un,
    "uk": _parse_uk,
}

# ---------------------------------------------------------------------------
# AWS actions
# ---------------------------------------------------------------------------

def _upload_s3(jsonl_path: Path, source: str, region: str) -> None:
    import boto3
    bucket = DEFAULT_S3_SANCTIONS_BUCKET
    key = f"{source}.jsonl"

    # Sanity-check bucket exists
    s3 = boto3.client("s3", region_name=region)
    try:
        s3.head_bucket(Bucket=bucket)
    except Exception:
        log.warning(
            "Bucket %s not found or not accessible — verify S3_SANCTIONS_BUCKET env var. "
            "Attempting upload anyway.",
            bucket,
        )

    log.info("Uploading %s → s3://%s/%s", jsonl_path, bucket, key)
    s3.upload_file(str(jsonl_path), bucket, key)
    log.info("Upload complete.")


def _seed_dynamo(jsonl_path: Path, region: str) -> None:
    import boto3
    table_name = "launchlens-sanctions-entities"
    dynamodb = boto3.resource("dynamodb", region_name=region)
    table = dynamodb.Table(table_name)

    log.info("Seeding DynamoDB table: %s", table_name)
    count = 0
    with table.batch_writer() as batch, jsonl_path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            entity = json.loads(line)
            item = {
                "id": f"{entity['list_source']}#{entity['list_entry_id']}",
                **entity,
            }
            batch.put_item(Item=item)
            count += 1
            if count % 1000 == 0:
                log.info("  Written %d items…", count)
    log.info("DynamoDB seed complete: %d items written.", count)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Normalize sanctions lists to canonical JSONL."
    )
    parser.add_argument(
        "--source",
        required=True,
        choices=list(PARSERS.keys()),
        help="Sanctions list source to process.",
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=None,
        help="Directory with pre-downloaded CSV/XML files (skips network download).",
    )
    parser.add_argument(
        "--output-jsonl",
        type=Path,
        default=None,
        help="Write canonical JSONL here (default: .cache/<source>.jsonl).",
    )
    parser.add_argument(
        "--upload-s3",
        action="store_true",
        help="Upload JSONL to S3 sanctions bucket.",
    )
    parser.add_argument(
        "--seed-dynamo",
        action="store_true",
        help="Batch-write entities to DynamoDB sanctions-entities table.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Parse and write local JSONL only — no AWS calls.",
    )
    parser.add_argument(
        "--aws-region",
        default="eu-central-1",
        help="AWS region (default: eu-central-1).",
    )
    args = parser.parse_args()

    # Resolve output path
    if args.output_jsonl:
        output_path = args.output_jsonl
    else:
        output_path = Path(__file__).parent / ".cache" / f"{args.source}.jsonl"
    output_path.parent.mkdir(parents=True, exist_ok=True)

    # Parse
    parse_fn = PARSERS[args.source]
    log.info("Parsing source: %s", args.source)
    count = 0
    with output_path.open("w") as out:
        for entity in parse_fn(args.input):
            out.write(json.dumps(entity, ensure_ascii=False) + "\n")
            count += 1
    log.info("Wrote %d entities to %s", count, output_path)

    if args.dry_run:
        log.info("--dry-run: skipping AWS actions.")
        return

    if args.upload_s3:
        _upload_s3(output_path, args.source, args.aws_region)

    if args.seed_dynamo:
        _seed_dynamo(output_path, args.aws_region)


if __name__ == "__main__":
    main()
