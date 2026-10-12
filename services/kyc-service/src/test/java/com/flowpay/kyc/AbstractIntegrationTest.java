package com.flowpay.kyc;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One Postgres container and one embedded Kafka broker are started once and shared by every integration test class. */
@SpringBootTest(properties = {"flowpay.security.internal-api-key=test-internal-key",
        "flowpay.kyc.outbox.poll-interval=200ms"})
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = "kyc.events", bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public abstract class AbstractIntegrationTest {

    protected static final String INTERNAL_API_KEY = "test-internal-key";

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @Autowired
    protected EmbeddedKafkaBroker kafka;

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void kycProperties(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        registry.add("flowpay.kyc.encryption-key", () -> Base64.getEncoder().encodeToString(key));
    }
}
