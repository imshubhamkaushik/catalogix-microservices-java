variable "aws_region" {
  description = "AWS region to deploy into"
  type        = string
  default     = "ap-south-1"
}

variable "cluster_name" {
  description = "Logical name of the cluster — used as a prefix for all resources and tags"
  type        = string
  default     = "catalogix-cluster"
}

variable "environment" {
  description = "Deployment environment (dev/staging/prod) — used as a prefix for all resources and tags"
  type        = string
  default     = "dev"
}

variable "project_name" {
  description = "Project name prefix"
  type        = string
  default     = "catalogix"

}
# db_password is intentionally not a variable. The RDS master password is chosen by the operator with
# `python bootstrap.py credentials` (scripts/python/credentials.py), stored in Secrets Manager as
# "<env_prefix>/operator-credentials", and read by main.tf. No pipeline parameter or tfvars file is involved.
variable "smtp_password" {
  description = "SMTP auth password for critical Alertmanager email alerts. Left empty by default (a real one is never committed) — until supplied, the critical-receiver's auth_password_file will be empty and Alertmanager just won't send mail, same as the null receiver it replaces. Supply via -var or a gitignored *.auto.tfvars file, never a committed default. (to/smarthost/auth_username aren't secrets — passed directly as Jenkins --set flags instead, not routed through here.)"
  type        = string
  default     = ""
  sensitive   = true
}
