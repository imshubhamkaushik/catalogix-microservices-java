terraform {
  backend "s3" {
    bucket       = "catalogix-tfstate"
    key          = "platform-infra/dev/terraform.tfstate"
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
    # Manages per-service databases + roles on the shared RDS instance —
    # see module.db_roles in main.tf. Connects directly to Postgres (not
    # via the AWS API), so it needs real network reachability to the RDS
    # endpoint — already open: modules/security-groups' rds_ingress_jenkins
    # rule allows Jenkins' SG to reach RDS on 5432, which is what actually
    # runs `terraform apply`.
    postgresql = { source = "cyrilgdn/postgresql", version = "~> 1.25" }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "Catalogix"
      ManagedBy   = "Terraform"
      Environment = "dev"
    }
  }
}

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

# Connects as the RDS master user to create per-service databases + roles.
# depends_on module.rds isn't declared here (providers can't have
# depends_on directly) — module.db_roles in main.tf depends on module.rds
# instead, which is what actually sequences this correctly: Terraform
# won't try to open this connection until the RDS instance exists.
provider "postgresql" {
  host            = module.rds.rds_address
  port            = 5432
  username        = local.db_username
  password        = random_password.db.result
  superuser       = false
  connect_timeout = 15
  sslmode         = "require"
}