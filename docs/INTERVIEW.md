# Interview notes

How to talk about Seatline, in plain words. Each section: what it is, why it's done this way, the trade-off, what breaks without it, and follow-up questions with answers. Numbers come from [`CLAIMS.md`](CLAIMS.md).

*This file grows with the build. Sections for the load test and the AWS deploy are added as those parts land.*

## The 30-second pitch

"Seatline is ticketing for sold-out events. When thousands of people buy at once, ticketing sites crash or sell a seat twice. Seatline keeps who-owns-which-seat in a ledger copied onto five servers that agree on every change using Raft, which I wrote from scratch in Java. A sale only counts once three of the five have it on disk, so I can kill two servers mid-sale and nothing is lost or double-sold. I proved it by running 10,000 randomized crash-and-network-failure runs and checking every recorded history with a linearizability checker I also wrote."

## Raft in one breath

Five servers keep the same ordered list of changes. One is the leader; it sends each change to the others, and a change is final once a majority (3) have saved it. If the leader dies, the others notice the missing heartbeats and elect a new one, and only a server with every final change can win.

**What's a term?** An election round number. Each term has at most one leader. Servers always move to the highest term they hear about, and a leader that sees a higher term steps down.

## Questions you will get

### Why 5 servers and not 2 or 3?
Raft needs a majority to agree. With 3 servers the majority is 2, so you survive 1 failure. With 5 the majority is 3, so you survive 2. With 2 servers the majority is 2, so losing either one stops everything, which is no better than one server. Even numbers don't help: 4 servers still only survive 1 failure. Five is the usual choice when you want to survive one failure while another server is down for maintenance.

### What happens when the leader is cut off with a minority of servers?
It can't get 3 acknowledgements, so nothing it accepts commits, and fans talking to it get timeouts. Within an election timeout it notices it can't reach a majority and steps down (check-quorum). Meanwhile the majority side elects a new leader and keeps selling. When the network heals, the old leader sees the higher term, becomes a follower, and any uncommitted entries it had are overwritten. Nothing a fan was told "confirmed" is lost, because confirmed means committed on a majority.

### Why can't a new leader commit an old-term entry just because a majority has it? (Figure 8)
Because a future leader could still overwrite it. The Raft paper's Figure 8 walks through it: an entry from term 2 gets copied to a majority by a leader in term 4, but a server holding a different term-3 entry at that position can still win an election (its last entry's term, 3, beats 2) and overwrite it. So a leader only counts copies for entries from its own term; older entries become committed indirectly once a current-term entry after them commits. `Figure8Test` replays the exact scenario message by message. **Try it:** delete `&& storage.term(n) == term` in `RaftNode.maybeCommit` and run the test: two servers end up applying different sales at the same log position.

### A client's write timed out. Did it happen? How do you make retries safe?
You can't know: the request might have been lost, or committed with the reply lost. So every client numbers its requests (client id + sequence number) and retries with the **same** number. The ledger remembers each client's last number and answer; a duplicate gets the stored answer and changes nothing. That turns "at least once" retries into "exactly once" effects. The chaos run proves it matters: turn dedup off and it fails within a few seeds, because a retried HOLD comes back "already exists" when the fan actually got the seats.

### Reads don't go through the log. How are they not stale?
A leader might have been replaced without knowing it yet, so it can't just read its own copy. With ReadIndex the leader notes its commit index, confirms with a majority that it's still leader (piggybacked on heartbeats, and reads that arrive together share one round), waits until it has applied up to that index, then answers. A new leader also waits until its first entry commits, since only then does it know what's committed. The chaos run catches the bug if you skip the majority check.

### How did you prove it's correct, not just that tests pass?
Three layers. (1) A safety checker inside every simulated run fails on two leaders in a term or two servers applying different commands at the same position. (2) Chaos runs: fans buy seats while servers crash, the network splits, and messages are dropped, delayed and duplicated, all from a seed so failures replay exactly. (3) Every chaos history goes through a linearizability checker: it searches for one order of all operations that respects real time and matches exactly what fans saw from a single ledger. If no such order exists, something is wrong. I also checked the checks catch real bugs by breaking the code on purpose (removing dedup, skipping the read check, removing the Figure 8 rule) and watching the tests fail.

### What does "linearizable" mean, plainly?
It behaves like there's one copy of the data and every operation happens at a single instant somewhere between when it was sent and when it answered. If a fan's purchase finished before your page load started, your page must show the seat as sold.

### Where does Raft sit in CAP?
Consistency over availability. During a network split only the side with a majority can sell; the minority side refuses rather than risk selling a seat the other side also sells. For ticketing that's the right call: a few seconds of "try again" beats two people holding the same ticket.

