# Event Ticketing — Architecture Review

Date: 29 September 2026

## Verdict and scope

The modular-monolith direction is appropriate, but this implementation is not ready for public production ticket sales. Retain the deployment model and repair security, inventory boundaries, and payment invariants before adding infrastructure or splitting services.

Reviewed the local Spring Boot backend, domain services, controllers, Flyway migrations, configuration, deployment files, and test sources. The README identifies the admin UI as a separate repository; that UI and a mobile client were not reviewed here. Findings below come from source inspection unless explicitly stated otherwise. External gateway behavior, deployed infrastructure, throughput, and database isolation overrides were not verified.

Validation: attempted the existing Maven test command using the locally available Maven distribution in offline mode. It failed before compilation because the Spring Boot 3.4.1 parent could not be resolved from the offline repository configuration. Docker's daemon was also unavailable, preventing MySQL Testcontainers validation. An existing test report records one skipped test; it is historical evidence, not a result from this review. No application source was changed.

## What is sound

- MySQL owns reservation correctness. The generated `active_lock` and unique index in V2 enforce one active reservation per event/seat independently of application prechecks.
- Flyway manages schema evolution, Hibernate uses validation, and open-session-in-view is disabled.
- Booking seats snapshot their price. Entities carry optimistic version fields.
- Payment orchestration separates database transactions from the external charge call and persists the initial intent first.
- Reconciliation, refund-pending state, retry accounting, and an operator review queue demonstrate useful failure-recovery design.
- Availability caching has a short TTL; writes still validate inventory in the database. Eviction is deferred until transaction commit.
- Request validation, booking ownership checks, ordered seat inserts, and bounded scheduled cleanup provide useful foundations.

These strengths do not establish all business invariants: unique seats do not enforce general-admission capacity, customer quotas, or payment/booking amount agreement.

## Prioritized findings

### 1. P1 — Administrative operations have no application authorization boundary

Evidence: `src/main/java/com/eventticketing/web/admin/EventAdminController.java:35`, `PaymentReviewController.java:34`, and the other admin controllers; `pom.xml` contains no Spring Security dependency and the MVC configurations install no global authorization mechanism.

Any caller who can reach the application can create/change catalog data, change event status and pricing, inspect payment review records, or requeue them. Package placement under `web.admin` does not restrict HTTP access. A separately deployed gateway could mitigate access, but none is represented in this repository.

Recommendation: introduce authenticated, deny-by-default request authorization, administrator/operator roles, and organizer ownership checks if organizers are separate tenants. Record the acting identity for administrative changes. Test that anonymous requests receive 401 and ordinary customers receive 403 on every management operation.

### 2. P1 — Customer tokens are self-asserted identities

Evidence: `common/security/TokenAuthenticator.java:35–54`, `resources/application.yml`, and `web/mobile/DevTokenController.java`.

Every nonempty token is accepted; a UUID token becomes the customer ID directly. Knowing a customer's UUID is therefore sufficient to assert that identity. Arbitrary token rotation also bypasses per-customer abuse controls. Turning off the development token endpoint does not fix the authenticator.

Recommendation: verify tokens from a trusted identity provider and derive the customer from the verified subject. Validate signature, issuer, audience, and lifetime. Confine development authentication and token minting to an explicit development profile. Spring Security's [JWT resource-server support](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html) supplies the relevant verification foundation.

### 3. P1 — General-admission inventory is shared accidentally across events

Evidence: `reservation/repository/BookingRepository.java:85–102`; `reservation/service/ReservationService.java:327–332` and `:979–980`.

The section totals filter only by section ID. Sections belong to a hall and are reused by multiple events. For example, 100 confirmed tickets for Friday's event in a 100-capacity section make Saturday's event appear full even with no Saturday bookings. The write check and seat-map availability both use these totals. Locking the hall section also serializes unrelated events unnecessarily.

Recommendation: key inventory and all counts by `(event_id, section_id)`. Prefer an event-section inventory record that owns the event's capacity and reservation counters, leaving the hall section as venue reference data. Add a regression test with two events sharing one section.

### 4. P1 — General-admission locking can still use a stale capacity snapshot

Evidence: `ReservationService.java:125–127`, `:254–262`, and `:327–332`.

The transaction performs plain reads before obtaining the event/section lock, then uses ordinary aggregate reads for the capacity decision. With MySQL's default REPEATABLE READ, those aggregates can retain the snapshot established before a competing booking committed. Serializing on the parent row does not refresh that snapshot. This creates an oversell path despite the lock.

This is a source-derived concurrency finding under the default isolation level, not a reproduced load-test result. MySQL documents the distinction between snapshot reads and locking reads in its [transaction isolation reference](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html).

