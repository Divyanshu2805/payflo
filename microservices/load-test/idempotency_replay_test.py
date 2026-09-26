"""Replay the same write request many times and check it took effect once.

For every idempotency key the test sends DUPS identical requests at the same instant, then LATE more
after the first has finished, all through the gateway:

    payments   one order, one X-Idempotency-Key, repeated POST /v1/payments (UPI, netbanking, card)
    orders     one X-Idempotency-Key, repeated POST /v1/orders (no receipt, so only the key can stop them)
    control    the same payment burst with NO key, to show the test can see duplicates when they happen

A key passes when every successful response for it carries the same id. The run id tags each order it
creates (receipt `idem-<run>-…`, notes.idemRun), so the result can be cross-checked in the database.

    python idempotency_replay_test.py
    python idempotency_replay_test.py --payments 1000 --order-keys 400 --merchants 20

Needs keys.csv from provision_keys.py and the rate limit raised (see docs/load-testing/running.md).
Exits 1 if any key resolved to more than one id. Only standard library.
"""
import argparse
import base64
import csv
import json
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent


def call(base_url, auth, path, body, idempotency_key=None):
    headers = {"Content-Type": "application/json", "Authorization": auth}
    if idempotency_key:
        headers["X-Idempotency-Key"] = idempotency_key
    request = urllib.request.Request(base_url + path, data=json.dumps(body).encode(), method="POST", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            return error.code, json.loads(raw)
        except ValueError:
            return error.code, {"raw": raw[:200].decode(errors="replace")}
    except OSError as error:  # connection-level failure
        return 0, {"error": str(error)}


def burst(n, send):
    """Call send() n times, released together by a barrier."""
    barrier = threading.Barrier(n)
    results = [None] * n

    def one(i):
        barrier.wait()
        results[i] = send()

    threads = [threading.Thread(target=one, args=(i,)) for i in range(n)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return results


def replay_keys(count, request_for, dups, late, wave):
    """Per key: a simultaneous burst, then — once every first attempt has finished — plain retries."""
    with ThreadPoolExecutor(wave) as pool:
        bursts = list(pool.map(lambda i: burst(dups, request_for(i)), range(count)))
    time.sleep(1.0)
    with ThreadPoolExecutor(wave * 4) as pool:
        retries = list(pool.map(lambda i: [request_for(i)() for _ in range(late)], range(count)))
    return [b + r for b, r in zip(bursts, retries)]


def summarize(per_key_results):
    codes = Counter()
    one_id = several_ids = no_success = 0
    for results in per_key_results:
        ids = set()
        for status, body in results:
            codes[status] += 1
            if 200 <= status < 300 and body.get("id"):
                ids.add(body["id"])
        if len(ids) == 1:
            one_id += 1
        elif not ids:
            no_success += 1
        else:
            several_ids += 1
    requests = sum(codes.values())
    return {
        "keys": len(per_key_results),
        "requests": requests,
        "duplicate_requests": requests - len(per_key_results),
        "status_codes": dict(sorted(codes.items())),
        "keys_resolving_to_one_id": one_id,
        "keys_resolving_to_several_ids": several_ids,
        "keys_with_no_success": no_success,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://localhost:8080", help="API gateway URL")
    parser.add_argument("--keys", default=str(HERE / "keys.csv"), help="CSV of keyId,secret")
    parser.add_argument("--merchants", type=int, default=10, help="how many of the keys to spread requests over")
    parser.add_argument("--payments", type=int, default=500, help="orders paid under one idempotency key each")
    parser.add_argument("--order-keys", type=int, default=200, help="idempotency keys used for order creation")
    parser.add_argument("--dups", type=int, default=20, help="simultaneous identical requests per key")
    parser.add_argument("--late", type=int, default=5, help="further retries per key after the burst has finished")
    parser.add_argument("--wave", type=int, default=10, help="keys in flight at once")
    parser.add_argument("--control", type=int, default=20, help="orders paid 5 times at once with no key")
    args = parser.parse_args()

    if not Path(args.keys).exists():
        sys.exit(f"{args.keys} not found: run provision_keys.py first")
    with open(args.keys, newline="") as f:
        merchants = ["Basic " + base64.b64encode(f"{row[0]}:{row[1]}".encode()).decode()
                     for row in csv.reader(f) if row][:args.merchants]

    run = uuid.uuid4().hex[:10]
    customer = {"name": "Idempotency Test", "email": "idempotency@payflo.test"}

    def post(auth, path, body, key=None):
        return call(args.base_url, auth, path, body, key)

    # One customer and card token per merchant, for the card payments.
    tokens = {}
    for auth in merchants:
        status, order = post(auth, "/v1/orders", {"amount": {"amountUnits": 1000, "currency": "INR"}, "customer": customer})
        if status != 201:
            sys.exit(f"could not create a setup order: HTTP {status} {order}")
        status, card = post(auth, "/v1/vault/tokenize", {
            "pan": "4111111111111111", "cvv": "123", "expiryMonth": 12, "expiryYear": 2035,
            "customerId": order["customerId"], "cardHolderName": "Idempotency Test"})
        if status not in (200, 201):
            sys.exit(f"could not tokenize the test card: HTTP {status} {card}")
        tokens[auth] = card["token"]

    def new_order(phase, i):
        auth = merchants[i % len(merchants)]
        status, order = post(auth, "/v1/orders", {
            "amount": {"amountUnits": 1000 + i, "currency": "INR"}, "receipt": f"idem-{run}-{phase}-{i}",
            "notes": {"idemRun": run, "phase": phase}, "customer": customer})
        if status != 201:
            sys.exit(f"could not create an order: HTTP {status} {order}")
        return auth, order["id"]

    def payment_body(auth, order_id, i):
        if i % 3 == 0:
            return {"orderId": order_id, "method": "UPI", "methodDetails": {"vpa": "idempotency@okbank"}}
        if i % 3 == 1:
            return {"orderId": order_id, "method": "NETBANKING", "methodDetails": {"bank": "HDFC"}}
        return {"orderId": order_id, "method": "CARD", "methodDetails": {"token": tokens[auth]}}

    summary = {"run": run, "dups": args.dups, "late": args.late}
    started = time.time()

    # Payments: one order, one key, many identical requests.
    with ThreadPoolExecutor(32) as pool:
        paid_orders = list(pool.map(lambda i: new_order("pay", i), range(args.payments)))

    def payment_request(i):
        auth, order_id = paid_orders[i]
        body = payment_body(auth, order_id, i)
        return lambda: post(auth, "/v1/payments", body, f"idem-{run}-pay-{i}")

    summary["payments"] = summarize(replay_keys(args.payments, payment_request, args.dups, args.late, args.wave))

    # Orders: one key, many identical create-order requests.
    def order_request(i):
        auth = merchants[i % len(merchants)]
        key = f"idem-{run}-order-{i}"
        body = {"amount": {"amountUnits": 2000 + i, "currency": "INR"},
                "notes": {"idemRun": run, "phase": "order", "key": key}, "customer": customer}
        return lambda: post(auth, "/v1/orders", body, key)

    summary["orders"] = summarize(replay_keys(args.order_keys, order_request, args.dups, args.late, args.wave))

    # Control: the same payment burst with no idempotency key. Even without a key an order may hold only
    # one live payment, so each order must end with exactly one (the rest are 400 ORDER_PAYMENT_IN_PROGRESS).
    with ThreadPoolExecutor(16) as pool:
        control_orders = list(pool.map(lambda i: new_order("ctl", i), range(args.control)))
    payments_per_order = Counter()
    for i, (auth, order_id) in enumerate(control_orders):
        body = payment_body(auth, order_id, i)
        results = burst(5, lambda: post(auth, "/v1/payments", body))
        payments_per_order[len({b.get("id") for s, b in results if 200 <= s < 300 and b.get("id")})] += 1
    summary["control_no_key"] = {"orders": args.control, "requests_per_order": 5,
                                 "payments_created_per_order": dict(sorted(payments_per_order.items()))}

    summary["seconds"] = round(time.time() - started, 1)
    print(json.dumps(summary, indent=2))

    duplicated = summary["payments"]["keys_resolving_to_several_ids"] + summary["orders"]["keys_resolving_to_several_ids"]
    unanswered = summary["payments"]["keys_with_no_success"] + summary["orders"]["keys_with_no_success"]
    retried = summary["payments"]["duplicate_requests"] + summary["orders"]["duplicate_requests"]
    multi_paid = sum(orders for created, orders in payments_per_order.items() if created != 1)
    verdict = "PASS" if duplicated == 0 and unanswered == 0 and multi_paid == 0 else "FAIL"
    print(f"\n{verdict}  {retried} duplicate requests, {duplicated} keys took effect more than once, "
          f"{unanswered} keys never succeeded, {multi_paid} keyless orders without exactly one payment",
          file=sys.stderr)
    sys.exit(0 if verdict == "PASS" else 1)


if __name__ == "__main__":
    main()