### What happens if a server crashes in the middle of writing to disk?
Each log record has a length and a CRC32C checksum. On restart the server replays the log and stops at the first record that's short or whose checksum doesn't match, then cuts the file there. That's safe because a server only acknowledges an entry after fsync, so anything half-written was never promised to anyone.

### Why build Raft instead of using database transactions?
For a real company I'd use something proven (Postgres with serializable transactions, or etcd/ZooKeeper for coordination). I built it to understand and show the consensus layer: elections, log repair, durability and proving correctness. The trade-off is risk: consensus code is easy to get subtly wrong, which is exactly why the project spends so much effort on simulation, linearizability checking and mutation-testing the tests.

### How would you scale to an 80,000-seat stadium?
One Raft group serializes every write through one leader, so throughput is capped by that leader. Seats split cleanly by section, so I'd shard: several Raft groups, each owning some sections, with a small mapping of section to group. A hold within one section stays a single-group command. A hold across sections would need a cross-group transaction (two-phase commit) or simply a rule that one hold stays within one section.

### How do the servers talk to each other, and how is it threaded?
gRPC with Protocol Buffers. Each server keeps one stream open to each other server and pushes Raft messages down it; if a peer is down or slow, messages are dropped rather than queued, because Raft resends anyway. Every request runs on a Java 21 virtual thread, and they all share one lock around the Raft node. One lock sounds slow, but the Raft code stays single-threaded and easy to reason about, and a separate flush thread batches everyone's writes into one fsync (group commit), which is what actually sets throughput. Measured: **11,965 writes/sec on 3 servers and 9,831 on 5**, p99 11.7 ms and 14.8 ms with 64 concurrent clients ([`results/ledger-throughput.json`](results/ledger-throughput.json)). Five servers is slower than three because the leader waits for 3 of 5 instead of 2 of 3 and sends every entry to 4 followers instead of 2.

**Follow-up: why not fine-grained locks?** The gain would be small: the expensive part is the fsync and the network round trip, not the in-memory Raft bookkeeping. A data race in consensus code means a double-sold seat, so simple and correct wins.

### How long does failover take?
I killed the leader process with SIGKILL 20 times on a 5-server cluster while a client kept writing. A new leader was elected after a median of **205 ms** (max 350 ms), and the client's longest pause between two successful writes was a median of **232 ms** ([`results/ledger-failover.json`](results/ledger-failover.json)). Most of that is on purpose: followers wait a random 150-300 ms without heartbeats before deciding the leader is gone. Shorter timeouts mean faster failover but more false alarms when the network or a GC pause slows heartbeats.

