package com.flowpay.wallet.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowpay.wallet.AbstractIntegrationTest;
import com.flowpay.wallet.client.KycClient;
import com.flowpay.wallet.exception.WalletException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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

class WalletIntegrationTest extends AbstractIntegrationTest {

    static final String CUSTOMER = "X-Authenticated-Customer-Id";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean KycClient kycClient;

    UUID ada;
    UUID bola;

    @BeforeEach
    void freshCustomers() {
        jdbc.update("delete from wallet_movements");
        jdbc.update("delete from wallet_status_changes");
        jdbc.update("delete from wallets");
        reset(kycClient);
        when(kycClient.levelOf(any())).thenReturn(1);
        ada = UUID.randomUUID();
        bola = UUID.randomUUID();
    }

    // ------------------------------------------------------------------ helpers

    ResultActions as(UUID customer, MockHttpServletRequestBuilder request) throws Exception {
        if (customer != null) request.header(CUSTOMER, customer.toString());
        return mockMvc.perform(request);
    }

    ResultActions openWallet(UUID customer, String currency) throws Exception {
        return as(customer, post("/api/v1/wallets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"" + currency + "\"}"));
    }

    String walletOf(UUID customer) throws Exception {
        return idOf(openWallet(customer, "NGN").andExpect(status().isCreated()));
    }

    static String idOf(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.id");
    }

    ResultActions internal(MockHttpServletRequestBuilder request, String actor) throws Exception {
        request.header("X-Internal-Api-Key", INTERNAL_API_KEY);
        if (actor != null) request.header("X-Actor", actor);
        return mockMvc.perform(request);
    }

    static String movement(String reference, String amount, String currency) {
        return "{\"reference\":\"%s\",\"amount\":%s,\"currency\":\"%s\"}".formatted(reference, amount, currency);
    }

    ResultActions credit(String wallet, String reference, String amount) throws Exception {
        return internal(post("/internal/wallets/" + wallet + "/credit").contentType(MediaType.APPLICATION_JSON)
                .content(movement(reference, amount, "NGN")), null);
    }

    ResultActions debit(String wallet, String reference, String amount) throws Exception {
        return internal(post("/internal/wallets/" + wallet + "/debit").contentType(MediaType.APPLICATION_JSON)
                .content(movement(reference, amount, "NGN")), null);
    }

    ResultActions setStatus(String wallet, String status) throws Exception {
        return internal(post("/internal/wallets/" + wallet + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"%s\",\"reason\":\"test\"}".formatted(status)), "ops.jane");
    }

    BigDecimal ledger(String wallet) {
        return jdbc.queryForObject("select ledger_balance from wallets where id = ?::uuid", BigDecimal.class, wallet);
    }

    BigDecimal available(String wallet) {
        return jdbc.queryForObject("select available_balance from wallets where id = ?::uuid", BigDecimal.class, wallet);
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    void assertBalances(String wallet, String expected) {
        assertThat(ledger(wallet)).isEqualByComparingTo(expected);
        assertThat(available(wallet)).isEqualByComparingTo(expected);
    }

    // ------------------------------------------------------------------ opening a wallet

    @Test
    void aVerifiedCustomerOpensAnActiveEmptyWallet() throws Exception {
        openWallet(ada, "NGN").andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/wallets/")))
                .andExpect(jsonPath("$.customerId").value(ada.toString()))
                .andExpect(jsonPath("$.currency").value("NGN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ledgerBalance").value(0))
                .andExpect(jsonPath("$.availableBalance").value(0))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void aCustomerWhoIsNotVerifiedEnoughCannotOpenAWallet() throws Exception {
        when(kycClient.levelOf(ada)).thenReturn(0);

        openWallet(ada, "NGN").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("KYC_LEVEL_TOO_LOW"));
        assertThat(count("select count(*) from wallets")).isZero();
    }

    @Test
    void ifVerificationCannotBeChecked_nothingIsCreated() throws Exception {
        when(kycClient.levelOf(ada)).thenThrow(new WalletException(HttpStatus.SERVICE_UNAVAILABLE, "KYC_UNAVAILABLE", "down"));

        openWallet(ada, "NGN").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("KYC_UNAVAILABLE"));
        assertThat(count("select count(*) from wallets")).isZero();
    }

    @Test
    void anUnsupportedCurrencyIsRefused() throws Exception {
        openWallet(ada, "USD").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_CURRENCY"));
        assertThat(count("select count(*) from wallets")).isZero();
    }

