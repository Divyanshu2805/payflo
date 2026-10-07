# The Demo and the Dashboard

The quickest way to see PayFlo working: one command starts everything and fills it with a merchant's day, and a small web dashboard shows the result. Both are for showing and exploring the system; neither is part of it.

## One command

From `microservices/`, with Docker running and a JDK 25 on the path:

```bash
python demo/demo.py
```

It needs Python 3.10+ and nothing else (standard library only). Every step is skipped when it is already done, so it is safe to run again:

1. Starts PostgreSQL, Redis and Kafka and creates the four databases. A container that already exists is started as it is; one that doesn't is created from `services.docker-compose.yaml`.
2. Builds the jars if there are none (`--build` forces a rebuild, which a code change needs).
3. Starts discovery, config, the four business services and the gateway, in that order, waiting for each. Their logs go to `demo/logs/`.
4. Starts the dashboard on <http://localhost:5173>.
5. Seeds the demo merchant, through the gateway, the way a merchant's own code would.

When it finishes it prints the dashboard's address, the demo merchant's login and where the API key is.

| Command | Does |
|---|---|
| `python demo/demo.py` | Start what isn't running, seed, serve the dashboard |
| `python demo/demo.py seed` | Add another batch of orders and payments to the running stack |
| `python demo/demo.py status` | What is up |
| `python demo/demo.py stop` | Stop the services and the dashboard **this script started** (the containers are left running) |
| `python demo/demo.py --no-seed` | Start everything, create no data |
| `python demo/demo.py --build` | Rebuild the jars first |
| `python demo/demo.py --port-offset 100` | Move every port by 100, for a machine where the usual ones are taken |

### What the seed creates

