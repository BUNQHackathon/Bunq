# 3 Bedrock Knowledge Bases, each backed by an S3 Vectors index.
#
# The aws_bedrockagent_knowledge_base resource exists in hashicorp/aws, but
# the S3_VECTORS storageConfiguration type may not yet be supported.
# We use null_resource + local-exec for KB creation and data source attachment,
# which is fully destroy-able. When provider support lands, replace with native
# resources (no state migration needed — just import).
#
# TODO verify: aws bedrock-agent create-knowledge-base --help
# TODO verify: aws bedrock-agent create-data-source --help
# TODO verify storageConfiguration JSON shape against:
#   https://docs.aws.amazon.com/cli/latest/reference/bedrock-agent/create-knowledge-base.html

locals {
  # Titan Embeddings v2 in eu-central-1 (foundation model, not inference profile).
  embedding_model_arn = "arn:aws:bedrock:${var.bedrock_region}::foundation-model/amazon.titan-embed-text-v2:0"
  vector_bucket_name  = "${local.name_prefix}-vectors"
}

# Fallback mapping — tainting null_resource.bedrock_kb requires manually refreshing these from the create-knowledge-base output.
locals {
  kb_ids_static = {
    regulations = "BCOYACZ7LK"
    policies    = "NFPCFPSOMG"
    controls    = "LGQ4D4V4FC"
  }
}

# ── Knowledge Bases (one per kb_source) ─────────────────────────────────────

resource "null_resource" "bedrock_kb" {
  for_each = toset(local.kb_sources)

  triggers = {
    # Store all values needed by destroy provisioner (self.triggers only).
    kb_name            = "${local.name_prefix}-${each.value}"
    kb_source          = each.value
    role_arn           = aws_iam_role.bedrock_kb.arn
    embedding_model    = local.embedding_model_arn
    vector_bucket_arn  = "arn:aws:s3vectors:${var.region}:${data.aws_caller_identity.current.account_id}:bucket/${local.name_prefix}-vectors"
    vector_bucket_name = "${local.name_prefix}-vectors"
    index_name         = "${each.value}-idx"
    region             = var.bedrock_region
    profile            = var.aws_profile
    account_id         = data.aws_caller_identity.current.account_id
  }

  provisioner "local-exec" {
    interpreter = ["powershell", "-NoProfile", "-Command"]
    # JSON configs go via temp files to bypass PowerShell native-exe quoting bug
    # (inline `\"key\":\"value\"` strings arrive at aws.exe with stripped/mangled quotes).
    command     = <<-EOT
      $ErrorActionPreference = 'Stop'

      $KB_CONFIG = New-TemporaryFile
      Set-Content -Path $KB_CONFIG.FullName -Value '{"type":"VECTOR","vectorKnowledgeBaseConfiguration":{"embeddingModelArn":"${local.embedding_model_arn}","embeddingModelConfiguration":{"bedrockEmbeddingModelConfiguration":{"dimensions":1024,"embeddingDataType":"FLOAT32"}}}}' -Encoding ascii

      $STORAGE_CONFIG = New-TemporaryFile
      Set-Content -Path $STORAGE_CONFIG.FullName -Value '{"type":"S3_VECTORS","s3VectorsConfiguration":{"vectorBucketArn":"arn:aws:s3vectors:${var.region}:${data.aws_caller_identity.current.account_id}:bucket/${local.name_prefix}-vectors","indexName":"${each.value}-idx"}}' -Encoding ascii

      $KB_ID = aws bedrock-agent create-knowledge-base --name "${local.name_prefix}-${each.value}" --role-arn "${aws_iam_role.bedrock_kb.arn}" --knowledge-base-configuration "file://$($KB_CONFIG.FullName)" --storage-configuration "file://$($STORAGE_CONFIG.FullName)" --region ${var.bedrock_region} --profile ${var.aws_profile} --query 'knowledgeBase.knowledgeBaseId' --output text

      Remove-Item -Force $KB_CONFIG.FullName, $STORAGE_CONFIG.FullName

      if (-not $KB_ID -or $KB_ID -eq 'None' -or $LASTEXITCODE -ne 0) {
        throw "create-knowledge-base failed for ${each.value}: KB_ID=$KB_ID exit=$LASTEXITCODE"
      }

      Set-Content -Path "$env:TEMP\kb_id_${each.value}.txt" -Value $KB_ID -Encoding ascii
      Write-Host "Created KB ${each.value}: $KB_ID"
    EOT
  }

  provisioner "local-exec" {
    when        = destroy
    interpreter = ["powershell", "-NoProfile", "-Command"]
    # Destroy provisioners may only reference self.triggers.* — no other resources.
    command = <<-EOT
      $KB_FILE = "$env:TEMP\kb_id_${self.triggers.kb_source}.txt"
      if (Test-Path $KB_FILE) {
        $KB_ID = Get-Content $KB_FILE
        try {
          aws bedrock-agent delete-knowledge-base --knowledge-base-id "$KB_ID" --region "${self.triggers.region}" --profile "${self.triggers.profile}"
        } catch { }
        Remove-Item -Force $KB_FILE
      }
    EOT
  }

  depends_on = [
    null_resource.s3_vectors_indexes,
    aws_iam_role.bedrock_kb
  ]
}

