package com.staysync.hotel.controller;

import com.staysync.common.ApiResponse;
import com.staysync.hotel.dto.HotelResponse;
import com.staysync.hotel.service.HotelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/hotels")
@RequiredArgsConstructor
@Tag(name = "Hotel & Property Catalog", description = "Endpoints for discovering hotels, searching by city, and geospatial radius lookups")
public class HotelController {

    private final HotelService hotelService;

    @GetMapping
    @Operation(summary = "List all active hotels with their room categories")
    public ResponseEntity<ApiResponse<List<HotelResponse>>> getAllHotels() {
        return ResponseEntity.ok(ApiResponse.success(hotelService.getAllHotels(), "Hotels fetched successfully"));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get specific hotel details by ID")
    public ResponseEntity<ApiResponse<HotelResponse>> getHotelById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(hotelService.getHotelById(id), "Hotel details fetched"));
    }

    @GetMapping("/search/city")
    @Operation(summary = "Search hotels by destination city")
    public ResponseEntity<ApiResponse<List<HotelResponse>>> getHotelsByCity(@RequestParam String city) {
        return ResponseEntity.ok(ApiResponse.success(hotelService.getHotelsByCity(city), "Hotels found for city: " + city));
    }

    @GetMapping("/search/nearby")
    @Operation(summary = "Geospatial radius search using Haversine formula (lat, lon, radius in km)")
    public ResponseEntity<ApiResponse<List<HotelResponse>>> getNearbyHotels(
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam(defaultValue = "10.0") double radiusKm) {
        return ResponseEntity.ok(ApiResponse.success(
                hotelService.getNearbyHotels(lat, lon, radiusKm),
                "Nearby hotels located within " + radiusKm + " km"));
    }
}
