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
- **Admin**, for the chaos panel and the benchmarks: status, crash, revive, cut off from other servers, heal. Off unless the server is started with `--admin` (see ports below).

**Threads.** Every incoming call runs on its own **Java 21 virtual thread**, so thousands of fans waiting on a write cost almost nothing. All of them share **one lock** around the Raft node. That's slower than clever fine-grained locking in theory, but the Raft code stays single-threaded and easy to reason about. The lock is held briefly; the longest hold is the fsync, which batches everyone's writes (next paragraph).

**Group commit.** A call takes the lock, hands its input to Raft (a message, a new command, a clock tick) and leaves. One flush thread then takes the lock, fsyncs the log **once** for everything that arrived since the last flush, and sends the outgoing messages. Under load, dozens of sales share one fsync. Replies to clients are held until that fsync has happened, so nobody is told "sold" for an entry that isn't on disk yet.

**Client library** ([`client/LedgerClient.java`](../ledger/src/main/java/dev/seatline/ledger/client/LedgerClient.java)). Tries the server it last saw as leader, follows "not the leader, try node 3" hints, moves on when a server is down, and backs off briefly once it has tried all of them. Each `Session` has a random client id and numbers its writes; a retry reuses the number, so the ledger's exactly-once check catches it.

**Metrics.** Each server serves Prometheus metrics on `/metrics`: term, commit index, whether it's leader, leader changes, elections started, writes by outcome and a write-latency histogram ([`server/Metrics.java`](../ledger/src/main/java/dev/seatline/ledger/server/Metrics.java)).

**Ports and who can reach them.** Each server has two ports. The **client port** serves only the Ledger service, for the API. The **peer port** serves Raft and, only if the server was started with `--admin`, the Admin service. Both listen on localhost unless `--bind` says otherwise. This matters because nothing on these ports is authenticated yet: anyone who could reach a peer port could pretend to be a leader and overwrite the seat log, and anyone who could reach Admin could crash servers. So until the security work lands, the rule is: peer ports are only reachable by the other ledger servers (separate port, private network, firewall), Admin is off unless needed (benchmarks and local demos), and the chaos panel will reach it through the authenticated API rather than directly.

**Planned (day 11): mutual TLS.** Every server and the API get certificates from a private certificate authority. Peer connections require a certificate that names one of the other ledger servers, and client connections require the API's certificate, so a stranger on the network can't speak Raft or call the ledger at all.

Run a node: `java -cp ... dev.seatline.ledger.server.LedgerMain --id 1 --cluster 1=localhost:7001,2=localhost:7002,3=localhost:7003 --port 8001 --data data/node1 --metrics 9101`.

### How it's tested

- **Simulator** ([`sim/Simulation.java`](../ledger/src/main/java/dev/seatline/ledger/sim/Simulation.java)): a fake world with a virtual clock and a network that drops, delays, duplicates, reorders and splits messages, plus crashes and power cuts. Everything random comes from one seed, so any failure replays exactly.
- **Safety checker**: watches every simulated run and fails the moment there are two leaders in one term or two servers apply different commands at the same log position.
- **Chaos runs** ([`sim/SeatChaos.java`](../ledger/src/main/java/dev/seatline/ledger/sim/SeatChaos.java)): five fans fight over six seats while servers crash and the network misbehaves. Every request and answer is recorded.
- **Linearizability checker** ([`check/LinearizabilityChecker.java`](../ledger/src/main/java/dev/seatline/ledger/check/LinearizabilityChecker.java)): takes a chaos run's recorded history and searches for one order of all operations that matches real time and gives exactly the answers fans saw from a single, non-replicated ledger. If none exists, the run fails. Results: [`results/chaos.json`](results/chaos.json).
- **Real cluster test** ([`LedgerClusterTest`](../ledger/src/test/java/dev/seatline/ledger/server/LedgerClusterTest.java)): three real servers on localhost talking gRPC with files on disk. Sells seats, retries a write with the same id, crashes the leader, stops a server and restarts it from disk, and cuts the leader off through the Admin API.
- **Benchmarks** ([`bench/LedgerBench.java`](../ledger/src/main/java/dev/seatline/ledger/bench/LedgerBench.java), `scripts/bench.sh`): starts every server as its own JVM, measures writes per second and latency on 3 and 5 servers, and kills the leader with SIGKILL 20 times to time failover. Results: [`results/ledger-throughput.json`](results/ledger-throughput.json), [`results/ledger-failover.json`](results/ledger-failover.json).


