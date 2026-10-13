"""A sample merchant's shop, taking payments through PayFlo the way a real integration does.

    python storefront/serve.py                                  # http://localhost:5174 -> gateway on :8080
    python storefront/serve.py --port 5174 --gateway http://localhost:8080

This is the merchant's side of PayFlo, not part of it: a shop, a checkout that takes the shopper's contact and
delivery details and then opens PayFlo's payment window, a confirmation page, and this server, which is the
merchant's backend. It holds the merchant's API key (the browser never sees it) and calls the public API through
the gateway: an order when the shopper pays, a card token from the vault, a payment, and then the payment's status
until the bank answers. It also receives PayFlo's signed webhook and checks the signature before believing it.

The API key is read from demo/.run/api-key.json, which demo/demo.py writes (or from PAYFLO_KEY_ID and
PAYFLO_KEY_SECRET); the webhook's signing secret from demo/.run/storefront.json. It listens on the loopback
interface only, keeps its checkout sessions in memory, and never logs or keeps a card number: the number goes
straight to the vault and only the token comes back. Standard library only, Python 3.10+.
"""
import argparse
import base64
import hashlib
import hmac
import http.client
import json
import mimetypes
import os
import re
import secrets
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

SITE = Path(__file__).resolve().parent / "site"
RUN_DIR = Path(__file__).resolve().parents[1] / "demo" / ".run"

MERCHANT = "Chai & Co. Online"
CURRENCY = "INR"
MAX_BODY_BYTES = 64 * 1024
MAX_QUANTITY = 10
FREE_SHIPPING_FROM = 49900          # orders from INR 499 ship free; below that, shipping is INR 49
SHIPPING_UNITS = 4900
WEBHOOK_TOLERANCE_SECONDS = 300
CSP = ("default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; "
       "base-uri 'none'; form-action 'none'; frame-ancestors 'none'")

