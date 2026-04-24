# S3 Vectors bucket + 3 indexes (GA Dec 2025).
# The hashicorp/aws provider v5.x has no native s3vectors resources yet,
# so we use null_resource + local-exec wrapping the AWS CLI v2.
# TODO verify against current CLI: aws s3vectors help

resource "null_resource" "s3_vectors_bucket" {
  triggers = {
    bucket_name = "${local.name_prefix}-vectors"
    region      = var.region
    profile     = var.aws_profile
  }

  provisioner "local-exec" {
    interpreter = ["powershell", "-NoProfile", "-Command"]
    # TODO verify: aws s3vectors create-vector-bucket --help
    command = <<-EOT
      aws s3vectors create-vector-bucket --vector-bucket-name ${local.name_prefix}-vectors --region ${var.region} --profile ${var.aws_profile}
    EOT
  }

  provisioner "local-exec" {
    when        = destroy
    interpreter = ["powershell", "-NoProfile", "-Command"]
    # TODO verify: aws s3vectors delete-vector-bucket --help
    command = <<-EOT
      try {
        aws s3vectors delete-vector-bucket --vector-bucket-name ${self.triggers.bucket_name} --region ${self.triggers.region} --profile ${self.triggers.profile}
      } catch { }
    EOT
  }
}

resource "null_resource" "s3_vectors_indexes" {
  for_each = toset(local.kb_sources)

  triggers = {
    bucket_name            = "${local.name_prefix}-vectors"
    index_name             = "${each.value}-idx"
    region                 = var.region
    profile                = var.aws_profile
    metadata_config_v      = "v2" # bump to force replace when metadata config changes
  }

  provisioner "local-exec" {
    interpreter = ["powershell", "-NoProfile", "-Command"]
    # Titan Embeddings v2 produces 1024-dimensional float32 vectors.
    # nonFilterableMetadataKeys keeps AMAZON_BEDROCK_TEXT_CHUNK out of the
    # 2048-byte filterable metadata limit imposed by S3 Vectors — required for
    # Bedrock KB ingestion of chunks containing more than ~2 KB of text.
    # JSON passed via temp file to bypass PowerShell native-exe quoting bug
    # (single-quoted JSON arrived at aws.exe with stripped inner double quotes).
    command = <<-EOT
      $tmp = New-TemporaryFile
      Set-Content -Path $tmp.FullName -Value '{"nonFilterableMetadataKeys":["AMAZON_BEDROCK_TEXT_CHUNK","AMAZON_BEDROCK_METADATA"]}' -Encoding ascii
      aws s3vectors create-index --vector-bucket-name ${local.name_prefix}-vectors --index-name ${each.value}-idx --data-type float32 --dimension 1024 --distance-metric cosine --metadata-configuration "file://$($tmp.FullName)" --region ${var.region} --profile ${var.aws_profile}
      Remove-Item $tmp.FullName
    EOT
  }

  provisioner "local-exec" {
    when        = destroy
    interpreter = ["powershell", "-NoProfile", "-Command"]
    # TODO verify: aws s3vectors delete-index --help
    command = <<-EOT
      try {
        aws s3vectors delete-index --vector-bucket-name ${self.triggers.bucket_name} --index-name ${self.triggers.index_name} --region ${self.triggers.region} --profile ${self.triggers.profile}
      } catch { }
    EOT
  }

  depends_on = [null_resource.s3_vectors_bucket]
}

# Separate inline policy granting the Bedrock KB role access to S3 Vectors.
# Added as a standalone resource to avoid editing iam.tf (surgical change rule).
# S3 Vectors IAM permissions moved into aws_iam_role.bedrock_kb.inline_policy
# in iam.tf because `inline_policy` on aws_iam_role exclusively manages all
# inline policies for the role — having a separate aws_iam_role_policy here
# made Terraform delete it on every apply.
