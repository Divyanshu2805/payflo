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
| `WALLET` | `methodDetails.wallet` = `wallet_fail` | `FAILED`, `WALLET_REJECTED` |
| `WALLET` | any other `wallet` | `AUTHORIZING` |

The values that authorize and then have the capture refused are in [section 3](#3-capture--the-acquirer-can-refuse-it).

The card processor runs inside vault-service, on the decrypted number, so tokenize one of the test numbers first and pay with its token. The UPI and net-banking processors run in payment-service.

## 2. The bank callback — the asynchronous answer

`BankCallbackSimulator` in payment-service resolves `AUTHORIZING` payments, as a real bank's callback would. Every `poll-interval-ms` (1 s) it takes payments whose simulated delay has passed and approves or declines them, in batches of `batch-size` (50) per transaction; an approval is followed straight away by a capture. A payment is therefore answered within about a second of its delay passing.

Settings are under `payment.simulator` in `config-repo/payment-service.yaml`:

| Method | Delay | Approval rate |
|---|---|---|
| `CARD` | 2–6 s | 90% |
| `UPI` | 1–3 s | 95% |
| `NETBANKING` | 4–10 s | 80% |
| `WALLET` | 1–3 s | 95% |

Refunds are answered the same way by `RefundResolver`: after `payment.refund.delay-seconds` (3 s) it approves `payment.refund.success-rate` (95%) of them and declines the rest (`SIM_REFUND_DECLINED`). See [refunds](refunds.md).

The delay and the approve/decline outcome are both derived from the payment id's hash, so a given payment always gets the same answer. A decline is `FAILED` with `SIM_BANK_ERROR_CODE`.

`chaos-mode` overrides the per-method rates for everything:

| `chaos-mode` | Effect |
|---|---|
| `NORMAL` | Per-method delay and approval rate (the default) |
| `SLOW` | Delays doubled |
| `SUCCESS` | Every payment approved and captured |
| `FAILURE` | Every payment declined |
| `TIMEOUT` | Nothing is resolved — payments stay `AUTHORIZING` |

## 4. The payout simulator

Settlements are paid to the merchant's bank by a third simulated bank, with the same shape as the payment one. It lives in operations-service (`PayoutOutcomeDecider`, `settlement.simulator.*` in `config-repo/operations-service.yaml`): after the bank accepts a transfer it answers after a delay, and the delay and the outcome are derived from the settlement id, so a given settlement always gets the same answer.

| Setting | Default | Meaning |
|---|---|---|
| `min-delay-seconds`, `max-delay-seconds` | `2`, `12` | How long the bank takes to answer a transfer it accepted |
| `success-rate` | `95` | Percent of transfers the bank completes (`NORMAL` and `SLOW`); the rest are declined with `SIM_PAYOUT_DECLINED` |
| `refuse-rate` | `0` | Percent of transfers the bank refuses to accept at all (every mode but `SUCCESS`): the settlement fails at once as `TRANSFER_NOT_STARTED` |
| `chaos-mode` | `NORMAL` | Overrides the above, as for payments |

| `chaos-mode` | Effect on a payout |
|---|---|
| `NORMAL` | The delay, then `success-rate` percent succeed |
| `SLOW` | Delays doubled |
| `SUCCESS` | Every transfer succeeds after its delay |
| `FAILURE` | Every transfer is declined (`SIM_PAYOUT_DECLINED`) |
| `TIMEOUT` | The bank never answers. After `settlement.transfer-timeout-minutes` (120) the recovery job fails the settlement with `TRANSFER_TIMEOUT` |

Every failed payout leaves its payments `CAPTURED`, so the next settlement run pays them out, and publishes `SETTLEMENT_FAILED` (a webhook, if the merchant subscribed). See the [settlement flow](../architecture/flows/settlement.md).

## 3. Capture — the acquirer can refuse it

After a bank approves, the payment is captured straight away (`CAPTURE_REQUEST` → `CAPTURING`). The adapters' `capture()` always succeeds, so the refusal is decided by `CaptureSimulator` in payment-service, which `PaymentGatewayRouter` asks first. A refused capture is `CAPTURE_FAIL`: the payment goes **back to `AUTHORIZED`** with `errorCode` `CAPTURE_DECLINED` (the authorization is still held, the order is still unpaid), a `PAYMENT_STATUS_CHANGED` webhook carries the `errorCode`, and the merchant retries with `POST /v1/payments/{id}/capture`. If nobody retries, the payment lapses to `AUTH_EXPIRED` after `payment.timeout.authorized-minutes` (60).

Two ways to make it happen:

| Trigger | Where |
|---|---|
| `UPI` `methodDetails.vpa` = `capturefail@okaxis` | The UPI processor tags the processor reference `UPI_PROCESSOR_CAPTURE_FAIL_…` |
| `NETBANKING` `methodDetails.bank` = `BANK_CODE_CAPTURE_FAIL` | `NBK_PROCESSOR_CAPTURE_FAIL_…` |
| `WALLET` `methodDetails.wallet` = `wallet_capture_fail` | `WALLET_PROCESSOR_CAPTURE_FAIL_…` |
| `CARD` number `4000000000000341` (tokenize it first) | vault-service tags it `CARD_PROCESSOR_CAPTURE_FAIL_…` |
| `payment.simulator.capture.failure-rate` | This percent of payments, chosen from each payment's id (so a given payment always gets the same answer); `0` by default |

| Setting | Default | Meaning |
|---|---|---|
| `payment.simulator.capture.failure-rate` | `0` | Percent of payments whose capture is refused, besides the test values |
| `payment.simulator.capture.failures-before-success` | `1` | How many captures of such a payment are refused before one goes through. `1`: the automatic capture fails and the merchant's retry works. A large number never lets one through, so the authorization lapses |

The refusals are counted from the payment's transition log (`CAPTURE_FAIL` rows), and a payment that is not marked to fail costs no extra query. A capture that succeeds on retry clears the `errorCode` the failed attempt left.
