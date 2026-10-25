package com.staysync.hotel.service;

import com.staysync.common.exception.ResourceNotFoundException;
import com.staysync.hotel.dto.HotelResponse;
import com.staysync.hotel.model.Hotel;
import com.staysync.hotel.repository.HotelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class HotelService {

    private final HotelRepository hotelRepository;

    public List<HotelResponse> getAllHotels() {
        return hotelRepository.findAll().stream()
                .map(HotelResponse::from)
                .toList();
    }

    public HotelResponse getHotelById(Long id) {
        Hotel hotel = hotelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Hotel not found with id: " + id));
        return HotelResponse.from(hotel);
    }

    public List<HotelResponse> getHotelsByCity(String city) {
        return hotelRepository.findByCityIgnoreCase(city).stream()
                .map(HotelResponse::from)
                .toList();
    }

    public List<HotelResponse> getNearbyHotels(double latitude, double longitude, double radiusKm) {
        log.info("Searching hotels within {} km of lat={}, lon={}", radiusKm, latitude, longitude);
        return hotelRepository.findNearbyHotels(latitude, longitude, radiusKm).stream()
                .map(HotelResponse::from)
                .toList();
    }
}
