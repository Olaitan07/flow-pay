package com.flowpay.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowpay.user.repository.CustomerRepository;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CustomerRegistrationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mockMvc;
    @Autowired CustomerRepository customerRepository;

    @BeforeEach
    void cleanDatabase() {
        customerRepository.deleteAll();
    }

    private static String body(String email, String phone) {
        return """
                {"firstName":"Ada","lastName":"Obi","email":"%s","phoneNumber":"%s",
                 "password":"Sup3rSecret","country":"NG"}""".formatted(email, phone);
    }

    private org.springframework.test.web.servlet.ResultActions register(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void validRegistration_returns201WithIdAndPendingVerification_andNeverExposesPassword() throws Exception {
        register(body("Ada@Example.com", "+2348012345678"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        var stored = customerRepository.findAll().getFirst();
        assertThat(stored.getPasswordHash()).startsWith("$2").doesNotContain("Sup3rSecret");
    }

    @Test
    void duplicateEmail_returns409_andCreatesNoSecondAccount() throws Exception {
        register(body("ada@example.com", "+2348012345678")).andExpect(status().isCreated());

        register(body("ADA@example.com", "+2348099999999"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_CUSTOMER"));

        assertThat(customerRepository.count()).isEqualTo(1);
    }

    @Test
    void duplicatePhoneNumber_returns409() throws Exception {
        register(body("ada@example.com", "+2348012345678")).andExpect(status().isCreated());

        register(body("other@example.com", "+2348012345678")).andExpect(status().isConflict());

        assertThat(customerRepository.count()).isEqualTo(1);
    }

    @Test
    void invalidInput_returns400WithFieldDetails() throws Exception {
        register("""
                {"firstName":"","lastName":"Obi","email":"not-an-email","phoneNumber":"0801",
                 "password":"weak","country":"Nigeria"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("firstName"),
                        org.hamcrest.Matchers.containsString("email"),
                        org.hamcrest.Matchers.containsString("phoneNumber"),
                        org.hamcrest.Matchers.containsString("password"),
                        org.hamcrest.Matchers.containsString("country"))))
                .andExpect(jsonPath("$.path").value("/api/v1/users"));

        assertThat(customerRepository.count()).isZero();
    }

    @Test
    void malformedJson_returns400() throws Exception {
        register("{not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    void simultaneousRegistrationsWithSameEmail_createExactlyOneAccount() throws Exception {
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            String phone = "+23480100000" + String.format("%02d", i);
            Callable<Integer> task = () -> {
                start.await();
                return register(body("race@example.com", phone)).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        int created = 0;
        for (Future<Integer> f : results) {
            int code = f.get();
            assertThat(code).isIn(201, 409);
            if (code == 201) created++;
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(customerRepository.count()).isEqualTo(1);
    }
}
