# Tech Stack Deep Dive: "What, Why & Alternatives"

> **How to use this guide in interviews:** When an interviewer asks *"Why did you choose Kafka instead of RabbitMQ?"* or *"Why Flyway instead of letting Hibernate create tables?"*, this document gives you the exact technical justification.

---

## 1. Java 17 LTS

### What exactly is it?
Java 17 is the modern Long-Term Support (LTS) release of the Java programming language and runtime, introducing features like Records, Pattern Matching, Sealed Classes, and enhanced Garbage Collection (ZGC).

### Why are we using it in StaySync?
* **Spring Boot 3 Baseline:** Spring Boot 3 strictly requires Java 17 minimum. It drops support for Java 8 and 11.
* **Records for Immutable DTOs:** Request and Response payloads are modeled using Java `record` (e.g., `BookingRequest`, `HotelResponse`), eliminating boilerplate getters/setters/equals/hashCode while guaranteeing immutability.
* **Text Blocks:** Clean multi-line SQL queries and JSON templates in tests without ugly string concatenations.

### Alternatives Considered & Rejected:
* **Java 8 / 11:** Outdated, lack modern JVM performance optimizations and incompatible with Spring Framework 6.

---

## 2. Spring Boot 3.3+

### What exactly is it?
Spring Boot is an enterprise-grade Java framework that provides opinionated auto-configuration, an embedded web container (Tomcat), and a production-ready ecosystem for building microservices and web applications.

### Why are we using it in StaySync?
* **Enterprise Standard:** The #1 backend framework in banking, fintech, and travel enterprises.
* **Actuator & Observability:** Native integration with Micrometer and Prometheus for tracking request latencies, JVM memory, and thread states.
* **Spring Data & Security Integration:** First-class support for relational databases, Redis caching, Kafka streaming, and OAuth2/JWT security filters.

### Alternatives Considered & Rejected:
* **Quarkus / Micronaut:** Faster cold-start times (great for serverless AWS Lambda), but Spring Boot has a vastly larger ecosystem, better tooling, and higher enterprise market demand.

---

## 3. PostgreSQL 16 + PostGIS

### What exactly is it?
PostgreSQL is the world's most advanced open-source relational database (RDBMS), providing strict ACID compliance. **PostGIS** is an extension that adds spatial data types (Points, Polygons) and geospatial indexing (R-Tree / GiST).

### Why are we using it in StaySync?
1. **ACID Financial Correctness:** When updating inventory and creating booking records, we need atomic transactions. If any step fails, the entire transaction rolls back.
2. **Geospatial Hotel Search:** PostGIS allows us to query hotels within a radius (e.g., `ST_DWithin(location, ST_MakePoint(lon, lat), 5000)`) in milliseconds using spatial indexes (`GIST`).
3. **CHECK Constraints:** Database-level sanity check (`CONSTRAINT chk_inventory CHECK (reserved_count <= available_count)`), ensuring that even if application logic fails, the DB will physically reject an overbooking.

### Alternatives Considered & Rejected:
* **MongoDB (NoSQL Document Store):** Flexible schema, but lacks multi-document ACID guarantees needed for financial transactions and inventory reservations across multiple tables.
* **MySQL:** Good RDBMS, but its spatial/GIS capabilities and date-range support are significantly inferior to PostgreSQL + PostGIS.

---

## 4. Flyway Database Migrations

### What exactly is it?
Flyway is a version-control system for your relational database schema. It applies SQL migration scripts (`V1__init.sql`, `V2__add_index.sql`) in a strict sequential order and tracks execution in a `flyway_schema_history` metadata table.

### Why are we using it in StaySync?
* **Reproducibility:** Every developer, staging server, and production pipeline runs the exact same migrations automatically on boot.
* **Zero Hibernate Surprises:** In real production, `spring.jpa.hibernate.ddl-auto=update` is **strictly forbidden** because Hibernate can drop columns, misconfigure indexes, or lock tables unexpectedly.

### Alternatives Considered & Rejected:
* **Liquibase:** Uses XML/YAML/JSON formats for database changes. Flyway was chosen because it uses **pure, native SQL**, giving full control over PostgreSQL-specific features like PostGIS types and GiST indexes.
* **Hibernate `ddl-auto=create/update`:** Fine for toy tutorial projects; catastrophic for production.

---

