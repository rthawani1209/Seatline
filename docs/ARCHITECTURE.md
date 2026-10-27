# Architecture

Seatline sells seats for sold-out events. The one thing it must never do is sell the same seat twice, even when servers crash in the middle of a sale. Everything below is built around that.

## The big picture

```
 fans ──> waiting room ──> API (Spring Boot) ──> seat ledger leader ──> 4 followers
                               │                        (5 servers, Raft)
                               └──> Postgres (accounts, events, orders: a read copy of the ledger)
```

The **seat ledger** is the only place that decides who owns a seat. The API asks it to hold and sell seats; everything else (Postgres, browsers) follows what the ledger says. *(The API, waiting room and frontend are still being built; this file grows with them.)*

## The seat ledger

### What it stores

For every seat: **free**, **held** by one hold (a fan has it for a few minutes while paying), or **sold** as one ticket. A seat is in exactly one of those states, so two fans can't both have it. The only way to sell a seat is to turn your own hold into a ticket, and only one hold can have a seat at a time.

Changes are commands: `CreateEvent`, `Hold` (all the requested seats or none), `Confirm` (hold to tickets, refused if the hold ran out), `Release`, `ExpireDue`, `Transfer` and `Return`. Code: [`ledger/.../seats/SeatLedger.java`](../ledger/src/main/java/dev/seatline/ledger/seats/SeatLedger.java).

### Why five copies, and why Raft

One server would be a single point of failure. So the ledger runs on five servers, and every change goes through **Raft**, a way for a group of servers to agree on one ordered list of changes (the *log*):

1. One server is the **leader**. Fans' commands go to it.
2. The leader adds the command to its log and sends it to the other four.
3. Once **3 of 5** have written it to disk, it's **committed**: it will never be lost or changed.
4. Every server applies committed commands in the same order, so all five copies of the ledger stay identical.

Three out of five is a majority. Any two majorities share at least one server, and that server remembers everything committed, which is why losing two servers loses nothing and why two leaders can never both make progress (no "split brain").