Recommendation: make reservation an atomic conditional update on event-scoped inventory, with release/confirmation updating the same invariant transactionally. Alternatively, deliberately use READ COMMITTED with a rigorously consistent parent-lock protocol across all capacity-changing paths. Validate on real MySQL with synchronized competing buyers for the last unit, including expiry and payment races.

### 5. P1 — Seat changes can alter the order after payment amount is fixed

Evidence: `ReservationService.java:375–429`, `:466–493`, and `:761–851`; `PaymentService.java:43–66`.

`beginPayment` snapshots the amount and commits before the gateway call. During that external call, `changeSeats` allows the pending booking to change quantity, seats, and total. `applyPaymentResult` subsequently reloads the booking and confirms it without requiring its amount or revision to match the charged order.

Example: start paying for a 10-unit seat, change to a 100-unit seat while the charge is in flight, then confirm the 100-unit booking against the original 10-unit charge. Optimistic locking does not prevent this sequence when the transactions occur one after another.

Recommendation: freeze inventory and financial terms when payment begins, or introduce an immutable order revision referenced by the payment attempt. Coordinate the start-payment and edit paths on the same booking lock/version and verify the captured revision, amount, and currency on confirmation. Add a delayed-gateway race test.

### 6. P1 — Reconciliation can lose a known successful charge

Evidence: `ReservationService.java:449–459`, `:684–705`, `payment/PaymentGateway.java`, and `payment/FakePaymentGateway.java`.

The service persists the successful provider reference, but reconciliation only accepts a lookup by idempotency key. If that lookup is empty, it marks the payment FAILED even when a reference was already stored. An empty lookup can represent missing history rather than proof that no money moved. The fake gateway demonstrates this after restart because its ledger is held only in memory.

Recommendation: look up known charges by provider reference; distinguish pending/unknown, authoritative failure, success, and refund states. Preserve each attempt as a separate durable record. Do not authorize another charge until the earlier attempt has an authoritative terminal outcome. Keep the fake gateway development-only and fail production startup without a configured real provider.

### 7. P1 — A refund state is not an exclusive worker claim

Evidence: `ReservationService.java:693–694`, `PaymentReconciliationJob.java`, `common/scheduling/SchedulerLease.java`, and `payment/PaymentGateway.java:34`.

Every worker reading REFUND_PENDING is told to refund again. The Redis lease expires without renewal and fails open during outages. Overlap is therefore possible, and the gateway interface does not require a stable refund idempotency key. The comments claim that a database claim prevents duplicate refunds, but replay safety actually depends on an unstated provider guarantee.

Recommendation: give each refund operation a durable ID and stable provider idempotency key, persist its outcome, and make retries safe even when the provider succeeded before the application crashed. Treat worker claiming as a load-control mechanism; it cannot substitute for idempotent external effects.

### 8. P2 — Reconciliation backoff imposes a one-hour minimum retry delay

Evidence: `ReservationService.java:579–608`; `PaymentRepository.java:34–35`.

The database query excludes any previously attempted payment whose last attempt is less than one hour old. The later per-record exponential-backoff filter cannot recover the excluded rows. Thus the documented short retries do not occur; even the first transient failure waits at least an hour for another attempt.

Recommendation: persist and index `next_reconcile_at`, then select work where that time is due. Verify the first retry, successive delays, the cap, and operator requeue with a controlled clock.

### 9. P2 — Hold controls can be bypassed by concurrency or renewal

Evidence: `ReservationService.java:160–169` and `:851`; `BookingController.java:58–60` and `:78–82`.

The one-live-hold check is a read followed by a separate insert, without a customer-level serialization mechanism. Two parallel requests for different seats can both see no hold and succeed. Separately, submitting the same seat list repeatedly extends the expiry indefinitely; this path does not consume the create-booking throttle.

Recommendation: enforce a customer hold slot transactionally, and impose an absolute hold deadline that seat edits cannot move indefinitely. Add parallel same-customer tests and a no-op seat-edit renewal test. Request idempotency would also make recovery from lost create-booking responses safer.

### 10. P1 — Event lifecycle and capacity rules are incomplete

Evidence: `catalog/service/EventService.java:71–99`, `:137–151`; `ReservationService.java:125–148`, `:171–240`, `:284–345`, `:375–429`, and `:1101–1110`.

Cancellation changes only event status. Existing pending holds can still proceed to payment, because payment checks the booking rather than event eligibility. Existing confirmed tickets are not passed into any cancellation/refund workflow. Non-cancelled events can return to DRAFT, which also re-enables hall replacement despite existing sales. Booking eligibility does not check the event's time window.

The seated and section-GA booking paths also do not enforce the aggregate event `maxCapacity`. Marking SOLD_OUT after payment is too late when several valid holds already exceed that cap; mixed halls further complicate the seated-only count.

