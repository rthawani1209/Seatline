# Seatline

Ticketing for sold-out events that never sells a seat twice, even when servers crash in the middle of the rush.

When thousands of fans hit "Buy" in the same second, ticketing sites tend to fall over or hand the same seat to two people. Seatline puts fans through a fair waiting room, holds seats for five minutes while they pay, and records every sale in a seat ledger copied across five servers that vote on each change (the Raft consensus algorithm, written from scratch in Java). A sale only counts once three of the five agree, so you can switch two servers off mid-sale and nothing is lost or double-sold.

> Work in progress. The seat ledger is being built first, because everything else depends on it being correct.

## Repo layout

```
seatline/
├── ledger/              Java seat ledger: Raft consensus, storage, gRPC server, simulator
│   ├── src/main/java/dev/seatline/ledger/
│   │   ├── raft/        the Raft node (elections, replication, commit rule, snapshots,
│   │   │                ReadIndex reads) and storage (write-ahead log with checksums)
│   │   ├── seats/       the seat ledger: holds, sales, expiry, transfers, returns
│   │   ├── check/       linearizability checker (Wing & Gong search with memoization)
│   │   ├── sim/         deterministic network/crash simulator and the chaos runs
│   │   ├── server/      a ledger node on the network: gRPC services, metrics, main
│   │   ├── client/      client library (finds the leader, retries safely)
│   │   └── bench/       throughput and failover benchmarks
│   └── src/main/proto/  ledger.proto: gRPC services and messages
├── backend/             Spring Boot API and waiting room          (coming)
├── frontend/            Next.js fan and organizer app              (coming)
├── loadtest/            Gatling "simulate a sell-out"              (coming)
├── infra/               Docker and AWS Terraform                   (coming)
├── scripts/             check.sh (same checks as CI), chaos.sh and bench.sh (write docs/results/)
├── docs/                ARCHITECTURE, INTERVIEW, CLAIMS (every claim and its proof), results/
└── pom.xml              Maven build for the Java modules
```

## Running a cluster

```
mvn -q -pl ledger compile dependency:build-classpath -Dmdep.outputFile=target/bench.cp
CP="ledger/target/classes:$(cat ledger/target/bench.cp)"
CLUSTER=1=localhost:7001,2=localhost:7002,3=localhost:7003
for i in 1 2 3; do java -cp "$CP" dev.seatline.ledger.server.LedgerMain --id $i --cluster $CLUSTER --data data/node$i --metrics 910$i & done
curl localhost:9101/metrics
```

`scripts/bench.sh` starts clusters like this itself and writes the throughput and failover results.

## Running the tests

You need Java 21 and Maven 3.9+.

```
mvn test
```

`scripts/check.sh` runs exactly what CI runs.

The ledger tests run on a simulated network with a fixed random seed, so every run is repeatable. `RandomizedSafetyTest` runs hundreds of seeded scenarios with dropped, delayed and duplicated messages, crashed servers and network splits, some of them writing real log files to disk and recovering from them after simulated power cuts. Pass `-Dseatline.sim.runs=5000` to run more.

`SeatChaosTest` is the headline test. Five fans buy from six seats on a five-server ledger while a "nemesis" crashes servers (often the leader), splits the network and drops, delays and duplicates messages. Every request and answer is recorded, and afterwards the run checks that:

- the whole history is **linearizable**: there's one order of all the operations, consistent with when each started and finished, in which a single ledger would have given exactly the answers the fans saw,
- no seat was sold twice,
- all five copies of the ledger end up identical.

CI runs it on 1,000 seeds. Breaking retry deduplication or letting a leader answer reads without confirming it's still the leader both make it fail within a few seeds.

`Figure8Test` replays the trickiest case from the Raft paper message by message: a leader must not treat an old entry as committed just because most servers have a copy. Remove that check from `RaftNode.maybeCommit` and the test shows two servers applying different sales at the same position in the log.
