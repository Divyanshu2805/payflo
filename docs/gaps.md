# Known gaps vs. requirements / v1 design

[← Back to docs index](README.md)

Found while syncing docs to the actual implementation (2025-10-06) — not yet triaged as intentionally
dropped vs. still planned:

1. `ORDER_RECORD` has no `idempotency_key` — the idempotent-order-creation requirement isn't backed yet.
2. `API_KEY` has no `webhook_secret_hash`.
3. `CUSTOMER` has no `gst_id`.
4. `PAYMENT` has no running `refunded_amount` total (derivable from `REFUND` rows instead).
5. `PAYMENT_TRANSITION_LOG` has no `reason` field.
6. **Secrets are stored unhashed in three places** — `AuthServiceImpl.signup` writes
   `request.password()` straight into `AppUser.passwordHash`, and both
   `ApiKeyServiceImpl.create`/`.rotate` write the raw generated secret straight into
   `ApiKey.keySecretHash`/`previousKeySecretHash`. None of it is hashed. Flagged, not fixed yet;
   commit and push proceeded as-is at the user's explicit call (2025-10-10 for the password,
   2025-10-14/26 for the API key secret), pending one shared hashing-dependency decision
   (`spring-security-crypto` vs. full `spring-boot-starter-security`) to fix all of it together.
7. **`OrderController`/`PaymentController` use a hardcoded `merchantId`** — each has its own fixed
   test UUID instance field instead of deriving the merchant from any caller identity, since
   there's no auth yet. Every order or payment created, fetched, cancelled, or listed currently
   belongs to/is scoped to that same merchant regardless of caller.
8. **Card is still fully unwired; netbanking and UPI both now hit a broken-empty-response bug on
   their happy path** — `NetBankingAdapter`/`UpiPaymentAdapter` call `PaymentProcessorRouter.charge()`
   and map the result to a `PaymentResult` via a `switch` expression (no `case null`, but wrapped in
   a `try/catch` that turns a `NullPointerException` into
   `PaymentResult.Failure("NBK_FAILED"/"UPI_FAILED", <NPE message>)`). `CardPaymentAdapter` is
   still the original `// TODO` stub — unchanged — so card payments fall through
   `PaymentServiceImpl`'s `case null` and stay `status: CREATED`, even though `CardPaymentProcessor`
   has real mock-acquirer logic (two test PANs — `4000000000000002` declined, `4000000000000069`
   expired — anything else `Pending`) nothing calls yet.
   `NetBankingPaymentProcessor` and `UpiPaymentProcessor` both now have real mock logic
   (`methodDetails.bank == "BANK_CODE_FAIL"` / `methodDetails.vpa == "fail@okaxis"` → `Failure`;
   anything else → `Success` with a generated `processorRef`) — but **`PaymentServiceImpl`'s
   `case PaymentResult.Success` branch treats any `Success` as an invalid state and does
   `return null`**, a placeholder written when nothing produced `Success` yet. Now both methods'
   happy paths do: a normal netbanking or UPI payment (no failure sentinel in `methodDetails`) hits
   that branch and `POST /v1/payments` returns `201 Created` with an empty body — the `Payment` row
   is still persisted correctly (dirty-checked within the `@Transactional` method even though the
   early `return` skips the explicit `.save()` calls), just never reported back to the caller.
   Flagged, not fixed — deciding what a synchronous `Success` (really a "redirect to bank"/"push
   notification sent" state, not a terminal one) should map to in `PaymentStatus` is a
   state-machine design call, not a mechanical bug fix.
9. **`Payment.idempotencyKey` is a fresh random value every call, never checked** —
   `PaymentServiceImpl.initiate` generates `UUID.randomUUID().toString()` per request instead of
   accepting/deriving a caller-supplied key and looking up an existing `Payment` by it, so retrying
   a payment-initiation request creates a duplicate `Payment` row rather than returning the
   original.
10. **`POST /v1/payments/{paymentId}/capture` has no pre-condition check on the payment's current
    status** — it can be called on a `Payment` in any status (already `CAPTURED`, still `CREATED`,
    `FAILED`, etc.) and will set `status = CAPTURING` and attempt the capture regardless, unlike
    `initiate`'s explicit `ORDER_NOT_PAYABLE` guard on the order. Consistent with the broader
    documented gap that state-machine transitions aren't enforced anywhere yet. Also: none of the
    three `PaymentAdapter.capture()` implementations talk to a real (or even properly simulated)
    acquirer — `CardPaymentAdapter.capture()` is a stub returning `null` (capture always reverts to
    `AUTHORIZED`), while `NetBankingAdapter.capture()`/`UpiPaymentAdapter.capture()` return a
    hardcoded `PaymentResult.Success` unconditionally, regardless of the payment's actual state or
    history — so calling capture on either always reports `CAPTURED`.
11. **`PaymentStateMachine` exists but is unused** — `payment/statemachine/PaymentStateMachine`
    encodes a validated transition table (`transition(PaymentStatus, PaymentEvent)`, throwing
    `InvalidStateTransitionException` for an undefined pair — see [Payment state
    machine](domain-vocabulary.md#payment-state-machine)), but nothing calls it. `PaymentServiceImpl.initiate`/`capture`
    both still mutate `Payment.status` directly without going through it, so entry 10's "no
    pre-condition check" gap remains live in practice even though the rulebook to fix it now exists.
