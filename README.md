# Event Ticketing

A seat-reservation system for events. Users browse published events, pick seats from a
hall's live seat map (or buy general-admission tickets for non-seated halls), hold up to two
seats at a time, and confirm with payment. Seats stay reserved only until a hold expires or
payment completes, and the system guarantees the same seat can never be double-booked for the
same event.

Built as a **modular monolith** with Spring Boot 3 / Java 21 on **MySQL 8**, with **Redis**
caching the read-heavy availability endpoints.

---

## Architecture

One deployable Spring Boot app, organized into bounded contexts that could later be split into
services:

```
com.eventticketing
├── catalog      Organizer, Hall, Seat, Event, EventPricing  (predefined, admin-managed)
├── reservation  Booking, BookingSeat, hold/lock logic, expiry sweeper, live availability
├── payment      PaymentGateway abstraction + FakePaymentGateway (always-approves)
├── demo         optional startup data seeder
└── common       BaseEntity, error handling, Clock
```

- **MySQL** is the single source of truth; **Flyway** owns the schema (Hibernate `ddl-auto: none`).
- **Redis** caches the availability reads with a short TTL, invalidated on booking events (see [Caching](#caching)).
- The mobile client gets **live data by polling** the seat-map / availability endpoints.

## Domain model

| Entity          | Notes |
|-----------------|-------|
| `Organizer`     | Owns events. |
| `Hall`          | `seated` flag. Seated halls have `numRows` × `numColumns`; seats are generated on creation. Non-seated halls have a `capacity`. |
| `Seat`          | Belongs to a hall; carries a `SeatType` (VIP / PREMIUM / REGULAR) and a label like `A1`. |
| `Event`         | Runs in one hall; has a lifecycle status and a `maxCapacity`. |
| `EventPricing`  | Price **per seat type, per event** (a null seat type = the general-admission price). |
| `Booking`       | An order in status `PENDING_PAYMENT → CONFIRMED / EXPIRED / CANCELLED`. |
| `BookingSeat`   | One held/booked seat within a booking, with a price snapshot. |

Event statuses: `DRAFT`, `PUBLISHED`, `CANCELLED`, `SOLD_OUT`. Only `PUBLISHED` events accept
new bookings; publishing requires complete pricing.

## Booking flow

1. `GET /api/events/available` — list bookable events.
2. `GET /api/events/{id}` — event detail (hall, pricing).
3. Seated → `GET /api/events/{id}/seats` for the live map; non-seated → `GET /api/events/{id}/availability`.
4. `POST /api/bookings` — reserve ≤ 2 seats (or N GA tickets). Returns the hold and its `expiresAt`.
5. `POST /api/bookings/{id}/payment` — fake payment; on success the hold becomes a confirmed booking.
6. Unpaid holds auto-release after the configured window (default 10 min).

## How double-booking is prevented (the important part)

Every held/booked seat is a `booking_seat` row. The table has a **stored generated column**:

```sql
active_lock = CASE WHEN status IN ('HELD','BOOKED')
                   THEN CONCAT(event_id, '-', seat_id) ELSE NULL END
```

with a **unique index** over it. While a seat is actively HELD or BOOKED, `active_lock` holds
`event_id-seat_id`; once the row is RELEASED it becomes `NULL`. MySQL allows many `NULL`s in a
unique index, so released/expired holds never collide — but any two *active* holds on the same
seat for the same event violate the constraint. **The database itself rejects the second
booking**, even under concurrent requests; the application doesn't rely on check-then-act logic.

- The service does a friendly pre-check and returns `409 Conflict` for already-taken seats.
- If a race slips past the pre-check, the unique-index violation on insert is caught and also
  returned as `409`.
- Expired holds are released two ways: lazily at booking time for the same event, and by a
  background **sweeper** (`app.reservation.sweep-interval`) for idle events.
- General-admission (non-seated) events can't be oversold: capacity is checked under a
  pessimistic row lock on the event.

The same seat is independent **across different events** in a hall — the lock is per
`(event, seat)`, so a seat can be sold for tonight's show and tomorrow's show separately.

A concurrency test (`ConcurrentBookingIntegrationTest`) fires 8 simultaneous bookings at one
seat and asserts exactly one wins.

## Running it

### Option A — everything in Docker (only Docker required)

No host JDK or Maven needed; the image builds the app itself.

```bash
docker compose up --build
```

This starts MySQL and the app together (with demo data). The app is on
`http://localhost:8080`. Stop with `Ctrl+C`, or run detached with `-d`.

### Option B — run the app on the host

Prerequisites: JDK 21, Maven, Docker (for MySQL only).

```bash
# 1. Start just MySQL
docker compose up -d mysql

# 2. Run the app (optionally seed demo data)
SEED_DEMO_DATA=true mvn spring-boot:run
```

Either way, Flyway creates the schema on first boot. With `SEED_DEMO_DATA=true` you get an
organizer, a seated hall + a general-admission hall, and two published events to play with.
See `api-examples.http` for ready-to-run requests.

Database connection is configurable via env vars: `DB_HOST`, `DB_PORT`, `DB_NAME`,
`DB_USERNAME`, `DB_PASSWORD` (defaults target the bundled docker-compose MySQL).

## Caching

The two live-availability reads — `GET /events/{id}/seats` and `GET /events/{id}/availability` —
are the hottest endpoints (the mobile app polls them). They're cached in Redis keyed by event id
with a short TTL (`app.reservation.cache-ttl`, default 2s), so a burst of identical polls collapses
into one database read every couple of seconds. The cache is evicted for an event the moment a
booking is created, paid, or cancelled — after the transaction commits, so a concurrent read can't
repopulate it with pre-commit state.

Two deliberate properties:

- **Correctness never depends on the cache.** A stale entry can only affect what the UI *shows*; the
  actual booking still goes through the MySQL unique index, so a just-taken seat simply yields a
  `409 Conflict` on the write. MySQL stays the single source of truth.
- **Redis is best-effort.** If Redis is unavailable, cache reads/writes/evicts are logged and
  skipped, and every request falls back to the database — the app keeps working, just without the
  read-offload.

## Payments & reconciliation

You can't wrap "charge a card" and "commit to the database" in one atomic transaction — they're
two systems (the dual-write problem). So payment is deliberately staged, and every charge is
recorded durably:

1. **`beginPayment`** (short txn) — validate the hold, persist a `Payment` row as `INITIATED`.
2. **`charge`** (no txn open) — call the gateway with a stable idempotency key (`booking-{id}`),
   so a retry never double-charges.
3. **`applyPaymentResult`** (short txn) — record the outcome and confirm the booking.

If step 3 never runs (crash, DB failure, lost response), the `Payment` stays `INITIATED` and a
**reconciliation job** picks it up: it asks the gateway what actually happened and then either
**confirms** the booking (charge succeeded, hold still valid) or **refunds** the charge (a
compensating action, when the seats are gone). The idempotency key is what makes this safe —
the charge can be looked up and re-applied without ever double-charging. Payment states:
`INITIATED → SUCCEEDED | FAILED | REFUNDED`.

The gateway is still a `FakePaymentGateway` (always approves, with an in-memory ledger so
`lookup`/`refund` behave like a real provider). Swap in a real `PaymentGateway` — ideally
webhook-driven for the final confirmation — without touching the reservation core.

## API docs (Swagger)

Interactive OpenAPI docs are served by SpringDoc once the app is running:

- Swagger UI: `http://localhost:8090/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8090/v3/api-docs`

(Use whatever host port you mapped; default is 8090.)

## Configuration

| Property                             | Default | Meaning |
|--------------------------------------|---------|---------|
| `app.reservation.hold-duration`      | `PT10M` | How long a seat stays reserved before auto-release. |
| `app.reservation.max-seats-per-booking` | `2`  | Max seats/tickets per booking. |
| `app.reservation.sweep-interval`     | `PT30S` | How often the sweeper releases expired holds. |
| `app.reservation.cache-ttl`          | `PT2S`  | How long availability reads are cached in Redis. |
| `app.seed-demo-data`                 | `false` | Seed the demo catalog on startup. |

Redis connection is configurable via `REDIS_HOST` / `REDIS_PORT` (default `localhost:6379`;
docker-compose points the app at the bundled `redis` service).

## Testing

```bash
mvn test
```

The integration test uses **Testcontainers** to spin up a real MySQL, so Docker must be
available and able to pull `mysql:8.0`.

## API reference

**Catalog (admin)**

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/organizers` | Create organizer |
| GET  | `/api/organizers` · `/api/organizers/{id}` | List / get |
| POST | `/api/halls` | Create hall (generates seats if seated) |
| GET  | `/api/halls` · `/api/halls/{id}` | List / get (detail includes seats) |
| POST | `/api/events` | Create event (DRAFT) |
| GET  | `/api/events` | List all events (admin) |
| PUT  | `/api/events/{id}/pricing` | Set per-seat-type pricing |
| PATCH| `/api/events/{id}/status` | Change status (publish/cancel/...) |

**Mobile / booking**

| Method | Path | Purpose |
|--------|------|---------|
| GET  | `/api/events/available` | Bookable events |
| GET  | `/api/events/{id}` | Event detail |
| GET  | `/api/events/{id}/seats` | Live seat map (seated) |
| GET  | `/api/events/{id}/availability` | Live capacity (non-seated) |
| POST | `/api/bookings` | Hold seats / tickets |
| GET  | `/api/bookings/{id}` | Booking status |
| POST | `/api/bookings/{id}/payment` | Fake payment → confirm |
| DELETE | `/api/bookings/{id}` | Release a pending hold |

## Notes & current scope

- **Authentication is intentionally deferred.** Bookings carry a `customerRef` string as a
  placeholder for the authenticated user; wire in Spring Security / JWT later without touching
  the reservation core.
- Payment is a `FakePaymentGateway` that always approves, but the surrounding flow is
  production-shaped: charge outside the transaction, a durable `Payment` record, and a
  reconciliation job that confirms-or-refunds in-doubt charges (see [Payments &
  reconciliation](#payments--reconciliation)).
- Seated events are bounded by their seats; per-event `maxCapacity` below the hall size is not
  strictly enforced for seated halls (seat availability is the practical cap).
