"""Run the PayFlo JMeter plan headless, report its throughput and grade latency and availability.

    python run_load_test.py --threads 100 --duration 300
    python run_load_test.py --summary-only results/20260920-141500     # re-grade an earlier run

Needs Apache JMeter 5.6+ (JMETER_HOME, or `jmeter` on PATH) and keys.csv from provision_keys.py.
JMeter 5.6's bundled Groovy can't run on Java 25 — point JMETER_JAVA at a Java 17 or 21 `java`
executable if your default Java is newer (the services themselves still run on 25).
Each run writes results/<timestamp>/: the raw samples (results.jtl), JMeter's HTML report
(report/index.html), and summary.json with the numbers below.

What it reports (docs/requirements.md):
  throughput   successful requests per second over the whole run. Reported, not graded: it is a property of
               the machine as much as of the system, so there is no number it must reach
  latency      p99 < 1s                        (per request type, and across all requests)
  availability >= 99.99% successful requests   (during the run; a real SLO is measured over months)
"""
import argparse
import csv
import datetime
import json
import math
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
TARGET_P99_MS = 1_000
TARGET_AVAILABILITY = 0.9999


def find_jmeter():
    home = os.environ.get("JMETER_HOME")
    names = ["jmeter.bat", "jmeter"] if os.name == "nt" else ["jmeter"]
    if home:
        for name in names:
            candidate = Path(home) / "bin" / name
            if candidate.exists():
                return str(candidate)
    for name in names:
        found = shutil.which(name)
        if found:
            return found
    sys.exit("JMeter not found: set JMETER_HOME or put jmeter on PATH (https://jmeter.apache.org/download_jmeter.cgi)")


def percentile(sorted_values, p):
    if not sorted_values:
        return 0.0
    rank = max(0, math.ceil(p / 100 * len(sorted_values)) - 1)
    return float(sorted_values[rank])


class CaptureWatcher:
    """Samples payment-service's transition counters through Prometheus while the test runs, and afterwards until
    the payments it started have all been answered by the (simulated) bank and captured.

    A payment is `started` by AUTHORIZE_ATTEMPT and `resolved` by AUTHORIZE_SUCCESS (it is then captured at once) or
    AUTHORIZE_FAIL, so started - resolved is the backlog the bank simulator still has to work through. Prometheus
    scrapes every 5 s, so figures are good to about that. Counters are summed across payment-service replicas."""

    EVENTS = ("AUTHORIZE_ATTEMPT", "AUTHORIZE_SUCCESS", "AUTHORIZE_FAIL", "CAPTURE_SUCCESS", "CAPTURE_FAIL")

    def __init__(self, prometheus_url, interval=2.0):
        self.url = prometheus_url.rstrip("/")
        self.interval = interval
        self.samples = []  # (seconds since start, {event: count since start})
        self._base = None
        self._t0 = None
        self._stop = threading.Event()
        self._thread = None
        self.available = False

    def _read(self):
        query = 'sum by (event) (payflo_payment_transitions_total{event=~"%s"})' % "|".join(self.EVENTS)
        with urllib.request.urlopen(f"{self.url}/api/v1/query?" + urllib.parse.urlencode({"query": query}), timeout=5) as r:
            data = json.loads(r.read().decode())
        counts = {event: 0.0 for event in self.EVENTS}
        for series in data["data"]["result"]:
            counts[series["metric"]["event"]] = float(series["value"][1])
        return counts

    def start(self):
        try:
            self._base = self._read()
        except Exception as error:  # Prometheus isn't running: the load test itself doesn't need it
            print(f"capture watch off ({error})")
            return
        self.available = True
        self._t0 = time.time()
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def _loop(self):
        while not self._stop.wait(self.interval):
            self._sample()

    def _sample(self):
        try:
            now = self._read()
        except Exception:
            return
        self.samples.append((time.time() - self._t0, {e: now[e] - self._base[e] for e in self.EVENTS}))

    def finish(self, last_sample_epoch, drain_timeout=240):
        """JMeter's last request finished at `last_sample_epoch`; wait for the backlog to clear, then report."""
        if not self.available:
            return None
        self._stop.set()
        self._thread.join()
        run_seconds = last_sample_epoch - self._t0
        deadline = time.time() + drain_timeout
        while time.time() < deadline:
            self._sample()
            if self.samples and self._backlog(self.samples[-1][1]) <= 0:
                break
            time.sleep(self.interval)

        def backlog_at(seconds):
            before = [s for s in self.samples if s[0] <= seconds]
            return (before[-1][1] if before else {e: 0 for e in self.EVENTS})

        at_end = backlog_at(run_seconds)
        started = at_end["AUTHORIZE_ATTEMPT"]
        peak = max((self._backlog(s[1]) for s in self.samples), default=0)
        drained = next((s[0] for s in self.samples if s[0] >= run_seconds and self._backlog(s[1]) <= 0), None)
        result = {
            "payments_started": int(started),
            "resolved_at_end_pct": round(100 * (started - self._backlog(at_end)) / started, 1) if started else None,
            "backlog_at_end": int(self._backlog(at_end)),
            "peak_backlog": int(peak),
            "seconds_to_drain_after_end": round(drained - run_seconds, 1) if drained is not None else None,
            "captured_per_second_during_run": round(at_end["CAPTURE_SUCCESS"] / run_seconds, 1),
        }
        return result

    @staticmethod
    def _backlog(counts):
        return counts["AUTHORIZE_ATTEMPT"] - counts["AUTHORIZE_SUCCESS"] - counts["AUTHORIZE_FAIL"]