## 5. Redis 7.2 + Redisson

### What exactly is it?
**Redis** is an ultra-fast in-memory key-value data store. **Redisson** is a Java client for Redis that implements distributed Java data structures and distributed synchronization primitives (e.g., `RLock`, `RSemaphore`).

### Why are we using it in StaySync?
1. **Redisson Distributed Locks:** Prevents two JVM application instances from processing the same booking request simultaneously. Redisson's **Watchdog** prevents premature lock release during long transactions.
2. **15-Minute Cart Reservation Holds:** Redis native Time-To-Live (`EXPIRE key 900`) allows us to hold rooms for 15 minutes without running heavy polling queries on the PostgreSQL database.
3. **Idempotency Cache:** Stores `X-Idempotency-Key` headers to stop duplicate charges when users double-click "Pay".

### Alternatives Considered & Rejected:
* **Raw `StringRedisTemplate` with `SETNX`:** Suffers from the "Lock Expiration during execution" and "Releasing someone else's lock" bugs. Redisson solves both out of the box with Lua scripts.
* **Hazelcast / Apache Ignite:** Full distributed in-memory data grids, but heavier to deploy and operate compared to lightweight Redis.

---

## 6. Apache Kafka (KRaft Mode)

### What exactly is it?
Apache Kafka is a distributed event streaming platform capable of handling trillions of events a day with persistent disk-backed storage, partitioned topics, and consumer groups.

### Why are we using it in StaySync?
* **Asynchronous Decoupling:** When a booking is confirmed, the core API should not wait for:
  * Sending confirmation emails/SMS
  * Sending analytics events
  * Generating PDF booking receipts
* **Reliability & Replayability:** If the Notification Service crashes, Kafka retains the events in the topic. When the service restarts, it resumes consuming where it left off (Offset tracking).
* **KRaft Mode:** Modern Kafka no longer requires Apache Zookeeper, drastically simplifying operations and container management.

### Alternatives Considered & Rejected:
* **RabbitMQ:** Excellent traditional AMQP message broker for task queues, but doesn't allow event replay and lacks the massive log-partitioned throughput of Kafka.
* **Synchronous REST Calls:** Making HTTP calls from BookingService to EmailService adds latency and introduces single points of failure (cascading outages).

---

## 7. Spring Security 6 & JWT (JSON Web Tokens)

### What exactly is it?
Spring Security is the standard authentication and authorization framework for Spring applications. JWT is a compact, URL-safe means of representing claims to be transferred between two parties.

### Why are we using it in StaySync?
* **Stateless Authentication:** Server does not store session IDs in memory. Each HTTP request contains `Authorization: Bearer <token>`, allowing the backend to scale horizontally without sticky sessions.
* **Role-Based Access Control (RBAC):**
  * `ROLE_GUEST`: Can search hotels, hold rooms, and view own bookings.
  * `ROLE_HOTEL_MANAGER`: Can update room rates, add inventory, and view hotel occupancy.
  * `ROLE_ADMIN`: Can manage platform fees and approve new hotels.

---

## 8. Testcontainers

### What exactly is it?
Testcontainers is a Java library that supports JUnit tests by providing lightweight, throwaway instances of Docker containers (PostgreSQL, Kafka, Redis).

### Why are we using it in StaySync?
* **The "H2 Database Lie":** Many developers test with in-memory H2 database. But H2 does NOT support PostGIS spatial functions, PostgreSQL date exclusion constraints, or PostgreSQL-specific SQL syntax. Tests pass on H2 and crash in production PostgreSQL.
* **Real Integration Tests:** Testcontainers spins up real PostgreSQL, real Redis, and real Kafka in Docker during `mvn test`, guaranteeing 100% fidelity to production.

---

## 9. Prometheus & Spring Boot Actuator

### What exactly is it?
Actuator exposes operational endpoints (`/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`). Prometheus periodically scrapes these metrics and stores them as time-series data.

### Why are we using it in StaySync?
* **Production Observability:** Hiring managers love engineers who monitor their systems. We track:
  * `staysync_booking_attempts_total` (counter)
  * `staysync_booking_failures_total` (counter, tagged with reasons: `SOLD_OUT`, `TIMEOUT`, `PAYMENT_DECLINED`)
  * `http_server_requests_seconds` (latency p95 / p99)
  * HikariCP active database connections.
