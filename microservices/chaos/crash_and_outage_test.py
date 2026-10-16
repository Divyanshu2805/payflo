"""Crash and outage tests: does the system lose, duplicate or strand a payment when something dies?

Runs against a stack you already started (the gateway on :8080, the four services, and the PostgreSQL, Redis and Kafka
containers). Standard library only, Python 3.10+.

    python chaos/crash_and_outage_test.py                      # every scenario
    python chaos/crash_and_outage_test.py payment-service-crash kafka-outage
    python chaos/crash_and_outage_test.py --port-offset 100    # a stack started with demo.py --port-offset 100
    python chaos/crash_and_outage_test.py --list

Each scenario starts a fresh set of merchants, registers a webhook to a receiver run by this script, and has a crowd of
customers place an order and pay it, each one retrying with an idempotency key, the way a real client would. A few
seconds in, something is killed or switched off. When it is back and the system has had time to settle, the script
checks, from the database and from what the clients and the webhook receiver saw:

  no duplicates       one order and one payment per customer, however often the client retried
  nothing lost        everything a client was told succeeded exists in the database
  nothing stuck       every payment reached an end state (CAPTURED or FAILED), none left authorizing
  money is consistent a captured payment's order is paid, a paid order has a captured payment
  events delivered    every captured payment's webhook reached the merchant's server (at least once)
  outbox drained      every event written to the outbox was published to Kafka

and it times the recovery, from the moment the component is back (a restarted service reports healthy, a stopped
container is started again):

  first payment       until a new customer's payment goes through again: a fresh order paid by UPI and seen CAPTURED
  all settled         until every customer's payment has ended CAPTURED or FAILED and the outbox is drained

A new payment takes a second or two even with nothing broken (the simulated bank answers a UPI payment in 1-3 s), so
each scenario also times a few payments before the fault, as the baseline to read the first figure against.

"kill" is a hard kill (SIGKILL / taskkill /F): no shutdown hooks, no draining, the same as a crashed process or a pulled
plug. "outage" stops a container.
"""
import argparse
import base64
import json
import os
import platform
import random
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]            # microservices/
BASE = ""                         # the gateway, set by use_ports()
PG_CONTAINER = os.environ.get("CHAOS_PG_CONTAINER", "pgvector-payflo")
PG_USER, PG_PASSWORD = os.environ.get("CHAOS_PG_USER", "user"), os.environ.get("CHAOS_PG_PASSWORD", "password")
REDIS_CONTAINER = os.environ.get("CHAOS_REDIS_CONTAINER", "redis")
KAFKA_CONTAINER = os.environ.get("CHAOS_KAFKA_CONTAINER", "kafka-razorpay-me")
LOG_DIR = Path(os.environ.get("CHAOS_LOG_DIR", ROOT / "chaos" / "logs"))
RESULTS_DIR = ROOT / "chaos" / "results"

SERVICES = {}                     # name: (port, health URL), set by use_ports()
SERVICE_ENV = {}                  # what a restarted service needs to find discovery and config on their (possibly moved) ports


def use_ports(offset):
    """Every port moves by --port-offset, as with demo.py's option of the same name."""
    global BASE
    port = lambda base: base + offset
    health = lambda base: f"http://127.0.0.1:{port(base)}/actuator/health"
    SERVICES.clear()
    SERVICES.update({
        "api-gateway-service": (port(8080), health(9081)),            # its Actuator is on the management port
        "merchant-service": (port(8081), health(8081)),
        "payment-service": (port(8082), health(8082)),
        "vault-service": (port(8083), health(8083)),
        "operations-service": (port(8084), health(8084)),
    })
    SERVICE_ENV.clear()
    SERVICE_ENV.update({"EUREKA_URL": f"http://localhost:{port(8761)}/eureka", "CONFIG_SERVER_URL": f"http://localhost:{port(8888)}",
                        "MANAGEMENT_PORT": str(port(9081))})
    BASE = os.environ.get("CHAOS_BASE_URL", f"http://127.0.0.1:{port(8080)}")


WINDOWS = platform.system() == "Windows"

MERCHANTS = 6                     # customers are spread over these, so no single API key hits its rate limit
CUSTOMERS = 36
CLIENT_DEADLINE = 150             # seconds a customer keeps retrying before it gives up
SETTLE_TIMEOUT = 300              # seconds allowed for the system to converge after the fault
BASELINE_PAYMENTS = 3             # payments timed before the fault, to know how long one takes with nothing broken


