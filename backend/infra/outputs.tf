output "ecr_repository_url" {
  description = "ECR repository URL for pushing the backend image"
  value       = aws_ecr_repository.backend.repository_url
}

output "uploads_bucket" {
  description = "Name of the S3 uploads bucket"
  value       = aws_s3_bucket.uploads.id
}

output "kb_source_buckets" {
  description = "Map of Knowledge Base source bucket names keyed by KB type"
  value       = { for k, v in aws_s3_bucket.kb_sources : k => v.id }
}

output "dynamodb_tables" {
  description = "Map of DynamoDB table names keyed by logical table name"
  value       = merge(
    { for k, v in aws_dynamodb_table.this : k => v.name },
    { "audit-log" = aws_dynamodb_table.audit_log.name }
  )
}

output "task_role_arn" {
  description = "ARN of the ECS task role assumed by the running container"
  value       = aws_iam_role.task.arn
}

output "vpc_id" {
  description = "ID of the default VPC used by ECS Express"
  value       = data.aws_vpc.default.id
}

output "public_subnet_ids" {
  description = "List of public subnet IDs available for ECS Express placement"
  value       = data.aws_subnets.public.ids
}

output "log_group_name" {
  description = "CloudWatch log group name for the backend ECS service"
  value       = aws_cloudwatch_log_group.backend.name
}

output "opensanctions_secret_arn" {
  description = "ARN of the Secrets Manager secret holding the OpenSanctions API key"
  value       = aws_secretsmanager_secret.opensanctions.arn
}

output "sidecar_token_secret_arn" {
  description = "ARN of the Secrets Manager secret holding the shared sidecar auth token"
  value       = aws_secretsmanager_secret.sidecar_token.arn
}

output "backend_url" {
  description = "Express Mode *.ecs.<region>.on.aws endpoint for the backend service"
  value       = "https://${trimprefix(aws_ecs_express_gateway_service.backend.ingress_paths[0].endpoint, "https://")}"
}

output "cloudfront_url" {
  description = "CloudFront domain name for the optional fallback distribution (empty if disabled)"
  value       = try("https://${aws_cloudfront_distribution.backend[0].domain_name}", "")
}

output "vite_api_base" {
  description = "VITE_API_BASE value to set on the Amplify frontend environment"
  value       = var.enable_cloudfront_fallback ? try("https://${aws_cloudfront_distribution.backend[0].domain_name}", "") : "https://${trimprefix(aws_ecs_express_gateway_service.backend.ingress_paths[0].endpoint, "https://")}"
}

output "kb_ids" {
  description = "Map of Bedrock Knowledge Base IDs keyed by source type (regulations/policies/controls)"
  value       = local.kb_ids_static
}

output "sidecar_url" {
  description = "Sidecar ECS Express endpoint (internal + public)"
  value       = "https://${trimprefix(aws_ecs_express_gateway_service.sidecar.ingress_paths[0].endpoint, "https://")}"
}

output "sidecar_ecr_repository_url" {
  description = "ECR repo URL for the sidecar image"
  value       = aws_ecr_repository.sidecar.repository_url
}