If the leader dies, the others stop hearing its heartbeats, wait a random 150-300 ms (random so they don't all try at once), and hold an election. The winner must have every committed entry, which Raft guarantees by having voters refuse candidates with less up-to-date logs.

Code: [`ledger/.../raft/RaftNode.java`](../ledger/src/main/java/dev/seatline/ledger/raft/RaftNode.java). It's a plain single-threaded state machine with no threads, sockets or clocks inside, driven by `tick()` (time passes), `step(message)` (a message arrives) and `propose(command)`. That's what lets the same code run on a real network and inside the simulator.

### Details that matter

| Piece | Plain-English version | Where |
|---|---|---|
| Pre-vote | Before starting an election, ask "would you vote for me?" without bumping the term. A server that was cut off can't barge back in and knock out a healthy leader. | `startPreVote` |
| Check-quorum | A leader that hasn't heard from a majority for an election timeout steps down, so a leader stranded on the wrong side of a network split stops pretending. | `tick` |
| The Figure 8 rule | A new leader only counts copies to commit entries from its **own** term. An older entry sitting on a majority can still be overwritten, so treating it as committed could lose a sale. | `maybeCommit` |
| No-op on election | A new leader immediately commits an empty entry, which settles what's committed from earlier terms. | `becomeLeader` |
| Fast backtracking | When a follower's log disagrees, it tells the leader the conflicting term and where it starts, so repair skips a whole term per round trip instead of one entry. | `handleAppendEntries` |
| Write-ahead log | Every command is written to an append-only file with a CRC32C checksum and fsynced before the server answers anyone. On restart a half-written last record is detected by its checksum and cut off. | `FileStorage` |
| Snapshots | Every 10,000 entries the ledger saves its whole state and the log before that point is deleted. A follower that fell too far behind gets the snapshot instead. | `maybeSnapshot`, `InstallSnapshot` |
| Exactly-once | Each client numbers its requests. The ledger remembers each client's last number and answer, so a retry after a timeout gets the original answer instead of happening twice. | `SeatLedger.apply` |
| ReadIndex reads | Reads skip the log, but the leader first confirms with a majority that it's still the leader, then waits until it has applied everything committed so far. So a read never returns stale data, even right after a leader change. | `readIndex` |
| Time | Hold expiry uses the timestamp the leader stamped on the log entry, never a server's own clock, so all five copies agree on whether a hold had expired. | `Entry.timeMillis` |
| Bad input | A command that can't be decoded is turned away by the leader, and if one ever reaches the log every copy answers `BAD_REQUEST` instead of crashing. | `SeatLedger.accepts` |

### Running on a real network

Each ledger server is one Java process ([`server/LedgerServer.java`](../ledger/src/main/java/dev/seatline/ledger/server/LedgerServer.java)) with three **gRPC** services, defined in [`ledger.proto`](../ledger/src/main/proto/ledger.proto) with **Protocol Buffers**:

- **Raft**, server to server. Each server keeps one long-lived stream open to each of the others and pushes Raft messages down it. If a peer is down or can't keep up, messages are dropped instead of piling up in memory; Raft already resends on the next heartbeat.
- **Ledger**, for the API: `Write` (a seat command), `SeatMap` and `GetTicket` (linearizable reads through ReadIndex).
- **Admin**, for the chaos panel and the benchmarks: status, crash, revive, cut off from other servers, heal.

**Threads.** Every incoming call runs on its own **Java 21 virtual thread**, so thousands of fans waiting on a write cost almost nothing. All of them share **one lock** around the Raft node. That's slower than clever fine-grained locking in theory, but the Raft code stays single-threaded and easy to reason about. The lock is held briefly; the longest hold is the fsync, which batches everyone's writes (next paragraph).

**Group commit.** A call takes the lock, hands its input to Raft (a message, a new command, a clock tick) and leaves. One flush thread then takes the lock, fsyncs the log **once** for everything that arrived since the last flush, and sends the outgoing messages. Under load, dozens of sales share one fsync. Replies to clients are held until that fsync has happened, so nobody is told "sold" for an entry that isn't on disk yet.

**Client library** ([`client/LedgerClient.java`](../ledger/src/main/java/dev/seatline/ledger/client/LedgerClient.java)). Tries the server it last saw as leader, follows "not the leader, try node 3" hints, moves on when a server is down, and backs off briefly once it has tried all of them. Each `Session` has a random client id and numbers its writes; a retry reuses the number, so the ledger's exactly-once check catches it.

**Metrics.** Each server serves Prometheus metrics on `/metrics`: term, commit index, whether it's leader, leader changes, elections started, writes by outcome and a write-latency histogram ([`server/Metrics.java`](../ledger/src/main/java/dev/seatline/ledger/server/Metrics.java)).

Run a node: `java -cp ... dev.seatline.ledger.server.LedgerMain --id 1 --cluster 1=localhost:7001,2=localhost:7002,3=localhost:7003 --data data/node1 --metrics 9101`.

### How it's tested

- **Simulator** ([`sim/Simulation.java`](../ledger/src/main/java/dev/seatline/ledger/sim/Simulation.java)): a fake world with a virtual clock and a network that drops, delays, duplicates, reorders and splits messages, plus crashes and power cuts. Everything random comes from one seed, so any failure replays exactly.
- **Safety checker**: watches every simulated run and fails the moment there are two leaders in one term or two servers apply different commands at the same log position.
- **Chaos runs** ([`sim/SeatChaos.java`](../ledger/src/main/java/dev/seatline/ledger/sim/SeatChaos.java)): five fans fight over six seats while servers crash and the network misbehaves. Every request and answer is recorded.
- **Linearizability checker** ([`check/LinearizabilityChecker.java`](../ledger/src/main/java/dev/seatline/ledger/check/LinearizabilityChecker.java)): takes a chaos run's recorded history and searches for one order of all operations that matches real time and gives exactly the answers fans saw from a single, non-replicated ledger. If none exists, the run fails. Results: [`results/chaos.json`](results/chaos.json).
- **Real cluster test** ([`LedgerClusterTest`](../ledger/src/test/java/dev/seatline/ledger/server/LedgerClusterTest.java)): three real servers on localhost talking gRPC with files on disk. Sells seats, retries a write with the same id, crashes the leader, stops a server and restarts it from disk, and cuts the leader off through the Admin API.
- **Benchmarks** ([`bench/LedgerBench.java`](../ledger/src/main/java/dev/seatline/ledger/bench/LedgerBench.java), `scripts/bench.sh`): starts every server as its own JVM, measures writes per second and latency on 3 and 5 servers, and kills the leader with SIGKILL 20 times to time failover. Results: [`results/ledger-throughput.json`](results/ledger-throughput.json), [`results/ledger-failover.json`](results/ledger-failover.json).
