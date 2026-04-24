resource "aws_secretsmanager_secret" "opensanctions" {
  name                    = "${local.name_prefix}/opensanctions-api-key"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "opensanctions" {
  secret_id     = aws_secretsmanager_secret.opensanctions.id
  secret_string = coalesce(var.opensanctions_api_key, "placeholder-update-via-aws-cli")
}

resource "random_password" "sidecar_token" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "sidecar_token" {
  name                    = "${local.name_prefix}/sidecar-token"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "sidecar_token" {
  secret_id     = aws_secretsmanager_secret.sidecar_token.id
  secret_string = random_password.sidecar_token.result
}
