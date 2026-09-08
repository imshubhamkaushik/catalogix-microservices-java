# S3 storage backends for Loki and Tempo, replacing the PVC-backed
# filesystem storage they started on. Each gets its own bucket and its own
# IRSA role (same pattern as modules/eso — a dedicated IAM role trusted
# only by a specific K8s service account via OIDC, not a shared role).
#
# Why separate from modules/eso instead of reusing its role: ESO's role is
# scoped to Secrets Manager access only. Giving it S3 permissions too would
# mean the External Secrets Operator pod — which already has read access to
# every credential in Secrets Manager — also gets write access to
# observability data. Keeping these separate means a compromised Loki or
# Tempo pod can only touch its own bucket, not app secrets, and vice versa.
#
# Bucket name / role ARN are NOT passed to Helm via values.yaml (values
# files aren't Terraform-templated — see helm/monitoring's own history of
# getting this wrong once already). They're published to SSM instead,
# matching the exact pattern modules/rds already uses for the RDS endpoint
# — Jenkinsfile.platform-infra's "Deploy Monitoring Stack" stage reads them
# via `aws ssm get-parameter` and passes them as `--set` flags.

variable "cluster_name" {
  type = string
}

variable "oidc_provider_arn" {
  type = string
}

variable "oidc_provider" {
  type = string
}

variable "permissions_boundary_arn" {
  type = string
}

variable "retention_days" {
  description = "Days to retain logs/traces in S3 before lifecycle expiry. Keep short for a fresher/learning project — this is genuinely billable storage, not a one-time cost."
  type        = number
  default     = 14
}

# ─── Loki bucket + role ────────────────────────────────────────────────────

resource "aws_s3_bucket" "loki" {
  bucket        = "${var.cluster_name}-loki-logs"
  force_destroy = true # convenient for a project you tear down and rebuild — see the same trade-off already called out on the ECR module for why this isn't a production default
}

resource "aws_s3_bucket_server_side_encryption_configuration" "loki" {
  bucket = aws_s3_bucket.loki.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "loki" {
  bucket = aws_s3_bucket.loki.id
  rule {
    id     = "expire-old-logs"
    status = "Enabled"
    filter {}
    expiration {
      days = var.retention_days
    }
  }
}

resource "aws_s3_bucket_public_access_block" "loki" {
  bucket                  = aws_s3_bucket.loki.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

data "aws_iam_policy_document" "loki_bucket_access" {
  statement {
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.loki.arn}/*"]
  }
  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.loki.arn]
  }
}

resource "aws_iam_policy" "loki" {
  name   = "${var.cluster_name}-loki-s3-policy"
  policy = data.aws_iam_policy_document.loki_bucket_access.json
}

data "aws_iam_policy_document" "loki_assume_role" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider}:sub"
      # Explicit service account name set in helm/monitoring/values.yaml's
      # loki.serviceAccount.name — not left to the chart's own default
      # naming, specifically so this trust condition can't silently drift
      # from whatever the chart happens to name it on a version bump.
      values = ["system:serviceaccount:monitoring:loki-sa"]
    }
    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "loki" {
  name                 = "${var.cluster_name}-loki-role"
  assume_role_policy   = data.aws_iam_policy_document.loki_assume_role.json
  permissions_boundary = var.permissions_boundary_arn
}

resource "aws_iam_role_policy_attachment" "loki" {
  role       = aws_iam_role.loki.name
  policy_arn = aws_iam_policy.loki.arn
}

# ─── Tempo bucket + role (same shape as Loki above) ───────────────────────

resource "aws_s3_bucket" "tempo" {
  bucket        = "${var.cluster_name}-tempo-traces"
  force_destroy = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tempo" {
  bucket = aws_s3_bucket.tempo.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "tempo" {
  bucket = aws_s3_bucket.tempo.id
  rule {
    id     = "expire-old-traces"
    status = "Enabled"
    filter {}
    expiration {
      days = var.retention_days
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tempo" {
  bucket                  = aws_s3_bucket.tempo.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

data "aws_iam_policy_document" "tempo_bucket_access" {
  statement {
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.tempo.arn}/*"]
  }
  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.tempo.arn]
  }
}

resource "aws_iam_policy" "tempo" {
  name   = "${var.cluster_name}-tempo-s3-policy"
  policy = data.aws_iam_policy_document.tempo_bucket_access.json
}

data "aws_iam_policy_document" "tempo_assume_role" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider}:sub"
      values   = ["system:serviceaccount:monitoring:tempo-sa"]
    }
    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "tempo" {
  name                 = "${var.cluster_name}-tempo-role"
  assume_role_policy   = data.aws_iam_policy_document.tempo_assume_role.json
  permissions_boundary = var.permissions_boundary_arn
}

resource "aws_iam_role_policy_attachment" "tempo" {
  role       = aws_iam_role.tempo.name
  policy_arn = aws_iam_policy.tempo.arn
}

# ─── SSM — Jenkins reads these at monitoring deploy time ──────────────────

resource "aws_ssm_parameter" "loki_bucket" {
  name  = "/${var.cluster_name}/loki-bucket"
  type  = "String"
  value = aws_s3_bucket.loki.bucket
}

resource "aws_ssm_parameter" "loki_role_arn" {
  name  = "/${var.cluster_name}/loki-role-arn"
  type  = "String"
  value = aws_iam_role.loki.arn
}

resource "aws_ssm_parameter" "tempo_bucket" {
  name  = "/${var.cluster_name}/tempo-bucket"
  type  = "String"
  value = aws_s3_bucket.tempo.bucket
}

resource "aws_ssm_parameter" "tempo_role_arn" {
  name  = "/${var.cluster_name}/tempo-role-arn"
  type  = "String"
  value = aws_iam_role.tempo.arn
}
