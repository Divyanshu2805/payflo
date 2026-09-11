# Key Abstractions

The domain concepts worth knowing by name before reading the code.

| Concept | Service | What it is |
|---|---|---|
| **Merchant** | merchant | The tenant: a business with KYC fields and settlement bank details. Everything else hangs off a merchant id. |
| **`MerchantContext`** | all (`common-lib`) | The request-scoped holder of the caller's merchant id and API key id, rebuilt from the gateway's headers. The only place a controller gets "who is calling". |
| **API key** | merchant | A `keyId` + secret pair for a merchant's backend, with a `TEST` or `LIVE` environment and a 24-hour grace period on rotation. |
| **Order** (`OrderRecord`) | payment | What is being paid for: an amount, an optional customer, a merchant receipt, an expiry. A payment is always attempted against one. |
| **Payment** | payment | One attempt to pay an order by one method. Its `status` moves only through the state machine. |
| **`PaymentStateMachine` / `PaymentTransitionService`** | payment | The validated table of `PaymentStatus` × `PaymentEvent` → next status, and the service that applies a transition and logs it. See [enums and state machines](../schema.md). |
| **`PaymentAdapter` / `PaymentProcessor`** | payment, vault | Two strategy layers chosen per `PaymentMethod`: the adapter owns how a method is initiated and captured; the processor is the acquirer-facing call. Adding a method is a new implementation and one config line, not a growing `switch`. |
| **Card token** | vault | The opaque stand-in for a vaulted card — the only card reference that ever leaves vault-service. |
| **Outbox event** | payment, operations | A row written with a domain change and published to Kafka afterward — how services tell each other what happened. |
| **Webhook event** | operations | One signed delivery of one domain event to one merchant endpoint, with its retry bookkeeping. |
| **Settlement** | operations | One merchant's nightly payout: gross, fee, GST and net, linked to the exact payments it covers. |
| **`Money`** | all (`common-lib`) | An integer count of the currency's smallest unit (paise for INR) plus the currency — never a floating-point amount. |
