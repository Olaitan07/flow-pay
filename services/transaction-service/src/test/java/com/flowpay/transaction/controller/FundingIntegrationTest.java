package com.flowpay.transaction.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.flowpay.transaction.client.WalletClient.CreditOutcome;
import com.flowpay.transaction.client.WalletClient.Kind;
import com.flowpay.transaction.client.WalletClient.WalletView;
import com.flowpay.transaction.exception.TransactionException;
import com.flowpay.transaction.service.FundingRecoveryJob;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Wallet funding end to end. wallet-service and kyc-service are the system's boundaries, so they are replaced by
 * mocks here (their real wire contracts are tested in the client tests); everything else is real, including Postgres.
 */
class FundingIntegrationTest extends AbstractIntegrationTest {

    static final String CUSTOMER = "X-Authenticated-Customer-Id";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FundingRecoveryJob recoveryJob;
    @MockitoBean WalletClient walletClient;
    @MockitoBean KycClient kycClient;

    UUID ada;
    UUID bola;
    UUID adaWallet;

    @BeforeEach
    void freshWorld() {
        jdbc.update("delete from funding_status_changes");
        jdbc.update("delete from fundings");
        reset(walletClient, kycClient);
        ada = UUID.randomUUID();
        bola = UUID.randomUUID();
        adaWallet = UUID.randomUUID();
        walletOf(adaWallet, ada, "NGN", "ACTIVE");
        when(kycClient.mayFundWallet(any(), any(), anyString())).thenReturn(new KycClient.Decision(true, 1, null, null));
        credited();
    }

    // ------------------------------------------------------------------ fakes for the boundaries

    void walletOf(UUID wallet, UUID owner, String currency, String status) {
        when(walletClient.find(wallet)).thenReturn(Optional.of(new WalletView(wallet, owner, currency, status)));
    }

    void credited() {
        when(walletClient.credit(any(), anyString(), any(), anyString())).thenReturn(new CreditOutcome(Kind.CREDITED, null));
    }

    void walletDown() {
        when(walletClient.credit(any(), anyString(), any(), anyString())).thenReturn(new CreditOutcome(Kind.UNAVAILABLE, null));
    }

    void walletRefuses(String code) {
        when(walletClient.credit(any(), anyString(), any(), anyString())).thenReturn(new CreditOutcome(Kind.REFUSED, code));
    }

    // ------------------------------------------------------------------ helpers

    ResultActions as(UUID customer, MockHttpServletRequestBuilder request) throws Exception {
        if (customer != null) request.header(CUSTOMER, customer.toString());
        return mockMvc.perform(request);
    }

    static String fundingJson(UUID wallet, String amount, String currency, String source) {
        return "{\"walletId\":\"%s\",\"amount\":%s,\"currency\":\"%s\",\"source\":\"%s\"}".formatted(wallet, amount, currency, source);
    }

    ResultActions start(UUID customer, UUID wallet, String amount) throws Exception {
        return as(customer, post("/api/v1/funding").contentType(MediaType.APPLICATION_JSON)
                .content(fundingJson(wallet, amount, "NGN", "BANK_TRANSFER")));
    }

    String started(String amount) throws Exception {
        ResultActions result = start(ada, adaWallet, amount).andExpect(status().isCreated());
        return com.jayway.jsonpath.JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.reference");
    }

    ResultActions internal(MockHttpServletRequestBuilder request, String actor) throws Exception {
        request.header("X-Internal-Api-Key", INTERNAL_API_KEY);
        if (actor != null) request.header("X-Actor", actor);
        return mockMvc.perform(request);
    }

    ResultActions confirm(String reference, String body) throws Exception {
        return internal(post("/internal/funding/" + reference + "/confirm").contentType(MediaType.APPLICATION_JSON).content(body), null);
    }