class WebhookWatcher:
    """The webhook SLA (docs/requirements.md: 99% delivered within 30 s) over a run, from operations-service's
    payflo.webhook.delivery.latency histogram through Prometheus: every delivery accepted between start() and the
    end of the wait, and how long after the change it reports each was accepted. Reports nothing when the test's
    merchants have no webhook (see provision_keys.py --webhook-url). Good to Prometheus' 5 s scrape."""

    SLA_SECONDS = 30.0
    SLA_SHARE = 0.99

    def __init__(self, prometheus_url, interval=2.0):
        self.url = prometheus_url.rstrip("/")
        self.interval = interval
        self.peak_pending = 0.0
        self.peak_oldest_age = 0.0
        self.peak_lag = 0.0
        self._base = None
        self._stop = threading.Event()
        self._thread = None
        self.available = False

    def _query(self, expr):
        with urllib.request.urlopen(f"{self.url}/api/v1/query?" + urllib.parse.urlencode({"query": expr}), timeout=5) as r:
            return json.loads(r.read().decode())["data"]["result"]

    def _snapshot(self):
        buckets = {}
        for series in self._query("sum by (le) (payflo_webhook_delivery_latency_seconds_bucket)"):
            le = series["metric"]["le"]
            buckets[math.inf if le == "+Inf" else float(le)] = float(series["value"][1])
        outcomes = {s["metric"]["outcome"]: float(s["value"][1])
                    for s in self._query("sum by (outcome) (payflo_webhook_deliveries_total)")}
        return buckets, outcomes

    def _gauge(self, expr):
        result = self._query(expr)
        return float(result[0]["value"][1]) if result else 0.0

    def start(self):
        try:
            self._base = self._snapshot()
        except Exception as error:
            print(f"webhook watch off ({error})")
            return
        self.available = True
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def _loop(self):
        while not self._stop.wait(self.interval):
            self._sample_gauges()

    # Events still waiting in Kafka for operations-service's consumer: they have no delivery row (and so are not
    # `pending`) until it gets to them, but their delivery clock is already running.
    LAG = 'sum(kafka_consumer_fetch_manager_records_lag{application="operations-service"})'

    def _sample_gauges(self):
        try:
            self.peak_pending = max(self.peak_pending, self._gauge("sum(payflo_webhook_pending)"))
            self.peak_oldest_age = max(self.peak_oldest_age, self._gauge("max(payflo_webhook_oldest_unattempted_age_seconds)"))
            self.peak_lag = max(self.peak_lag, self._gauge(self.LAG))
        except Exception:
            pass

    def finish(self, drain_timeout=180):
        if not self.available:
            return None
        self._stop.set()
        self._thread.join()
        deadline = time.time() + drain_timeout
        pending = 0.0
        lag = 0.0
        while time.time() < deadline:
            self._sample_gauges()
            try:
                pending = self._gauge("sum(payflo_webhook_pending)")
                lag = self._gauge(self.LAG)
            except Exception:
                pending, lag = 0.0, 0.0
            if pending <= 0 and lag <= 0:
                break
            time.sleep(self.interval)
        time.sleep(6)  # one more scrape, so the deliveries of the last seconds are in
        try:
            buckets, outcomes = self._snapshot()
        except Exception:
            return None
        base_buckets, base_outcomes = self._base
        cumulative = {le: buckets.get(le, 0.0) - base_buckets.get(le, 0.0) for le in buckets}
        delivered = cumulative.get(math.inf, 0.0)
        dead = outcomes.get("dead", 0.0) - base_outcomes.get("dead", 0.0)
        if delivered + dead <= 0:
            return None

        def quantile(q):
            for le in sorted(cumulative):
                if delivered and cumulative[le] >= q * delivered:
                    return le
            return math.inf

        within = max((v for le, v in cumulative.items() if le <= self.SLA_SECONDS), default=0.0)
        share = within / (delivered + dead)
        return {
            "delivered": int(delivered),
            "dead_lettered": int(dead),
            "delivered_within_30s_pct": round(100 * share, 2),
            "sla_met": share >= self.SLA_SHARE,
            "p50_seconds_at_most": None if quantile(0.5) == math.inf else quantile(0.5),
            "p99_seconds_at_most": None if quantile(0.99) == math.inf else quantile(0.99),
            "peak_pending": int(self.peak_pending),
            "peak_oldest_unattempted_age_seconds": round(self.peak_oldest_age, 1),
            "pending_at_end": int(pending),
            "peak_consumer_lag": int(self.peak_lag),
            "consumer_lag_at_end": int(lag),
        }


