# Per-service databases + roles on the shared RDS instance.
#
# Each backend service gets its own database and login role. The role is limited
# to its service database, while the Terraform PostgreSQL provider uses the
# shared master connection to create and manage these resources.
#
# Isolation tier: role-level isolation on one shared RDS instance, not seven
# independent database instances. A shared-instance failure or resource
# constraint can still affect every service.
#
# This module intentionally exposes credentials to the root module instead of
# writing Secrets Manager resources itself. The root module merges them into
# the single secret version managed by module.app_secrets.

variable "readonly_username" {
  description = "Optional read-only login for humans (psql/pgAdmin). Empty = do not create it."
  type        = string
  default     = ""
}

variable "readonly_password" {
  description = "Password of the read-only login. Required when readonly_username is set."
  type        = string
  default     = ""
  sensitive   = true
}

locals {
  create_readonly = var.readonly_username != "" && var.readonly_password != ""
}

variable "services" {
  description = "Map of service name => database name, e.g. { \"user-svc\" = \"catalogix_users\" }"
  type        = map(string)
}

resource "random_password" "service_db" {
  for_each = var.services

  length  = 20
  special = false # avoids JDBC URL encoding issues, same reasoning as the master DB password

  keepers = {
    service = each.key
  }
}

resource "postgresql_role" "service" {
  for_each = var.services

  name     = replace(each.key, "-", "_") # Postgres role names can't contain hyphens
  login    = true
  password = random_password.service_db[each.key].result

  # No superuser/createdb/createrole — this role can only do what its
  # grant below explicitly allows.
  superuser       = false
  create_role     = false
  create_database = false
}

resource "postgresql_database" "service" {
  for_each = var.services

  name  = each.value
  owner = postgresql_role.service[each.key].name

  allow_connections = true
}

resource "postgresql_grant" "service_schema" {
  for_each = var.services

  database    = postgresql_database.service[each.key].name
  role        = postgresql_role.service[each.key].name
  schema      = "public"
  object_type = "schema"
  privileges  = ["CREATE", "USAGE"]

  depends_on = [postgresql_database.service]
}

# ---------------------------------------------------------------------------
# Optional read-only login for humans.
#
# Lets you look at the data with psql/pgAdmin without using the master credential (which can do
# anything) or an application role (which can write). It can CONNECT to every service database
# and SELECT from every table, nothing else. The tables are created later by each service's
# Flyway migrations, so besides granting SELECT on existing tables, default privileges make
# tables the service role creates in the future readable too.
#
# The instance is not publicly reachable — connect through a tunnel (see DEVOPS.md).
# ---------------------------------------------------------------------------
resource "postgresql_role" "readonly" {
  count = local.create_readonly ? 1 : 0

  name     = var.readonly_username
  login    = true
  password = var.readonly_password

  superuser       = false
  create_role     = false
  create_database = false
}

resource "postgresql_grant" "readonly_connect" {
  for_each = local.create_readonly ? var.services : {}

  database    = postgresql_database.service[each.key].name
  role        = postgresql_role.readonly[0].name
  object_type = "database"
  privileges  = ["CONNECT"]
}

resource "postgresql_grant" "readonly_schema" {
  for_each = local.create_readonly ? var.services : {}

  database    = postgresql_database.service[each.key].name
  role        = postgresql_role.readonly[0].name
  schema      = "public"
  object_type = "schema"
  privileges  = ["USAGE"]

  depends_on = [postgresql_grant.readonly_connect]
}

resource "postgresql_grant" "readonly_tables" {
  for_each = local.create_readonly ? var.services : {}

  database    = postgresql_database.service[each.key].name
  role        = postgresql_role.readonly[0].name
  schema      = "public"
  object_type = "table"
  objects     = []
  privileges  = ["SELECT"]

  depends_on = [postgresql_grant.readonly_schema]
}

resource "postgresql_default_privileges" "readonly_future_tables" {
  for_each = local.create_readonly ? var.services : {}

  database    = postgresql_database.service[each.key].name
  role        = postgresql_role.readonly[0].name
  owner       = postgresql_role.service[each.key].name
  schema      = "public"
  object_type = "table"
  privileges  = ["SELECT"]

  depends_on = [postgresql_grant.readonly_schema]
}
