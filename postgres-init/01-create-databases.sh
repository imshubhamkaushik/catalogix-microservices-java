#!/bin/bash
# Database-per-service: each service gets its own logical database on this
# shared Postgres instance (one database, formerly ${POSTGRES_DB}, held every
# service's tables together — this is the split that undoes that).
#
# Local/dev uses one Postgres instance with a separate logical database per
# application service. Database boundaries prevent Flyway/schema changes in one
# service from colliding with another service while keeping local infrastructure
# lightweight. Credentials are shared in this development setup; production
# hardening can use service-specific database roles and grants.
set -e

for db in catalogix-users catalogix-catalog catalogix-inventory catalogix-cart catalogix-payment catalogix-checkout catalogix-notification; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<EOSQL
    SELECT 'CREATE DATABASE $db OWNER $POSTGRES_USER'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$db')\gexec
EOSQL
done
