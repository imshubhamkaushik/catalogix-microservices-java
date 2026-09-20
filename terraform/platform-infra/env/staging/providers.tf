terraform {
  backend "s3" {
    bucket       = "catalogix-tfstate"
    key          = "platform-infra/staging/terraform.tfstate"
    region       = "ap-south-1"
    encrypt      = true
    use_lockfile = true
  }

  required_version = ">= 1.12.0"

  required_providers {
    aws        = { source = "hashicorp/aws", version = "~> 6.0" }
    kubernetes = { source = "hashicorp/kubernetes", version = "~> 3.0" }
    helm       = { source = "hashicorp/helm", version = "~> 3.0" }
    kubectl    = { source = "alekc/kubectl", version = "~> 2.0" }
    tls        = { source = "hashicorp/tls", version = "~> 4.0" }
    random     = { source = "hashicorp/random", version = "~> 3.0" }
    postgresql = { source = "cyrilgdn/postgresql", version = "~> 1.25" }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "Catalogix"
      ManagedBy   = "Terraform"
      Environment = "staging"
    }
  }
}

# Kubernetes and Helm providers are aliased so they only resolve
# after module.eks is created and the cluster endpoint is known.
provider "kubernetes" {
  alias                  = "after_eks"
  host                   = data.aws_eks_cluster.this.endpoint
  cluster_ca_certificate = base64decode(data.aws_eks_cluster.this.certificate_authority[0].data)

  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = ["eks", "get-token", "--cluster-name", data.aws_eks_cluster.this.name]
  }
}

provider "helm" {
  alias = "after_eks"

  kubernetes = {
    host                   = data.aws_eks_cluster.this.endpoint
    cluster_ca_certificate = base64decode(data.aws_eks_cluster.this.certificate_authority[0].data)

    exec = {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = ["eks", "get-token", "--cluster-name", data.aws_eks_cluster.this.name]
    }
  }
}

# Was entirely absent from this file — module.eso's `providers = { ...
# kubectl = kubectl.after_eks }` block (main.tf) and the aws_auth
# kubectl_manifest resource (also being added to main.tf) both reference
# this alias. Without it, both would fail with "there is no explicitly
# configured provider ... with this alias" — the kubectl_manifest
# resource can't even be added to this file until this exists.
provider "kubectl" {
  alias                  = "after_eks"
  host                   = module.eks.cluster_endpoint
  cluster_ca_certificate = base64decode(data.aws_eks_cluster.this.certificate_authority[0].data)
  load_config_file       = false # never reads ~/.kube/config — fully self-contained

  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args = [
      "eks", "get-token",
      "--cluster-name", data.aws_eks_cluster.this.name,
      "--region", var.aws_region
    ]
  }
}

# Same as env/dev/providers.tf — connects as RDS master user to create
# per-service databases + roles. modules/security-groups already opens
# 5432 from Jenkins' SG to RDS (rds_ingress_jenkins), same as dev.
provider "postgresql" {
  host            = module.rds.rds_address
  port            = 5432
  username        = local.db_username
  password        = random_password.db.result
  superuser       = false
  connect_timeout = 15
  sslmode         = "require"
}
