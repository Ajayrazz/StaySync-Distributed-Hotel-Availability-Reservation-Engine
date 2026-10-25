package com.staysync.inventory.model;

import com.staysync.hotel.model.RoomType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "daily_inventory", uniqueConstraints = {
        @UniqueConstraint(name = "uq_room_date", columnNames = {"room_type_id", "calendar_date"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomType roomType;

    @Column(name = "calendar_date", nullable = false)
    private LocalDate calendarDate;

    @Column(name = "available_count", nullable = false)
    private Integer availableCount;

    @Column(name = "reserved_count", nullable = false)
    @Builder.Default
    private Integer reservedCount = 0;

    @Column(name = "price_multiplier", nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal priceMultiplier = BigDecimal.ONE;

    /**
     * JPA Optimistic Locking Version.
     * Prevents lost updates under concurrent reservation commits.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public int getFreeRooms() {
        return Math.max(0, availableCount - reservedCount);
    }
}
