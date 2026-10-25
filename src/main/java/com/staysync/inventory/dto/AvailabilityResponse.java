package com.staysync.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AvailabilityResponse(
        Long roomTypeId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        long totalNights,
        boolean isAvailable,
        int minAvailableRooms,
        BigDecimal totalPrice,
        List<DailyRate> dailyBreakdown
) {
    public record DailyRate(
            LocalDate date,
            int availableRooms,
            BigDecimal price
    ) {}
}
