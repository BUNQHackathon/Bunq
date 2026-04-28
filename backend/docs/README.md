# Backend Documentation

Reference docs for the Java Spring Boot backend, grouped by topic.

## api/
- [API.md](api/API.md) — full HTTP API contract
- [DOCUMENTS_API.md](api/DOCUMENTS_API.md) — Documents API frontend contract
- [MOCK_API.md](api/MOCK_API.md) — mock/stub API reference

## architecture/
- [BACKEND.md](architecture/BACKEND.md) — entry point overview, package structure, how to run
- [STACK.md](architecture/STACK.md) — tech stack summary
- [STRUCTURE.md](architecture/STRUCTURE.md) — directory structure reference
- [CODE_PATTERNS.md](architecture/CODE_PATTERNS.md) — coding conventions (DynamoDB, SSE, error handling)

## infra/
- [DEPLOYMENT.md](infra/DEPLOYMENT.md) — AWS deployment plan (Terraform, ECS, ECR)
- [INFRA_GUIDE.md](infra/INFRA_GUIDE.md) — AWS infrastructure (VPC, ALB, ECS)
- [DYNAMODB.md](infra/DYNAMODB.md) — table conventions and access patterns

## integrations/
- [SIDECAR.md](integrations/SIDECAR.md) — Python sidecar communication protocol
- [PROMPT_CACHE.md](integrations/PROMPT_CACHE.md) — Bedrock prompt caching approach

## dev/
- [EXCEPTIONS.md](dev/EXCEPTIONS.md) — exception hierarchy and global handler
- [MAPPERS.md](dev/MAPPERS.md) — mapper helper conventions
