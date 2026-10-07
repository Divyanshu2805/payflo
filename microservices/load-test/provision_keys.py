"""Create load-test merchants and write their API keys to keys.csv.

Signs up N merchants through the gateway, logs each one in, creates a TEST API key, and writes
`keyId,secret` per line. The load test gives each JMeter thread one of these keys, so traffic is
spread across merchants the way real traffic would be.

    python provision_keys.py --merchants 50
    python provision_keys.py --base-url http://localhost:8080 --merchants 200 --out keys.csv
    python provision_keys.py --merchants 50 --out keys-webhooks.csv --webhook-url http://localhost:8084/webhook/success

With --webhook-url every merchant also registers that URL for its events, so the load test generates webhook
deliveries and the webhook SLA can be measured (operations-service's /webhook/success answers 204 to anything).

Only standard library, so it runs anywhere Python 3.10+ does.
"""
import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid


def call(base_url, method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(base_url + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode() or "null")
    except urllib.error.HTTPError as error:
        raise SystemExit(f"{method} {path} failed: HTTP {error.code} {error.read().decode()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://localhost:8080", help="API gateway URL")
    parser.add_argument("--merchants", type=int, default=50, help="how many merchants/API keys to create")
    parser.add_argument("--out", default="keys.csv", help="output CSV (gitignored)")
    parser.add_argument("--webhook-url", help="also register this webhook URL for every merchant")
    parser.add_argument("--webhook-events", default="ALL",
                        help="events the webhook subscribes to: ALL, or e.g. PAYMENT_STATUS_CHANGED,ORDER_CREATED")
    args = parser.parse_args()

    run = uuid.uuid4().hex[:8]
    password = "LoadTest-" + run
    lines = []
    for i in range(args.merchants):
        email = f"loadtest-{run}-{i}@payflo.test"
        call(args.base_url, "POST", "/v1/auth/signup", {
            "name": f"Load Test {i}", "email": email, "password": password,
            "businessName": f"Load Test Merchant {i}", "businessType": "PROPRIETORSHIP",
        })
        token = call(args.base_url, "POST", "/v1/auth/login", {"email": email, "password": password})["accessToken"]
        key = call(args.base_url, "POST", "/v1/merchants/api-keys", {"environment": "TEST"}, token)
        if args.webhook_url:
            call(args.base_url, "POST", "/v1/merchants/webhooks",
                 {"targetUrl": args.webhook_url, "eventTypes": args.webhook_events}, token)
        lines.append(f"{key['keyId']},{key['keySecret']}")
        print(f"\rcreated {i + 1}/{args.merchants} merchants", end="", file=sys.stderr)

    with open(args.out, "w", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print(f"\nwrote {len(lines)} API keys to {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
