"""One command to see PayFlo working: start everything, fill it with a merchant's day, open the dashboard.

    python demo/demo.py            # start what isn't running, seed the demo merchant, serve the dashboard
    python demo/demo.py seed       # only add another batch of orders and payments to a running stack
    python demo/demo.py status     # what is up
    python demo/demo.py stop       # stop the services and the dashboard this script started
    python demo/demo.py --build    # rebuild the jars first (needed after a code change)
    python demo/demo.py --port-offset 100     # if 8080-8084, 8761, 8888 or 5173 are taken: use 8180-8184, 8861, ...

Run it from microservices/. It needs Docker, a JDK 25 and Python 3.10+, and nothing else: standard library only.

What "up" does, skipping every step that is already done:

  1. starts PostgreSQL, Redis and Kafka (services.docker-compose.yaml) and creates the four databases
  2. builds the jars if there are none
  3. starts discovery, config, the four business services and the gateway, in that order, and waits for each
  4. starts the dashboard on http://localhost:5173 (dashboard/serve.py)
  5. seeds: a merchant that has passed KYC, an API key, two webhook endpoints (one that works, one that fails),
     a team member, orders paid by card, UPI, net banking and wallet, the test values that fail, a refund,
     and a settlement run by the platform operator

Everything goes through the gateway on :8080, the way a merchant's own code would. The seed is safe to run again:
it logs in to the same merchant and adds a new batch.
"""
import argparse
import base64
import json
import os
import platform
import re
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]            # microservices/
REPO = ROOT.parent
RUN_DIR = ROOT / "demo" / ".run"                       # pids and the demo API key; gitignored
LOG_DIR = ROOT / "demo" / "logs"
WINDOWS = platform.system() == "Windows"


# Development-only values, like every default in config-repo. The admin key is the gateway's documented development
# default (docs/api/admin.md); set ADMIN_API_KEY if yours differs.
DEMO_EMAIL = os.environ.get("DEMO_EMAIL", "demo@payflo.test")
DEMO_PASSWORD = os.environ.get("DEMO_PASSWORD", "demo-password-1")
TEAM_EMAIL = os.environ.get("DEMO_TEAM_EMAIL", "analyst@payflo.test")
ADMIN_KEY = os.environ.get("ADMIN_API_KEY", "dev-admin-api-key-change-me")

PG_CONTAINER, PG_USER = "pgvector-payflo", "user"
CONTAINERS = {"postgres": PG_CONTAINER, "redis": "redis", "kafka": "kafka-razorpay-me"}     # compose service: container
DATABASES = ["payflo_merchant", "payflo_payment", "payflo_vault", "payflo_operations"]

BUSINESS = ["merchant-service", "vault-service", "payment-service", "operations-service"]

# Filled in by use_ports(): every port below moves by --port-offset, for a machine where the usual ones are taken.
OFFSET = 0
SERVICES = {}          # name: (port, URL that answers 200 once it is ready). Started top to bottom.
SERVICE_ENV = {}       # what every service needs to find discovery and config on their (possibly moved) ports
GATEWAY = DASHBOARD = WEBHOOK_OK = WEBHOOK_FAILING = ""
DASHBOARD_PORT = 0


def use_ports(offset):
    global OFFSET, GATEWAY, DASHBOARD, DASHBOARD_PORT, WEBHOOK_OK, WEBHOOK_FAILING
    OFFSET = offset
    port = lambda base: base + offset
    health = lambda base: f"http://127.0.0.1:{port(base)}/actuator/health"
    SERVICES.clear()
    SERVICES.update({
        "discovery-service": (port(8761), f"http://127.0.0.1:{port(8761)}/eureka/apps"),
        "config-service": (port(8888), health(8888)),
        "merchant-service": (port(8081), health(8081)),
        "vault-service": (port(8083), health(8083)),
        "payment-service": (port(8082), health(8082)),
        "operations-service": (port(8084), health(8084)),
        "api-gateway-service": (port(8080), health(9081)),          # its Actuator is on the management port
    })
    SERVICE_ENV.clear()
    SERVICE_ENV.update({"EUREKA_URL": f"http://localhost:{port(8761)}/eureka",
                        "CONFIG_SERVER_URL": f"http://localhost:{port(8888)}",
                        "MANAGEMENT_PORT": str(port(9081))})          # read by the gateway only
    GATEWAY = os.environ.get("DEMO_BASE_URL", f"http://localhost:{port(8080)}")
    DASHBOARD_PORT = port(5173)
    DASHBOARD = f"http://localhost:{DASHBOARD_PORT}"
    WEBHOOK_OK = f"http://localhost:{port(8084)}/webhook/success"            # operations-service's test receiver: 204
    WEBHOOK_FAILING = f"http://localhost:{port(8084)}/webhook/unreachable"   # no such endpoint: every attempt fails


