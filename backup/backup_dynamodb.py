#!/usr/bin/env python3
"""
DynamoDB full backup to local JSON files.
Usage: python backup_dynamodb.py [--profile PROFILE] [--region REGION]
"""
import boto3
import json
import os
import sys
import argparse
from decimal import Decimal
from datetime import datetime

TABLES = [
    "launchlens-sessions",
    "launchlens-documents",
    "launchlens-obligations",
    "launchlens-controls",
    "launchlens-mappings",
    "launchlens-gaps",
    "launchlens-sanctions-hits",
    "launchlens-sanctions-entities",
    "launchlens-evidence",
    "launchlens-audit-log",
    "launchlens-chat-messages",
]

def decimal_default(obj):
    if isinstance(obj, Decimal):
        return float(obj)
    raise TypeError(f"Object of type {type(obj)} is not JSON serializable")

def backup_table(client, table_name, out_dir):
    print(f"  Scanning {table_name}...", end=" ", flush=True)
    items = []
    kwargs = {"TableName": table_name}
    while True:
        resp = client.scan(**kwargs)
        items.extend(resp.get("Items", []))
        last = resp.get("LastEvaluatedKey")
        if not last:
            break
        kwargs["ExclusiveStartKey"] = last

    out_path = os.path.join(out_dir, f"{table_name}.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(items, f, default=decimal_default, ensure_ascii=False, indent=2)
    print(f"{len(items)} items -> {out_path}")
    return len(items)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", default="default")
    parser.add_argument("--region", default="eu-central-1")
    parser.add_argument("--out-dir", default="dynamodb_backup")
    args = parser.parse_args()

    out_dir = args.out_dir
    os.makedirs(out_dir, exist_ok=True)

    session = boto3.Session(profile_name=args.profile, region_name=args.region)
    client = session.client("dynamodb")

    # Check which tables actually exist
    existing = set(client.list_tables()["TableNames"])
    to_backup = [t for t in TABLES if t in existing]
    skipped = [t for t in TABLES if t not in existing]

    if skipped:
        print(f"Skipping (not found): {', '.join(skipped)}")

    print(f"\nBacking up {len(to_backup)} tables to ./{out_dir}/")
    total = 0
    for table in to_backup:
        total += backup_table(client, table, out_dir)

    print(f"\nDone. {total} total items backed up.")
    print(f"Backup directory: {os.path.abspath(out_dir)}")

if __name__ == "__main__":
    main()
