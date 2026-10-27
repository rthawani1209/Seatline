# Claims and proof

Every claim Seatline makes about itself, where the proof lives, and how to reproduce it with one command. Numbers come only from committed files in [`docs/results/`](results/), each with the date, machine and command that produced it.

Status: ✅ built and proven · 🛠 planned (day of the build plan in brackets)

## Seat ledger (Raft, from scratch)

| Claim | Proof | Reproduce | Measured |
|---|---|---|---|
| ✅ Raft leader election with randomized timeouts, pre-vote and check-quorum | [`ElectionTest`](../ledger/src/test/java/dev/seatline/ledger/raft/ElectionTest.java) | `mvn -pl ledger test -Dtest=ElectionTest` | re-election after a leader crash asserted under 1,000 ms in simulation; real-cluster number 🛠 (day 4) |
| ✅ Never two leaders in one term | `SafetyChecker` runs inside every simulated test and throws on a second leader; [`RandomizedSafetyTest`](../ledger/src/test/java/dev/seatline/ledger/sim/RandomizedSafetyTest.java) | `mvn -pl ledger test -Dtest=RandomizedSafetyTest -Dseatline.sim.runs=2000` | 0 violations, 2,000 seeds in CI |
| ✅ Log replication with nextIndex/matchIndex and the commit rule | [`ReplicationTest`](../ledger/src/test/java/dev/seatline/ledger/raft/ReplicationTest.java) | `mvn -pl ledger test -Dtest=ReplicationTest` | |
| ✅ The Figure 8 rule (only count replicas for current-term entries) | [`Figure8Test`](../ledger/src/test/java/dev/seatline/ledger/raft/Figure8Test.java); removing the check makes it fail with "applied a different entry at index 2" | `mvn -pl ledger test -Dtest=Figure8Test` | |
| ✅ Fast log backtracking (conflict term + index hints) | `RaftNode.handleAppendEntries` / `handleAppendResponse`; `ReplicationTest.divergentFollowerLogsAreRepaired` | `mvn -pl ledger test -Dtest=ReplicationTest` | |
| ✅ Write-ahead log with a CRC32C on every record, fsync before replying | [`FileStorage`](../ledger/src/main/java/dev/seatline/ledger/raft/FileStorage.java); [`FileStorageTest`](../ledger/src/test/java/dev/seatline/ledger/raft/FileStorageTest.java) (torn tail, flipped bits, corrupt hard state) | `mvn -pl ledger test -Dtest=FileStorageTest` | |
| ✅ Crash recovery: a node killed mid-write rejoins with the correct log | `RandomizedSafetyTest.safeWithSnapshotsAndRealFiles` (simulated power cuts drop unsynced bytes, nodes reload from disk) | `mvn -pl ledger test -Dtest=RandomizedSafetyTest` | |
| ✅ Snapshots that truncate the log, InstallSnapshot for lagging followers | [`SnapshotTest`](../ledger/src/test/java/dev/seatline/ledger/raft/SnapshotTest.java) | `mvn -pl ledger test -Dtest=SnapshotTest` | |
| ✅ Exactly-once writes (client id + sequence number dedup) | `SeatLedgerTest.retriedRequestAppliesOnce`; turning dedup off makes the chaos run fail at seed 4 | `mvn -pl ledger test -Dtest=SeatLedgerTest` | |
| ✅ Linearizable reads with ReadIndex, no stale reads after a leader change | [`ReadIndexTest`](../ledger/src/test/java/dev/seatline/ledger/raft/ReadIndexTest.java); skipping the leadership check makes it and the chaos run fail | `mvn -pl ledger test -Dtest=ReadIndexTest` | |
| ✅ Seeded in-memory network that drops, delays, duplicates, reorders and partitions | [`Simulation`](../ledger/src/main/java/dev/seatline/ledger/sim/Simulation.java) | any test above, failures print the seed | |
| ✅ One bad request can't crash the cluster | [`PoisonPillTest`](../ledger/src/test/java/dev/seatline/ledger/seats/PoisonPillTest.java) | `mvn -pl ledger test -Dtest=PoisonPillTest` | |
| 🛠 gRPC + Protocol Buffers transport, Java 21 virtual threads, one lock around Raft state (day 4) | | | |
| 🛠 Ledger throughput on 3 vs 5 nodes, p50/p99 (day 4) | | | [X] writes/sec |
| 🛠 Leader failover time on a real cluster (day 4) | | | [X] ms |

## Never double-selling

| Claim | Proof | Reproduce | Measured |
|---|---|---|---|
| ✅ HOLD is all-or-nothing; a seat has exactly one state | [`SeatLedgerTest`](../ledger/src/test/java/dev/seatline/ledger/seats/SeatLedgerTest.java), `SeatLedger.checkInvariants` | `mvn -pl ledger test -Dtest=SeatLedgerTest` | |
| ✅ jqwik property tests: random command sequences never break the seat invariants | [`SeatLedgerPropertiesTest`](../ledger/src/test/java/dev/seatline/ledger/seats/SeatLedgerPropertiesTest.java) | `mvn -pl ledger test -Dtest=SeatLedgerPropertiesTest` | 1,100 random sequences per run |
| ✅ Linearizability checker written for the project (Wing & Gong with Lowe's memoization) | [`LinearizabilityChecker`](../ledger/src/main/java/dev/seatline/ledger/check/LinearizabilityChecker.java), [`LinearizabilityCheckerTest`](../ledger/src/test/java/dev/seatline/ledger/check/LinearizabilityCheckerTest.java) | `mvn -pl ledger test -Dtest=LinearizabilityCheckerTest` | |
| ✅ 0 double-sold seats across thousands of chaos runs with crashes and network splits, every history checked for linearizability | [`SeatChaos`](../ledger/src/main/java/dev/seatline/ledger/sim/SeatChaos.java), [`results/chaos.json`](results/chaos.json) | `scripts/chaos.sh 10000` | **10,000 runs, 1,249,639 operations checked, 0 violations** |
| 🛠 Every sale has a payment, checked end to end through the backend (day 6, day 10) | | | |
| 🛠 Gatling: 10,000 simulated fans, throughput and p99 purchase latency, 0 double-sold (day 11) | | | [X] fans, [X] ms p99 |

## Still to build

Waiting room, payments, tickets and scanner, accounts and security, frontend, Docker and AWS rows are added as each lands (days 5 to 12 of the build plan).
