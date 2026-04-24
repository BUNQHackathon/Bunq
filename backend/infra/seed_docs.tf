# Upload local seed documents to the 3 Bedrock KB source buckets.
#
# TODO: drop seed docs into java-backend/seed/{regulations,policies,controls}/
#   before running terraform apply. The upload is skipped if the directory
#   is empty (fileset returns nothing → for_each has zero entries).
#
# Directory layout expected:
#   java-backend/
#     seed/
#       regulations/   ← PDF or text files about regulations (GDPR, NIS2, etc.)
#       policies/      ← Company policy documents
#       controls/      ← Control framework documents (ISO 27001, SOC 2, etc.)

locals {
  seed_files = merge([
    for source in local.kb_sources : {
      for f in fileset("${path.module}/../seed/${source}", "**") :
      "${source}/${f}" => {
        source = source
        key    = f
        path   = "${path.module}/../seed/${source}/${f}"
      }
    }
  ]...)
}

resource "aws_s3_object" "seed_docs" {
  for_each = local.seed_files

  bucket = aws_s3_bucket.kb_sources[each.value.source].id
  key    = each.value.key
  source = each.value.path
  etag   = filemd5(each.value.path)

  # Let S3 choose content-type automatically based on file extension.
  # PDFs will be served as application/pdf; text files as text/plain.
}
