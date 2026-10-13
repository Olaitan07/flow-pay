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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

/** The wire contract with wallet-service: the exact JSON it sends and receives, and what each answer means. */
class WalletClientTest {

    private HttpServer server;
    private int status;
    private String body;
    private final AtomicReference<String> seenKey = new AtomicReference<>();
    private final AtomicReference<String> seenPath = new AtomicReference<>();
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

    private WalletClient client() {
        return new WalletClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .defaultHeader("X-Internal-Api-Key", "the-key").build());
    }

    private final UUID walletId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    @Test
    void findsAWallet_readingTheRealResponseShape() {
        body = """
                {"wallet":{"id":"%s","customerId":"%s","currency":"NGN","ledgerBalance":0.00,"availableBalance":0.00,
                 "status":"FROZEN","createdAt":"2026-10-12T10:00:00Z"},"statusHistory":[]}""".formatted(walletId, customerId);

        var wallet = client().find(walletId).orElseThrow();

        assertThat(wallet.id()).isEqualTo(walletId);
        assertThat(wallet.customerId()).isEqualTo(customerId);
        assertThat(wallet.currency()).isEqualTo("NGN");
        assertThat(wallet.status()).isEqualTo("FROZEN");
        assertThat(seenPath.get()).isEqualTo("GET /internal/wallets/" + walletId);
        assertThat(seenKey.get()).isEqualTo("the-key");
    }

    @Test
    void aMissingWalletIsEmpty_notAnError() {
        status = 404;
        body = "{\"error\":\"WALLET_NOT_FOUND\"}";

        assertThat(client().find(walletId)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 500, 503})
    void aLookupThatCannotAnswerIsUnavailable_notMissing(int code) {
        status = code;

        assertThatThrownBy(() -> client().find(walletId))
                .isInstanceOfSatisfying(TransactionException.class, e -> assertThat(e.getErrorCode()).isEqualTo("WALLET_UNAVAILABLE"));
    }

    @Test
    void creditSendsTheReferenceAndExactAmount() {
        status = 201;

        var outcome = client().credit(walletId, "FND-ABC", new BigDecimal("5000.00"), "NGN");

        assertThat(outcome.kind()).isEqualTo(WalletClient.Kind.CREDITED);
        assertThat(seenPath.get()).isEqualTo("POST /internal/wallets/" + walletId + "/credit");
        assertThat(seenKey.get()).isEqualTo("the-key");
        assertThat(seenBody.get()).contains("\"reference\":\"FND-ABC\"").contains("\"currency\":\"NGN\"")
                .containsPattern("\"amount\":5000(\\.00)?[,}]");
    }

    @Test
    void aRepeatedCreditThatTheWalletRecognisesCountsAsCredited() {
        status = 200;

        assertThat(client().credit(walletId, "FND-ABC", new BigDecimal("1.00"), "NGN").kind()).isEqualTo(WalletClient.Kind.CREDITED);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 422, 400})
    void aClearRefusalCarriesTheWalletsReason(int code) {
        status = code;
        body = "{\"status\":" + code + ",\"error\":\"WALLET_NOT_ACCEPTING_FUNDS\",\"message\":\"no\"}";

        var outcome = client().credit(walletId, "FND-ABC", new BigDecimal("1.00"), "NGN");

        assertThat(outcome.kind()).isEqualTo(WalletClient.Kind.REFUSED);
        assertThat(outcome.code()).isEqualTo("WALLET_NOT_ACCEPTING_FUNDS");
    }

    @Test
    void aRefusalWithNoReadableReasonStillCountsAsRefused() {
        status = 422;
        body = "not json";

        assertThat(client().credit(walletId, "FND-ABC", new BigDecimal("1.00"), "NGN").kind()).isEqualTo(WalletClient.Kind.REFUSED);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 408, 429, 500, 502, 503})
    void anythingThatSaysNothingAboutTheMoneyIsUnavailable_soItIsRetried(int code) {
        status = code;

        assertThat(client().credit(walletId, "FND-ABC", new BigDecimal("1.00"), "NGN").kind()).isEqualTo(WalletClient.Kind.UNAVAILABLE);
    }

    @Test
    void anUnreachableWalletServiceIsUnavailable_neverRefused() {
        var client = client();
        server.stop(0);

        assertThat(client.credit(walletId, "FND-ABC", new BigDecimal("1.00"), "NGN").kind()).isEqualTo(WalletClient.Kind.UNAVAILABLE);
        assertThatThrownBy(() -> client.find(walletId))
                .isInstanceOfSatisfying(TransactionException.class, e -> assertThat(e.getErrorCode()).isEqualTo("WALLET_UNAVAILABLE"));
    }

    // ------------------------------------------------------------------ finding a customer's wallet

    @Test
    void findsACustomersWalletByCurrency_readingTheRealResponseShape() {
        body = """
                {"id":"%s","customerId":"%s","currency":"NGN","ledgerBalance":0.00,"availableBalance":0.00,
                 "status":"ACTIVE","createdAt":"2026-10-12T10:00:00Z"}""".formatted(walletId, customerId);

        var wallet = client().findByCustomer(customerId, "NGN").orElseThrow();

        assertThat(wallet.id()).isEqualTo(walletId);
        assertThat(wallet.customerId()).isEqualTo(customerId);
        assertThat(wallet.status()).isEqualTo("ACTIVE");
        assertThat(seenPath.get()).isEqualTo("GET /internal/wallets/by-customer/" + customerId);
        assertThat(seenKey.get()).isEqualTo("the-key");
    }

    @Test
    void aCustomerWithNoWalletIsEmpty_notAnError() {
        status = 404;
        body = "{\"error\":\"WALLET_NOT_FOUND\"}";

        assertThat(client().findByCustomer(customerId, "NGN")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 500, 503})
    void aCustomerLookupThatCannotAnswerIsUnavailable_notMissing(int code) {
        status = code;

        assertThatThrownBy(() -> client().findByCustomer(customerId, "NGN"))
                .isInstanceOfSatisfying(TransactionException.class, e -> assertThat(e.getErrorCode()).isEqualTo("WALLET_UNAVAILABLE"));
    }

    // ------------------------------------------------------------------ transfers

    private final UUID recipientWalletId = UUID.randomUUID();

    @Test
    void transferSendsTheReferenceBothWalletsAndExactMoney() {
        status = 201;

        var outcome = client().transfer("TRF-ABC", walletId, recipientWalletId, new BigDecimal("10000.00"), new BigDecimal("100.00"), "NGN");

        assertThat(outcome.kind()).isEqualTo(WalletClient.Kind.CREDITED);
        assertThat(seenPath.get()).isEqualTo("POST /internal/wallets/transfer");
        assertThat(seenKey.get()).isEqualTo("the-key");
        assertThat(seenBody.get()).contains("\"reference\":\"TRF-ABC\"").contains("\"currency\":\"NGN\"")
                .contains("\"senderWalletId\":\"" + walletId + "\"")
                .contains("\"recipientWalletId\":\"" + recipientWalletId + "\"")
                .containsPattern("\"amount\":10000(\\.00)?[,}]").containsPattern("\"fee\":100(\\.00)?[,}]");
    }

    @Test
    void aRepeatedTransferThatTheWalletRecognisesCountsAsDone() {
        status = 200;

        assertThat(client().transfer("TRF-ABC", walletId, recipientWalletId, BigDecimal.ONE, BigDecimal.ZERO, "NGN").kind())
                .isEqualTo(WalletClient.Kind.CREDITED);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 422, 400})
    void aClearTransferRefusalCarriesTheWalletsReasonAndMessage(int code) {
        status = code;
        body = "{\"status\":" + code + ",\"error\":\"INSUFFICIENT_FUNDS\",\"message\":\"Not enough available balance\"}";

        var outcome = client().transfer("TRF-ABC", walletId, recipientWalletId, BigDecimal.ONE, BigDecimal.ZERO, "NGN");

        assertThat(outcome.kind()).isEqualTo(WalletClient.Kind.REFUSED);
        assertThat(outcome.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(outcome.message()).isEqualTo("Not enough available balance");
    }

    @Test
    void aTransferRefusalWithNoReadableReasonStillCountsAsRefused() {
        status = 422;
        body = "not json";

        var outcome = client().transfer("TRF-ABC", walletId, recipientWalletId, BigDecimal.ONE, BigDecimal.ZERO, "NGN");

        assertThat(outcome.kind()).isEqualTo(WalletClient.Kind.REFUSED);
        assertThat(outcome.code()).isEqualTo("HTTP_422");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 408, 429, 500, 502, 503})
    void aTransferAnswerThatSaysNothingAboutTheMoneyIsUnavailable_soItIsRetried(int code) {
        status = code;

        assertThat(client().transfer("TRF-ABC", walletId, recipientWalletId, BigDecimal.ONE, BigDecimal.ZERO, "NGN").kind())
                .isEqualTo(WalletClient.Kind.UNAVAILABLE);
    }

    @Test
    void anUnreachableWalletServiceMeansAnUnconfirmedTransfer_neverAFailedOne() {
        var client = client();
        server.stop(0);

        assertThat(client.transfer("TRF-ABC", walletId, recipientWalletId, BigDecimal.ONE, BigDecimal.ZERO, "NGN").kind())
                .isEqualTo(WalletClient.Kind.UNAVAILABLE);
    }
}
