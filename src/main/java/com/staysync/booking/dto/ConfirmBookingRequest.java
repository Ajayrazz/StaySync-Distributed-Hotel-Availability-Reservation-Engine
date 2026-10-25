package com.staysync.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for confirming a booking after payment succeeds.
 *
 * Why idempotencyKey is required here:
 * This is the payment-execution step. If the user's network drops mid-request
 * and they retry, the server must detect the duplicate using idempotencyKey
 * and return the already-confirmed booking without re-processing payment.
 *
 * The idempotencyKey is generated client-side (UUID) when the checkout page loads.
 * It is immutable for the entire checkout session of that specific booking attempt.
 */
public record ConfirmBookingRequest(

        @NotNull(message = "Hold ID is required to confirm a booking")
        @NotBlank(message = "Hold ID cannot be blank")
        String holdId,

        @NotNull(message = "Idempotency key is required to prevent duplicate charges")
        @NotBlank(message = "Idempotency key cannot be blank")
        String idempotencyKey
) {}
