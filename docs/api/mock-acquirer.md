# Mock Acquirer

There is no real bank or card network. Two simulated layers decide what happens to a payment, and both can be steered on purpose for testing.

## 1. The acquirer — the synchronous answer

When `POST /v1/payments` runs, the method's `PaymentProcessor` answers immediately: `Failure` with an error code for the test values below, otherwise `Pending` with a processor reference, which leaves the payment `AUTHORIZING`.

| Method | Trigger | Result |
|---|---|---|
| `CARD` | card number `4000000000000002` | `FAILED`, `CARD_DECLINED` |
| `CARD` | card number `4000000000000069` | `FAILED`, `CARD_EXPIRED` |
| `CARD` | any other card | `AUTHORIZING` |
| `CARD` | `methodDetails.token` missing, unknown or revoked | `FAILED`, `PAYMENT_GATEWAY_ROUTER_UNREACHABLE` (compensated) |
| `UPI` | `methodDetails.vpa` = `fail@okaxis` | `FAILED`, `UPI_REJECTED` |
| `UPI` | `methodDetails` present but no `vpa` | `FAILED`, `UPI_FAILED` |
| `UPI` | any other `vpa`, or no `methodDetails` at all | `AUTHORIZING` |
| `NETBANKING` | `methodDetails.bank` = `BANK_CODE_FAIL` | `FAILED`, `BANK_REJECTED` |
| `NETBANKING` | `methodDetails` present but no `bank` | `FAILED`, `NBK_FAILED` |
| `NETBANKING` | any other `bank`, or no `methodDetails` at all | `AUTHORIZING` |

The card processor runs inside vault-service, on the decrypted number, so tokenize one of the test numbers first and pay with its token. The UPI and net-banking processors run in payment-service.

## 2. The bank callback — the asynchronous answer

`BankCallbackSimulator` in payment-service resolves `AUTHORIZING` payments, as a real bank's callback would. Every `poll-interval-ms` (5 s) it takes payments whose simulated delay has passed and approves or declines them; an approval is followed straight away by a capture.

Settings are under `payment.simulator` in `config-repo/payment-service.yaml`:

| Method | Delay | Approval rate |
|---|---|---|
| `CARD` | 2–6 s | 90% |
| `UPI` | 1–3 s | 95% |
| `NETBANKING` | 4–10 s | 80% |

The delay and the approve/decline outcome are both derived from the payment id's hash, so a given payment always gets the same answer. A decline is `FAILED` with `SIM_BANK_ERROR_CODE`.

`chaos-mode` overrides the per-method rates for everything:

| `chaos-mode` | Effect |
|---|---|
| `NORMAL` | Per-method delay and approval rate (the default) |
| `SLOW` | Delays doubled |
| `SUCCESS` | Every payment approved and captured |
| `FAILURE` | Every payment declined |
| `TIMEOUT` | Nothing is resolved — payments stay `AUTHORIZING` |

## Capture

Every adapter's `capture()` returns success unconditionally today, so a capture never fails in practice; the `CAPTURE_FAIL` path exists in the state machine but can't be reached through the simulators.
