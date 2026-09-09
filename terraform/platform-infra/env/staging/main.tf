# Read outputs from bootstrap-infra (VPC, subnets).
# bootstrap-infra must be applied first before running platform-infra.
#
# Staging shares the same VPC as dev (single bootstrap-infra layer).
# Cluster and RDS resources are fully isolated by separate module instances
# and name-prefixed with "catalogix-staging".

data "terraform_remote_state" "bootstrap" {
  backend = "s3"
  config = {
    bucket = "catalogix-tfstate"
    key    = "bootstrap-infra/terraform.tfstate"
    region = "ap-south-1"
  }
}

data "aws_caller_identity" "current" {}

resource "random_password" "db" {
  length  = 24
  special = false # avoids JDBC URL encoding issues with special characters

  # keepers tie the password lifecycle to the RDS instance name.
  # Without keepers, a terraform state refresh or re-import silently regenerates the password, rotating the secret and breaking the running app.
  # Password only changes if the RDS name changes — which is always intentional.
  keepers = {
    rds_name = "${local.env_prefix}-db"
  }
}

resource "random_password" "jwt" {
  length  = 48
  special = false

  keepers = {
    cluster_name = local.env_prefix
  }
}

resource "random_password" "rabbitmq" {
  length  = 24
  special = false

  keepers = {
    cluster_name = local.env_prefix
  }
}

resource "aws_kms_key" "eks" {
  description             = "EKS secrets encryption key — staging"
  deletion_window_in_days = 7
}

locals {
  # Pulled from remote state so every module uses the same source of truth
  vpc_id          = data.terraform_remote_state.bootstrap.outputs.vpc_id
  vpc_cidr        = data.terraform_remote_state.bootstrap.outputs.vpc_cidr
  private_subnets = data.terraform_remote_state.bootstrap.outputs.private_subnets
  public_subnets  = data.terraform_remote_state.bootstrap.outputs.public_subnets

  # Pulled from EKS module output — used in providers.tf for kubernetes/helm
  cluster_name        = module.eks.cluster_name
  cluster_endpoint    = module.eks.cluster_endpoint
  cluster_certificate = module.eks.cluster_certificate

  # Change to "catalogix-prod" in the prod env directory
  env_prefix = "${var.cluster_name}-${var.environment}"

  # Single source of truth for the DB username - referenced by RDS, Secrets Manager, and Helm
  db_username = "catalogix"

  # Was missing entirely from this env — see env/dev/main.tf's identical
  # local for the full rationale. Without this, module.eks, module.alb,
  # and module.eso below were all missing a required argument
  # (permissions_boundary_arn has no default — see each module's
  # variables.tf), which would fail `terraform plan` outright the moment
  # anyone actually tried to apply this environment.
  permissions_boundary_arn = data.terraform_remote_state.bootstrap.outputs.jenkins_boundary_arn
}

# Security Groups
module "sg" {
  source       = "../../modules/security-groups"
  project_name = local.env_prefix
  vpc_id       = local.vpc_id
  vpc_cidr     = local.vpc_cidr

  jenkins_sg_id     = data.terraform_remote_state.bootstrap.outputs.jenkins_sg_id
  eks_cluster_sg_id = module.eks.cluster_sg_id
}

# EKS
# Staging uses slightly larger nodes (t3.medium - only in paid AWS tier // c7i-flex.large - only in free tier) and allows scaling to 3
# to validate the app under realistic load before production.
module "eks" {
  source = "../../modules/eks"

  cluster_name    = local.env_prefix
  cluster_version = "1.36"
  private_subnets = local.private_subnets

  # Bumped again: RabbitMQ went from 1 replica to 3 for HA (soft
  # anti-affinity wants to spread them across distinct nodes) — desired
  # nudged up so there's actual room for that spread, not just theoretical
  # max headroom. Still an estimate, not a measured figure.
  min_size     = 2
  max_size     = 5
  desired_size = 4

  # KMS key for EKS secrets
  kms_key_arn = aws_kms_key.eks.arn

  jenkins_role_arn  = data.terraform_remote_state.bootstrap.outputs.jenkins_role_arn
  jenkins_public_ip = data.terraform_remote_state.bootstrap.outputs.public_ip_jenkins

  # my_ip_cidr comes from bootstrap-infra's remote state, captured
  # once at bootstrap-infra apply time, instead of this module independently
  # querying checkip.amazonaws.com again at a potentially much later time.
  # See modules/eks/main.tf for the full rationale.
  my_ip_cidr = data.terraform_remote_state.bootstrap.outputs.jenkins_my_ip_cidr

  # Whoever runs terraform apply automatically gets console access.
  # No variable or tfvars entry needed.
  console_iam_arn = data.aws_iam_session_context.current.issuer_arn

  # permissions_boundary_arn is required on every IAM role created in this module.
  permissions_boundary_arn = local.permissions_boundary_arn
}

data "aws_eks_cluster" "this" {
  name       = module.eks.cluster_name
  depends_on = [module.eks]
}

data "aws_eks_cluster_auth" "this" {
  name       = module.eks.cluster_name
  depends_on = [module.eks]
}

# ECR is shared between dev and staging — same images, different Helm releases.
# No separate ECR module needed for staging.