def print_webhooks(webhooks):
    if not webhooks:
        return
    p50, p99 = webhooks["p50_seconds_at_most"], webhooks["p99_seconds_at_most"]
    print("\nwebhooks (delivery SLA: 99% within 30 s of the change):")
    print(f"  {'PASS' if webhooks['sla_met'] else 'MISS'}  {webhooks['delivered_within_30s_pct']}% within 30 s of "
          f"{webhooks['delivered'] + webhooks['dead_lettered']} events ({webhooks['delivered']} delivered, "
          f"{webhooks['dead_lettered']} dead); p50 <= {p50} s, p99 <= {p99} s; peak {webhooks['peak_pending']} waiting, "
          f"oldest unattempted {webhooks['peak_oldest_unattempted_age_seconds']} s, {webhooks['pending_at_end']} left at the end; "
          f"Kafka consumer lag peaked at {webhooks['peak_consumer_lag']} ({webhooks['consumer_lag_at_end']} at the end)")


def print_capture(capture):
    if not capture:
        return
    drain = capture["seconds_to_drain_after_end"]
    print("\ncapture (payments started and answered by the bank simulator):")
    print(f"  {capture['payments_started']} payments started; {capture['resolved_at_end_pct']}% had been resolved when the run "
          f"ended (backlog {capture['backlog_at_end']}, peak {capture['peak_backlog']}); "
          + (f"cleared {drain} s after the run" if drain is not None else "NOT cleared within the wait")
          + f"; {capture['captured_per_second_during_run']} captures/s over the run")


