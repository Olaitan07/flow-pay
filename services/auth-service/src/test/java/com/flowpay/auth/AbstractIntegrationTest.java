package com.flowpay.auth;

import com.flowpay.auth.service.NotificationClient;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One Postgres container and one throwaway RSA key pair are shared by every integration test class. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    protected static final String INTERNAL_API_KEY = "test-internal-key";
    protected static final KeyPair KEY_PAIR;

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    /** notification-service is a separate service; the emails it would send are captured here instead. */
    @MockitoBean
    protected NotificationClient notificationClient;

    static {
        POSTGRES.start();
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEY_PAIR = generator.generateKeyPair();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add("flowpay.security.internal-api-key", () -> INTERNAL_API_KEY);
        registry.add("flowpay.security.jwt.private-key",
                () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded()));
        registry.add("flowpay.security.jwt.public-key",
                () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()));
    }
}
