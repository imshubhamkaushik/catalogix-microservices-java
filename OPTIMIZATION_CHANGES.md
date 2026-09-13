# Catalogix optimization patch

This patch is focused on four concrete reliability/performance problems observed in the current project:

1. Payment retries could create duplicate payment rows when checkout timed out after payment-svc had already committed.
2. The checkout-to-payment HTTP timeout was short enough to surface false failures during slow/cold downstream work.
3. Each Spring service used the default Hikari connection-pool sizing, which is unnecessarily large for the project's small local/EKS footprint.
4. The nginx gateway could keep an old Docker container IP after a backend container restart, producing 502s until the gateway restarted.
5. Catalog search now has a composite PostgreSQL expression index for the common category + price-range query.

## Files changed

### Payment idempotency
- `backend/payment-svc/src/main/java/com/catalogix/payment/model/Payment.java`
  - Added the nullable `idempotencyKey` entity field mapped to `payments.idempotency_key`.
- `backend/payment-svc/src/main/java/com/catalogix/payment/repository/PaymentRepository.java`
  - Added lookup by `(requestedByUserId, idempotencyKey)`.
- `backend/payment-svc/src/main/java/com/catalogix/payment/svc/PaymentSvc.java`
  - Added idempotent processing/replay.
  - Added compatibility validation for a reused key.
  - Uses `saveAndFlush()` so the unique-key race is surfaced before the request completes.
  - Added lookup used by the controller's race-recovery path.
- `backend/payment-svc/src/main/java/com/catalogix/payment/controller/PaymentController.java`
  - Accepts `Idempotency-Key`.
  - Returns the stored result on replay.
  - Recovers a concurrent unique-key race instead of returning a raw 500.
- `backend/payment-svc/src/main/java/com/catalogix/payment/exception/IdempotencyConflictException.java`
  - New 409 conflict exception for reusing a key with a different order/amount/method.
- `backend/payment-svc/src/main/java/com/catalogix/payment/exception/GlobalExceptionHandler.java`
  - Maps idempotency conflicts to HTTP 409.
- `backend/payment-svc/src/main/resources/db/migration/V3__add_payment_idempotency_key.sql`
  - Adds the payment idempotency column and a partial unique index.
- `backend/payment-svc/src/test/java/com/catalogix/payment/svc/PaymentSvcTest.java`
  - Added replay/conflict/decline idempotency coverage.
- `backend/payment-svc/src/test/java/com/catalogix/payment/controller/PaymentControllerTest.java`
  - Added replay and concurrent-race controller coverage.

### Checkout retry safety
- `backend/checkout-svc/src/main/java/com/catalogix/checkout/client/PaymentClient.java`
  - Forwards `Idempotency-Key` to payment-svc.
  - Keeps the previous overload for non-key callers.
- `backend/checkout-svc/src/main/java/com/catalogix/checkout/svc/CheckoutSvc.java`
  - Accepts the key on payment operations and forwards it to PaymentClient.
  - Keeps the previous overload for compatibility.
- `backend/checkout-svc/src/main/java/com/catalogix/checkout/controller/OrderController.java`
  - Accepts `Idempotency-Key` on `POST /orders/{id}/pay`.
- `backend/checkout-svc/src/main/java/com/catalogix/checkout/config/RestTemplateConfig.java`
  - Increased read timeout from 5s to 8s while keeping a fast 3s connect timeout.
- `backend/checkout-svc/src/test/java/com/catalogix/checkout/controller/OrderControllerTest.java`
  - Updated payment endpoint expectations for the forwarded key.

### Browser retry behavior
- `frontend-svc/src/api.jsx`
  - `payOrder()` now accepts and sends `Idempotency-Key`.
- `frontend-svc/src/components/Orders.jsx`
  - Generates a stable key for a payment form and resets it whenever payment inputs change, allowing safe retry after a transport timeout without accidentally reusing a key for a different payment operation.
- `frontend-svc/src/components/Orders.test.jsx`
  - Updated assertions to verify a payment idempotency key is supplied.

### Database connection-pool tuning
The following `application.properties` files now bound Hikari pools to 5 connections per service, with 1 idle connection, short connection/validation timeouts, a 10-minute max lifetime, and a 2-minute keepalive:
- `backend/user-svc/src/main/resources/application.properties`
- `backend/catalog-svc/src/main/resources/application.properties`
- `backend/inventory-svc/src/main/resources/application.properties`
- `backend/cart-svc/src/main/resources/application.properties`
- `backend/promotions-svc/src/main/resources/application.properties`
- `backend/payment-svc/src/main/resources/application.properties`
- `backend/checkout-svc/src/main/resources/application.properties`
- `backend/notification-svc/src/main/resources/application.properties`
- `backend/review-svc/src/main/resources/application.properties`

### Gateway DNS resiliency
- `gateway/nginx.conf`
  - Added Docker DNS resolver `127.0.0.11`.
  - Changed backend `proxy_pass` targets to variables so nginx resolves the current container address instead of pinning a stale IP after container replacement.
  - Explicitly forwards `Idempotency-Key`.

### Catalog query index
- `backend/catalog-svc/src/main/resources/db/migration/V4__add_category_price_index.sql`
  - Added `LOWER(category), price` index for category + price-range filtering.

## Validation performed here

- nginx configuration was syntax-checked successfully with the updated `gateway/nginx.conf`.
- The repository was inspected from the supplied zip and the patch was applied to a working copy.
- Full Maven/Spring test execution was not possible in this sandbox because Maven is not installed and the repository has no Maven wrapper. Run the commands below in the project environment.

## Recommended validation

```powershell
mvn clean test
npm --prefix frontend-svc ci
npm --prefix frontend-svc test -- --run
docker compose build
docker compose up -d
```

Then exercise the existing end-to-end acceptance plan, paying particular attention to payment retry/idempotency and the successful-payment database checks.
