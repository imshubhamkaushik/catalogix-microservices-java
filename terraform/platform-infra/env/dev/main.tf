# This is the root module for the "platform-infra" Terraform project, which provisions the shared infrastructure components for the EKS cluster and its dependencies.

# terraform_remote_state - Read outputs from bootstrap-infra (VPC, subnets).
# bootstrap-infra must be applied first before running platform-infra.
data "terraform_remote_state" "bootstrap" {
  backend = "s3"
  config = {
    bucket = "catalogix-tfstate"
    key    = "bootstrap-infra/terraform.tfstate"
    region = "ap-south-1"
  }
}

# AWS data sources for dynamic values and to avoid hardcoding ARNs or account IDs.
data "aws_caller_identity" "current" {}

data "aws_iam_session_context" "current" {
  arn = data.aws_caller_identity.current.arn
}

# ---------------------------------------------------------------------------
# Credentials chosen by the OPERATOR (whoever deploys), not generated here.
#
# scripts/python/credentials.py (run from your laptop: `python bootstrap.py credentials`)
# stores them in ONE Secrets Manager secret, "<env_prefix>/operator-credentials". This
# pipeline needs no parameters: it just reads that secret. The IAM role of the Jenkins host
# already allows secretsmanager:GetSecretValue on "catalogix-*" secrets.
#
# Only the RDS master password has to pass through Terraform. The application admin,
# RabbitMQ and Grafana passwords are read straight from the same secret by External
# Secrets (helm/catalogix-hc, helm/monitoring) and never enter Terraform or its state.
#
# The RDS master password DOES land in the (encrypted, access-restricted) state file, exactly
# as the previous random_password did — Terraform must hand it to RDS and to the postgresql
# provider. Choosing it yourself changes who knows it, not where it is stored.
# ---------------------------------------------------------------------------
data "aws_secretsmanager_secret_version" "operator" {
  secret_id = "${local.env_prefix}/operator-credentials"
}

locals {
  operator_credentials = jsondecode(data.aws_secretsmanager_secret_version.operator.secret_string)

  db_master_password = lookup(local.operator_credentials, "db_master_password", "")

  # Optional human read-only database login (both keys, or neither).
  db_readonly_username = lookup(local.operator_credentials, "db_readonly_username", "")
  db_readonly_password = lookup(local.operator_credentials, "db_readonly_password", "")
}

# Same password policy as scripts/python/credentials.py. The pipeline's "Verify Operator
# Credentials" stage already checks this; the preconditions are the backstop for anyone
# running Terraform by hand.
resource "terraform_data" "operator_credentials_check" {
  lifecycle {
    precondition {
      condition     = length(local.db_master_password) > 0
      error_message = "db_master_password is missing from ${local.env_prefix}/operator-credentials. Run: python scripts/python/credentials.py --env dev"
    }
    precondition {
      condition = (
        length(local.db_master_password) >= 8 &&
        length(local.db_master_password) <= 64 &&
        can(regex("[a-z]", local.db_master_password)) &&
        can(regex("[A-Z]", local.db_master_password)) &&
        can(regex("[0-9]", local.db_master_password)) &&
        !can(regex("[/\"@[:space:]]", local.db_master_password))
      )
      error_message = "db_master_password must be 8-64 characters with a lowercase letter, an uppercase letter and a digit, and must not contain / \" @ or whitespace (RDS rejects those). Change it with: python scripts/python/credentials.py --env dev --only db_master_password"
    }
    precondition {
      condition     = (local.db_readonly_username == "") == (local.db_readonly_password == "")
      error_message = "db_readonly_username and db_readonly_password must be set together (or both left out) in ${local.env_prefix}/operator-credentials."
    }
  }
}

# JWT signing key, shared across all 7 backend services (they all validate
# tokens issued by user-svc). Same keepers pattern as the DB password —
# only rotates if the cluster name changes, never on an incidental
# terraform refresh, since rotating this invalidates every live session.
resource "random_password" "jwt" {
  length  = 48
  special = false

  keepers = {
    cluster_name = local.env_prefix
  }
}



# KMS key for EKS secrets encryption. Using a customer-managed key is a best practice for production workloads, but not strictly required for this demo since the default AWS-managed key would work fine for encrypting EKS secrets.
resource "aws_kms_key" "eks" {
  description             = "EKS secrets encryption key - dev environment"
  deletion_window_in_days = 7
  # enable_key_rotation is a best practice for long-lived keys, but not required for this demo since the key is only used to encrypt EKS secrets and doesn't have any direct human access or permissions attached to it. Enabling rotation adds complexity by creating new key versions every year, which would require updating the eks module with the new key ARN to avoid breaking changes.
  enable_key_rotation = true
}

