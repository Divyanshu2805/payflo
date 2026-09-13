# Vault

Turning a card into a token that can be charged later. **Service:** vault-service · **Controller:** `VaultController` (`/v1/vault`)

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `POST` | `/v1/vault/tokenize` | `TokenizeRequest { pan, cvv, expiryMonth, expiryYear, customerId?, cardHolderName }` | `201` `TokenizeResponse { token, lastFour, brand, expiryMonth, expiryYear }` | The card number is never echoed back. Use `token` as `methodDetails.token` in a [`CARD` payment](payments.md). |

| Field | Constraint |
|---|---|
| `pan` | required, 13–19 digits, must pass the Luhn check |
| `cvv` | required, 3–4 digits — validated and **never stored** |
| `expiryMonth` | required, 1–12 |
| `expiryYear` | required, not in the past (`@ExpiryYear`) |
| `customerId` | optional — a customer id from merchant-service, stored as a plain id |
| `cardHolderName` | at least 3 characters |

## What happens to the card

vault-service detects the brand from the leading digits, generates a random AES-256 data key for this card, encrypts the number with it (AES-GCM), wraps the data key with the master key, and stores the encrypted number and wrapped key with the last four, first six, expiry and holder name. The token (`tok_…`) is a separate row pointing at the card. See the [vault data model](../schema/vault-service.md) and [decision 0004](../architecture/decisions/0004-isolate-card-data-in-vault-service.md).

Tokenizing the same card twice creates two cards and two tokens; there is no de-duplication. Tokens can't be listed, read or revoked through the API yet.

Card numbers that make a payment fail on purpose are listed in [mock acquirer](mock-acquirer.md).
