package com.flowpay.transaction;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres container is started once and shared by every integration test class. The recovery sweep never runs on
 * its own in tests (interval of an hour); tests call it directly, and min-age 0 makes any stuck funding eligible.
 */
@SpringBootTest(properties = {
        "flowpay.security.internal-api-key=test-internal-key",
        "flowpay.transaction.recovery.interval=1h",
        "flowpay.transaction.recovery.min-age=0s"})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    protected static final String INTERNAL_API_KEY = "test-internal-key";

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }
}
