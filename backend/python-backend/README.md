# LaunchLens Python Sidecar

FastAPI sidecar for sanctions screening, evidence hashing, and compliance DAG generation.

## Install

```bash
uv pip install -e '.[dev]'
# or
pip install -e '.[dev]'
```

## Run

```bash
SIDECAR_TOKEN=dev uvicorn app.main:app --port 8001 --reload
```

## Test

```bash
pytest
```

## Env vars

| Variable | Default | Required |
|---|---|---|
| `SIDECAR_TOKEN` | — | yes |
| `AWS_REGION` | `eu-central-1` | no |
| `OPENSANCTIONS_API_KEY` | — | no (skips live lookup if absent) |
| `FUZZY_THRESHOLD` | `0.92` | no |
