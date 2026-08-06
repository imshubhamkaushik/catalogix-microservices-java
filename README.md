# Catalogix

> **Architecture note:** this README's diagram and service list below
> describe the original 4-service design (user-svc / product-svc / order-svc
> / notification-svc). That design was later split further — cart, coupons,
> payment, and inventory each became their own service, and order-svc became
> a saga-orchestrating checkout-svc — see **[ARCHITECTURE.md](./ARCHITECTURE.md)**
> for the current 9-service map, including which parts of this README below
> are now out of date. The rest of this document (setup, auth design,
> reliability patterns, etc.) is still accurate unless ARCHITECTURE.md says
> otherwise.

A small microservices-based product catalogue: register, log in, browse/manage products, and place orders.

```
                        ┌─────────────┐
   browser  ───────────▶│   gateway   │  nginx reverse proxy + rate limiting, port 8080
                        └──────┬──────┘
              ┌────────────────┼────────────────┬───────────────┐
              ▼                ▼                 ▼               ▼
        /users/*         /products/*        /orders/*        everything else
              │                │                 │               │
        ┌─────▼─────┐   ┌──────▼──────┐   ┌──────▼──────┐  ┌─────▼──────┐
        │ user-svc  │   │ product-svc │   │  order-svc  │  │frontend-svc│
        │  :8081    │   │   :8082     │◀──┤   :8083     │  │  (nginx)   │
        └──┬───┬────┘   └──────┬──────┘   └────┬───┬────┘  └────────────┘
           │   │               │                │   │
           │   └───────┐  ┌────┘                │   │
           │           ▼  ▼                     │   │
           │     ┌───────────┐                  │   │
           │     │  Postgres │◀─────────────────┘   │
           │     └───────────┘                      │
           │                                         ▼
           │                                 ┌───────────────┐
           └────────────────────────────────▶│notification-svc│  :8084
                                              └───────┬───────┘
                                                      ▼
                                              ┌───────────────┐
                                              │    Mailpit     │  dev SMTP catcher, :8025
                                              └───────────────┘
```

- **user-svc** — registration, login, access+refresh tokens, password reset, email verification, profile editing, admin user directory
- **product-svc** — product catalogue: search, pagination, categories, stock, caching
- **order-svc** — places/cancels orders; calls product-svc (circuit-breaker-guarded) to price items and reserve/restore stock; fires order confirmation/cancellation emails
- **notification-svc** — internal, service-to-service email delivery (SYSTEM-token-only API); everything it sends lands in Mailpit locally
- **frontend-svc** — React (Vite) SPA, served as static files by nginx
- **gateway** — nginx reverse proxy + rate limiting; the single entry point browsers talk to

Each service also exposes interactive API docs at `/swagger-ui.html` (e.g. `http://localhost:8081/swagger-ui.html` for user-svc).

## Quick start

```bash
cp .env.example .env
# edit .env — at minimum set a real JWT_SECRET (32+ chars) for anything beyond local testing
cp secrets/postgres_password.txt.example secrets/postgres_password.txt
# make sure secrets/postgres_password.txt's contents match POSTGRES_PASSWORD in .env exactly
docker compose up --build
```

Then open **http://localhost:8080**. Register an account, or register with an email listed in `ADMIN_EMAILS` to get an admin account (admins see everyone's orders and can manage the user directory).

Postgres is also published on `localhost:5432`, and each backend service on its own port (8081/8082/8083/8084) for direct access/debugging, if you want to inspect them without going through the gateway. Every email any service sends lands in **Mailpit** at `http://localhost:8025` — nothing is ever actually delivered anywhere, so this is where you'll see verification links, password reset links, and order confirmations.

## What's in this version

