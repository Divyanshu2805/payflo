# Orders

What a customer is paying for. **Service:** payment-service · **Controller:** `OrderController` (`/v1/orders`)

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `POST` | `/v1/orders` | `CreateOrderRequest { amount, receipt?, notes?, expiresAt?, customer? }` + optional `X-Idempotency-Key` header | `201` `OrderResponse { id, merchantId, customerId, receipt, amount, status, attempts, notes, expiresAt, createdAt }` | `status` is `CREATED`, `attempts` `0`. `409 ORDER_RECEIPT_DUPLICATE` if this merchant already used the receipt. With the idempotency header, a retry returns the original order (the key is kept in the database as well as Redis); the same key for a different amount, receipt or notes is `422 IDEMPOTENCY_KEY_REUSED`, and a key over 100 characters is `400 IDEMPOTENCY_KEY_TOO_LONG`. Publishes `ORDER_CREATED`. |
| `GET` | `/v1/orders` | `?status=&page=&size=` | `200` a page of `OrderResponse`, newest first | `status` is one of `CREATED`, `ATTEMPTED`, `PAID`, `CANCELLED`, `EXPIRED`. |
| `GET` | `/v1/orders/{orderId}` | — | `200` `OrderResponse` | `404 ORDER_NOT_FOUND` for an unknown or another merchant's order. |
| `GET` | `/v1/orders/{orderId}/payments` | — | `200` a list of `PaymentResponse`, oldest first | Every attempt at paying the order. |
| `POST` | `/v1/orders/{orderId}/cancel` | — | `200` `OrderResponse` with `status: CANCELLED` | Only an unpaid order with no payment in flight or completed: otherwise `400 ORDER_CANNOT_CANCEL`. Publishes `ORDER_CANCELLED`. |

| Field | Constraint | Meaning |
|---|---|---|
| `amount` | required | `Money` — `{ amountUnits, currency }`. `amountUnits` must be between 1 and 500,000,000 (INR 5,000,000) and `currency` must be `INR`; anything else is `400 VALIDATION_FAILED` |
| `receipt` | at most 100 characters | The merchant's own order reference; unique per merchant |
| `notes` | — | Any JSON object, stored as-is |
| `expiresAt` | — | Defaults to 15 minutes from now (`payment.order.default-order-expiry-minutes`). Once it passes the order can't be paid (`400 ORDER_EXPIRED`) and, unpaid, becomes `EXPIRED` within a minute |
| `customer` | `name` ≤ 200, `email` a valid email ≤ 200, `phone` ≤ 20 | When `email` is present, the customer is found or created in merchant-service by merchant + email, and its id is returned as `customerId` |

The customer is resolved **before** the order's transaction opens, so a slow merchant-service never holds a database connection — see the [payment flow](../architecture/flows/payment.md#creating-an-order).

## Related

- [Payments](payments.md) — paying an order.
- [payment-service data model](../schema/payment-service.md#order_record).
