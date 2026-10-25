# StaySync — The Complete "Why We Built This" Guide

> **Who this document is for:** Anyone reading this repository for the first time —  
> a recruiter, a hiring manager, or you yourself, preparing for a technical interview.  
> This document answers the question every interviewer eventually asks:  
> **"Walk me through your project. Why did you build it this way?"**

---

## Part 1: The Problem We Are Solving

### Start With the Real World

Open Booking.com. Search for a hotel in Paris for New Year's Eve.

You see a result: **"Prestige Vendôme Room — Only 1 room left!"**

You click **"Reserve."**

At the exact same moment, someone in Tokyo is also clicking **"Reserve"** on that same room.

---

**What happens in a naive, tutorial-style CRUD application?**

```
User A reads DB:  available_rooms = 1  ✅
User B reads DB:  available_rooms = 1  ✅  (at the same millisecond)

User A writes:    UPDATE rooms SET booked = true WHERE id = 10;  ✅
User B writes:    UPDATE rooms SET booked = true WHERE id = 10;  ✅  (overbooked!)

Both users get a "Booking Confirmed" email.
The hotel has 1 room. Two guests show up.
The hotel walks a guest to a competitor property, pays compensation, 
and gets a 1-star review that destroys their rating.
```

This is called a **Race Condition**. It is one of the most dangerous and costly bugs in any system that handles money, inventory, or reservations.

**StaySync was built specifically to solve this.**

---

## Part 2: Why This Project Matters for Your Resume

Most developers build the same projects:
- Todo List API
- Blog REST API
- Student Management System
- Simple E-commerce CRUD

**What's wrong with those?**

They demonstrate that you know Spring Boot syntax. They do not demonstrate that you understand:

- What happens when two users hit the same endpoint simultaneously
- How to prevent data corruption under concurrent load
- How to design systems that stay correct even when servers crash mid-transaction
- How to build backends that scale horizontally without breaking

**StaySync demonstrates all of these things.** It is the difference between a developer who knows frameworks and an engineer who understands systems.

When a hiring manager at Booking.com, Uber, or any fintech sees this project, they see evidence that you have thought about problems they deal with every single day in production.

---

## Part 3: The Core Problems Solved (One by One)

---

### Problem 1: Double-Booking (The Overbooking Bug)

**What is it?**

Two users simultaneously request the last available room. Without protection, both succeed, resulting in two confirmed bookings for one physical room.

**Why is it hard?**

It only happens under concurrent load. It works fine in local testing because you test one request at a time. It silently destroys data in production at the worst possible moment — peak traffic, like New Year's Eve or a big concert.

**How StaySync solves it (3-Tier Defense):**

**Tier 1 — Redisson Distributed Lock (Redis)**

Before any database interaction, we acquire a Redis lock scoped to the specific room and date range.

```
Lock Key: "lock:room_type:10:dates:2026-12-31_2027-01-01"
```

Only one thread across all running application servers can hold this lock at one time. Every other thread either waits up to 5 seconds or fails fast with a clear error message.

*Why Redis and not a Java `synchronized` block?*

A Java `synchronized` block only locks within a single JVM instance. In a real deployment, you run 3, 5, or 10 application servers simultaneously. Thread A on Server 1 and Thread B on Server 2 have completely separate JVM memory spaces. A `synchronized` block on Server 1 has no effect on what Server 2 does.

Redis is external to all servers. When Server 1 acquires a Redis lock, Server 2 physically cannot acquire the same lock until Server 1 releases it.

**Tier 2 — Optimistic Locking with `@Version` (PostgreSQL)**

Every row in the `daily_inventory` table has a `version` column. When we update a row, we include the version number we read in the WHERE clause:

```sql
UPDATE daily_inventory 
SET reserved_count = reserved_count + 1, version = version + 1
WHERE id = :id AND version = :versionWeRead;
```

If another thread updated the row between our read and our write, the version number changes and our UPDATE matches 0 rows. We detect this and either retry or return a failure.

This is the safety net in case the distributed lock is somehow bypassed.

**Tier 3 — Database CHECK Constraint (Final Physical Barrier)**

```sql
CONSTRAINT chk_reserved_available CHECK (reserved_count <= available_count)
```

Even if Tier 1 and Tier 2 both somehow fail, the database engine itself will physically reject any INSERT or UPDATE that would result in more reservations than available rooms. This is the last line of defense — an invariant enforced at the storage level that no application code can accidentally bypass.

---

### Problem 2: The Checkout Limbo (The 15-Minute Hold)

**What is it?**

