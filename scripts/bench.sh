#!/usr/bin/env bash
# Benchmarks a real ledger cluster (one JVM per node, fsync on, gRPC over localhost) and writes
# docs/results/ledger-throughput.json and docs/results/ledger-failover.json.
# Flags: --nodes 3,5 --clients 64 --seconds 20 --warmup 5 --failovers 20
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -B -pl ledger compile dependency:build-classpath -Dmdep.outputFile=target/bench.cp
java -cp "ledger/target/classes:$(cat ledger/target/bench.cp)" dev.seatline.ledger.bench.LedgerBench "$@"