# locals - centralize commonly used values and outputs from other modules to avoid duplication and ensure consistency across the configuration. This is a best practice for larger Terraform projects to improve maintainability and reduce the risk of errors from hardcoding values or referencing outputs directly in multiple places.
locals {
  # Pulled from remote state so every module uses the same source of truth
  vpc_id          = data.terraform_remote_state.bootstrap.outputs.vpc_id
  private_subnets = data.terraform_remote_state.bootstrap.outputs.private_subnets
  public_subnets  = data.terraform_remote_state.bootstrap.outputs.public_subnets

  # Pulled from EKS module output — used in providers.tf for kubernetes/helm
  cluster_name        = module.eks.cluster_name
  cluster_endpoint    = module.eks.cluster_endpoint
  cluster_certificate = module.eks.cluster_certificate

  # Required on every IAM role created below — see bootstrap-infra/iam.tf
  # for why, and modules/eks/variables.tf for the per-module rationale.
  permissions_boundary_arn = data.terraform_remote_state.bootstrap.outputs.jenkins_boundary_arn

  # Env-specific prefix — change to "catalogix-staging" or "catalogix-prod" in other workspaces
  env_prefix = "${var.cluster_name}-${var.environment}"

  # Single source of truth for the DB username - referenced by RDS, Secrets Manager, and Helm
  db_username = "catalogix"
}

# Security Groups — all in the shared VPC from bootstrap
module "sg" {
  source       = "../../modules/security-groups"
  project_name = local.env_prefix
  vpc_id       = local.vpc_id
  vpc_cidr     = data.terraform_remote_state.bootstrap.outputs.vpc_cidr

  jenkins_sg_id     = data.terraform_remote_state.bootstrap.outputs.jenkins_sg_id
  eks_cluster_sg_id = module.eks.cluster_sg_id # implicit depends_on module.eks
}

# EKS
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

# EKS DATA (safe, resolves after creation)
data "aws_eks_cluster" "this" {
  name = module.eks.cluster_name

  depends_on = [module.eks]
}

# # EKS AUTH (safe, resolves after creation)
# data "aws_eks_cluster_auth" "this" {
#   name = module.eks.cluster_name

#   depends_on = [module.eks]
# }

# ECR — global, no VPC dependency
module "ecr" {
  source = "../../modules/ecr"
  repositories = [
    "catalogix-user-svc", "catalogix-catalog-svc", "catalogix-inventory-svc", "catalogix-cart-svc", 
    "catalogix-payment-svc", "catalogix-checkout-svc", "catalogix-notification-svc",
    "catalogix-frontend", "catalogix-gateway"
  ]
}

# ALB Controller (installs the AWS Load Balancer Controller into EKS)
module "alb" {
  source = "../../modules/alb"

  cluster_name             = module.eks.cluster_name
  oidc_provider_arn        = module.eks.oidc_provider_arn
  oidc_provider            = trimprefix(module.eks.oidc_provider_arn, "arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/")
  permissions_boundary_arn = local.permissions_boundary_arn

  depends_on = [module.eks, module.sg]
}

# RDS
module "rds" {
  source = "../../modules/rds"

  project_name = "${local.env_prefix}-db"
  # RDS requires SOME initial database name at instance creation — this one
  # is never used by any service. The 7 real per-service databases
  # (catalogix-<service>, etc.) are created by
  # module.db_roles below, matching postgres-init/01-create-databases.sh's
  # local-dev naming exactly.
  db_name                 = "catalogix_admin"
  username                = local.db_username
  password                = local.db_master_password
  private_subnets         = local.private_subnets
  security_group_id       = module.sg.rds_sg
  db_engine_version       = "18.1"
  backup_retention_period = 0
  multi_az                = false
  skip_final_snapshot     = true

  ssm_parameter_path = "/${local.env_prefix}/rds-endpoint"

  # Never create the database with a password that failed the policy check.
  depends_on = [terraform_data.operator_credentials_check]
}