# ── Data Sources (one per kb_source, pointing at the S3 KB bucket) ──────────

resource "null_resource" "bedrock_data_source" {
  for_each = toset(local.kb_sources)

  triggers = {
    kb_source  = each.value
    region     = var.bedrock_region
    profile    = var.aws_profile
    bucket_arn = aws_s3_bucket.kb_sources[each.value].arn
    kb_id      = null_resource.bedrock_kb[each.value].id
  }

  provisioner "local-exec" {
    interpreter = ["powershell", "-NoProfile", "-Command"]
    # JSON config via temp file — same quoting fix as bedrock_kb resource above.
    command     = <<-EOT
      $ErrorActionPreference = 'Stop'

      $KB_ID = Get-Content "$env:TEMP\kb_id_${each.value}.txt"
      if (-not $KB_ID) { throw "kb_id_${each.value}.txt is empty — KB creation likely failed" }

      $DS_CONFIG = New-TemporaryFile
      Set-Content -Path $DS_CONFIG.FullName -Value '{"type":"S3","s3Configuration":{"bucketArn":"${aws_s3_bucket.kb_sources[each.value].arn}"}}' -Encoding ascii

      $DS_ID = aws bedrock-agent create-data-source --knowledge-base-id "$KB_ID" --name "${local.name_prefix}-${each.value}-source" --data-source-configuration "file://$($DS_CONFIG.FullName)" --region ${var.bedrock_region} --profile ${var.aws_profile} --query 'dataSource.dataSourceId' --output text

      Remove-Item -Force $DS_CONFIG.FullName

      if (-not $DS_ID -or $DS_ID -eq 'None' -or $LASTEXITCODE -ne 0) {
        throw "create-data-source failed for ${each.value}: DS_ID=$DS_ID exit=$LASTEXITCODE"
      }

      Set-Content -Path "$env:TEMP\ds_id_${each.value}.txt" -Value $DS_ID -Encoding ascii
      Write-Host "Created data source ${each.value}: $DS_ID"
    EOT
  }

  provisioner "local-exec" {
    when        = destroy
    interpreter = ["powershell", "-NoProfile", "-Command"]
    # Destroy provisioners may only reference self.triggers.* — no other resources.
    command = <<-EOT
      $KB_FILE = "$env:TEMP\kb_id_${self.triggers.kb_source}.txt"
      $DS_FILE = "$env:TEMP\ds_id_${self.triggers.kb_source}.txt"
      if ((Test-Path $KB_FILE) -and (Test-Path $DS_FILE)) {
        $KB_ID = Get-Content $KB_FILE
        $DS_ID = Get-Content $DS_FILE
        try {
          aws bedrock-agent delete-data-source --knowledge-base-id "$KB_ID" --data-source-id "$DS_ID" --region "${self.triggers.region}" --profile "${self.triggers.profile}"
        } catch { }
        Remove-Item -Force $DS_FILE
      }
    EOT
  }

  depends_on = [null_resource.bedrock_kb]
}

# ── Ingestion trigger (start-ingestion-job on initial apply) ─────────────────

resource "null_resource" "kb_ingestion" {
  for_each = toset(local.kb_sources)

  triggers = {
    # Re-ingest when the data source provisioner re-runs.
    # For manual re-ingestion: terraform taint null_resource.kb_ingestion["<source>"]
    ds_id = null_resource.bedrock_data_source[each.value].id
  }

  provisioner "local-exec" {
    interpreter = ["powershell", "-NoProfile", "-Command"]
    command     = <<-EOT
      $ErrorActionPreference = 'Stop'

      $KB_ID = Get-Content "$env:TEMP\kb_id_${each.value}.txt"
      $DS_ID = Get-Content "$env:TEMP\ds_id_${each.value}.txt"

      aws bedrock-agent start-ingestion-job --knowledge-base-id "$KB_ID" --data-source-id "$DS_ID" --region ${var.bedrock_region} --profile ${var.aws_profile}

      Write-Host "Ingestion job started for KB ${each.value}: $KB_ID / $DS_ID"
    EOT
  }

  # No destroy provisioner — ingestion jobs cannot be deleted.

  depends_on = [null_resource.bedrock_data_source]
}
