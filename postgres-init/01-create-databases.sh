#!/bin/bash
# Database-per-service: each service gets its own logical database on this
# shared Postgres instance (one database, formerly ${POSTGRES_DB}, held every
# service's tables together — this is the split that undoes that).
#
# Honest scope note: this is the "shared cluster, isolated databases" tier of
# the pattern, appropriate for local/dev and not unusual in real lower
# environments either. It is NOT the same as separate managed instances
# (e.g. one RDS cluster per service) that a production deployment of this
# architecture would eventually want, and every service still authenticates
# as the same Postgres role (${POSTGRES_USER}), so isolation here is by
# database name, not by credential — a further hardening step (per-service
# roles with grants scoped to just their own database) is a natural follow-up
# that didn't make it into this pass. What this DOES already give you: each
# service's Flyway migrations run against a database only it has tables in,
# so a bug in one service's schema can't collide with another's, and
# database-per-service is enforced in the JDBC URL every service actually
# uses, not just as a convention.
set -e

for db in catalogix_users catalogix_catalog catalogix_inventory catalogix_cart catalogix_promotions catalogix_payment catalogix_checkout catalogix_notification; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    SELECT 'CREATE DATABASE $db OWNER $POSTGRES_USER'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$db')\gexec
EOSQL
done