# The shop's prices live here, on the server: the browser sends product ids and quantities, never an amount.
CATALOG = [
    {"id": "masala-chai", "name": "Masala Chai", "category": "black", "detail": "Assam CTC with cardamom, ginger and clove",
     "size": "250 g", "priceUnits": 44900, "badge": "Bestseller", "rating": 4.8, "reviews": 1284,
     "notes": ["Spiced", "Bold", "Warming"], "brew": "2 tsp simmered in milk and water, 4 min",
     "description": "Our house blend: strong Assam CTC ground fresh with green cardamom, dried ginger, clove and a little "
                    "black pepper. Made for boiling the Indian way, with milk and sugar.",
     "shape": "tin", "tone": "spice"},
    {"id": "assam-breakfast", "name": "Assam Breakfast", "category": "black", "detail": "Malty second-flush whole leaf",
     "size": "250 g", "priceUnits": 39900, "rating": 4.7, "reviews": 862,
     "notes": ["Malty", "Brisk", "Honeyed"], "brew": "2.5 g at 95 °C, 4 min",
     "description": "Golden-tipped second flush from the Brahmaputra valley. Full and malty on its own, and strong enough "
                    "to take a splash of milk.",
     "shape": "tin", "tone": "amber"},
    {"id": "darjeeling-first-flush", "name": "Darjeeling First Flush", "category": "black", "detail": "Spring harvest, light and floral",
     "size": "100 g", "priceUnits": 89900, "badge": "New season", "rating": 4.9, "reviews": 318,
     "notes": ["Floral", "Muscatel", "Bright"], "brew": "2 g at 85 °C, 3 min",
     "description": "The first leaves of spring from high-altitude gardens, picked in March. Pale in the cup, with a "
                    "fresh, flowery aroma. Best without milk.",
     "shape": "tin", "tone": "leaf"},
    {"id": "kashmiri-kahwa", "name": "Kashmiri Kahwa", "category": "green", "detail": "Green tea with saffron, almond and cinnamon",
     "size": "150 g", "priceUnits": 64900, "rating": 4.8, "reviews": 540,
     "notes": ["Saffron", "Nutty", "Sweet"], "brew": "2 g at 80 °C, 3 min",
     "description": "The valley's festive green tea, blended with Kashmiri saffron, cinnamon, cardamom and slivered almonds. "
                    "Sweeten with a little honey.",
     "shape": "tin", "tone": "saffron"},
    {"id": "tulsi-green", "name": "Tulsi Green", "category": "green", "detail": "Holy basil and green tea",
     "size": "25 pyramid bags", "priceUnits": 34900, "rating": 4.6, "reviews": 701,
     "notes": ["Herbal", "Fresh", "Clean"], "brew": "1 bag at 80 °C, 2 min",
     "description": "Three kinds of tulsi with a light Nilgiri green tea, in biodegradable pyramid bags. An easy everyday cup.",
     "shape": "tin", "tone": "mint"},
    {"id": "lemongrass-ginger", "name": "Lemongrass Ginger", "category": "green", "detail": "Caffeine-free herbal infusion",
     "size": "100 g", "priceUnits": 29900, "badge": "Caffeine free", "rating": 4.5, "reviews": 226,
     "notes": ["Zesty", "Warm", "Soothing"], "brew": "2 g at 100 °C, 5 min",
     "description": "Kerala lemongrass with dried ginger and a little lemon peel. No tea leaves, so nothing to keep you up.",
     "shape": "pouch", "tone": "lemon"},
    {"id": "kulhad-set", "name": "Kulhad Cups", "category": "teaware", "detail": "Hand-thrown terracotta",
     "size": "Set of 6", "priceUnits": 59900, "rating": 4.7, "reviews": 412,
     "notes": ["Handmade", "Unglazed", "120 ml"], "brew": "Rinse in cold water before the first use",
     "description": "Unglazed clay cups thrown by potters in Khurja, the way roadside chai is served. Each one is a little "
                    "different.",
     "shape": "cup", "tone": "clay"},
    {"id": "chai-gift-box", "name": "The Chai Gift Box", "category": "teaware", "detail": "Masala Chai, Assam Breakfast and two kulhads",
     "size": "Gift box", "priceUnits": 99900, "mrpUnits": 104700, "badge": "Gift", "rating": 4.9, "reviews": 189,
     "notes": ["2 teas", "2 kulhads", "Gift note"], "brew": "Ships in a gift box with a handwritten note",
     "description": "Our two best-loved teas with a pair of kulhads, packed in a keepsake box. A good first chai kit.",
     "shape": "box", "tone": "gift"},
]
PRODUCTS = {product["id"]: product for product in CATALOG}

# What the shopper is told. The API's errorCode says why; these say it the way a checkout would.
DECLINE_MESSAGES = {
    "CARD_DECLINED": "Your card was declined. Try a different card or another way to pay.",
    "CARD_EXPIRED": "Your card has expired. Try a different card.",
    "CARD_TOKEN_INVALID": "That card could not be used. Enter it again.",
    "UPI_REJECTED": "The UPI request was rejected. Check the address or pay another way.",
    "BANK_REJECTED": "Your bank rejected the payment. Try another bank or another way to pay.",
    "WALLET_REJECTED": "The wallet rejected the payment. Try another way to pay.",
    "SIM_BANK_ERROR_CODE": "Your bank declined the payment. Try again, or pay another way.",
    "CAPTURE_DECLINED": "The bank approved the payment but would not complete it. You have not been charged; try again.",
    "PAYMENT_GATEWAY_ROUTER_UNREACHABLE": "The payment could not be started and nothing was charged. Try again.",
}
BANK_NAMES = {"HDFC": "HDFC Bank", "ICICI": "ICICI Bank", "SBI": "State Bank of India", "AXIS": "Axis Bank",
              "KOTAK": "Kotak Mahindra Bank", "YES": "Yes Bank", "PNB": "Punjab National Bank", "BOB": "Bank of Baroda",
              "IDFC": "IDFC FIRST Bank", "INDUSIND": "IndusInd Bank"}
