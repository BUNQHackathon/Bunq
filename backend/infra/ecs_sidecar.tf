# ECS Express Mode service — Python FastAPI sidecar.
# Mirrors the shape of ecs_express.tf (Java backend) but with a Docker buildx
# build instead of Jib (Jib is Java-only).

# ── ECR repository ────────────────────────────────────────────────────────────
resource "aws_ecr_repository" "sidecar" {
  name                 = "${local.name_prefix}-sidecar"
  image_tag_mutability = "MUTABLE"
  force_delete         = true

  image_scanning_configuration {
    scan_on_push = true
  }
}

# ── IAM task role (sidecar-scoped; no Bedrock, no Transcribe) ─────────────────
resource "aws_iam_role" "sidecar_task" {
  name = "${local.name_prefix}-sidecar-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# DynamoDB: read-all tables, write only sanctions-hits / audit-log / evidence
resource "aws_iam_role_policy" "sidecar_dynamodb" {
  name = "sidecar-dynamodb"
  role = aws_iam_role.sidecar_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "DynamoReadAll"
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:Query",
          "dynamodb:Scan",
          "dynamodb:BatchGetItem",
          "dynamodb:DescribeTable"
        ]
        Resource = [
          "arn:aws:dynamodb:${var.region}:${data.aws_caller_identity.current.account_id}:table/${var.project_prefix}-*",
          "arn:aws:dynamodb:${var.region}:${data.aws_caller_identity.current.account_id}:table/${var.project_prefix}-*/index/*"
        ]
      },
      {
        Sid    = "DynamoWriteSanctionsAuditEvidence"
        Effect = "Allow"
        Action = [
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:DeleteItem",
          "dynamodb:BatchWriteItem"
        ]
        Resource = [
          "arn:aws:dynamodb:${var.region}:${data.aws_caller_identity.current.account_id}:table/${var.project_prefix}-sanctions-hits",
          "arn:aws:dynamodb:${var.region}:${data.aws_caller_identity.current.account_id}:table/${var.project_prefix}-audit-log",
          "arn:aws:dynamodb:${var.region}:${data.aws_caller_identity.current.account_id}:table/${var.project_prefix}-evidence"
        ]
      }
    ]
  })
}

# S3: read on sanctions/ and uploads/ prefixes
resource "aws_iam_role_policy" "sidecar_s3" {
  name = "sidecar-s3"
  role = aws_iam_role.sidecar_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3ReadSanctionsAndUploads"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.uploads.arn,
          "${aws_s3_bucket.uploads.arn}/launchlens-sanctions/*",
          "${aws_s3_bucket.uploads.arn}/launchlens-uploads/*"
        ]
      }
    ]
  })
}

# Secrets Manager: GetSecretValue on the two sidecar secrets
resource "aws_iam_role_policy" "sidecar_secrets" {
  name = "sidecar-secrets"
  role = aws_iam_role.sidecar_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "SecretsRead"
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = [
          aws_secretsmanager_secret.sidecar_token.arn,
          aws_secretsmanager_secret.opensanctions.arn
        ]
      }
    ]
  })
}

# CloudWatch logs
resource "aws_iam_role_policy" "sidecar_logs" {
  name = "sidecar-logs"
  role = aws_iam_role.sidecar_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "CloudWatchLogs"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "arn:aws:logs:${var.region}:${data.aws_caller_identity.current.account_id}:log-group:/ecs/${var.project_prefix}-sidecar*"
      }
    ]
  })
}

# ── CloudWatch log group ───────────────────────────────────────────────────────
resource "aws_cloudwatch_log_group" "sidecar" {
  name              = "/ecs/${local.name_prefix}-sidecar"
  retention_in_days = 7
}

