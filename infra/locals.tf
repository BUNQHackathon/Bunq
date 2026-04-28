locals {
  dynamodb_tables = [
    "obligations", "controls", "mappings", "gaps",
    "sanctions-hits", "evidence", "sanctions-entities", "audit-log",
    "chat-messages", "launches"
  ]
  kb_sources = ["regulations", "policies", "controls"]
  model_ids = {
    opus   = "eu.anthropic.claude-opus-4-7"
    sonnet = "eu.anthropic.claude-sonnet-4-6"
    haiku  = "eu.anthropic.claude-haiku-4-5-20251001-v1:0"
  }
  name_prefix = var.project_prefix
}
