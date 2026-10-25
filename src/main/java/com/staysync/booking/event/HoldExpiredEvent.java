package com.staysync.booking.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Domain Event: Published to Kafka when a 15-minute reservation hold expires.
 *
 * Flow:
 * HoldExpiryScheduler detects an ACTIVE hold past its expiresAt timestamp →
 * Releases reserved_count in PostgreSQL (compensating transaction) →
 * Publishes this event to Kafka (for analytics / audit trail) →
 * Marks ReservationHold.status = EXPIRED in PostgreSQL
 *
 * Why publish to Kafka on expiry?
 * The event serves as an audit trail. Analytics teams can calculate
 * "cart abandonment rates" by comparing HoldCreated vs HoldExpired events.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldExpiredEvent {

    private String eventId;
    private String holdId;
    private String userId;
    private Long roomTypeId;
    private LocalDate checkInDate;
    private LocalDate checkOutDate;

    @Builder.Default
    private Instant occurredAt = Instant.now();
}
