"""
Export labelled evaluation fixtures from DynamoDB.
Run from the Bunq project root with:
  AWS_PROFILE=bunq AWS_REGION=eu-central-1 AWS_CA_BUNDLE=... py export_eval_fixtures.py
"""
import boto3
import json
import random
import sys
import os

SESSION_ID = "ba6c6744-2a4f-433c-813b-b9758be36f2a"
OUT_DIR = os.path.dirname(os.path.abspath(__file__))

# ── DynamoDB client ────────────────────────────────────────────────────────────
ca_bundle = os.environ.get("AWS_CA_BUNDLE")
session = boto3.Session(
    profile_name=os.environ.get("AWS_PROFILE", "bunq"),
    region_name=os.environ.get("AWS_REGION", "eu-central-1"),
)
ddb = session.resource("dynamodb", verify=ca_bundle if ca_bundle else True)
client = session.client("dynamodb", verify=ca_bundle if ca_bundle else True)


def ddb_val(v):
    """Unwrap a DynamoDB typed value dict."""
    if "S" in v: return v["S"]
    if "N" in v: return v["N"]
    if "BOOL" in v: return v["BOOL"]
    if "NULL" in v: return None
    if "L" in v: return [ddb_val(x) for x in v["L"]]
    if "M" in v: return {k: ddb_val(vv) for k, vv in v["M"].items()}
    if "SS" in v: return list(v["SS"])
    if "NS" in v: return list(v["NS"])
    return v


def query_session(table_name, session_id):
    """Query all items for a session via session-id-index GSI."""
    paginator = client.get_paginator("query")
    pages = paginator.paginate(
        TableName=table_name,
        IndexName="session-id-index",
        KeyConditionExpression="session_id = :s",
        ExpressionAttributeValues={":s": {"S": session_id}},
    )
    items = []
    for page in pages:
        for raw in page["Items"]:
            items.append({k: ddb_val(v) for k, v in raw.items()})
    return items


def get_item_by_id(table_name, item_id):
    """Fetch a single item by primary key 'id'."""
    resp = client.get_item(
        TableName=table_name,
        Key={"id": {"S": item_id}},
    )
    raw = resp.get("Item")
    if not raw:
        return None
    return {k: ddb_val(v) for k, v in raw.items()}


# ══════════════════════════════════════════════════════════════════════════════
# TASK 1 — Export ALL controls for the session
# ══════════════════════════════════════════════════════════════════════════════
print("Fetching controls...", flush=True)
all_controls_raw = query_session("launchlens-controls", SESSION_ID)
print(f"  Raw controls fetched: {len(all_controls_raw)}", flush=True)

controls = []
for c in all_controls_raw:
    controls.append({
        "id": c.get("id"),
        "description": c.get("description"),
        "mappedStandards": c.get("mappedStandards"),
        "owner": c.get("owner"),
        "controlType": c.get("controlType"),
        "category": c.get("category"),
    })

controls_path = os.path.join(OUT_DIR, "controls.json")
with open(controls_path, "w", encoding="utf-8") as f:
    json.dump(controls, f, indent=2, ensure_ascii=False)
print(f"  Written: {controls_path} ({len(controls)} controls)", flush=True)

# Build description lookup for hint-matching
# id → description (lower), description → id
desc_to_id = {}
for c in controls:
    if c.get("description"):
        desc_to_id[c["description"].lower()] = c["id"]


def resolve_control_hint(hint: str):
    """Best-effort fuzzy match on hint keywords against control descriptions."""
    hint_lower = hint.lower()
    # Try to find a control whose description contains key terms from the hint
    hint_words = [w for w in hint_lower.split() if len(w) > 4]
    best_id = None
    best_score = 0
    for c in controls:
        desc = (c.get("description") or "").lower()
        score = sum(1 for w in hint_words if w in desc)
        if score > best_score:
            best_score = score
            best_id = c["id"]
    if best_score >= 2:
        return best_id, best_score
    return None, 0


# ══════════════════════════════════════════════════════════════════════════════
# TASK 2 — Export labelled obligation sample
# ══════════════════════════════════════════════════════════════════════════════

def fetch_obligation(obl_id):
    item = get_item_by_id("launchlens-obligations", obl_id)
    if not item:
        return None
    source = item.get("source") or {}
    return {
        "id": item.get("id"),
        "subject": item.get("subject"),
        "action": item.get("action"),
        "conditions": item.get("conditions"),
        "riskCategory": item.get("riskCategory"),
        "deontic": item.get("deontic"),
        "source": {
            "regulation": source.get("regulation") if isinstance(source, dict) else None,
            "article": source.get("article") if isinstance(source, dict) else None,
            "section": source.get("section") if isinstance(source, dict) else None,
            "sourceText": source.get("sourceText") if isinstance(source, dict) else None,
        },
    }