def log(message):
    print(time.strftime("%H:%M:%S"), message, flush=True)


# ------------------------------------------------------------------------------------------------ process and container control

def run(command, **kwargs):
    return subprocess.run(command, capture_output=True, text=True, **kwargs)


def pid_on_port(port):
    if WINDOWS:
        for line in run(["netstat", "-ano", "-p", "TCP"]).stdout.splitlines():
            parts = line.split()
            if len(parts) >= 5 and parts[3] == "LISTENING" and parts[1].endswith(f":{port}"):
                return int(parts[4])
        return None
    out = run(["lsof", "-t", f"-iTCP:{port}", "-sTCP:LISTEN"]).stdout.split()
    return int(out[0]) if out else None


def kill_service(name):
    pid = pid_on_port(SERVICES[name][0])
    if pid is None:
        log(f"  {name} was not running")
        return
    if WINDOWS:
        run(["taskkill", "/F", "/PID", str(pid)])
    else:
        os.kill(pid, signal.SIGKILL)
    log(f"  killed {name} (pid {pid}) with no warning")


# Customers retry hard during a fault, and the default limit is 200 requests a minute per API key, so the gateway always
# runs with it raised here, including when a scenario restarts it.
GATEWAY_ENV = {"API_KEY_RATE_LIMIT_PER_MINUTE": "100000", "PUBLIC_AUTH_RATE_LIMIT_PER_MINUTE": "100000"}


def start_service(name, env=None):
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    out = open(LOG_DIR / f"{name}.log", "ab")
    full_env = dict(os.environ, **SERVICE_ENV, **(GATEWAY_ENV if name == "api-gateway-service" else {}), **(env or {}))
    subprocess.Popen(["java", "-Duser.timezone=Asia/Kolkata", "-jar", f"target/{name}-0.0.1-SNAPSHOT.jar",
                      f"--server.port={SERVICES[name][0]}"], cwd=ROOT / name,
                     stdout=out, stderr=subprocess.STDOUT, env=full_env,
                     creationflags=(0x08000000 | 0x00000008) if WINDOWS else 0)     # no console window, detached
    log(f"  started {name}")


def healthy(url):
    try:
        with urllib.request.urlopen(url, timeout=3) as r:
            return json.loads(r.read()).get("status") == "UP"
    except Exception:
        return False


def wait_healthy(name, timeout=240, on_up=None):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if healthy(SERVICES[name][1]):
            if on_up:
                on_up()
            time.sleep(8 if name != "api-gateway-service" else 3)         # a moment for Eureka to hand out the new address
            return True
        time.sleep(2)
    return False


def restart_service(name, downtime=0, env=None, on_back=None):
    kill_service(name)
    time.sleep(downtime)
    start_service(name, env)
    if not wait_healthy(name, on_up=on_back):
        raise RuntimeError(f"{name} did not come back")
    log(f"  {name} is back")


def ensure_all_up():
    for name in SERVICES:
        if not healthy(SERVICES[name][1]):
            log(f"  {name} is down: starting it")
            start_service(name)
            wait_healthy(name)


def docker(*args):
    return run(["docker", *args])


def container_stop(container):
    docker("stop", "-t", "0", container)
    log(f"  stopped {container}")


def container_start(container, settle=12, on_back=None):
    docker("start", container)
    if on_back:
        on_back()
    time.sleep(settle)
    log(f"  started {container}")


def psql(db, sql):
    out = run(["docker", "exec", "-e", f"PGPASSWORD={PG_PASSWORD}", PG_CONTAINER, "psql", "-U", PG_USER, "-d", db, "-Atc", sql])
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout.strip()


# ------------------------------------------------------------------------------------------------ the API, as a client sees it

def call(method, path, body=None, basic=None, token=None, key=None, timeout=6):
    headers = {"Content-Type": "application/json"}
    if basic:
        headers["Authorization"] = "Basic " + base64.b64encode(basic.encode()).decode()
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["X-Idempotency-Key"] = key
    request = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, None
    except Exception:
        return 0, None                    # refused, reset or timed out: the client doesn't know what happened


def retry(request, deadline):
    """What a careful client does: repeat the same request, same idempotency key, until it is told something final."""
    attempts = 0
    while time.monotonic() < deadline:
        attempts += 1
        status, body = request()
        if status in (200, 201):
            return status, body, attempts
        if status == 422:                                                     # the key was reused for a different request
            return status, body, attempts
        if status in (400, 401, 403, 404):                                    # the request itself is wrong: retrying won't help
            return status, body, attempts
        time.sleep(0.8 + random.random())                                     # 0 (no answer), 409 (in progress), 429, 5xx
    return 0, None, attempts


