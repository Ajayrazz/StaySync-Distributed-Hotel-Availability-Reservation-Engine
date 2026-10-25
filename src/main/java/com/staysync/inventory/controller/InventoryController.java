package com.staysync.inventory.controller;

import com.staysync.common.ApiResponse;
import com.staysync.inventory.dto.AvailabilityResponse;
import com.staysync.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
@Tag(name = "Room Availability & Inventory", description = "Endpoints for checking real-time room availability across arbitrary date ranges")
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/check")
    @Operation(summary = "Check room availability and calculate total price for specific check-in and check-out dates")
    public ResponseEntity<ApiResponse<AvailabilityResponse>> checkAvailability(
            @RequestParam Long roomTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut) {

        AvailabilityResponse response = inventoryService.checkAvailability(roomTypeId, checkIn, checkOut);
        return ResponseEntity.ok(ApiResponse.success(response, "Availability calculated successfully"));
    }
}
