package com.staysync.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String BOOKING_CONFIRMED_TOPIC = "staysync.booking.confirmed";
    public static final String BOOKING_CANCELLED_TOPIC = "staysync.booking.cancelled";
    public static final String HOLD_EXPIRED_TOPIC = "staysync.booking.hold.expired";

    @Bean
    public NewTopic bookingConfirmedTopic() {
        return TopicBuilder.name(BOOKING_CONFIRMED_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic bookingCancelledTopic() {
        return TopicBuilder.name(BOOKING_CANCELLED_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic holdExpiredTopic() {
        return TopicBuilder.name(HOLD_EXPIRED_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
