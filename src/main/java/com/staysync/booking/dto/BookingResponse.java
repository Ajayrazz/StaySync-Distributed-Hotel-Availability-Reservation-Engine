package com.staysync.booking.dto;

import com.staysync.booking.model.Booking;
import com.staysync.booking.model.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Immutable response DTO for a confirmed booking.
 *
 * Why expose bookingReference instead of internal DB id?
 * The internal Long id is an implementation detail of the persistence layer.
 * bookingReference is a human-readable, URL-safe external identifier
 * (e.g., STAY-2026-A3F9X) that can be shown on the booking confirmation page,
 * printed on receipts, and passed between services without exposing DB internals.
 */
public record BookingResponse(
        Long id,
        String bookingReference,
        String userId,
        Long hotelId,
        String hotelName,
        Long roomTypeId,
        String roomTypeName,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        long totalNights,
        BigDecimal totalPrice,
        BookingStatus status,
        Instant createdAt
) {
    public static BookingResponse from(Booking booking) {
        long nights = java.time.temporal.ChronoUnit.DAYS.between(
                booking.getCheckInDate(), booking.getCheckOutDate());

        return new BookingResponse(
                booking.getId(),
                booking.getBookingReference(),
                booking.getUserId(),
                booking.getHotel().getId(),
                booking.getHotel().getName(),
                booking.getRoomType().getId(),
                booking.getRoomType().getName(),
                booking.getCheckInDate(),
                booking.getCheckOutDate(),
                nights,
                booking.getTotalPrice(),
                booking.getStatus(),
                booking.getCreatedAt()
        );
    }
}