def log(message=""):
    print(message, flush=True)


def step(message):
    log(f"\n== {message}")


def fail(message):
    raise SystemExit(f"\nERROR: {message}")


# ------------------------------------------------------------------------------------------------ processes and containers

def run(command, **kwargs):
    return subprocess.run(command, capture_output=True, text=True, **kwargs)


def answers(url, timeout=3):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status == 200
    except Exception:
        return False


def fetch(url, timeout=3):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.read().decode(errors="replace")
    except Exception:
        return ""


def port_open(port):
    with socket.socket() as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def pid_on_port(port):
    if WINDOWS:
        for line in run(["netstat", "-ano", "-p", "TCP"]).stdout.splitlines():
            parts = line.split()
            if len(parts) >= 5 and parts[3] == "LISTENING" and parts[1].endswith(f":{port}"):
                return int(parts[4])
        return None
    out = run(["lsof", "-t", f"-iTCP:{port}", "-sTCP:LISTEN"]).stdout.split()
    return int(out[0]) if out else None


def spawn(name, command, cwd, env=None):
    """Starts a process that outlives this script, with its output in demo/logs/<name>.log."""
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    out = open(LOG_DIR / f"{name}.log", "ab")
    process = subprocess.Popen(command, cwd=cwd, stdout=out, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                               env=dict(os.environ, **(env or {})),
                               creationflags=(0x08000000 | 0x00000008) if WINDOWS else 0,     # no window, detached
                               start_new_session=not WINDOWS)
    started = read_json("started.json", {})
    started[name] = process.pid
    write_json("started.json", started)


def read_json(name, default):
    try:
        return json.loads((RUN_DIR / name).read_text())
    except (OSError, ValueError):
        return default


def write_json(name, value):
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    (RUN_DIR / name).write_text(json.dumps(value, indent=2))


def wait_for(what, check, timeout):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if check():
            return
        time.sleep(2)
    fail(f"{what} was not ready after {timeout} s. Logs are in {LOG_DIR}")


def start_infrastructure():
    step("Infrastructure: PostgreSQL, Redis, Kafka")
    if run(["docker", "info"]).returncode != 0:
        fail("Docker isn't running. Start Docker and run this again.")
    existing = run(["docker", "ps", "-a", "--format", "{{.Names}}"]).stdout.split()
    running = run(["docker", "ps", "--format", "{{.Names}}"]).stdout.split()
    for service, container in CONTAINERS.items():
        if container in running:
            log(f"   {container} is running")
        elif container in existing:          # created earlier, perhaps from another checkout: start it as it is
            log(f"   starting {container}")
            if run(["docker", "start", container]).returncode != 0:
                fail(f"could not start the existing container {container}")
        else:
            log(f"   creating {container}")
            created = run(["docker", "compose", "-f", str(REPO / "services.docker-compose.yaml"), "up", "-d", service])
            if created.returncode != 0:
                fail(f"docker compose could not start {service}:\n{created.stderr}")

    # Through the "postgres" database, which every server has, rather than the compose file's POSTGRES_DB: a
    # container created earlier with other settings may not have that one.
    psql = ["docker", "exec", PG_CONTAINER, "psql", "-U", PG_USER, "-d", "postgres"]
    wait_for("PostgreSQL", lambda: run(psql + ["-Atc", "SELECT 1"]).stdout.strip() == "1", 90)
    have = run(psql + ["-Atc", "SELECT datname FROM pg_database"]).stdout.split()
    for database in DATABASES:
        if database not in have:
            log(f"   creating database {database}")
            created = run(psql + ["-c", f"CREATE DATABASE {database}"])
            if created.returncode != 0:
                fail(f"could not create the database {database}: {created.stderr.strip()}")
    wait_for("Redis", lambda: port_open(6380), 60)
    wait_for("Kafka", lambda: port_open(29092), 120)
    log("   ready")