    @Test
    void aMalformedOrMissingCurrencyIsAValidationError() throws Exception {
        for (String bad : List.of("ngn", "NG", "NAIRA", "", "N1N")) {
            openWallet(ada, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }
        as(ada, post("/api/v1/wallets").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        as(ada, post("/api/v1/wallets").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    void everyCustomerCallNeedsAnIdentity() throws Exception {
        openWallet(null, "NGN").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
        as(null, get("/api/v1/wallets")).andExpect(status().isUnauthorized());
        as(null, get("/api/v1/wallets/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void aSecondLiveWalletInTheSameCurrencyIsRefused() throws Exception {
        walletOf(ada);

        openWallet(ada, "NGN").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("WALLET_ALREADY_EXISTS"));
        assertThat(count("select count(*) from wallets where customer_id = ?", ada)).isEqualTo(1);
    }

    @Test
    void twoCustomersEachGetTheirOwnWallet() throws Exception {
        walletOf(ada);
        walletOf(bola);
        assertThat(count("select count(*) from wallets")).isEqualTo(2);
    }

    @Test
    void simultaneousRequestsToOpenTheSameWalletCreateExactlyOne() throws Exception {
        List<Integer> statuses = inParallel(8, i -> openWallet(ada, "NGN").andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(count("select count(*) from wallets where customer_id = ?", ada)).isEqualTo(1);
    }

    @Test
    void aClosedWalletDoesNotStopTheCustomerOpeningANewOne() throws Exception {
        String first = walletOf(ada);
        setStatus(first, "CLOSED").andExpect(status().isOk());

        openWallet(ada, "NGN").andExpect(status().isCreated());
        assertThat(count("select count(*) from wallets where customer_id = ?", ada)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ reading wallets

    @Test
    void aCustomerSeesOnlyTheirOwnWallets() throws Exception {
        String adas = walletOf(ada);
        String bolas = walletOf(bola);

        as(ada, get("/api/v1/wallets")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(adas));
        as(ada, get("/api/v1/wallets/" + adas)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(adas));
        as(ada, get("/api/v1/wallets/" + bolas)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WALLET_NOT_FOUND"));
        as(ada, get("/api/v1/wallets/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void someoneWithNoWalletsGetsAnEmptyList() throws Exception {
        as(ada, get("/api/v1/wallets")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theWalletShowsBothBalances() throws Exception {
        String id = walletOf(ada);
        credit(id, "fund-1", "100000.00").andExpect(status().isCreated());

        as(ada, get("/api/v1/wallets/" + id)).andExpect(jsonPath("$.ledgerBalance").value(100000.00))
                .andExpect(jsonPath("$.availableBalance").value(100000.00));
    }

    // ------------------------------------------------------------------ internal access

    @Test
    void internalEndpointsNeedTheInternalKey() throws Exception {
        String id = walletOf(ada);
        mockMvc.perform(get("/internal/wallets/" + id)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/wallets/" + id).header("X-Internal-Api-Key", "wrong")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/wallets/" + id + "/debit").contentType(MediaType.APPLICATION_JSON)
                .content(movement("x", "1.00", "NGN"))).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/wallets/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FROZEN\",\"reason\":\"r\"}").header("X-Actor", "a")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ moving money

    @Test
    void creditThenDebit_updatesBothBalances() throws Exception {
        String id = walletOf(ada);

        credit(id, "fund-1", "5000.00").andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.ledgerBalanceAfter").value(5000.00)).andExpect(jsonPath("$.availableBalanceAfter").value(5000.00));
        debit(id, "send-1", "2000.00").andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("DEBIT"))
                .andExpect(jsonPath("$.ledgerBalanceAfter").value(3000.00));

        assertBalances(id, "3000.00");
        assertThat(count("select count(*) from wallet_movements where wallet_id = ?::uuid", id)).isEqualTo(2);
    }

    @Test
    void aCustomerCannotSpendMoreThanTheyHave() throws Exception {
        String id = walletOf(ada);
        credit(id, "fund-1", "5000.00");

        debit(id, "send-1", "5000.01").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));

        assertBalances(id, "5000.00");
        assertThat(count("select count(*) from wallet_movements where reference = 'send-1'")).isZero();
    }

    @Test
    void spendingExactlyEverythingIsAllowed_andLeavesZero() throws Exception {
        String id = walletOf(ada);
        credit(id, "fund-1", "5000.00");

        debit(id, "send-1", "5000.00").andExpect(status().isCreated());
        assertBalances(id, "0.00");
        debit(id, "send-2", "0.01").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void badAmountsAreRejected() throws Exception {
        String id = walletOf(ada);
        for (String amount : List.of("0", "0.00", "-5.00", "10.001", "\"abc\"", "null")) {
            debit(id, "bad-" + amount.hashCode(), amount).andExpect(status().is4xxClientError());
            credit(id, "badc-" + amount.hashCode(), amount).andExpect(status().is4xxClientError());
        }
        debit(id, "r", "0").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        assertBalances(id, "0.00");
        assertThat(count("select count(*) from wallet_movements")).isZero();
    }

    @Test
    void theCurrencyMustMatchTheWallet() throws Exception {
        String id = walletOf(ada);
        internal(post("/internal/wallets/" + id + "/credit").contentType(MediaType.APPLICATION_JSON)
                .content(movement("fund-usd", "100.00", "USD")), null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("CURRENCY_MISMATCH"));
        assertBalances(id, "0.00");
    }

    @Test
    void moneyCannotMoveInAnUnknownWallet() throws Exception {
        String unknown = UUID.randomUUID().toString();
        credit(unknown, "fund-1", "1.00").andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("WALLET_NOT_FOUND"));
        debit(unknown, "send-1", "1.00").andExpect(status().isNotFound());
    }

    @Test
    void aMovementNeedsAValidReference() throws Exception {
        String id = walletOf(ada);
        for (String ref : List.of("", " ", "has space", "semi;colon", "x".repeat(101))) {
            internal(post("/internal/wallets/" + id + "/credit").contentType(MediaType.APPLICATION_JSON)
                    .content(movement(ref, "1.00", "NGN")), null).andExpect(status().isBadRequest());
        }
        assertThat(count("select count(*) from wallet_movements")).isZero();
    }

    // ------------------------------------------------------------------ duplicates and concurrency

    @Test
    void repeatingAReferenceReturnsTheFirstResult_andMovesNothingTwice() throws Exception {
        String id = walletOf(ada);
        credit(id, "fund-1", "5000.00").andExpect(status().isCreated());

        credit(id, "fund-1", "5000.00").andExpect(status().isOk()).andExpect(jsonPath("$.ledgerBalanceAfter").value(5000.00));
        assertBalances(id, "5000.00");

        debit(id, "send-1", "1000.00").andExpect(status().isCreated());
        debit(id, "send-1", "1000.00").andExpect(status().isOk());
        assertBalances(id, "4000.00");
        assertThat(count("select count(*) from wallet_movements")).isEqualTo(2);
    }

    @Test
    void aReferenceCannotBeReusedForADifferentMovement() throws Exception {
        String id = walletOf(ada);
        credit(id, "ref-1", "5000.00").andExpect(status().isCreated());

        credit(id, "ref-1", "6000.00").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("REFERENCE_REUSED"));
        debit(id, "ref-1", "5000.00").andExpect(status().isConflict());
        credit(walletOf(bola), "ref-1", "5000.00").andExpect(status().isConflict());
        assertBalances(id, "5000.00");
    }

    @Test
    void aRefusedMovementDoesNotUseUpItsReference() throws Exception {
        String id = walletOf(ada);
        debit(id, "send-1", "100.00").andExpect(status().isUnprocessableEntity());

        credit(id, "fund-1", "500.00");
        debit(id, "send-1", "100.00").andExpect(status().isCreated());
        assertBalances(id, "400.00");
    }

    @Test
    void simultaneousIdenticalRequestsMoveMoneyOnlyOnce() throws Exception {
        String id = walletOf(ada);
        credit(id, "fund-1", "1000.00");

        List<Integer> statuses = inParallel(10, i -> debit(id, "same-ref", "300.00").andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200, 201);
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertBalances(id, "700.00");
        assertThat(count("select count(*) from wallet_movements where reference = 'same-ref'")).isEqualTo(1);
    }

    @Test
    void simultaneousSpendingNeverGoesBelowZero() throws Exception {
        String id = walletOf(ada);
        credit(id, "fund-1", "1000.00");

        List<Integer> statuses = inParallel(30, i -> debit(id, "race-" + i, "100.00").andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(10);
        assertThat(statuses.stream().filter(s -> s == 422)).hasSize(20);
        assertBalances(id, "0.00");
        assertThat(count("select count(*) from wallet_movements where wallet_id = ?::uuid and type = 'DEBIT'", id)).isEqualTo(10);
    }

    @Test
    void creditsAndDebitsAtTheSameTimeAddUpExactly() throws Exception {
        String id = walletOf(ada);
        credit(id, "fund-0", "1000.00");

        inParallel(40, i -> i % 2 == 0
                ? credit(id, "c-" + i, "10.00").andReturn().getResponse().getStatus()
                : debit(id, "d-" + i, "10.00").andReturn().getResponse().getStatus());

        assertBalances(id, "1000.00");
        assertThat(count("select count(*) from wallet_movements where wallet_id = ?::uuid", id)).isEqualTo(41);
    }

    // ------------------------------------------------------------------ the wallet-status rules

    @Test
    void anActiveWalletSendsAndReceives() throws Exception {
        String id = walletOf(ada);
        credit(id, "fund-1", "100.00").andExpect(status().isCreated());
        debit(id, "send-1", "40.00").andExpect(status().isCreated());
    }

    @Test
    void aFrozenWalletCannotSendButCanStillReceive() throws Exception {
        String id = walletOf(ada);
        credit(id, "fund-1", "100.00");
        setStatus(id, "FROZEN").andExpect(status().isOk()).andExpect(jsonPath("$.wallet.status").value("FROZEN"));

        debit(id, "send-1", "10.00").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("WALLET_NOT_ACTIVE"));
        credit(id, "fund-2", "50.00").andExpect(status().isCreated());
        assertBalances(id, "150.00");
    }

    @Test
    void aBlockedWalletCannotSendOrReceive() throws Exception {
        String id = walletOf(ada);
        credit(id, "fund-1", "100.00");
        setStatus(id, "BLOCKED").andExpect(status().isOk());

        debit(id, "send-1", "10.00").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("WALLET_NOT_ACTIVE"));
        credit(id, "fund-2", "10.00").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("WALLET_NOT_ACCEPTING_FUNDS"));
        assertBalances(id, "100.00");
    }

    @Test
    void aClosedWalletCannotSendOrReceive() throws Exception {
        String id = walletOf(ada);
        setStatus(id, "CLOSED").andExpect(status().isOk());

        debit(id, "send-1", "10.00").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("WALLET_NOT_ACTIVE"));
        credit(id, "fund-1", "10.00").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("WALLET_NOT_ACCEPTING_FUNDS"));
    }

    @Test
    void reactivatingAWalletRestoresItsAbilityToSend() throws Exception {
        String id = walletOf(ada);
        credit(id, "fund-1", "100.00");
        setStatus(id, "BLOCKED");
        debit(id, "send-1", "10.00").andExpect(status().isUnprocessableEntity());

        setStatus(id, "ACTIVE").andExpect(status().isOk());
        debit(id, "send-2", "10.00").andExpect(status().isCreated());
        assertBalances(id, "90.00");
    }

    @Test
    void theCustomerSeesTheStatusOfTheirWallet() throws Exception {
        String id = walletOf(ada);
        setStatus(id, "FROZEN");
        as(ada, get("/api/v1/wallets/" + id)).andExpect(jsonPath("$.status").value("FROZEN"));
    }

    // ------------------------------------------------------------------ changing status

    @Test
    void everyStatusChangeIsRecordedWithWhoAndWhy() throws Exception {
        String id = walletOf(ada);
        internal(post("/internal/wallets/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FROZEN\",\"reason\":\"  Lost phone  \"}"), "ops.jane").andExpect(status().isOk());
        setStatus(id, "ACTIVE");

        internal(get("/internal/wallets/" + id), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.wallet.status").value("ACTIVE"))
                .andExpect(jsonPath("$.statusHistory.length()").value(2))
                .andExpect(jsonPath("$.statusHistory[0].from").value("ACTIVE"))
                .andExpect(jsonPath("$.statusHistory[0].to").value("FROZEN"))
                .andExpect(jsonPath("$.statusHistory[0].reason").value("Lost phone"))
                .andExpect(jsonPath("$.statusHistory[0].actor").value("ops.jane"))
                .andExpect(jsonPath("$.statusHistory[1].to").value("ACTIVE"));
    }

    @Test
    void aStatusChangeNeedsAnActorAndAReason() throws Exception {
        String id = walletOf(ada);
        internal(post("/internal/wallets/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FROZEN\",\"reason\":\"r\"}"), null).andExpect(status().isBadRequest());
        internal(post("/internal/wallets/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FROZEN\",\"reason\":\"r\"}"), "  ").andExpect(status().isBadRequest());
        internal(post("/internal/wallets/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FROZEN\",\"reason\":\" \"}"), "ops.jane").andExpect(status().isBadRequest());
        internal(post("/internal/wallets/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FROZEN\"}"), "ops.jane").andExpect(status().isBadRequest());
        internal(post("/internal/wallets/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"EXPLODED\",\"reason\":\"r\"}"), "ops.jane").andExpect(status().isBadRequest());

        assertThat(count("select count(*) from wallet_status_changes")).isZero();
    }

    @Test
    void settingTheSameStatusIsRefused() throws Exception {
        String id = walletOf(ada);
        setStatus(id, "ACTIVE").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("WALLET_STATUS_UNCHANGED"));
    }

    @Test
    void aClosedWalletCanNeverBeReopened() throws Exception {
        String id = walletOf(ada);
        setStatus(id, "CLOSED").andExpect(status().isOk());

        for (String next : List.of("ACTIVE", "FROZEN", "BLOCKED")) {
            setStatus(id, next).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
        }
    }

    @Test
    void aWalletWithMoneyCannotBeClosed() throws Exception {
        String id = walletOf(ada);
        credit(id, "fund-1", "0.01");

        setStatus(id, "CLOSED").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("WALLET_NOT_EMPTY"));
        as(ada, get("/api/v1/wallets/" + id)).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(count("select count(*) from wallet_status_changes")).isZero();

        debit(id, "send-1", "0.01");
        setStatus(id, "CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.wallet.status").value("CLOSED"));
    }

    @Test
    void statusChangeOnAnUnknownWalletIs404() throws Exception {
        setStatus(UUID.randomUUID().toString(), "FROZEN").andExpect(status().isNotFound());
    }

    @Test
    void aDepositArrivingWhileAWalletIsClosedIsNeverLost() throws Exception {
        for (int round = 0; round < 15; round++) {
            String id = walletOf(UUID.randomUUID());
            List<Integer> results = inParallel(2, i -> i == 0
                    ? setStatus(id, "CLOSED").andReturn().getResponse().getStatus()
                    : credit(id, "late-" + id, "100.00").andReturn().getResponse().getStatus());

            String status = jdbc.queryForObject("select status from wallets where id = ?::uuid", String.class, id);
            if (status.equals("CLOSED")) {
                // closing won: the wallet must have been empty, and the deposit must have been refused
                assertBalances(id, "0.00");
                assertThat(results.get(1)).isEqualTo(422);
            } else {
                // the deposit won: the wallet holds the money and the close was refused
                assertBalances(id, "100.00");
                assertThat(results.get(0)).isEqualTo(422);
            }
        }
    }

    // ------------------------------------------------------------------ the database's own guards

    @Test
    void theDatabaseItselfRefusesANegativeBalance() throws Exception {
        String id = walletOf(ada);
        assertThatThrownBy(() -> jdbc.update("update wallets set ledger_balance = -1, available_balance = -1 where id = ?::uuid", id))
                .hasMessageContaining("ck_wallets");
    }

    @Test
    void theDatabaseItselfRefusesAvailableAboveLedger() throws Exception {
        String id = walletOf(ada);
        assertThatThrownBy(() -> jdbc.update("update wallets set available_balance = 10 where id = ?::uuid", id))
                .hasMessageContaining("ck_wallets_available_within_ledger");
    }

    @Test
    void theDatabaseItselfRefusesAnUnknownStatusOrADuplicateLiveWallet() throws Exception {
        String id = walletOf(ada);
        assertThatThrownBy(() -> jdbc.update("update wallets set status = 'SUSPENDED' where id = ?::uuid", id))
                .hasMessageContaining("ck_wallets_status");
        assertThatThrownBy(() -> jdbc.update("""
                insert into wallets (id, customer_id, currency, status, created_at, updated_at)
                values (gen_random_uuid(), ?, 'NGN', 'ACTIVE', now(), now())""", ada))
                .hasMessageContaining("ux_wallets_customer_currency_live");
    }

    // ------------------------------------------------------------------ concurrency helper

    private <T> List<T> inParallel(int n, ThrowingFunction<Integer, T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
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