When you click "Reserve" on Booking.com, the room does not immediately become permanently booked. It is held for you while you enter your payment details. But if you close your browser and walk away, the room cannot be locked forever — other potential guests need to be able to book it.

**Why is it hard?**

You need to:
1. Show the room as "unavailable" to other users during checkout (to prevent double-booking)
2. Automatically release the room if the user abandons checkout after 15 minutes
3. Handle the case where the server crashes mid-checkout (the room must still be released eventually)
4. Not block the user's thread for 15 minutes (you return immediately and release in the background)

**How StaySync solves it:**

When a user initiates a hold:

1. **Atomic DB Update:** We increment `reserved_count` in `daily_inventory` within a `@Transactional` boundary. This immediately makes the room "appear unavailable" to new availability checks.

2. **Redis TTL Hold Key:** We store hold metadata in Redis with a 15-minute expiry:
   ```
   Key:   hold:meta:HOLD-A3F9X1
   Value: roomTypeId|userId|checkIn|checkOut|rooms
   TTL:   900 seconds (15 minutes)
   ```

3. **PostgreSQL Hold Record:** We also persist a `ReservationHold` row in PostgreSQL with an `expiresAt` timestamp. PostgreSQL is the source of truth; Redis is the fast lookup cache.

4. **HoldExpiryScheduler:** A Spring `@Scheduled` task runs every 60 seconds, queries for `ACTIVE` holds past their `expiresAt`, and for each one:
   - Decrements `reserved_count` back (compensating transaction — the "undo" operation)
   - Marks the hold as `EXPIRED`
   - Publishes a `HoldExpiredEvent` to Kafka for cart abandonment analytics

The frontend receives an `expiresAt` timestamp in the hold response and displays a countdown timer: *"14:32 remaining to complete your booking."*

---

### Problem 3: Double Charges (Idempotency)

**What is it?**

A user clicks "Confirm & Pay." Their internet connection drops. The request might have reached the server or might not have. They click the button again. And again, anxiously.

Without protection, the payment gateway charges their credit card 3 times for 1 booking.

**Why is it hard?**

HTTP is not inherently idempotent for POST requests. You cannot tell if a retry is the same intent as the original request or a genuinely new request.

**How StaySync solves it:**

Every checkout page generates a unique `UUID` called an **Idempotency Key** when it loads. This key is sent in every confirmation request for that checkout session.

When the server receives a confirmation request:

```
Step 1: Try to atomically SET a Redis key:
        SET "idempotency:confirm:{uuid}" "PROCESSING" NX EX 86400

Step 2: If SET returns false → duplicate request detected.
        Look up the already-confirmed booking and return it.
        Do NOT charge the payment gateway again.

Step 3: If SET returns true → first time we've seen this key.
        Process payment and confirm booking.
        Update the Redis key value to "CONFIRMED:{bookingReference}".
```

The user may click 10 times. Only 1 payment is processed. All 10 responses return the same booking confirmation.

---

### Problem 4: Tight Coupling of Critical Path (Event-Driven Architecture)

**What is it?**

After a booking is confirmed, many things need to happen:
- Send a confirmation email to the guest
- Generate and email a PDF invoice
- Notify the hotel's Property Management System (PMS)
- Award loyalty points to the guest's account
- Update revenue analytics dashboards

In a naive implementation, you would make synchronous HTTP calls to all these services from inside the booking confirmation method. 

**Why is this a problem?**

If the email service is having a slow day and takes 8 seconds to respond, your booking API now takes 8 seconds to respond. If the email service is completely down, your booking API returns a 500 error, and the user's booking fails even though the payment was charged.

You have created a **single point of failure** where one peripheral service (email) can take down the entire booking system.

**How StaySync solves it with Kafka:**

After saving the booking to PostgreSQL, we publish one event to Apache Kafka:

```java
BookingConfirmedEvent event = BookingConfirmedEvent.builder()
        .bookingReference(bookingReference)
        .userId(hold.getUserId())
        .hotelName(hotel.getName())
        .totalPrice(totalPrice)
        // ... etc
        .build();

eventPublisher.publishBookingConfirmed(event);  // Non-blocking, returns immediately
```

This call returns in milliseconds. The booking API responds to the user instantly.

Kafka stores the event durably on disk. Separate consumer services read from the Kafka topic at their own pace:
- The email service reads the event and sends the email
- The invoice service reads the event and generates the PDF
- The analytics service reads the event and updates dashboards

If the email service crashes, it restarts, reads the event from Kafka (because Kafka retains events based on offset), and sends the email. The guest eventually gets their email. The booking was never at risk.