def jar(name):
    return ROOT / name / "target" / f"{name}-0.0.1-SNAPSHOT.jar"


def build(force):
    missing = [name for name in SERVICES if not jar(name).exists()]
    if not force and not missing:
        return
    step("Building every module (a few minutes the first time)")
    up = [name for name, (_, url) in SERVICES.items() if answers(url)]
    if up:
        fail(f"{', '.join(up)} is running and holds its jar open. Run 'python demo/demo.py stop' first.")
    version = run(["java", "-version"]).stderr
    if '"25' not in version:
        fail(f"PayFlo needs a JDK 25 on the PATH; found:\n{version}")
    mvnw = [str(ROOT / "mvnw.cmd")] if WINDOWS else ["sh", str(ROOT / "mvnw")]
    if subprocess.run(mvnw + ["-q", "-ntp", "clean", "install", "-DskipTests"], cwd=ROOT).returncode != 0:
        fail("the build failed; see the output above")


def is_ours(name, url):
    """Whether what answers on a service's port is that PayFlo service, started by hand rather than by this script.

    A health check settles it for most. Any Eureka answers /eureka/apps, though, so another project's registry on
    :8761 would pass: it is taken as PayFlo's only if nothing but PayFlo's services are registered in it.
    """
    if name != "discovery-service":
        return answers(url)
    registered = set(re.findall(r"<name>([^<]+)</name>", fetch(url)))
    return answers(url) and registered <= {s.upper() for s in SERVICES}


def start_services():
    step("Services")

    def start(name):
        port, url = SERVICES[name]
        holder = pid_on_port(port)
        if holder is not None:
            if holder == read_json("started.json", {}).get(name) or is_ours(name, url):
                log(f"   {name} is running")
                return False
            fail(f"port {port} is taken by another program (pid {holder}), so {name} can't start. "
                 f"Stop that program, or move PayFlo with: python demo/demo.py --port-offset 100")
        log(f"   starting {name} on :{port}")
        spawn(name, ["java", "-Duser.timezone=Asia/Kolkata", "-jar", str(jar(name)), f"--server.port={port}"],
              ROOT / name, SERVICE_ENV)
        return True

    for group in (["discovery-service"], ["config-service"], BUSINESS, ["api-gateway-service"]):
        started = [name for name in group if start(name)]
        for name in started:
            wait_for(name, lambda n=name: answers(SERVICES[n][1]), 300)
        if started and group != ["discovery-service"] and group != ["config-service"]:
            time.sleep(10)          # a moment for Eureka to hand the new addresses to the gateway and the Feign clients
    # The gateway being healthy doesn't mean it can route yet: wait until a public route really answers.
    wait_for("the gateway's routes", lambda: http("POST", "/v1/auth/login", {"email": "nobody@payflo.test",
                                                                             "password": "not-a-real-password"})[0] == 401, 120)
    log("   ready")


def dashboard_up():
    # By what it says, not by a 200: a development server on the same port answers 200 to any path.
    return fetch(f"http://127.0.0.1:{DASHBOARD_PORT}/healthz").strip() == "payflo-dashboard"


def start_dashboard():
    step("Dashboard")
    if dashboard_up():
        log(f"   running at {DASHBOARD}")
        return
    holder = pid_on_port(DASHBOARD_PORT)
    if holder is not None:
        fail(f"port {DASHBOARD_PORT} is taken by another program (pid {holder}), so the dashboard can't start. "
             f"Stop that program, or move PayFlo with: python demo/demo.py --port-offset 100")
    spawn("dashboard", [sys.executable, str(ROOT / "dashboard" / "serve.py"), "--port", str(DASHBOARD_PORT),
                        "--gateway", GATEWAY], ROOT / "dashboard")
    wait_for("the dashboard", dashboard_up, 30)
    log(f"   serving {DASHBOARD}")


