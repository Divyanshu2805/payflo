# Architecture

How PayFlo is put together: the services and what each one owns, how they talk to each other, how the important requests flow end to end, and the decisions behind the shape of it all.

If you are new to the codebase, read these in order:

1. [System context](system-context.md) — the services, their databases, and the infrastructure they depend on.
2. [Module map](module-map.md) — what lives in each module and package, and the layering rules inside a service.
3. [Service communication](service-communication.md) — the gateway's route table, the internal API, and the events on Kafka.
4. The four request flows that cover almost everything non-trivial:
   - [Authentication](flows/authentication.md) — credentials issued by merchant-service, verified once at the gateway.
   - [Payment](flows/payment.md) — an order and a payment from request to `CAPTURED`.
   - [Webhook delivery](flows/webhook-delivery.md) — a domain event becoming signed, retried deliveries.
   - [Settlement](flows/settlement.md) — the nightly payout to each merchant.

## Reference

| Page | Covers |
|---|---|
| [Security model](security-model.md) | Tenancy, gateway authentication, trusted identity headers, the internal API, the card vault, secrets |
| [Cross-cutting concerns](cross-cutting-concerns.md) | Errors, idempotency, rate limiting, resilience, scheduling, auditing, configuration, observability |
| [Key abstractions](key-abstractions.md) | The handful of domain concepts worth knowing by name |
| [Where do I change…?](where-to-change.md) | A task-oriented index into the code |
| [Architecture decisions](decisions/README.md) | Records of the significant design decisions and their trade-offs |

## Related

- [Data model](../schema/README.md) — entities and tables, per service, and the state machines.
- [API reference](../api.md) — every public endpoint and the internal API.
- [Known gaps](../gaps.md) — the constraints and trade-offs this design accepts today.
