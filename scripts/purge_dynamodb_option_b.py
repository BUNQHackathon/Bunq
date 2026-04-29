"""
Option B purge: clear pipeline-derived DynamoDB rows so the next run
re-extracts under the new pipeline (B3 grounding, B8 reranker,
B1 temperature=0, B9 calibration anchors).

Keeps: documents (Textract cache), sanctions-entities, doc-jurisdictions.
Resets: documents.obligationsExtracted=false, documents.controlsExtracted=false.
Clears: mappings, gaps, sanctions-hits, evidence, audit-log, launches, sessions,
        jurisdiction-runs, chat-messages, obligations, controls.

Usage:
    python scripts/purge_dynamodb_option_b.py --dry-run
    python scripts/purge_dynamodb_option_b.py --apply
"""
import argparse
import sys
import time
from typing import Iterable

import boto3

REGION = "eu-central-1"
PREFIX = "launchlens"

# (logical_name, [key_attribute_names])
CLEAR_TABLES: list[tuple[str, list[str]]] = [
    ("mappings", ["id"]),
    ("gaps", ["id"]),
    ("sanctions-hits", ["id"]),
    ("evidence", ["id"]),
    ("audit-log", ["id"]),
    ("launches", ["id"]),
    ("sessions", ["id"]),
    ("jurisdiction-runs", ["launch_id", "jurisdiction_code"]),
    ("chat-messages", ["id"]),
    ("obligations", ["id"]),
    ("controls", ["id"]),
]

DOCUMENTS_TABLE = f"{PREFIX}-documents"


def scan_keys(client, table: str, key_attrs: list[str]) -> Iterable[dict]:
    """Yield key-only items for every row in the table, paging through results."""
    projection = ", ".join(f"#k{i}" for i in range(len(key_attrs)))
    expr_names = {f"#k{i}": name for i, name in enumerate(key_attrs)}
    kwargs = {
        "TableName": table,
        "ProjectionExpression": projection,
        "ExpressionAttributeNames": expr_names,
    }
    while True:
        resp = client.scan(**kwargs)
        for item in resp.get("Items", []):
            yield item
        last = resp.get("LastEvaluatedKey")
        if not last:
            return
        kwargs["ExclusiveStartKey"] = last


def batch_delete(client, table: str, keys: list[dict]) -> None:
    """Delete up to 25 items at a time via BatchWriteItem; retry unprocessed."""
    for i in range(0, len(keys), 25):
        batch = keys[i : i + 25]
        request = {
            table: [{"DeleteRequest": {"Key": k}} for k in batch]
        }
        attempt = 0
        while request:
            resp = client.batch_write_item(RequestItems=request)
            unprocessed = resp.get("UnprocessedItems") or {}
            if not unprocessed:
                break
            request = unprocessed
            attempt += 1
            if attempt > 5:
                raise RuntimeError(f"giving up on unprocessed for {table}")
            time.sleep(0.2 * attempt)


def clear_table(client, logical: str, key_attrs: list[str], apply: bool) -> int:
    name = f"{PREFIX}-{logical}"
    keys = list(scan_keys(client, name, key_attrs))
    print(f"  {name}: {len(keys)} rows", end="")
    if not keys:
        print(" (already empty)")
        return 0
    if not apply:
        print(" (dry run, would delete)")
        return len(keys)
    batch_delete(client, name, keys)
    print(" — deleted")
    return len(keys)


def reset_document_flags(client, apply: bool) -> int:
    keys = list(scan_keys(client, DOCUMENTS_TABLE, ["id"]))
    print(f"  {DOCUMENTS_TABLE}: {len(keys)} rows", end="")
    if not keys:
        print(" (no documents to reset)")
        return 0
    if not apply:
        print(" (dry run, would reset extracted flags)")
        return len(keys)
    for k in keys:
        client.update_item(
            TableName=DOCUMENTS_TABLE,
            Key=k,
            UpdateExpression="SET obligationsExtracted = :f, controlsExtracted = :f",
            ExpressionAttributeValues={":f": {"BOOL": False}},
        )
    print(" — flags reset")
    return len(keys)


def main() -> int:
    parser = argparse.ArgumentParser()
    g = parser.add_mutually_exclusive_group(required=True)
    g.add_argument("--dry-run", action="store_true")
    g.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    client = boto3.client("dynamodb", region_name=REGION)
    apply = args.apply

    print(f"Region: {REGION}, Prefix: {PREFIX}, Mode: {'APPLY' if apply else 'DRY-RUN'}")
    print("\nClearing run-derived tables:")
    total_cleared = 0
    for logical, keys in CLEAR_TABLES:
        try:
            total_cleared += clear_table(client, logical, keys, apply)
        except client.exceptions.ResourceNotFoundException:
            print(f"  {PREFIX}-{logical}: (table not found, skipping)")

    print("\nResetting document extraction flags:")
    docs_reset = reset_document_flags(client, apply)

    print(f"\nSummary: cleared {total_cleared} rows, reset {docs_reset} document flag(s)")
    if not apply:
        print("(dry run — re-run with --apply to execute)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