# Per-service databases + roles — see modules/db-roles/main.tf for the full
# reasoning. Names here must stay in sync with
# postgres-init/01-create-databases.sh (local docker-compose dev) and
# helm/catalogix-hc/values-dev.yaml's `dbName` field per service — all
# three were cross-checked against each other by hand (see the
# conversation this was built in) since nothing here is validated by a
# shared source of truth across languages/tools.
module "db_roles" {
  source = "../../modules/db-roles"

  services = {
    "user-svc"         = "catalogix-users"
    "catalog-svc"      = "catalogix-catalog"
    "inventory-svc"    = "catalogix-inventory"
    "cart-svc"         = "catalogix-cart"
    "payment-svc"      = "catalogix-payment"
    "checkout-svc"     = "catalogix-checkout"
    "notification-svc" = "catalogix-notification"
  }

  # Optional human read-only login (off unless both keys are in the operator secret) —
  # lets you look at the data with psql/pgAdmin without ever using the master credential.
  readonly_username = local.db_readonly_username
  readonly_password = local.db_readonly_password

  depends_on = [module.rds]
}

# Secrets Manager
# Stores the generated credentials so the app can read them via ESO
# No one ever needs to know or handle the password except the app itself
module "secrets" {
  source = "../../modules/secrets-manager"

  # Namespaced name avoids collision if you add more envs (staging, prod).
  secret_name = "${local.env_prefix}/db-credentials"

  secret_values = {
    db_user = local.db_username
    db_pass = local.db_master_password
  }
}

# App secrets — JWT signing key + RabbitMQ admin credentials. Kept as a
# separate Secrets Manager secret (not merged into db-credentials above) so
# the two rotate independently: a JWT/RabbitMQ credential rotation
# shouldn't touch the DB password's lifecycle or vice versa.
#
# Key names here (jwt_secret, db_user_<svc>, db_password_<svc>, ...)
# are copied VERBATIM into the 'catalogix-secrets' K8s Secret by
# helm/catalogix-hc/templates/external-secrets.yaml (dataFrom.extract), and
# referenced by name from _helpers.tpl and values-*.yaml — rename with care.
module "app_secrets" {
  source = "../../modules/secrets-manager"

  secret_name = "${local.env_prefix}/app-secrets"

  # Single merged map — one Terraform resource owns this secret's version,
  # deliberately kept in one resource so a single Terraform resource owns
  # the complete secret version.
  secret_values = merge(
    {
      # Machine secrets only. The passwords a human chooses (RabbitMQ, the bootstrap admin,
      # Grafana, RDS master) live in "<env_prefix>/operator-credentials" instead — see the
      # data source at the top of this file. helm/catalogix-hc's ExternalSecret merges the two.
      jwt_secret = random_password.jwt.result
    },
    { for svc, creds in module.db_roles.credentials : "db_user_${replace(svc, "-", "_")}" => creds.username },
    { for svc, creds in module.db_roles.credentials : "db_password_${replace(svc, "-", "_")}" => creds.password }
  )
}

# Separate from app_secrets deliberately: Alertmanager runs in the
# `monitoring` namespace, not `catalogix` — a K8s Secret is namespace-scoped,
# so it needs its OWN ExternalSecret (see helm/monitoring/templates/
# alertmanager-secrets.yaml) pulling from its own Secrets Manager entry, not
# a copy of the app's JWT/RabbitMQ/DB credentials mounted somewhere they're
# not needed.
module "alerting_secrets" {
  source = "../../modules/secrets-manager"

  secret_name = "${local.env_prefix}/alerting-secrets"

  secret_values = {
    # Empty string until var.smtp_password is actually supplied — see
    # that variable's description in variables.tf for why it's never a
    # committed default. Alertmanager's critical-receiver just won't send
    # mail until this is real, same behavior as the null receiver it replaces.
    smtp_password = var.smtp_password
  }
}

# External Secrets Operator - syncs Secrets Manager secrets into K8s Secrets
# This replaces the manual process of creating K8s Secrets wth `kubectl create secrets` in the Jenkins pipeline
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
    kubectl    = kubectl.after_eks
  }

  # ESO must come after EKS nodes and ALB controller so the cluster is stable
  depends_on = [module.eks, module.alb, module.sg]

}


