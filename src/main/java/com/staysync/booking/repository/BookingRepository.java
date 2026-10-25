package com.staysync.booking.repository;

import com.staysync.booking.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingReference(String bookingReference);

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    List<Booking> findByUserIdOrderByCreatedAtDesc(String userId);
}