### Authentication & security
- **Access + refresh tokens.** Login/registration issue a short-lived JWT (15 min by default) plus a long-lived opaque refresh token, stored hashed (SHA-256) in user-svc's DB. `POST /users/refresh` rotates it — each use revokes the old refresh token and issues a new one, so a stolen-then-reused old token is easy to spot (its hash is already marked revoked). The frontend does this silently: a 401 triggers one shared refresh-and-retry before falling back to logging out.
- **`POST /users/logout`** revokes one session; **`POST /users/logout-all`** revokes every refresh token for the account ("log out everywhere").
- **Login brute-force lockout.** 5 failed attempts for an email locks it out for 15 minutes (429 + `Retry-After` header), independent of whatever password is tried next.
- **Gateway-level rate limiting**, stricter on `/users/login|register|refresh` specifically, as defense-in-depth on top of each service's own per-IP `RateLimiterFilter`.
- **Fail-fast startup check** if `JWT_SECRET` is still the placeholder value from `.env.example`.
- **Docker secret for the Postgres password** (the officially-supported `POSTGRES_PASSWORD_FILE` convention — see `secrets/`). The app services still connect via `SPRING_DATASOURCE_PASSWORD` as a plain env var, which is how a real secrets manager (Vault, AWS/GCP Secrets Manager) would inject it in production anyway — this repo's `.env` file is the local stand-in for that.
- Roles (`USER`/`ADMIN` via `ADMIN_EMAILS`), ownership checks on delete/manage — unchanged from before.

### Reliability (order-svc)
- **Idempotent order creation.** Pass an `Idempotency-Key` header when placing an order; a retried request with the same key returns the original order (`200`) instead of creating a duplicate (`201`) — including recovering cleanly from the rare race where two concurrent requests with the same key both try to create the order at once.
- **Circuit breaker** (Resilience4j) around every call order-svc makes to product-svc. Opens after real infrastructure failures (timeouts, connection refused, 5xx) — not business outcomes like "out of stock" or "not found," which are deliberately excluded from tripping it.
- **Outbox pattern for compensation.** If restoring stock (on order cancellation, or rolling back an earlier line item after a later one fails) can't reach product-svc live, the intent is written to a `stock_adjustment_outbox` table in the same transaction, and a scheduled job retries it until it succeeds or exhausts 10 attempts (then it's marked `DEAD_LETTER` for manual attention — see `GET /admin/outbox` and `POST /admin/outbox/{id}/retry`, admin-only). The background job authenticates to product-svc with a short-lived system-issued token, since there's no user request to forward one from.

### API & performance polish
- **OpenAPI/Swagger UI** on all three services (`/swagger-ui.html`, `/v3/api-docs`), via springdoc — zero extra code beyond the existing controller annotations.
- **In-memory caching** (Caffeine, 30s TTL) on product-svc's single-product read — the hot path order-svc hits when pricing every line item.
- **Frontend tests** (Vitest + React Testing Library) covering the login/register flow, the auth context's token lifecycle (including silent refresh and forced logout), and the order cart/idempotency-key flow.
- Bumped `axios` and `vite` to patched versions after `npm audit` flagged known CVEs in the versions this project started with.

### Account & notifications (new: notification-svc)
- **notification-svc**, a fourth microservice whose only job is sending email, behind a SYSTEM-role-only internal API (`POST /notifications/email`) — no end-user token can call it directly. Every attempt (sent or failed) is logged to a `notification_log` table for audit purposes; this is *not* a retry queue (see Known limitations).
- **Password reset.** `POST /users/forgot-password` (always returns 202 regardless of whether the email exists, to avoid account enumeration) emails a one-hour link; `POST /users/reset-password` consumes it and revokes every existing session for that account, since a stolen refresh token from before the reset shouldn't keep working.
- **Email verification.** A verification email goes out on registration (24h link) and again whenever you change your email address in Account settings; `POST /users/resend-verification` covers a lost/expired original. Verification is tracked (and shown in the UI) but **not** enforced as a login gate — see Known limitations for why.
- **Profile editing.** `PATCH /users/me` updates name/email/password; changing email or password requires `currentPassword` as a lightweight re-auth check.
- **Order confirmation/cancellation emails**, fired `@Async` (off the request thread, via a small dedicated thread pool) so a slow or unreachable notification-svc never adds latency to placing or cancelling an order — best-effort, same philosophy as everything else in order-svc's reliability story.
- New frontend pages: `/forgot-password`, `/reset-password`, `/verify-email`, and an **Account** page (profile editing, resend-verification, "log out everywhere").

