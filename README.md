# Seatline

Ticketing for sold-out events that never sells a seat twice, even when servers crash in the middle of the rush.

When thousands of fans hit "Buy" in the same second, ticketing sites tend to fall over or hand the same seat to two people. Seatline puts fans through a fair waiting room, holds seats for ten minutes while they pay, and records every sale in a seat ledger copied across five servers that vote on each change (the Raft consensus algorithm, written from scratch in Java). A sale only counts once three of the five agree, so you can switch two servers off mid-sale and nothing is lost or double-sold.

> Work in progress: the ledger, API, web app, Docker setup, load test and production setup are done; going live on AWS is next.

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
├── backend/             Spring Boot API: accounts, roles, events, waiting room, checkout
│   └── src/main/java/dev/seatline/api/
│       ├── account/     sign-up, email verification, login, two-factor codes, password reset,
│       │                login rate limits, sending email, admin role changes
│       ├── security/    who may call which route, access tokens
│       ├── venue/       venues and seat maps (sections, rows, accessible and limited-view seats)
│       ├── event/       events, price tiers, promo codes, all-in pricing, quotes, shared seat map
│       ├── seats/       the API's connection to the seat ledger
│       ├── order/       holds, checkout, tickets, transfers, returns, cancelling an event
│       ├── queue/       the waiting room (Redis)
│       ├── ticket/      signed QR codes, door teams and door scanning
│       ├── payment/     payment provider interface (stand-in until Stripe keys are set)
│       └── web/         error responses, rate limits
├── frontend/            Next.js web app for fans, organizers and door staff
│   ├── app/             pages: events, seat picker, waiting room, checkout, tickets, sign-in,
│   │                    account (two-step sign-in, password), password reset, organizer (event
│   │                    builder, live dashboard, promo codes, door team, cancel, chaos panel),
│   │                    door scanner, admin, and api/ which passes requests through to the API
│   ├── components/      seat map, header, ticket QR code, dialogs
│   ├── lib/             API client, sign-in state, polling hooks, formatting
│   └── e2e/             Playwright browser tests against the whole stack (desktop and phone)
├── loadtest/            Gatling "simulate a sell-out": thousands of fans buy at once, then the books are checked
├── deploy/              production setup for one server: Compose file with Caddy for HTTPS,
│                        setup.sh, and README.md with the AWS EC2 steps
├── infra/
│   └── docker/          Dockerfiles for the ledger, the API, the web app and the certificates
├── scripts/             check.sh (same checks as CI), chaos.sh, bench.sh and loadtest.sh (write docs/results/),
│                        certs.sh (TLS)
├── docs/                ARCHITECTURE, INTERVIEW, CLAIMS (every claim and its proof), results/
├── docker-compose.yml   the whole stack on one machine: 5 ledger nodes with mutual TLS, API,
│                        Postgres, Redis, web app
└── pom.xml              Maven build for the Java modules
```

## Running everything

You need Docker.

```
docker compose up -d --build
```

Then open http://localhost:3000. The first start makes the TLS certificates for the five ledger nodes and the API. Locally no email is sent: confirmation codes and reset links wait in the `outbox_emails` table (`docker compose exec postgres psql -U seatline -c 'select body from outbox_emails order by id desc limit 1'`); set `SEATLINE_SMTP_HOST` and friends to send them. Set `SEATLINE_JWT_SECRET` in a `.env` file to keep people signed in across API restarts. Every new account is a fan. On a server, the account named by `SEATLINE_ADMIN_EMAIL` becomes the first admin when it sets its password from a reset link (see [deploy/README.md](deploy/README.md)). Locally, to try the organizer, door or admin screens, change your role in Postgres (`update users set role = 'ADMIN' where email = '...'`) and sign in again.

To run it for real on a server, see [`deploy/README.md`](deploy/README.md).

## Running a ledger cluster by hand


```
mvn -q -pl ledger compile dependency:build-classpath -Dmdep.outputFile=target/bench.cp
CP="ledger/target/classes:$(cat ledger/target/bench.cp)"
CLUSTER=1=localhost:7001,2=localhost:7002,3=localhost:7003
for i in 1 2 3; do java -cp "$CP" dev.seatline.ledger.server.LedgerMain --id $i --cluster $CLUSTER --port 800$i --data data/node$i --metrics 910$i & done
curl localhost:9101/metrics
```

`--cluster` lists the ports the servers use to talk to each other; `--port` is where the API connects. Everything listens on localhost unless you pass `--bind`, and the crash/partition controls only exist with `--admin`. `scripts/bench.sh` starts clusters like this itself and writes the throughput and failover results.

## Running the tests

You need Java 21, Maven 3.9+ and Docker (the API tests start a real Postgres with Testcontainers).

```
mvn test
```

`scripts/check.sh` runs what CI runs, apart from the browser tests, which need the Docker stack up: see [`frontend/e2e/README.md`](frontend/e2e/README.md).

The ledger tests run on a simulated network with a fixed random seed, so every run is repeatable. `RandomizedSafetyTest` runs hundreds of seeded scenarios with dropped, delayed and duplicated messages, crashed servers and network splits, some of them writing real log files to disk and recovering from them after simulated power cuts. Pass `-Dseatline.sim.runs=5000` to run more.

`SeatChaosTest` is the headline test. Five fans buy from six seats on a five-server ledger while a "nemesis" crashes servers (often the leader), splits the network and drops, delays and duplicates messages. Every request and answer is recorded, and afterwards the run checks that:

- the whole history is **linearizable**: there's one order of all the operations, consistent with when each started and finished, in which a single ledger would have given exactly the answers the fans saw,
- no seat was sold twice,
- all five copies of the ledger end up identical.

CI runs it on 1,000 seeds. Breaking retry deduplication or letting a leader answer reads without confirming it's still the leader both make it fail within a few seeds.

`Figure8Test` replays the trickiest case from the Raft paper message by message: a leader must not treat an old entry as committed just because most servers have a copy. Remove that check from `RaftNode.maybeCommit` and the test shows two servers applying different sales at the same position in the log.

**Load test.** With the stack up, `scripts/loadtest.sh -Dfans=10000 -DrampSeconds=300` signs in 10,000 fans and sends them at a 5,000-seat event, then checks the books from Postgres and the ledger and writes [`docs/results/sellout.json`](docs/results/sellout.json). Under Docker on a laptop, point it at the API container's own address with `-Dapi=http://<container ip>:8080`; Docker's port forwarding can't keep up with that many connections.