POSITIVES = [
    ("OBL-284e6c838b21bf12b3aa9471", "annual PEP/RCA re-review under EDD (adverse media + txn behaviour)"),
    ("OBL-9195d73555c491951f2ee7fb", "in-house Dow Jones transaction screening at initiation (sanctions)"),
    ("OBL-5b13f9141742c8e00d034bc6", "daily Dow Jones download + phonetic screening of every user vs sanctions/EU/UN lists"),
    ("OBL-776467908fceed61e954b941", "PEP Procedure (CRO-owned) / Gitlab EDD template per client type"),
    ("OBL-f37e4233c0af4e5ccd9d21e1", "Establishment or continuation of any relationship with a PEP requires formal approval from the Head of Compliance"),
    ("OBL-917b68ad3469eeeaf6df38f6", "multi-layer corporate structure — independent source verification on every entity to identify UBOs"),
    ("OBL-91a78a63215663af021440df", "mandatory video biometric verification + ID-copy collection from directors/representatives before onboarding"),
    ("OBL-3644cab0124f9e93d8a86108", "mandatory manual analyst assessment before any FIU report (report quality)"),
    ("OBL-6f6a5ef6e7a1ba875efe97ea", "company representative / PoA verification, UBO structure mapping"),
    ("OBL-c8dba974babb09898e45497b", "dedicated Compliance/FinCrime function, four-eyes second-line review, escalation hierarchy"),
]

DELTA = [
    ("OBL-d8ceda25146666ff10bb3d23", "10-year retention required (bunq policy says 5 years) — resolve to bunq's retention control id", "retention"),
]

NEGATIVES = [
    "OBL-f2e638cbe78a9b4a1e1e002c",
    "OBL-9994bd62a5abc50e7c3b986f",
    "OBL-751f7dd180332cf5e6502671",
    "OBL-0685a6c51a0ef67ab5278d0a",
    "OBL-e8749cbcfefe8e1c7c13fae5",
    "OBL-2a05fb6775a87e38e40406e4",
    "OBL-6cde4527424affcb3de47cc9",
    "OBL-9abe02f2eb97bcaab2cbecf3",
    "OBL-3802e6838cfd6b87338f9f82",
    "OBL-c08b83a8fc746b161859c439",
    "OBL-6286df4253fd8badc4a1eaec",
]

labelled = []
resolution_table = []  # For summary report
not_found = []

print("\nFetching POSITIVES...", flush=True)
for obl_id, hint in POSITIVES:
    obl = fetch_obligation(obl_id)
    if not obl:
        print(f"  NOT FOUND: {obl_id}", flush=True)
        not_found.append(obl_id)
        continue
    ctrl_id, score = resolve_control_hint(hint)
    note = None if ctrl_id else f"No control description matched hint keywords with score>=2 (best_score={score})"
    labelled.append({
        **obl,
        "label": "should_be_covered",
        "expected_control_id": ctrl_id,
        "note": note,
    })
    resolution_table.append((obl_id, hint, ctrl_id, score, note))
    status = ctrl_id if ctrl_id else "UNRESOLVED"
    print(f"  {obl_id} -> {status} (score={score})", flush=True)

print("\nFetching DELTA...", flush=True)
# Retention control is known: CTRL-1770b91075500fa5f41bf09b
# "Retention of all FIU-reported unusual transaction records for five years..."
RETENTION_CTRL_ID = "CTRL-1770b91075500fa5f41bf09b"
for obl_id, hint, keyword in DELTA:
    obl = fetch_obligation(obl_id)
    if not obl:
        print(f"  NOT FOUND: {obl_id}", flush=True)
        not_found.append(obl_id)
        continue
    # Verify the control exists in our export
    retention_control = next((c for c in controls if c["id"] == RETENTION_CTRL_ID), None)
    ctrl_id = RETENTION_CTRL_ID if retention_control else None
    score = 99  # Manually resolved
    note = "jurisdiction_delta: Italy requires 10yr retention; bunq control covers 5yr (FIU records only)"
    if not ctrl_id:
        note += "; WARNING: retention control not found in export"
    labelled.append({
        **obl,
        "label": "jurisdiction_delta",
        "expected_control_id": ctrl_id,
        "note": note,
    })
    resolution_table.append((obl_id, hint, ctrl_id, score, note))
    print(f"  {obl_id} -> {ctrl_id or 'UNRESOLVED'} (manual retention match)", flush=True)

