package com.staysync.booking.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Domain Event: Published to Kafka when a booking is confirmed.
 *
 * Why an event? After payment confirmation, several downstream systems need to react:
 * - Email Service: Send booking confirmation + PDF receipt to guest
 * - Hotel Notification: Alert hotel property management system
 * - Analytics: Track conversion rates, revenue metrics
 * - Loyalty: Award reward points to the guest account
 *
 * Publishing this to Kafka decouples all these concerns from the critical booking path.
 * If the email server is down, the booking still succeeds. Email retries independently.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingConfirmedEvent {

    private String eventId;
    private String bookingReference;
    private String userId;
    private Long hotelId;
    private String hotelName;
    private Long roomTypeId;
    private String roomTypeName;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate checkInDate;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate checkOutDate;

    private long totalNights;
    private BigDecimal totalPrice;

    @Builder.Default
    private Instant occurredAt = Instant.now();
}
