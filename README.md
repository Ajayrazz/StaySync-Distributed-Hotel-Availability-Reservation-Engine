# StaySync — Distributed Hotel Availability & Reservation Engine

> A **production-grade**, event-driven hotel booking backend inspired by Booking.com architecture — built with **Spring Boot 3.3**, **Redis Distributed Locks**, **Apache Kafka**, and **PostgreSQL** to solve real-world engineering problems like double-booking prevention, 15-minute checkout holds, geospatial search, and idempotent payment processing.

---

## 🏗️ Architecture Overview

```
Client (Browser / Mobile)
        │
        ▼
Spring Boot API (Security Filter + JWT)
        │
        ├──▶ Hotel & Search Controller → HotelService → PostgreSQL (Haversine Geo Query)
        │
        ├──▶ Inventory Controller → InventoryService → DailyInventory Table (Atomic SQL)
        │
        └──▶ Booking Controller
                    │
                    ├──▶ Redisson Distributed Lock (Redis)
                    ├──▶ InventoryService.holdInventory() → PostgreSQL ACID Tx
                    ├──▶ ReservationHold → Redis TTL (15 min)
                    └──▶ Kafka Producer → BookingConfirmedEvent
                                 │
                                 └──▶ Notification Consumer (Email / Invoice)
```

---

## 🚀 Tech Stack

| Technology | Version | Purpose |
|:---|:---|:---|
| **Java** | 17 LTS | Language |
| **Spring Boot** | 3.3.4 | Core framework |
| **PostgreSQL** | 16 | Primary RDBMS (ACID Transactions) |
| **Flyway** | 10.x | Database schema version control |
| **Redis + Redisson** | 7.2 / 3.35 | Distributed locks & 15-min cart holds |
| **Apache Kafka** | KRaft | Async event streaming |
| **Spring Security 6** | 6.x | Stateless JWT authentication |
| **Testcontainers** | 1.20 | Real DB/Redis/Kafka in integration tests |
| **Prometheus + Actuator** | 2.51 | Observability & metrics |
| **SpringDoc OpenAPI** | 2.6 | Swagger UI auto-documentation |
| **Lombok** | latest | Boilerplate reduction |

---

## ⚡ Key Engineering Problems Solved

### 1. The Double-Booking Problem
A 3-tier defense ensures zero overbooking under high concurrency:
- **Tier 1:** Redisson Distributed Lock with Watchdog (`lock:room_type:{id}:dates:{range}`)
- **Tier 2:** `@Version` Optimistic Locking on `DailyInventory` (JPA)
- **Tier 3:** `CHECK CONSTRAINT` in PostgreSQL (`reserved_count <= available_count`)

### 2. The 15-Minute Checkout Hold State Machine
```
[User Clicks Reserve]
        │
        ▼
[Acquire Redisson Lock] ──── FAIL ──▶ [Return 409 Conflict]
        │
      SUCCESS
        │
        ▼
[Increment reserved_count in DB] ──▶ [Create Redis Hold with 900s TTL]
        │
        ▼
[User pays within 15 min?]
        ├── YES ──▶ [CONFIRMED] → Kafka Event published
        └── NO  ──▶ [EXPIRED] → Compensating TX releases inventory
```

### 3. Idempotent Payment (No Double Charges)
Every checkout request carries `X-Idempotency-Key: <UUID>`.
Redis atomic `SET key val NX EX 1800` ensures duplicate requests return cached response without re-charging.

### 4. Transactional Outbox Pattern (Reliable Kafka Publishing)
Booking events are written to an `outbox_events` PostgreSQL table **within the same DB transaction** as the booking. A background worker publishes to Kafka and marks the record processed, guaranteeing at-least-once delivery even if Kafka is temporarily unavailable.

### 5. Geospatial Hotel Search (Haversine Formula)
```sql
SELECT * FROM hotels h
WHERE (6371 * acos(cos(radians(:lat)) * cos(radians(h.latitude)) * ...)) <= :radiusKm
ORDER BY distance_km ASC
```
Find hotels within any radius without PostGIS extension dependency, using pure SQL.

---

## 📁 Project Structure

