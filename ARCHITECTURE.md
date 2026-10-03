# Catalogix architecture

Catalogix is a seven-service Spring Boot backend behind an Nginx gateway, with React as the frontend, RabbitMQ for asynchronous notifications, and PostgreSQL database-per-service separation on a shared instance. The current service set intentionally excludes the old promotions, reviews, returns, and wishlist features.

## Service map

```text
                                  ┌─────────────────┐
        browser ─────────────────▶│     AWS ALB     │
                                  └────────┬────────┘
                                           │
                                  ┌────────▼────────┐
                                  │     gateway     │
                                  │ nginx :11000    │
                                  │ /api routing    │
                                  │ rate limiting   │
                                  └───────┬─────────┘
                           ┌──────────────┼──────────────┐
                           │              │              │
                           ▼              ▼              ▼
                    /api/users/*   /api/products/*   /api/orders/*
                           │              │              │
                      user-svc       catalog-svc     checkout-svc
                       :11010          :11002           :11007
                           │              │              │
                           │              ▼              ├──▶ cart-svc
                           │        inventory-svc        ├──▶ inventory-svc
                           │           :11003            └──▶ payment-svc
                           │
                           └──────────────┬─────────────────────────┐
                                          │ RabbitMQ                  │
                                          ▼                           ▼
                                   notification-svc              frontend
                                      :11008                         :11001

Gateway also routes `/api/cart` and the admin notification/outbox APIs.
`inventory-svc` and `payment-svc` are internal-only application services.
RabbitMQ carries user and checkout events to notification-svc; notification-svc
sends email and records an audit log.
```

The `/api` prefix keeps backend routes separate from React client-side routes. The gateway owns routing and request rate limiting; it does not aggregate business data.

## Service responsibilities

| Service | Owns | Key dependencies |
|---|---|---|
| **user-svc** | accounts, authentication, refresh sessions, profiles, addresses, seller requests | RabbitMQ for email-verification/password-reset events |
| **catalog-svc** | product metadata, ownership, moderation state | inventory-svc for live stock values |
| **inventory-svc** | stock quantities and idempotent reserve/release operations | none at runtime |
| **cart-svc** | user cart contents and totals | catalog-svc, inventory-svc |
| **payment-svc** | mock card/UPI/COD payments and refunds | none at runtime |
| **checkout-svc** | orders, order state machine, payment flow, stock compensation outbox | cart/catalog/inventory/payment services; RabbitMQ for order events |
| **notification-svc** | email delivery and notification audit log | RabbitMQ, user-svc preference lookup, SMTP/Mailpit |

## Order flow

`checkout-svc` is the order saga/orchestrator.

1. **Read and reserve.** For each cart item, checkout gets the product price from catalog-svc and reserves stock through inventory-svc.
2. **Persist.** The order is written with an idempotency key. If the database write fails, any already-created stock reservations are released.
3. **Pay.** A payment attempt is sent to payment-svc outside the checkout database transaction. Confirmed, declined, and unknown outcomes are handled explicitly.
4. **Compensate when needed.** Failed stock releases are recorded in `compensation_outbox` and retried by `CompensationOutboxProcessor` using row locking so multiple checkout replicas can work safely.
5. **Publish events.** Successful confirmation and cancellation publish RabbitMQ events; notification-svc consumes them and sends the corresponding email when the user has opted in.

The compensation outbox is deliberately narrower than the old design: it now handles stock-release compensation only.

## Notification flow

User password-reset and email-verification requests are published by user-svc to RabbitMQ. Checkout publishes order-confirmed and order-cancelled events. notification-svc consumes those events, checks notification preferences when needed, sends mail through the configured SMTP provider, and writes an audit record to `notification_log`.

In local development, Mailpit provides the SMTP endpoint. The gateway exposes only the admin notification-log read API; normal password-reset and order-email flows remain asynchronous through RabbitMQ.

## Data layout

Each backend service has a logical PostgreSQL database of its own on the shared PostgreSQL/RDS instance:

```text
catalogix-users
catalogix-catalog
catalogix-inventory
catalogix-cart
catalogix-payment
catalogix-checkout
catalogix-notification
```

Terraform creates a dedicated PostgreSQL role and database for each service. This is role-level isolation on one shared instance, not seven independent managed database instances.

## Platform and observability

- **EKS + Helm:** application services, gateway, frontend, and RabbitMQ run in the `catalogix` namespace.
- **External Secrets Operator:** application and monitoring secrets are synchronized from AWS Secrets Manager.
- **Prometheus / Grafana / Alertmanager:** metrics, dashboards, and alert delivery run in the `monitoring` namespace.
- **Gitleaks / SonarQube / Trivy:** secret scanning, static analysis, quality-gate enforcement, and container/Kubernetes scanning are handled in the main application pipeline.
- **OWASP ZAP:** retained as an optional manual DAST stage rather than a core deployment gate.


## Scope decisions

The current architecture keeps notification-svc and RabbitMQ because asynchronous email is a useful real integration boundary: password reset, email verification, order confirmation, and cancellation can all be delivered without coupling the request path directly to SMTP.

The deleted promotions, review, returns, and wishlist capabilities are no longer part of the application API or deployment topology. Historical Flyway migration files that created their old tables remain immutable so existing databases can migrate forward safely; cleanup migrations remove those legacy schema objects from current deployments.

The project is intentionally not split further into service discovery/config-server/BFF layers. Kubernetes service DNS and the gateway are sufficient for the current scale and make the deployment easier to operate and explain.
