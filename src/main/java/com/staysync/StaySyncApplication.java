package com.staysync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * StaySync - Distributed Hotel Availability and Reservation Engine.
 * 
 * Features:
 * - High-concurrency room reservation with Redisson distributed locks.
 * - 15-minute temporary inventory holds with TTL state machine.
 * - PostGIS geospatial search and date-range overlap queries.
 * - Apache Kafka event streaming for asynchronous booking confirmation.
 * - Spring Boot 3.3+ with Java 17 and Flyway schema management.
 */
@SpringBootApplication
@EnableScheduling
public class StaySyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(StaySyncApplication.class, args);
    }
}
