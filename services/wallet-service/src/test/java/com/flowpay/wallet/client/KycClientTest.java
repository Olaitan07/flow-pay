package com.flowpay.wallet.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowpay.wallet.exception.WalletException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/** The wallet service must fail closed: if it cannot learn a customer's level, it must not guess. */
class KycClientTest {

    private HttpServer server;
    private int status;
    private String body;
    private final AtomicReference<String> seenKey = new AtomicReference<>();
    private final AtomicReference<String> seenPath = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        status = 200;
        body = "{}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            seenPath.set(exchange.getRequestURI().getPath());
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

    private KycClient client(int port) {
        return new KycClient(RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .defaultHeader("X-Internal-Api-Key", "the-key").build());
    }

    @Test
    void readsTheLevel_askingTheRightQuestionWithTheKey() {
        body = "{\"level\":2,\"allowedOperations\":[\"FUND_WALLET\"],\"maxSingleTransactionAmount\":5000000.00,\"currency\":\"NGN\"}";
        UUID customer = UUID.randomUUID();

        assertThat(client(server.getAddress().getPort()).levelOf(customer)).isEqualTo(2);
        assertThat(seenPath.get()).isEqualTo("/internal/kyc/customers/" + customer + "/capabilities");
        assertThat(seenKey.get()).isEqualTo("the-key");
    }

    @Test
    void aServerErrorMeansUnavailable_notLevelZero() {
        status = 500;

        assertThatThrownBy(() -> client(server.getAddress().getPort()).levelOf(UUID.randomUUID()))
                .isInstanceOfSatisfying(WalletException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getErrorCode()).isEqualTo("KYC_UNAVAILABLE");
                });
    }

    @Test
    void aRejectedKeyMeansUnavailable() {
        status = 401;

        assertThatThrownBy(() -> client(server.getAddress().getPort()).levelOf(UUID.randomUUID()))
                .isInstanceOfSatisfying(WalletException.class, e -> assertThat(e.getErrorCode()).isEqualTo("KYC_UNAVAILABLE"));
    }

    @Test
    void anUnreachableServiceMeansUnavailable() throws IOException {
        int port = server.getAddress().getPort();
        server.stop(0);

        assertThatThrownBy(() -> client(port).levelOf(UUID.randomUUID()))
                .isInstanceOfSatisfying(WalletException.class, e -> assertThat(e.getErrorCode()).isEqualTo("KYC_UNAVAILABLE"));
    }
}