    ResultActions paid(String reference, String amount) throws Exception {
        return confirm(reference, "{\"outcome\":\"SUCCESS\",\"amount\":%s,\"currency\":\"NGN\",\"providerReference\":\"PSK_1\"}".formatted(amount));
    }

    ResultActions paymentFailed(String reference) throws Exception {
        return confirm(reference, "{\"outcome\":\"FAILED\",\"failureReason\":\"Insufficient funds at the bank\"}");
    }

    ResultActions retry(String reference, String actor) throws Exception {
        return internal(post("/internal/funding/" + reference + "/retry"), actor);
    }

    String statusOf(String reference) {
        return jdbc.queryForObject("select status from fundings where reference = ?", String.class, reference);
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    int history(String reference) {
        return count("select count(*) from funding_status_changes c join fundings f on f.id = c.funding_id where f.reference = ?", reference);
    }

    // ------------------------------------------------------------------ starting a funding

    @Test
    void startingAFundingRecordsItPending_andMovesNoMoney() throws Exception {
        start(ada, adaWallet, "5000.00").andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/funding/FND-")))
                .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.matchesPattern("FND-[0-9A-F]{20}")))
                .andExpect(jsonPath("$.walletId").value(adaWallet.toString()))
                .andExpect(jsonPath("$.amount").value(5000.00))
                .andExpect(jsonPath("$.currency").value("NGN"))
                .andExpect(jsonPath("$.source").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").exists());

        verify(walletClient, never()).credit(any(), anyString(), any(), anyString());
        assertThat(count("select count(*) from fundings")).isEqualTo(1);
        assertThat(count("select count(*) from funding_status_changes")).isEqualTo(1);
    }

    @Test
    void everyFundingGetsItsOwnUniqueReference() throws Exception {
        List<String> references = new ArrayList<>();
        for (int i = 0; i < 25; i++) references.add(started("10.00"));
        assertThat(references).doesNotHaveDuplicates();
    }

    @Test
    void allSourcesAreAccepted_andAnUnknownOneIsNot() throws Exception {
        for (String source : List.of("BANK_TRANSFER", "CARD", "EXTERNAL_PROVIDER")) {
            as(ada, post("/api/v1/funding").contentType(MediaType.APPLICATION_JSON)
                    .content(fundingJson(adaWallet, "10.00", "NGN", source))).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.source").value(source));
        }
        for (String source : List.of("CASH", "INTERNAL_TRANSFER", "card")) {
            as(ada, post("/api/v1/funding").contentType(MediaType.APPLICATION_JSON)
                    .content(fundingJson(adaWallet, "10.00", "NGN", source))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void aCustomerCannotFundSomeoneElsesWallet() throws Exception {
        UUID bolaWallet = UUID.randomUUID();
        walletOf(bolaWallet, bola, "NGN", "ACTIVE");

        start(ada, bolaWallet, "100.00").andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("WALLET_NOT_FOUND"));
        start(ada, UUID.randomUUID(), "100.00").andExpect(status().isNotFound());
        assertThat(count("select count(*) from fundings")).isZero();
    }

    @Test
    void theCurrencyMustMatchTheWallet() throws Exception {
        as(ada, post("/api/v1/funding").contentType(MediaType.APPLICATION_JSON)
                .content(fundingJson(adaWallet, "100.00", "USD", "CARD")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("CURRENCY_MISMATCH"));
        assertThat(count("select count(*) from fundings")).isZero();
    }

    @Test
    void aBlockedOrClosedWalletCannotBeFunded_butAFrozenOneCan() throws Exception {
        for (String blocked : List.of("BLOCKED", "CLOSED")) {
            walletOf(adaWallet, ada, "NGN", blocked);
            start(ada, adaWallet, "100.00").andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("WALLET_NOT_ACCEPTING_FUNDS"));
        }
        walletOf(adaWallet, ada, "NGN", "FROZEN");
        start(ada, adaWallet, "100.00").andExpect(status().isCreated());
    }

    @Test
    void aCustomerWhoIsNotVerifiedEnoughCannotFund() throws Exception {
        when(kycClient.mayFundWallet(eq(ada), any(), anyString())).thenReturn(new KycClient.Decision(false, 0, "KYC_LEVEL_TOO_LOW", 1));

        start(ada, adaWallet, "100.00").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("KYC_LEVEL_TOO_LOW"));
        assertThat(count("select count(*) from fundings")).isZero();
    }

    @Test
    void anAmountAboveTheVerificationLimitIsRefused() throws Exception {
        when(kycClient.mayFundWallet(eq(ada), any(), anyString())).thenReturn(new KycClient.Decision(false, 1, "KYC_LIMIT_EXCEEDED", null));

        start(ada, adaWallet, "999999.00").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("KYC_LIMIT_EXCEEDED"));
        assertThat(count("select count(*) from fundings")).isZero();
    }

