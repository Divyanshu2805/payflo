"""Serves the PayFlo dashboard and forwards its API calls to the gateway.

    python dashboard/serve.py                                  # http://localhost:5173 -> gateway on :8080
    python dashboard/serve.py --port 5173 --gateway http://localhost:8080

The dashboard is a static page (site/): no build step, no dependencies. A browser may only call the origin a page
came from, so this server answers the page's /v1/** calls by passing them to the gateway unchanged. That keeps the
gateway exactly as it is: no CORS, no static files, no route that skips authentication. The dashboard is one more
API client, with a login like any other.

It listens on the loopback interface only and stores nothing: not a token, not a request body. Standard library
only, Python 3.10+.
"""
import argparse
import http.client
import json
import mimetypes
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

SITE = Path(__file__).resolve().parent / "site"
OPENAPI = Path(__file__).resolve().parents[2] / "docs" / "api" / "openapi.yaml"

# Only what the gateway routes publicly. /internal/** is never forwarded, here or by the gateway.
PROXIED_PREFIXES = ("/v1/", "/webhook/")
# A browser sends many headers; the API needs these and nothing else. Identity headers (X-Merchant-Id and the rest)
# are not in the list, and the gateway drops them anyway.
REQUEST_HEADERS = ("Authorization", "Content-Type", "Accept", "X-Idempotency-Key", "X-Admin-Key")
RESPONSE_HEADERS = ("Content-Type", "Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining", "Allow")
MAX_BODY_BYTES = 1024 * 1024          # the gateway's own limit

SWAGGER_CDN = "https://cdn.jsdelivr.net"
APP_CSP = ("default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; "
           "base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
# Swagger UI is loaded from a CDN and styles itself inline, so the docs page alone gets a wider policy.
DOCS_CSP = (f"default-src 'none'; script-src 'self' {SWAGGER_CDN}; style-src 'self' 'unsafe-inline' {SWAGGER_CDN}; "
            f"img-src 'self' data:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "payflo-dashboard"
    gateway = None                      # (scheme, host, port), set in main()

    def do_GET(self):
        self.route()

    def do_POST(self):
        self.route()

    def do_PUT(self):
        self.route()

    def do_PATCH(self):
        self.route()

    def do_DELETE(self):
        self.route()

    def route(self):
        path = urlsplit(self.path).path
        if path.startswith(PROXIED_PREFIXES):
            return self.proxy()
        if self.command != "GET":
            return self.send_json(405, "METHOD_NOT_ALLOWED", "Only the API paths accept this method")
        if path == "/healthz":
            return self.send_bytes(200, b"payflo-dashboard", "text/plain")
        if path == "/openapi.yaml":
            return self.send_file(OPENAPI, "application/yaml", APP_CSP)
        self.static(path)

    # ---- the page

    def static(self, path):
        relative = "index.html" if path == "/" else path.lstrip("/")
        target = (SITE / relative).resolve()
        if SITE not in target.parents or not target.is_file():          # also refuses ../ out of site/
            return self.send_json(404, "NOT_FOUND", "No such page")
        content_type = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        if target.suffix == ".js":
            content_type = "text/javascript"          # Windows' registry sometimes says text/plain, which browsers refuse
        self.send_file(target, content_type, DOCS_CSP if target.name == "docs.html" else APP_CSP)

    def send_file(self, target, content_type, csp):
        try:
            body = target.read_bytes()
        except OSError:
            return self.send_json(404, "NOT_FOUND", "No such page")
        if content_type.startswith("text/") or content_type in ("application/yaml", "application/json"):
            content_type += "; charset=utf-8"
        self.send_bytes(200, body, content_type, {"Content-Security-Policy": csp})

    # ---- the API

    def proxy(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY_BYTES:
            return self.send_json(413, "REQUEST_TOO_LARGE", "The request body is too large")
        body = self.rfile.read(length) if length else None
        headers = {name: self.headers[name] for name in REQUEST_HEADERS if self.headers.get(name)}
        scheme, host, port = self.gateway
        connection_type = http.client.HTTPSConnection if scheme == "https" else http.client.HTTPConnection
        connection = connection_type(host, port, timeout=60)
        try:
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            passed = {name: response.getheader(name) for name in RESPONSE_HEADERS if response.getheader(name)}
            status = response.status
        except OSError:
            return self.send_json(502, "GATEWAY_UNREACHABLE",
                                  f"The dashboard could not reach the API gateway at {scheme}://{host}:{port}. Is the stack running?")
        finally:
            connection.close()
        self.send_bytes(status, payload, None, passed)

    # ---- responses

    def send_json(self, status, code, description):
        body = json.dumps({"errorCode": code, "errorDescription": description}).encode()
        self.send_bytes(status, body, "application/json")

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
            if self.command != "HEAD":
                self.wfile.write(body)
        except OSError:
            self.close_connection = True          # the browser went away (a reload, a closed tab): nothing to tell it

    def log_message(self, format, *args):
        pass          # see log_request: one line per API call, never a header or a body

    def log_request(self, code="-", size="-"):
        path = urlsplit(self.path).path
        if path.startswith(PROXIED_PREFIXES):
            print(time.strftime("%H:%M:%S"), self.command, path, code, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=5173)
    parser.add_argument("--gateway", default="http://localhost:8080", help="the API gateway's URL")
    args = parser.parse_args()

    gateway = urlsplit(args.gateway)
    if gateway.scheme not in ("http", "https") or not gateway.hostname:
        sys.exit(f"--gateway must be an http(s) URL, not {args.gateway}")
    # "localhost" is tried as ::1 first, and the gateway listens on 127.0.0.1 only: on Windows every call then waits
    # two seconds for the IPv6 attempt to give up. Go straight to the address it is on.
    host = "127.0.0.1" if gateway.hostname == "localhost" else gateway.hostname
    Handler.gateway = (gateway.scheme, host, gateway.port or (443 if gateway.scheme == "https" else 80))

    # Loopback only, like every other PayFlo process: this forwards whatever credential the browser sends.
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    print(f"PayFlo dashboard on http://localhost:{args.port} -> {args.gateway}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