Recommendation: define allowed transitions and financial consequences explicitly. Prevent incompatible venue changes once reservations exist. Serialize cancellation with payment eligibility and initiate durable cancellation/refund work. Enforce both section and event capacity where both are business constraints, or remove an unsupported cap from the contract. Define and test the sales closing policy.

### 11. P2 — Money has an amount but no consistent currency contract

Evidence: `catalog/dto/SectionItem.java`, `reservation/domain/Payment.java`, `reservation/dto/BookingResponse.java`, `payment/PaymentGateway.java`, and `ReservationService.java:215–227`.

Sections carry independently editable currency strings, while booking totals sum seat prices and payment charges accept only an amount. A booking spanning differently denominated sections can produce a meaningless total. General-admission bookings also lack a currency snapshot.

Recommendation: choose one currency per event/order, validate every line against it, and persist amount plus currency on booking and payment attempts. Pass both to the provider and validate decimal precision using the supported currency policy.

## Architectural direction

Keep one deployable application and database for now. The current deployment is simple and fits the strong transactional relationship between seats, holds, and orders. The package boundaries are useful but not yet independently enforceable modules: ReservationService spans more than 1,100 lines, directly accesses catalog repositories, generates tickets, reads availability, and manages payment recovery; HallService in turn accesses reservation repositories.

Refactor incrementally into cohesive application services:

| Area | Responsibility |
| --- | --- |
| Catalog | Venue layouts, event definitions, publishing rules |
| Inventory | Event-seat and event-section capacity; atomic reserve/release |
| Booking | Customer ownership, hold lifetime, immutable checkout revision |
| Payments | Separate attempts, provider interaction, reconciliation, refunds |
| Ticketing | Issue/void tickets; later, authenticated single-use admission validation |
| Read services | Paginated event/history queries and cached availability |

Use narrow module interfaces and architecture tests to prevent accidental repository access across boundaries. Shared-database transactions can remain where they protect business invariants. Introduce a transactional outbox when adding reliable asynchronous notifications, payment dispatch, or cancellation fan-out; do not add a broker without a concrete delivery requirement.

Venue layout should become versioned or frozen for an event after sales start. Currently live hall edits can alter future availability and ticket labels for existing event inventory. Design event-specific inventory snapshots before offering rich post-publication layout editing.

## Operational and maintainability improvements

- Add separate development and production configurations. Current defaults enable seeding and dev tokens, use root database credentials, and disable database TLS. Docker exposes database/cache ports and the runtime image has no explicit non-root user.
- Add readiness/liveness checks, request correlation, structured internal error logs, and metrics for booking latency/conflicts, pool saturation, inventory contention, reconciliation age, refund age, and payments awaiting review. No Actuator dependency or telemetry configuration is present in this repository.
- Replace raw exception text returned by `GlobalExceptionHandler.java:58–59` with a stable public error and correlation ID. Map optimistic-lock conflicts deliberately and log unexpected errors internally.
- Paginate event lists, booking history, and payment review queues. History fetches seats but maps tickets too, which can introduce additional per-booking queries. Seat maps issue separate aggregates for every GA section; batch these and measure query counts.
- Bound lazy expiry on the booking request path; scheduled expiry is batched, but `releaseExpiredForEvent` can load all expired bookings for a hot event in a customer transaction.
- Treat Redis outage behavior as a capacity scenario: cache, throttle, and scheduler lease all degrade simultaneously onto MySQL. Load-test this explicitly. Do not infer a throughput target from cache annotations or pool size alone.
- Update README and API examples: identity headers, seat limits, pricing terminology, payment keys, seeding defaults, and ports have drifted from current code.
- Add a Maven wrapper and a required CI job with real MySQL. Current integration tests use `disabledWithoutDocker = true`, so an apparently successful suite can omit the most important checks. The same-seat concurrency test catches every exception as an acceptable conflict; unexpected failures should fail it.

## Recommended delivery order and acceptance evidence

1. Secure all endpoints and isolate demo authentication/payment behavior. Prove anonymous/admin/customer/organizer access boundaries with HTTP-level tests.
2. Correct event-scoped inventory and payment/edit invariants. Prove two events do not share stock, last-unit GA cannot oversell, and payment cannot confirm a different order revision.
3. Complete payment recovery, refund idempotency, cancellation, and money semantics. Inject failures before/after every provider and database boundary, including worker overlap and restart.
4. Make hold rules atomic, freeze/version published inventory, and close the lifecycle gaps. Add explicit invariants to tests and API documentation.
5. Add production configuration, mandatory integration CI, monitoring, and load tests. Establish throughput and latency targets with the expected event size, buyer concurrency, and polling frequency.

Production approval should require passing these failure/concurrency scenarios on real MySQL plus a payment-provider sandbox. This review establishes specific source-level risks, not a security certification or measured scalability claim.
