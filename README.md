# Seatline

Ticketing for sold-out events that never sells a seat twice, even when servers crash in the middle of the rush.

When thousands of fans hit "Buy" in the same second, ticketing sites tend to fall over or hand the same seat to two people. Seatline puts fans through a fair waiting room, holds seats for five minutes while they pay, and records every sale in a seat ledger copied across five servers that vote on each change (the Raft consensus algorithm, written from scratch in Java). A sale only counts once three of the five agree, so you can switch two servers off mid-sale and nothing is lost or double-sold.

> Work in progress. The seat ledger is being built first, because everything else depends on it being correct.

## Repo layout

```
seatline/
├── ledger/              Java seat ledger: Raft consensus, storage, simulator
│   └── src/main/java/dev/seatline/ledger/
│       ├── raft/        the Raft node (elections, replication, commit rule, snapshots)
│       │                and storage (write-ahead log on disk with checksums)
│       └── sim/         deterministic network/crash simulator used by the tests
├── backend/             Spring Boot API and waiting room          (coming)
├── frontend/            Next.js fan and organizer app              (coming)
├── loadtest/            Gatling "simulate a sell-out"              (coming)
├── infra/               Docker and AWS Terraform                   (coming)
├── scripts/             check.sh (runs the same checks as CI)
├── docs/                architecture, decisions, interview notes
└── pom.xml              Maven build for the Java modules
```

## Running the tests

You need Java 21 and Maven 3.9+.

```
mvn test
```

`scripts/check.sh` runs exactly what CI runs.

The ledger tests run on a simulated network with a fixed random seed, so every run is repeatable. `RandomizedSafetyTest` runs hundreds of seeded scenarios with dropped, delayed and duplicated messages, crashed servers and network splits, some of them writing real log files to disk and recovering from them after simulated power cuts. Pass `-Dseatline.sim.runs=5000` to run more.

`Figure8Test` replays the trickiest case from the Raft paper message by message: a leader must not treat an old entry as committed just because most servers have a copy. Remove that check from `RaftNode.maybeCommit` and the test shows two servers applying different sales at the same position in the log.
