package com.staysync.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI staySyncOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("StaySync - Distributed Hotel Reservation Engine API")
                        .description("Production-grade distributed hotel reservation and availability API designed with " +
                                "Redisson distributed locks, 15-minute checkout holds, and Kafka event streaming.")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("StaySync Platform Engineering")
                                .email("engineering@staysync.com"))
                        .license(new License().name("Apache 2.0")));
    }
}
