package com.staysync.booking.dto;

import com.staysync.booking.model.ReservationHold;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Response DTO for the reservation hold (15-minute checkout window).
 *
 * expiresAt is crucial for the frontend — the UI renders a countdown timer
 * so the user knows they have, e.g., 14:32 remaining to complete payment.
 * Once this timestamp elapses, the frontend disables the "Pay" button and
 * shows "Your reservation has expired. Please search again."
 */
public record HoldResponse(
        String holdId,
        Long roomTypeId,
        String roomTypeName,
        String userId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        long totalNights,
        BigDecimal estimatedTotalPrice,
        Instant expiresAt,
        String status
) {
    public static HoldResponse from(ReservationHold hold, String roomTypeName,
                                    long nights, BigDecimal totalPrice) {
        return new HoldResponse(
                hold.getHoldId(),
                hold.getRoomType().getId(),
                roomTypeName,
                hold.getUserId(),
                hold.getCheckInDate(),
                hold.getCheckOutDate(),
                nights,
                totalPrice,
                hold.getExpiresAt(),
                hold.getStatus()
        );
    }
}