WALLET_NAMES = {"PAYTM": "Paytm", "PHONEPE": "PhonePe", "AMAZONPAY": "Amazon Pay", "MOBIKWIK": "MobiKwik", "FREECHARGE": "Freecharge"}
STATES = ["Andaman and Nicobar Islands", "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chandigarh", "Chhattisgarh",
          "Dadra and Nagar Haveli and Daman and Diu", "Delhi", "Goa", "Gujarat", "Haryana", "Himachal Pradesh", "Jammu and Kashmir",
          "Jharkhand", "Karnataka", "Kerala", "Ladakh", "Lakshadweep", "Madhya Pradesh", "Maharashtra", "Manipur", "Meghalaya",
          "Mizoram", "Nagaland", "Odisha", "Puducherry", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana", "Tripura",
          "Uttar Pradesh", "Uttarakhand", "West Bengal"]
EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
PIN_CODE = re.compile(r"^[1-9][0-9]{5}$")
IN_FLIGHT = ("CREATED", "AUTHORIZING", "CAPTURING")


class Refused(Exception):
    """Something the shopper can be told: an HTTP status, a code and a sentence."""

    def __init__(self, status, code, message, field=None):
        super().__init__(message)
        self.status, self.code, self.message, self.field = status, code, message, field


class PayFlo:
    """The merchant's API client: every call goes through the gateway with the API key, as HTTP Basic."""

    def __init__(self, gateway, key_id, key_secret):
        self.scheme, self.host, self.port = gateway
        self.authorization = "Basic " + base64.b64encode(f"{key_id}:{key_secret}".encode()).decode()

    def call(self, method, path, body=None, idempotency_key=None):
        headers = {"Authorization": self.authorization, "Accept": "application/json"}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        if idempotency_key:
            headers["X-Idempotency-Key"] = idempotency_key
        connection_type = http.client.HTTPSConnection if self.scheme == "https" else http.client.HTTPConnection
        connection = connection_type(self.host, self.port, timeout=30)
        try:
            connection.request(method, path, body=data, headers=headers)
            response = connection.getresponse()
            text = response.read().decode(errors="replace")
            status = response.status
        except OSError:
            raise Refused(502, "PAYFLO_UNREACHABLE", "The payment service can't be reached right now. Try again in a moment.")
        finally:
            connection.close()
        try:
            return status, (json.loads(text) if text else {})
        except ValueError:
            return status, {}


