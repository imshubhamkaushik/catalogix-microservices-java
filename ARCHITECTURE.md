# Catalogix architecture (post-split)

This document replaces the architecture section of the original README, which
described the earlier 4-service design (user-svc / product-svc / order-svc /
notification-svc). That design is still a completely reasonable way to build
this app — see the note at the bottom of this file on when a split like this
one is actually worth it. This document describes what it became after
splitting cart, coupons, payment, and inventory out into their own services,
and turning order-svc into a proper saga orchestrator.

## Service map

```text
                                  ┌─────────────────┐
        browser ─────────────────▶│     AWS ALB     │
                                  └────────┬────────┘
                                           │
                                  ┌────────▼────────┐
                                  │     gateway     │
                                  │ nginx :80       │
                                  │ /api routing    │
                                  │ rate limiting   │
                                  └───────┬─────────┘
                           ┌──────────────┼──────────────┐
                           │              │              │
                           ▼              ▼              ▼
                    /api/users/*   /api/products/*   /api/orders/* ...
                           │              │              │
                      user-svc       catalog-svc     checkout-svc
                       :11001          :11002           :11007

                                          │
                                  everything else
                                          ▼
                                   frontend-svc
                                      :11000

      Gateway routes also include /api/cart, /api/wishlist, /api/coupons,
      /api/notifications, /api/reviews and /api/admin. inventory-svc (:11003)
      and payment-svc (:11006) remain internal-only and have no gateway route.

      checkout-svc calls catalog/inventory/promotions/payment/cart/user over
      the internal service network; catalog-svc calls inventory/review;
      review-svc calls checkout-svc. RabbitMQ carries user/checkout events to
      notification-svc. Every service exports traces via OTLP to the monitoring
      stack, and each service has its own logical Postgres database.
```

The `/api` prefix is intentional: the React application has client-side routes
like `/products`, `/orders`, `/users`, `/coupons` and `/reviews`. Keeping APIs
under `/api/*` means a browser refresh can unambiguously reach the SPA while
AJAX requests are routed to the correct backend service.

## What owns what

| Service | Owns | Notably does NOT own |
|---|---|---|
| **user-svc** | accounts, sessions/tokens, profiles | anything about products or orders |
| **catalog-svc** | product name/description/price/category | **stock** (moved to inventory-svc) |
| **inventory-svc** | stock levels, reserve/release (row-locked) | pricing, product metadata |
| **cart-svc** | a user's in-progress cart | reserving stock — carts are non-binding until checkout |
| **promotions-svc** | coupons, atomic redeem/release (row-locked) | discount *display* during cart browsing (that's a read-only preview call, not a redemption) |
| **payment-svc** | mock payment attempts | knowledge of orders beyond an opaque `orderId` |
| **checkout-svc** | orders, order items, the saga that creates/pays/cancels them | any of the above — it *calls* all five other services above to place one order |
| **notification-svc** | sending email, driven entirely by RabbitMQ events | nothing calls it synchronously anymore |

## The saga: placing an order

`checkout-svc`'s `CheckoutSvc.placeOrder()` is the orchestrator. Steps 1–2 are
compensable; if anything fails at any point up through the DB save in step 3,
everything already committed gets unwound:

1. **Reserve.** For each item: fetch price (catalog-svc), reserve stock
   (inventory-svc, row-locked).
2. **Redeem.** If a coupon code is present, atomically re-validate and
   redeem it (promotions-svc, row-locked) — this is the one moment a coupon
   actually gets used; browsing/cart-applying a coupon only ever calls the
   read-only `/preview` endpoint.
3. **Persist.** Save the order locally.

If step 3 fails — including the idempotency-key race two concurrent
checkouts can hit — steps 1 and 2 are compensated: stock is released,
the coupon redemption is released. Compensation is attempted live first;
if the downstream service is unreachable, it's queued to
`compensation_outbox` and retried by `CompensationOutboxProcessor`
(`FOR UPDATE SKIP LOCKED`, safe under multiple replicas).

Paying for or cancelling an order later follows the same compensate-on-failure
shape (see `payOrder()` / `cancelOrder()` in `CheckoutSvc`).

## Deliberately NOT split further

- **Cart stays adjacent to checkout, not merged into it** — it's still its
  own service so it can be read/written independently of order placement,
  but nothing about it needed choreography or events; it's a thin service.
- **No API gateway logic beyond routing/rate limiting** — no BFF-style aggregation beyond
  catalog-svc composing its own stock reads from inventory-svc for external
  API-shape compatibility.
- **No service discovery / config server** — docker-compose's DNS-by-container-name
  is doing that job. Fine at this scale; Eureka/Consul (or Kubernetes' own
  DNS) is the natural replacement if this ever runs across multiple hosts.

## Honest scope notes from this pass

- **Database-per-service here means separate logical databases on one
  shared Postgres instance**, not separate managed instances. Per-service
  DB ROLES now exist too (`terraform/platform-infra/modules/db-roles`) —
  each service authenticates with its own role, not a shared master
  credential. This is role-level isolation on a shared instance, not
  instance-level isolation, stated honestly: the RDS instance itself being
  down or under heavy load still affects every service regardless.
- **Test coverage is real and service-focused.** Backend services have unit/controller tests around their current business logic and HTTP behavior, with the frontend covered by Vitest/Testing Library. Integration-style tests should be added selectively where a real external dependency is important; the project does not require a separate contract-testing platform for the current scope.
- **Static analysis and, where possible, real execution — not uniformly
  either.** Terraform/Helm/most Java has been cross-referenced and traced
  by hand (this caught real bugs — a `relativePath` bug affecting all 9
  services' parent POM resolution, a staging environment with a wrong
  Terraform variable name and 2 missing required arguments, and other integration/configuration issues). The frontend 
  instance is the only genuine exception: actually installed,
  built, and run for real that static review never would have
  surfaced. Expect a first pass with `mvn compile`/`terraform plan`/`helm
  template` to turn up things neither method caught.

## When was this split actually worth it?

Worth repeating from the conversation that led here: the pressures that
justify this kind of split in a real e-commerce platform are team scale
(independent deploys), wildly different traffic/scaling profiles between
components, a genuine compliance boundary (payment/PCI), and blast-radius
isolation at a size where that matters. A single-contributor project at
this traffic level has none of those pressures yet — this split is worth
having as the *exercise* of building the pattern, which is what this pass
was explicitly for, not because the original 4-service design was wrong.