def stop():
    step("Stopping what this script started")
    started = read_json("started.json", {})
    ports = dict({name: port for name, (port, _) in SERVICES.items()}, dashboard=DASHBOARD_PORT)
    for name in reversed(list(started)):
        # Only if that process still holds the port it was started for: a recorded pid may have been reused since.
        pid = pid_on_port(ports[name])
        if pid is None or pid != started[name]:
            continue
        if WINDOWS:
            run(["taskkill", "/F", "/PID", str(pid)])
        else:
            os.kill(pid, signal.SIGTERM)
        log(f"   stopped {name}")
    write_json("started.json", {})
    log("   PostgreSQL, Redis and Kafka are left running (docker stop pgvector-payflo redis kafka-razorpay-me)")


def status():
    step("Status")
    running = run(["docker", "ps", "--format", "{{.Names}}"]).stdout.split()
    for container in CONTAINERS.values():
        log(f"   {'up  ' if container in running else 'DOWN'}  {container}")
    for name, (port, url) in SERVICES.items():
        log(f"   {'up  ' if answers(url) else 'DOWN'}  {name} :{port}")
    log(f"   {'up  ' if dashboard_up() else 'DOWN'}  dashboard {DASHBOARD}")


# ------------------------------------------------------------------------------------------------ the API, as a client sees it