print("\nFetching NEGATIVES...", flush=True)
for obl_id in NEGATIVES:
    obl = fetch_obligation(obl_id)
    if not obl:
        print(f"  NOT FOUND: {obl_id}", flush=True)
        not_found.append(obl_id)
        continue
    labelled.append({
        **obl,
        "label": "should_stay_missing",
        "expected_control_id": None,
        "note": "Genuinely Italy-specific obligation; no bunq control exists",
    })
    print(f"  {obl_id} -> null (negative)", flush=True)


# ══════════════════════════════════════════════════════════════════════════════
# TASK 3 — Random obligations spread across risk_category values
# ══════════════════════════════════════════════════════════════════════════════
print("\nFetching random obligations...", flush=True)

# Get all obligation ids in the session (just key attributes to save read capacity)
# We'll use a scan with a projection to get ids + riskCategory quickly
all_obl_ids_by_cat = {}

paginator = client.get_paginator("query")
pages = paginator.paginate(
    TableName="launchlens-obligations",
    IndexName="session-id-index",
    KeyConditionExpression="session_id = :s",
    ExpressionAttributeValues={":s": {"S": SESSION_ID}},
    ProjectionExpression="id, riskCategory",
)
for page in pages:
    for raw in page["Items"]:
        item_id = ddb_val(raw["id"])
        cat = ddb_val(raw.get("riskCategory", {"S": "UNKNOWN"})) if "riskCategory" in raw else "UNKNOWN"
        all_obl_ids_by_cat.setdefault(cat, []).append(item_id)

print(f"  riskCategory distribution: { {k: len(v) for k, v in all_obl_ids_by_cat.items()} }", flush=True)

# Already-selected ids (avoid duplicates)
selected_ids = {e["id"] for e in labelled if e.get("id")}

# Pick ~2-3 per category, aiming for ~15 total
TARGET = 15
random_ids = []
cats = list(all_obl_ids_by_cat.keys())
random.seed(42)  # Reproducible
per_cat = max(1, TARGET // max(len(cats), 1))

for cat in cats:
    candidates = [i for i in all_obl_ids_by_cat[cat] if i not in selected_ids]
    pick = random.sample(candidates, min(per_cat, len(candidates)))
    random_ids.extend(pick)

# Trim or top-up to TARGET
random.shuffle(random_ids)
random_ids = random_ids[:TARGET]

print(f"  Sampling {len(random_ids)} random obligations...", flush=True)
random_count = 0
for obl_id in random_ids:
    obl = fetch_obligation(obl_id)
    if not obl:
        print(f"  NOT FOUND (random): {obl_id}", flush=True)
        continue
    labelled.append({
        **obl,
        "label": "random",
        "expected_control_id": None,
        "note": None,
    })
    random_count += 1

print(f"  Random obligations added: {random_count}", flush=True)

# ── Write obligations_labelled.json ──────────────────────────────────────────
obl_path = os.path.join(OUT_DIR, "obligations_labelled.json")
with open(obl_path, "w", encoding="utf-8") as f:
    json.dump(labelled, f, indent=2, ensure_ascii=False)
print(f"\nWritten: {obl_path} ({len(labelled)} obligations)", flush=True)

# ── Summary ───────────────────────────────────────────────────────────────────
positives_in  = [x for x in labelled if x["label"] == "should_be_covered"]
deltas_in     = [x for x in labelled if x["label"] == "jurisdiction_delta"]
negatives_in  = [x for x in labelled if x["label"] == "should_stay_missing"]
randoms_in    = [x for x in labelled if x["label"] == "random"]

print("\n═══ SUMMARY ═══")
print(f"controls.json          : {len(controls)} items")
print(f"obligations_labelled   : {len(labelled)} total")
print(f"  positives            : {len(positives_in)}")
print(f"  delta                : {len(deltas_in)}")
print(f"  negatives            : {len(negatives_in)}")
print(f"  random               : {len(randoms_in)}")
if not_found:
    print(f"  NOT FOUND ids        : {not_found}")

print("\n═══ POSITIVE RESOLUTION TABLE ═══")
print(f"{'Obligation ID':<40} {'Score':>5}  {'Control ID / Status'}")
print("-" * 100)
for obl_id, hint, ctrl_id, score, note in resolution_table:
    status = ctrl_id if ctrl_id else "UNRESOLVED"
    print(f"{obl_id:<40} {score:>5}  {status}")
    if note and not ctrl_id:
        print(f"  note: {note}")
    hint_safe = hint.encode("ascii", "replace").decode("ascii")
    print(f"  hint: {hint_safe}")