class Shop:
    """Checkout sessions, in memory: what is in the basket, the PayFlo order and payment behind it, and the webhook."""

    def __init__(self, payflo, webhook_secret):
        self.payflo = payflo
        self.webhook_secret = webhook_secret          # a function: the secret is read when a delivery arrives
        self.sessions = {}
        self.by_payment = {}
        self.seen_events = set()
        self.lock = threading.Lock()

    def create(self, items):
        lines, total = [], 0
        if not isinstance(items, list) or not items:
            raise Refused(400, "BASKET_EMPTY", "Your basket is empty.")
        for item in items:
            product = PRODUCTS.get(item.get("id")) if isinstance(item, dict) else None
            quantity = item.get("quantity") if isinstance(item, dict) else None
            if product is None or not isinstance(quantity, int) or isinstance(quantity, bool) or not 1 <= quantity <= MAX_QUANTITY:
                raise Refused(400, "BASKET_INVALID", "Something in your basket is no longer available.")
            lines.append({"id": product["id"], "name": product["name"], "size": product["size"], "shape": product["shape"],
                          "tone": product["tone"], "quantity": quantity, "priceUnits": product["priceUnits"],
                          "totalUnits": product["priceUnits"] * quantity})
            total += product["priceUnits"] * quantity
        shipping = 0 if total >= FREE_SHIPPING_FROM else SHIPPING_UNITS
        session = {"id": "cs_test_" + secrets.token_urlsafe(24), "receipt": "CC-" + secrets.token_hex(4).upper(),
                   "items": lines, "subtotalUnits": total, "shippingUnits": shipping, "amountUnits": total + shipping,
                   "order": None, "payment": None, "paid_with": None, "contact": None, "address": None,
                   "webhook": None, "capture_retried": False, "lock": threading.Lock()}
        with self.lock:
            self.sessions[session["id"]] = session
        return session

    def find(self, session_id):
        with self.lock:
            session = self.sessions.get(session_id)
        if session is None:
            raise Refused(404, "SESSION_NOT_FOUND", "This checkout has expired. Go back to the shop and start again.")
        return session

    # ---- the shopper's details

    def save_details(self, session, body):
        """Contact and delivery details, checked here as well as in the page. They can change until a payment starts."""
        def text(value, limit):
            value = " ".join(str(value or "").split())
            return value if len(value) <= limit else None

        address = body.get("address") if isinstance(body.get("address"), dict) else {}
        email, name = text(body.get("email"), 200), text(body.get("name"), 100)
        phone = "".join(ch for ch in str(body.get("phone") or "")[:30] if ch.isdigit())
        if len(phone) == 12 and phone.startswith("91"):
            phone = phone[2:]
        line1, line2 = text(address.get("line1"), 200), text(address.get("line2"), 200)
        city, state, pin = text(address.get("city"), 80), text(address.get("state"), 80), text(address.get("pin"), 6)
        checks = [
            (email and EMAIL.match(email), "email", "Enter a valid email address."),
            (len(phone) == 10 and phone[0] in "6789", "phone", "Enter a 10-digit mobile number."),
            (name and len(name) >= 2, "name", "Enter your full name."),
            (line1, "line1", "Enter your address."),
            (line2 is not None, "line2", "Keep this line under 200 characters."),
            (city, "city", "Enter your city."),
            (state in STATES, "state", "Choose your state."),
            (pin and PIN_CODE.match(pin), "pin", "Enter a 6-digit PIN code."),
        ]
        for passed, field, message in checks:
            if not passed:
                raise Refused(400, "DETAILS_INVALID", message, field)
        with session["lock"]:
            if self.locked(session):
                raise Refused(409, "PAYMENT_STARTED", "This order is already being paid.")
            session["contact"] = {"email": email, "phone": phone, "name": name}
            session["address"] = {"line1": line1, "line2": line2 or None, "city": city, "state": state, "pin": pin}

    def locked(self, session):
        payment = session["payment"]
        return bool(payment and (payment["status"] in IN_FLIGHT or self.is_paid(payment)))

    # ---- paying

    def pay(self, session, body):
        with session["lock"]:          # one attempt at a time for a checkout, however many times Pay is pressed
            if self.locked(session):
                return
            contact = session["contact"]
            if contact is None:
                raise Refused(400, "DETAILS_MISSING", "Add your contact and delivery details first.")
            method = body.get("method")
            details, paid_with = self.method_details(method, body)

            if session["order"] is None:
                # The session id is the idempotency key: a retry of this request can never make a second order.
                status, order = self.payflo.call("POST", "/v1/orders", {
                    "amount": {"amountUnits": session["amountUnits"], "currency": CURRENCY},
                    "receipt": session["receipt"],
                    "notes": {"source": "storefront", "items": ", ".join(f"{line['quantity']} x {line['name']}" for line in session["items"]),
                              "shippingUnits": session["shippingUnits"]},
                    "customer": {"email": contact["email"], "name": contact["name"], "phone": "+91" + contact["phone"]},
                }, idempotency_key=session["id"])
                if status != 201:
                    raise self.refusal(status, order)
                session["order"] = order

            if method == "CARD":
                details, paid_with = self.tokenize(body.get("card") or {})
            status, payment = self.payflo.call("POST", "/v1/payments", {
                "orderId": session["order"]["id"], "method": method, "methodDetails": details,
            }, idempotency_key=str(uuid.uuid4()))
            if status != 201:
                raise self.refusal(status, payment)
            session["payment"], session["paid_with"], session["capture_retried"] = payment, paid_with, False
            with self.lock:
                self.by_payment[payment["id"]] = session

    def method_details(self, method, body):
        if method == "CARD":
            return None, None
        field, label, names = {"UPI": ("vpa", "UPI", {}), "NETBANKING": ("bank", "Net banking", BANK_NAMES),
                               "WALLET": ("wallet", "Wallet", WALLET_NAMES)}.get(method, (None, None, None))
        if field is None:
            raise Refused(400, "METHOD_INVALID", "Choose a way to pay.")
        value = str(body.get(field) or "").strip()
        if not value or len(value) > 200 or (method == "UPI" and "@" not in value):
            raise Refused(400, "DETAILS_INVALID", "Enter a UPI ID like name@bank." if method == "UPI" else "Choose one to continue.", field)
        return {field: value}, f"{label} · {names.get(value, value)}"

    def tokenize(self, card):
        """The card goes to the vault and a token comes back. Nothing here keeps or logs the number."""
        number = "".join(ch for ch in str(card.get("number") or "") if ch.isdigit())
        month, _, year = str(card.get("expiry") or "").replace(" ", "").partition("/")
        if not (month.isdigit() and year.isdigit() and len(year) in (2, 4)):
            raise Refused(400, "CARD_INVALID", "Enter the expiry as MM / YY.", "expiry")
        status, token = self.payflo.call("POST", "/v1/vault/tokenize", {
            "pan": number, "cvv": str(card.get("cvc") or ""), "expiryMonth": int(month),
            "expiryYear": int(year) + (2000 if len(year) == 2 else 0), "cardHolderName": str(card.get("name") or "").strip()})
        if status == 400:
            names = {"pan": ("number", "Your card number is invalid."), "cvv": ("cvc", "Your card's security code is invalid."),
                     "expiryMonth": ("expiry", "Your card's expiry date is invalid."), "expiryYear": ("expiry", "Your card has expired."),
                     "cardHolderName": ("name", "Enter the name on the card.")}
            for error in token.get("fieldErrors") or []:
                if error.get("field") in names:
                    field, message = names[error["field"]]
                    raise Refused(400, "CARD_INVALID", message, field)
            raise Refused(400, "CARD_INVALID", "Check your card details and try again.", "number")
        if status != 201:
            raise self.refusal(status, token)
        return {"token": token["token"]}, f"{str(token.get('brand') or 'Card').title()} ending {token.get('lastFour')}"

    @staticmethod
    def refusal(status, body):
        code = body.get("errorCode") or f"HTTP_{status}"
        if status == 429:
            return Refused(429, code, "Too many attempts. Wait a minute and try again.")
        if code in ("ORDER_NOT_PAYABLE", "ORDER_EXPIRED", "ORDER_CARD_ATTEMPTS_EXCEEDED"):
            return Refused(409, code, "This checkout can't be paid any more. Go back to the shop and start again.")
        if code == "ORDER_PAYMENT_IN_PROGRESS":
            return Refused(409, code, "A payment for this order is already being processed.")
        return Refused(502, code, "The payment could not be started. Nothing was charged; try again.")

    @staticmethod
    def is_paid(payment):
        return payment["status"] in ("CAPTURED", "PARTIALLY_REFUNDED", "REFUNDED", "SETTLED")

    def refresh(self, session):
        """Asks PayFlo how the payment stands. A capture the bank refused is retried once, as a merchant would."""
        with session["lock"]:
            payment = session["payment"]
            if payment is None or not (payment["status"] in IN_FLIGHT or payment["status"] == "AUTHORIZED"):
                return
            status, latest = self.payflo.call("GET", f"/v1/payments/{payment['id']}")
            if status != 200:
                return
            if latest["status"] == "AUTHORIZED" and latest.get("errorCode") and not session["capture_retried"]:
                session["capture_retried"] = True
                status, captured = self.payflo.call("POST", f"/v1/payments/{payment['id']}/capture")
                if status == 200:
                    latest = captured
            session["payment"] = latest

    def view(self, session):
        """What the browser may know about a checkout: no key, no token, nothing about any other session."""
        payment = session["payment"]
        state, message = "open", None
        if payment:
            if self.is_paid(payment):
                state = "paid"
            elif payment["status"] in IN_FLIGHT or (payment["status"] == "AUTHORIZED" and not payment.get("errorCode")):
                state = "processing"
            else:
                state = "failed"
                message = DECLINE_MESSAGES.get(payment.get("errorCode"), "The payment did not go through. Try again, or pay another way.")
        return {
            "id": session["id"], "merchant": MERCHANT, "receipt": session["receipt"], "items": session["items"],
            "subtotalUnits": session["subtotalUnits"], "shippingUnits": session["shippingUnits"],
            "amountUnits": session["amountUnits"], "currency": CURRENCY, "state": state, "message": message,
            "contact": session["contact"], "address": session["address"], "paidWith": session["paid_with"],
            "orderId": session["order"]["id"] if session["order"] else None,
            "payment": {"id": payment["id"], "status": payment["status"], "method": payment["method"],
                        "errorCode": payment.get("errorCode"), "capturedAt": payment.get("capturedAt")} if payment else None,
            "webhook": session["webhook"], "webhookExpected": bool(self.webhook_secret()),
        }

    # ---- the webhook

    def webhook(self, raw_body, timestamp, signature):
        """PayFlo's signed delivery. Returns the HTTP status to answer with."""
        secret = self.webhook_secret()
        if not secret:
            return 503
        try:
            fresh = abs(time.time() - int(timestamp or "")) <= WEBHOOK_TOLERANCE_SECONDS
        except ValueError:
            fresh = False
        expected = hmac.new(secret.encode(), (timestamp or "").encode() + b"." + raw_body, hashlib.sha256).hexdigest()
        if not fresh or not hmac.compare_digest(expected, signature or ""):
            return 401
        try:
            event = json.loads(raw_body)
            payload = event.get("payload") or {}
        except (ValueError, AttributeError):
            return 400
        with self.lock:
            if event.get("id") in self.seen_events:          # delivery is at-least-once: a repeat is acknowledged, not redone
                return 204
            self.seen_events.add(event.get("id"))
            session = self.by_payment.get(payload.get("paymentId"))
        status = payload.get("paymentStatus") or payload.get("status")
        if session is not None and event.get("event") == "PAYMENT_STATUS_CHANGED" and status == "CAPTURED":
            session["webhook"] = {"eventId": event.get("id"), "event": event.get("event"), "status": status,
                                  "receivedAt": time.strftime("%H:%M:%S")}
        return 204


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "payflo-storefront"
    shop = None

    def do_GET(self):
        path = urlsplit(self.path).path
        try:
            if path == "/healthz":
                return self.send_bytes(200, b"payflo-storefront", "text/plain")
            if path == "/store/config":
                return self.send_json(200, {"merchant": MERCHANT, "currency": CURRENCY, "catalog": CATALOG, "maxQuantity": MAX_QUANTITY,
                                            "freeShippingFrom": FREE_SHIPPING_FROM, "shippingUnits": SHIPPING_UNITS, "states": STATES})
            if path.startswith("/store/checkout/"):
                session = self.shop.find(path.removeprefix("/store/checkout/"))
                self.shop.refresh(session)
                return self.send_json(200, self.shop.view(session))
            self.static(path)
        except Refused as refused:
            self.send_refusal(refused)

    def do_POST(self):
        path = urlsplit(self.path).path
        try:
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY_BYTES:
                raise Refused(413, "REQUEST_TOO_LARGE", "The request is too large.")
            raw = self.rfile.read(length) if length else b""
            if path == "/webhooks/payflo":
                status = self.shop.webhook(raw, self.headers.get("X-PayFlo-Timestamp"), self.headers.get("X-PayFlo-Signature"))
                return self.send_bytes(status, b"", None)
            try:
                body = json.loads(raw or b"{}")
            except ValueError:
                body = None
            if not isinstance(body, dict):
                raise Refused(400, "BODY_INVALID", "The request could not be read.")
            if path == "/store/checkout":
                return self.send_json(201, self.shop.view(self.shop.create(body.get("items"))))
            if path.startswith("/store/checkout/") and path.endswith("/details"):
                session = self.shop.find(path.removeprefix("/store/checkout/").removesuffix("/details"))
                self.shop.save_details(session, body)
                return self.send_json(200, self.shop.view(session))
            if path.startswith("/store/checkout/") and path.endswith("/pay"):
                session = self.shop.find(path.removeprefix("/store/checkout/").removesuffix("/pay"))
                self.shop.pay(session, body)
                return self.send_json(200, self.shop.view(session))
            raise Refused(404, "NOT_FOUND", "No such page")
        except Refused as refused:
            self.send_refusal(refused)

    def static(self, path):
        relative = "index.html" if path == "/" else path.lstrip("/")
        target = (SITE / relative).resolve()
        if SITE not in target.parents or not target.is_file():          # also refuses ../ out of site/
            raise Refused(404, "NOT_FOUND", "No such page")
        content_type = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        if target.suffix == ".js":
            content_type = "text/javascript"          # Windows' registry sometimes says text/plain, which browsers refuse
        if content_type.startswith("text/"):
            content_type += "; charset=utf-8"
        self.send_bytes(200, target.read_bytes(), content_type, {"Content-Security-Policy": CSP})

    def send_refusal(self, refused):
        self.send_json(refused.status, {"errorCode": refused.code, "message": refused.message, "field": refused.field})

    def send_json(self, status, value):
        self.send_bytes(status, json.dumps(value).encode(), "application/json")

    def send_bytes(self, status, body, content_type, headers=None):
        self.send_response(status)
        if content_type:
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        try:
            self.end_headers()
            self.wfile.write(body)
        except OSError:
            self.close_connection = True          # the browser went away: nothing to tell it

    def log_message(self, format, *args):
        pass          # see log_request: one line per call, never a header or a body (a body can hold a card number)

    def log_request(self, code="-", size="-"):
        path = urlsplit(self.path).path
        if path.startswith(("/store/", "/webhooks/")) and self.command == "POST":
            print(time.strftime("%H:%M:%S"), self.command, re.sub(r"cs_test_[\w-]+", "<session>", path), code, flush=True)


