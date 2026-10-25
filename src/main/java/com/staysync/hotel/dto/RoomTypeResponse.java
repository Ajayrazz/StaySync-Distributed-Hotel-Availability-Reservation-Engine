package com.staysync.hotel.dto;

import com.staysync.hotel.model.RoomType;

import java.math.BigDecimal;

public record RoomTypeResponse(
        Long id,
        Long hotelId,
        String name,
        String description,
        BigDecimal basePrice,
        Integer capacity,
        Integer totalInventory
) {
    public static RoomTypeResponse from(RoomType entity) {
        return new RoomTypeResponse(
                entity.getId(),
                entity.getHotel().getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getBasePrice(),
                entity.getCapacity(),
                entity.getTotalInventory()
        );
    }
}
