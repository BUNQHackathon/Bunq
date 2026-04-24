# LaunchLens Infrastructure Guide

> How to read this file: top to bottom. Each section covers one file (or a tightly related group) with a "Purpose", "What it creates", "Key concepts", and an "Annotated excerpt" where the HCL is non-obvious. Sections 3-8 follow that template; sections 9-13 are shorter reference stops.

---

## 0. Where things live (map)

```
java-backend/
├── pom.xml                          [NEW] Jib plugin + actuator dependency
├── DEPLOYMENT.md                    [NEW] Architectural decision record
├── INFRA_GUIDE.md                   [NEW] This file
├── seed/
│   ├── regulations/                 [NEW] PDF/text seed docs (GDPR, NIS2, …)
│   ├── policies/                    [NEW] Company policy docs
│   └── controls/                    [NEW] Control framework docs (ISO 27001, …)
├── src/
│   └── main/
│       ├── resources/
│       │   └── application.yaml     [CHANGED] Bedrock region + model IDs + sidecar token
│       └── java/com/bunq/javabackend/
│           ├── config/
│           │   └── CorsConfig.java  [NEW] Amplify + localhost CORS rules
│           └── client/
│               └── SidecarClient.java  [CHANGED] X-Sidecar-Token header injection
└── infra/                           [NEW entire directory]
    ├── versions.tf                  Provider version pins
    ├── providers.tf                 AWS provider configuration
    ├── backend.tf                   Local state (v1)
    ├── variables.tf                 All input variables
    ├── locals.tf                    Computed constants (table names, model IDs)
    ├── outputs.tf                   Values printed after apply
    ├── terraform.tfvars.example     Committed template for secrets
    ├── .gitignore                   Keeps state + secrets out of git
    ├── README.md                    Quick-start commands
    ├── vpc.tf                       Data-source reads of default VPC + subnets
    ├── iam.tf                       3 IAM roles (task, execution, bedrock-kb)
    ├── dynamodb.tf                  9 on-demand tables
    ├── s3.tf                        Uploads bucket + 3 KB source buckets
    ├── secrets.tf                   OpenSanctions key + sidecar token
    ├── ecr.tf                       Container image registry
    ├── cloudwatch.tf                Log group (7-day retention)
    ├── s3_vectors.tf                Vector bucket + 3 indexes (CLI fallback)
    ├── bedrock_kb.tf                3 Knowledge Bases + data sources + ingestion
    ├── seed_docs.tf                 Uploads seed/ docs to KB source buckets
    ├── jib_build.tf                 Maven Jib build+push via null_resource
    ├── ecs_express.tf               ECS Express Mode service (Fargate + ALB + TLS)
    └── cloudfront.tf                Optional CloudFront fallback (disabled by default)
```

Files marked `[NEW]` did not exist before the deployment plan was executed. `[CHANGED]` means the file existed but was modified.

---

## 1. Reading order

If you have never used Terraform before, read sections 2 then 3. Every concept you meet in sections 4-8 will already have a name.

If you know Terraform but not AWS, skim section 2 for the terms we use, then read sections 4 onward — the annotations explain the AWS side.

Fastest path to "just make it work": read section 10 (Common workflows) and come back here when something breaks.

Suggested full read order:

1. Section 2 — Terraform cheat-sheet (builds vocabulary)
2. Section 3 — Configuration backbone (understand the skeleton before the organs)
3. Section 4 — Foundation layer (IAM is the hardest; spend time here)
4. Section 5 — Knowledge Base layer (read the RAG primer first)
5. Section 6 — Container layer (Jib)
6. Section 7 — Runtime layer (ECS Express Mode)
7. Section 8 — Optional fallback (CloudFront)
8. Section 9 — Spring Boot edits (recap)
9. Sections 10-13 — Workflows, troubleshooting, glossary, next steps

---

## 2. Terraform basics cheat-sheet

This is a one-page reference. Each concept appears in our code; the pointer tells you where to find a real example.

| Concept | One-line definition | Where we use it |
|---|---|---|
| `resource` | Declares a new AWS resource for Terraform to create and manage | Every `.tf` file; e.g., `aws_dynamodb_table` in `dynamodb.tf` |
| `data` | Reads an existing resource that Terraform did not create | `vpc.tf` reads the default VPC; `iam.tf` reads the pre-existing execution role |
| `variable` | An input parameter; callers can override the default | `variables.tf` — `region`, `project_prefix`, `enable_cloudfront_fallback`, etc. |
| `locals` | Computed constants scoped to the module; cannot be overridden from outside | `locals.tf` — table name list, model ID map |
| `output` | A value printed after `apply`; also readable by other modules | `outputs.tf` — `backend_url`, `vite_api_base`, `kb_ids` |
| `provider` | Tells Terraform which cloud SDK to use and how to authenticate | `providers.tf` — AWS provider, `eu-central-1`, named profile |
| `for_each` | Loops over a set or map, creating one resource instance per element | `dynamodb.tf` (9 tables from one `resource` block), `s3.tf` (3 KB buckets) |
| `count` | Simpler loop — creates N copies; `count = 0` means "do not create" | `cloudfront.tf` — `count = var.enable_cloudfront_fallback ? 1 : 0` |
| `null_resource` | A no-op resource whose only purpose is to run `provisioner` blocks | `s3_vectors.tf`, `bedrock_kb.tf`, `jib_build.tf` |
| `local-exec` provisioner | Runs a shell command on your local machine during `apply` or `destroy` | `jib_build.tf` (Maven), `s3_vectors.tf` (AWS CLI), `bedrock_kb.tf` (AWS CLI) |
| `triggers` | A map on `null_resource`; when any value changes, the resource is re-created | `jib_build.tf` — hash of `src/` + `pom.xml` triggers a rebuild |
| state file | `terraform.tfstate` — Terraform's memory of what exists in AWS. Do not delete it or edit by hand | `backend.tf` — stored locally in `infra/` |
| `terraform init` | Downloads provider plugins; run once per new checkout | Run first |
| `terraform plan` | Shows a diff: `+` create, `-` destroy, `~` update. No AWS changes yet | Run before every `apply` |
| `terraform apply` | Executes the plan | Creates or updates everything |
| `terraform destroy` | Deletes all managed resources in reverse order | Teardown |
| `terraform taint` | Marks a resource "dirty" so it is re-created on next `apply` | Used to force Jib rebuild or KB re-ingestion |
| `depends_on` | Explicit ordering when Terraform cannot infer it from references | `ecs_express.tf` waits for `jib_build` |

---

