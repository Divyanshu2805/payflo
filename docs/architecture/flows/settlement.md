# Flow: Settlement

How merchants get paid. Every night each active merchant's captured, not-yet-settled payments are totalled, refunds and the platform fee and GST are taken off, and the rest is sent to the merchant's bank account as one transfer. It runs in operations-service, which owns settlements but reads payments and bank details from the services that own them.

![Nightly settlement](../../assets/diagrams/flow-settlement.png)

All paths below are under `operations-service/src/main/java/com/project/payflo/operations_service/settlement/`.

## A saga, not a transaction

Settlement used to be one database transaction wrapped around calls to payment-service, merchant-service and the bank, holding a connection (and, after a crash, leaving no clue how far it got). It is now a sequence of short transactions (`SettlementRecorder`) with the remote calls between them, driven by `SettlementTransactionExecutor`:

```
INITIATED --transfer accepted--> TRANSFER_PENDING --bank says yes--> PROCESSED --payments marked settled--> (done)
    |                                  |
    +--transfer not started--> FAILED  +--bank says no--> FAILED
```

## The nightly run

1. **`SettlementEngine`** runs at 23:00 (`@Scheduled(cron = "0 0 23 * * *")`), ShedLock-guarded with the lock held for up to 2 hours so only one instance settles. It fetches the active merchant ids from merchant-service and settles them on virtual threads, **at most `settlement.concurrency` (4) at a time** — each holds database connections and calls three services. **One merchant failing never stops the others**: it is logged and its payments are picked up by the next run.
2. **`processForMerchant`**, per merchant, with no transaction held:
   - **reads the payments** from payment-service (`GET /internal/payments/unsettled-captured`), a page at a time (`settlement.page-size` 1000), oldest first, up to `settlement.max-payments-per-run` (20,000) — only payments captured before the **T+N hold** (`settlement.hold-days`, 0 by default) and with no refund still waiting on the bank;
   - **drops** any payment already in a payout that is `INITIATED` or `TRANSFER_PENDING`, or `PROCESSED` but not yet reported to payment-service. A payment stays `CAPTURED` until a payout is confirmed, so without this a slow transfer would be paid again the next night;
   - **fetches the bank account** from merchant-service *before writing anything*. A merchant with no account on file, or whose lookup fails, is skipped and its payments stay for a later run;
   - **groups by currency** and splits any group whose total would not fit `Money`'s int, so each becomes its own settlement;
   - **computes** `gross` (sum of the payments), `refunds` (what the bank has completed of them), `fee` (`settlement.fee-rate`, 2%, of `gross − refunds` — refunded money wasn't earned), `GST` (`settlement.gst-rate`, 18%, of the fee) and `net`, rounded half-up to whole minor units;
   - **step 1**: saves the `Settlement` (`INITIATED`) and a `SettlementPayment` row per payment — the audit trail from payout back to payments;
   - **step 2**: asks `BankTransferProcessor` to transfer the net amount, with the settlement id as the bank's reference, and moves it to `TRANSFER_PENDING`; if that throws it is marked `FAILED`, which frees its payments.

Every call to payment- and merchant-service goes through `SettlementIntegrationGateway`, which wraps them in a circuit breaker and retry.

## The payout callback

A real bank confirms a transfer later. **`BankSettlementCallbackSimulator`** stands in for that: every 5 seconds (ShedLock-guarded) it picks up `TRANSFER_PENDING` settlements and asks `PayoutOutcomeDecider` what the bank does with each, then calls `resolveTransfer`, one failure never holding up the rest. The decider works like the payment simulator: each transfer takes a delay (`settlement.simulator.min-delay-seconds`–`max-delay-seconds`, counted from when the bank accepted it) and then succeeds `success-rate` percent of the time, all derived from the settlement id so a settlement always gets the same answer, and `chaos-mode` overrides it (see [the payout simulator](../../api/mock-acquirer.md#4-the-payout-simulator)):

- **success** → **step 3**: `PROCESSED` with `processed_at`, and a `SETTLEMENT_PROCESSED` outbox row; then **step 4**: payment-service marks the covered payments `SETTLED` (`POST /internal/payments/mark-settled`, in batches of 500, through the state machine with a log row each), and the settlement gets `payments_settled_at`;
- **failure** → `FAILED` with a `failure_reason`, and `SETTLEMENT_FAILED`.

## Recovery

A crash or an outage between steps leaves a state `SettlementRecoveryJob` (every minute, ShedLock-guarded, only for settlements older than two minutes) finishes:

| Stuck in | Cause | Recovery |
|---|---|---|
| `INITIATED` | The settlement was recorded but the transfer never reached the bank | The transfer is started again. The bank identifies it by the settlement id, so it can't be paid twice |
| `PROCESSED`, `payments_settled_at` empty | The bank paid but payment-service wasn't told | The payments are marked settled now. Until then they stay held back from new payouts. Marking is repeatable: payment-service skips payments already `SETTLED` |
| `TRANSFER_PENDING` for longer than `settlement.transfer-timeout-minutes` (120) | The bank accepted the transfer and never answered | It is failed with `TRANSFER_TIMEOUT` and the payments are paid out by the next run. A real integration would ask the bank for the transfer's status by its reference before giving up |

## Refunds

A payment refunded in part is paid out net of the refunds the bank has completed (`PaymentSettlementView.refundedAmountUnits`), and its settlement records them in `refund_amount`. A payment with a refund still pending is left for the next run, and a fully refunded payment is never paid out. A refund asked for after the payout is refused, since that money has left. See [refunds](../../api/refunds.md).

## Limits

The simulated bank can decline a transfer, refuse it, or never answer (see above), so the `FAILED` branches all run; what it cannot do is take a payout back after paying. Only `ACTIVE` merchants are settled; becoming `ACTIVE` is the (simulated) [KYC step](../../api/merchant-account.md#kyc). The fee and GST rates are global settings, not per merchant.

## Related

- [Settlements API](../../api/settlements.md) — how a merchant reads its payouts.
- [Settlement status](../../schema/enums.md#settlement-status) and the [operations-service data model](../../schema/operations-service.md).
- [Internal API](../../api/internal.md) — the endpoints settlement calls.