def read_json(path):
    try:
        return json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return {}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=5174)
    parser.add_argument("--gateway", default="http://localhost:8080", help="the API gateway's URL")
    parser.add_argument("--key-file", default=str(RUN_DIR / "api-key.json"), help="where demo.py saved the merchant's API key")
    parser.add_argument("--webhook-file", default=str(RUN_DIR / "storefront.json"), help="where demo.py saved the webhook's signing secret")
    args = parser.parse_args()

    gateway = urlsplit(args.gateway)
    if gateway.scheme not in ("http", "https") or not gateway.hostname:
        sys.exit(f"--gateway must be an http(s) URL, not {args.gateway}")
    saved = read_json(args.key_file)
    key_id = os.environ.get("PAYFLO_KEY_ID") or saved.get("keyId")
    key_secret = os.environ.get("PAYFLO_KEY_SECRET") or saved.get("keySecret")
    if not key_id or not key_secret:
        sys.exit(f"No API key: run 'python demo/demo.py' first (it writes {args.key_file}), or set PAYFLO_KEY_ID and PAYFLO_KEY_SECRET.")
    # Read when a delivery arrives, not once here: demo.py may register the webhook after this server has started.
    webhook_secret = lambda: os.environ.get("PAYFLO_WEBHOOK_SECRET") or read_json(args.webhook_file).get("webhookSecret")

    # 127.0.0.1, not localhost: the gateway isn't on ::1, and on Windows each call would wait two seconds to find out.
    host = "127.0.0.1" if gateway.hostname == "localhost" else gateway.hostname
    payflo = PayFlo((gateway.scheme, host, gateway.port or (443 if gateway.scheme == "https" else 80)), key_id, key_secret)
    Handler.shop = Shop(payflo, webhook_secret)

    # Loopback only, like every other PayFlo process.
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    print(f"{MERCHANT} on http://localhost:{args.port} -> {args.gateway}"
          f"{'' if webhook_secret() else ' (no webhook secret yet: confirmations come from the API only)'}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