### Everything from the previous round (still true)
- Real JWT-verified identity everywhere (no more trusting a client-sent `X-USER-ID` header).
- `order-svc` itself, product categories/stock/pagination, Flyway actually wired up (it wasn't before), the `gateway` reverse proxy (nginx previously only served static files — there was nowhere for API calls to land), and the frontend's real login screen / Orders page / admin Users directory.

## Roadmap (in progress across sessions)

This is being built in phases, each unlocking the next:

1. ~~notification-svc + password reset + email verification + profile editing~~ ✅ this round
2. **Commerce completeness** (server-side cart, payment step, richer order status, coupons) — next
3. **Message broker conversion** — convert order-svc → notification-svc (and eventually → product-svc) from direct HTTP calls to published events, using notification-svc's existence as the first real consumer
4. Catalog depth (images, variants, reviews) and a seller dashboard
5. Real search (OpenSearch), last since it's the most invasive new infrastructure

## Configuration

See `.env.example` for the full list. Must-changes for anything beyond local testing:
- `JWT_SECRET` — `openssl rand -base64 48`. Must be identical across every service.
- `secrets/postgres_password.txt` — copy from the `.example` file and keep it in sync with `POSTGRES_PASSWORD` in `.env`.
- `MAIL_HOST`/`MAIL_PORT`/`MAIL_FROM` in notification-svc's config — point these at a real SMTP provider instead of Mailpit for anything beyond local dev.
- `FRONTEND_BASE_URL` — must be wherever the gateway is actually reachable from a browser, since it's used to build the links inside verification/reset emails.

## Running tests

```bash
mvn test                    # all backend services (needs Docker for the Testcontainers-based product-svc integration test)
cd frontend-svc && npm test # Vitest + React Testing Library
```

## Known limitations

Being upfront about what's *not* production-hardened here:

- **Still not a full distributed saga.** The outbox covers *compensation* reliably now, but the initial multi-item reservation loop in `createOrder` is still synchronous, one item at a time. A saga/choreography approach (or moving the whole flow onto a message broker) would be the next step for true cross-service atomicity.
- **In-memory state doesn't survive a restart or scale past one replica.** The login-lockout tracker, the Caffeine product cache, and the circuit breaker's state are all per-instance. Fine for a single instance; a multi-replica deployment would want these backed by something shared (Redis, typically) instead.
- **`user-svc`, `product-svc`, `order-svc` and `notification-svc` have no container-level healthcheck.** They run on a distroless base image with no shell or wget, so `docker compose`'s `healthcheck:` isn't practical there; they rely on `depends_on` + `restart: unless-stopped` instead. `gateway` and `frontend-svc` (both nginx-based) do have real healthchecks.
- **Email verification isn't a login gate.** An unverified account can still do everything — the UI just shows a banner. Making it a hard gate is a product decision more than a technical one (it locks out anyone whose verification email got lost/delayed/spam-filtered), so this build tracks the status without enforcing it. Flip it in `UserSvc.login()` if you want it enforced.
- **notification-svc's `notification_log` is an audit trail, not a retry queue.** A failed send is recorded and returned to the caller as `status: FAILED`, but nothing automatically retries it (unlike order-svc's stock-adjustment outbox, which does). A lost password-reset email today just means the user requests another one.
- **No message broker yet.** user-svc/order-svc call notification-svc synchronously (well, `@Async` on order-svc's side, but still a direct HTTP call). See the Roadmap above — this is the natural next seam for an event-driven conversion.
- **PATCH /products/{id}/stock is open to any authenticated user**, not just the product's owner — intentional, since placing an order needs to decrement a *different* user's stock, but it does mean any logged-in user could technically restock or deplete someone else's listing directly if they called the endpoint themselves outside the normal order flow.
- **`react-router-dom` has an open moderate-severity advisory** (open-redirect related) with no patched 6.x release available as of this writing — `npm audit` will still flag it. Tracked, not ignored; re-run `npm audit` periodically and upgrade when a fix lands (likely requires a v7 migration).
- **Refresh tokens are returned in the JSON body**, not an httpOnly cookie, consistent with this app's existing localStorage-based session model — a real hardening pass would move to httpOnly cookies to reduce XSS exposure, which'd need a bigger frontend/CORS rework than fits here.
- **No CI pipeline, no distributed tracing/centralized logs, no IaC/Kubernetes manifests.** Still docker-compose-only; see the "what level is this at" conversation for the fuller list of what a production deployment would still need.
