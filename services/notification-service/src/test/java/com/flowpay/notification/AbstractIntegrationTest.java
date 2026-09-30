package com.flowpay.notification;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "flowpay.security.internal-api-key=test-internal-key")
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    protected static final String INTERNAL_API_KEY = "test-internal-key";

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    /** No real SMTP server in tests; the message handed to the mail sender is inspected instead. */
    @MockitoBean
    protected JavaMailSender mailSender;
}
