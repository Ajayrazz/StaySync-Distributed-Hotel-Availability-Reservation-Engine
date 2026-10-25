package com.staysync.hotel.repository;

import com.staysync.hotel.model.Hotel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HotelRepository extends JpaRepository<Hotel, Long> {

    List<Hotel> findByCityIgnoreCase(String city);

    List<Hotel> findByCountryIgnoreCase(String country);

    /**
     * Geospatial radius search using the spherical Haversine formula.
     * Computes great-circle distance in kilometers between two points on Earth.
     * 6371 is the mean radius of Earth in km.
     */
    @Query(value = """
        SELECT h.*, 
               (6371 * acos(
                    LEAST(1.0, GREATEST(-1.0, 
                        cos(radians(:latitude)) * cos(radians(h.latitude)) * 
                        cos(radians(h.longitude) - radians(:longitude)) + 
                        sin(radians(:latitude)) * sin(radians(h.latitude))
                    ))
               )) AS distance_km
        FROM hotels h
        WHERE h.active = true
          AND (6371 * acos(
                    LEAST(1.0, GREATEST(-1.0, 
                        cos(radians(:latitude)) * cos(radians(h.latitude)) * 
                        cos(radians(h.longitude) - radians(:longitude)) + 
                        sin(radians(:latitude)) * sin(radians(h.latitude))
                    ))
               )) <= :radiusKm
        ORDER BY distance_km ASC
        """, nativeQuery = true)
    List<Hotel> findNearbyHotels(
            @Param("latitude") double latitude,
            @Param("longitude") double longitude,
            @Param("radiusKm") double radiusKm);
}
