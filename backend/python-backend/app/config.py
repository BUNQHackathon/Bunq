from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    aws_region: str = "eu-central-1"
    sidecar_token: str  # required — start fails if missing

    opensanctions_api_key: str = ""
    opensanctions_base_url: str = "https://api.opensanctions.org"
    sanctions_use_live_api: bool = False
    fuzzy_threshold: float = 0.92

    dynamodb_sanctions_entities_table: str = "launchlens-sanctions-entities"
    dynamodb_sessions_table: str = "launchlens-sessions"
    dynamodb_obligations_table: str = "launchlens-obligations"
    dynamodb_controls_table: str = "launchlens-controls"
    dynamodb_mappings_table: str = "launchlens-mappings"
    dynamodb_evidence_table: str = "launchlens-evidence"
    dynamodb_gaps_table: str = "launchlens-gaps"
    dynamodb_audit_log_table: str = "launchlens-audit-log"


@lru_cache
def get_settings() -> Settings:
    return Settings()
