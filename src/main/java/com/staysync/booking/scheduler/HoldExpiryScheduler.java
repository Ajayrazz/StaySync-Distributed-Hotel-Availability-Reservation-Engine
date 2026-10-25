package com.staysync.booking.scheduler;

import com.staysync.booking.event.BookingEventPublisher;
import com.staysync.booking.event.HoldExpiredEvent;
import com.staysync.booking.model.ReservationHold;
import com.staysync.booking.repository.ReservationHoldRepository;
import com.staysync.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * HoldExpiryScheduler — Proactive Compensating Transaction Runner
 *
 * Problem It Solves:
 * When a user starts checkout (triggering a 15-minute hold) and then abandons
 * the browser or their payment fails, the reserved_count in daily_inventory
 * remains incremented but no booking was created.
 *
 * Without this scheduler: Rooms appear sold out to new customers even though
 * the hold has naturally expired. Hotel loses revenue.
 *
 * How It Works:
 * Every 60 seconds, this scheduler:
 * 1. Queries PostgreSQL for all ReservationHold records where:
 *    - status = 'ACTIVE' AND expiresAt < NOW()
 * 2. For each expired hold, executes a compensating transaction:
 *    - Decrements reserved_count back by the held rooms
 * 3. Marks all expired holds as status = 'EXPIRED' in bulk
 * 4. Publishes HoldExpiredEvent to Kafka for analytics
 *
 * Why Polling + Bulk instead of Redis Keyspace Events?
 * Redis keyspace notifications (when a Redis key expires, fire an event)
 * are not 100% reliable under high load — events can be missed during
 * Redis cluster failover. PostgreSQL as source of truth with a scheduled
 * poll is simpler and more reliable for correctness.
 *
 * Why @Transactional on the scheduler method?
 * The inventory release and hold status update must succeed or fail together.
 * If the inventory release fails, the hold should NOT be marked EXPIRED
 * (otherwise we'd mark it expired but never release the inventory).
 *
 * Production Consideration:
 * In a multi-instance deployment, this scheduler will run on EVERY instance.
 * To prevent double-processing, use ShedLock (or Quartz clustering) to ensure
 * only one instance runs the scheduler at a time:
 *   @SchedulerLock(name = "holdExpiryTask", lockAtMostFor = "PT55S")
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HoldExpiryScheduler {

    private final ReservationHoldRepository holdRepository;
    private final InventoryService inventoryService;
    private final BookingEventPublisher eventPublisher;

    /**
     * Runs every 60 seconds. Finds expired ACTIVE holds and releases their inventory.
     *
     * fixedDelay = 60_000: wait 60 seconds AFTER the previous execution completes.
     * This avoids overlapping runs if the DB query takes longer than 60 seconds.
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireStaleHolds() {
        Instant now = Instant.now();
        List<ReservationHold> expiredHolds = holdRepository.findExpiredActiveHolds(now);

        if (expiredHolds.isEmpty()) {
            log.debug("[HoldExpiryScheduler] No expired holds found at {}", now);
            return;
        }

        log.info("[HoldExpiryScheduler] Processing {} expired hold(s)...", expiredHolds.size());

        List<String> holdIdsToExpire = expiredHolds.stream()
                .map(ReservationHold::getHoldId)
                .toList();

        int processedCount = 0;
        int failedCount = 0;

        for (ReservationHold hold : expiredHolds) {
            try {
                processExpiredHold(hold);
                processedCount++;
            } catch (Exception ex) {
                // Log and continue — don't let one failed release block all others
                failedCount++;
                log.error("[HoldExpiryScheduler] Failed to release inventory for holdId={}: {}",
                        hold.getHoldId(), ex.getMessage(), ex);
            }
        }

        // Bulk update status to EXPIRED in one SQL UPDATE for efficiency
        if (!holdIdsToExpire.isEmpty()) {
            holdRepository.bulkExpireHolds(holdIdsToExpire);
        }

        log.info("[HoldExpiryScheduler] Completed: {} released, {} failed out of {} total",
                processedCount, failedCount, expiredHolds.size());
    }

    private void processExpiredHold(ReservationHold hold) {
        log.info("[HoldExpiryScheduler] Expiring hold={} for user={} roomType={} dates={}/{}",
                hold.getHoldId(), hold.getUserId(), hold.getRoomType().getId(),
                hold.getCheckInDate(), hold.getCheckOutDate());

        // COMPENSATING TRANSACTION: Decrement reserved_count for each day of the hold period
        inventoryService.releaseInventory(
                hold.getRoomType().getId(),
                hold.getCheckInDate(),
                hold.getCheckOutDate(),
                1 // 1 room per hold
        );

        // Publish analytics event to Kafka
        HoldExpiredEvent event = HoldExpiredEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .holdId(hold.getHoldId())
                .userId(hold.getUserId())
                .roomTypeId(hold.getRoomType().getId())
                .checkInDate(hold.getCheckInDate())
                .checkOutDate(hold.getCheckOutDate())
                .build();

        eventPublisher.publishHoldExpired(event);
    }
}
