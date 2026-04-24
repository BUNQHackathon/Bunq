# v1: local state. TODO post-hackathon: migrate to S3 + DynamoDB lock backend.
terraform {
  backend "local" {}
}
