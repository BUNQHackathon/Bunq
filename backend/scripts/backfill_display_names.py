#!/usr/bin/env python3
"""
backfill_display_names.py
Reads backend/seed/document-titles.yaml and sets display_name on each
launchlens-documents DDB row matched by filename.

Usage:
    python backend/scripts/backfill_display_names.py
    DRY_RUN=1 python backend/scripts/backfill_display_names.py
"""

import os
import sys
from pathlib import Path

import boto3
import yaml

TABLE = "launchlens-documents"
REGION = "eu-central-1"
DRY_RUN = os.environ.get("DRY_RUN", "0") == "1"

SCRIPT_DIR = Path(__file__).parent
YAML_FILE = SCRIPT_DIR.parent / "seed" / "document-titles.yaml"


def load_yaml_entries():
    with open(YAML_FILE, encoding="utf-8") as f:
        data = yaml.safe_load(f)
    return data.get("documents", [])


def build_filename_map(table):
    """Single full-table scan to build {filename: id} map.

    Uses the high-level boto3 Table.scan() which returns plain Python types
    (str, not {'S': str}), with automatic pagination via LastEvaluatedKey.
    """
    filename_map = {}
    kwargs = {"ProjectionExpression": "id, filename"}
    while True:
        response = table.scan(**kwargs)
        for item in response.get("Items", []):
            fn = item.get("filename")
            doc_id = item.get("id")
            if fn and doc_id:
                filename_map[fn] = doc_id
        last = response.get("LastEvaluatedKey")
        if not last:
            break
        kwargs["ExclusiveStartKey"] = last
    return filename_map


def main():
    entries = load_yaml_entries()

    dynamodb = boto3.resource("dynamodb", region_name=REGION)
    table = dynamodb.Table(TABLE)

    print(f"Building filename->id map from {TABLE} (single scan)...")
    filename_map = build_filename_map(table)
    print(f"  {len(filename_map)} documents found in DDB.\n")

    matched = skipped = failed = 0

    for entry in entries:
        filename = entry.get("filename", "").strip()
        title = entry.get("title", "").strip()

        if not filename:
            continue

        doc_id = filename_map.get(filename)

        if doc_id is None:
            print(f"[SKIP] {filename} (not found in DDB)")
            skipped += 1
            continue

        if DRY_RUN:
            print(f"[DRY-RUN] Would update id={doc_id} | {filename} -> {title}")
            matched += 1
            continue

        try:
            table.update_item(
                Key={"id": doc_id},
                UpdateExpression="SET display_name = :n",
                ExpressionAttributeValues={":n": title},
            )
            print(f"[OK] {filename} -> {title}")
            matched += 1
        except Exception as exc:
            short = str(exc).split("\n")[0][:120]
            print(f"[FAIL] {filename} err={short}")
            failed += 1

    print()
    print("--- Summary ---")
    print(f"Matched/updated      : {matched}")
    print(f"Skipped (not in DDB) : {skipped}")
    print(f"Failed               : {failed}")

    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
