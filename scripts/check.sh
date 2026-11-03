#!/usr/bin/env bash
# Runs the same steps as CI. Run this before pushing.
set -euo pipefail
cd "$(dirname "$0")/.."

# the API tests run Postgres in Docker (Testcontainers), like CI does
if ! docker info >/dev/null 2>&1; then
  echo "Docker isn't running; start it first (the backend tests need it)" >&2
  exit 1
fi

mvn -B verify
mvn -B -pl ledger test -Dtest=RandomizedSafetyTest -Dseatline.sim.runs=2000
mvn -B -pl ledger test -Dtest=SeatChaosTest -Dseatline.chaos.runs=1000
