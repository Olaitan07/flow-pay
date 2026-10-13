package com.flowpay.wallet.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowpay.wallet.AbstractIntegrationTest;
import com.flowpay.wallet.client.KycClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The atomic wallet-to-wallet transfer, against a real Postgres: the all-or-nothing guarantee, the fee, retries,
 * and what happens when many transfers run at once.
 */
class WalletTransferIntegrationTest extends AbstractIntegrationTest {

    static final UUID FEE_OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000fe");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean KycClient kycClient;

    String ada;
    String bola;

    @BeforeEach
    void freshWorld() throws Exception {
        jdbc.update("delete from wallet_movements");
        jdbc.update("delete from wallet_status_changes");
        jdbc.update("delete from wallets");
        reset(kycClient);
        when(kycClient.levelOf(any())).thenReturn(1);
        ada = newWallet(UUID.randomUUID(), "50000.00");
        bola = newWallet(UUID.randomUUID(), "0.00");
    }

    // ------------------------------------------------------------------ helpers

    String newWallet(UUID owner, String funds) throws Exception {
        String body = mockMvc.perform(post("/api/v1/wallets").header("X-Authenticated-Customer-Id", owner.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"NGN\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");
        if (new BigDecimal(funds).signum() > 0) {
            internal(post("/internal/wallets/" + id + "/credit").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reference\":\"seed-%s\",\"amount\":%s,\"currency\":\"NGN\"}".formatted(id, funds)))
                    .andExpect(status().isCreated());
        }
        return id;
    }