## 3. Configuration backbone

### `versions.tf`

**Purpose.** Pins the minimum Terraform version and locks provider versions so every team member (and CI) uses identical binaries.

**What it creates.** Nothing in AWS. It only constrains what `terraform init` downloads.

**Key concepts.** The `~> 6.23` constraint means "any version >= 6.23.0 and < 7.0.0". This is called a pessimistic constraint operator. The `null` and `random` providers are also declared here because `jib_build.tf` and `secrets.tf` use them.

**Annotated excerpt.**

```hcl
terraform {
  required_version = ">= 1.9"          # Terraform CLI must be this version or newer

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.23"              # AWS provider: 6.23.x or 6.24.x, never 7.x
    }
    null = {
      source  = "hashicorp/null"
      version = "~> 3.2"              # Used by null_resource (jib_build, s3_vectors, bedrock_kb)
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"              # Used by random_password in secrets.tf
    }
  }
}
```

We use `hashicorp/aws >= 6.23` specifically because `aws_ecs_express_gateway_service` — the ECS Express Mode resource — was only added to the provider in the 6.x series.

---

### `providers.tf`

**Purpose.** Configures the AWS provider: which region to deploy into, which credentials profile to use, and what tags to automatically attach to every resource.

**What it creates.** Nothing. Provider configuration is metadata.

**Key concepts.** `default_tags` is a provider-level feature that injects the same tags onto every AWS resource this provider manages. You never have to remember to add `Project = "launchlens"` in every resource block — it happens automatically.

**Annotated excerpt.**

```hcl
provider "aws" {
  region  = var.region        # "eu-central-1" from variables.tf
  profile = var.aws_profile   # AWS CLI named profile ("default" or "launchlens")

  default_tags {
    tags = {
      Project   = var.project_prefix   # "launchlens" on every resource
      ManagedBy = "terraform"          # Makes it easy to audit in the AWS console
    }
  }
}
```

---

### `backend.tf`

**Purpose.** Tells Terraform where to store the state file.

**What it creates.** Nothing in AWS. For v1, the state file lives at `infra/terraform.tfstate` on your disk.

**Key concepts.**

- **State file** is Terraform's source of truth. It records every resource's current attributes. If you delete it, Terraform thinks nothing exists and will try to create everything from scratch (often failing with "resource already exists" errors).
- `backend "local" {}` is the default. For solo hackathon work this is fine.
- Post-hackathon, migrate to `backend "s3"` with a DynamoDB lock table so multiple developers share state safely. See section 13 for the migration path.

```hcl
# v1: local state. TODO post-hackathon: migrate to S3 + DynamoDB lock backend.
terraform {
  backend "local" {}
}
```

---

### `variables.tf`

**Purpose.** Declares every input parameter the module accepts, with types, descriptions, and safe defaults.

**What it creates.** Nothing. Variables are inputs, not resources.

**Key concepts.** You override defaults either in `terraform.tfvars` (preferred for secrets) or on the command line with `-var="key=value"`. Variables marked `sensitive = true` are redacted from plan output.

Key variables to know:

| Variable | Default | When you change it |
|---|---|---|
| `project_prefix` | `launchlens` | Never — it prefixes every resource name |
| `region` | `eu-central-1` | Only if moving to a different region |
| `aws_profile` | `default` | If your AWS CLI profile is named something else |
| `image_tag` | `latest` | To pin a specific image version |
| `amplify_origin` | `https://*.amplifyapp.com` | If your Amplify URL changes |
| `sidecar_base_url` | `""` | Set to the sidecar's Express URL once deployed |
| `enable_cloudfront_fallback` | `false` | Set `true` only if the Express URL misbehaves from Amplify |
| `opensanctions_api_key` | `""` | Your OpenSanctions API key (sensitive) |

---

### `locals.tf`

**Purpose.** Defines computed constants used across multiple files, so there is one place to add or remove a table name or Knowledge Base type.

**What it creates.** Nothing. Locals are evaluated in memory.

**Annotated excerpt.**

```hcl
locals {
  dynamodb_tables = [
    "sessions", "obligations", "controls", "mappings", "gaps",
    "sanctions-hits", "evidence", "sanctions-entities", "audit-log"
  ]
  # Adding a table: append to this list, run terraform apply.
  # Removing a table: remove from list — terraform destroy that table only.

  kb_sources = ["regulations", "policies", "controls"]
  # Used by s3.tf, s3_vectors.tf, bedrock_kb.tf, seed_docs.tf — all via for_each.

  model_ids = {
    opus   = "eu.anthropic.claude-opus-4-7-v1:0"
    sonnet = "eu.anthropic.claude-sonnet-4-6-v1:0"
    haiku  = "eu.anthropic.claude-haiku-4-5-v1:0"
  }
  # These are cross-region inference profile IDs, not foundation model IDs.
  # The "eu." prefix routes traffic across EU AWS regions for higher throughput.
}
```

---

### `outputs.tf`

**Purpose.** Exposes values you need after `apply`: the backend URL, the Amplify environment variable, ECR repo URL, Knowledge Base IDs.

**What it creates.** Nothing in AWS. Outputs are printed to stdout and stored in state.

**Key outputs.**

| Output | What it is |
|---|---|
| `backend_url` | The `https://*.ecs.eu-central-1.on.aws` URL — paste into Amplify |
| `vite_api_base` | Same as `backend_url` unless CloudFront fallback is on; use this for `VITE_API_BASE` |
| `ecr_repository_url` | Where Jib pushes the image and ECS pulls from |
| `kb_ids` | Map of Knowledge Base IDs by type; needed to call Bedrock Retrieve |
| `uploads_bucket` | S3 bucket name for presigned PUTs |

```hcl
output "vite_api_base" {
  # Automatically switches between Express URL and CloudFront URL.
  value = var.enable_cloudfront_fallback
    ? "https://${try(aws_cloudfront_distribution.backend[0].domain_name, "")}"
    : "https://${aws_ecs_express_gateway_service.backend.ingress_paths[0].endpoint}"
}
```

---

### `terraform.tfvars.example`

**Purpose.** A committed template showing which variables need real values. Copy it to `terraform.tfvars` (which is gitignored) and fill in secrets.

```hcl
aws_profile           = "default"
opensanctions_api_key = ""
sidecar_base_url      = "https://<sidecar-service>.ecs.eu-central-1.on.aws"
```

The three variables here are the only ones without usable defaults. Everything else (region, prefix, model IDs) is fine as-is for the hackathon.

---

### `.gitignore`

