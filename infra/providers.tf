provider "aws" {
  region   = var.region
  profile  = var.aws_profile
  insecure = true # corporate proxy intercepts TLS

  default_tags {
    tags = {
      Project   = var.project_prefix
      ManagedBy = "terraform"
    }
  }
}
