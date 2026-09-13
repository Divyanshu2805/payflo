# Orders

What a customer is paying for. **Service:** payment-service · **Controller:** `OrderController` (`/v1/orders`)

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `POST` | `/v1/orders` | `CreateOrderRequest { amount, receipt?, notes?, expiresAt?, customer? }` | `201` `OrderResponse { id, merchantId, customerId, receipt, amount, status, attempts, notes, expiresAt, createdAt }` | `status` is `CREATED`, `attempts` `0`. `409 ORDER_RECEIPT_DUPLICATE` if this merchant already used the receipt. Publishes `ORDER_CREATED`. |

| Field | Constraint | Meaning |
|---|---|---|
| `amount` | required | `Money` — `{ amountUnits, currency }` |
| `receipt` | at most 100 characters | The merchant's own order reference; unique per merchant |
| `notes` | — | Any JSON object, stored as-is |
| `expiresAt` | — | Defaults to 30 minutes from now (`payment.order.default-order-expiry-minutes`). Nothing expires orders yet |
| `customer` | `name` ≤ 200, `email` a valid email ≤ 200, `phone` ≤ 20 | When `email` is present, the customer is found or created in merchant-service by merchant + email, and its id is returned as `customerId` |

The customer is resolved **before** the order's transaction opens, so a slow merchant-service never holds a database connection — see the [payment flow](../architecture/flows/payment.md#creating-an-order).

## Not available

Reading an order (`GET /v1/orders/{id}`), cancelling one (`POST …/cancel`) and listing its payments (`GET …/payments`) exist in the monolith, and payment-service's `OrderService` already implements all three — but no controller routes them yet. See [known gaps](../known-gaps/not-yet-built.md#ported-from-the-monolith).

## Related

- [Payments](payments.md) — paying an order.
- [payment-service data model](../schema/payment-service.md#order_record).
