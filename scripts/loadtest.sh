#!/usr/bin/env bash
# "Simulate a sell-out": runs the Gatling load test against a running stack (docker compose up -d)
# and writes docs/results/sellout.json with the machine, the command and the book check.
# Settings pass through to Gatling, e.g. scripts/loadtest.sh -Dfans=10000 -DrampSeconds=60
set -euo pipefail
cd "$(dirname "$0")/.."

audit="$PWD/loadtest/target/sellout-audit.json"
rm -f "$audit"
mvn -q -B -pl loadtest gatling:test -Daudit="$audit" "$@"

python3 - "$audit" "$*" <<'PY'
import json, os, platform, subprocess, sys, datetime
audit = json.load(open(sys.argv[1]))
cpu = next((l.split(":", 1)[1].strip() for l in open("/proc/cpuinfo") if l.startswith("model name")), platform.processor())
out = {
    "date": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "machine": f"{os.cpu_count()} cores, {cpu}; the whole stack (5 ledger nodes, API, Postgres, Redis) and Gatling on this one machine",
    "command": ("scripts/loadtest.sh " + sys.argv[2]).strip(),
    "commit": subprocess.run(["git", "describe", "--always", "--dirty"], capture_output=True, text=True).stdout.strip(),
    **audit,
}
json.dump(out, open("docs/results/sellout.json", "w"), indent=2)
print(json.dumps(out, indent=2))
bad = audit["seatsWithTwoTickets"] or audit["ordersWithoutPayment"] or audit["paymentsLost"] or audit["tickets"] != audit["ledgerSold"]
sys.exit("the books don't add up, see above" if bad else 0)
PY
