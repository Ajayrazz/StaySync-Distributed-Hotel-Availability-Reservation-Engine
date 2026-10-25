package com.staysync.booking;

import com.staysync.BaseIntegrationTest;
import com.staysync.booking.dto.HoldRequest;
import com.staysync.booking.dto.HoldResponse;
import com.staysync.booking.service.BookingService;
import com.staysync.common.exception.RoomUnavailableException;
import com.staysync.inventory.model.DailyInventory;
import com.staysync.inventory.repository.DailyInventoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================
 * ConcurrentBookingIntegrationTest
 * ============================================================
 *
 * Tests the system's correctness guarantees under concurrent load.
 *
 * TEST SCENARIO:
 * A hotel room type has EXACTLY 1 room available for a specific date.
 * We fire 10 concurrent hold requests simultaneously.
 *
 * EXPECTED OUTCOME:
 * - Exactly 1 request succeeds (gets the hold)
 * - Exactly 9 requests fail with RoomUnavailableException
 * - The DailyInventory.reservedCount = 1 (not 2, not 10)
 *
 * WHY THIS TEST MATTERS FOR INTERVIEWS:
 * This test proves that our 3-tier defense (Redisson Lock + Optimistic Locking
 * + DB CHECK constraint) is working correctly. Without the distributed lock,
 * in a naive implementation, multiple threads could read available_count=1,
 * all decide "yes there's a room", and all create bookings.
 *
 * Running this test uses real PostgreSQL and real Redis via Testcontainers.
 * H2 would pass silently even with a broken implementation because it doesn't
 * enforce the same constraint semantics.
 */
@DisplayName("Concurrent Booking Race Condition Tests")
class ConcurrentBookingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private DailyInventoryRepository dailyInventoryRepository;

    @Test
    @DisplayName("Given 1 available room and 10 concurrent requests, exactly 1 should succeed")
    void givenOneAvailableRoom_whenTenConcurrentHoldRequests_thenExactlyOneSucceeds()
            throws InterruptedException {

        // ==============================
        // ARRANGE
        // ==============================
        // Room type ID=1 (Deluxe King Suite at The Plaza) is seeded by V2 migration.
        // We use a date far enough in the future to not conflict with other tests.
        Long roomTypeId = 1L;
        LocalDate checkIn = LocalDate.now().plusDays(90);
        LocalDate checkOut = checkIn.plusDays(1);

        // Force available_count to 1 for this specific date to simulate a sold-out scenario
        dailyInventoryRepository.findByRoomTypeIdAndCalendarDate(roomTypeId, checkIn)
                .ifPresent(inv -> {
                    inv.setAvailableCount(1);
                    inv.setReservedCount(0);
                    dailyInventoryRepository.save(inv);
                });

        // ==============================
        // ACT: Fire 10 concurrent threads
        // ==============================
        int concurrentUsers = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentUsers);
        CountDownLatch startGate = new CountDownLatch(1); // All threads start simultaneously
        CountDownLatch finishLine = new CountDownLatch(concurrentUsers);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<HoldResponse> successfulHolds = new CopyOnWriteArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < concurrentUsers; i++) {
            final String userId = "concurrent-user-" + i;
            executor.submit(() -> {
                try {
                    startGate.await(); // All threads wait here until released simultaneously
                    HoldRequest request = new HoldRequest(roomTypeId, userId, checkIn, checkOut, 1);
                    HoldResponse response = bookingService.initiateHold(request);
                    successCount.incrementAndGet();
                    successfulHolds.add(response);
                } catch (RoomUnavailableException e) {
                    failCount.incrementAndGet();
                    errors.add(e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLine.countDown();
                }
            });
        }

        startGate.countDown(); // 🚀 Release all 10 threads simultaneously
        finishLine.await(30, TimeUnit.SECONDS); // Wait for all to complete
        executor.shutdown();

        // ==============================
        // ASSERT
        // ==============================
        System.out.println("✅ Successful holds: " + successCount.get());
        System.out.println("❌ Failed attempts (expected): " + failCount.get());
        System.out.println("🔒 Hold ID secured: " +
                (successfulHolds.isEmpty() ? "NONE" : successfulHolds.get(0).holdId()));

        // CRITICAL: Exactly one must win the race
        assertThat(successCount.get())
                .as("Exactly 1 hold should succeed for 1 available room")
                .isEqualTo(1);

        assertThat(failCount.get())
                .as("The remaining 9 requests should fail gracefully")
                .isEqualTo(concurrentUsers - 1);

        // CRITICAL: Database state must be consistent
        DailyInventory finalInventory = dailyInventoryRepository
                .findByRoomTypeIdAndCalendarDate(roomTypeId, checkIn)
                .orElseThrow();

        assertThat(finalInventory.getReservedCount())
                .as("Only 1 room should be reserved in the database (no overbooking)")
                .isEqualTo(1);

        assertThat(finalInventory.getFreeRooms())
                .as("Zero rooms should be free after the single successful hold")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("Hold should contain correct expiry timestamp approximately 15 minutes from now")
    void whenHoldCreated_thenExpiresAtShouldBeApproximatelyFifteenMinutesAhead() {
        Long roomTypeId = 2L; // Superior Double Room — has 10 inventory
        LocalDate checkIn = LocalDate.now().plusDays(60);
        LocalDate checkOut = checkIn.plusDays(2);

        HoldRequest request = new HoldRequest(roomTypeId, "test-user-expiry", checkIn, checkOut, 1);
        HoldResponse hold = bookingService.initiateHold(request);

        assertThat(hold.holdId()).isNotBlank();
        assertThat(hold.status()).isEqualTo("ACTIVE");
        assertThat(hold.totalNights()).isEqualTo(2);

        // Verify expiresAt is roughly 15 minutes from now (within ±10 second tolerance)
        long secondsUntilExpiry = java.time.Duration.between(
                java.time.Instant.now(), hold.expiresAt()).getSeconds();

        assertThat(secondsUntilExpiry)
                .as("Hold should expire in approximately 15 minutes (900 seconds)")
                .isBetween(890L, 910L);
    }
}