```
StaySync-Engine/
├── src/main/java/com/staysync/
│   ├── StaySyncApplication.java          # @SpringBootApplication entry point
│   ├── config/
│   │   ├── KafkaTopicConfig.java          # Kafka topic bean definitions
│   │   ├── OpenApiConfig.java             # Swagger UI configuration
│   │   └── SecurityConfig.java            # Spring Security 6 filter chain
│   ├── common/
│   │   ├── ApiResponse.java               # Standard API envelope wrapper
│   │   ├── GlobalExceptionHandler.java    # @RestControllerAdvice error handling
│   │   └── exception/                     # Custom domain exceptions
│   ├── hotel/                             # Hotel & Room Catalog Module
│   │   ├── model/Hotel.java
│   │   ├── model/RoomType.java
│   │   ├── repository/HotelRepository.java
│   │   ├── service/HotelService.java
│   │   ├── controller/HotelController.java
│   │   └── dto/                           # Java Records (immutable DTOs)
│   ├── inventory/                         # Daily Availability Engine Module
│   │   ├── model/DailyInventory.java      # @Version Optimistic Locking
│   │   ├── repository/DailyInventoryRepository.java
│   │   ├── service/InventoryService.java  # Atomic reserve/release logic
│   │   └── controller/InventoryController.java
│   └── booking/                           # Reservation State Machine Module
│       ├── model/Booking.java
│       ├── model/ReservationHold.java
│       ├── model/BookingStatus.java
│       └── repository/BookingRepository.java
├── src/main/resources/
│   ├── application.yml                   # Full app configuration
│   └── db/migration/
│       ├── V1__init_schema.sql            # Tables, constraints, indexes
│       └── V2__seed_demo_data.sql         # 3 world-class hotels + 90-day inventory
├── infra/
│   └── prometheus.yml                    # Prometheus scrape config
├── docker-compose.yml                    # PostgreSQL, Redis, Kafka, Prometheus
├── docs/
│   ├── 01_ARCHITECTURE_AND_DESIGN.md
│   ├── 02_CONCURRENCY_LOCKING_AND_OVERBOOKING.md
│   ├── 03_TECH_STACK_AND_TRADE_OFFS.md
│   └── 04_SENIOR_INTERVIEW_QA_CHEATSHEET.md
└── pom.xml
```

---

## 🛠️ Local Setup & Running

### Prerequisites
- Java 17+
- Maven 3.9+
- Docker & Docker Compose

### 1. Start Infrastructure
```bash
docker-compose up -d
```
This starts PostgreSQL (port 5432), Redis (6379), Kafka (9092), and Prometheus (9090).

### 2. Run the Application
```bash
./mvnw spring-boot:run
```
Flyway automatically applies `V1__init_schema.sql` and `V2__seed_demo_data.sql` on startup.

### 3. Access the APIs
| Resource | URL |
|:---|:---|
| **Swagger UI** | http://localhost:8080/swagger-ui.html |
| **Health Check** | http://localhost:8080/actuator/health |
| **Prometheus Metrics** | http://localhost:8080/actuator/prometheus |

---

## 📡 API Endpoints

### Hotel Discovery
```http
GET  /api/v1/hotels                          # List all hotels
GET  /api/v1/hotels/{id}                     # Get hotel by ID
GET  /api/v1/hotels/search/city?city=Paris   # Search by city
GET  /api/v1/hotels/search/nearby?lat=48.87&lon=2.33&radiusKm=5  # Geospatial search
```

### Room Availability
```http
GET  /api/v1/inventory/check?roomTypeId=1&checkIn=2026-12-20&checkOut=2026-12-25
```

---

## 📚 Documentation

Full interview preparation documentation is in the `/docs` folder:

| Document | Contents |
|:---|:---|
| [`01_ARCHITECTURE_AND_DESIGN.md`](docs/01_ARCHITECTURE_AND_DESIGN.md) | System diagrams, domain models, state machines |
| [`02_CONCURRENCY_LOCKING_AND_OVERBOOKING.md`](docs/02_CONCURRENCY_LOCKING_AND_OVERBOOKING.md) | Deep dive into Redisson, Optimistic Locking, Idempotency |
| [`03_TECH_STACK_AND_TRADE_OFFS.md`](docs/03_TECH_STACK_AND_TRADE_OFFS.md) | What/Why/Alternatives for every technology |
| [`04_SENIOR_INTERVIEW_QA_CHEATSHEET.md`](docs/04_SENIOR_INTERVIEW_QA_CHEATSHEET.md) | Real L4/L5 interview Q&A with model answers |

---

## 💡 Interview Talking Points

> "This project solves the **overbooking problem** — one of the hardest challenges in any reservation platform — using a 3-tier defense: Redisson distributed locks for fast pre-filtering, JPA `@Version` optimistic locking as a database-level safety net, and PostgreSQL `CHECK CONSTRAINTS` as the final physical barrier. I tested all concurrent scenarios using **Testcontainers**, which spins up real PostgreSQL and Redis instances, because H2 doesn't support PostgreSQL's constraint semantics."

---

## 📊 Observability

| Metric | How to View |
|:---|:---|
| HTTP Request Latency (p95/p99) | Prometheus + `http_server_requests_seconds` |
| HikariCP Active Connections | `/actuator/metrics/hikaricp.connections.active` |
| JVM Heap Memory | `/actuator/metrics/jvm.memory.used` |
| Custom Booking Metrics | `/actuator/prometheus` (search `staysync_`) |
