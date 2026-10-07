# Analytics

A merchant's own numbers: a live dashboard (today and the last seven days) and a report over any date range, grouped by day, week or month. **Service:** payment-service · **Controller:** `AnalyticsController` (`/v1/analytics`) · **Logic:** `AnalyticsService`

Both endpoints are read-only, available to every role and to both credentials, and scoped to the caller's merchant — a merchant never sees another's figures. Responses are never cached (`Cache-Control: no-store`): the numbers are read from the database on every call.

| Method | Path | Request | Response |
|---|---|---|---|
| `GET` | `/v1/analytics/dashboard` | — | `200` `DashboardResponse { currency, generatedAt, today, todaySummary, last7Days, daily[7], byMethod[] }` |
| `GET` | `/v1/analytics/report` | `?from=&to=&granularity=` | `200` `ReportResponse { currency, from, to, granularity, totals, periods[], byMethod[] }` |

## The report's parameters

| Parameter | Default | Meaning |
|---|---|---|
| `from`, `to` | the last 30 days, ending today | Dates as `YYYY-MM-DD`, both inclusive, in the server's time zone (IST). `to` defaults to today and `from` to 29 days before `to` |
| `granularity` | `DAY` | `DAY`, `WEEK` (weeks start on Monday) or `MONTH` |

A range longer than 366 days, or one where `from` is after `to`, is `400 INVALID_DATE_RANGE`; ask for a longer history in parts. A malformed date or an unknown `granularity` is `400 INVALID_PARAMETER`.

## What a `Summary` holds

Every figure is a `Summary`: one per period, one for today, and one for each window's total.

| Field | Meaning |
|---|---|
| `periodStart` | The first day of the period: the day itself, the Monday of the week, the first of the month. For a window's total, the window's first day |
| `grossAmountUnits`, `capturedCount` | Payments **captured** in the period, by the time of capture, in paise |
| `refundedAmountUnits` | Refunds the bank **processed** in the period, by the time it processed them |
| `netAmountUnits` | `grossAmountUnits − refundedAmountUnits`: the revenue the merchant kept |
| `paymentsCreated` | Payments **started** in the period, by creation time |
| `failedCount` | Of those, the ones that ended `FAILED` or `AUTH_EXPIRED` |
| `successRate` | Of the payments started in the period that reached an outcome, the share that were captured: `captured ÷ (captured + failed)`, to four decimals. Payments still in flight are left out of both sides. Absent while none has reached an outcome |

Amounts are `long`, in the currency's smallest unit (paise), and the currency is `INR` — the only one orders accept. Days with no activity are returned as zeros, so a chart has no gaps. A payment captured today and refunded next week counts in today's gross and in next week's refunds, which is when the money moved.

## The dashboard

| Field | Meaning |
|---|---|
| `today`, `todaySummary` | Today's date and its figures |
| `last7Days` | The total of the seven days ending today |
| `daily` | Seven `Summary` entries, oldest first, one per day |
| `byMethod` | Captured payments over the seven days by `CARD`, `UPI`, `NETBANKING` and `WALLET`: `{ method, capturedCount, grossAmountUnits }`, biggest first |

## The report

`periods` has one `Summary` per day, week or month in the range (a week or month at the edge of the range holds only the days inside it), `totals` is their sum, and `byMethod` is the method breakdown over the whole range.

```json
{
  "currency": "INR",
  "from": "2026-09-08", "to": "2026-10-06", "granularity": "WEEK",
  "totals": { "periodStart": "2026-09-08", "grossAmountUnits": 722000, "capturedCount": 7, "refundedAmountUnits": 20000,
              "netAmountUnits": 702000, "paymentsCreated": 8, "failedCount": 1, "successRate": 0.875 },
  "periods": [ { "periodStart": "2026-09-07", "grossAmountUnits": 0, "...": "..." }, "…" ],
  "byMethod": [ { "method": "UPI", "capturedCount": 7, "grossAmountUnits": 722000 } ]
}
```

## How it is computed

Four aggregate queries run per call — payments captured per day, payments created per day and status, refunds processed per day, and captured payments by method — each filtered by merchant and a date range, on the `(merchant_id, captured_at)`, `(merchant_id, created_at)` and `(merchant_id, processed_at)` indexes. The days are then added up in `AnalyticsService`, so a week or a month is a sum of days. Nothing is precomputed or cached.

## Related

- [Orders](orders.md), [payments](payments.md), [refunds](refunds.md) and [settlements](settlements.md): what the figures are made of.
- [The error model](errors.md).