    @Test
    void kycReceivesTheRealAmountAndCurrency() throws Exception {
        started("1234.50");
        verify(kycClient).mayFundWallet(eq(ada), org.mockito.ArgumentMatchers.argThat(a -> a.compareTo(new BigDecimal("1234.50")) == 0), eq("NGN"));
    }

    @Test
    void ifVerificationCannotBeChecked_nothingIsRecorded() throws Exception {
        when(kycClient.mayFundWallet(any(), any(), anyString()))
                .thenThrow(TransactionException.unavailable("KYC_UNAVAILABLE", "down"));

        start(ada, adaWallet, "100.00").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("KYC_UNAVAILABLE"));
        assertThat(count("select count(*) from fundings")).isZero();
    }

    @Test
    void ifTheWalletCannotBeChecked_nothingIsRecorded() throws Exception {
        when(walletClient.find(adaWallet)).thenThrow(TransactionException.unavailable("WALLET_UNAVAILABLE", "down"));

        start(ada, adaWallet, "100.00").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("WALLET_UNAVAILABLE"));
        assertThat(count("select count(*) from fundings")).isZero();
    }

    @Test
    void badAmountsAreRejected() throws Exception {
        for (String amount : List.of("0", "0.00", "-5.00", "10.001", "\"abc\"", "null", "1234567890123456.00")) {
            start(ada, adaWallet, amount).andExpect(status().isBadRequest());
        }
        assertThat(count("select count(*) from fundings")).isZero();
    }

    @Test
    void aMalformedRequestIsRejected() throws Exception {
        as(ada, post("/api/v1/funding").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        as(ada, post("/api/v1/funding").contentType(MediaType.APPLICATION_JSON).content("not json")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        as(ada, post("/api/v1/funding").contentType(MediaType.APPLICATION_JSON)
                .content("{\"walletId\":\"nope\",\"amount\":1.00,\"currency\":\"NGN\",\"source\":\"CARD\"}")).andExpect(status().isBadRequest());
        as(ada, post("/api/v1/funding").contentType(MediaType.APPLICATION_JSON)
                .content(fundingJson(adaWallet, "1.00", "ngn", "CARD"))).andExpect(status().isBadRequest());
    }

    @Test
    void everyCustomerCallNeedsAnIdentity() throws Exception {
        start(null, adaWallet, "100.00").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
        as(null, get("/api/v1/funding")).andExpect(status().isUnauthorized());
        as(null, get("/api/v1/funding/FND-X")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ reading fundings

    @Test
    void aCustomerSeesOnlyTheirOwnFundings_newestFirst() throws Exception {
        String first = started("10.00");
        String second = started("20.00");
        UUID bolaWallet = UUID.randomUUID();
        walletOf(bolaWallet, bola, "NGN", "ACTIVE");
        start(bola, bolaWallet, "30.00").andExpect(status().isCreated());

        as(ada, get("/api/v1/funding")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].reference").value(second)).andExpect(jsonPath("$[1].reference").value(first));
        as(ada, get("/api/v1/funding/" + first)).andExpect(status().isOk()).andExpect(jsonPath("$.reference").value(first));
        as(bola, get("/api/v1/funding/" + first)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("FUNDING_NOT_FOUND"));
        as(ada, get("/api/v1/funding/FND-DOESNOTEXIST")).andExpect(status().isNotFound());
        as(UUID.randomUUID(), get("/api/v1/funding")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theListHonoursItsLimit() throws Exception {
        for (int i = 0; i < 5; i++) started("10.00");
        as(ada, get("/api/v1/funding?limit=3")).andExpect(jsonPath("$.length()").value(3));
        as(ada, get("/api/v1/funding?limit=0")).andExpect(jsonPath("$.length()").value(1));
        as(ada, get("/api/v1/funding?limit=100000")).andExpect(jsonPath("$.length()").value(5));
    }

    // ------------------------------------------------------------------ a successful payment

    @Test
    void whenTheProviderConfirmsPayment_theWalletIsCreditedExactlyOnce_andTheFundingSucceeds() throws Exception {
        String reference = started("5000.00");

        paid(reference, "5000.00").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.providerReference").value("PSK_1"))
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[0].to").value("PENDING"))
                .andExpect(jsonPath("$.history[1].from").value("PENDING"))
                .andExpect(jsonPath("$.history[1].to").value("PROCESSING"))
                .andExpect(jsonPath("$.history[2].to").value("SUCCESS"));

        // the funding's reference IS the wallet's movement reference: that is what makes a retry safe
        verify(walletClient, times(1)).credit(eq(adaWallet), eq(reference), org.mockito.ArgumentMatchers.argThat(a -> a.compareTo(new BigDecimal("5000.00")) == 0), eq("NGN"));
        as(ada, get("/api/v1/funding/" + reference)).andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void aWalletIsNeverCreditedBeforeThePaymentIsConfirmed() throws Exception {
        started("5000.00");
        verify(walletClient, never()).credit(any(), anyString(), any(), anyString());
    }

    @Test
    void confirmingTheSamePaymentAgainChangesNothing() throws Exception {
        String reference = started("5000.00");
        paid(reference, "5000.00").andExpect(status().isOk());

        paid(reference, "5000.00").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        paid(reference, "5000.00").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(walletClient, times(1)).credit(any(), anyString(), any(), anyString());
        assertThat(history(reference)).isEqualTo(3);
    }

    @Test
    void simultaneousConfirmationsEndInOneSuccess_withEachStepRecordedOnce() throws Exception {
        String reference = started("5000.00");

        List<Integer> statuses = inParallel(10, i -> paid(reference, "5000.00").andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200);
        assertThat(statusOf(reference)).isEqualTo("SUCCESS");
        assertThat(history(reference)).isEqualTo(3);
        // any caller that reached the wallet used the same reference, so the wallet can only credit once
        org.mockito.Mockito.mockingDetails(walletClient).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("credit"))
                .forEach(inv -> assertThat((String) inv.getArgument(1)).isEqualTo(reference));
    }

    // ------------------------------------------------------------------ a failed payment

    @Test
    void aFailedPaymentNeverTouchesTheWallet() throws Exception {
        String reference = started("5000.00");

        paymentFailed(reference).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("Insufficient funds at the bank"));

        verify(walletClient, never()).credit(any(), anyString(), any(), anyString());
        as(ada, get("/api/v1/funding/" + reference)).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("Insufficient funds at the bank"));
    }

    @Test
    void aFailureWithNoReasonStillGetsOne() throws Exception {
        String reference = started("5000.00");
        confirm(reference, "{\"outcome\":\"FAILED\"}").andExpect(jsonPath("$.failureReason").value("Payment failed"));
    }

    @Test
    void aFailedFundingCanNeverLaterBecomeSuccessful() throws Exception {
        String reference = started("5000.00");
        paymentFailed(reference);

        paid(reference, "5000.00").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("FUNDING_ALREADY_FAILED"));

        verify(walletClient, never()).credit(any(), anyString(), any(), anyString());
        assertThat(statusOf(reference)).isEqualTo("FAILED");
    }

    @Test
    void aSuccessfulFundingCanNeverLaterBeMarkedFailed() throws Exception {
        String reference = started("5000.00");
        paid(reference, "5000.00");

        paymentFailed(reference).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("FUNDING_STATE_CONFLICT"));
        assertThat(statusOf(reference)).isEqualTo("SUCCESS");
    }

    @Test
    void repeatingAFailureChangesNothing() throws Exception {
        String reference = started("5000.00");
        paymentFailed(reference);
        paymentFailed(reference).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(history(reference)).isEqualTo(2);
    }

    @Test
    void contradictoryConfirmationsAtTheSameTime_leaveOneConsistentOutcome() throws Exception {
        for (int round = 0; round < 10; round++) {
            String reference = started("100.00");
            List<Integer> statuses = inParallel(2, i -> i == 0
                    ? paid(reference, "100.00").andReturn().getResponse().getStatus()
                    : paymentFailed(reference).andReturn().getResponse().getStatus());

            String outcome = statusOf(reference);
            assertThat(outcome).isIn("SUCCESS", "FAILED");
            boolean credited = org.mockito.Mockito.mockingDetails(walletClient).getInvocations().stream()
                    .anyMatch(inv -> inv.getMethod().getName().equals("credit") && reference.equals(inv.getArgument(1)));
            // the wallet is credited if, and only if, the funding ended SUCCESS
            assertThat(credited).isEqualTo(outcome.equals("SUCCESS"));
            // and the loser was told so, not silently accepted
            assertThat(statuses).contains(200);
            assertThat(statuses.stream().filter(s -> s == 409)).hasSizeLessThanOrEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ what the provider reports must match

    @Test
    void aDifferentAmountIsNeverCredited_itWaitsForAPerson() throws Exception {
        String reference = started("5000.00");

        paid(reference, "4000.00").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUIRES_REVIEW"))
                .andExpect(jsonPath("$.reviewCode").value("AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.reviewReason").value("Provider reported 4000.00 NGN, expected 5000.00 NGN"));

        verify(walletClient, never()).credit(any(), anyString(), any(), anyString());
        as(ada, get("/api/v1/funding/" + reference)).andExpect(jsonPath("$.status").value("REQUIRES_REVIEW"));
    }

    @Test
    void aDifferentCurrencyIsNeverCredited() throws Exception {
        String reference = started("5000.00");
        confirm(reference, "{\"outcome\":\"SUCCESS\",\"amount\":5000.00,\"currency\":\"USD\"}")
                .andExpect(jsonPath("$.status").value("REQUIRES_REVIEW")).andExpect(jsonPath("$.reviewCode").value("AMOUNT_MISMATCH"));
        verify(walletClient, never()).credit(any(), anyString(), any(), anyString());
    }

    @Test
    void aMismatchedFundingCannotBeRetriedIntoSuccess() throws Exception {
        String reference = started("5000.00");
        paid(reference, "4000.00");

        retry(reference, "ops.jane").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("FUNDING_NOT_RETRYABLE"));
        // even a later, correct-looking confirmation does not override a held funding
        paid(reference, "5000.00").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUIRES_REVIEW"));
        verify(walletClient, never()).credit(any(), anyString(), any(), anyString());
    }

    @Test
    void successNeedsTheAmountAndCurrency() throws Exception {
        String reference = started("5000.00");
        confirm(reference, "{\"outcome\":\"SUCCESS\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        confirm(reference, "{\"outcome\":\"SUCCESS\",\"amount\":5000.00}").andExpect(status().isBadRequest());
        confirm(reference, "{\"outcome\":\"SUCCESS\",\"currency\":\"NGN\"}").andExpect(status().isBadRequest());
        confirm(reference, "{\"outcome\":\"MAYBE\"}").andExpect(status().isBadRequest());
        confirm(reference, "{}").andExpect(status().isBadRequest());
        confirm(reference, "{\"outcome\":\"SUCCESS\",\"amount\":5000.001,\"currency\":\"NGN\"}").andExpect(status().isBadRequest());
        assertThat(statusOf(reference)).isEqualTo("PENDING");
    }

    @Test
    void confirmingAnUnknownReferenceIs404() throws Exception {
        paid("FND-DOESNOTEXIST", "1.00").andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("FUNDING_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ when the wallet cannot take the money

    @Test
    void ifTheWalletServiceIsDown_thePaymentIsRecordedAndTheFundingWaits() throws Exception {
        String reference = started("5000.00");
        walletDown();

        paid(reference, "5000.00").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"));

        assertThat(statusOf(reference)).isEqualTo("PROCESSING");
        as(ada, get("/api/v1/funding/" + reference)).andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void theRecoverySweepFinishesAWaitingFundingOnceTheWalletIsBack() throws Exception {
        String reference = started("5000.00");
        walletDown();
        paid(reference, "5000.00");
        assertThat(recoveryJob.run()).isEqualTo(1);
        assertThat(statusOf(reference)).isEqualTo("PROCESSING");

        credited();
        assertThat(recoveryJob.run()).isEqualTo(1);

        assertThat(statusOf(reference)).isEqualTo("SUCCESS");
        assertThat(recoveryJob.run()).isZero();
        assertThat(history(reference)).isEqualTo(3);
    }

    @Test
    void theSweepLeavesEverythingElseAlone() throws Exception {
        String pending = started("10.00");
        String failed = started("20.00");
        paymentFailed(failed);
        String done = started("30.00");
        paid(done, "30.00");
        String held = started("40.00");
        paid(held, "1.00");
        reset(walletClient);

        assertThat(recoveryJob.run()).isZero();

        verify(walletClient, never()).credit(any(), anyString(), any(), anyString());
        assertThat(List.of(statusOf(pending), statusOf(failed), statusOf(done), statusOf(held)))
                .containsExactly("PENDING", "FAILED", "SUCCESS", "REQUIRES_REVIEW");
    }

    @Test
    void oneFundingThatCannotBeFinishedDoesNotStopTheOthers() throws Exception {
        String first = started("10.00");
        String second = started("20.00");
        walletDown();
        paid(first, "10.00");
        paid(second, "20.00");

        when(walletClient.credit(any(), eq(first), any(), anyString())).thenThrow(new IllegalStateException("boom"));
        when(walletClient.credit(any(), eq(second), any(), anyString())).thenReturn(new CreditOutcome(Kind.CREDITED, null));

        assertThat(recoveryJob.run()).isEqualTo(2);
        assertThat(statusOf(second)).isEqualTo("SUCCESS");
        assertThat(statusOf(first)).isEqualTo("PROCESSING");
    }

    @Test
    void aWalletThatRefusesTheMoney_sendsTheFundingToReview_neverDropsIt() throws Exception {
        String reference = started("5000.00");
        walletRefuses("WALLET_NOT_ACCEPTING_FUNDS");

        paid(reference, "5000.00").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUIRES_REVIEW"))
                .andExpect(jsonPath("$.reviewCode").value("CREDIT_REFUSED"))
                .andExpect(jsonPath("$.reviewReason").value("The wallet refused the money: WALLET_NOT_ACCEPTING_FUNDS"));
        assertThat(recoveryJob.run()).isZero();
    }

    @Test
    void operationsRetryARefusedFundingOnceTheCauseIsFixed() throws Exception {
        String reference = started("5000.00");
        walletRefuses("WALLET_NOT_ACCEPTING_FUNDS");
        paid(reference, "5000.00");

        retry(reference, "ops.jane").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUIRES_REVIEW"));

        credited();
        retry(reference, "ops.jane").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.reviewCode").doesNotExist())
                .andExpect(jsonPath("$.history[?(@.actor == 'ops.jane')].to").value(org.hamcrest.Matchers.hasItem("PROCESSING")));
    }

    @Test
    void retryOnlyAppliesToStuckOrRefusedFundings() throws Exception {
        String pending = started("10.00");
        retry(pending, "ops.jane").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("FUNDING_NOT_RETRYABLE"));

        String done = started("20.00");
        paid(done, "20.00");
        retry(done, "ops.jane").andExpect(status().isUnprocessableEntity());

        String failed = started("30.00");
        paymentFailed(failed);
        retry(failed, "ops.jane").andExpect(status().isUnprocessableEntity());

        retry("FND-DOESNOTEXIST", "ops.jane").andExpect(status().isNotFound());
        retry(pending, null).andExpect(status().isBadRequest());
        retry(pending, "  ").andExpect(status().isBadRequest());
    }

    @Test
    void operationsCanRetryAStuckProcessingFunding() throws Exception {
        String reference = started("5000.00");
        walletDown();
        paid(reference, "5000.00");

        credited();
        retry(reference, "ops.jane").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    // ------------------------------------------------------------------ internal access

    @Test
    void internalEndpointsNeedTheInternalKey() throws Exception {
        String reference = started("100.00");
        mockMvc.perform(get("/internal/funding/" + reference)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/funding/" + reference).header("X-Internal-Api-Key", "wrong")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/funding/" + reference + "/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"FAILED\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/funding/" + reference + "/retry").header("X-Actor", "a")).andExpect(status().isUnauthorized());
        assertThat(statusOf(reference)).isEqualTo("PENDING");
    }

    @Test
    void operationsSeeTheWholeStory() throws Exception {
        String reference = started("5000.00");
        paid(reference, "5000.00");

        internal(get("/internal/funding/" + reference), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(ada.toString()))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[0].actor").value("customer"))
                .andExpect(jsonPath("$.history[1].actor").value("provider"))
                .andExpect(jsonPath("$.history[2].actor").value("system"));
    }

    // ------------------------------------------------------------------ the database's own guards

    @Test
    void theDatabaseRefusesANonPositiveAmountOrADuplicateReference() throws Exception {
        String reference = started("100.00");
        assertThatThrownBy(() -> jdbc.update("update fundings set amount = 0 where reference = ?", reference)).hasMessageContaining("ck_fundings_amount_positive");
        assertThatThrownBy(() -> jdbc.update("""
                insert into fundings (id, reference, customer_id, wallet_id, source, amount, currency, status, created_at, updated_at)
                values (gen_random_uuid(), ?, gen_random_uuid(), gen_random_uuid(), 'CARD', 1, 'NGN', 'PENDING', now(), now())""", reference))
                .hasMessageContaining("ux_fundings_reference");
    }

    @Test
    void theDatabaseRefusesAnUnknownStatusOrAnInconsistentReview() throws Exception {
        String reference = started("100.00");
        assertThatThrownBy(() -> jdbc.update("update fundings set status = 'DONE' where reference = ?", reference)).hasMessageContaining("ck_fundings_status");
        // a review status must say why; any other status must not carry a review code
        assertThatThrownBy(() -> jdbc.update("update fundings set status = 'REQUIRES_REVIEW' where reference = ?", reference))
                .hasMessageContaining("ck_fundings_review_consistent");
        assertThatThrownBy(() -> jdbc.update("update fundings set review_code = 'CREDIT_REFUSED' where reference = ?", reference))
                .hasMessageContaining("ck_fundings_review_consistent");
    }

    // ------------------------------------------------------------------ concurrency helper

    private <T> List<T> inParallel(int n, ThrowingFunction<Integer, T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            var start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                Callable<T> call = () -> {
                    start.await();
                    return task.apply(index);
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    interface ThrowingFunction<A, R> {
        R apply(A a) throws Exception;
    }
}
