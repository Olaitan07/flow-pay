package com.flowpay.kyc;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One Postgres and one Kafka broker are started once and shared by every integration test class. */
@SpringBootTest(properties = {"flowpay.security.internal-api-key=test-internal-key",
        "flowpay.kyc.outbox.poll-interval=200ms"})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    protected static final String INTERNAL_API_KEY = "test-internal-key";

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kycProperties(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        registry.add("flowpay.kyc.encryption-key", () -> Base64.getEncoder().encodeToString(key));
    }
}