# ALB Controller
#
# FIX: this block previously passed vpc_id/region (not declared by
# modules/alb/variables.tf — only cluster_name, oidc_provider,
# oidc_provider_arn, and permissions_boundary_arn exist) and a
# providers = { kubernetes, helm } passthrough map (the module's
# required_providers only declares aws; kubernetes/helm are commented out
# in modules/alb/main.tf). Both failed `terraform validate` outright —
# "An argument named X is not expected here" / a provider-passthrough
# error — before staging could ever be planned, let alone applied.
# Matches env/dev/main.tf's module "alb" call exactly now.
module "alb" {
  source = "../../modules/alb"

  cluster_name             = module.eks.cluster_name
  oidc_provider_arn        = module.eks.oidc_provider_arn
  oidc_provider            = trimprefix(module.eks.oidc_provider_arn, "arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/")
  permissions_boundary_arn = local.permissions_boundary_arn

  depends_on = [module.eks, module.sg]
}

# RDS
# Staging uses the same instance class as dev (db.t4g.micro) to keep cost down.
# backup_retention_period = 1 unlike dev (0) — staging should catch data-loss bugs.
module "rds" {
  source = "../../modules/rds"

  project_name = "${local.env_prefix}-db"
  # RDS requires SOME initial database name at instance creation — this one
  # is never used by any service. The 9 real per-service databases
  # (catalogix_users, catalogix_catalog, etc.) are created by
  # module.db_roles below, matching postgres-init/01-create-databases.sh's
  # local-dev naming exactly.
  db_name                 = "catalogix_admin"
  username                = local.db_username
  password                = random_password.db.result
  private_subnets         = local.private_subnets
  security_group_id       = module.sg.rds_sg
  db_engine_version       = "18.1"
  backup_retention_period = 0
  multi_az                = false
  skip_final_snapshot     = true

  ssm_parameter_path = "/${local.env_prefix}/rds-endpoint"
}

module "db_roles" {
  source = "../../modules/db-roles"

  services = {
    "user-svc"         = "catalogix_users"
    "catalog-svc"      = "catalogix_catalog"
    "inventory-svc"    = "catalogix_inventory"
    "cart-svc"         = "catalogix_cart"
    "promotions-svc"   = "catalogix_promotions"
    "payment-svc"      = "catalogix_payment"
    "checkout-svc"     = "catalogix_checkout"
    "notification-svc" = "catalogix_notification"
    "review-svc"       = "catalogix_reviews"
  }

  depends_on = [module.rds]
}

# Secrets Manager
module "secrets" {
  source = "../../modules/secrets-manager"

  secret_name = "${local.env_prefix}/db-credentials"

  secret_values = {
    db_user = local.db_username
    db_pass = random_password.db.result
  }
}

module "app_secrets" {
  source = "../../modules/secrets-manager"

  secret_name = "${local.env_prefix}/app-secrets"

  secret_values = merge(
    {
      jwt_secret        = random_password.jwt.result
      rabbitmq_user     = "catalogix"
      rabbitmq_password = random_password.rabbitmq.result
    },
    { for svc, creds in module.db_roles.credentials : "db_user_${replace(svc, "-", "_")}" => creds.username },
    { for svc, creds in module.db_roles.credentials : "db_password_${replace(svc, "-", "_")}" => creds.password }
  )
}

# Same reasoning as env/dev/main.tf — separate secret, monitoring namespace
# needs its own ExternalSecret since K8s Secrets are namespace-scoped.
module "alerting_secrets" {
  source = "../../modules/secrets-manager"

  secret_name = "${local.env_prefix}/alerting-secrets"

  secret_values = {
    smtp_password = var.smtp_password
  }
}

# External Secrets Operator
module "eso" {
  source = "../../modules/eso"

  cluster_name             = module.eks.cluster_name
  oidc_provider_arn        = module.eks.oidc_provider_arn
  oidc_provider            = trimprefix(module.eks.oidc_provider_arn, "arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/")
  region                   = var.aws_region
  permissions_boundary_arn = local.permissions_boundary_arn

  providers = {
    kubernetes = kubernetes.after_eks
    helm       = helm.after_eks
  }

  depends_on = [module.eks, module.alb, module.sg]
}

module "observability_storage" {
  source = "../../modules/observability-storage"

  cluster_name             = local.env_prefix
  oidc_provider_arn        = module.eks.oidc_provider_arn
  oidc_provider            = trimprefix(module.eks.oidc_provider_arn, "arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/")
  permissions_boundary_arn = local.permissions_boundary_arn
  retention_days           = 14

  depends_on = [module.eks]
}

# gp3 StorageClass — same reasoning as dev env.
# Kept in root module (not inside module.eks) so the kubernetes provider
# resolves only after the cluster endpoint is known.
resource "kubernetes_storage_class_v1" "gp3" {
  provider = kubernetes.after_eks

  metadata {
    name = "gp3-sc"
    annotations = {
      "storageclass.kubernetes.io/is-default-class" = "false"
    }
  }

  storage_provisioner    = "ebs.csi.aws.com"
  reclaim_policy         = "Retain"
  volume_binding_mode    = "WaitForFirstConsumer"
  allow_volume_expansion = true

  parameters = {
    type = "gp3"
  }

  lifecycle {
    prevent_destroy = false
  }

  depends_on = [module.eks, module.alb, module.sg]
}