    ResultActions internal(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("X-Internal-Api-Key", INTERNAL_API_KEY));
    }

    static String transferJson(String reference, String from, String to, String amount, String fee) {
        return "{\"reference\":\"%s\",\"senderWalletId\":\"%s\",\"recipientWalletId\":\"%s\",\"amount\":%s,\"fee\":%s,\"currency\":\"NGN\"}"
                .formatted(reference, from, to, amount, fee);
    }

    ResultActions transfer(String reference, String from, String to, String amount, String fee) throws Exception {
        return internal(post("/internal/wallets/transfer").contentType(MediaType.APPLICATION_JSON)
                .content(transferJson(reference, from, to, amount, fee)));
    }

    void setStatus(String wallet, String status) throws Exception {
        internal(post("/internal/wallets/" + wallet + "/status").contentType(MediaType.APPLICATION_JSON)
                .header("X-Actor", "ops.jane").content("{\"status\":\"%s\",\"reason\":\"test\"}".formatted(status)))
                .andExpect(status().isOk());
    }

    BigDecimal balance(String wallet) {
        return jdbc.queryForObject("select available_balance from wallets where id = ?::uuid", BigDecimal.class, wallet);
    }

    BigDecimal feeWalletBalance() {
        var rows = jdbc.queryForList("select available_balance from wallets where customer_id = ?", BigDecimal.class, FEE_OWNER);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }

    BigDecimal everyonesMoney() {
        return jdbc.queryForObject("select coalesce(sum(ledger_balance), 0) from wallets", BigDecimal.class);
    }

    int movementCount() {
        return jdbc.queryForObject("select count(*) from wallet_movements where reference not like 'seed-%'", Integer.class);
    }

    // ------------------------------------------------------------------ the acceptance example

    @Test
    void fiftyThousandSendingTenThousandWithAHundredFeeLeavesThirtyNineNineHundred() throws Exception {
        transfer("TRF-1", ada, bola, "10000.00", "100.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.debit.amount").value(10100.00))
                .andExpect(jsonPath("$.credit.amount").value(10000.00))
                .andExpect(jsonPath("$.fee.amount").value(100.00));

        assertThat(balance(ada)).isEqualByComparingTo("39900.00");
        assertThat(balance(bola)).isEqualByComparingTo("10000.00");
        assertThat(feeWalletBalance()).isEqualByComparingTo("100.00");
        assertThat(everyonesMoney()).isEqualByComparingTo("50000.00");
    }

    @Test
    void aZeroFeeTransferNeverTouchesTheFeeWallet() throws Exception {
        transfer("TRF-2", ada, bola, "1000.00", "0.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.fee").doesNotExist());
        assertThat(balance(ada)).isEqualByComparingTo("49000.00");
        assertThat(balance(bola)).isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForObject("select count(*) from wallets where customer_id = ?", Integer.class, FEE_OWNER)).isZero();
    }

    // ------------------------------------------------------------------ all or nothing

    @Test
    void notEnoughForTheAmountPlusFeeMovesNothingAtAll() throws Exception {
        // 50,000 covers the amount alone, but not amount + fee.
        transfer("TRF-3", ada, bola, "50000.00", "100.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));
        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
        assertThat(balance(bola)).isEqualByComparingTo("0.00");
        assertThat(feeWalletBalance()).isEqualByComparingTo("0.00");
        assertThat(movementCount()).isZero();
    }

    @Test
    void aRecipientThatCannotReceiveRollsBackTheSendersDebit() throws Exception {
        setStatus(bola, "BLOCKED");
        transfer("TRF-4", ada, bola, "1000.00", "100.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RECIPIENT_NOT_ACCEPTING_FUNDS"));
        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
        assertThat(feeWalletBalance()).isEqualByComparingTo("0.00");
        assertThat(movementCount()).isZero();
    }

    @Test
    void aFrozenRecipientStillReceives() throws Exception {
        setStatus(bola, "FROZEN");
        transfer("TRF-5", ada, bola, "1000.00", "100.00").andExpect(status().isCreated());
        assertThat(balance(bola)).isEqualByComparingTo("1000.00");
    }

    @Test
    void aSenderThatIsFrozenBlockedOrClosedCannotSend() throws Exception {
        for (String blocked : List.of("FROZEN", "BLOCKED")) {
            setStatus(ada, blocked);
            transfer("TRF-6-" + blocked, ada, bola, "1000.00", "100.00").andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("WALLET_NOT_ACTIVE"));
            setStatus(ada, "ACTIVE");
        }
        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
        assertThat(movementCount()).isZero();
    }

    @Test
    void anUnknownWalletIsReportedWithWhoseItWas() throws Exception {
        transfer("TRF-7", UUID.randomUUID().toString(), bola, "1000.00", "0.00").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SENDER_WALLET_NOT_FOUND"));
        transfer("TRF-8", ada, UUID.randomUUID().toString(), "1000.00", "0.00").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RECIPIENT_WALLET_NOT_FOUND"));
        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
    }

    @Test
    void aBlockedFeeWalletMeansTryAgainNotFailedAndNothingMoves() throws Exception {
        transfer("TRF-9a", ada, bola, "1000.00", "100.00").andExpect(status().isCreated());
        String feeWallet = jdbc.queryForObject("select id::text from wallets where customer_id = ?", String.class, FEE_OWNER);
        setStatus(feeWallet, "BLOCKED");
        transfer("TRF-9b", ada, bola, "1000.00", "100.00").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("FEE_WALLET_UNAVAILABLE"));
        assertThat(balance(ada)).isEqualByComparingTo("48900.00");
        setStatus(feeWallet, "ACTIVE");
        transfer("TRF-9b", ada, bola, "1000.00", "100.00").andExpect(status().isCreated());
        assertThat(balance(ada)).isEqualByComparingTo("47800.00");
    }

    // ------------------------------------------------------------------ bad requests

    @Test
    void zeroNegativeAndOverPreciseAmountsAreRejected() throws Exception {
        for (String amount : List.of("0", "0.00", "-5.00", "1.234")) {
            transfer("TRF-10", ada, bola, amount, "0.00").andExpect(status().isBadRequest());
        }
        transfer("TRF-10", ada, bola, "10.00", "-1.00").andExpect(status().isBadRequest());
        assertThat(movementCount()).isZero();
    }

    @Test
    void aWalletCannotSendToItself() throws Exception {
        transfer("TRF-11", ada, ada, "1000.00", "0.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SAME_WALLET"));
    }

    @Test
    void theCurrencyMustMatchBothWallets() throws Exception {
        internal(post("/internal/wallets/transfer").contentType(MediaType.APPLICATION_JSON)
                .content(transferJson("TRF-12", ada, bola, "10.00", "0.00").replace("NGN", "USD")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("CURRENCY_MISMATCH"));
        assertThat(balance(ada)).isEqualByComparingTo("50000.00");
    }

    @Test
    void theInternalKeyIsRequired() throws Exception {
        mockMvc.perform(post("/internal/wallets/transfer").contentType(MediaType.APPLICATION_JSON)
                .content(transferJson("TRF-13", ada, bola, "10.00", "0.00"))).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ retries

    @Test
    void repeatingATransferMovesNothingTwiceAndReturnsTheFirstResult() throws Exception {
        transfer("TRF-14", ada, bola, "10000.00", "100.00").andExpect(status().isCreated());
        transfer("TRF-14", ada, bola, "10000.00", "100.00").andExpect(status().isOk())
                .andExpect(jsonPath("$.debit.amount").value(10100.00));
        assertThat(balance(ada)).isEqualByComparingTo("39900.00");
        assertThat(balance(bola)).isEqualByComparingTo("10000.00");
        assertThat(feeWalletBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void aReferenceCannotBeReusedForADifferentTransfer() throws Exception {
        transfer("TRF-15", ada, bola, "10000.00", "100.00").andExpect(status().isCreated());
        transfer("TRF-15", ada, bola, "20000.00", "100.00").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REFERENCE_REUSED"));
        transfer("TRF-15", ada, bola, "10000.00", "50.00").andExpect(status().isConflict());
        assertThat(balance(ada)).isEqualByComparingTo("39900.00");
    }

    @Test
    void theSameTransferSentManyTimesAtOnceMovesMoneyOnce() throws Exception {
        List<Integer> statuses = runTogether(8, i -> () ->
                transfer("TRF-16", ada, bola, "10000.00", "100.00").andReturn().getResponse().getStatus());
        assertThat(statuses).containsOnly(200, 201);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(balance(ada)).isEqualByComparingTo("39900.00");
        assertThat(balance(bola)).isEqualByComparingTo("10000.00");
        assertThat(feeWalletBalance()).isEqualByComparingTo("100.00");
    }

    // ------------------------------------------------------------------ simultaneous different transfers

    @Test
    void twoTransfersThatTogetherWouldOverspendCannotBothSucceed() throws Exception {
        String rich = newWallet(UUID.randomUUID(), "100000.00");
        String one = newWallet(UUID.randomUUID(), "0.00");
        String two = newWallet(UUID.randomUUID(), "0.00");
        List<Integer> statuses = runTogether(2, i -> () -> transfer("TRF-17-" + i, rich, i == 0 ? one : two,
                i == 0 ? "80000.00" : "70000.00", "100.00").andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(balance(rich)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(everyonesMoney()).isEqualByComparingTo("150000.00");
    }

    @Test
    void manyTransfersFromOneWalletNeverOverspendItAndNoMoneyIsLost() throws Exception {
        String source = newWallet(UUID.randomUUID(), "1000.00");
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < 12; i++) targets.add(newWallet(UUID.randomUUID(), "0.00"));
        // Each costs 110.00 (100 + 10 fee): at most 9 fit in 1,000.00.
        List<Integer> statuses = runTogether(12, i -> () ->
                transfer("TRF-18-" + i, source, targets.get(i), "100.00", "10.00").andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(9);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(3);
        assertThat(balance(source)).isEqualByComparingTo("10.00");
        assertThat(feeWalletBalance()).isEqualByComparingTo("90.00");
        assertThat(everyonesMoney()).isEqualByComparingTo("51000.00"); // ada's 50,000 + the source wallet's 1,000
    }

    @Test
    void oppositeTransfersBetweenTwoWalletsNeverDeadlock() throws Exception {
        String a = newWallet(UUID.randomUUID(), "100000.00");
        String b = newWallet(UUID.randomUUID(), "100000.00");
        List<Integer> statuses = runTogether(40, i -> () ->
                i % 2 == 0 ? transfer("TRF-19-" + i, a, b, "10.00", "1.00").andReturn().getResponse().getStatus()
                        : transfer("TRF-19-" + i, b, a, "10.00", "1.00").andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(201);
        // 20 each way: each wallet nets only the fees it paid.
        assertThat(balance(a)).isEqualByComparingTo("99980.00");
        assertThat(balance(b)).isEqualByComparingTo("99980.00");
        assertThat(feeWalletBalance()).isEqualByComparingTo("40.00");
    }

    // ------------------------------------------------------------------ finding a wallet from a customer

    @Test
    void aWalletIsFoundByItsCustomerAndCurrency() throws Exception {
        UUID owner = UUID.randomUUID();
        String id = newWallet(owner, "0.00");
        internal(get("/internal/wallets/by-customer/" + owner).param("currency", "NGN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.status").value("ACTIVE"));
        internal(get("/internal/wallets/by-customer/" + owner).param("currency", "USD")).andExpect(status().isNotFound());
        internal(get("/internal/wallets/by-customer/" + UUID.randomUUID()).param("currency", "NGN")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ concurrency harness

    /** Starts n tasks at the same instant and returns each one's result. */
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
