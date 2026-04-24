moved {
  from = aws_dynamodb_table.this["sessions"]
  to   = aws_dynamodb_table.sessions
}

resource "aws_dynamodb_table" "this" {
  for_each     = toset([for t in local.dynamodb_tables : t if !contains(["audit-log", "obligations", "controls"], t)])
  name         = "${local.name_prefix}-${each.value}"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }
}

resource "aws_dynamodb_table" "obligations" {
  name         = "${local.name_prefix}-obligations"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }

  attribute {
    name = "document_id"
    type = "S"
  }

  global_secondary_index {
    name            = "document-id-index"
    hash_key        = "document_id"
    projection_type = "ALL"
  }
}

resource "aws_dynamodb_table" "controls" {
  name         = "${local.name_prefix}-controls"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }

  attribute {
    name = "document_id"
    type = "S"
  }

  global_secondary_index {
    name            = "document-id-index"
    hash_key        = "document_id"
    projection_type = "ALL"
  }
}

resource "aws_dynamodb_table" "documents" {
  name         = "${local.name_prefix}-documents"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }

  attribute {
    name = "kind"
    type = "S"
  }

  attribute {
    name = "last_used_at"
    type = "S"
  }

  global_secondary_index {
    name            = "kind-last-used-at-index"
    hash_key        = "kind"
    range_key       = "last_used_at"
    projection_type = "ALL"
  }
}

resource "aws_dynamodb_table" "audit_log" {
  name         = "${local.name_prefix}-audit-log"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }

  attribute {
    name = "session_id"
    type = "S"
  }

  attribute {
    name = "timestamp"
    type = "S"
  }

  global_secondary_index {
    name            = "session_id-timestamp-index"
    hash_key        = "session_id"
    range_key       = "timestamp"
    projection_type = "ALL"
  }
}

resource "aws_dynamodb_table" "jurisdiction_runs" {
  name         = "${local.name_prefix}-jurisdiction-runs"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "launch_id"
  range_key    = "jurisdiction_code"

  attribute {
    name = "launch_id"
    type = "S"
  }

  attribute {
    name = "jurisdiction_code"
    type = "S"
  }

  global_secondary_index {
    name            = "jurisdiction-index"
    hash_key        = "jurisdiction_code"
    range_key       = "launch_id"
    projection_type = "ALL"
  }
}

resource "aws_dynamodb_table" "sessions" {
  name         = "${local.name_prefix}-sessions"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }

  attribute {
    name = "launch_id"
    type = "S"
  }

  attribute {
    name = "createdAt"
    type = "S"
  }

  global_secondary_index {
    name            = "launch-sessions-index"
    hash_key        = "launch_id"
    range_key       = "createdAt"
    projection_type = "ALL"
  }
}
