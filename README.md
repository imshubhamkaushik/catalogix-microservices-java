# Catalogix

Catalogix is a small e-commerce application built as a seven-service Spring Boot backend behind an Nginx gateway, with a React/Vite frontend, RabbitMQ-backed asynchronous notifications, PostgreSQL database-per-service separation, and a DevSecOps delivery stack for AWS EKS.

> Infrastructure and CI/CD details live in [DEVOPS.md](DEVOPS.md). The current service and dependency map lives in [ARCHITECTURE.md](ARCHITECTURE.md).

## Current architecture

```text
Browser
  │
  ▼
AWS ALB (EKS) / localhost:11000 (local)
  │
  ▼
Gateway (Nginx :11000)
  ├── /api/users/*       → user-svc :11010
  ├── /api/products/*    → catalog-svc :11002
  ├── /api/cart/*        → cart-svc :11004
  ├── /api/orders/*      → checkout-svc :11007
  ├── /api/admin/*       → checkout-svc :11007
  └── /api/notifications → notification-svc :11008

checkout-svc ──→ catalog-svc
             ├→ inventory-svc :11003
             ├→ cart-svc
             └→ payment-svc :11006

user-svc ──────┐
               ├── RabbitMQ ──→ notification-svc ──→ SMTP / Mailpit
checkout-svc ──┘

PostgreSQL: one logical database per backend service
Prometheus / Grafana / Alertmanager: cluster observability
```

The current application deliberately does not include the former promotions, reviews, returns, or wishlist features. Their historical Flyway migrations are retained only where required for schema history, with current cleanup migrations removing those legacy objects from an upgraded database.

## Services

| Service | Responsibility |
|---|---|
| `user-svc` | Registration, login, JWT/refresh sessions, password reset, email verification, profiles, addresses, seller requests, admin users |
| `catalog-svc` | Product catalogue, search/filter/sort, ownership, moderation, live stock composition |
| `inventory-svc` | Stock initialization, reservation/release, operation idempotency |
| `cart-svc` | Persistent user cart and cart-to-checkout handoff |
| `payment-svc` | Mock CARD/UPI/COD payments, idempotency, refunds |
| `checkout-svc` | Orders, payment orchestration, state transitions, cancellation, compensation outbox |
| `notification-svc` | RabbitMQ consumers for user/order events, email delivery, notification audit log |
| `gateway` | Nginx API routing, rate limiting, SPA entry point |
| `frontend` | React/Vite single-page application |

## Authentication and account flows

- Access JWTs are short-lived; refresh tokens are opaque and stored hashed in `user-svc`.
- Refresh rotates the token so a previously used refresh token cannot simply be replayed.
- Login failures are tracked and accounts are temporarily locked after repeated failures.
- Password reset uses a one-hour single-use token and revokes existing sessions after a successful reset.
- Email verification is delivered asynchronously through RabbitMQ and notification-svc.
- Changing the account email or password requires the current password.
- Seller access is request/approval based; registering never grants `SELLER` or `ADMIN`.

## Commerce and reliability

### Cart

The cart is persisted in `cart-svc`. Product and stock information is resolved from the owning services, and checkout receives a clean item handoff rather than promotion/coupon state.

### Checkout saga

`checkout-svc` orchestrates the order path:

1. Resolve product pricing from `catalog-svc`.
2. Reserve stock through `inventory-svc`.
3. Save the order with an idempotency key.
4. Pay through `payment-svc` outside the checkout database transaction.
5. Release stock when a compensating action is required.
6. Record failed stock releases in `compensation_outbox` for retry/dead-letter handling.
7. Publish order-confirmed/order-cancelled events to RabbitMQ.

This keeps database transactions short while still handling duplicate submissions, ambiguous downstream outcomes, and partial-failure compensation explicitly.

### Payments

`payment-svc` is a deterministic mock payment boundary. It supports CARD, UPI, and COD flows, payment idempotency, and idempotent refunds. `checkout-svc` serializes order-level payment attempts so a concurrent request cannot double-charge the same order.

## Notifications

RabbitMQ is intentionally retained because it gives the notification flow a clean asynchronous boundary without coupling password-reset or order requests to SMTP availability.

`user-svc` publishes:

- `user.email-verification-requested`
- `user.password-reset-requested`

`checkout-svc` publishes:

- `order.confirmed`
- `order.cancelled`

`notification-svc` consumes those events, checks notification preferences for order mail, sends through the configured SMTP endpoint, and records the result in `notification_log`.

For local development, Mailpit catches the messages instead of delivering real email.

## Local development

Create the local environment and Postgres secret first:

```bash
cp .env.example .env
cp secrets/postgres_password.txt.example secrets/postgres_password.txt
```

Then make sure the password file matches `POSTGRES_PASSWORD` in `.env` and start the application:

```bash
docker compose up --build
```

Open:

- Application: `http://localhost:11000`
- Mailpit: `http://localhost:8025`
- RabbitMQ management: `http://localhost:25672`

The gateway is the normal browser-facing entry point. Backend services are internal to the Compose network.

## Configuration

See `.env.example` for the local configuration surface. The important settings include:

- `JWT_SECRET` — shared signing secret; do not keep the placeholder outside a throwaway local environment.
- `POSTGRES_USER` / `POSTGRES_PASSWORD` — local Postgres credentials.
- `RABBITMQ_USER` / `RABBITMQ_PASSWORD` — broker credentials used by user-svc, checkout-svc, and notification-svc.
- `MAIL_HOST` / `MAIL_PORT` / `MAIL_FROM` — notification-svc SMTP settings.
- `FRONTEND_BASE_URL` — base URL used when notification links are generated.

## Testing

Backend tests:

```bash
mvn test
```

Frontend tests:

```bash
cd frontend
npm test
```

The backend suite is intentionally focused on current behavior and critical failure modes rather than retaining tests for removed features or trivial forwarding cases.

## DevSecOps stack

The root application pipeline builds and scans the current nine application images (seven backend services plus frontend and gateway) and deploys the Helm release to EKS.

Core controls include Gitleaks, SonarQube quality gates, Trivy container/Helm scanning, Terraform validation, Helm-based deployment, Prometheus/Grafana/Alertmanager monitoring, and External Secrets Operator integration.

OWASP ZAP remains available as an optional manual DAST stage rather than a mandatory deployment gate.

## Repository structure

```text
backend/
  security-common/
  user-svc/
  catalog-svc/
  inventory-svc/
  cart-svc/
  payment-svc/
  checkout-svc/
  notification-svc/
frontend/
gateway/
helm/
  catalogix-hc/
  monitoring/
terraform/
  bootstrap-infra/
  platform-infra/
ansible/
scripts/
postgres-init/
Jenkinsfile.app-cicd
Jenkinsfile.platform-infra
ARCHITECTURE.md
DEVOPS.md
```

For the complete dependency graph, Terraform/Helm topology, monitoring details, and operational trade-offs, see [ARCHITECTURE.md](ARCHITECTURE.md) and [DEVOPS.md](DEVOPS.md).
