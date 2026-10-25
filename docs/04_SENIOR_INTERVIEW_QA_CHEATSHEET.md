# Senior Backend Interview Q&A Cheatsheet: StaySync

> **Purpose:** Read this before your technical screen and system design rounds. These questions are modeled directly after real L4/L5/Senior interviews at companies like Booking.com, Uber, Amazon, and fintech firms.

---

### Q1: "Walk me through the lifecycle of a booking from user search to final confirmation."
**Model Answer:**
> "Sure. The workflow is divided into three key stages:
> 1. **Discovery:** The user searches for hotels in a city or within a geographic radius. We query PostgreSQL using PostGIS spatial indexing (`ST_DWithin`) and join with our `DailyInventory` table to ensure that for all requested stay dates, `(available_count - reserved_count) >= requested_rooms`.
> 2. **Temporary 15-Minute Reservation Hold:** When the user clicks 'Reserve', we don't immediately confirm the booking because payment hasn't occurred. To prevent race conditions, we acquire a Redisson distributed lock keyed on `(roomTypeId + dates)`. Inside the critical section, we atomically increment `reserved_count` on the daily inventory rows and create a temporary hold record in Redis with a 15-minute TTL.
> 3. **Checkout & Confirmation:** The user enters payment info. The request passes an `X-Idempotency-Key` header to prevent double charges. Once the payment gateway confirms funds, we update the booking status to `CONFIRMED` in PostgreSQL, delete the Redis hold key, and publish a `BookingConfirmedEvent` to Apache Kafka. Kafka consumers asynchronously handle sending the confirmation email and invoice generation."

---

### Q2: "Why use Redisson distributed locks when PostgreSQL already has `SELECT ... FOR UPDATE`?"
**Model Answer:**
> "That's a classic database scalability trade-off. While `SELECT ... FOR UPDATE` provides ACID row-level locking, holding relational database locks across high-volume traffic quickly exhausts the database connection pool (HikariCP).
> 
> If 5,000 users are competing for rooms, 5,000 threads will hold database connections open waiting for locks, starving read queries and crashing the app.
> 
> By placing **Redisson** in front of the database, we intercept and serialize concurrent contention in-memory in Redis. Only the thread that successfully acquires the lock proceeds to interact with the database; the other threads can fail fast or back off immediately without touching the relational connection pool. Furthermore, we back this up with **Optimistic Locking (`@Version`)** in the database as a secondary defense layer."

---

### Q3: "What happens if a user starts checkout, holds the room, and closes their laptop?"
**Model Answer:**
> "In StaySync, every reservation hold is given a strict 15-minute Time-To-Live (TTL). When the hold is created, the hold record is written to Redis with a TTL of 900 seconds. 
> 
> We handle expiration through two complementary mechanisms:
> 1. **Reactive Check:** If any user or process checks the booking status after the 15-minute window, the engine checks `expiresAt < Instant.now()` and immediately transitions the status to `EXPIRED`.
> 2. **Proactive Reconciliation:** We have a lightweight scheduled task / Redis expired-event listener that queries unconfirmed holds older than 15 minutes, marks them `EXPIRED`, and executes a compensating transaction that decrements `reserved_count` in PostgreSQL, instantly making the room available again."

---

### Q4: "What happens if the application crashes while holding a Redisson lock?"
**Model Answer:**
> "If an application instance crashes while holding a lock, standard Redis `SETNX` without expiration would cause a permanent deadlock. 
> 
> Redisson solves this with its **Watchdog mechanism**:
> When a lock is acquired without specifying an explicit lease time, Redisson sets a default lock lease time (e.g., 30 seconds) and starts a background thread (the watchdog) that extends the lease every 10 seconds as long as the holding thread is alive.
> If the JVM crashes or suffers a hardware outage, the watchdog dies with it. Redis then automatically expires the lock after the remaining lease time (at most 30 seconds), preventing system deadlocks."

---

### Q5: "How do you guarantee that a user isn't charged twice if they click the 'Pay' button multiple times?"
**Model Answer:**
> "We implement **API Idempotency** using client-generated Idempotency Keys (`X-Idempotency-Key`).
> 
> When the checkout page renders, the frontend generates a unique UUID. When the user submits payment:
> 1. The backend performs an atomic Redis command: `SET idempotency:{uuid} 'IN_PROGRESS' NX EX 1800`.
> 2. If Redis returns `false`, another request with this exact key is already being processed or has finished. We reject the duplicate request with HTTP 409 Conflict or return the previous cached response.
> 3. Once the payment succeeds, we update the Redis value to store the successful booking reference. Subsequent clicks simply read the cached booking confirmation without hitting the payment gateway again."

---

### Q6: "How do you handle the Dual-Write problem between PostgreSQL and Kafka?"
**Model Answer:**
> "The dual-write problem occurs when you save a booking to PostgreSQL and then publish an event to Kafka. If Kafka is down or the network fails between the DB commit and Kafka send, the database has the booking, but downstream services (email, invoicing) never know about it.
> 
> In StaySync, we design for the **Transactional Outbox Pattern**:
> Instead of publishing directly to Kafka inside the database transaction, we write the event into an `outbox_events` table within the **same** local PostgreSQL transaction that saves the booking.
> A background worker (using Spring Scheduled or Debezium Change Data Capture) reads from `outbox_events` and publishes to Kafka. Once Kafka acknowledges the write (`acks=all`), the outbox record is marked processed. This guarantees **at-least-once delivery**."

---

### Q7: "Why did you use Flyway instead of Hibernate's `ddl-auto=update`?"
**Model Answer:**
> "`ddl-auto=update` is dangerous in production because Hibernate tries to infer schema changes. It will never drop obsolete columns or rename tables safely, and it cannot properly manage indexes, spatial PostGIS column constraints, or complex triggers.
> 
> Flyway gives us **version-controlled, immutable, repeatable SQL migrations**. Every migration file (e.g., `V1__init_schema.sql`, `V2__add_spatial_index.sql`) is reviewed in code review, tested in CI/CD using Testcontainers, and executed predictably across all environments without manual DBA intervention."

---

### Q8: "How do you test this system reliably in CI/CD?"
**Model Answer:**
> "We strictly avoid mocking infrastructure with H2 or mock message brokers. H2 doesn't support PostgreSQL's PostGIS spatial extensions, exclusion constraints, or exact locking semantics.
> 
> Instead, we use **Testcontainers**. In our JUnit 5 integration test suite, Testcontainers spins up real, isolated Docker containers for PostgreSQL, Redis, and Kafka. Tests execute against real instances, validating SQL syntax, Flyway migrations, Redis distributed locks, and Kafka consumer offsets before any code can be merged."
