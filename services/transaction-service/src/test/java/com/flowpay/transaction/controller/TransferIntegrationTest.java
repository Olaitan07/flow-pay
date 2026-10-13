package com.flowpay.transaction.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowpay.transaction.AbstractIntegrationTest;
import com.flowpay.transaction.client.KycClient;
import com.flowpay.transaction.client.WalletClient;
import com.flowpay.transaction.client.WalletClient.Kind;
import com.flowpay.transaction.client.WalletClient.TransferOutcome;
import com.flowpay.transaction.client.WalletClient.WalletView;
import com.flowpay.transaction.entity.TransferStatus;
import com.flowpay.transaction.exception.TransactionException;
import com.flowpay.transaction.service.TransferProcessor;
import com.flowpay.transaction.service.TransferRecoveryJob;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * P2P transfers end to end. wallet-service and kyc-service are the system's boundaries, so they are replaced by mocks
 * here (their wire contracts are tested in the client tests, and the money movement itself in wallet-service's tests);
 * everything else is real, including Postgres.
 */
class TransferIntegrationTest extends AbstractIntegrationTest {

    static final String CUSTOMER = "X-Authenticated-Customer-Id";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransferRecoveryJob recoveryJob;
    @Autowired TransferProcessor processor;
    @Autowired com.flowpay.transaction.repository.TransferRepository transfers;
    @MockitoBean WalletClient walletClient;
    @MockitoBean KycClient kycClient;

    UUID ada;
    UUID bola;
    UUID adaWallet;
    UUID bolaWallet;

    @BeforeEach
    void freshWorld() {
        jdbc.update("delete from transfer_status_changes");
        jdbc.update("delete from transfers");
        reset(walletClient, kycClient);
        ada = UUID.randomUUID();
        bola = UUID.randomUUID();
        adaWallet = UUID.randomUUID();
        bolaWallet = UUID.randomUUID();
        walletFor(ada, adaWallet, "ACTIVE");
        walletFor(bola, bolaWallet, "ACTIVE");
        when(kycClient.mayTransfer(any(), any(), anyString())).thenReturn(new KycClient.Decision(true, 1, null, null));
        walletMovesMoney();
    }

    // ------------------------------------------------------------------ fakes for the boundaries

    void walletFor(UUID owner, UUID wallet, String status) {
        when(walletClient.findByCustomer(owner, "NGN")).thenReturn(Optional.of(new WalletView(wallet, owner, "NGN", status)));
    }

    void walletMovesMoney() {
        when(walletClient.transfer(anyString(), any(), any(), any(), any(), anyString()))
                .thenReturn(new TransferOutcome(Kind.CREDITED, null, null));
    }

    void walletDown() {
        when(walletClient.transfer(anyString(), any(), any(), any(), any(), anyString()))
                .thenReturn(new TransferOutcome(Kind.UNAVAILABLE, null, null));
    }

    void walletRefuses(String code) {
        when(walletClient.transfer(anyString(), any(), any(), any(), any(), anyString()))
                .thenReturn(new TransferOutcome(Kind.REFUSED, code, "wallet says " + code));
    }

    void kycRefuses(String code, Integer requiredLevel) {
        when(kycClient.mayTransfer(any(), any(), anyString())).thenReturn(new KycClient.Decision(false, 0, code, requiredLevel));
    }

    // ------------------------------------------------------------------ helpers

    ResultActions as(UUID customer, MockHttpServletRequestBuilder request) throws Exception {
        if (customer != null) request.header(CUSTOMER, customer.toString());
        return mockMvc.perform(request);
    }

    static String transferJson(UUID recipient, String amount, String currency, String narration) {
        return "{\"recipientCustomerId\":\"%s\",\"amount\":%s,\"currency\":\"%s\"%s}".formatted(recipient, amount, currency,
                narration == null ? "" : ",\"narration\":\"" + narration + "\"");
    }

    ResultActions send(UUID sender, String key, UUID recipient, String amount) throws Exception {
        return send(sender, key, recipient, amount, null);
    }

    ResultActions send(UUID sender, String key, UUID recipient, String amount, String narration) throws Exception {
        var request = post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                .content(transferJson(recipient, amount, "NGN", narration));
        if (key != null) request.header("Idempotency-Key", key);
        return as(sender, request);
    }

    String sent(String key, String amount) throws Exception {
        ResultActions result = send(ada, key, bola, amount).andExpect(status().isCreated());
        return com.jayway.jsonpath.JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.reference");
    }

    String statusOf(String reference) {
        return jdbc.queryForObject("select status from transfers where reference = ?", String.class, reference);
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    int history(String reference) {
        return count("select count(*) from transfer_status_changes c join transfers t on t.id = c.transfer_id where t.reference = ?", reference);
    }

    // ------------------------------------------------------------------ the acceptance example

    @Test
    void aTransferOfTenThousandWithAHundredFeeCostsTheSenderTenThousandOneHundred() throws Exception {
        send(ada, "ABC123", bola, "10000.00", "Rent for October").andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/transfers/TRF-")))
                .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.matchesPattern("TRF-[0-9A-F]{20}")))
                .andExpect(jsonPath("$.direction").value("SENT"))
                .andExpect(jsonPath("$.counterpartyCustomerId").value(bola.toString()))
                .andExpect(jsonPath("$.amount").value(10000.00))
                .andExpect(jsonPath("$.fee").value(100.00))
                .andExpect(jsonPath("$.total").value(10100.00))
                .andExpect(jsonPath("$.currency").value("NGN"))
                .andExpect(jsonPath("$.narration").value("Rent for October"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // The wallet service is told: take 10,100 from Ada's wallet (10,000 + 100), give Bola 10,000, give the platform 100.
        var reference = ArgumentCaptor.forClass(String.class);
        verify(walletClient).transfer(reference.capture(), eq(adaWallet), eq(bolaWallet), eq(new BigDecimal("10000.00")),
                eq(new BigDecimal("100.00")), eq("NGN"));
        assertThat(count("select count(*) from transfers where total = amount + fee and total = 10100.00")).isEqualTo(1);
        assertThat(history(reference.getValue())).isEqualTo(2); // requested, then succeeded
        assertThat(statusOf(reference.getValue())).isEqualTo("SUCCESS");
    }

    @Test
    void aQuoteShowsAmountFeeAndTotalAndRecordsNothing() throws Exception {
        as(ada, post("/api/v1/transfers/quote").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":10000.00,\"currency\":\"NGN\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(10000.00)).andExpect(jsonPath("$.fee").value(100.00))
                .andExpect(jsonPath("$.total").value(10100.00)).andExpect(jsonPath("$.currency").value("NGN"));
        assertThat(count("select count(*) from transfers")).isZero();
        verify(walletClient, never()).transfer(anyString(), any(), any(), any(), any(), anyString());

        as(ada, post("/api/v1/transfers/quote").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":0,\"currency\":\"NGN\"}")).andExpect(status().isBadRequest());
        as(null, post("/api/v1/transfers/quote").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":10,\"currency\":\"NGN\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyTransferGetsItsOwnUniqueReference() throws Exception {
        List<String> references = new ArrayList<>();
        for (int i = 0; i < 25; i++) references.add(sent("key-" + i, "10.00"));
        assertThat(references).doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------ invalid requests

    @Test
    void zeroNegativeAndOverPreciseAmountsAreRejectedWithoutTouchingAnything() throws Exception {
        for (String amount : List.of("0", "0.00", "-1", "-0.01", "10.001", "1e2x")) {
            send(ada, "k-" + amount, bola, amount).andExpect(status().isBadRequest());
        }
        assertThat(count("select count(*) from transfers")).isZero();
        verify(walletClient, never()).transfer(anyString(), any(), any(), any(), any(), anyString());
        verify(kycClient, never()).mayTransfer(any(), any(), anyString());
    }

    @Test
    void aMalformedRequestIsRejected() throws Exception {
        as(ada, post("/api/v1/transfers").header("Idempotency-Key", "k1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":10,\"currency\":\"NGN\"}")).andExpect(status().isBadRequest()); // no recipient
        as(ada, post("/api/v1/transfers").header("Idempotency-Key", "k1").contentType(MediaType.APPLICATION_JSON)
                .content(transferJson(bola, "10", "ngn", null))).andExpect(status().isBadRequest());
        as(ada, post("/api/v1/transfers").header("Idempotency-Key", "k1").contentType(MediaType.APPLICATION_JSON)
                .content("{not json")).andExpect(status().isBadRequest());
        send(ada, "k1", bola, "10", "x".repeat(141)).andExpect(status().isBadRequest());
        send(ada, "k1", bola, "10", "x".repeat(140)).andExpect(status().isCreated());
    }

    @Test
    void anIdempotencyKeyIsRequiredAndMustBeWellFormed() throws Exception {
        send(ada, null, bola, "10.00").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        send(ada, "has space", bola, "10.00").andExpect(status().isBadRequest());
        send(ada, "x".repeat(101), bola, "10.00").andExpect(status().isBadRequest());
        send(ada, "x".repeat(100), bola, "10.00").andExpect(status().isCreated());
        assertThat(count("select count(*) from transfers")).isEqualTo(1);
    }

    @Test
    void aTransferNeedsALoggedInCustomer() throws Exception {
        send(null, "k1", bola, "10.00").andExpect(status().isUnauthorized());
        as(null, get("/api/v1/transfers")).andExpect(status().isUnauthorized());
        as(null, get("/api/v1/transfers/TRF-X")).andExpect(status().isUnauthorized());
        assertThat(count("select count(*) from transfers")).isZero();
    }

    @Test
    void aCustomerCannotSendToThemselves() throws Exception {
        send(ada, "k1", ada, "10.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SELF_TRANSFER"));
        assertThat(count("select count(*) from transfers")).isZero();
        verify(walletClient, never()).transfer(anyString(), any(), any(), any(), any(), anyString());
    }

    // ------------------------------------------------------------------ KYC

    @Test
    void anUnverifiedCustomerCannotSend() throws Exception {
        kycRefuses("KYC_LEVEL_TOO_LOW", 1);
        send(ada, "k1", bola, "10.00").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("KYC_LEVEL_TOO_LOW"));
        assertNothingHappened();
    }

    @Test
    void anAmountAboveTheLevelsLimitIsRefused() throws Exception {
        kycRefuses("KYC_LIMIT_EXCEEDED", null);
        send(ada, "k1", bola, "60000.00").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("KYC_LIMIT_EXCEEDED"));
        assertNothingHappened();
    }

    @Test
    void anUnsupportedCurrencyIsRefused() throws Exception {
        kycRefuses("UNSUPPORTED_CURRENCY", null);
        send(ada, "k1", bola, "10.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_CURRENCY"));
        assertNothingHappened();
    }

    @Test
    void ifKycCannotAnswer_nothingIsSentAndNothingIsRecorded() throws Exception {
        when(kycClient.mayTransfer(any(), any(), anyString())).thenThrow(TransactionException.unavailable("KYC_UNAVAILABLE", "down"));
        send(ada, "k1", bola, "10.00").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("KYC_UNAVAILABLE"));
        assertNothingHappened();
    }

    @Test
    void theKycCheckIsMadeForTheSenderAndTheAmountWithoutTheFee() throws Exception {
        send(ada, "k1", bola, "10000.00").andExpect(status().isCreated());
        verify(kycClient).mayTransfer(ada, new BigDecimal("10000.00"), "NGN");
    }

    void assertNothingHappened() {
        assertThat(count("select count(*) from transfers")).isZero();
        verify(walletClient, never()).transfer(anyString(), any(), any(), any(), any(), anyString());
    }

    // ------------------------------------------------------------------ wallets

    @Test
    void aSenderWithoutAWalletInThatCurrencyIsTold() throws Exception {
        when(walletClient.findByCustomer(ada, "NGN")).thenReturn(Optional.empty());
        send(ada, "k1", bola, "10.00").andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("WALLET_NOT_FOUND"));
        assertNothingHappened();
    }

    @Test
    void aSenderWhoseWalletCannotSendIsRefused() throws Exception {
        for (String blocked : List.of("FROZEN", "BLOCKED", "CLOSED")) {
            walletFor(ada, adaWallet, blocked);
            send(ada, "k-" + blocked, bola, "10.00").andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("WALLET_NOT_ACTIVE"));
        }
        assertNothingHappened();
    }

    @Test
    void aRecipientWithNoWalletIsNotFound() throws Exception {
        when(walletClient.findByCustomer(bola, "NGN")).thenReturn(Optional.empty());
        send(ada, "k1", bola, "10.00").andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("RECIPIENT_NOT_FOUND"));
        assertNothingHappened();
    }

    @Test
    void aRecipientWhoCannotReceiveIsRefused_butAFrozenOneStillCan() throws Exception {
        walletFor(bola, bolaWallet, "BLOCKED");
        send(ada, "k1", bola, "10.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RECIPIENT_NOT_ACCEPTING_FUNDS"));
        assertNothingHappened();

        walletFor(bola, bolaWallet, "FROZEN");
        send(ada, "k2", bola, "10.00").andExpect(status().isCreated());
    }

    @Test
    void ifTheWalletServiceCannotBeAskedWhoTheRecipientIs_nothingIsRecorded() throws Exception {
        when(walletClient.findByCustomer(bola, "NGN")).thenThrow(TransactionException.unavailable("WALLET_UNAVAILABLE", "down"));
        send(ada, "k1", bola, "10.00").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("WALLET_UNAVAILABLE"));
        assertNothingHappened();
    }

    // ------------------------------------------------------------------ the wallet decides

    @Test
    void whenTheWalletRefusesForLackOfFunds_theTransferFailsAndSaysWhy() throws Exception {
        walletRefuses("INSUFFICIENT_FUNDS");
        send(ada, "k1", bola, "50000.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.message").value("Not enough available balance to cover the amount and the fee"));

        assertThat(count("select count(*) from transfers where status = 'FAILED' and failure_code = 'INSUFFICIENT_FUNDS'")).isEqualTo(1);
        String reference = jdbc.queryForObject("select reference from transfers", String.class);
        assertThat(history(reference)).isEqualTo(2);
    }

    @Test
    void retryingAFailedTransferGivesTheSameAnswerAndAsksTheWalletNothingMore() throws Exception {
        walletRefuses("INSUFFICIENT_FUNDS");
        send(ada, "k1", bola, "50000.00").andExpect(status().isUnprocessableEntity());
        walletMovesMoney(); // even if the customer has since been topped up, the key's answer does not change

        send(ada, "k1", bola, "50000.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));
        verify(walletClient, times(1)).transfer(anyString(), any(), any(), any(), any(), anyString());
        assertThat(count("select count(*) from transfers")).isEqualTo(1);
    }

    @Test
    void aFailedTransferCannotLaterBecomeASuccess() throws Exception {
        walletRefuses("INSUFFICIENT_FUNDS");
        send(ada, "k1", bola, "10.00").andExpect(status().isUnprocessableEntity());
        walletMovesMoney();

        assertThat(recoveryJob.run()).isZero();
        String reference = jdbc.queryForObject("select reference from transfers", String.class);
        assertThat(statusOf(reference)).isEqualTo("FAILED");
        var failed = jdbc.queryForObject("select count(*) from transfers where status = 'FAILED'", Integer.class);
        assertThat(failed).isEqualTo(1);
    }

    @Test
    void theWalletsOtherRefusalsAreAlsoFinalAndExplained() throws Exception {
        walletRefuses("RECIPIENT_NOT_ACCEPTING_FUNDS");
        send(ada, "k1", bola, "10.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RECIPIENT_NOT_ACCEPTING_FUNDS"));
        walletRefuses("SOMETHING_NEW");
        send(ada, "k2", bola, "10.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SOMETHING_NEW")).andExpect(jsonPath("$.message").value("wallet says SOMETHING_NEW"));
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void sendingTheSameRequestAgainReturnsTheOriginalAndMovesNothingTwice() throws Exception {
        String first = com.jayway.jsonpath.JsonPath.read(send(ada, "ABC123", bola, "10000.00", "Rent")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.reference");

        send(ada, "ABC123", bola, "10000.00", "Rent").andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(first)).andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.total").value(10100.00));

        verify(walletClient, times(1)).transfer(anyString(), any(), any(), any(), any(), anyString());
        assertThat(count("select count(*) from transfers")).isEqualTo(1);
        assertThat(history(first)).isEqualTo(2);
    }

    @Test
    void aKeyReusedForADifferentRequestIsRefused() throws Exception {
        sent("ABC123", "10000.00");

        send(ada, "ABC123", bola, "50000.00").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REUSED"));
        send(ada, "ABC123", UUID.randomUUID(), "10000.00").andExpect(status().isConflict());
        send(ada, "ABC123", bola, "10000.00", "a different note").andExpect(status().isConflict());

        verify(walletClient, times(1)).transfer(anyString(), any(), any(), any(), any(), anyString());
        assertThat(count("select count(*) from transfers")).isEqualTo(1);
    }

    @Test
    void theSameAmountWrittenDifferentlyIsStillTheSameRequest() throws Exception {
        sent("ABC123", "10000.00");
        send(ada, "ABC123", bola, "10000").andExpect(status().isOk());
        send(ada, "ABC123", bola, "10000.0").andExpect(status().isOk());
        assertThat(count("select count(*) from transfers")).isEqualTo(1);
    }

    @Test
    void keysBelongToTheCustomerWhoMadeThem() throws Exception {
        sent("ABC123", "10.00");
        walletFor(bola, bolaWallet, "ACTIVE");
        send(bola, "ABC123", ada, "10.00").andExpect(status().isCreated());
        assertThat(count("select count(*) from transfers")).isEqualTo(2);
    }

    @Test
    void aRetryDoesNotRunTheChecksAgain() throws Exception {
        sent("ABC123", "10.00");
        reset(kycClient);
        when(kycClient.mayTransfer(any(), any(), anyString())).thenThrow(TransactionException.unavailable("KYC_UNAVAILABLE", "down"));
        send(ada, "ABC123", bola, "10.00").andExpect(status().isOk());
    }

    @Test
    void theSameRequestSentManyTimesAtOnceCreatesOneTransfer() throws Exception {
        List<Integer> statuses = runTogether(8, i -> () -> send(ada, "ABC123", bola, "10000.00").andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200, 201);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(count("select count(*) from transfers")).isEqualTo(1);
        // Whoever reached the wallet service used the same reference, so the money moves once there.
        var references = ArgumentCaptor.forClass(String.class);
        verify(walletClient, atLeastOnce()).transfer(references.capture(), any(), any(), any(), any(), anyString());
        assertThat(references.getAllValues()).doesNotContainNull().containsOnly(references.getValue());
    }

    @Test
    void differentTransfersAtOnceAreAllRecordedSeparately() throws Exception {
        List<Integer> statuses = runTogether(10, i -> () -> send(ada, "key-" + i, bola, "10.00").andReturn().getResponse().getStatus());
        assertThat(statuses).containsOnly(201);
        assertThat(count("select count(distinct reference) from transfers")).isEqualTo(10);
    }

    // ------------------------------------------------------------------ the wallet service is unreachable

    @Test
    void ifTheWalletServiceCannotConfirm_theTransferStaysProcessingAndIsNotFailed() throws Exception {
        walletDown();

        String reference = com.jayway.jsonpath.JsonPath.read(send(ada, "k1", bola, "10.00").andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/transfers/TRF-")))
                .andExpect(jsonPath("$.status").value("PROCESSING")).andReturn().getResponse().getContentAsString(), "$.reference");

        assertThat(statusOf(reference)).isEqualTo("PROCESSING");
        assertThat(history(reference)).isEqualTo(1);
    }

    @Test
    void theRecoverySweepFinishesAnUnconfirmedTransferWithTheSameReference() throws Exception {
        walletDown();
        String reference = com.jayway.jsonpath.JsonPath.read(send(ada, "k1", bola, "10.00").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(), "$.reference");

        assertThat(recoveryJob.run()).isEqualTo(1);
        assertThat(statusOf(reference)).isEqualTo("PROCESSING"); // still down: still safe, still waiting

        walletMovesMoney();
        assertThat(recoveryJob.run()).isEqualTo(1);
        assertThat(statusOf(reference)).isEqualTo("SUCCESS");
        assertThat(history(reference)).isEqualTo(2);

        var seen = ArgumentCaptor.forClass(String.class);
        verify(walletClient, times(3)).transfer(seen.capture(), any(), any(), any(), any(), anyString());
        assertThat(seen.getAllValues()).containsOnly(reference);

        assertThat(recoveryJob.run()).isZero(); // nothing left to do
    }

    @Test
    void recoveryRecordsAClearRefusalAsFailed() throws Exception {
        walletDown();
        String reference = com.jayway.jsonpath.JsonPath.read(send(ada, "k1", bola, "10.00").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(), "$.reference");

        walletRefuses("INSUFFICIENT_FUNDS");
        recoveryJob.run();

        assertThat(statusOf(reference)).isEqualTo("FAILED");
        assertThat(count("select count(*) from transfers where reference = ? and failure_code = 'INSUFFICIENT_FUNDS'", reference)).isEqualTo(1);
    }

    @Test
    void retryingWithTheSameKeyFinishesAnUnconfirmedTransfer() throws Exception {
        walletDown();
        String reference = com.jayway.jsonpath.JsonPath.read(send(ada, "k1", bola, "10.00").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(), "$.reference");

        walletMovesMoney();
        send(ada, "k1", bola, "10.00").andExpect(status().isOk()).andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(count("select count(*) from transfers")).isEqualTo(1);
    }

    @Test
    void retryingWhileStillDownStaysProcessing() throws Exception {
        walletDown();
        send(ada, "k1", bola, "10.00").andExpect(status().isAccepted());
        send(ada, "k1", bola, "10.00").andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PROCESSING"));
        assertThat(count("select count(*) from transfers")).isEqualTo(1);
    }

    @Test
    void aSettledTransferIsNeverSentToTheWalletAgain() throws Exception {
        String reference = sent("k1", "10.00");
        var transfer = new com.flowpay.transaction.entity.Transfer(reference, ada, bola, adaWallet, bolaWallet,
                new BigDecimal("10.00"), new BigDecimal("100.00"), "NGN", null, "k1", "h", java.time.Instant.now());
        reset(walletClient);

        assertThat(recoveryJob.run()).isZero();
        // Even handed a stale PROCESSING copy of a transfer that has already succeeded, nothing is sent to the wallet.
        var settled = processor.settle(jdbcLoad(reference));
        assertThat(settled.getStatus()).isEqualTo(TransferStatus.SUCCESS);
        verify(walletClient, never()).transfer(anyString(), any(), any(), any(), any(), anyString());
        assertThat(transfer.getTotal()).isEqualByComparingTo("110.00");
    }

    com.flowpay.transaction.entity.Transfer jdbcLoad(String reference) {
        return transfers.findAll().stream().filter(t -> t.getReference().equals(reference)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ who can see what

    @Test
    void theSenderSeesTheFeeAndTheRecipientDoesNot() throws Exception {
        String reference = sent("k1", "10000.00");

        as(ada, get("/api/v1/transfers/" + reference)).andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("SENT")).andExpect(jsonPath("$.counterpartyCustomerId").value(bola.toString()))
                .andExpect(jsonPath("$.fee").value(100.00)).andExpect(jsonPath("$.total").value(10100.00));

        as(bola, get("/api/v1/transfers/" + reference)).andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("RECEIVED")).andExpect(jsonPath("$.counterpartyCustomerId").value(ada.toString()))
                .andExpect(jsonPath("$.amount").value(10000.00)).andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.fee").doesNotExist()).andExpect(jsonPath("$.total").doesNotExist());
    }

    @Test
    void aStrangerCannotSeeATransfer_anUnknownOneLooksTheSame() throws Exception {
        String reference = sent("k1", "10.00");
        UUID stranger = UUID.randomUUID();

        as(stranger, get("/api/v1/transfers/" + reference)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TRANSFER_NOT_FOUND"));
        as(ada, get("/api/v1/transfers/TRF-DOESNOTEXIST")).andExpect(status().isNotFound());
        as(stranger, get("/api/v1/transfers")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aRecipientNeverSeesATransferThatDidNotComplete() throws Exception {
        walletRefuses("INSUFFICIENT_FUNDS");
        send(ada, "k1", bola, "10.00").andExpect(status().isUnprocessableEntity());
        walletDown();
        send(ada, "k2", bola, "10.00").andExpect(status().isAccepted());
        List<String> references = jdbc.queryForList("select reference from transfers", String.class);

        for (String reference : references) {
            as(bola, get("/api/v1/transfers/" + reference)).andExpect(status().isNotFound());
            as(ada, get("/api/v1/transfers/" + reference)).andExpect(status().isOk());
        }
        as(bola, get("/api/v1/transfers")).andExpect(jsonPath("$.length()").value(0));
        as(ada, get("/api/v1/transfers")).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void theListShowsSentAndReceivedNewestFirstAndHonoursTheLimit() throws Exception {
        sent("k1", "1.00");
        walletFor(bola, bolaWallet, "ACTIVE");
        send(bola, "k2", ada, "2.00").andExpect(status().isCreated());
        sent("k3", "3.00");

        as(ada, get("/api/v1/transfers")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].amount").value(3.00)).andExpect(jsonPath("$[0].direction").value("SENT"))
                .andExpect(jsonPath("$[1].amount").value(2.00)).andExpect(jsonPath("$[1].direction").value("RECEIVED"))
                .andExpect(jsonPath("$[2].amount").value(1.00));
        as(ada, get("/api/v1/transfers").param("limit", "2")).andExpect(jsonPath("$.length()").value(2));
        as(ada, get("/api/v1/transfers").param("limit", "0")).andExpect(jsonPath("$.length()").value(1));
    }

    // ------------------------------------------------------------------ the database refuses nonsense too

    @Test
    void theDatabaseItselfRefusesATransferThatDoesNotAddUp() {
        String insert = "insert into transfers (id, reference, sender_customer_id, recipient_customer_id, sender_wallet_id, "
                + "recipient_wallet_id, amount, fee, total, currency, status, failure_code, idempotency_key, request_hash, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, ?, ?, gen_random_uuid(), gen_random_uuid(), ?, ?, ?, 'NGN', ?, ?, ?, 'h', now(), now())";
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // control: a valid row goes in
        jdbc.update(insert, "OK", a, b, 10, 1, 11, "PROCESSING", null, "k0");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, "T1", a, b, 10, 1, 12, "PROCESSING", null, "k1"))
                .isInstanceOf(DataIntegrityViolationException.class); // total is not amount + fee
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, "T2", a, b, 0, 0, 0, "PROCESSING", null, "k2"))
                .isInstanceOf(DataIntegrityViolationException.class); // zero amount
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, "T3", a, b, 10, -1, 9, "PROCESSING", null, "k3"))
                .isInstanceOf(DataIntegrityViolationException.class); // negative fee
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, "T4", a, a, 10, 1, 11, "PROCESSING", null, "k4"))
                .isInstanceOf(DataIntegrityViolationException.class); // to self
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, "T5", a, b, 10, 1, 11, "FAILED", null, "k5"))
                .isInstanceOf(DataIntegrityViolationException.class); // failed without a reason
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, "T6", a, b, 10, 1, 11, "PROCESSING", "X", "k6"))
                .isInstanceOf(DataIntegrityViolationException.class); // a reason without having failed
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, "T7", a, b, 10, 1, 11, "DONE", null, "k7"))
                .isInstanceOf(DataIntegrityViolationException.class); // unknown status
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, "T8", a, b, 10, 1, 11, "PROCESSING", null, "k0"))
                .isInstanceOf(DataIntegrityViolationException.class); // same customer, same key
        assertThat(count("select count(*) from transfers")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ concurrency harness

    <T> List<T> runTogether(int n, java.util.function.IntFunction<Callable<T>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<T> work = task.apply(i);
            futures.add(pool.submit(() -> {
                go.await();
                return work.call();
            }));
        }
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) results.add(f.get());
        pool.shutdown();
        return results;
    }
}