# ── Docker buildx image build + push ──────────────────────────────────────────
# Triggered by a hash of all Python source files + Dockerfile + pyproject.toml.
# Requires: Docker CLI with buildx, ECR login already available.
resource "null_resource" "sidecar_image_build" {
  triggers = {
    src_hash = sha1(join("", [
      for f in sort(fileset("${path.module}/../../python-backend", "**/*.py")) :
      filemd5("${path.module}/../../python-backend/${f}")
    ]))
    dockerfile_hash = try(filemd5("${path.module}/../../python-backend/Dockerfile"), "no-dockerfile")
    pyproject_hash  = try(filemd5("${path.module}/../../python-backend/pyproject.toml"), "no-pyproject")
    ecr_url         = aws_ecr_repository.sidecar.repository_url
  }

  provisioner "local-exec" {
    interpreter = ["powershell", "-NoProfile", "-Command"]
    working_dir = "${path.module}/../.."
    command     = <<-EOT
      $ErrorActionPreference = 'Stop'
      $ecrImage = '${aws_ecr_repository.sidecar.repository_url}:latest'
      $registry = $ecrImage.Split('/')[0]
      $pw = aws ecr get-login-password --region ${var.region} --profile ${var.aws_profile}
      $pw | docker login --username AWS --password-stdin $registry
      docker buildx build --platform linux/amd64 -t "$ecrImage" --push python-backend/
    EOT
  }

  depends_on = [aws_ecr_repository.sidecar]
}

# ── ECS Express Gateway Service ───────────────────────────────────────────────
resource "aws_ecs_express_gateway_service" "sidecar" {
  service_name            = "${local.name_prefix}-sidecar-v3"
  cluster                 = "default"
  execution_role_arn      = data.aws_iam_role.ecs_task_execution.arn
  infrastructure_role_arn = data.aws_iam_role.ecs_infra_express.arn
  task_role_arn           = aws_iam_role.sidecar_task.arn
  cpu                     = 512
  memory                  = 1024
  health_check_path       = "/health"

  primary_container {
    image          = "${aws_ecr_repository.sidecar.repository_url}:latest"
    container_port = 8001

    aws_logs_configuration {
      log_group         = aws_cloudwatch_log_group.sidecar.name
      log_stream_prefix = "ecs"
    }

    environment {
      name  = "AWS_REGION"
      value = var.region
    }
    environment {
      name  = "DYNAMODB_SESSIONS_TABLE"
      value = aws_dynamodb_table.sessions.name
    }
    environment {
      name  = "DYNAMODB_OBLIGATIONS_TABLE"
      value = aws_dynamodb_table.obligations.name
    }
    environment {
      name  = "DYNAMODB_CONTROLS_TABLE"
      value = aws_dynamodb_table.controls.name
    }
    environment {
      name  = "DYNAMODB_MAPPINGS_TABLE"
      value = aws_dynamodb_table.this["mappings"].name
    }
    environment {
      name  = "DYNAMODB_GAPS_TABLE"
      value = aws_dynamodb_table.this["gaps"].name
    }
    environment {
      name  = "DYNAMODB_SANCTIONS_HITS_TABLE"
      value = aws_dynamodb_table.this["sanctions-hits"].name
    }
    environment {
      name  = "DYNAMODB_EVIDENCE_TABLE"
      value = aws_dynamodb_table.this["evidence"].name
    }
    environment {
      name  = "DYNAMODB_SANCTIONS_ENTITIES_TABLE"
      value = aws_dynamodb_table.this["sanctions-entities"].name
    }
    environment {
      name  = "DYNAMODB_AUDIT_LOG_TABLE"
      value = aws_dynamodb_table.audit_log.name
    }

    secret {
      name       = "SIDECAR_TOKEN"
      value_from = aws_secretsmanager_secret.sidecar_token.arn
    }
    secret {
      name       = "OPENSANCTIONS_API_KEY"
      value_from = aws_secretsmanager_secret.opensanctions.arn
    }
  }

  # TODO lock down with SG — for hackathon, public Express endpoint is acceptable
  network_configuration {
    subnets         = data.aws_subnets.public.ids
    security_groups = []
  }

  depends_on = [
    null_resource.sidecar_image_build,
    aws_iam_role_policy.ecs_task_execution_secrets,
  ]
}
