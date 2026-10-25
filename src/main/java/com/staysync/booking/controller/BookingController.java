package com.staysync.booking.controller;

import com.staysync.booking.dto.*;
import com.staysync.booking.service.BookingService;
import com.staysync.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * BookingController — REST API for the Booking & Reservation Lifecycle.
 *
 * Endpoint Design Philosophy:
 * The booking flow is split into two explicit HTTP calls to model the real business process:
 *
 * 1. POST /api/v1/bookings/hold     → Reserve room for 15 minutes (no payment yet)
 * 2. POST /api/v1/bookings/confirm  → Confirm booking after payment captured
 *
 * Why two steps?
 * This matches Booking.com's UX: "Reserve" (no credit card yet) followed by "Complete Booking".
 * Separating these allows the payment gateway to run independently and the UI
 * to display a countdown timer while the hold is active.
 */
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Tag(name = "Booking & Reservation Engine",
        description = "Core booking endpoints: initiate holds, confirm after payment, cancel, and retrieve booking details")
public class BookingController {

    private final BookingService bookingService;

    /**
     * Step 1: Reserve a room with a 15-minute hold.
     * Internally: Acquires Redisson lock → Atomically holds inventory → Creates Redis TTL hold.
     */
    @PostMapping("/hold")
    @Operation(
            summary = "Initiate 15-minute reservation hold",
            description = "Acquires a Redisson distributed lock, atomically reserves room inventory, " +
                    "and creates a 15-minute checkout window. Returns a holdId for the confirmation step."
    )
    public ResponseEntity<ApiResponse<HoldResponse>> initiateHold(
            @Valid @RequestBody HoldRequest request) {

        HoldResponse holdResponse = bookingService.initiateHold(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(holdResponse, "Room held successfully. Complete payment within 15 minutes."));
    }

    /**
     * Step 2: Confirm booking after payment captured.
     * Internally: Idempotency check → Validate hold → Persist booking → Publish Kafka event.
     */
    @PostMapping("/confirm")
    @Operation(
            summary = "Confirm booking after payment",
            description = "Validates the hold is still ACTIVE, creates a CONFIRMED booking, " +
                    "and publishes a BookingConfirmedEvent to Kafka for async email/invoice processing. " +
                    "Requires X-Idempotency-Key to prevent duplicate charges."
    )
    public ResponseEntity<ApiResponse<BookingResponse>> confirmBooking(
            @Valid @RequestBody ConfirmBookingRequest request) {

        BookingResponse booking = bookingService.confirmBooking(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(booking, "Booking confirmed! Reference: " + booking.bookingReference()));
    }

    /**
     * Cancel a confirmed booking and release inventory.
     */
    @PatchMapping("/{bookingReference}/cancel")
    @Operation(
            summary = "Cancel a confirmed booking",
            description = "Cancels booking and executes compensating transaction to release reserved inventory back to the pool."
    )
    public ResponseEntity<ApiResponse<BookingResponse>> cancelBooking(
            @Parameter(description = "Booking reference number (e.g., STAY-202612-A3F9X1)")
            @PathVariable String bookingReference) {

        BookingResponse cancelled = bookingService.cancelBooking(bookingReference);
        return ResponseEntity.ok(ApiResponse.success(cancelled, "Booking cancelled. Inventory released."));
    }

    /**
     * Retrieve a specific booking by its reference number.
     */
    @GetMapping("/{bookingReference}")
    @Operation(summary = "Get booking details by reference number")
    public ResponseEntity<ApiResponse<BookingResponse>> getBookingByReference(
            @PathVariable String bookingReference) {

        return ResponseEntity.ok(ApiResponse.success(
                bookingService.getBookingByReference(bookingReference),
                "Booking fetched successfully"));
    }

    /**
     * Retrieve all bookings for a given user (booking history).
     */
    @GetMapping("/user/{userId}")
    @Operation(summary = "Get all bookings for a user (booking history)")
    public ResponseEntity<ApiResponse<List<BookingResponse>>> getBookingsByUser(
            @PathVariable String userId) {

        return ResponseEntity.ok(ApiResponse.success(
                bookingService.getBookingsByUser(userId),
                "Booking history for user: " + userId));
    }
}