# gp3 StorageClass — used by Prometheus and Grafana — moved here from modules/eks/main.tf.
#
# This resource uses the kubernetes provider. Keeping it inside module.eks caused it to be included in the targeted apply (-target=module.eks),
# where the kubernetes provider resolves to localhost:80 because local.cluster_endpoint was "(known after apply)" at plan time.
# Placing it in the root module ensures it runs only in (full apply), when module.eks is in state, the endpoint is known, and the provider connects to the real cluster.
#
# WaitForFirstConsumer ensures the EBS volume is created in the same AZ as
# the pod that claims it — required for single-AZ deployments.
resource "kubernetes_storage_class_v1" "gp3" {
  provider = kubernetes.after_eks

  metadata {
    name = "gp3-sc"
    annotations = {
      # Not set as default to avoid silently provisioning volumes for other workloads
      "storageclass.kubernetes.io/is-default-class" = "false"
    }
  }

  storage_provisioner = "ebs.csi.aws.com"
  # reclaim_policy = Delete ensures the EBS volume is deleted when the PVC is deleted, avoiding orphaned volumes and unexpected AWS charges.
  reclaim_policy         = "Delete"
  volume_binding_mode    = "WaitForFirstConsumer"
  allow_volume_expansion = true

  parameters = {
    type = "gp3"
  }

  lifecycle {
    prevent_destroy = false
  }

  # depends_on updated from aws_eks_addon.ebs_csi (module-internal ref)
  # to module.eks — the root-level handle for the entire EKS module, which includes the ebs_csi addon internally.
  depends_on = [
    module.eks,
    module.alb,
    module.sg,
    module.eso
  ]
}

# ALB Controller - Helm release to install the AWS Load Balancer Controller into the EKS cluster. This is required for the Ingress resources in the app to work, and is a common component in EKS clusters that use ALB for ingress.
resource "helm_release" "alb_controller" {
  provider   = helm.after_eks
  name       = "aws-load-balancer-controller"
  namespace  = "kube-system"
  repository = "https://aws.github.io/eks-charts"
  chart      = "aws-load-balancer-controller"
  version    = "1.11.0"

  wait    = true
  timeout = 300 # Good practice so it doesn't hang forever if it fails

  values = [
    yamlencode({
      clusterName = module.eks.cluster_name
      region      = var.aws_region
      vpcId       = local.vpc_id
      serviceAccount = {
        create = true
        name   = "aws-load-balancer-controller"
        annotations = {
          "eks.amazonaws.com/role-arn" = module.alb.alb_role_arn
        }
      }
    })
  ]

  depends_on = [module.eks, module.alb]
}

# aws-auth ConfigMap — allows EKS worker nodes to join the cluster.
#
# Moved here from modules/eks/main.tf (was terraform_data + local-exec).
# Reason: local-exec required kubectl and the aws CLI to be installed on
# whichever machine runs terraform apply — a hidden runtime dependency that
# broke on clean CI runners and the Jenkins EC2 after a fresh Ansible run.
#
# The alecks/kubectl provider (alias = after_eks) is already configured in providers.tf and already used for the ClusterSecretStore in the ESO module.
# It authenticates via "aws eks get-token" exec block, so no local kubeconfig file is needed (load_config_file = false). 
# This is consistent with how every other Kubernetes resource is managed in this root module.
#
# replace_on_change = [module.eks.node_role_arn, module.eks.cluster_name]
# Re-apply the ConfigMap automatically when the node role or cluster changes.
resource "kubectl_manifest" "aws_auth" {
  provider = kubectl.after_eks

  # Heredoc keeps the format identical to what aws-iam-authenticator expects.
  # Double-yamlencode (outer manifest + inner mapRoles) introduces key-ordering and quoting edge cases with yamlencode — this is simpler and explicit.
  #
  # force_conflicts = true: if someone runs `kubectl apply` on this ConfigMap manually (e.g. to add a mapUsers entry), Terraform wins the field-manager conflict on the next apply and restores the desired state. 
  # This is intentional — additional mapRoles entries (e.g. for Fargate profiles or cluster access entries) should be added here, not outside Terraform.
  #
  # Drift detection: if the ConfigMap is deleted or corrupted, Terraform detects the diff via normal state comparison and re-applies on the next apply.
  # No triggers_replace needed — kubectl_manifest tracks real resource state, unlike terraform_data + local-exec which tracked nothing.
  yaml_body = <<-YAML
apiVersion: v1
kind: ConfigMap
metadata:
  name: aws-auth
  namespace: kube-system
data:
  mapRoles: |
    - rolearn: ${module.eks.node_role_arn}
      username: system:node:{{EC2PrivateDNSName}}
      groups:
        - system:bootstrappers
        - system:nodes
YAML

  force_conflicts = true

  depends_on = [module.eks]
}