# ------------------------------------------------------------------------------------------------ the merchant's webhook receiver

class Receiver:
    def __init__(self):
        self.events = []                                                      # (payment id, status), as delivered
        self.lock = threading.Lock()
        receiver = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                try:
                    payload = json.loads(body).get("payload", {})
                    with receiver.lock:
                        receiver.events.append((payload.get("paymentId"), payload.get("paymentStatus")))
                except Exception:
                    pass
                self.send_response(200)
                self.end_headers()

            def log_message(self, *args):
                pass

        class Server(ThreadingHTTPServer):
            request_queue_size = 256      # the default backlog of 5 resets most of a burst of deliveries, as a failed attempt
            daemon_threads = True

        self.server = Server(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}/hook"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def captured(self):
        with self.lock:
            return {payment for payment, status in self.events if status == "CAPTURED"}


# ------------------------------------------------------------------------------------------------ one scenario

class Merchant:
    def __init__(self, label, receiver=None):
        suffix = uuid.uuid4().hex[:8]
        email, password = f"chaos-{label}-{suffix}@payflo.test", "Chaos-Pass-" + suffix
        status, signup = call("POST", "/v1/auth/signup", {"name": "Chaos", "email": email, "password": password,
                                                          "businessName": "Chaos " + suffix, "businessType": "TRUST"})
        assert status == 201, f"signup failed: {status} {signup}"
        self.id = signup["id"]
        self.jwt = call("POST", "/v1/auth/login", {"email": email, "password": password})[1]["accessToken"]
        key = call("POST", "/v1/merchants/api-keys", {"environment": "TEST"}, token=self.jwt)[1]
        self.basic = f"{key['keyId']}:{key['keySecret']}"
        if receiver:
            hook = call("POST", "/v1/merchants/webhooks", {"targetUrl": receiver.url}, token=self.jwt)
            assert hook[0] == 200, f"webhook registration failed: {hook}"
        status, card = call("POST", "/v1/vault/tokenize", {"pan": "4111111111111111", "cvv": "123", "expiryMonth": 12, "expiryYear": 2031,
                                                          "cardHolderName": "Chaos Test"}, basic=self.basic)
        assert status == 201, f"tokenize failed: {status} {card}"
        self.card_token = card["token"]


def customer(index, merchant, run_id, card_share, fault_started, outcome):
    """One customer: create an order, then pay it. Retries everything, the way a client library would."""
    time.sleep(random.random() * 12)                                          # spread over the first moments, so the fault lands mid-flight
    deadline = time.monotonic() + CLIENT_DEADLINE
    ref = f"{run_id}-{index}"
    outcome[index] = {"ref": ref, "merchant": merchant.id, "order": None, "payment": None, "attempts": 0, "failed": None}
    body = {"amount": {"amountUnits": 10_000 + index, "currency": "INR"}, "notes": {"ref": ref}}
    if index % 2:
        body["receipt"] = ref                                                 # half the customers also send a receipt, half rely on the key alone
    status, order, attempts = retry(lambda: call("POST", "/v1/orders", body, basic=merchant.basic, key=f"o-{ref}"), deadline)
    outcome[index]["attempts"] += attempts
    if status not in (200, 201):
        outcome[index]["failed"] = f"order {status}"
        return
    outcome[index]["order"] = order["id"]
    if random.random() < card_share:
        payment = {"orderId": order["id"], "method": "CARD", "methodDetails": {"token": merchant.card_token}}
    else:
        payment = {"orderId": order["id"], "method": "UPI", "methodDetails": {"vpa": f"c{index}@okaxis"}}
    status, paid, attempts = retry(lambda: call("POST", "/v1/payments", payment, basic=merchant.basic, key=f"p-{ref}"), deadline)
    outcome[index]["attempts"] += attempts
    if status not in (200, 201):
        outcome[index]["failed"] = f"payment {status}"
        return
    outcome[index]["payment"] = paid["id"]


