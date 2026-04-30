#!/usr/bin/env bash
# Full local backup of DynamoDB + S3 before account deletion.
# Run from the backup/ directory.
# Usage: ./backup_all.sh [aws-profile]

set -e

PROFILE="${1:-default}"
REGION="eu-central-1"

S3_BUCKETS=(
  "launchlens-uploads"
  "launchlens-kb-regulations"
  "launchlens-kb-policies"
  "launchlens-kb-controls"
)

echo "========================================"
echo "LaunchLens backup"
echo "Profile : $PROFILE"
echo "Region  : $REGION"
echo "========================================"

# ── DynamoDB ─────────────────────────────────────────────────────────────────
echo ""
echo "=== DynamoDB ==="
python backup_dynamodb.py --profile "$PROFILE" --region "$REGION" --out-dir dynamodb_backup

# ── S3 ───────────────────────────────────────────────────────────────────────
echo ""
echo "=== S3 ==="
mkdir -p s3_backup

for BUCKET in "${S3_BUCKETS[@]}"; do
  echo ""
  echo "  Syncing s3://$BUCKET -> s3_backup/$BUCKET ..."
  # Check bucket exists first
  if aws s3api head-bucket --bucket "$BUCKET" --profile "$PROFILE" 2>/dev/null; then
    aws s3 sync "s3://$BUCKET" "s3_backup/$BUCKET" \
      --profile "$PROFILE" \
      --region "$REGION" \
      --no-progress
    echo "  Done: s3_backup/$BUCKET"
  else
    echo "  Bucket $BUCKET not found, skipping."
  fi
done

echo ""
echo "========================================"
echo "Backup complete!"
echo "  DynamoDB -> $(pwd)/dynamodb_backup/"
echo "  S3       -> $(pwd)/s3_backup/"
echo "========================================"
