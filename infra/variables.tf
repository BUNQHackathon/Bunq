variable "project_prefix" {
  description = "Short name prefix applied to all resource names"
  type        = string
  default     = "launchlens"
}

variable "region" {
  description = "AWS region for all resources"
  type        = string
  default     = "eu-central-1"
}

variable "bedrock_region" {
  description = "AWS region used for Bedrock runtime and Knowledge Bases"
  type        = string
  default     = "eu-central-1"
}

variable "aws_profile" {
  description = "AWS CLI named profile to use for authentication"
  type        = string
  default     = "default"
}

variable "image_tag" {
  description = "Docker image tag to deploy to ECS"
  type        = string
  default     = "latest"
}

variable "amplify_origin" {
  description = "Amplify hosting origin allowed in S3 CORS and Spring CORS config"
  type        = string
  default     = "https://*.amplifyapp.com"
}

variable "sidecar_base_url" {
  description = "Base URL of the sidecar Express service (empty until sidecar is deployed)"
  type        = string
  default     = ""
}

variable "enable_cloudfront_fallback" {
  description = "Set to true to provision a CloudFront distribution in front of the Express URL"
  type        = bool
  default     = false
}

variable "opensanctions_api_key" {
  description = "API key for the OpenSanctions data API"
  type        = string
  default     = ""
  sensitive   = true
}
