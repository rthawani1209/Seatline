# Claims and proof

Every claim Seatline makes about itself, where the proof lives, and how to reproduce it with one command. Numbers come only from committed files in [`docs/results/`](results/), each with the date, machine and command that produced it. The benchmark numbers are from one 4-core machine running every server, so a real deployment with one machine per server will differ.

Status: ✅ built and proven · 🛠 planned (day of the build plan in brackets)

## Seat ledger (Raft, from scratch)

| Claim | Proof | Reproduce | Measured |
|---|---|---|---|
| ✅ Raft leader election with randomized timeouts, pre-vote and check-quorum | [`ElectionTest`](../ledger/src/test/java/dev/seatline/ledger/raft/ElectionTest.java) | `mvn -pl ledger test -Dtest=ElectionTest` | re-election after a leader crash asserted under 1,000 ms in simulation; on a real cluster see the failover row |
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
| ✅ gRPC + Protocol Buffers transport between servers and for clients | [`ledger.proto`](../ledger/src/main/proto/ledger.proto), [`LedgerServer`](../ledger/src/main/java/dev/seatline/ledger/server/LedgerServer.java), [`LedgerClusterTest`](../ledger/src/test/java/dev/seatline/ledger/server/LedgerClusterTest.java) (3 real servers on localhost) | `mvn -pl ledger test -Dtest=LedgerClusterTest` | |
| ✅ Java 21 virtual threads for RPC handling, one lock around Raft state, group commit (one fsync per batch) | `LedgerServer` (`newVirtualThreadPerTaskExecutor`, `withRaft`, `flush`) | `mvn -pl ledger test -Dtest=LedgerClusterTest` | |
| ✅ Client library: finds the leader, follows hints, retries with the same client id + seq | [`LedgerClient`](../ledger/src/main/java/dev/seatline/ledger/client/LedgerClient.java); `LedgerClusterTest.retriedWriteIsAppliedOnce`, `survivesTheLeaderCrashing...` | `mvn -pl ledger test -Dtest=LedgerClusterTest` | |
| ✅ Admin API to crash, revive and cut off servers (for the chaos panel); off unless started with `--admin`, peer and client traffic on separate ports, localhost by default | `LedgerServer.AdminService`; `LedgerClusterTest.anIsolatedLeaderStepsDown...`, `adminIsOffUnlessAskedFor...` | `mvn -pl ledger test -Dtest=LedgerClusterTest` | |
| ✅ Prometheus metrics: term, commit index, leader, leader changes, writes, write latency | [`Metrics`](../ledger/src/main/java/dev/seatline/ledger/server/Metrics.java); `LedgerClusterTest.exposesPrometheusMetrics` | `mvn -pl ledger test -Dtest=LedgerClusterTest` | |
| ✅ Ledger throughput on 3 vs 5 servers (each its own JVM, fsync on, 64 concurrent clients) | [`LedgerBench`](../ledger/src/main/java/dev/seatline/ledger/bench/LedgerBench.java), [`results/ledger-throughput.json`](results/ledger-throughput.json) | `scripts/bench.sh` | **3 servers: 11,965 writes/sec, p50 5.0 ms, p99 11.7 ms. 5 servers: 9,831 writes/sec, p50 6.0 ms, p99 14.8 ms** |
| ✅ Leader failover on a real cluster (leader killed with SIGKILL, 20 times) | `LedgerBench.failover`, [`results/ledger-failover.json`](results/ledger-failover.json) | `scripts/bench.sh` | **5 servers: new leader in 205 ms median (max 350), client writes paused 232 ms median. 3 servers: 219 ms / 255 ms** |
| ✅ Mutual TLS between ledger nodes and the API; each port also checks the certificate's name (Raft traffic only from nodes, the client port only from the API, the admin controls only from nodes or the API) | [`Tls`](../ledger/src/main/java/dev/seatline/ledger/server/Tls.java), [`scripts/certs.sh`](../scripts/certs.sh); [`LedgerTlsTest`](../ledger/src/test/java/dev/seatline/ledger/server/LedgerTlsTest.java) (6 tests; a certificate from the same CA with another name is refused everywhere, and loosening any name check fails a test); all API tests run against a TLS ledger | `mvn -pl ledger test -Dtest=LedgerTlsTest` | |

## Never double-selling

