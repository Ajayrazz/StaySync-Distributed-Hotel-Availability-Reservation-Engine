package com.staysync.booking.repository;

import com.staysync.booking.model.ReservationHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReservationHoldRepository extends JpaRepository<ReservationHold, Long> {

    Optional<ReservationHold> findByHoldId(String holdId);

    /**
     * Finds all ACTIVE holds that have passed their expiry time.
     * Used by HoldExpiryScheduler to run the compensating transaction.
     *
     * Why index on (expires_at) WHERE status = 'ACTIVE'?
     * In production, there could be millions of hold records.
     * The partial index ensures the query only scans ACTIVE rows,
     * not all historical holds. This keeps the scheduler extremely fast.
     */
    @Query("SELECT h FROM ReservationHold h WHERE h.status = 'ACTIVE' AND h.expiresAt < :now")
    List<ReservationHold> findExpiredActiveHolds(@Param("now") Instant now);

    /**
     * Bulk-expire holds to minimize individual UPDATE round trips.
     */
    @Modifying
    @Query("UPDATE ReservationHold h SET h.status = 'EXPIRED' WHERE h.holdId IN :holdIds")
    int bulkExpireHolds(@Param("holdIds") List<String> holdIds);
}
