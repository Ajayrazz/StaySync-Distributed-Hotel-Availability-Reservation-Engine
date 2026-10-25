package com.staysync.hotel.dto;

import com.staysync.hotel.model.Hotel;

import java.util.List;

public record HotelResponse(
        Long id,
        String name,
        String description,
        String address,
        String city,
        String country,
        Double latitude,
        Double longitude,
        Integer starRating,
        List<RoomTypeResponse> roomTypes
) {
    public static HotelResponse from(Hotel hotel) {
        List<RoomTypeResponse> rooms = hotel.getRoomTypes() != null
                ? hotel.getRoomTypes().stream().map(RoomTypeResponse::from).toList()
                : List.of();

        return new HotelResponse(
                hotel.getId(),
                hotel.getName(),
                hotel.getDescription(),
                hotel.getAddress(),
                hotel.getCity(),
                hotel.getCountry(),
                hotel.getLatitude(),
                hotel.getLongitude(),
                hotel.getStarRating(),
                rooms
        );
    }
}