def probe_payment(merchant, wait=30):
    """A new customer paying by UPI, once, with no retry: the moment its payment is seen CAPTURED, or None."""
    ref = "probe-" + uuid.uuid4().hex[:12]
    status, order = call("POST", "/v1/orders", {"amount": {"amountUnits": 10_000, "currency": "INR"}, "notes": {"ref": ref}},
                         basic=merchant.basic, key=f"o-{ref}")
    if status not in (200, 201):
        return None
    status, paid = call("POST", "/v1/payments", {"orderId": order["id"], "method": "UPI", "methodDetails": {"vpa": f"{ref}@okaxis"}},
                        basic=merchant.basic, key=f"p-{ref}")
    if status not in (200, 201):
        return None
    end = time.monotonic() + wait
    while time.monotonic() < end:
        status, payment = call("GET", f"/v1/payments/{paid['id']}", basic=merchant.basic)
        if status == 200 and payment["status"] == "CAPTURED":
            return time.monotonic()
        if status == 200 and payment["status"] == "FAILED":                  # the simulated bank declines a few by design
            return None
        time.sleep(0.25)
    return None


class RecoveryClock:
    """Times the recovery from the moment the fault's component is back.

    first payment: a new payment is started every second, by a merchant of its own so the scenario's checks don't see
    it, until one is captured. all settled: the database is read every second until every customer is done, each of
    their payments has ended CAPTURED or FAILED, and none of their events is waiting in the outbox.
    """

    def __init__(self, probe_merchant, customers, unsettled):
        self.probe_merchant, self.customers, self.unsettled = probe_merchant, customers, unsettled
        self.fault_started = self.back = self.first_payment = self.settled = None
        self.lock, self.done = threading.Lock(), threading.Event()

    def component_back(self):
        if self.back is not None:
            return
        self.back = time.monotonic()
        log(f"  component back {self.back - self.fault_started:.1f} s after the fault: timing the recovery")
        threading.Thread(target=self._probe, daemon=True).start()
        threading.Thread(target=self._watch, daemon=True).start()

    def _probe(self):
        def one():
            captured = probe_payment(self.probe_merchant)
            if captured is not None:
                with self.lock:
                    self.first_payment = min(self.first_payment or captured, captured)
        end = self.back + SETTLE_TIMEOUT
        while self.first_payment is None and time.monotonic() < end:
            threading.Thread(target=one, daemon=True).start()
            time.sleep(1)

    def _watch(self):
        end = self.back + CLIENT_DEADLINE + SETTLE_TIMEOUT
        while time.monotonic() < end:
            try:
                if not any(t.is_alive() for t in self.customers) and self.unsettled() == 0:
                    self.settled = time.monotonic()
                    break
            except RuntimeError:                                              # PostgreSQL not answering yet
                pass
            time.sleep(1)
        self.done.set()

    def wait(self):
        if self.back is None:                                                 # the fault never healed: nothing to time
            return
        self.done.wait(CLIENT_DEADLINE + SETTLE_TIMEOUT + 10)
        wait_until(lambda: self.first_payment is not None, 40, interval=1)   # a probe still waiting on the bank

    def seconds(self, since, moment):
        return None if since is None or moment is None else round(moment - since, 1)

    def summary(self, normal):
        return {"component_back_after_fault_s": self.seconds(self.fault_started, self.back),
                "first_payment_captured_s": self.seconds(self.back, self.first_payment),
                "all_settled_and_drained_s": self.seconds(self.back, self.settled),
                "normal_payment_capture_s": normal}


class Report:
    def __init__(self, scenario):
        self.scenario, self.checks = scenario, []

    def check(self, name, ok, detail=""):
        self.checks.append({"check": name, "ok": bool(ok), "detail": str(detail)})
        log(f"  {'PASS' if ok else 'FAIL'}  {name}" + ("" if ok else f"  -> {detail}"))

    @property
    def passed(self):
        return all(c["ok"] for c in self.checks)


def wait_until(condition, timeout, interval=3):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if condition():
            return True
        time.sleep(interval)
    return condition()


