# ECS Express Mode service — native resource (hashicorp/aws >= 6.23).

# ── Inline policy: allow execution role to fetch our Secrets Manager secrets ──
# The task execution role (not the task role) is used by ECS to pull secrets
# before starting the container.
resource "aws_iam_role_policy" "ecs_task_execution_secrets" {
  name = "launchlens-execution-secrets"
  role = data.aws_iam_role.ecs_task_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = [
          aws_secretsmanager_secret.opensanctions.arn,
          aws_secretsmanager_secret.sidecar_token.arn
        ]
      }
    ]
  })
}

# ── ECS Express Gateway Service ───────────────────────────────────────────────
# Express Mode auto-provisions ALB, HTTPS listener, managed TLS cert, and a
# *.ecs.<region>.on.aws endpoint. The endpoint is exposed via ingress_paths[0].endpoint.
resource "aws_ecs_express_gateway_service" "backend" {
  service_name            = "${local.name_prefix}-backend-v5"
  cluster                 = "default"
  execution_role_arn      = data.aws_iam_role.ecs_task_execution.arn
  infrastructure_role_arn = data.aws_iam_role.ecs_infra_express.arn
  task_role_arn           = aws_iam_role.task.arn
  cpu                     = 1024
  memory                  = 2048
  health_check_path       = "/api/v1/actuator/health"

  primary_container {
    image          = "${aws_ecr_repository.backend.repository_url}:${var.image_tag}"
    container_port = 8080

    aws_logs_configuration {
      log_group         = aws_cloudwatch_log_group.backend.name
      log_stream_prefix = "ecs"
    }

    environment {
      name  = "AWS_REGION"
      value = var.region
    }
    environment {
      name  = "AWS_BEDROCK_REGION"
      value = var.bedrock_region
    }
    environment {
      name  = "AWS_BEDROCK_MODEL_IDS_OPUS"
      value = local.model_ids.opus
    }
    environment {
      name  = "AWS_BEDROCK_MODEL_IDS_SONNET"
      value = local.model_ids.sonnet
    }
    environment {
      name  = "AWS_BEDROCK_MODEL_IDS_HAIKU"
      value = local.model_ids.haiku
    }
    environment {
      name  = "AWS_DYNAMODB_SESSIONS_TABLE"
      value = aws_dynamodb_table.sessions.name
    }
    environment {
      name  = "AWS_DYNAMODB_OBLIGATIONS_TABLE"
      value = aws_dynamodb_table.obligations.name
    }
    environment {
      name  = "AWS_DYNAMODB_CONTROLS_TABLE"
      value = aws_dynamodb_table.controls.name
    }
    environment {
      name  = "AWS_DYNAMODB_DOCUMENTS_TABLE"
      value = aws_dynamodb_table.documents.name
    }
    environment {
      name  = "AWS_DYNAMODB_DOC_JURISDICTIONS_TABLE"
      value = aws_dynamodb_table.doc_jurisdictions.name
    }
    environment {
      name  = "AWS_DYNAMODB_MAPPINGS_TABLE"
      value = aws_dynamodb_table.this["mappings"].name
    }
    environment {
      name  = "AWS_DYNAMODB_GAPS_TABLE"
      value = aws_dynamodb_table.this["gaps"].name
    }
    environment {
      name  = "AWS_DYNAMODB_SANCTIONS_HITS_TABLE"
      value = aws_dynamodb_table.this["sanctions-hits"].name
    }
    environment {
      name  = "AWS_DYNAMODB_EVIDENCE_TABLE"
      value = aws_dynamodb_table.this["evidence"].name
    }
    environment {
      name  = "AWS_DYNAMODB_SANCTIONS_ENTITIES_TABLE"
      value = aws_dynamodb_table.this["sanctions-entities"].name
    }
    environment {
      name  = "AWS_DYNAMODB_AUDIT_LOG_TABLE"
      value = aws_dynamodb_table.audit_log.name
    }
    environment {
      name  = "AWS_DYNAMODB_JURISDICTION_RUNS_TABLE"
      value = aws_dynamodb_table.jurisdiction_runs.name
    }
    environment {
      name  = "AWS_DYNAMODB_LAUNCHES_TABLE"
      value = aws_dynamodb_table.this["launches"].name
    }
    environment {
      name  = "AWS_S3_UPLOADS_BUCKET"
      value = aws_s3_bucket.uploads.id
    }
    environment {
      name  = "KB_REGULATIONS_ID"
      value = local.kb_ids_static["regulations"]
    }
    environment {
      name  = "KB_POLICIES_ID"
      value = local.kb_ids_static["policies"]
    }
    environment {
      name  = "KB_CONTROLS_ID"
      value = local.kb_ids_static["controls"]
    }
    environment {
      name  = "SIDECAR_BASE_URL"
      value = "https://${trimprefix(aws_ecs_express_gateway_service.sidecar.ingress_paths[0].endpoint, "https://")}"
    }
    environment {
      name  = "SERVER_PORT"
      value = "8080"
    }
    environment {
      name  = "ADMIN_TOKEN"
      value = "demo-test-7f3a9b2c"
    }

    secret {
      name       = "OPENSANCTIONS_API_KEY"
      value_from = aws_secretsmanager_secret.opensanctions.arn
    }
    secret {
      name       = "SIDECAR_TOKEN"
      value_from = aws_secretsmanager_secret.sidecar_token.arn
    }
  }

  network_configuration {
    subnets         = data.aws_subnets.public.ids
    security_groups = []
  }

  depends_on = [
    null_resource.jib_build,
    aws_iam_role_policy.ecs_task_execution_secrets,
  ]
}
