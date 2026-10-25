package com.staysync.inventory.service;

import com.staysync.common.exception.InvalidDateRangeException;
import com.staysync.common.exception.ResourceNotFoundException;
import com.staysync.common.exception.RoomUnavailableException;
import com.staysync.hotel.model.RoomType;
import com.staysync.hotel.repository.RoomTypeRepository;
import com.staysync.inventory.dto.AvailabilityResponse;
import com.staysync.inventory.model.DailyInventory;
import com.staysync.inventory.repository.DailyInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final DailyInventoryRepository dailyInventoryRepository;
    private final RoomTypeRepository roomTypeRepository;

    @Transactional(readOnly = true)
    public AvailabilityResponse checkAvailability(Long roomTypeId, LocalDate checkIn, LocalDate checkOut) {
        validateDateRange(checkIn, checkOut);

        RoomType roomType = roomTypeRepository.findById(roomTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found with id: " + roomTypeId));

        long totalNights = ChronoUnit.DAYS.between(checkIn, checkOut);
        LocalDate lastNight = checkOut.minusDays(1);

        List<DailyInventory> inventoryList = dailyInventoryRepository
                .findByRoomTypeIdAndCalendarDateBetweenOrderByCalendarDateAsc(roomTypeId, checkIn, lastNight);

        if (inventoryList.size() < totalNights) {
            log.warn("Inventory not fully generated for roomType={} between {} and {}", roomTypeId, checkIn, checkOut);
            return new AvailabilityResponse(roomTypeId, checkIn, checkOut, totalNights, false, 0, BigDecimal.ZERO, List.of());
        }

        int minAvailable = Integer.MAX_VALUE;
        BigDecimal totalPrice = BigDecimal.ZERO;
        List<AvailabilityResponse.DailyRate> breakdown = new ArrayList<>();

        for (DailyInventory inv : inventoryList) {
            int free = inv.getFreeRooms();
            if (free < minAvailable) {
                minAvailable = free;
            }

            BigDecimal dailyPrice = roomType.getBasePrice().multiply(inv.getPriceMultiplier());
            totalPrice = totalPrice.add(dailyPrice);

            breakdown.add(new AvailabilityResponse.DailyRate(inv.getCalendarDate(), free, dailyPrice));
        }

        boolean isAvailable = minAvailable > 0;
        return new AvailabilityResponse(
                roomTypeId,
                checkIn,
                checkOut,
                totalNights,
                isAvailable,
                isAvailable ? minAvailable : 0,
                totalPrice,
                breakdown
        );
    }

    /**
     * Atomically reserves inventory for each day of stay.
     * If any day has insufficient rooms, the transaction is marked for rollback.
     */
    @Transactional
    public BigDecimal holdInventory(Long roomTypeId, LocalDate checkIn, LocalDate checkOut, int requestedRooms) {
        validateDateRange(checkIn, checkOut);

        RoomType roomType = roomTypeRepository.findById(roomTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found with id: " + roomTypeId));

        LocalDate current = checkIn;
        LocalDate lastNight = checkOut.minusDays(1);
        BigDecimal totalPrice = BigDecimal.ZERO;

        while (!current.isAfter(lastNight)) {
            int updated = dailyInventoryRepository.reserveRoomsAtomic(roomTypeId, current, requestedRooms);
            if (updated == 0) {
                log.warn("Failed to hold roomType={} for date={}. Insufficient rooms.", roomTypeId, current);
                throw new RoomUnavailableException("Room is no longer available for date: " + current);
            }

            DailyInventory inv = dailyInventoryRepository.findByRoomTypeIdAndCalendarDate(roomTypeId, current)
                    .orElseThrow();
            BigDecimal dailyPrice = roomType.getBasePrice().multiply(inv.getPriceMultiplier())
                    .multiply(BigDecimal.valueOf(requestedRooms));
            totalPrice = totalPrice.add(dailyPrice);

            current = current.plusDays(1);
        }

        log.info("Successfully held {} rooms for roomType={} from {} to {}", requestedRooms, roomTypeId, checkIn, checkOut);
        return totalPrice;
    }

    /**
     * Releases held rooms back to inventory (compensating transaction on hold expiration or cancellation).
     */
    @Transactional
    public void releaseInventory(Long roomTypeId, LocalDate checkIn, LocalDate checkOut, int roomsToRelease) {
        LocalDate current = checkIn;
        LocalDate lastNight = checkOut.minusDays(1);

        while (!current.isAfter(lastNight)) {
            dailyInventoryRepository.releaseRoomsAtomic(roomTypeId, current, roomsToRelease);
            current = current.plusDays(1);
        }
        log.info("Released {} rooms for roomType={} between {} and {}", roomsToRelease, roomTypeId, checkIn, checkOut);
    }

    private void validateDateRange(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null) {
            throw new InvalidDateRangeException("Check-in and check-out dates must not be null");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw new InvalidDateRangeException("Check-out date must be strictly after check-in date");
        }
        if (checkIn.isBefore(LocalDate.now())) {
            throw new InvalidDateRangeException("Check-in date cannot be in the past");
        }
    }
}