**Purpose.** Keeps the state file, real tfvars, and the downloaded provider cache out of version control.

What it excludes:
- `.terraform/` — provider binaries (large, platform-specific, reproducible via `init`)
- `terraform.tfstate` and `terraform.tfstate.backup` — contains resource IDs and possibly secret values
- `terraform.tfvars` — may contain your `opensanctions_api_key`
- `.terraform.lock.hcl` exclusion is optional; committing the lock file is actually recommended for reproducibility. Check whether it is excluded in your copy.

---

## 4. Foundation layer (AWS basics)

### `vpc.tf`

**Purpose.** Finds the AWS default VPC and its public subnets, making them available to other files via data sources.

**What it creates.** Nothing new. These `data` blocks read VPC resources that AWS creates automatically in every new account.

**Key concepts.** The default VPC is pre-wired with internet-facing public subnets. ECS Express Mode needs subnet IDs to place Fargate tasks. Rather than creating a custom VPC (which involves route tables, NAT gateways, and security groups), we reuse what AWS already provides for the hackathon.

```hcl
data "aws_vpc" "default" {
  default = true           # Finds the VPC where default=true; every account has one
}

data "aws_subnets" "public" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "map-public-ip-on-launch"   # True = public subnet (gets a public IP)
    values = ["true"]
  }
}
```

`data.aws_subnets.public.ids` is then referenced in `ecs_express.tf` under `network_configuration`.

---

### `iam.tf`

**Purpose.** Creates and wires the IAM roles that govern what the ECS container is allowed to do in AWS.

**What it creates.** Two new IAM roles plus two data-source reads of pre-existing roles.

IAM is the most confusing part of AWS for newcomers. Here is the mental model before reading the code:

**Think of an IAM role as a hat.** When ECS puts a hat on your container, the container gets whatever permissions are stitched into that hat. The hat has two sides:

1. **Trust policy** — who is allowed to put on this hat (which AWS service can assume this role).
2. **Permissions policy** — what AWS actions the wearer can perform.

There are four roles in play here:

| Role | Created by | Trust principal | What it does |
|---|---|---|---|
| `ecsTaskExecutionRole` | AWS (pre-existing) | `ecs-tasks.amazonaws.com` | Pulls the container image from ECR; ships logs to CloudWatch; fetches Secrets Manager values before the container starts |
| `ecsInfrastructureRoleForExpressServices` | AWS (pre-existing) | `ecs.amazonaws.com` | Lets Express Mode provision the ALB, TLS cert, and VPC wiring on your behalf |
| `launchlens-task-role` | `iam.tf` (new) | `ecs-tasks.amazonaws.com` | The running application's hat — DynamoDB, S3, Bedrock, Transcribe, Polly, Textract, Secrets Manager |
| `launchlens-bedrock-kb-role` | `iam.tf` (new) | `bedrock.amazonaws.com` | The Knowledge Base ingestion hat — read from KB source S3 buckets |

**Why two roles for ECS and not one?** The execution role acts before the container starts (pull image, inject secrets). The task role acts while the container runs (call DynamoDB). Keeping them separate means you can audit and restrict each boundary independently.

**Annotated excerpt — task role trust policy.**

```hcl
resource "aws_iam_role" "task" {
  name = "${local.name_prefix}-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }  # Only ECS can assume this role
      Action    = "sts:AssumeRole"                         # "Put on the hat"
    }]
  })
  # ... inline_policy follows
}
```

**Annotated excerpt — task role Bedrock permissions.**

```hcl
{
  Effect = "Allow"
  Action = [
    "bedrock:InvokeModel",
    "bedrock:InvokeModelWithResponseStream"
  ]
  Resource = [
    # Cross-region inference profile (eu. prefix = EU data residency)
    "arn:aws:bedrock:${var.bedrock_region}:*:inference-profile/eu.anthropic.*",
    # Foundation models referenced by the profile (must be listed separately)
    "arn:aws:bedrock:*::foundation-model/anthropic.*",
    "arn:aws:bedrock:*::foundation-model/amazon.titan-embed-*"
  ]
}
```

The `eu.anthropic.*` wildcard covers all three Claude models (Opus, Sonnet, Haiku) so you do not have to update IAM when adding a new model variant.

**Pre-existing roles via `data` blocks.** The account already had `ecsTaskExecutionRole` and `ecsInfrastructureRoleForExpressServices` from a prior Express Mode experiment. Instead of recreating them (which would fail with "role already exists"), we read them with `data "aws_iam_role"` and reference their ARNs. This is the standard pattern for reusing resources you did not create with Terraform.

---

### `dynamodb.tf`

**Purpose.** Creates all nine application DynamoDB tables.

**What it creates.** Nine `aws_dynamodb_table` resources, each named `launchlens-<table>`.

**Key concepts.**

- `PAY_PER_REQUEST` (on-demand) billing: you pay per read/write operation, not for reserved capacity. For a 24-hour hackathon this costs almost nothing.
- `for_each = toset(local.dynamodb_tables)` creates one table per item in the list from `locals.tf`. Add a table name to that list and run `apply`; remove it and run `apply` to delete.
- All tables use `id` (String) as the hash key (partition key). Secondary indexes for other access patterns can be added per-table later without changing the current code.

```hcl
resource "aws_dynamodb_table" "this" {
  for_each     = toset(local.dynamodb_tables)    # One resource per table name
  name         = "${local.name_prefix}-${each.value}"  # e.g. "launchlens-sessions"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"   # S = String, N = Number, B = Binary
  }
}
```

---

### `s3.tf`

**Purpose.** Creates the uploads bucket (where users PUT documents via presigned URLs) and three source buckets used by the Bedrock Knowledge Bases.

**What it creates.**

- `launchlens-uploads` — 1 bucket with versioning, SSE-S3, Block Public Access, and CORS.
- `launchlens-kb-regulations`, `launchlens-kb-policies`, `launchlens-kb-controls` — 3 buckets with SSE-S3 and Block Public Access (no CORS needed; Bedrock reads them server-side).

**Key concepts.**

- **Block Public Access** is the modern S3 default. All four settings are enabled here. Objects are never publicly reachable; access goes through IAM or presigned URLs.
- **SSE-S3 (AES256)** encrypts objects at rest using S3-managed keys. No cost, no setup.
- **Versioning** on the uploads bucket preserves every previous version of an object — useful for audit trails and recovering overwritten documents.
- **CORS** on the uploads bucket is required because the browser PUT goes directly to S3 (not via your backend). The rule allows `https://*.amplifyapp.com` and `http://localhost:5173`.
- **Presigned URLs** are short-lived signed S3 URLs. Your backend generates them (via the AWS SDK), returns them to the browser, and the browser PUTs directly to S3. No data flows through your backend, no file size limit on your container.

