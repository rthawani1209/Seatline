#!/usr/bin/env bash
# Runs the same steps as CI. Run this before pushing.
set -euo pipefail
cd "$(dirname "$0")/.."

mvn -B verify
mvn -B -pl ledger test -Dtest=RandomizedSafetyTest -Dseatline.sim.runs=2000
mvn -B -pl ledger test -Dtest=SeatChaosTest -Dseatline.chaos.runs=1000