def summarize(run_dir: Path, capture=None, webhooks=None):
    rows = list(csv.DictReader(open(run_dir / "results.jtl", newline="", encoding="utf-8")))
    if not rows:
        sys.exit(f"no samples in {run_dir / 'results.jtl'}")

    def stats(samples):
        elapsed = sorted(int(r["elapsed"]) for r in samples)
        ok = sum(1 for r in samples if r["success"] == "true")
        start = min(int(r["timeStamp"]) for r in samples)
        end = max(int(r["timeStamp"]) + int(r["elapsed"]) for r in samples)
        seconds = max((end - start) / 1000, 0.001)
        return {
            "requests": len(samples),
            "successful": ok,
            "error_rate": round(1 - ok / len(samples), 6),
            "throughput_rps": round(len(samples) / seconds, 1),
            "successful_rps": round(ok / seconds, 1),
            "p50_ms": percentile(elapsed, 50),
            "p95_ms": percentile(elapsed, 95),
            "p99_ms": percentile(elapsed, 99),
            "max_ms": float(elapsed[-1]),
        }

    by_label = {}
    for r in rows:
        by_label.setdefault(r["label"], []).append(r)
    overall = stats(rows)
    per_label = {label: stats(samples) for label, samples in sorted(by_label.items())}
    availability = overall["successful"] / overall["requests"]
    worst_p99 = max(s["p99_ms"] for s in per_label.values())

    checks = {
        "p99_latency": {"target": f"< {TARGET_P99_MS} ms (every request type)", "actual": worst_p99,
                        "pass": worst_p99 < TARGET_P99_MS},
        "availability": {"target": f">= {TARGET_AVAILABILITY:.2%}", "actual": round(availability, 6),
                         "pass": availability >= TARGET_AVAILABILITY},
    }
    summary = {"run": run_dir.name, "overall": overall, "per_request": per_label, "targets": checks}
    if capture:
        summary["capture"] = capture
    if webhooks:
        summary["webhooks"] = webhooks
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    print(f"\n{'request':<22}{'count':>9}{'rps':>9}{'p50':>8}{'p95':>8}{'p99':>8}{'max':>8}{'errors':>9}")
    for label, s in list(per_label.items()) + [("ALL", overall)]:
        print(f"{label:<22}{s['requests']:>9}{s['throughput_rps']:>9}{s['p50_ms']:>8.0f}{s['p95_ms']:>8.0f}"
              f"{s['p99_ms']:>8.0f}{s['max_ms']:>8.0f}{s['error_rate']:>9.2%}")
    print(f"\nthroughput: {overall['successful_rps']} successful req/s over the whole run")
    print("\ntargets:")
    for name, c in checks.items():
        print(f"  {'PASS' if c['pass'] else 'MISS'}  {name:<13} actual {c['actual']}  (target {c['target']})")
    print_capture(capture)
    print_webhooks(webhooks)
    print(f"\nHTML report: {run_dir / 'report' / 'index.html'}")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", default="8080")
    parser.add_argument("--protocol", default="http")
    parser.add_argument("--threads", type=int, default=50, help="concurrent virtual users")
    parser.add_argument("--rampup", type=int, default=30, help="seconds to start all threads")
    parser.add_argument("--duration", type=int, default=120, help="test length in seconds (includes ramp-up)")
    parser.add_argument("--keys", default=str(HERE / "keys.csv"), help="CSV of keyId,secret")
    parser.add_argument("--summary-only", metavar="RUN_DIR", help="skip JMeter, just grade an existing run")
    parser.add_argument("--prometheus", default="http://localhost:9090",
                        help="Prometheus that scrapes payment-service, for the capture backlog (skipped if unreachable)")
    args = parser.parse_args()

    if args.summary_only:
        summarize(Path(args.summary_only))
        return

    if not Path(args.keys).exists():
        sys.exit(f"{args.keys} not found — run provision_keys.py first")

    run_dir = HERE / "results" / datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir.mkdir(parents=True)
    command = [
        find_jmeter(), "-n",
        "-t", str(HERE / "payflo-load-test.jmx"),
        "-l", str(run_dir / "results.jtl"),
        "-e", "-o", str(run_dir / "report"),
        "-j", str(run_dir / "jmeter.log"),
        f"-Jhost={args.host}", f"-Jport={args.port}", f"-Jprotocol={args.protocol}",
        f"-Jthreads={args.threads}", f"-Jrampup={args.rampup}", f"-Jduration={args.duration}",
        f"-Jkeys={Path(args.keys).resolve()}",
        "-Jjmeter.save.saveservice.output_format=csv",
    ]
    env = dict(os.environ)
    if os.environ.get("JMETER_JAVA"):
        env["JM_LAUNCH"] = os.environ["JMETER_JAVA"]  # jmeter(.bat) launches this java instead of PATH's
    print("running:", " ".join(command))
    watcher = CaptureWatcher(args.prometheus)
    webhook_watcher = WebhookWatcher(args.prometheus)
    watcher.start()
    webhook_watcher.start()
    subprocess.run(command, check=True, env=env)
    # When the load actually stopped: JMeter's own clock, not when it finished writing its report.
    with open(run_dir / "results.jtl", newline="", encoding="utf-8") as samples:
        last = max(int(r["timeStamp"]) + int(r["elapsed"]) for r in csv.DictReader(samples)) / 1000
    summarize(run_dir, watcher.finish(last), webhook_watcher.finish())


if __name__ == "__main__":
    main()
