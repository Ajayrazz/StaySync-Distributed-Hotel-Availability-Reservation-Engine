package com.staysync.booking.listener;

import com.staysync.booking.event.BookingConfirmedEvent;
import com.staysync.booking.event.HoldExpiredEvent;
import com.staysync.config.KafkaTopicConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * NotificationEventConsumer — Kafka Consumer for Booking Domain Events.
 *
 * CONSUMER GROUP: staysync-booking-group
 *
 * What is a Consumer Group?
 * Kafka delivers each message to EXACTLY ONE consumer within the same group.
 * If we scale to 3 application instances, all 3 share the group, and Kafka
 * automatically load-balances partitions across all 3 consumers.
 * This ensures no duplicate email is sent even when scaled horizontally.
 *
 * Why @KafkaListener over REST calls?
 * If we called an EmailService REST endpoint directly from BookingService:
 * → A 5-second email server slowdown directly impacts the booking API response time.
 * → If the email server is down, the booking confirmation also fails.
 * With Kafka: BookingService fires the event and returns in milliseconds.
 * The EmailService consumer retries independently until email is sent.
 *
 * Offset Management:
 * auto-offset-reset: earliest — on first startup, read from earliest available message.
 * enable.auto.commit: false (Spring Kafka default) — offsets committed after @KafkaListener
 * method returns successfully, ensuring at-least-once delivery.
 */
@Component
@Slf4j
public class NotificationEventConsumer {

    /**
     * Listens for BookingConfirmedEvent and simulates sending a confirmation email.
     *
     * In production, this would call an EmailService, a push notification gateway,
     * or a WhatsApp Business API. For now, we log the intent.
     */
    @KafkaListener(
            topics = KafkaTopicConfig.BOOKING_CONFIRMED_TOPIC,
            groupId = "staysync-notification-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleBookingConfirmed(
            @Payload BookingConfirmedEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("====================================================");
        log.info("[KAFKA CONSUMER] BookingConfirmedEvent received");
        log.info("  Partition: {} | Offset: {}", partition, offset);
        log.info("  Booking Reference : {}", event.getBookingReference());
        log.info("  Guest User ID     : {}", event.getUserId());
        log.info("  Hotel             : {} (ID: {})", event.getHotelName(), event.getHotelId());
        log.info("  Room Type         : {}", event.getRoomTypeName());
        log.info("  Stay Duration     : {} → {} ({} nights)",
                event.getCheckInDate(), event.getCheckOutDate(), event.getTotalNights());
        log.info("  Total Amount      : ${}", event.getTotalPrice());
        log.info("  Event Occurred At : {}", event.getOccurredAt());
        log.info("====================================================");

        // Simulate downstream actions:
        sendConfirmationEmail(event);
        notifyHotelPropertySystem(event);
        awardLoyaltyPoints(event);
    }

    /**
     * Listens for HoldExpiredEvent (analytics / audit trail for abandoned carts).
     */
    @KafkaListener(
            topics = KafkaTopicConfig.HOLD_EXPIRED_TOPIC,
            groupId = "staysync-analytics-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleHoldExpired(
            @Payload HoldExpiredEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("====================================================");
        log.info("[KAFKA CONSUMER] HoldExpiredEvent received (Cart Abandonment)");
        log.info("  Partition: {} | Offset: {}", partition, offset);
        log.info("  Hold ID    : {}", event.getHoldId());
        log.info("  User ID    : {}", event.getUserId());
        log.info("  Dates      : {} → {}", event.getCheckInDate(), event.getCheckOutDate());
        log.info("  Occurred At: {}", event.getOccurredAt());
        log.info("====================================================");

        // In production: record cart abandonment in analytics DB
        // Trigger re-engagement email: "You left without completing your booking!"
        recordCartAbandonmentAnalytics(event);
    }

    // ====================
    // Simulated Downstream Actions
    // ====================

    private void sendConfirmationEmail(BookingConfirmedEvent event) {
        log.info("[EMAIL SERVICE] Sending booking confirmation email to user: {} for reference: {}",
                event.getUserId(), event.getBookingReference());
        // Real: EmailClient.send(to=event.getUserEmail(), template="BOOKING_CONFIRMED", ...)
    }

    private void notifyHotelPropertySystem(BookingConfirmedEvent event) {
        log.info("[PMS INTEGRATION] Notifying hotel property management for hotelId={} booking={}",
                event.getHotelId(), event.getBookingReference());
        // Real: PmsApiClient.createReservation(event)
    }

    private void awardLoyaltyPoints(BookingConfirmedEvent event) {
        long pointsEarned = event.getTotalPrice().longValue() * 10; // 10 points per dollar
        log.info("[LOYALTY] Awarding {} points to userId={} for booking={}",
                pointsEarned, event.getUserId(), event.getBookingReference());
        // Real: LoyaltyService.credit(userId, points)
    }

    private void recordCartAbandonmentAnalytics(HoldExpiredEvent event) {
        log.info("[ANALYTICS] Recording cart abandonment for userId={} holdId={}",
                event.getUserId(), event.getHoldId());
        // Real: AnalyticsClient.track("cart_abandoned", properties)
    }
}
