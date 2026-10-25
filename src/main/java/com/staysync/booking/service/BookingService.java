package com.staysync.booking.service;

import com.staysync.booking.dto.*;
import com.staysync.booking.event.BookingConfirmedEvent;
import com.staysync.booking.event.BookingEventPublisher;
import com.staysync.booking.model.Booking;
import com.staysync.booking.model.BookingStatus;
import com.staysync.booking.model.ReservationHold;
import com.staysync.booking.repository.BookingRepository;
import com.staysync.booking.repository.ReservationHoldRepository;
import com.staysync.common.exception.DuplicateRequestException;
import com.staysync.common.exception.InvalidDateRangeException;
import com.staysync.common.exception.ResourceNotFoundException;
import com.staysync.common.exception.RoomUnavailableException;
import com.staysync.hotel.model.Hotel;
import com.staysync.hotel.model.RoomType;
import com.staysync.hotel.repository.HotelRepository;
import com.staysync.hotel.repository.RoomTypeRepository;
import com.staysync.inventory.service.InventoryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * ======================================================
 * BookingService — The Core Reservation Engine
 * ======================================================
 *
 * This is the most complex service in StaySync. It orchestrates the entire
 * booking workflow: from a user clicking "Reserve" to a confirmed booking.
 *
 * KEY PATTERNS IMPLEMENTED HERE:
 *
 * 1. DISTRIBUTED LOCKING (Redisson)
 *    Problem: Two simultaneous requests for the last room on Dec 31.
 *    Solution: Acquire a Redis lock keyed on roomTypeId + date range.
 *              Only one thread proceeds at a time for the same resource.
 *
 * 2. 15-MINUTE HOLD STATE MACHINE
 *    Problem: User needs time to enter payment details; room can't be left open.
 *    Solution: Atomically increment reserved_count in PostgreSQL + create
 *              a Redis-backed TTL key for the hold. If TTL expires before
 *              payment, the HoldExpiryScheduler runs the compensating transaction.
 *
 * 3. IDEMPOTENT CONFIRMATION (Idempotency Key)
 *    Problem: Network drops; user clicks "Pay" 3 times.
 *    Solution: Check Redis for the idempotency key before processing.
 *              If already processed, return cached booking. No re-charge.
 *
 * 4. KAFKA EVENT PUBLISHING (Fire-and-Forget with Callback Logging)
 *    Problem: Booking confirmed; now email, invoice, loyalty points need updating.
 *    Solution: Publish BookingConfirmedEvent to Kafka topic. Downstream consumers
 *              handle notifications asynchronously. Booking API stays fast.
 *
 * 5. CUSTOM MICROMETER METRICS
 *    Problem: How do we know if our booking success rate is healthy in production?
 *    Solution: Increment counters on each attempt, success, and failure reason.
 *              These surface in Prometheus for Grafana dashboards and alerting.
 */
