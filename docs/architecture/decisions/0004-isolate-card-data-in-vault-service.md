# 0004. Isolate card data in vault-service

**Status:** Accepted

## Context

Anything that stores, processes or transmits card numbers falls in PCI DSS scope. If payment-service decrypted cards, it — and everything it talks to — would be in scope too.

## Decision

Card data lives only in vault-service and its database, `payflo_vault`:

- Tokenization encrypts each card number under its own AES-256 data key, which is itself wrapped by a master key only vault-service is configured with. Merchants get back an opaque token, brand, last four and expiry. The CVV is validated and never stored.
- Charging a card is `POST /internal/vault/charge` with a token and an amount. vault-service decrypts the card, runs the acquirer call itself (`CardPaymentProcessor`) behind a thread-pool bulkhead, and returns only the outcome.

## Consequences

- payment-service, and everything upstream of it, never sees a card number.
- A card payment is a network hop to vault-service, so payment initiation became [a saga](0006-payment-initiation-as-a-saga.md) that compensates if vault-service is unavailable.
- vault-service is the one place that must be hardened most: the master key needs a real secret store before any shared environment — see [known gaps](../../gaps.md).