### Tell me about a bug you hit.
Two good ones from putting the ledger on a real network:
1. **The benchmark showed about 600 writes/sec with 64 clients, roughly what 4 clients should manage.** The client library used a `synchronized` method around a blocking gRPC call. On Java 21, a virtual thread that blocks inside `synchronized` pins its carrier thread, and there's one carrier per CPU core, so only 4 of the 64 clients could ever be waiting at once. Switching to a `ReentrantLock` let all 64 run (later JDKs fix this pinning).
2. **A server restarted after a crash never caught up.** The leader had opened a gRPC stream to it while it was down, and that stream stayed "not ready" forever even after the server came back, so every message was silently dropped. The fix: if a stream hasn't been writable for 250 ms, cancel it and open a new one. `LedgerClusterTest.aStoppedNodeRestartsFromDiskAndCatchesUp` covers it.
3. **The API froze for 30 seconds when 20 fans tried to hold the same seat at once.** Loading an event ran a second query (each tier's sections) from inside the first query's row loop, so every request needed two database connections at the same time. With a pool of 10, ten requests each grabbed one connection and all waited forever for a second. I made it a single query and checked the rest of the code for the same pattern. The race test `OrderFlowTest.twentyFansRaceForOneSeatAndExactlyOneGetsIt` is what caught it.

### How do you store passwords?
Argon2id through Spring Security. It's deliberately slow and needs a lot of memory per guess, so even with the hashes an attacker can only try a few guesses per second per core, and GPUs don't help much. Each hash has its own random salt, so two people with the same password get different hashes. Login also runs a hash for unknown emails, so timing doesn't leak which emails have accounts.

### How do you stop password guessing?
Three layers: Argon2 makes each offline guess expensive, login is limited to 5 failures per account and 20 per address in 15 minutes, and users can turn on two-factor codes so a password alone isn't enough. A used two-factor code is remembered (by its time step) so someone watching over your shoulder can't reuse it.

### How do you know every endpoint has the right permissions?
The policy lives in one place, and a test turns it into a table: every route times every role (plus nobody), expecting "allowed" or "refused". The test also lists the routes Spring actually serves and fails if one isn't in the table, so a new endpoint can't ship without someone deciding who may call it. Anything not listed is denied by default. I checked the test bites by opening the admin route to any signed-in user; it failed with exactly the three roles that shouldn't get in.

### Why tokens instead of sessions?
The API is stateless, so any API server can handle any request and scaling is just adding servers. Tokens in an `Authorization` header also aren't sent automatically by the browser, which removes cross-site request forgery. The trade-off: a plain JWT can't be revoked before it expires. See the next question.

### How do you handle money?
Whole cents in `long`s, never floating point, so $0.10 + $0.20 is exactly 30 cents. All the price math is in one small class with its own tests: promo discount first, then the fee on what's left, then tax, each rounded half up. The price on the seat map is already the all-in total, so there's no surprise at checkout, and the quote endpoint returns the exact number checkout will charge.

### How do you revoke a JWT?
Each user has a token version number in the database, and every token records the version it was issued at. Changing someone's role or turning off their two-factor bumps the number, and the API checks it on every request, so all their older tokens stop working immediately. It costs one primary-key lookup per request, which gives up a little of "stateless", but a demoted organizer being able to keep publishing events for an hour was worse. `EventFlowTest.aDemotedOrganizersOldTokenStopsWorking` shows it.

### What if the payment goes through but the hold has already expired?
The ledger decides, using the time stamped in its log, so every copy agrees whether the hold was still live. If it wasn't, the ledger refuses to issue tickets and the API refunds the charge. The order is always: charge, then confirm in the ledger, then write Postgres. Charging is idempotent and ticket ids are derived from the hold, so if the API crashes halfway, the fan's retry picks up where it stopped without charging twice or losing the tickets. And if the fan never retries (closed the tab), a background reconciler finds payments that never became orders and either finishes them or refunds them. Each payment attempt is written down before the card is charged, which is what makes that possible. The tests make the API "die" at each step and check it always ends in tickets or a refund, never neither.

### How does the waiting room work?
Each fan who joins gets the next number from a Redis counter. The line moves at a fixed rate the organizer picks, so the number of fans let in so far is just rate times minutes since the sale opened. Any API server can work out from the clock whether your number has come up, with no background job and no coordination. Once it has, you get a 10-minute pass, and holding seats requires it. Two fairness rules: a new fan's number is the larger of "last number given" and "let in so far", plus one, so turns nobody used aren't banked up for a sudden crowd; and everyone who joins before the sale opens is shuffled into a random order at the opening, so bots gain nothing by joining first. The trade-off: someone who joins and leaves still uses a turn, so the real flow into the seat map can be lower than the rate. With more time I'd let in more fans when passes go unused, using the count of active passes.

### How do you stop fake or shared tickets at the door?
The QR code carries the ticket id, a version and the current 15-second time step, signed with an HMAC only the server knows, so you can't make one up. The app refreshes the code every 15 seconds and the door accepts only the current step or one either side, so a screenshot sold to someone else goes stale in about half a minute. Transferring a ticket bumps its version in the ledger, so the seller's code stops working for good. At the door, the scan marks the ticket with an `update ... where scanned_at is null`, which only one request can win, so the same code at two doors gets in once. The trade-off: scanning needs the network. An offline scanner would need to download the event's valid codes ahead of time and sync scans afterwards, which I left out.

### How do the ledger servers know who they're talking to?
Mutual TLS with a private certificate authority. Every node and the API get a certificate, and both sides of every connection show theirs, so a stranger can't even connect. CA-signed isn't enough on its own, though: the API's certificate is CA-signed too, and if it leaked it could be used to send fake Raft messages and overwrite the seat log. So each port also checks the name in the certificate: Raft only accepts `ledger-N`, the client port only `api`, and the admin controls (crash, revive, cut off, used by the chaos panel) accept a node or the API. There's a test for each case, including a certificate from the same authority with a different name, and I checked the tests fail when any name check is loosened.

### How do you know the frontend actually works?
Playwright tests drive a real browser through every flow against the whole stack in Docker: five ledger nodes, the API, Postgres and Redis. Sign up and verify with the emailed code, pick seats on the map, get a card declined then pay, send a ticket to a friend, return one, go through the waiting room, scan at the door, and an admin crashing the ledger's leader from the chaos panel while sales carry on. One test runs on an emulated phone and checks nothing is wider than the screen. axe checks the main pages in both themes for accessibility problems. CI runs them on every push. The tests don't mock the API, so a passing run means the screens and the backend agree.

### Why polling instead of WebSockets for the live seat map?
Every 2 seconds the page asks for the seat map, which the API reads from the ledger's memory, so it's cheap. Polling works through every proxy and load balancer without sticky sessions, and if a request fails the next one just tries again. The trade-off is up to 2 seconds of lag, which the hold step covers anyway: if someone takes your seat first, the hold is refused and the seat drops out of your picks. For a much bigger venue I'd push changes with server-sent events from the API and keep polling as the fallback.