@Service
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final ReservationHoldRepository holdRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final HotelRepository hotelRepository;
    private final InventoryService inventoryService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final BookingEventPublisher eventPublisher;

    // Micrometer Counters for Prometheus observability
    private final Counter bookingAttemptCounter;
    private final Counter bookingSuccessCounter;
    private final Counter bookingFailedSoldOutCounter;
    private final Counter bookingFailedLockBusyCounter;

    @Value("${staysync.booking.hold-duration-minutes:15}")
    private int holdDurationMinutes;

    public BookingService(
            BookingRepository bookingRepository,
            ReservationHoldRepository holdRepository,
            RoomTypeRepository roomTypeRepository,
            HotelRepository hotelRepository,
            InventoryService inventoryService,
            RedissonClient redissonClient,
            StringRedisTemplate redisTemplate,
            BookingEventPublisher eventPublisher,
            MeterRegistry meterRegistry) {

        this.bookingRepository = bookingRepository;
        this.holdRepository = holdRepository;
        this.roomTypeRepository = roomTypeRepository;
        this.hotelRepository = hotelRepository;
        this.inventoryService = inventoryService;
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;

        // Register custom metrics with Prometheus via Micrometer
        this.bookingAttemptCounter = Counter.builder("staysync.booking.attempts")
                .description("Total number of booking hold attempts")
                .register(meterRegistry);
        this.bookingSuccessCounter = Counter.builder("staysync.booking.confirmed")
                .description("Total successfully confirmed bookings")
                .register(meterRegistry);
        this.bookingFailedSoldOutCounter = Counter.builder("staysync.booking.failed")
                .description("Failed booking attempts")
                .tag("reason", "SOLD_OUT")
                .register(meterRegistry);
        this.bookingFailedLockBusyCounter = Counter.builder("staysync.booking.failed")
                .description("Failed booking attempts")
                .tag("reason", "LOCK_BUSY")
                .register(meterRegistry);
    }

    // ==========================================
    // STEP 1: INITIATE A 15-MINUTE HOLD
    // ==========================================

    /**
     * Initiates a temporary room reservation hold.
     *
     * Thread Safety Guarantee:
     * The Redisson distributed lock ensures that for a given (roomTypeId + dateRange),
     * only ONE thread across ALL application instances can execute the critical
     * section at any given moment.
     *
     * @param request HoldRequest containing roomTypeId, userId, checkIn, checkOut dates
     * @return HoldResponse with holdId, pricing breakdown, and 15-min expiry timestamp
     */
    public HoldResponse initiateHold(HoldRequest request) {
        bookingAttemptCounter.increment();
        validateDates(request.checkInDate(), request.checkOutDate());

        RoomType roomType = roomTypeRepository.findById(request.roomTypeId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Room type not found: " + request.roomTypeId()));

        // ==================================================
        // REDISSON DISTRIBUTED LOCK
        // Lock key is scoped to roomTypeId + date range to
        // allow concurrent bookings for DIFFERENT room types
        // or DIFFERENT date ranges without blocking each other.
        // ==================================================
        String lockKey = buildLockKey(request.roomTypeId(), request.checkInDate(), request.checkOutDate());
        RLock lock = redissonClient.getLock(lockKey);

        log.info("Attempting to acquire lock: {} for user: {}", lockKey, request.userId());

        boolean lockAcquired;
        try {
            // tryLock(waitTime, leaseTime, unit)
            // waitTime=5s: wait up to 5 seconds before giving up
            // leaseTime=-1: use Redisson Watchdog (auto-renew lease until unlock)
            lockAcquired = lock.tryLock(5, -1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RoomUnavailableException("Booking request was interrupted. Please try again.");
        }

        if (!lockAcquired) {
            bookingFailedLockBusyCounter.increment();
            log.warn("Lock acquisition failed (high traffic) for roomType={} dates={}/{}",
                    request.roomTypeId(), request.checkInDate(), request.checkOutDate());
            throw new RoomUnavailableException(
                    "This room is experiencing high booking traffic. Please try again in a moment.");
        }

        try {
            // ==================================================
            // CRITICAL SECTION — Protected by Redisson Lock
            // ==================================================
            return executeHoldWithinLock(request, roomType);
        } finally {
            // ALWAYS release the lock in finally block.
            // Redisson isHeldByCurrentThread() prevents accidentally
            // releasing a lock acquired by another thread (which can
            // happen with raw Redis SETNX if lease expires during execution).
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("Redisson lock released: {}", lockKey);
            }
        }
    }

    /**
     * Executed within the distributed lock's critical section.
     * Any exception here triggers transaction rollback AND lock release in finally.
     */
    @Transactional
    protected HoldResponse executeHoldWithinLock(HoldRequest request, RoomType roomType) {
        // Atomically reserve inventory in PostgreSQL (inside @Transactional boundary)
        BigDecimal totalPrice;
        try {
            totalPrice = inventoryService.holdInventory(
                    request.roomTypeId(),
                    request.checkInDate(),
                    request.checkOutDate(),
                    request.requestedRooms()
            );
        } catch (RoomUnavailableException ex) {
            bookingFailedSoldOutCounter.increment();
            throw ex; // Re-throw; inventory hold failed (sold out or constraint violated)
        }

        long nights = ChronoUnit.DAYS.between(request.checkInDate(), request.checkOutDate());
        Instant expiresAt = Instant.now().plus(holdDurationMinutes, ChronoUnit.MINUTES);
        String holdId = "HOLD-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();

        // Persist the hold record in PostgreSQL
        ReservationHold hold = ReservationHold.builder()
                .holdId(holdId)
                .roomType(roomType)
                .userId(request.userId())
                .checkInDate(request.checkInDate())
                .checkOutDate(request.checkOutDate())
                .expiresAt(expiresAt)
                .status("ACTIVE")
                .build();
        holdRepository.save(hold);

        // Store hold metadata in Redis with TTL for fast lookup during payment step
        // Key: hold:meta:{holdId}   Value: roomTypeId|userId|checkIn|checkOut
        // This Redis entry acts as a lightweight cache; PostgreSQL is the source of truth.
        String redisHoldKey = "hold:meta:" + holdId;
        String redisHoldValue = String.format("%d|%s|%s|%s|%d",
                request.roomTypeId(), request.userId(),
                request.checkInDate(), request.checkOutDate(),
                request.requestedRooms());
        redisTemplate.opsForValue().set(redisHoldKey, redisHoldValue,
                Duration.ofMinutes(holdDurationMinutes + 1)); // +1 min buffer for scheduler

        log.info("Hold CREATED: holdId={} for user={} roomType={} dates={}/{} expires={}",
                holdId, request.userId(), request.roomTypeId(),
                request.checkInDate(), request.checkOutDate(), expiresAt);

        return HoldResponse.from(hold, roomType.getName(), nights, totalPrice);
    }

    // ==========================================
    // STEP 2: CONFIRM BOOKING (POST PAYMENT)
    // ==========================================

    /**
     * Confirms a booking after payment has been collected.
     *
     * Idempotency guarantee:
     * Redis atomic SET NX prevents duplicate processing for same idempotencyKey.
     * Any network retry hitting this endpoint with the same key returns
     * the cached booking reference, never re-confirms or re-charges.
     *
     * @param request ConfirmBookingRequest with holdId and idempotencyKey
     * @return BookingResponse with booking reference and full details
     */
    @Transactional
    public BookingResponse confirmBooking(ConfirmBookingRequest request) {
        // ========================================
        // IDEMPOTENCY CHECK
        // ========================================
        String idempotencyRedisKey = "idempotency:confirm:" + request.idempotencyKey();
        Boolean isFirstRequest = redisTemplate.opsForValue()
                .setIfAbsent(idempotencyRedisKey, "PROCESSING", Duration.ofHours(24));

        if (Boolean.FALSE.equals(isFirstRequest)) {
            // Duplicate request detected. Check if original completed.
            log.warn("Duplicate confirmation request detected for idempotencyKey={}", request.idempotencyKey());
            return bookingRepository.findByIdempotencyKey(request.idempotencyKey())
                    .map(BookingResponse::from)
                    .orElseThrow(() -> new DuplicateRequestException(
                            "Booking is already being processed. Please wait a moment."));
        }

        // ========================================
        // VALIDATE HOLD EXISTS AND IS ACTIVE
        // ========================================
        ReservationHold hold = holdRepository.findByHoldId(request.holdId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation hold not found: " + request.holdId()));

        if (!"ACTIVE".equals(hold.getStatus())) {
            redisTemplate.delete(idempotencyRedisKey); // Allow retry with valid hold
            throw new RoomUnavailableException(
                    "Reservation hold '" + request.holdId() + "' has expired or already been used. Please reserve again.");
        }

        if (hold.isExpired()) {
            hold.setStatus("EXPIRED");
            holdRepository.save(hold);
            redisTemplate.delete(idempotencyRedisKey);
            throw new RoomUnavailableException(
                    "Your 15-minute checkout window has expired. Please search for rooms again.");
        }

        // ========================================
        // CREATE CONFIRMED BOOKING RECORD
        // ========================================
        RoomType roomType = hold.getRoomType();
        Hotel hotel = roomType.getHotel();

        // Calculate total price (re-use availability pricing)
        long nights = ChronoUnit.DAYS.between(hold.getCheckInDate(), hold.getCheckOutDate());

        // Use the price already calculated at hold time (stored in Redis) or recompute
        BigDecimal totalPrice = inventoryService
                .checkAvailability(roomType.getId(), hold.getCheckInDate(), hold.getCheckOutDate())
                .totalPrice();

        String bookingReference = generateBookingReference();

        Booking booking = Booking.builder()
                .bookingReference(bookingReference)
                .userId(hold.getUserId())
                .hotel(hotel)
                .roomType(roomType)
                .checkInDate(hold.getCheckInDate())
                .checkOutDate(hold.getCheckOutDate())
                .totalPrice(totalPrice)
                .status(BookingStatus.CONFIRMED)
                .idempotencyKey(request.idempotencyKey())
                .build();

        Booking savedBooking = bookingRepository.save(booking);

        // Transition hold status to CONVERTED (cannot be used again)
        hold.setStatus("CONVERTED");
        holdRepository.save(hold);

        // Clean up Redis hold metadata key
        redisTemplate.delete("hold:meta:" + request.holdId());

        // Update idempotency key from PROCESSING to CONFIRMED
        redisTemplate.opsForValue().set(idempotencyRedisKey,
                "CONFIRMED:" + bookingReference, Duration.ofHours(24));

        // ========================================
        // PUBLISH KAFKA DOMAIN EVENT
        // ========================================
        BookingConfirmedEvent event = BookingConfirmedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .bookingReference(bookingReference)
                .userId(hold.getUserId())
                .hotelId(hotel.getId())
                .hotelName(hotel.getName())
                .roomTypeId(roomType.getId())
                .roomTypeName(roomType.getName())
                .checkInDate(hold.getCheckInDate())
                .checkOutDate(hold.getCheckOutDate())
                .totalNights(nights)
                .totalPrice(totalPrice)
                .build();

        eventPublisher.publishBookingConfirmed(event);
        bookingSuccessCounter.increment();

        log.info("Booking CONFIRMED: reference={} for user={} hotel={} nights={}",
                bookingReference, hold.getUserId(), hotel.getName(), nights);

        return BookingResponse.from(savedBooking);
    }

    // ==========================================
    // STEP 3: CANCEL BOOKING
    // ==========================================

    /**
     * Cancels a confirmed booking and releases inventory back.
     *
     * Business Rule: In a real system, cancellation policies (free before 24h,
     * penalty after) would be validated here. We implement the inventory
     * release (compensating transaction) regardless.
     */
    @Transactional
    public BookingResponse cancelBooking(String bookingReference) {
        Booking booking = bookingRepository.findByBookingReference(bookingReference)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking not found: " + bookingReference));

        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new IllegalStateException(
                    "Only CONFIRMED bookings can be cancelled. Current status: " + booking.getStatus());
        }

        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepository.save(booking);

        // Compensating transaction: release the inventory back to available pool
        inventoryService.releaseInventory(
                booking.getRoomType().getId(),
                booking.getCheckInDate(),
                booking.getCheckOutDate(),
                1 // default 1 room per booking for simplicity
        );

        log.info("Booking CANCELLED: reference={}", bookingReference);
        return BookingResponse.from(booking);
    }

    // ==========================================
    // READ OPERATIONS
    // ==========================================

    @Transactional(readOnly = true)
    public BookingResponse getBookingByReference(String bookingReference) {
        return bookingRepository.findByBookingReference(bookingReference)
                .map(BookingResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking not found with reference: " + bookingReference));
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getBookingsByUser(String userId) {
        return bookingRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(BookingResponse::from)
                .toList();
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    private void validateDates(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null) {
            throw new InvalidDateRangeException("Check-in and check-out dates are required.");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw new InvalidDateRangeException("Check-out must be after check-in date.");
        }
    }

    private String buildLockKey(Long roomTypeId, LocalDate checkIn, LocalDate checkOut) {
        return String.format("lock:room_type:%d:dates:%s_%s", roomTypeId, checkIn, checkOut);
    }

    private String generateBookingReference() {
        // Format: STAY-YYYYMM-XXXXXX (e.g., STAY-202612-A3F9X1)
        String datePart = java.time.YearMonth.now().toString().replace("-", "");
        String randomPart = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return String.format("STAY-%s-%s", datePart, randomPart);
    }
}
