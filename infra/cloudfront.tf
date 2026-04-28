# Optional CloudFront fallback — only created when enable_cloudfront_fallback=true.
# Use case: if https://<svc>.ecs.eu-central-1.on.aws misbehaves from Amplify
# (e.g., CORS or routing issues), toggle with:
#   terraform apply -var="enable_cloudfront_fallback=true"
# No WAF attached — see DEPLOYMENT.md WAF gotcha before enabling.

resource "aws_cloudfront_distribution" "backend" {
  count   = var.enable_cloudfront_fallback ? 1 : 0
  enabled = true
  comment = "${local.name_prefix} CloudFront fallback for ECS Express backend"

  origin {
    domain_name = aws_ecs_express_gateway_service.backend.ingress_paths[0].endpoint
    origin_id   = "${local.name_prefix}-ecs-origin"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id = "${local.name_prefix}-ecs-origin"
    # Managed-CachingDisabled: do not cache API responses.
    cache_policy_id = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
    # Managed-AllViewerExceptHostHeader: forward all headers except Host.
    origin_request_policy_id = "b689b0a8-53d0-40ab-baf2-68738e2966ac"

    allowed_methods        = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods         = ["GET", "HEAD"]
    viewer_protocol_policy = "redirect-to-https"
    compress               = true
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  # EU + US only — cheapest price class (~50% cheaper than global).
  price_class = "PriceClass_100"

  tags = {
    Project = local.name_prefix
  }
}
