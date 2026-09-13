# Cross-Service References

A column that names something in another service's database is a **plain id**: no foreign key, no join, no JPA relation. When the data is needed, the owning service is asked for it over its [internal API](../architecture/service-communication.md#internal-api). See [decision 0002](../architecture/decisions/0002-database-per-service.md).

| Column | Refers to | Owned by |
|---|---|---|
| `order_record.merchant_id`, `payment.merchant_id`, `refund.merchant_id` | `merchant.id` | merchant-service |
| `order_record.customer_id` | `customer.id` | merchant-service |
| `card_token.merchant`, `card_token.customer` | `merchant.id`, `customer.id` | merchant-service |
| `webhook_event.merchant_id`, `dlq_event.merchant_id`, `settlement.merchant_id` | `merchant.id` | merchant-service |
| `settlement_payment.payment_id` | `payment.id` | payment-service |
| `outbox_event.aggregate_id` (both services) | an order, payment or settlement id | its own service |

## What this means in practice

- **Dangling ids are possible.** Nothing prevents a reference to a merchant or customer that no longer exists, and code reading these columns must tolerate it.
- **Scoping is by value, not by join.** payment-service scopes every order and payment query by `merchant_id = <MerchantContext>`, and that equality is the whole tenant boundary.
- **No cascade crosses a service.** Deleting or soft-deleting something in one database changes nothing in another.
- **Consistency is best-effort.** Settlement reads payments from payment-service and records them in its own database; if the two drift, the link table is still the record of what was paid out.
