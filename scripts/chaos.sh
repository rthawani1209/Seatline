#!/usr/bin/env bash
# Runs N seat chaos runs (default 10,000) and writes docs/results/chaos.json.
# Any broken rule (non-linearizable history, double sale, replicas disagreeing) stops it with the seed.
set -euo pipefail
cd "$(dirname "$0")/.."
runs="${1:-10000}"
mvn -q -B -pl ledger compile
java -cp ledger/target/classes dev.seatline.ledger.sim.ChaosMain "$runs" docs/results/chaos.json
