package com.staysync.booking.event;

import com.staysync.config.KafkaTopicConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka Event Publisher for the Booking domain.
 *
 * Design Principle — Why async send with callback?
 * KafkaTemplate.send() is non-blocking. It returns a CompletableFuture.
 * We attach a callback to log success/failure. This means the booking
 * confirmation is already returned to the user before Kafka acknowledges.
 * This is acceptable because the booking is already CONFIRMED in PostgreSQL.
 *
 * For guaranteed delivery (Transactional Outbox Pattern), a separate
 * outbox publisher would scan the outbox_events table and publish
 * unprocessed events, ensuring no event is ever lost, even if this
 * in-memory publish fails.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishBookingConfirmed(BookingConfirmedEvent event) {
        log.info("Publishing BookingConfirmedEvent for reference: {}", event.getBookingReference());

        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(KafkaTopicConfig.BOOKING_CONFIRMED_TOPIC, event.getBookingReference(), event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish BookingConfirmedEvent for reference={}: {}",
                        event.getBookingReference(), ex.getMessage());
            } else {
                log.info("BookingConfirmedEvent published successfully. Topic={}, Partition={}, Offset={}",
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }

    public void publishHoldExpired(HoldExpiredEvent event) {
        log.info("Publishing HoldExpiredEvent for holdId: {}", event.getHoldId());

        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(KafkaTopicConfig.HOLD_EXPIRED_TOPIC, event.getHoldId(), event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish HoldExpiredEvent for holdId={}: {}",
                        event.getHoldId(), ex.getMessage());
            } else {
                log.info("HoldExpiredEvent published. Partition={}, Offset={}",
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
