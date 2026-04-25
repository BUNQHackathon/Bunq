data "aws_iam_role" "ecs_task_execution" {
  name = "ecsTaskExecutionRole"
}

data "aws_iam_role" "ecs_infra_express" {
  name = "ecsInfrastructureRoleForExpressServices"
}

data "aws_caller_identity" "current" {}

resource "aws_iam_role" "task" {
  name = "${local.name_prefix}-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  inline_policy {
    name = "launchlens-task-policy"
    policy = jsonencode({
      Version = "2012-10-17"
      Statement = [
        {
          Effect = "Allow"
          Action = [
            "dynamodb:GetItem",
            "dynamodb:PutItem",
            "dynamodb:UpdateItem",
            "dynamodb:DeleteItem",
            "dynamodb:Query",
            "dynamodb:Scan",
            "dynamodb:BatchWriteItem",
            "dynamodb:BatchGetItem",
            "dynamodb:DescribeTable"
          ]
          Resource = [
            "arn:aws:dynamodb:${var.region}:${data.aws_caller_identity.current.account_id}:table/${var.project_prefix}-*",
            "arn:aws:dynamodb:${var.region}:${data.aws_caller_identity.current.account_id}:table/${var.project_prefix}-*/index/*"
          ]
        },
        {
          Effect = "Allow"
          Action = [
            "s3:GetObject",
            "s3:PutObject",
            "s3:DeleteObject",
            "s3:ListBucket"
          ]
          Resource = concat(
            [
              aws_s3_bucket.uploads.arn,
              "${aws_s3_bucket.uploads.arn}/*"
            ],
            [for k, v in aws_s3_bucket.kb_sources : v.arn],
            [for k, v in aws_s3_bucket.kb_sources : "${v.arn}/*"]
          )
        },
        {
          Effect = "Allow"
          Action = [
            "bedrock:InvokeModel",
            "bedrock:InvokeModelWithResponseStream"
          ]
          Resource = [
            "arn:aws:bedrock:${var.bedrock_region}:*:inference-profile/eu.anthropic.*",
            "arn:aws:bedrock:*::foundation-model/anthropic.*",
            "arn:aws:bedrock:*::foundation-model/amazon.titan-embed-*"
          ]
        },
        {
          Effect = "Allow"
          Action = [
            "bedrock:Retrieve",
            "bedrock:RetrieveAndGenerate"
          ]
          Resource = "arn:aws:bedrock:${var.bedrock_region}:${data.aws_caller_identity.current.account_id}:knowledge-base/*"
        },
        {
          Effect = "Allow"
          Action = [
            "transcribe:*",
            "polly:SynthesizeSpeech",
            "polly:DescribeVoices",
            "textract:DetectDocumentText",
            "textract:AnalyzeDocument",
            "textract:StartDocumentTextDetection",
            "textract:GetDocumentTextDetection"
          ]
          Resource = "*"
        },
        {
          Effect   = "Allow"
          Action   = ["secretsmanager:GetSecretValue"]
          Resource = "arn:aws:secretsmanager:${var.region}:${data.aws_caller_identity.current.account_id}:secret:${var.project_prefix}/*"
        },
        {
          Effect = "Allow"
          Action = [
            "logs:CreateLogStream",
            "logs:PutLogEvents"
          ]
          Resource = "arn:aws:logs:${var.region}:${data.aws_caller_identity.current.account_id}:log-group:/ecs/${var.project_prefix}-*"
        }
      ]
    })
  }
}

resource "aws_iam_role" "bedrock_kb" {
  name = "${local.name_prefix}-bedrock-kb-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "bedrock.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  inline_policy {
    name = "launchlens-bedrock-kb-policy"
    policy = jsonencode({
      Version = "2012-10-17"
      Statement = [
        {
          Effect   = "Allow"
          Action   = ["bedrock:*"]
          Resource = "*"
        },
        {
          Effect = "Allow"
          Action = [
            "s3:GetObject",
            "s3:ListBucket"
          ]
          Resource = concat(
            [for k, v in aws_s3_bucket.kb_sources : v.arn],
            [for k, v in aws_s3_bucket.kb_sources : "${v.arn}/*"]
          )
        },
        {
          Effect   = "Allow"
          Action   = ["s3vectors:*"]
          Resource = "*"
        }
      ]
    })
  }
}
