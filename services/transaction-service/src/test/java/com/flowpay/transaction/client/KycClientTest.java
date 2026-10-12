package com.flowpay.transaction.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowpay.transaction.exception.TransactionException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The wire contract with kyc-service's authorize endpoint, and failing closed when it cannot answer. */
class KycClientTest {

    private HttpServer server;
    private int status;
    private String body;
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private final AtomicReference<String> seenKey = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        status = 200;
        body = "{}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            seenPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private KycClient client() {
        return new KycClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .defaultHeader("X-Internal-Api-Key", "the-key").build());
    }

    @Test
    void asksTheFundWalletQuestion_withTheAmountAndKey() {
        body = "{\"allowed\":true,\"level\":1,\"maxSingleTransactionAmount\":50000.00,\"currency\":\"NGN\"}";
        UUID customer = UUID.randomUUID();

        var decision = client().mayFundWallet(customer, new BigDecimal("5000.00"), "NGN");

        assertThat(decision.allowed()).isTrue();
        assertThat(seenPath.get()).isEqualTo("POST /internal/kyc/customers/" + customer + "/authorize");
        assertThat(seenKey.get()).isEqualTo("the-key");
        assertThat(seenBody.get()).contains("\"operation\":\"FUND_WALLET\"").contains("\"currency\":\"NGN\"");
    }

    @Test
    void aRefusalCarriesTheReasonAndRequiredLevel() {
        body = "{\"allowed\":false,\"level\":0,\"reasonCode\":\"KYC_LEVEL_TOO_LOW\",\"requiredLevel\":1,\"maxSingleTransactionAmount\":0,\"currency\":\"NGN\"}";

        var decision = client().mayFundWallet(UUID.randomUUID(), new BigDecimal("1.00"), "NGN");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("KYC_LEVEL_TOO_LOW");
        assertThat(decision.requiredLevel()).isEqualTo(1);
    }

    @Test
    void ifKycCannotAnswer_theCustomerIsNotWavedThrough() {
        status = 500;
        assertThatThrownBy(() -> client().mayFundWallet(UUID.randomUUID(), BigDecimal.ONE, "NGN"))
                .isInstanceOfSatisfying(TransactionException.class, e -> assertThat(e.getErrorCode()).isEqualTo("KYC_UNAVAILABLE"));

        status = 401;
        assertThatThrownBy(() -> client().mayFundWallet(UUID.randomUUID(), BigDecimal.ONE, "NGN"))
                .isInstanceOfSatisfying(TransactionException.class, e -> assertThat(e.getErrorCode()).isEqualTo("KYC_UNAVAILABLE"));
    }

    @Test
    void anUnreachableKycServiceIsUnavailable() {
        var client = client();
        server.stop(0);

        assertThatThrownBy(() -> client.mayFundWallet(UUID.randomUUID(), BigDecimal.ONE, "NGN"))
                .isInstanceOfSatisfying(TransactionException.class, e -> assertThat(e.getErrorCode()).isEqualTo("KYC_UNAVAILABLE"));
    }
}
