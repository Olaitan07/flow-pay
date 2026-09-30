package com.flowpay.user;

import com.flowpay.user.service.CredentialClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One Postgres container is started once and shared by every integration test class. */
@SpringBootTest(properties = "flowpay.security.internal-api-key=test-internal-key")
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    /** auth-service is a separate service; its behaviour is stubbed here and tested in its own module. */
    @MockitoBean
    protected CredentialClient credentialClient;

    static {
        POSTGRES.start();
    }
}