## The API (Spring Boot)

The API is what browsers and the door scanner talk to. It owns accounts, events and prices in Postgres, and asks the seat ledger for anything that touches who owns a seat. Code: [`backend/`](../backend/src/main/java/dev/seatline/api/).

### Accounts and sign-in

- **Passwords** are hashed with **Argon2id**, a hash designed to be slow and memory-hungry so stolen hashes are expensive to crack. Logging in with an unknown email still runs a hash, so response time doesn't reveal who has an account, and both cases get the same `bad_credentials` answer.
- **Email verification**: sign-up emails a random code; only its SHA-256 is stored, it works once and expires after 24 hours. Until it's used, login is refused.
- **Two-factor codes** (optional): the six-digit codes from an authenticator app (TOTP, RFC 6238), written out in [`Totp.java`](../backend/src/main/java/dev/seatline/api/account/Totp.java) and checked against the RFC's own test values. A code is accepted for one 30-second step either side of now, and never twice.
- **Rate limits**: 5 failed logins per account or 20 per address within 15 minutes, then refused until old failures age out. In memory for now; it moves to Redis with the waiting room so all API servers share the counts. Old entries are swept away so the table can't grow forever. Behind the load balancer the client's real address comes from `X-Forwarded-For`, trusted only when the request arrives from a private-network address (the balancer); otherwise everyone would share the balancer's address and one attacker could lock out every user.
- **Tokens**: a successful login returns a signed token (JWT, HS256, 1 hour) carrying the user id and role. Clients send it as `Authorization: Bearer ...`. Because it's not a cookie, a malicious site can't make the browser send it, so there's no cross-site request forgery to defend against.
- **Taking tokens back**: each token carries the user's *token version*. Changing someone's role or turning off their two-factor bumps it, and every request checks the version against the database, so old tokens stop working at once instead of up to an hour later.

### Who may call what

Every route is listed in [`SecurityConfig`](../backend/src/main/java/dev/seatline/api/security/SecurityConfig.java) with the roles allowed (fan, organizer, door staff, admin); anything not listed is refused. [`RouteAccessTest`](../backend/src/test/java/dev/seatline/api/security/RouteAccessTest.java) holds the same policy as a table, fails if any route is missing from it, and calls every route as nobody and as each role to check who gets in.

### Venues, events and prices

- A **venue** is a list of sections, each with rows of seats. Every seat gets a label (`FLOOR-A-1`: section, row, number), an x/y spot for drawing the map, and flags for **accessible** and **limited view**. Sections are laid out side by side so the frontend can draw the map without extra data. Code: [`venue/`](../backend/src/main/java/dev/seatline/api/venue/).
- An **event** belongs to an organizer and starts as a **draft** that only its organizer (and admins) can see. Publishing puts it on sale.
- **Tiers** set prices for groups of sections: standard, early bird (gone after its end date), student (needs a `.edu` email) and VIP.
- **Promo codes** take a percentage off, with a use limit and an expiry. Each event also caps how many tickets one fan can buy.
- **All-in prices**: the price on the seat map already includes the service fee and tax, worked out in whole cents in one place ([`Pricing`](../backend/src/main/java/dev/seatline/api/event/Pricing.java)): discount first, then fee on what's left, then tax on that. A **quote** checks every rule above for a basket of seats and returns the exact total checkout will charge.