This is called **temporal decoupling**: the producer (BookingService) and the consumers (EmailService, AnalyticsService) do not need to be running at the same time.

---

### Problem 5: Testing Lies (Testcontainers vs H2)

**What is it?**

Most Spring Boot tutorials use an **H2 in-memory database** for tests. This is convenient but dangerously misleading.

H2 is a completely different database from PostgreSQL. It does not support:
- PostgreSQL's `CHECK` constraints (the ones preventing overbooking)
- PostgreSQL-specific date functions used in our availability queries
- The exact locking semantics of PostgreSQL transactions

Tests written against H2 pass confidently, and then the code fails in production against real PostgreSQL.

**How StaySync solves it:**

We use **Testcontainers**. During `mvn test`, it pulls real Docker images of PostgreSQL and Redis, starts them as isolated containers, runs all migrations via Flyway, and executes the tests against real databases.

Our `ConcurrentBookingIntegrationTest` is the most important test:

```
Given: 1 room available for December 31st
When:  10 threads are released simultaneously using CountDownLatch
Then:  Exactly 1 booking succeeds
       Exactly 9 fail with RoomUnavailableException
       database.reserved_count = 1 (verified in real PostgreSQL)
```

This test cannot be faked. If the distributed lock or the optimistic locking breaks, this test fails loudly. If it passes, we have proven — with real infrastructure — that the system handles concurrent load correctly.

---

## Part 4: Architectural Decisions Explained

### Why a Modular Monolith, Not Microservices?

This question comes up in every senior interview.

We organized the code into distinct modules (`hotel`, `inventory`, `booking`) with clear boundaries, each with its own models, repositories, services, and controllers. But they all run in the same JVM process.

**Why not separate them into independent microservices from the start?**

1. **Network latency:** When BookingService needs to call InventoryService to hold a room, in a monolith this is a method call taking nanoseconds. In microservices, this is a network HTTP call taking milliseconds, introducing latency and the possibility of network failures.

2. **Distributed transactions:** The hold operation must atomically update inventory AND create a hold record. In a monolith, `@Transactional` handles this trivially. In microservices, you need a distributed transaction protocol (Saga pattern) with compensating transactions — significantly more complex.

3. **Operational overhead:** Each microservice needs its own Kubernetes deployment, health checks, service discovery, circuit breakers, and distributed tracing. This complexity is only justified once the team is large enough that different teams own different services.

The modular monolith gives us clean architecture and domain separation today, with the ability to extract services into microservices later when the team or traffic demands it. The domain boundaries are already drawn.

### Why `daily_inventory` Table Instead of Computing from Bookings?

This is a classic database design interview question.

**The naive approach:** To check if a room is available for Oct 20–25, query all existing bookings for that room and see if any overlap with those dates.

```sql
SELECT COUNT(*) FROM bookings 
WHERE room_type_id = 10 
  AND check_out_date > '2026-10-20' 
  AND check_in_date < '2026-10-25'
  AND status = 'CONFIRMED';
```

**The problem:** As the bookings table grows to millions of rows, this query becomes slow. Every availability search requires scanning and filtering through all historical bookings. With thousands of concurrent searches, this creates enormous database load.

**The StaySync approach:** Pre-computed `daily_inventory` rows.

```sql
-- Checking availability for 5 nights
SELECT MIN(available_count - reserved_count) 
FROM daily_inventory
WHERE room_type_id = 10 
  AND calendar_date BETWEEN '2026-10-20' AND '2026-10-24';
```

This queries at most 5 rows (one per night), regardless of how many historical bookings exist. With a database index on `(room_type_id, calendar_date)`, this is a microsecond lookup.

The trade-off: we need to keep `daily_inventory` in sync with bookings. We do this through atomic SQL updates (no separate sync job required), which is simpler and more reliable.

### Why Flyway for Database Migrations?

Every single time you deploy code, the database schema must match. Flyway gives us **version-controlled database changes**.

Every schema change is a numbered SQL file:
- `V1__init_schema.sql` — creates all tables
- `V2__seed_demo_data.sql` — seeds realistic hotel data
- `V3__add_geospatial_index.sql` — (future) adds PostGIS spatial index

Flyway tracks which files have been applied in a `flyway_schema_history` table. On every application startup, it checks for new migration files and applies them in order.

**Why not `spring.jpa.hibernate.ddl-auto=update`?**

Hibernate's `ddl-auto=update` is strictly forbidden in production because:
- It never drops columns (schema drift accumulates silently)
- It cannot manage indexes, constraints, or stored procedures
- It runs differently across different Hibernate versions
- It cannot be reviewed in code review or rolled back

