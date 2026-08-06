# Catalogix architecture (post-split)

This document replaces the architecture section of the original README, which
described the earlier 4-service design (user-svc / product-svc / order-svc /
notification-svc). That design is still a completely reasonable way to build
this app — see the note at the bottom of this file on when a split like this
one is actually worth it. This document describes what it became after
splitting cart, coupons, payment, and inventory out into their own services,
and turning order-svc into a proper saga orchestrator.

## Service map

```
                                  ┌─────────────┐
        browser ─────────────────▶│   gateway   │  nginx, rate-limited, port 8080
                                  └──────┬──────┘
      ┌──────────┬──────────────┬───────┼────────┬─────────────┬──────────────┐
      ▼          ▼              ▼       ▼        ▼             ▼              ▼
  /users/*  /products/*     /orders/*  /cart/*  /coupons/*  everything else
      │          │              │       │        │             │
 ┌────▼────┐┌────▼─────┐  ┌─────▼─────┐┌▼───────┐┌▼───────────┐┌▼───────────┐
 │user-svc ││catalog-svc│  │checkout-svc││cart-svc││promotions- ││frontend-svc│
 │ :8081   ││  :8082    │  │  :8083    ││ :8086  ││svc  :8087  ││  (nginx)   │
 └────┬────┘└─────┬─────┘  └─────┬─────┘└───┬────┘└─────┬──────┘└────────────┘
      │           │               │          │           │
      │     ┌─────▼──────┐        │    ┌─────┴───────────┴────┐
      │     │inventory-  │◀───────┼────┤ (cart/checkout also  │
      │     │svc  :8085  │        │    │  read catalog/       │
      │     └────────────┘        │    │  inventory/promotions│
      │                           ▼    │  directly, not shown │
      │                     ┌──────────┴┐ as arrows to avoid   │
      │                     │payment-svc│ a diagram of spaghetti)
      │                     │  :8088    │ — not reachable via the
      │                     └───────────┘  gateway; checkout-svc
      │                                    is its only caller
      ▼
 ┌──────────┐        events over RabbitMQ        ┌──────────────────┐
 │ RabbitMQ │◀───────────────────────────────────▶│ notification-svc │ :8084
 └──────────┘   (user-svc, checkout-svc publish;  └──────────────────┘
                 notification-svc consumes)

 Every service above also exports traces via OTLP to Jaeger (:16686).
 Every service has its own Postgres database (one shared instance,
 database-per-service — see postgres-init/).
```

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
- **No API gateway logic beyond routing** — no BFF-style aggregation beyond
  catalog-svc composing its own stock reads from inventory-svc for external
  API-shape compatibility.
- **No service discovery / config server** — docker-compose's DNS-by-container-name
  is doing that job. Fine at this scale; Eureka/Consul (or Kubernetes' own
  DNS) is the natural replacement if this ever runs across multiple hosts.
- **No contract testing (Pact) or shared event schema registry** — the
  clients in each service (`CatalogClient`, `InventoryClient`, etc.) are
  hand-maintained subsets of each other's DTOs. This is real risk (a
  renamed field in catalog-svc's `ProductResponse` fails silently at
  runtime, not at build time) and the most concrete next piece of
  infrastructure worth adding if this split is kept.

## Honest scope notes from this pass

- **Database-per-service here means separate logical databases on one
  shared Postgres instance**, not separate managed instances, and every
  service still authenticates with the same Postgres role. Isolation is by
  database name, not credential. Per-service DB roles/grants is the natural
  next hardening step.
- **No test suites were ported for the new or restructured services.**
  catalog-svc's and checkout-svc's old tests referenced APIs that no longer
  exist and were removed rather than left broken; none of the four brand
  new services (payment/inventory/cart/promotions-svc) have tests yet. This
  is real debt, not an oversight being glossed over.
- **None of this has been compiled.** The sandbox this was built in has no
  `mvn`/`javac`. Every cross-service method signature was manually
  cross-checked against its caller, but "manually checked" is not "verified
  by a compiler" — expect to spend a first pass fixing whatever `mvn compile`
  turns up per module before this actually runs.

## When was this split actually worth it?

Worth repeating from the conversation that led here: the pressures that
justify this kind of split in a real e-commerce platform are team scale
(independent deploys), wildly different traffic/scaling profiles between
components, a genuine compliance boundary (payment/PCI), and blast-radius
isolation at a size where that matters. A single-contributor project at
this traffic level has none of those pressures yet — this split is worth
having as the *exercise* of building the pattern, which is what this pass
was explicitly for, not because the original 4-service design was wrong.