Prices and events live in Postgres. Which seats are free, held or sold is only ever decided by the ledger:

- **Publishing** an event first creates it in the ledger with every seat a tier covers, then marks it on sale in Postgres. If the API dies between the two, publishing again finds the event already in the ledger and carries on.
- **The live seat map** (`GET /events/{id}/seats`) reads straight from the ledger through ReadIndex, so it's never stale. It returns only free, held or sold; who holds or owns a seat isn't shown.
- **Best available** (`GET /events/{id}/best-seats`) suggests seats together in one row: the front-most row that has room wins, then the seats closest to the middle. It can be limited to accessible seats. It only suggests; holding them still goes through the ledger, so two fans offered the same seats can't both get them. Code: [`BestSeats`](../backend/src/main/java/dev/seatline/api/event/BestSeats.java).
- **Talking to the ledger** goes through [`Seats`](../backend/src/main/java/dev/seatline/api/seats/Seats.java): a fixed pool of 64 ledger sessions. The ledger remembers the last answer for every client id it has seen, so reusing sessions keeps that memory bounded. If no leader answers within 5 seconds the API replies `503 try_again`.
- **Tests** start a real one-node ledger inside the test JVM ([`TestLedger`](../backend/src/test/java/dev/seatline/api/TestLedger.java)).

### Buying tickets

Code: [`order/OrderService.java`](../backend/src/main/java/dev/seatline/api/order/OrderService.java).

1. **Hold.** The fan picks seats. The API prices them (same rules as a quote), then asks the ledger to hold all of them for 10 minutes, or none. If even one is taken the fan gets `409 seats_taken` and nothing is held. The quoted price is saved with the hold so checkout charges exactly that.
2. **Pay.** Checkout first writes a *payment attempt* row, then charges the card. Charges are idempotent (the key is the hold plus the card), so pressing "pay" twice charges once.
3. **Confirm.** The API asks the ledger to turn the hold into tickets. If the hold ran out while the fan was paying, the ledger refuses (it uses the time stamped in the log, so every copy agrees) and the API refunds the charge.
4. **Record.** Only after the ledger said yes are the order and tickets written to Postgres, in one transaction, along with a confirmation email in the outbox and the promo code's use count.

**If the API dies halfway**, nobody has to retry for things to come right. Every 30 seconds a reconciler looks for payment attempts that are over a minute old and never finished. For each one it asks the ledger to confirm the hold with that payment: if the tickets are issued (or were already, since a ticket's id is made from the hold and seat and records which payment bought it) it writes the order; if the hold is gone it refunds. So money taken always ends as either tickets or a refund. Refunds go through a queue table too, with an id that says what they're for, so a refund that fails to send is retried and one that two workers both decide on is only sent once. Two checkouts of the same hold at the same moment end with one order.

**Promo codes** are reserved when seats are held (`used < max_uses` checked and incremented in one statement), and given back if the hold is released or runs out unpaid, so the last use of a code can't go to several fans. Every API server also tells the ledger to clear out expired holds every 5 seconds, which is harmless when several do it.

**Payments** sit behind a small interface ([`payment/Payments.java`](../backend/src/main/java/dev/seatline/api/payment/Payments.java)). For now it's a stand-in that approves `tok_visa` and declines `tok_declined`, the way Stripe's test cards do; the Stripe version plugs into the same interface.

**After buying**, a fan can give a ticket to another account (the ticket's version goes up, so a screenshot of the old QR code stops working) or hand it back for a refund of what they paid, which puts the seat back on sale.

### Data

Postgres, with the schema in versioned Flyway migrations ([`db/migration`](../backend/src/main/resources/db/migration/)). All queries go through Spring Data JPA or `JdbcTemplate` with `?` placeholders, never string concatenation, so text from users can't change a query. Tests run against a real Postgres started in Docker by Testcontainers.