Flyway migrations are reviewed, tested, and repeatable. They are code — not magic.

---

## Part 5: How Each File Contributes (The Dependency Chain)

Understanding how the pieces connect is crucial for explaining the project coherently:

```
HTTP Request (POST /api/v1/bookings/hold)
        │
        ▼
BookingController.initiateHold()
        │  Validates @Valid HoldRequest (Bean Validation)
        │
        ▼
BookingService.initiateHold()
        │  1. roomTypeRepository.findById()  → Validates room exists
        │  2. redissonClient.getLock()       → Gets Redisson lock reference
        │  3. lock.tryLock(5s, watchdog)     → ACQUIRES distributed lock
        │     ─── Critical Section Begins ───
        │  4. inventoryService.holdInventory()
        │         │
        │         ▼
        │     DailyInventoryRepository.reserveRoomsAtomic()
        │         → SQL: UPDATE daily_inventory SET reserved_count = reserved_count + 1
        │               WHERE room_type_id = ? AND calendar_date = ? 
        │               AND (available_count - reserved_count) >= 1
        │         → Returns 0 if no rows matched → throws RoomUnavailableException
        │
        │  5. ReservationHoldRepository.save()  → Persist hold in PostgreSQL
        │  6. redisTemplate.set(holdKey, meta, 15min TTL) → Redis hold cache
        │     ─── Critical Section Ends ───
        │  7. lock.unlock()  → Release Redisson lock
        │
        ▼
HoldResponse (holdId, expiresAt, estimatedPrice)
        │
        ▼
HTTP Response 201 Created
```

Then 15 minutes later, if not confirmed:

```
HoldExpiryScheduler (every 60 seconds)
        │
        ▼
ReservationHoldRepository.findExpiredActiveHolds(now)
        │
        ▼
InventoryService.releaseInventory()
        → SQL: UPDATE daily_inventory SET reserved_count = reserved_count - 1
        │
        ▼
ReservationHoldRepository.bulkExpireHolds()
        → SQL: UPDATE reservation_holds SET status = 'EXPIRED'
        │
        ▼
BookingEventPublisher.publishHoldExpired()
        → Kafka: staysync.booking.hold.expired topic
        │
        ▼
NotificationEventConsumer.handleHoldExpired()
        → Records cart abandonment analytics
```

---

## Part 6: What This Project Shows About You as an Engineer

When you present StaySync in an interview, you are demonstrating that you think about:

| Concern | How StaySync Addresses It |
|:---|:---|
| **Correctness under concurrency** | Redisson locks + Optimistic locking + DB constraints |
| **Data consistency** | `@Transactional` boundaries, atomic SQL updates, compensating transactions |
| **System resilience** | Kafka decoupling, HoldExpiryScheduler, idempotency keys |
| **Observability** | Custom Micrometer counters, Actuator endpoints, Prometheus scraping |
| **Testability** | Testcontainers (real infrastructure), not H2 mocks |
| **Schema management** | Flyway migrations, `ddl-auto=validate` (never `create` or `update`) |
| **API design** | Standardized `ApiResponse<T>` envelope, human-readable booking references |
| **Separation of concerns** | Modular domain packages, each with its own model/repo/service/controller |
| **Documentation** | Every design decision is explained with trade-offs and alternatives |

---

## Part 7: The One Paragraph That Wins Interviews

> *"StaySync is a hotel reservation engine that solves the hardest problems in booking platforms — double-booking prevention and concurrent inventory management. I implemented a 3-tier defense: a Redisson distributed lock prevents concurrent threads from even reaching the database for the same room and date range; JPA Optimistic Locking with `@Version` provides a database-level safety net; and a PostgreSQL CHECK constraint makes overbooking physically impossible. I modeled the checkout as a 15-minute state machine — the room is held in a PENDING state, tracked with a Redis TTL, and automatically released via a compensating transaction if the user abandons payment. All post-booking actions — email, invoicing, loyalty points — are fully decoupled through Apache Kafka events, so a slow email server never impacts booking confirmation latency. I validated all of this with Testcontainers integration tests that run against real PostgreSQL and Redis, proving the race condition is actually prevented under 10 concurrent threads — not just in a mocked environment."*

This paragraph covers:
- The **core problem** (double-booking)
- The **solution architecture** (3-tier defense)
- The **interesting engineering challenge** (15-min state machine)
- **Resilience thinking** (Kafka decoupling)
- **Proof of correctness** (Testcontainers concurrency test)

It takes 45 seconds to say and immediately signals a senior-level engineering mindset.
