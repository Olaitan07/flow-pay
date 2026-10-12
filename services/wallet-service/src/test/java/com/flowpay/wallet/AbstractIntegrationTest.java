package com.flowpay.wallet;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One Postgres container is started once and shared by every integration test class. */
@SpringBootTest(properties = {"flowpay.security.internal-api-key=test-internal-key"})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    protected static final String INTERNAL_API_KEY = "test-internal-key";

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }
}
