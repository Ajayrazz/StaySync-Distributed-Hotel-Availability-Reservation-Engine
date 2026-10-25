package com.staysync.booking.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request body for initiating a 15-minute reservation hold.
 *
 * Java Record chosen for immutability: DTOs should never be mutated after creation.
 * Bean Validation annotations enforce contract at the HTTP layer before service logic runs.
 *
 * Why separate HoldRequest from a direct BookingRequest?
 * Booking.com and Airbnb separate the "Reserve" step from the "Pay" step.
 * The hold is the reservation gate; confirmation happens only after payment clears.
 */
public record HoldRequest(

        @NotNull(message = "Room type ID is required")
        Long roomTypeId,

        @NotNull(message = "Guest user ID is required")
        @NotBlank(message = "User ID cannot be blank")
        String userId,

        @NotNull(message = "Check-in date is required")
        @Future(message = "Check-in date must be in the future")
        LocalDate checkInDate,

        @NotNull(message = "Check-out date is required")
        LocalDate checkOutDate,

        @Min(value = 1, message = "At least 1 room must be requested")
        int requestedRooms
) {}