```hcl
resource "aws_s3_bucket_cors_configuration" "uploads" {
  bucket = aws_s3_bucket.uploads.id

  cors_rule {
    allowed_methods = ["GET", "PUT", "HEAD", "POST"]
    allowed_origins = [var.amplify_origin, "http://localhost:5173"]
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]   # Browser needs ETag to confirm upload
    max_age_seconds = 3000
  }
}
```

---

### `secrets.tf`

**Purpose.** Stores two secrets in AWS Secrets Manager: the OpenSanctions API key and a randomly generated shared token used to authenticate calls between the main backend and the sidecar service.

**What it creates.** Two `aws_secretsmanager_secret` entries plus their initial versions.

**Key concepts.**

- `recovery_window_in_days = 0` means the secret is deleted immediately when Terraform destroys it (no 7-30 day recovery window). This is intentional for hackathon teardowns — you want a clean slate.
- `random_password.sidecar_token` generates a 32-character alphanumeric string on first `apply`. Terraform stores the result in state (encrypted). You never need to see or copy this value — it is injected into both services as an environment variable.
- Secrets are not in environment variables in the task definition in plaintext. The ECS execution role fetches them from Secrets Manager right before the container starts and injects them as env vars. The `secret` blocks in `ecs_express.tf` handle this wiring.

```hcl
resource "random_password" "sidecar_token" {
  length  = 32
  special = false   # No special chars — simpler to pass in HTTP headers
}

resource "aws_secretsmanager_secret" "sidecar_token" {
  name                    = "${local.name_prefix}/sidecar-token"
  recovery_window_in_days = 0  # Immediate deletion on destroy
}
```

---

### `ecr.tf`

**Purpose.** Creates the private container image registry where Jib pushes the Spring Boot image and from which ECS pulls it.

**What it creates.** One `aws_ecr_repository` named `launchlens-backend`.

**Key concepts.**

- `image_tag_mutability = "MUTABLE"` means the `latest` tag can be overwritten on each push. For a hackathon this is fine; production repos often use `IMMUTABLE` to prevent accidental overwrites.
- `scan_on_push = true` runs a vulnerability scan on every pushed image using Amazon ECR's built-in scanner (powered by Clair/Grype). Results appear in the ECR console.
- `force_delete = true` allows `terraform destroy` to delete the repo even if it contains images. Without this, Terraform would refuse to delete a non-empty repo.

```hcl
resource "aws_ecr_repository" "backend" {
  name                 = "${local.name_prefix}-backend"
  image_tag_mutability = "MUTABLE"
  force_delete         = true

  image_scanning_configuration {
    scan_on_push = true
  }
}
```

---

### `cloudwatch.tf`

**Purpose.** Creates the CloudWatch log group where ECS streams container stdout/stderr.

**What it creates.** One log group: `/ecs/launchlens-backend`.

**Key concepts.** `retention_in_days = 7` deletes log data automatically after one week, keeping costs near zero. ECS Express Mode sends logs here automatically — you do not configure a log driver in the task definition separately; the `aws_logs_configuration` block in `ecs_express.tf` points at this group.

```hcl
resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/${local.name_prefix}-backend"
  retention_in_days = 7
}
```

---

## 5. Knowledge Base layer (Bedrock + S3 Vectors)

### The RAG pipeline — read this first

RAG stands for Retrieval-Augmented Generation. Instead of asking Claude to answer from its training data alone, you first retrieve relevant passages from your own documents, then include those passages in the prompt. For LaunchLens, the documents are compliance regulations (GDPR, NIS2, etc.), internal policies, and control frameworks.

The pipeline in order:

1. **Source documents** — PDF or text files placed under `seed/{regulations,policies,controls}/`.
2. **S3 source buckets** — `s3.tf` creates `launchlens-kb-regulations` (and `policies`, `controls`). `seed_docs.tf` uploads the seed files there.
3. **Bedrock Knowledge Base** — A managed Bedrock service that reads from the S3 bucket, chunks documents, embeds each chunk using Titan Embeddings v2 (1024 dimensions), and writes the vectors to S3 Vectors.
4. **S3 Vectors** — AWS's dedicated vector store (GA December 2025). Stores the float32 embeddings indexed by cosine similarity. At query time, Bedrock embeds your search query, runs cosine search against the index, and returns the top-k matching chunks.
5. **Retrieve** — At runtime, `bedrock-agent-runtime:Retrieve` takes a natural language query, runs the same embed + cosine lookup, and returns relevant passages. Your Spring service injects those passages into the Claude prompt.

There are three separate pipelines (one per KB type) running in parallel. Each has its own S3 source bucket, S3 Vectors index, and Bedrock Knowledge Base.

---

### `s3_vectors.tf`

**Purpose.** Creates one S3 Vectors bucket and three indexes (one per KB type). S3 Vectors is too new (GA December 2025) to have a native Terraform resource in the AWS provider, so we use `null_resource` + `local-exec` wrapping the AWS CLI.

**What it creates.**

