"""Run the PayFlo JMeter plan headless and grade the result against the non-functional targets.

    python run_load_test.py --threads 100 --duration 300
    python run_load_test.py --summary-only results/20260920-141500     # re-grade an earlier run

Needs Apache JMeter 5.6+ (JMETER_HOME, or `jmeter` on PATH) and keys.csv from provision_keys.py.
JMeter 5.6's bundled Groovy can't run on Java 25 — point JMETER_JAVA at a Java 17 or 21 `java`
executable if your default Java is newer (the services themselves still run on 25).
Each run writes results/<timestamp>/: the raw samples (results.jtl), JMeter's HTML report
(report/index.html), and summary.json with the numbers below.

Targets (docs/requirements.md):
  throughput  >= 10,000 transactions/s        (whole-run average over successful requests)
  latency     p99 < 1s                        (per request type, and across all requests)
  availability >= 99.99% successful requests  (during the run; a real SLO is measured over months)
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
from pathlib import Path

HERE = Path(__file__).resolve().parent
TARGET_TPS = 10_000
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


def summarize(run_dir: Path):
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
        "throughput": {"target": f">= {TARGET_TPS} req/s", "actual": overall["successful_rps"],
                       "pass": overall["successful_rps"] >= TARGET_TPS},
        "p99_latency": {"target": f"< {TARGET_P99_MS} ms (every request type)", "actual": worst_p99,
                        "pass": worst_p99 < TARGET_P99_MS},
        "availability": {"target": f">= {TARGET_AVAILABILITY:.2%}", "actual": round(availability, 6),
                         "pass": availability >= TARGET_AVAILABILITY},
    }
    summary = {"run": run_dir.name, "overall": overall, "per_request": per_label, "targets": checks}
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    print(f"\n{'request':<22}{'count':>9}{'rps':>9}{'p50':>8}{'p95':>8}{'p99':>8}{'max':>8}{'errors':>9}")
    for label, s in list(per_label.items()) + [("ALL", overall)]:
        print(f"{label:<22}{s['requests']:>9}{s['throughput_rps']:>9}{s['p50_ms']:>8.0f}{s['p95_ms']:>8.0f}"
              f"{s['p99_ms']:>8.0f}{s['max_ms']:>8.0f}{s['error_rate']:>9.2%}")
    print("\ntargets:")
    for name, c in checks.items():
        print(f"  {'PASS' if c['pass'] else 'MISS'}  {name:<13} actual {c['actual']}  (target {c['target']})")
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
    subprocess.run(command, check=True, env=env)
    summarize(run_dir)


if __name__ == "__main__":
    main()
