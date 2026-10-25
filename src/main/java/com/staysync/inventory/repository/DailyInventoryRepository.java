package com.staysync.inventory.repository;

import com.staysync.inventory.model.DailyInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyInventoryRepository extends JpaRepository<DailyInventory, Long> {

    Optional<DailyInventory> findByRoomTypeIdAndCalendarDate(Long roomTypeId, LocalDate calendarDate);

    List<DailyInventory> findByRoomTypeIdAndCalendarDateBetweenOrderByCalendarDateAsc(
            Long roomTypeId, LocalDate startDate, LocalDate endDate);

    /**
     * Atomic reservation update.
     * Guarantees at the database level that we never increment reserved_count beyond available_count.
     */
    @Modifying
    @Query("""
        UPDATE DailyInventory d 
        SET d.reservedCount = d.reservedCount + :rooms, 
            d.version = d.version + 1 
        WHERE d.roomType.id = :roomTypeId 
          AND d.calendarDate = :date 
          AND (d.availableCount - d.reservedCount) >= :rooms
        """)
    int reserveRoomsAtomic(
            @Param("roomTypeId") Long roomTypeId,
            @Param("date") LocalDate date,
            @Param("rooms") int rooms);

    /**
     * Atomic release of previously held rooms (compensating transaction on expiration or cancellation).
     */
    @Modifying
    @Query("""
        UPDATE DailyInventory d 
        SET d.reservedCount = d.reservedCount - :rooms, 
            d.version = d.version + 1 
        WHERE d.roomType.id = :roomTypeId 
          AND d.calendarDate = :date 
          AND d.reservedCount >= :rooms
        """)
    int releaseRoomsAtomic(
            @Param("roomTypeId") Long roomTypeId,
            @Param("date") LocalDate date,
            @Param("rooms") int rooms);
}