def run_scenario(name, fault, card_share):
    log(f"=== {name}")
    report = Report(name)
    receiver = Receiver()
    run_id = uuid.uuid4().hex[:8]
    merchants = [Merchant(f"{name[:12]}-{i}", receiver) for i in range(MERCHANTS)]
    probe_merchant = Merchant(f"{name[:12]}-probe")                          # its payments time the recovery; no check reads them
    ready_at = time.monotonic() + 35                                          # the delivery side caches a merchant's webhook targets briefly
    baseline = []
    for _ in range(BASELINE_PAYMENTS):
        began = time.monotonic()
        captured = probe_payment(probe_merchant)
        if captured is not None:
            baseline.append(captured - began)
    normal = round(sorted(baseline)[len(baseline) // 2], 1) if baseline else None
    log(f"  with nothing broken, a new payment is captured in {normal} s")
    time.sleep(max(0.0, ready_at - time.monotonic()))
    ids = ",".join(f"'{m.id}'" for m in merchants)
    theirs = f"payload::text similar to '%({'|'.join(m.id for m in merchants)})%'"

    def unsettled():
        return int(psql("payflo_payment", f"select (select count(*) from payment where merchant_id in ({ids}) and status not in ('CAPTURED', 'FAILED'))"
                                          f" + (select count(*) from outbox_event where status <> 'PUBLISHED' and {theirs})"))

    outcome, started = {}, threading.Event()
    threads = [threading.Thread(target=customer, args=(i, merchants[i % MERCHANTS], run_id, card_share, started, outcome)) for i in range(CUSTOMERS)]
    clock = RecoveryClock(probe_merchant, threads, unsettled)
    log(f"  {CUSTOMERS} customers starting; the fault lands in 6 seconds")
    for t in threads:
        t.start()
    time.sleep(6)
    started.set()
    clock.fault_started = time.monotonic()
    try:
        fault(clock.component_back)
    except Exception as e:
        report.check("the fault could be injected and healed", False, e)
    for t in threads:
        t.join(timeout=CLIENT_DEADLINE + 30)
    clock.wait()
    report.recovery = clock.summary(normal)
    log(f"  recovery after the component was back: a new payment captured in {report.recovery['first_payment_captured_s']} s, "
        f"everything settled and the outbox drained in {report.recovery['all_settled_and_drained_s']} s")

    acknowledged_orders = [o["order"] for o in outcome.values() if o["order"]]
    acknowledged_payments = [o["payment"] for o in outcome.values() if o["payment"]]
    gave_up = {i: o["failed"] for i, o in outcome.items() if o["failed"]}
    retried = sum(1 for o in outcome.values() if o["attempts"] > 2)
    log(f"  clients done: {len(acknowledged_orders)} orders and {len(acknowledged_payments)} payments acknowledged, "
        f"{retried} customers had to retry, {len(gave_up)} gave up")

    # let the system converge: every payment ends, every event is published, every webhook arrives
    def payment_states():
        rows = psql("payflo_payment", f"select status || ':' || count(*) from payment where merchant_id in ({ids}) group by status")
        return dict(line.split(":") for line in rows.splitlines()) if rows else {}

    end_states = {"CAPTURED", "FAILED"}
    wait_until(lambda: set(payment_states()) <= end_states, SETTLE_TIMEOUT)
    wait_until(lambda: psql("payflo_payment", f"select count(*) from outbox_event where status <> 'PUBLISHED' and {theirs}") == "0", 120)
    captured_ids = {line for line in psql("payflo_payment", f"select id from payment where merchant_id in ({ids}) and status = 'CAPTURED'").splitlines()}
    wait_until(lambda: captured_ids <= receiver.captured(), SETTLE_TIMEOUT)

    # ---- the invariants
    report.check("every customer got through (the clients' retries were enough)", not gave_up, gave_up)

    duplicate_orders = psql("payflo_payment", f"select notes->>'ref' || ' x' || count(*) from order_record where merchant_id in ({ids}) group by notes->>'ref' having count(*) > 1")
    order_count = int(psql("payflo_payment", f"select count(*) from order_record where merchant_id in ({ids})"))
    report.check("no duplicates: one order per customer", not duplicate_orders and order_count == len(acknowledged_orders),
                 f"{order_count} orders in the database, {len(acknowledged_orders)} acknowledged; duplicated: {duplicate_orders}")

    payment_count = int(psql("payflo_payment", f"select count(*) from payment where merchant_id in ({ids})"))
    distinct_orders = int(psql("payflo_payment", f"select count(distinct order_id) from payment where merchant_id in ({ids})"))
    report.check("no duplicates: one payment per order", payment_count == distinct_orders == len(acknowledged_payments),
                 f"{payment_count} payments over {distinct_orders} orders, {len(acknowledged_payments)} acknowledged")

    if acknowledged_payments:
        stored = int(psql("payflo_payment", f"select count(*) from payment where id in ({','.join(repr(p) for p in acknowledged_payments)})"))
        report.check("nothing lost: every payment a client was told about is in the database", stored == len(acknowledged_payments),
                     f"{stored} of {len(acknowledged_payments)}")

    states = payment_states()
    report.check("nothing stuck: every payment reached CAPTURED or FAILED", set(states) <= end_states, states)

    unpaid_captured = int(psql("payflo_payment", "select count(*) from payment p join order_record o on o.id = p.order_id "
                               f"where p.merchant_id in ({ids}) and p.status = 'CAPTURED' and o.order_status <> 'PAID'"))
    paid_without_capture = int(psql("payflo_payment", "select count(*) from order_record o where o.merchant_id in "
                                    f"({ids}) and o.order_status = 'PAID' and not exists (select 1 from payment p where p.order_id = o.id and p.status = 'CAPTURED')"))
    report.check("money is consistent: captured payments' orders are PAID, and nothing is PAID without a capture",
                 unpaid_captured == 0 and paid_without_capture == 0, f"{unpaid_captured} captured but unpaid, {paid_without_capture} paid without capture")

    undelivered = int(psql("payflo_payment", f"select count(*) from outbox_event where status <> 'PUBLISHED' and {theirs}"))
    report.check("outbox drained: every event was published to Kafka", undelivered == 0, f"{undelivered} still waiting")

    missing = captured_ids - receiver.captured()
    report.check("events delivered: every captured payment's webhook reached the merchant (at least once)", not missing,
                 f"{len(missing)} of {len(captured_ids)} missing")

    report.counts = {"customers": CUSTOMERS, "orders": order_count, "payments": payment_count, "payment_states": states,
                     "customers_that_retried": retried, "webhooks_received": len(receiver.events)}
    receiver.server.shutdown()
    return report


# ------------------------------------------------------------------------------------------------ the faults

def crash(service, downtime=6):
    return lambda back: restart_service(service, downtime, on_back=back)


def outage(container, seconds=25):
    def fault(back):
        container_stop(container)
        time.sleep(seconds)
        container_start(container, on_back=back)
    return fault


SCENARIOS = {
    "payment-service-crash": (crash("payment-service"), 0.3),
    "vault-service-crash": (crash("vault-service"), 1.0),               # every payment is a card payment, so the vault is on the path
    "gateway-crash": (crash("api-gateway-service", 4), 0.3),
    "operations-service-crash": (crash("operations-service", 8), 0.3),  # webhook delivery stops while payments carry on
    "kafka-outage": (outage(KAFKA_CONTAINER, 25), 0.3),
    "redis-outage": (outage(REDIS_CONTAINER, 25), 0.3),
    "postgres-outage": (outage(PG_CONTAINER, 20), 0.3),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("scenarios", nargs="*", help="which to run (default: all)")
    parser.add_argument("--port-offset", type=int, default=int(os.environ.get("CHAOS_PORT_OFFSET", 0)),
                        help="the stack's ports moved by this much (demo.py --port-offset)")
    parser.add_argument("--list", action="store_true")
    args = parser.parse_args()
    use_ports(args.port_offset)
    if args.list:
        print("\n".join(SCENARIOS))
        return 0
    chosen = args.scenarios or list(SCENARIOS)
    unknown = [s for s in chosen if s not in SCENARIOS]
    if unknown:
        sys.exit(f"unknown scenario(s): {unknown}; --list shows them")

    log("restarting the gateway with a raised API-key rate limit, so retries are not mistaken for abuse")
    restart_service("api-gateway-service")

    reports = []
    for name in chosen:
        ensure_all_up()
        fault, card_share = SCENARIOS[name]
        reports.append(run_scenario(name, fault, card_share))

    log("")
    log("SUMMARY")
    for report in reports:
        log(f"  {'PASS' if report.passed else 'FAIL'}  {report.scenario}   {report.counts}")
    log("")
    log("RECOVERY, in seconds from the component being back (a new payment takes the 'normal' time with nothing broken)")
    shown = lambda value: "-" if value is None else f"{value:.1f}"
    log(f"  {'scenario':<26}{'back after':>11}{'normal':>8}{'first payment':>15}{'all settled':>13}")
    for r in reports:
        rec = r.recovery
        log(f"  {r.scenario:<26}{shown(rec['component_back_after_fault_s']):>11}{shown(rec['normal_payment_capture_s']):>8}"
            f"{shown(rec['first_payment_captured_s']):>15}{shown(rec['all_settled_and_drained_s']):>13}")
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    out = RESULTS_DIR / (time.strftime("%Y%m%d-%H%M%S") + ".json")
    out.write_text(json.dumps([{"scenario": r.scenario, "passed": r.passed, "counts": r.counts, "recovery": r.recovery, "checks": r.checks}
                               for r in reports], indent=2))
    log(f"  results written to {out}")
    return 0 if all(r.passed for r in reports) else 1


if __name__ == "__main__":
    sys.exit(main())