- `launchlens-vectors` — one S3 Vectors bucket.
- `regulations-idx`, `policies-idx`, `controls-idx` — three indexes inside that bucket. Each index stores float32 vectors of dimension 1024 using cosine distance (matching Titan Embeddings v2's output).

**Key concepts.**

- `null_resource` runs shell commands during `apply` and `destroy`. The resource itself has no AWS footprint; the shell commands create the real AWS objects.
- `triggers` are a map of values. When any value changes between applies, Terraform marks the `null_resource` as "dirty" and re-runs the provisioners. Here, the bucket name and region are in `triggers` so that a rename forces re-creation.
- **Destroy provisioner** — the `when = destroy` block runs during `terraform destroy`. It calls `aws s3vectors delete-vector-bucket` and uses `|| true` so the destroy does not fail if the resource was already deleted manually.
- The `aws_iam_role_policy.bedrock_kb_s3vectors` resource at the bottom of this file attaches an additional policy to the Bedrock KB role, granting it `s3vectors:*` permissions. It is placed here rather than in `iam.tf` to keep the S3 Vectors concerns co-located (surgical change rule).

```hcl
resource "null_resource" "s3_vectors_indexes" {
  for_each = toset(local.kb_sources)  # Creates three index resources in parallel

  provisioner "local-exec" {
    command = <<-EOT
      aws s3vectors create-index \
        --vector-bucket-name ${local.name_prefix}-vectors \
        --index-name ${each.value}-idx \
        --data-type float32 \
        --dimension 1024 \       # Must match Titan Embeddings v2 output
        --distance-metric cosine \
        --region ${var.region} \
        --profile ${var.aws_profile}
    EOT
  }

  depends_on = [null_resource.s3_vectors_bucket]  # Bucket must exist first
}
```

---

### `bedrock_kb.tf`

**Purpose.** Creates three Bedrock Knowledge Bases (one per KB type), attaches S3 data sources, and triggers the initial ingestion job.

**What it creates.** Three KBs, three data sources, three ingestion jobs — all via `null_resource` CLI calls, because the S3_VECTORS storage configuration type is new enough that the Terraform AWS provider may not support it yet.

**How it passes KB IDs between resources.** The `create-knowledge-base` CLI call writes the returned KB ID to `/tmp/kb_id_<source>.txt`. Subsequent resources (`bedrock_data_source`, `kb_ingestion`) read from that file. The `data "external"` blocks at the bottom of the file expose the IDs to `outputs.tf` and `ecs_express.tf`.

**Three-stage sequence for each KB type.**

```
null_resource.bedrock_kb["regulations"]
    → creates KB, writes /tmp/kb_id_regulations.txt
        ↓
null_resource.bedrock_data_source["regulations"]
    → reads KB ID, creates S3 data source, writes /tmp/ds_id_regulations.txt
        ↓
null_resource.kb_ingestion["regulations"]
    → reads both IDs, starts ingestion job
```

**To re-ingest after adding documents.**

```bash
terraform taint 'null_resource.kb_ingestion["regulations"]'
terraform apply
```

**Annotated excerpt — KB creation.**

```hcl
provisioner "local-exec" {
  command = <<-EOT
    set -e   # Exit immediately if any command fails

    KB_ID=$(aws bedrock-agent create-knowledge-base \
      --name "${local.name_prefix}-${each.value}" \
      --role-arn "${aws_iam_role.bedrock_kb.arn}" \
      --knowledge-base-configuration '{"type":"VECTOR",...}' \
      --storage-configuration '{"type":"S3_VECTORS",...}' \
      --region ${var.bedrock_region} \
      --query 'knowledgeBase.knowledgeBaseId' \
      --output text)

    echo "$KB_ID" > /tmp/kb_id_${each.value}.txt  # Save for downstream resources
  EOT
}
```

---

### `seed_docs.tf`

**Purpose.** Uploads every file found under `java-backend/seed/{regulations,policies,controls}/` to the corresponding KB source bucket, so documents are in place before the first ingestion job runs.

**What it creates.** One `aws_s3_object` per seed file found. If the seed directories are empty, `for_each` has zero entries and nothing is uploaded (no error).

**Key concepts.**

- `fileset("${path.module}/../seed/${source}", "**")` lists all files recursively under the seed directory. `path.module` is the `infra/` directory, so `..` goes up to `java-backend/`.
- `etag = filemd5(each.value.path)` causes Terraform to re-upload a file if its content changes between applies, because the ETag changes.
- Content-type is set automatically by S3 based on the file extension (PDFs get `application/pdf`).

```hcl
locals {
  seed_files = merge([
    for source in local.kb_sources : {
      for f in fileset("${path.module}/../seed/${source}", "**") :
      "${source}/${f}" => { source = source, key = f, path = "..." }
    }
  ]...)
}

resource "aws_s3_object" "seed_docs" {
  for_each = local.seed_files     # One resource per file
  bucket   = aws_s3_bucket.kb_sources[each.value.source].id
  key      = each.value.key       # Filename within the bucket
  source   = each.value.path      # Local file path
  etag     = filemd5(each.value.path)  # Forces re-upload when content changes
}
```

---

## 6. Container layer (Jib)

### `jib_build.tf`

**Purpose.** Builds the Spring Boot application into an OCI container image and pushes it to ECR — without a Docker daemon.

**What it creates.** A `null_resource` that runs `./mvnw jib:build` on your local machine during `apply`.

**What Jib is and why we use it.** Jib is a Maven/Gradle plugin by Google that builds container images without Docker. Instead of writing a Dockerfile and requiring Docker Desktop to be running, Jib reads your classpath, creates optimally layered image layers (one layer per dependency group), and pushes directly to any OCI registry. For a Spring Boot app on Windows/WSL2 this eliminates the Docker Desktop dependency entirely.

Jib's layer strategy is smart about rebuild speed: your project's dependencies (Maven artifacts) go in a stable layer that is cached and only re-pushed when dependencies change. Your application code goes in a thin top layer. A code-only change rebuilds and pushes only that thin layer.

**How the ECR auth works.** ECR requires a short-lived password (valid 12 hours) obtained via `aws ecr get-login-password`. The `local-exec` command runs that subshell inline and passes the password to Jib via `-Djib.to.auth.password`.

**Annotated excerpt.**

```hcl
resource "null_resource" "jib_build" {
  triggers = {
    # Hash of every file under src/ — any code change causes a rebuild.
    src_hash = sha1(join("", [
      for f in sort(fileset("${path.module}/../src", "**")) :
      filesha1("${path.module}/../src/${f}")
    ]))
    pom_hash  = filesha1("${path.module}/../pom.xml")  # Also rebuild on dep changes
    image_tag = var.image_tag
    ecr_url   = aws_ecr_repository.backend.repository_url
  }

  provisioner "local-exec" {
    working_dir = "${path.module}/.."    # Run from java-backend/ so ./mvnw is found
    command     = <<-EOT
      ./mvnw -B -DskipTests compile jib:build \
        -Djib.to.image="${aws_ecr_repository.backend.repository_url}:${var.image_tag}" \
        -Djib.to.auth.username=AWS \
        -Djib.to.auth.password="$(aws ecr get-login-password \
          --region ${var.region} --profile ${var.aws_profile})"
    EOT
  }

  depends_on = [aws_ecr_repository.backend]  # ECR repo must exist before we push
}
```

**`pom.xml` Jib configuration.**

```xml
<plugin>
    <groupId>com.google.cloud.tools</groupId>
    <artifactId>jib-maven-plugin</artifactId>
    <version>3.5.1</version>
    <configuration>
        <from>
            <image>eclipse-temurin:25-jre</image>  <!-- Java 25 JRE base image -->
        </from>
        <to>
            <image>${env.ECR_IMAGE}</image>   <!-- Set by Terraform via -Djib.to.image -->
        </to>
        <container>
            <ports><port>8080</port></ports>
            <jvmFlags>
                <jvmFlag>-Xms512m</jvmFlag>
                <jvmFlag>-Xmx512m</jvmFlag>  <!-- Fits within 2 GB Fargate memory -->
            </jvmFlags>
        </container>
    </configuration>
</plugin>
```

Note: `${env.ECR_IMAGE}` is a Maven property that reads the `ECR_IMAGE` environment variable. Terraform overrides this with `-Djib.to.image=...` on the command line, so the `pom.xml` value only matters for local `mvn jib:build` runs.

---

## 7. Runtime layer (ECS Express Mode)

### `ecs_express.tf`

**Purpose.** Deploys the Spring Boot container as a publicly accessible HTTPS service using ECS Express Mode — AWS's managed, opinionated Fargate deployment that provisions ALB, HTTPS listener, managed TLS cert, and a `*.ecs.eu-central-1.on.aws` URL automatically.

**What it creates.**

- One `aws_ecs_express_gateway_service` that auto-provisions under the hood:
  - A Fargate task (1 vCPU / 2 GB RAM)
  - An Application Load Balancer
  - An HTTPS listener on port 443
  - An AWS-managed TLS certificate on the `*.ecs.<region>.on.aws` domain (no ACM request, no DNS validation needed)
  - A `*.ecs.eu-central-1.on.aws` URL ready immediately after the service becomes healthy
  - CloudWatch log streaming (via `aws_logs_configuration`)
  - Rolling-update deployment controller
- One inline policy attached to the execution role: permission to read the two Secrets Manager secrets before the container starts.

**Why ECS Express Mode instead of App Runner.** App Runner announced end-of-life for new customers on 2026-04-30 (8 days from the date of this writing). ECS Express Mode is AWS's recommended replacement. It delivers the same "one resource in, HTTPS URL out" experience but is built on Fargate and the ALB, which are both long-term AWS primitives.

**How environment variables reach Spring Boot.** The `environment` blocks in `primary_container` inject env vars into the Fargate task. Spring Boot picks them up automatically via relaxed binding: `AWS_BEDROCK_MODEL_IDS_OPUS` in the environment becomes `aws.bedrock.model-ids.opus` in `application.yaml` (underscores to dots, uppercase to lowercase).

**How secrets reach Spring Boot.** The `secret` blocks reference Secrets Manager ARNs. Before the container starts, the ECS execution role fetches the secret values and injects them as env vars (`OPENSANCTIONS_API_KEY`, `SIDECAR_TOKEN`). The container never calls Secrets Manager directly at runtime.

**Annotated excerpt.**

```hcl
resource "aws_ecs_express_gateway_service" "backend" {
  service_name            = "${local.name_prefix}-backend"
  cluster                 = "default"
  execution_role_arn      = data.aws_iam_role.ecs_task_execution.arn   # Pull image + logs
  infrastructure_role_arn = data.aws_iam_role.ecs_infra_express.arn    # Provision ALB
  task_role_arn           = aws_iam_role.task.arn                      # App AWS calls
  cpu                     = 1024   # 1 vCPU
  memory                  = 2048   # 2 GB
  health_check_path       = "/actuator/health"  # ALB polls this; must return 200

  primary_container {
    image          = "${aws_ecr_repository.backend.repository_url}:${var.image_tag}"
    container_port = 8080

    # CloudWatch logs — no log driver config needed; Express Mode handles the agent
    aws_logs_configuration {
      log_group         = aws_cloudwatch_log_group.backend.name
      log_stream_prefix = "ecs"
    }

    # Env vars: table names, bucket name, KB IDs, model IDs, sidecar URL
    environment { name = "AWS_REGION"; value = var.region }
    # ... (9 table env vars, 3 KB ID env vars, 3 model ID env vars)

    # Secrets: pulled from Secrets Manager before container start
    secret {
      name       = "SIDECAR_TOKEN"
      value_from = aws_secretsmanager_secret.sidecar_token.arn
    }
  }

  network_configuration {
    subnets = data.aws_subnets.public.ids   # From vpc.tf
  }

  depends_on = [
    null_resource.jib_build,                      # Image must exist before ECS starts
    aws_iam_role_policy.ecs_task_execution_secrets # Secrets policy must attach first
  ]
}
```

**Rolling updates.** When you run `terraform apply` after a code change, Jib pushes a new image and the `jib_build` triggers change (because the `src_hash` changes). Terraform then sees the `image` reference in `ecs_express.tf` has changed and updates the service. ECS starts new tasks with the new image while old tasks continue serving traffic, then terminates the old tasks once the new ones are healthy. No downtime window.

---

## 8. Optional fallback

### `cloudfront.tf`

**Purpose.** Provisions a CloudFront distribution in front of the Express Mode URL. Disabled by default (`count = 0`). Enable only if the direct `*.ecs.eu-central-1.on.aws` URL causes problems from the Amplify frontend.

**Why it exists.** A previous experiment with App Runner discovered that Amplify's fetch infrastructure can sometimes block direct requests to AWS-managed service URLs due to how cross-origin requests are routed. If that symptom re-emerges with Express Mode, a CloudFront URL (`*.cloudfront.net`) acts as a neutral intermediary. See [DEPLOYMENT.md](DEPLOYMENT.md) for the full architectural rationale.

**How to enable it.**

```bash
terraform apply -var="enable_cloudfront_fallback=true"
terraform output cloudfront_url
# Then set VITE_API_BASE on the Amplify environment to the cloudfront_url value.
```

**Key settings in the distribution.**

```hcl
# Managed-CachingDisabled — do not cache API responses (they are dynamic)
cache_policy_id = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"

# Managed-AllViewerExceptHostHeader — forward all request headers except Host.
# This prevents CloudFront's Host header from confusing the backend.
origin_request_policy_id = "b689b0a8-53d0-40ab-baf2-68738e2966ac"

price_class = "PriceClass_100"   # EU + US edge nodes only — ~50% cheaper than global
```

**WAF warning.** Do not attach a WAF to the CloudFront distribution. AWS managed rules treat PDF bytes in multipart uploads as SQL injection and return 403. See [DEPLOYMENT.md](DEPLOYMENT.md) for the full WAF gotcha and the workaround if a stakeholder insists.

---

## 9. Spring Boot edits (recap of Wave 1)

These four files were edited or created to support the Terraform infrastructure.

### `pom.xml`

Two additions:

1. `spring-boot-starter-actuator` dependency — exposes `/actuator/health`. The ECS Express Mode ALB health check polls this path every 30 seconds. Without it, the ALB marks all tasks unhealthy and the service never becomes ready.
2. `jib-maven-plugin:3.5.1` — enables `./mvnw jib:build` from `jib_build.tf`. The `<from>` image is `eclipse-temurin:25-jre` (Java 25). The `<to>` image coordinate is supplied at build time via `-Djib.to.image`.

### `application.yaml`

Three changes:

1. `aws.bedrock.region: eu-central-1` — Bedrock runtime calls go to Frankfurt, not `us-east-1`.
2. `aws.bedrock.model-ids.*` keys with `${ENV_VAR:fallback}` syntax — Terraform injects the cross-region inference profile IDs via environment variables. If the env var is absent (local dev), the fallback value kicks in.
3. `management.endpoints.web.exposure.include: health` — exposes only the health endpoint, not metrics or env dumps. The `show-details: never` keeps the response minimal (just `{"status":"UP"}`).
4. `sidecar.token: ${SIDECAR_TOKEN:}` — the shared secret token, injected from Secrets Manager via ECS. The empty fallback means local dev runs without a token (SidecarClient skips the header when blank).

### `CorsConfig.java`

A new `@Configuration` class implementing `WebMvcConfigurer`. It allows:

- `https://*.amplifyapp.com` — your Amplify-hosted React frontend.
- `http://localhost:5173` — Vite dev server.

All HTTP methods and headers are allowed. `allowCredentials(true)` enables the browser to send cookies or auth headers cross-origin. `maxAge(3600)` caches the preflight response for one hour, reducing OPTIONS round trips.

### `SidecarClient.java`

The sidecar is a separate Express Mode service that handles sanctions screening, evidence hashing, proof trees, and compliance maps. The client communicates over HTTPS with a shared secret for authentication.

Key change: the constructor now accepts `@Value("${sidecar.token:}") String token`. If the token is non-blank, it adds `X-Sidecar-Token: <token>` as a default header on every `WebClient` request. The sidecar service validates this header and rejects requests without it. This is the v1 authentication model; v2 will use VPC private networking to eliminate the need for the token entirely.

---

## 10. Common workflows

### Deploy from scratch

```bash
# 0. Prerequisites: AWS CLI v2, Terraform >= 1.9, jq, Git Bash (not cmd.exe)
cd java-backend/infra

# 1. Copy the vars template and fill in your values
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars: set aws_profile, opensanctions_api_key, sidecar_base_url

# 2. Download provider plugins (once per checkout)
terraform init

# 3. Preview what will be created — read this before applying
terraform plan

# 4. Create everything (~10 min: Jib build + ECR push + KB creation + ECS startup)
terraform apply

# 5. Get the backend URL
terraform output backend_url

# 6. Verify the service is healthy
curl https://$(terraform output -raw backend_url)/actuator/health
# Expected: {"status":"UP"}

# 7. Set VITE_API_BASE on the Amplify environment
terraform output vite_api_base
```

### Redeploy after a code change

```bash
# Just run apply — the src_hash trigger in jib_build.tf detects the change
terraform apply
# Jib builds and pushes a new image, ECS rolls tasks automatically.
```

### Toggle the CloudFront fallback

```bash
# Enable:
terraform apply -var="enable_cloudfront_fallback=true"
terraform output cloudfront_url
# Set VITE_API_BASE on Amplify to the cloudfront_url value.

# Disable (tears down the distribution):
terraform apply -var="enable_cloudfront_fallback=false"
```

### Force a Jib rebuild without code changes

```bash
terraform taint null_resource.jib_build
terraform apply
```

### Force KB re-ingestion after adding seed documents

```bash
# Add new files to java-backend/seed/regulations/ (or policies/ or controls/)
# aws_s3_object.seed_docs will upload them automatically on apply.
# Then taint the ingestion job to re-run:
terraform taint 'null_resource.kb_ingestion["regulations"]'
terraform apply
```

### Teardown

```bash
terraform destroy
# All resources are deleted in reverse dependency order.
# ECR images are deleted (force_delete = true on the repo).
# S3 objects are deleted (force_destroy = true on buckets).
# Secrets Manager secrets are deleted immediately (recovery_window_in_days = 0).
# CloudFront distributions (if enabled) take ~15 min to fully disable after destroy returns.
```

### Re-request Bedrock model access

AWS Console → Bedrock → Model access (left sidebar) → Manage model access → select region `eu-central-1` → request Anthropic Claude Opus 4.7, Sonnet 4.6, Haiku 4.5, and Amazon Titan Embeddings v2. Approval can take hours; start at T=0 of the hackathon.

---

## 11. Troubleshooting crib sheet

**`AccessDeniedException` when calling Bedrock.**
Bedrock model access has not been approved yet for your account in `eu-central-1`. Wait for approval in the Bedrock console (Bedrock → Model access), then re-run `terraform apply`. The KB creation step will succeed once models are accessible.

**Jib push fails with `401 Unauthorized`.**
The ECR login token is valid for 12 hours. If you started `apply` and it ran the Jib step more than 12 hours later, the token has expired. Taint and re-apply:
```bash
terraform taint null_resource.jib_build
terraform apply
```

**`aws s3vectors` command not found.**
S3 Vectors requires a recent AWS CLI v2. Upgrade: `winget upgrade Amazon.AWSCLI` or download from the AWS CLI v2 release page.

**ECS service stuck in `PROVISIONING` for more than 5 minutes.**
Express Mode provisions the ALB asynchronously. If it is stuck, check the stopped task reason:
```bash
aws ecs list-tasks --cluster default --service-name launchlens-backend
aws ecs describe-tasks --cluster default --tasks <task-arn>
```
The most common cause is a failed health check: the container started but `/actuator/health` is not returning 200. Check CloudWatch logs (`/ecs/launchlens-backend`) for startup errors.

**`/tmp/kb_id_regulations.txt: No such file or directory`.**
The KB ID files are written to `/tmp` during `apply`. If you open a new shell or reboot between a partial apply and the next apply, these files are gone. Taint the KB resources to re-create them:
```bash
terraform taint 'null_resource.bedrock_kb["regulations"]'
terraform taint 'null_resource.bedrock_kb["policies"]'
terraform taint 'null_resource.bedrock_kb["controls"]'
terraform apply
```

**Spring Boot starts but DynamoDB calls fail with `ResourceNotFoundException`.**
The table name in `application.yaml` does not match what Terraform created. Terraform names tables `launchlens-<table>`. Check `terraform output dynamodb_tables` and compare against `aws.dynamodb.*-table` values in `application.yaml`.

**`terraform plan` shows an unexpected destroy of a resource you want to keep.**
Never run `apply` until you have read and understood the plan. If Terraform wants to destroy something you did not intend, check: did the resource name change? Did a `for_each` key change? A destroy+recreate is sometimes unavoidable but should be a conscious decision.

**Stale `.terraform.lock.hcl` causing `init` to fail.**
If `terraform init` complains about provider hash mismatches after a version bump:
```bash
rm .terraform.lock.hcl
terraform init
```

**CloudFront distribution takes too long to teardown.**
CloudFront distributions are globally distributed. After `terraform destroy`, AWS needs ~15 minutes to fully decommission the distribution. The Terraform call returns earlier; the AWS-side cleanup continues in the background. This is normal.

**`data.external.kb_ids` returns an empty ID in outputs.**
The `data "external"` block reads `/tmp/kb_id_<source>.txt`. If these files are missing (new session after a partial apply), the output is empty. Taint `null_resource.bedrock_kb["*"]` and re-apply to regenerate them.

---

## 12. Glossary

**IAM role.** An AWS identity with no password. AWS services (ECS, Bedrock) assume roles to act on your behalf. A role has a trust policy (who can assume it) and one or more permission policies (what it can do).

**IAM policy.** A JSON document listing `Allow` or `Deny` rules for AWS API actions. Attached to a role (or user). Inline policies are embedded in the role definition; managed policies are standalone and reusable.

**Trust policy.** The part of an IAM role that says which AWS service or account is allowed to assume it. For ECS tasks the trust principal is `ecs-tasks.amazonaws.com`.

**Fargate.** AWS's serverless container runtime. You specify CPU and memory; AWS picks the host. No EC2 instance to manage or patch.

**ECS cluster.** A logical grouping of ECS services and tasks. We use the pre-existing `default` cluster — Express Mode places tasks there automatically.

**ECS service.** A long-running ECS entity that maintains a desired number of task copies (replicas), handles health checks, and integrates with the ALB.

**ALB (Application Load Balancer).** An AWS load balancer that routes HTTP/HTTPS traffic to ECS tasks. Express Mode provisions and manages one ALB per service automatically.

**Target group.** An ALB concept: a set of endpoints (ECS task IPs + port) that the ALB routes traffic to. Express Mode manages target group registration automatically.

**ACM cert (AWS Certificate Manager).** AWS's managed TLS certificate service. Express Mode uses an AWS-managed cert on the `*.ecs.<region>.on.aws` domain — no manual ACM request or DNS validation needed.

**Route 53.** AWS's DNS service. Not used here — the `*.ecs.eu-central-1.on.aws` domain is pre-configured by AWS. Custom domain setup would require Route 53, but the hackathon uses the provided URL.

**VPC (Virtual Private Cloud).** An isolated network in AWS. We use the account's default VPC (pre-created by AWS) and its public subnets to keep setup simple.

**Subnet.** A range of IP addresses within a VPC. Public subnets have a route to the internet via an Internet Gateway; private subnets do not. ECS tasks are placed in public subnets so they can pull images from ECR and reach Bedrock.

**Security group.** A stateful firewall at the ENI (network interface) level. Express Mode manages security groups for the ALB and tasks automatically.

**ECR (Elastic Container Registry).** AWS's private Docker/OCI image registry. Images are stored by digest; tags (like `latest`) are mutable pointers to digests.

**RAG (Retrieval-Augmented Generation).** A pattern where relevant document passages are retrieved from a vector store and included in the LLM prompt, so the model answers from your data rather than its training data alone.

**Embedding.** A vector (array of floats) that represents a piece of text semantically. Similar texts have similar vectors. Titan Embeddings v2 produces 1024-dimensional float32 vectors.

**Vector store.** A database optimised for storing and searching embeddings by similarity. S3 Vectors is AWS's managed vector store; it uses cosine similarity search.

**Inference profile.** A virtual Bedrock model ID with the `eu.` prefix (e.g., `eu.anthropic.claude-sonnet-4-6-v1:0`) that routes requests across multiple EU AWS regions for higher throughput and EU data residency. No extra cost versus the foundation model ID.

**Presigned URL.** A time-limited, signed URL that grants a specific HTTP action on a specific S3 object. The backend generates it; the browser uses it to PUT a file directly to S3 without credentials.

**KB (Bedrock Knowledge Base).** AWS's managed RAG pipeline: point it at an S3 bucket, choose an embedding model and vector store, and it handles chunking, embedding, and indexing. At query time, `bedrock-agent-runtime:Retrieve` runs the full embed + search + return cycle.

---

## 13. Where to go next

**Migrate state to S3 backend.** When more than one person is deploying, you need shared state with a lock so two applies do not race. Terraform docs: [S3 backend](https://developer.hashicorp.com/terraform/language/backend/s3). The migration command is `terraform init -migrate-state` after adding the `backend "s3"` block to `backend.tf`. You will need to create the S3 bucket and DynamoDB lock table first (a bootstrapping problem — create them manually or with a separate minimal Terraform config).

**Add WAF.** Read the WAF gotcha in [DEPLOYMENT.md](DEPLOYMENT.md) before attaching any WAF ACL to the ALB or CloudFront. Short version: AWS managed SQLi rules 403 PDF multipart uploads. Scope-down upload paths or set SQLi body rules to Count mode before enabling WAF.

**Sidecar private networking.** v1 uses a public Express URL + shared secret token for the sidecar. v2 should use AWS Cloud Map (service discovery) with private security groups so the sidecar is unreachable from the internet. See the sidecar VPC connector pattern mentioned in [DEPLOYMENT.md](DEPLOYMENT.md).

**Custom domain.** Replace the `*.ecs.eu-central-1.on.aws` URL with `api.yourdomain.com`. You will need Route 53 (or your DNS provider), ACM for a custom cert, and a listener rule pointing the domain at the Express ALB. This is a post-hackathon task; the provided URL is sufficient for Amplify integration.

**Deployment controller upgrade.** The current controller is `ECS` (rolling update). To move to ECS-native blue/green (zero-downtime with instant rollback), change the deployment controller type in `ecs_express.tf`. No data migration required — it is a one-attribute change.

**Provider upgrade for S3 Vectors and Bedrock KB.** Once `hashicorp/aws` adds native `aws_s3_vectors_*` and the `S3_VECTORS` storage type in `aws_bedrockagent_knowledge_base`, replace the `null_resource` blocks in `s3_vectors.tf` and `bedrock_kb.tf` with native resources. Use `terraform import` to bring the existing AWS objects under the new resource definitions without deleting and recreating them.