| Step | Shows |
|---|---|
| Signup, profile, payout account, KYC | A merchant going from `PENDING_KYC` to `ACTIVE` |
| An API key | Saved in `demo/.run/api-key.json` (gitignored), since the secret is shown only once |
| Two webhook endpoints | One that works (operations-service's test receiver) and one that fails, so retries and `FAILED` deliveries are visible |
| A `TEAM` user | A read-only login |
| Payments by UPI, card, net banking and wallet | The simulated bank authorizing and capturing within seconds, and declining some on its own |
| `fail@okaxis`, a declined card then UPI on the same order | The [test values](../api/mock-acquirer.md), and two attempts on one order |
| `capturefail@okaxis`, then a manual capture | A refused capture left `AUTHORIZED`, captured on retry |
| The same order request twice with one idempotency key | One order |
| An unpaid and a cancelled order | The other order statuses |
| A partial refund | `PARTIALLY_REFUNDED`, and the refund netted out of the payout |
| A settlement run by the operator | Gross − refunds − fee − GST = net, and the payments `SETTLED` |
| A few more payments afterwards | Captured and not yet settled, so a refund can be tried from the dashboard |

The outcome of an ordinary payment is the simulated bank's to decide (it declines about one net-banking payment in five), so the seed reports what happened rather than assuming it.

### Ports already taken

The script refuses to start a service on a port another program holds, and says which. It does not take "something answered on 8761" as proof that PayFlo's discovery-service is running: any Eureka answers there, and registering with another project's registry would quietly break both. Either stop the other program or move PayFlo:

```bash
python demo/demo.py --port-offset 100     # gateway :8180, services :8181-8184, discovery :8861, config :8988, dashboard :5273
```

The offset is remembered (in `demo/.run/settings.json`), so `seed`, `status` and `stop` find the same stack; `--port-offset 0` goes back. The services find discovery and config on their moved ports through `EUREKA_URL` and `CONFIG_SERVER_URL`.

### Development values

The demo merchant's login, and the admin key the seed uses for the settlement run, are development-only values, like every default in `config-repo`. Override them with `DEMO_EMAIL`, `DEMO_PASSWORD` and `ADMIN_API_KEY`. Don't point the script at a shared environment.

## The dashboard

`microservices/dashboard/` is a static page (`site/`: plain HTML, CSS and JavaScript modules, no build step and no dependencies) and a small server:

```bash
python dashboard/serve.py                       # http://localhost:5173 -> the gateway on :8080
python dashboard/serve.py --port 5273 --gateway http://localhost:8180
```

It is one more client of the public API. Everything it shows comes from the same endpoints a merchant's backend calls, and the **API requests** bar along the bottom of the page lists each call it makes, with the status, the time and the credential used.

It is laid out like a payments product's dashboard: the business and its sections in a sidebar, lists with a tab per status and the amount leading each row, and a row opening a drawer from the right with the amount, a timeline of what happened, and the actions that apply. The box in the top bar opens a payment, order or refund from a pasted ID (`/` puts the cursor in it).

The frame is exactly the height of the window and never scrolls: the sidebar and the API requests bar stay where they are, and only the content between them moves.

| Section | What it exercises |
|---|---|
| Home, Reports | The [analytics](../api/analytics.md) endpoints: today's net volume, a seven-day trend with a value at each point, the method breakdown, and a report over any range (with presets for the last 7, 30 and 90 days and 12 months). For a merchant that isn't set up yet, Home also lists what is left: profile, payout account, KYC, an API key, a webhook endpoint, a first payment, each read from the API |
| Orders | Create (with an idempotency key), read, cancel, and **Pay**: a checkout with the mock acquirer's test values one click away, which follows the payment until the bank answers |
| Payments, Refunds | Read, capture a refused capture again, refund in full or in part |
| Settlements | Each payout and how its net amount is reached |
| Webhooks | Endpoints (create, pause, rotate the secret, delete) and deliveries (payload, attempts, replay) |
| API keys | Create, roll the secret with a grace period, revoke; and the test values |
| Account | Profile, payout account, KYC, team users, password |
| Audit log | The merchant's own entries |
| **Operator** (a separate login, with the admin key) | Merchants (suspend, reactivate), a settlement run on demand, the audit log across merchants |

Log in as the demo merchant, as its `TEAM` user to see a read-only login refused by the gateway (`403 ROLE_FORBIDDEN`), or create a new account from the login page.

### How it is put together

| File | Holds |
|---|---|
| `site/app.css` | Every style. Colours are tokens (`--brand`, `--text`, `--good-bg`, ...), defined once for light and again under `[data-theme="dark"]`; a component never names a colour itself |
| `site/js/ui.js` | The shared pieces: tables and paged lists, badges, forms, dialogs and drawers, toasts, the trend chart |
| `site/js/icons.js` | The icons and the logo, as inline SVG |
| `site/js/api.js` | The only code that calls the backend: sessions, token refresh, the request log |
| `site/js/app.js` | Login, the shell (sidebar, top bar, routing), the API requests bar |
| `site/js/views/` | One file per area: `analytics`, `orders` (and the checkout), `payments`, `operations` (settlements, webhooks), `account`, `operator` |

Markup is built with `createElement` and text nodes, never `innerHTML`: receipts, notes, webhook payloads and error text come from the API and must not be able to become markup.

One layout trap worth knowing: content hidden for screen readers (`.sr-only`) goes on a block wrapper, never on a `<table>`. A table can't be made smaller than its content, so one "hidden" at 1 px keeps its full height and, being absolutely positioned, stretches the page far past its end. The chart's data table did exactly that on the Reports page.

### How it reaches the API

A browser may only call the origin a page came from, so `serve.py` answers the page's `/v1/**` (and `/webhook/**`) calls by passing them to the gateway unchanged. That is why the gateway needed no change for the dashboard: no CORS, no static files, no new public route, and its strict `Content-Security-Policy` stays as it is.

- It listens on `127.0.0.1` only, like every other PayFlo process, and forwards a fixed list of headers (`Authorization`, `Content-Type`, `Accept`, `X-Idempotency-Key`, `X-Admin-Key`). Identity headers are not in the list, and the gateway drops them anyway.
- It never forwards `/internal/**`, stores nothing, and logs one line per API call: the method, path and status, never a header or a body.
- The page keeps the session (the JWT and refresh token, or the admin key) in `sessionStorage`, so closing the tab ends it. The only thing it remembers longer is the theme (light by default, dark from the button in the top bar), in `localStorage`. Its own `Content-Security-Policy` allows scripts and styles from itself only.

### Limits

- **It is a demo client, not a product.** There is no hosted checkout: the card form posts a card number to `POST /v1/vault/tokenize` like any API client would, which is fine for test cards and not how a real checkout should collect one (that takes hosted fields or a redirect, so the merchant's page never sees the number).
- **Local only.** It isn't deployed to the Kubernetes cluster. On kind the gateway is on `localhost:8080` too, so `serve.py` works against it unchanged.
- **Every dashboard user shares the server's address** as far as the gateway's per-address limits (signup, login, failed authentication) are concerned.
- **No automated tests.** It was checked by hand, in a browser, against the running stack.
- **System fonts.** The page's policy allows nothing remote, so it uses the fonts already on the machine (Segoe UI Variable on Windows, San Francisco on macOS, and Inter when it is installed) rather than loading a web font.

## API reference in the browser

<http://localhost:5173/docs.html> renders [`docs/api/openapi.yaml`](../api/openapi.yaml) with Swagger UI, and "Try it out" calls the real API through the same server. Swagger UI itself is loaded from a CDN, so that page needs an internet connection; the spec does not. See the [API reference](../api/README.md#openapi-and-postman).