| Claim | Proof | Reproduce | Measured |
|---|---|---|---|
| ✅ HOLD is all-or-nothing; a seat has exactly one state | [`SeatLedgerTest`](../ledger/src/test/java/dev/seatline/ledger/seats/SeatLedgerTest.java), `SeatLedger.checkInvariants` | `mvn -pl ledger test -Dtest=SeatLedgerTest` | |
| ✅ jqwik property tests: random command sequences never break the seat invariants | [`SeatLedgerPropertiesTest`](../ledger/src/test/java/dev/seatline/ledger/seats/SeatLedgerPropertiesTest.java) | `mvn -pl ledger test -Dtest=SeatLedgerPropertiesTest` | 1,100 random sequences per run |
| ✅ Linearizability checker written for the project (Wing & Gong with Lowe's memoization) | [`LinearizabilityChecker`](../ledger/src/main/java/dev/seatline/ledger/check/LinearizabilityChecker.java), [`LinearizabilityCheckerTest`](../ledger/src/test/java/dev/seatline/ledger/check/LinearizabilityCheckerTest.java) | `mvn -pl ledger test -Dtest=LinearizabilityCheckerTest` | |
| ✅ 0 double-sold seats across thousands of chaos runs with crashes and network splits, every history checked for linearizability | [`SeatChaos`](../ledger/src/main/java/dev/seatline/ledger/sim/SeatChaos.java), [`results/chaos.json`](results/chaos.json) | `scripts/chaos.sh 10000` | **10,000 runs, 1,249,639 operations checked, 0 violations** |
| ✅ Sell-out load test: 10,000 simulated fans for 5,000 seats through the real API, ledger and database, then the books are checked from Postgres and the ledger | [`loadtest/`](../loadtest/) (Gatling, open model, fans retry taken seats), [`results/sellout.json`](results/sellout.json) | `scripts/loadtest.sh -Dfans=10000 -DrampSeconds=300` (stack up first) | **all 5,000 seats sold in 100.1 s (33.3 purchases/s), 0 seats with two tickets, purchase p50 21 ms, p99 4.1 s**; one 4-core machine running everything, API freshly started (cold JVM). Fans arrive evenly over 300 s, so about two thirds (6,659) came after the sell-out and were told so |
| ✅ Every sale has a payment: after the sell-out, every order matches a completed payment for the same amount, and no money taken is left without an order or a refund | `Stage.audit` in the load test (`ordersWithoutPayment`, `paymentsLost`), [`results/sellout.json`](results/sellout.json) | same | **0 orders without payment, 0 payments lost, ledger sold = tickets = 5,000** |

## Accounts and security

| Claim | Proof | Reproduce | Measured |
|---|---|---|---|
| ✅ Passwords hashed with Argon2id | `SecurityConfig.passwordEncoder`; `AccountFlowTest.registerVerifyLoginAndSeeYourself` checks the stored hash starts with `$argon2id$` | `mvn -pl backend -am test -Dtest=AccountFlowTest -Dsurefire.failIfNoSpecifiedTests=false` | |
| ✅ Email verification before login; tokens stored hashed, single use, expire in 24 h | [`AccountService`](../backend/src/main/java/dev/seatline/api/account/AccountService.java); `AccountFlowTest.emailMustBeVerifiedAndTokensWorkOnce` | same | |
| ✅ Optional two-factor codes (TOTP, RFC 6238), codes can't be replayed | [`Totp`](../backend/src/main/java/dev/seatline/api/account/Totp.java) (checked against the RFC's test vectors in `TotpTest`); `AccountFlowTest.twoFactorIsRequiredOnceEnabledAndCodesCantBeReused` | same | |
| ✅ Login rate limits per address and account, per address, and per account; attempts are counted before the password is checked, so parallel guesses can't slip past | [`LoginLimiter`](../backend/src/main/java/dev/seatline/api/account/LoginLimiter.java); `AccountFlowTest.repeatedFailuresAreRateLimited`, `LoginLimiterTest` | same | 5 per address and account, 30 per address, 50 per account, per 15 min |
| ✅ A two-factor code sent many times at once signs in once | `AccountService.useStep` (one conditional update); `AccountFlowTest.oneTwoFactorCodeSentTenTimesAtOnceSignsInOnce` (fails if the step is claimed with a read then a write) | same | 10 parallel tries, 1 succeeds |
| ✅ Change password (needs the current one), reset a forgotten password by email (link works once, for an hour, and doesn't say whether an account exists), sign out everywhere | `AccountService.changePassword`, `forgotPassword`, `resetPassword`, `signOutEverywhere`; `AccountFlowTest.changingThePasswordNeedsTheOldOneAndSignsOutOtherSessions`, `aResetLinkSetsANewPasswordOnceAndStrangersLearnNothing`, `signingOutEverywhereEndsEverySession`; e2e `account.spec.ts` | same | |
| ✅ Role-based access (fan, organizer, door staff, admin) on every route, tested per route and role; unlisted routes are refused | [`SecurityConfig`](../backend/src/main/java/dev/seatline/api/security/SecurityConfig.java), [`RouteAccessTest`](../backend/src/test/java/dev/seatline/api/security/RouteAccessTest.java) (making the admin route open to any signed-in user fails it) | `mvn -pl backend -am test -Dtest=RouteAccessTest -Dsurefire.failIfNoSpecifiedTests=false` | |
| ✅ Tokens can be revoked: role changes, password changes, turning off two-factor and "sign out everywhere" end existing sessions at once | [`TokenVersionCheck`](../backend/src/main/java/dev/seatline/api/security/TokenVersionCheck.java); `EventFlowTest.aDemotedOrganizersOldTokenStopsWorking`, `AccountFlowTest.turningTwoFactorOffNeedsACodeAndKeepsOnlyThisSessionSignedIn` | `mvn -pl backend -am test -Dtest=EventFlowTest -Dsurefire.failIfNoSpecifiedTests=false` | |
| ✅ Admins manage roles from an admin page; an admin can't change their own role | [`AdminUserController`](../backend/src/main/java/dev/seatline/api/account/AdminUserController.java); `AdminUsersTest`, e2e `admin.spec.ts` | `mvn -pl backend -am test -Dtest=AdminUsersTest -Dsurefire.failIfNoSpecifiedTests=false` | |
| ✅ Per-minute limits on quotes and holds, shared across API servers (Redis), so promo codes can't be guessed by script | [`RateLimiter`](../backend/src/main/java/dev/seatline/api/web/RateLimiter.java); `EventFlowTest.quotingIsLimitedSoPromoCodesCantBeGuessed` | `mvn -pl backend -am test -Dtest=EventFlowTest -Dsurefire.failIfNoSpecifiedTests=false` | 60 quotes, 30 holds per account per minute |
| ✅ Parameterized queries only (Spring Data JPA and `JdbcTemplate` with `?` placeholders), schema managed by Flyway | `backend/src/main/resources/db/migration/` | | |
| ✅ Testcontainers: API tests run against a real Postgres in Docker | [`TestDatabase`](../backend/src/test/java/dev/seatline/api/TestDatabase.java) | `mvn -pl backend -am test` | |

## Venues, events and pricing

| Claim | Proof | Reproduce | Measured |
|---|---|---|---|
| ✅ Venues with seat maps: sections, rows, labels like `FLOOR-A-1`, x/y layout, accessible and limited-view seats | [`VenueService`](../backend/src/main/java/dev/seatline/api/venue/VenueService.java); `EventFlowTest.venueSeatMapHasLabelsLayoutAndAccessibility` | `mvn -pl backend -am test -Dtest=EventFlowTest -Dsurefire.failIfNoSpecifiedTests=false` | |
| ✅ Price tiers (standard, early bird that ends on a date, student needing a .edu email, VIP), each limited to some sections | [`EventService`](../backend/src/main/java/dev/seatline/api/event/EventService.java); `EventFlowTest.quotesApplyPromoCodesAndCheckTiers` | same | |
| ✅ Promo codes (percent off, expiry) and a per-fan ticket limit | `EventService.addPromo`, `EventService.quote`; `EventFlowTest.quotesApplyPromoCodesAndCheckTiers`, `EventFlowTest.perFanLimitAndExpiredPromoCodesAreRefused` | same | 4 per fan in the test event |
| ✅ Promo code use limits: a use is reserved when seats are held (never more than `max_uses` at once) and returned if the hold ends unpaid | `OrderFlowTest.aPromoCodeStopsWorkingOnceItsUsesAreGone`, `OrderFlowTest.theLastPromoUseCantGoToTwoFansAndComesBackIfUnpaid` | `mvn -pl backend -am test -Dtest=OrderFlowTest -Dsurefire.failIfNoSpecifiedTests=false` | |
| ✅ All-in pricing: fees and tax are in the price fans see, in whole cents | [`Pricing`](../backend/src/main/java/dev/seatline/api/event/Pricing.java); `PricingTest`, `EventFlowTest.draftsAreHiddenUntilPublishedAndPricesAreAllIn` | `mvn -pl backend -am test -Dtest='PricingTest,EventFlowTest' -Dsurefire.failIfNoSpecifiedTests=false` | |
| ✅ Publishing puts the event's seats in the Raft ledger; the live seat map is read from the ledger, never from Postgres | `EventService.publish`, [`Seats`](../backend/src/main/java/dev/seatline/api/seats/Seats.java); `EventFlowTest.publishingPutsTheSeatsInTheLedger` (real ledger node in the test) | `mvn -pl backend -am test -Dtest=EventFlowTest -Dsurefire.failIfNoSpecifiedTests=false` | |
| ✅ "Best available": free seats together in one row, front rows first, then the middle; optional accessible-only | [`BestSeats`](../backend/src/main/java/dev/seatline/api/event/BestSeats.java); `EventFlowTest.bestAvailablePicksSeatsTogetherNearTheFront` | `mvn -pl backend -am test -Dtest=EventFlowTest -Dsurefire.failIfNoSpecifiedTests=false` | |
| ✅ Organizers can only change their own events; drafts are hidden from the public | `EventService.requireOwner`, `getVisible`; `EventFlowTest.organizersCanOnlyTouchTheirOwnEvents` | same | |
| ✅ Layouts and sales that can't work are refused: two rows with one label, a sale opening after the show starts, a promo code that's already expired; no seats are sold once the show has started | `VenueService.create`, `EventService.create`, `EventService.quote`; `EventFlowTest.layoutsAndSalesThatCantWorkAreRefused`, `perFanLimitAndExpiredPromoCodesAreRefused` | same | |

## Buying tickets

| Claim | Proof | Reproduce | Measured |
|---|---|---|---|
| ✅ Never double-sold through the API: 20 fans grab the same seat at once, exactly one gets it | `OrderFlowTest.twentyFansRaceForOneSeatAndExactlyOneGetsIt` | `mvn -pl backend -am test -Dtest=OrderFlowTest -Dsurefire.failIfNoSpecifiedTests=false` | 1 of 20 succeeds, 19 get 409 |
| ✅ All-or-nothing holds that expire after 10 minutes | [`OrderService.hold`](../backend/src/main/java/dev/seatline/api/order/OrderService.java), ledger `Hold` | same | |
| ✅ Idempotent checkout: retrying charges once and returns the same order | `OrderFlowTest.retryingCheckoutGivesTheSameOrderAndChargesOnce` | same | |
| ✅ One charge per hold, whatever is clicked: ten Pay clicks at once with different cards charge once; a new charge can only start after a decline | `OrderService.attemptKey`; `OrderFlowTest.tenClicksOnPayWithDifferentCardsChargeOnce` (fails with per-card keys), `afterADeclineAnotherCardCanPayButMadeUpCardsCant` | same | 10 parallel clicks, 1 charge |
| ✅ A hold that was let go or ran out is never charged; a free order never reaches the card processor | `OrderService.checkout`; `OrderFlowTest.aHoldThatsBeenLetGoOrHasRunOutIsNeverCharged`, `aFreeOrderNeverReachesTheCardProcessor` | same | |
| ✅ Paying after the hold ran out is refunded automatically | `OrderFlowTest.paymentAfterTheHoldIsGoneIsRefunded` | same | |
| ✅ A crash between the ledger and Postgres is recovered on retry, without a second charge | `OrderFlowTest.checkoutFinishesAfterACrashBetweenLedgerAndDatabase` | same | |
| ✅ Never charged without getting the seats: if the API dies after charging, a background reconciler finishes the order or refunds, with no retry needed from the fan | [`OrderService.reconcileNow`](../backend/src/main/java/dev/seatline/api/order/OrderService.java); `OrderFlowTest.diedAfterChargingAndTheHoldIsStillThereSoTheOrderIsFinished`, `diedAfterChargingAndTheHoldIsGoneSoTheMoneyGoesBack`, `diedAfterTheLedgerConfirmedSoTheOrderIsWritten` | same | |
| ✅ Refunds are queued and retried until sent, and never sent twice | `refunds` table (V5 migration), `OrderService.sendRefunds` | same | |
| ✅ Two simultaneous checkouts of one hold give one order | `OrderFlowTest.twoCheckoutsOfOneHoldAtOnceGiveOneOrder` | same | |
| ✅ Transfer a ticket to another account (old QR version stops working) and return a ticket for a refund; only the buyer can return, and not once it's scanned or the show has started | `OrderFlowTest.ticketsCanBeGivenToAFriendOrHandedBack`, `aTicketCantBeReturnedAfterItsScannedOrTheShowStarts` | same | |
| ✅ Cancelling an event refunds every order in full (fees too, less any refund already made for a returned ticket), exactly once; its tickets stop scanning and holders are emailed | `OrderService.cancelEvent`; `OrderFlowTest.cancellingAnEventRefundsEveryOrderInFullOnce`, `DoorFlowTest.aCancelledEventsTicketsAreTurnedAway`, e2e "an organizer cancels an event" | same | |
| ✅ Emails go through an outbox table and are sent over SMTP when it's configured: each once, failures retried, several API servers never send the same one | [`OutboxSender`](../backend/src/main/java/dev/seatline/api/account/OutboxSender.java) (`for update skip locked`); `OutboxSenderTest` | `mvn -pl backend -am test -Dtest=OutboxSenderTest -Dsurefire.failIfNoSpecifiedTests=false` | |
| 🛠 Stripe test mode: payments go through an interface with a stand-in provider until Stripe test keys are added | [`Payments`](../backend/src/main/java/dev/seatline/api/payment/Payments.java), [`FakePayments`](../backend/src/main/java/dev/seatline/api/payment/FakePayments.java) | | |

## Waiting room

| Claim | Proof | Reproduce | Measured |
|---|---|---|---|
| ✅ Redis-backed waiting room: fans let in in arrival order at the event's rate, with place in line and estimated wait | [`WaitingRoom`](../backend/src/main/java/dev/seatline/api/queue/WaitingRoom.java); `QueueFlowTest.fansAreLetInInOrderAtTheEventsRate` | `mvn -pl backend -am test -Dtest=QueueFlowTest -Dsurefire.failIfNoSpecifiedTests=false` | |
| ✅ Seats can only be held with a 10-minute pass from the waiting room (when the event has one) | same test | same | |
| ✅ Unused turns aren't saved up: a crowd arriving after a quiet spell still goes in at the rate | `QueueFlowTest.turnsNobodyUsedArentSavedUp` | same | |
| ✅ Fans who arrive before the sale opens are put in random order when it opens | `QueueFlowTest.arrivingBeforeTheSaleOpensDoesntMakeYouFirst` | same | 20 early fans shuffled |
| ✅ 2-minute cooldown after letting a hold run out unpaid, or letting it go after sitting on it for over half its time | `OrderService.coolingDown`; `QueueFlowTest.lettingAHoldRunOutMeansAShortWaitButReleasingDoesNot`, `lettingGoAtTheLastMinuteWaitsLikeRunningOut` | same | |
| ✅ Joining again after being let in keeps the pass instead of taking a second place; one network address can bring at most 50 accounts into one line an hour | `WaitingRoom.join`; `QueueFlowTest.joiningAgainWhileLetInKeepsThePassAndDoesntTakeASecondPlace`, `oneAddressCanOnlyBringSoManyAccountsIntoTheLine` | same | |

## Tickets at the door

| Claim | Proof | Reproduce | Measured |
|---|---|---|---|
| ✅ Signed QR codes (HMAC-SHA256); made-up or edited codes are refused | [`TicketCodes`](../backend/src/main/java/dev/seatline/api/ticket/TicketCodes.java); `DoorFlowTest.madeUpOrAlteredCodesAreRefused` | `mvn -pl backend -am test -Dtest=DoorFlowTest -Dsurefire.failIfNoSpecifiedTests=false` | |
| ✅ Each ticket gets in once, even with several doors scanning it at the same moment | `DoorFlowTest.aTicketGetsInOnce`, `twoDoorsScanningTheSameCodeAtOnceLetItInOnce` | same | |
| ✅ The QR code changes every 15 seconds; a screenshot more than one step old is refused | `DoorFlowTest.aScreenshotOfTheCodeGoesStale` | same | 15 s steps, ±1 accepted |
| ✅ A transferred ticket's old code stops working; a returned ticket doesn't get in | `DoorFlowTest.aTransferredTicketsOldCodeStopsWorking`, `aReturnedTicketDoesntGetIn` | same | |
| ✅ Door staff scan only at events they're on the door team for (organizers add them by email) | [`DoorService`](../backend/src/main/java/dev/seatline/api/ticket/DoorService.java); `DoorFlowTest.doorStaffScanOnlyAtTheEventsTheyreOnTheDoorTeamFor`, e2e "the door team decides who can scan" | same | |

## Web app

| Claim | Proof | Reproduce | Measured |
|---|---|---|---|
| ✅ Next.js app for fans, organizers, door staff and admins: sign-up with email verification, password reset, account page with two-step sign-in, seat map, waiting room, checkout, tickets with send and return, event builder, live dashboard with promo codes, door team and cancel, door scanner, admin accounts page | [`frontend/app/`](../frontend/app/) | `docker compose up -d --build`, open http://localhost:3000 | |
| ✅ Browser tests drive every screen against the real stack (5 ledger nodes, API, Postgres, Redis) on a desktop browser and an emulated phone | [`frontend/e2e/`](../frontend/e2e/), CI job `e2e` | see [`frontend/e2e/README.md`](../frontend/e2e/README.md) | 33 tests |
| ✅ The seat map works from the keyboard (arrow keys, Enter/Space) and with a screen reader (each seat is a labelled button) | [`SeatMap`](../frontend/components/SeatMap.tsx); e2e "the seat map works from the keyboard" | same | |
| ✅ No automated accessibility violations (axe, WCAG 2.1 A and AA rules) on the home, sign-in, sign-up, seat picker and tickets pages, in dark and light themes | e2e "main pages have no accessibility violations" | same | 0 violations, 5 pages × 2 themes |
| ✅ Organizer dashboard: live seats left, held, sold, revenue, people waiting, scanned, and a double-sold count (tickets issued in Postgres minus seats the ledger says are sold) | [`OrganizerController`](../backend/src/main/java/dev/seatline/api/organizer/OrganizerController.java), [`frontend/app/organizer/events/[id]`](../frontend/app/organizer/events/); `OrderFlowTest.theOrganizerSeesLiveSalesForTheirEventOnly`, e2e "live numbers update as fans buy" | same | |
| ✅ Chaos panel: an admin crashes the ledger's leader from the browser, a new one is elected, and sales carry on | [`ChaosController`](../backend/src/main/java/dev/seatline/api/seats/ChaosController.java), [`LedgerAdmin`](../backend/src/main/java/dev/seatline/api/seats/LedgerAdmin.java); `ChaosFlowTest`, e2e "an admin kills the ledger's leader..." | same | |

## Running it

| Claim | Proof | Reproduce | Measured |
|---|---|---|---|
| ✅ Docker images for the ledger, API and web app; one Compose file runs the whole stack with the five ledger nodes on their own network, talking over mutual TLS | [`infra/docker/`](../infra/docker/), [`docker-compose.yml`](../docker-compose.yml) | `docker compose up -d --build` | |
| ✅ Production setup for one server: Caddy for HTTPS (Let's Encrypt), API refuses to start without a signing key, passwords on Postgres and Redis, nothing but Caddy published, each container holds only its own TLS key, memory limits | [`deploy/`](../deploy/) (`docker-compose.prod.yml`, `setup.sh`, step-by-step [`README`](../deploy/README.md) for AWS EC2) | `docker compose -f deploy/docker-compose.prod.yml config` | |
| ✅ Seat maps stay fast in a rush: every fan polling one event shares one ledger read per half second | `EventService.seatStates`; `SeatMapSharingTest.fiftyFansPollingAtOnceCostOneLedgerRead` | `mvn -pl backend -am test -Dtest=SeatMapSharingTest -Dsurefire.failIfNoSpecifiedTests=false` | 50 parallel polls, 1 ledger read |
| 🛠 Live on AWS (needs an AWS account) | | | |