def http(method, path, body=None, token=None, key=None, admin=False, idempotency_key=None):
    """One request through the gateway. Returns (status, parsed body); never raises on an HTTP error."""
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Authorization"] = "Basic " + base64.b64encode(f"{key[0]}:{key[1]}".encode()).decode()
    if admin:
        headers["X-Admin-Key"] = ADMIN_KEY
    if idempotency_key:
        headers["X-Idempotency-Key"] = idempotency_key
    data = json.dumps(body).encode() if body is not None else None
    # 127.0.0.1, not localhost: the gateway isn't on ::1, and on Windows each call would wait two seconds to find out.
    request = urllib.request.Request(GATEWAY.replace("://localhost", "://127.0.0.1") + path, data=data, method=method,
                                     headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode()
            return response.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as error:
        text = error.read().decode()
        try:
            return error.code, json.loads(text)
        except ValueError:
            return error.code, {"errorDescription": text}
    except OSError as error:
        return 0, {"errorDescription": str(error)}


def must(result, what, ok=(200, 201, 204)):
    status_code, body = result
    if status_code not in ok:
        fail(f"{what}: HTTP {status_code} {json.dumps(body)}")
    return body


def rupees(amount_units):
    return f"Rs {amount_units / 100:,.2f}"


class Merchant:
    """The demo merchant: a dashboard login (JWT) for the account, an API key for orders and payments."""

    def __init__(self):
        self.token = None
        self.key = None
        self.profile = None

    def login(self):
        status_code, body = http("POST", "/v1/auth/login", {"email": DEMO_EMAIL, "password": DEMO_PASSWORD})
        if status_code == 401:
            must(http("POST", "/v1/auth/signup", {
                "name": "Demo Owner", "email": DEMO_EMAIL, "password": DEMO_PASSWORD,
                "businessName": "Chai & Co. Online", "businessType": "PRIVATE_LIMITED"}), "signup")
            status_code, body = http("POST", "/v1/auth/login", {"email": DEMO_EMAIL, "password": DEMO_PASSWORD})
            if status_code == 401:
                fail(f"{DEMO_EMAIL} is registered with a different password. Set DEMO_PASSWORD, or DEMO_EMAIL "
                     f"to a new address.")
            log(f"   signed up {DEMO_EMAIL}")
        self.token = must((status_code, body), "login")["accessToken"]
        self.profile = must(http("GET", "/v1/merchants/me", token=self.token), "read profile")
        log(f"   logged in as {DEMO_EMAIL} ({self.profile['status']})")

    def activate(self):
        """Profile, payout account and KYC: what makes a merchant ACTIVE and so eligible for settlement."""
        if self.profile["status"] == "ACTIVE":
            return
        if self.profile["status"] == "SUSPENDED":
            log("   the demo merchant is suspended: reactivating it as the operator")
            must(http("POST", f"/v1/admin/merchants/{self.profile['id']}/reactivate",
                      {"reason": "demo seed"}, admin=True), "reactivate")
        must(http("PUT", "/v1/merchants/me", {
            "businessName": "Chai & Co. Online", "businessType": "PRIVATE_LIMITED", "contactNumber": "+91 98765 43210",
            "websiteUrl": "https://chai-and-co.example", "panId": "ABCDE1234F", "gstId": "27ABCDE1234F1Z5"},
                  token=self.token), "update profile")
        must(http("PUT", "/v1/merchants/me/settlement-bank", {
            "accountNumber": "123456789012", "ifsc": "HDFC0001234", "accountHolderName": "Chai and Co Online Pvt Ltd",
            "currentPassword": DEMO_PASSWORD}, token=self.token), "set payout account")
        self.profile = must(http("POST", "/v1/merchants/me/kyc", token=self.token), "submit KYC")
        log(f"   profile, payout account and KYC done: {self.profile['status']}")

    def api_key(self):
        """A key's secret is shown once, so the one this script created is kept in demo/.run (gitignored)."""
        saved = read_json("api-key.json", {})
        if saved.get("email") == DEMO_EMAIL:
            key = (saved["keyId"], saved["keySecret"])
            if http("GET", "/v1/orders?size=1", key=key)[0] == 200:
                self.key = key
                return
        created = must(http("POST", "/v1/merchants/api-keys", {"environment": "TEST"}, token=self.token),
                       "create API key")
        write_json("api-key.json", {"email": DEMO_EMAIL, "keyId": created["keyId"], "keySecret": created["keySecret"]})
        self.key = (created["keyId"], created["keySecret"])
        log(f"   created API key {created['keyId']} (secret saved in demo/.run/api-key.json)")

    def webhooks(self):
        have = {w["targetUrl"] for w in must(http("GET", "/v1/merchants/webhooks", token=self.token), "list webhooks")}
        for url, events in ((WEBHOOK_OK, "ALL"), (WEBHOOK_FAILING, "ORDER_CREATED")):
            if url not in have:
                must(http("POST", "/v1/merchants/webhooks", {"targetUrl": url, "eventTypes": events},
                          token=self.token), "create webhook")
                log(f"   registered webhook {url} ({events})")

    def team(self):
        users = must(http("GET", "/v1/merchants/users", token=self.token), "list users")
        if any(u["email"] == TEAM_EMAIL for u in users):
            return
        status_code, body = http("POST", "/v1/merchants/users", {"email": TEAM_EMAIL, "password": DEMO_PASSWORD,
                                                                 "role": "TEAM"}, token=self.token)
        if status_code == 201:
            log(f"   added {TEAM_EMAIL} as a read-only TEAM member (same password)")
        else:
            log(f"   could not add {TEAM_EMAIL}: {body.get('errorCode')}")

    # ---- orders and payments, with the API key, as the merchant's backend

    def order(self, amount_units, label, batch, idempotency_key=None, customer="buyer@example.com"):
        return must(http("POST", "/v1/orders", {
            "amount": {"amountUnits": amount_units, "currency": "INR"},
            "receipt": f"demo-{batch}-{label}",
            "notes": {"scenario": label},
            "customer": {"name": "Demo Buyer", "email": customer, "phone": "+919876543210"},
        }, key=self.key, idempotency_key=idempotency_key), f"create order {label}")

    def tokenize(self, pan):
        return must(http("POST", "/v1/vault/tokenize", {
            "pan": pan, "cvv": "123", "expiryMonth": 12, "expiryYear": time.localtime().tm_year + 3,
            "cardHolderName": "Demo Buyer"}, key=self.key), "tokenize a card")["token"]

    def pay(self, order, method, details):
        return must(http("POST", "/v1/payments", {"orderId": order["id"], "method": method, "methodDetails": details},
                         key=self.key, idempotency_key=str(uuid.uuid4())), f"pay order {order['receipt']}")

    def payment(self, payment_id):
        return must(http("GET", f"/v1/payments/{payment_id}", key=self.key), "read payment")

    def settle(self, payment_ids, timeout=40):
        """Waits for the simulated bank: every payment CAPTURED or FAILED, or AUTHORIZED with a refused capture."""
        end = time.monotonic() + timeout
        payments = {}
        while time.monotonic() < end:
            payments = {pid: self.payment(pid) for pid in payment_ids}
            if all(p["status"] in ("CAPTURED", "FAILED", "AUTH_EXPIRED") or
                   (p["status"] == "AUTHORIZED" and p.get("errorCode")) for p in payments.values()):
                break
            time.sleep(2)
        return payments


def seed():
    step("Seeding the demo merchant")
    merchant = Merchant()
    merchant.login()
    merchant.activate()
    merchant.api_key()
    merchant.webhooks()
    merchant.team()

    batch = uuid.uuid4().hex[:6]
    good_card = merchant.tokenize("4111111111111111")
    declined_card = merchant.tokenize("4000000000000002")          # the mock acquirer's "declined" number

    step("A day of orders (the bank is simulated and answers within seconds)")
    scenarios = [                 # label, amount in paise, method, methodDetails
        ("upi", 49900, "UPI", {"vpa": "buyer@okaxis"}),
        ("card", 129900, "CARD", {"token": good_card}),
        ("netbanking", 250000, "NETBANKING", {"bank": "HDFC"}),
        ("wallet", 34900, "WALLET", {"wallet": "PAYTM"}),
        ("upi-2", 89900, "UPI", {"vpa": "another@okhdfc"}),
        ("upi-rejected", 19900, "UPI", {"vpa": "fail@okaxis"}),
        ("capture-refused", 75000, "UPI", {"vpa": "capturefail@okaxis"}),
    ]
    payments = {}
    for label, amount, method, details in scenarios:
        payments[label] = merchant.pay(merchant.order(amount, label, batch), method, details)

    # A declined card, then the customer pays the same order another way: two attempts on one order.
    retried = merchant.order(59900, "card-declined-then-upi", batch)
    payments["card-declined"] = merchant.pay(retried, "CARD", {"token": declined_card})
    payments["retry-by-upi"] = merchant.pay(retried, "UPI", {"vpa": "buyer@okaxis"})

    # The same request sent twice with one idempotency key is one order, not two.
    key = f"demo-{batch}-idempotent"
    first = merchant.order(15000, "idempotent", batch, idempotency_key=key)
    second = merchant.order(15000, "idempotent", batch, idempotency_key=key)
    merchant.order(99900, "unpaid", batch)
    cancelled = merchant.order(45000, "cancelled", batch)
    must(http("POST", f"/v1/orders/{cancelled['id']}/cancel", key=merchant.key), "cancel order")

    final = merchant.settle([p["id"] for p in payments.values()])
    labels = {p["id"]: label for label, p in payments.items()}
    for payment_id, p in final.items():
        note = f"  {p['errorCode']}" if p.get("errorCode") else ""
        log(f"   {labels[payment_id]:<16} {p['method']:<10} {rupees(p['amount']['amountUnits']):>12}  {p['status']}{note}")
    log(f"   idempotency      the same key twice returned {'one order' if first['id'] == second['id'] else 'TWO ORDERS'}")

    # The refused capture left its payment AUTHORIZED: capture it by hand, as the merchant would.
    refused = final[payments["capture-refused"]["id"]]
    if refused["status"] == "AUTHORIZED":
        captured = must(http("POST", f"/v1/payments/{refused['id']}/capture", key=merchant.key), "capture")
        final[refused["id"]] = captured
        log(f"   capture-refused  captured on retry: {captured['status']}")

    step("A refund, then the operator runs a settlement")
    captured = [p for p in final.values() if p["status"] == "CAPTURED"]
    if captured:
        target = max(captured, key=lambda p: p["amount"]["amountUnits"])
        refund = must(http("POST", f"/v1/payments/{target['id']}/refunds",
                           {"amountUnits": 10000, "notes": {"reason": "one item returned"}},
                           key=merchant.key, idempotency_key=str(uuid.uuid4())), "refund")
        end = time.monotonic() + 30
        while refund["status"] == "PENDING" and time.monotonic() < end:
            time.sleep(2)
            refund = must(http("GET", f"/v1/refunds/{refund['id']}", key=merchant.key), "read refund")
        log(f"   refunded {rupees(10000)} of {rupees(target['amount']['amountUnits'])}: {refund['status']}")

    run_result = must(http("POST", "/v1/admin/settlements/run", {"merchantId": merchant.profile["id"]}, admin=True),
                      "run settlement", ok=(200, 409))
    if "settlementsCreated" in run_result:
        log(f"   settlement run started {run_result['settlementsCreated']} payout(s)")
        end = time.monotonic() + 45
        while time.monotonic() < end:
            latest = must(http("GET", "/v1/settlements?size=1", key=merchant.key), "list settlements")["items"]
            if latest and latest[0]["status"] in ("PROCESSED", "FAILED"):
                s = latest[0]
                log(f"   payout {s['status']}: gross {rupees(s['grossAmount']['amountUnits'])}"
                    f" - refunds {rupees(s['refundAmount']['amountUnits'])}"
                    f" - fee {rupees(s['feeAmount']['amountUnits'])} - GST {rupees(s['gstAmount']['amountUnits'])}"
                    f" = net {rupees(s['netAmount']['amountUnits'])}")
                break
            time.sleep(3)
    else:
        log("   another settlement run is in progress; skipped")

    # Settled payments can't be refunded, so leave a few fresh captured ones to refund from the dashboard.
    step("A few more payments, left unsettled to try a refund on")
    later = [merchant.pay(merchant.order(amount, label, batch), method, details) for label, amount, method, details in (
        ("later-upi", 64900, "UPI", {"vpa": "evening@okicici"}),
        ("later-card", 219900, "CARD", {"token": good_card}),
        ("later-wallet", 12900, "WALLET", {"wallet": "PHONEPE"}))]
    for p in merchant.settle([p["id"] for p in later]).values():
        log(f"   {p['method']:<10} {rupees(p['amount']['amountUnits']):>12}  {p['status']}")
    return merchant


def summary():
    log(f"""
PayFlo is running.

  Dashboard     {DASHBOARD}
                log in as {DEMO_EMAIL} / {DEMO_PASSWORD}   (owner)
                       or {TEAM_EMAIL} / the same password  (read-only TEAM)
                operator console: the admin key is the gateway's ADMIN_API_KEY (the development default is in docs/api/admin.md)
  API docs      {DASHBOARD}/docs.html   (Swagger UI over docs/api/openapi.yaml)
  Gateway       {GATEWAY}   (what the dashboard and your own curl calls talk to)
  API key       demo/.run/api-key.json
  Logs          demo/logs/

  python demo/demo.py seed     another batch of orders and payments
  python demo/demo.py stop     stop the services
""")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", nargs="?", default="up", choices=["up", "seed", "stop", "status"])
    parser.add_argument("--build", action="store_true", help="rebuild the jars before starting")
    parser.add_argument("--no-seed", action="store_true", help="start everything but create no data")
    parser.add_argument("--port-offset", type=int, help="add this to every port (services and dashboard), for a "
                                                        "machine where the usual ones are taken; remembered until changed")
    args = parser.parse_args()

    # The offset is remembered, so 'seed', 'status' and 'stop' find the stack that 'up' started.
    settings = read_json("settings.json", {})
    if args.port_offset is not None:
        settings["portOffset"] = args.port_offset
        write_json("settings.json", settings)
    use_ports(settings.get("portOffset", 0))

    if args.command == "stop":
        return stop()
    if args.command == "status":
        return status()
    if args.command == "seed":
        if not answers(SERVICES["api-gateway-service"][1]):
            fail("the stack isn't running: run 'python demo/demo.py' first")
        seed()
        return summary()

    start_infrastructure()
    build(args.build)
    start_services()
    start_dashboard()
    if not args.no_seed:
        seed()
    summary()


if __name__ == "__main__":
    main()
