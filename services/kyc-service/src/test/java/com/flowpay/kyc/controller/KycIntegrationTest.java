package com.flowpay.kyc.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowpay.kyc.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class KycIntegrationTest extends AbstractIntegrationTest {

    static final String CUSTOMER = "X-Authenticated-Customer-Id";
    static final String ADULT_DOB = LocalDate.now().minusYears(30).toString();
    static final String TIER1 = """
            {"dateOfBirth":"%s","addressLine1":"12 Marina Road","city":"Lagos","state":"Lagos",
             "postalCode":"101233","country":"NG"}""".formatted(ADULT_DOB);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    UUID ada;
    UUID bola;

    @BeforeEach
    void freshCustomers() {
        jdbc.update("delete from kyc_decisions");
        jdbc.update("delete from kyc_submissions");
        jdbc.update("delete from outbox_events");
        ada = UUID.randomUUID();
        bola = UUID.randomUUID();
    }

    // ------------------------------------------------------------------ helpers

    ResultActions as(UUID customer, MockHttpServletRequestBuilder request) throws Exception {
        if (customer != null) request.header(CUSTOMER, customer.toString());
        return mockMvc.perform(request);
    }

    ResultActions submit(UUID customer, int tier, String json) throws Exception {
        return as(customer, post("/api/v1/kyc/tiers/" + tier).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions viewStatus(UUID customer) throws Exception {
        return as(customer, get("/api/v1/kyc"));
    }

    static String tier2(String type, String number) {
        return "{\"idType\":\"%s\",\"idNumber\":\"%s\"}".formatted(type, number);
    }

    ResultActions internal(MockHttpServletRequestBuilder request, String reviewer) throws Exception {
        request.header("X-Internal-Api-Key", INTERNAL_API_KEY);
        if (reviewer != null) request.header("X-Reviewer", reviewer);
        return mockMvc.perform(request);
    }

    ResultActions decide(String submissionId, String reviewer, String json) throws Exception {
        return internal(post("/internal/kyc/submissions/" + submissionId + "/decision")
                .contentType(MediaType.APPLICATION_JSON).content(json), reviewer);
    }

    static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    String submitAndApprove(UUID customer, int tier, String json) throws Exception {
        String id = idOf(submit(customer, tier, json).andExpect(status().isCreated()));
        decide(id, "reviewer.jane", "{\"decision\":\"APPROVE\"}").andExpect(status().isOk());
        return id;
    }

    void makeLevelOne(UUID customer) throws Exception {
        submitAndApprove(customer, 1, TIER1);
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ------------------------------------------------------------------ customer: status

    @Test
    void aNewCustomerIsLevelZero_withNothingStarted_andMayDoNothing() throws Exception {
        viewStatus(ada).andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value(0))
                .andExpect(jsonPath("$.tiers[0].tier").value(1))
                .andExpect(jsonPath("$.tiers[0].status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.tiers[1].status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.capabilities.allowedOperations").isEmpty());
    }

    @Test
    void everyCustomerCallNeedsAnIdentity() throws Exception {
        viewStatus(null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
        submit(null, 1, TIER1).andExpect(status().isUnauthorized());
        submit(null, 2, tier2("NIN", "12345678901")).andExpect(status().isUnauthorized());
    }

    @Test
    void oneCustomerNeverSeesAnothersVerification() throws Exception {
        submit(ada, 1, TIER1).andExpect(status().isCreated());

        viewStatus(bola).andExpect(jsonPath("$.tiers[0].status").value("NOT_STARTED"));
        viewStatus(ada).andExpect(jsonPath("$.tiers[0].status").value("PENDING"));
    }

    // ------------------------------------------------------------------ tier 1

    @Test
    void validTierOneIsRecordedAsPending_andWaitsForAPerson() throws Exception {
        submit(ada, 1, TIER1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.tier").value(1))
                .andExpect(jsonPath("$.id").isNotEmpty());

        viewStatus(ada).andExpect(jsonPath("$.level").value(0))
                .andExpect(jsonPath("$.tiers[0].status").value("PENDING"));
        assertThat(count("select count(*) from kyc_decisions where type = 'SUBMITTED'")).isEqualTo(1);
    }

    @Test
    void aSecondOpenSubmissionForTheSameTierIsRefused() throws Exception {
        submit(ada, 1, TIER1).andExpect(status().isCreated());

        submit(ada, 1, TIER1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("KYC_ALREADY_SUBMITTED"));
        assertThat(count("select count(*) from kyc_submissions")).isEqualTo(1);
    }

    @Test
    void under18sAreNotEligible() throws Exception {
        submit(ada, 1, TIER1.replace(ADULT_DOB, LocalDate.now().minusYears(17).toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("KYC_NOT_ELIGIBLE"));
        assertThat(count("select count(*) from kyc_submissions")).isZero();
    }

    @Test
    void invalidTierOneInputIs400() throws Exception {
        submit(ada, 1, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        submit(ada, 1, TIER1.replace("\"NG\"", "\"Nigeria\"")).andExpect(status().isBadRequest());
        submit(ada, 1, TIER1.replace(ADULT_DOB, LocalDate.now().plusDays(1).toString())).andExpect(status().isBadRequest());
        submit(ada, 1, "{broken").andExpect(status().isBadRequest());
    }

    @Test
    void simultaneousTierOneSubmissionsCreateExactlyOne() throws Exception {
        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return submit(ada, 1, TIER1).andReturn().getResponse().getStatus();
            }));
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
        assertThat(count("select count(*) from kyc_submissions")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ tier 2

    @Test
    void tierTwoNeedsVerifiedTierOne() throws Exception {
        submit(ada, 2, tier2("NIN", "12345678901")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("KYC_TIER_PREREQUISITE"));
        submit(ada, 1, TIER1).andExpect(status().isCreated());
        submit(ada, 2, tier2("NIN", "12345678901")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("KYC_TIER_PREREQUISITE"));
    }

    @Test
    void tierTwoIdNumberIsEncryptedAtRest_andOnlyEverShownMasked() throws Exception {
        makeLevelOne(ada);

        submit(ada, 2, tier2("NIN", "12345678901")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.maskedIdNumber").value("*******8901"));

        Map<String, Object> row = jdbc.queryForMap(
                "select id_number_encrypted, id_number_hash, id_number_last4 from kyc_submissions where tier = 2");
        assertThat(row.get("id_number_encrypted").toString()).doesNotContain("12345678901");
        assertThat(row.get("id_number_hash").toString()).hasSize(64).doesNotContain("12345678901");
        assertThat(row.get("id_number_last4")).isEqualTo("8901");
        viewStatus(ada).andExpect(status().isOk()).andExpect(result ->
                assertThat(result.getResponse().getContentAsString()).doesNotContain("12345678901"));
    }

    @Test
    void ninAndBvnMustBeElevenDigits_otherIdsAreFlexible() throws Exception {
        makeLevelOne(ada);

        submit(ada, 2, tier2("NIN", "1234567890")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        submit(ada, 2, tier2("BVN", "1234567890A")).andExpect(status().isBadRequest());
        submit(ada, 2, tier2("NIN", "123")).andExpect(status().isBadRequest());
        submit(ada, 2, tier2("PASSPORT", "a-1234 567")).andExpect(status().isCreated());
    }

    @Test
    void theSameIdUsedByAnotherCustomerIsFlaggedForCloserReview_withoutTellingTheCustomerWhy() throws Exception {
        makeLevelOne(ada);
        makeLevelOne(bola);
        submit(ada, 2, tier2("NIN", "12345678901")).andExpect(jsonPath("$.status").value("PENDING"));

        String flagged = idOf(submit(bola, 2, tier2("NIN", "123 456 789 01")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUIRES_REVIEW")));

        internal(get("/internal/kyc/submissions/" + flagged), "reviewer.jane").andExpect(status().isOk())
                .andExpect(jsonPath("$.history[?(@.type == 'FLAGGED')].reason")
                        .value("ID number matches another customer's submission"));
        viewStatus(bola).andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .doesNotContain("another customer").doesNotContain("matches"));
    }

    @Test
    void reusingYourOwnIdAfterARejectionIsNotFlagged() throws Exception {
        makeLevelOne(ada);
        String first = idOf(submit(ada, 2, tier2("NIN", "12345678901")));
        decide(first, "reviewer.jane", "{\"decision\":\"REJECT\",\"reason\":\"Photo unclear\"}").andExpect(status().isOk());

        submit(ada, 2, tier2("NIN", "12345678901")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // ------------------------------------------------------------------ review

    @Test
    void approvingTierOneRaisesTheLevel_recordsTheDecision_andCannotBeRepeated() throws Exception {
        String id = idOf(submit(ada, 1, TIER1));

        decide(id, "reviewer.jane", "{\"decision\":\"APPROVE\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.customerLevel").value(1));

        viewStatus(ada).andExpect(jsonPath("$.level").value(1))
                .andExpect(jsonPath("$.tiers[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$.capabilities.allowedOperations[0]").value("FUND_WALLET"));
        decide(id, "reviewer.jane", "{\"decision\":\"APPROVE\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("KYC_NOT_REVIEWABLE"));
        submit(ada, 1, TIER1).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("KYC_ALREADY_VERIFIED"));
        assertThat(jdbc.queryForObject("select decided_by from kyc_submissions where id = ?", String.class, UUID.fromString(id)))
                .isEqualTo("reviewer.jane");
    }

    @Test
    void rejectingNeedsAReason_theCustomerSeesIt_andMayTryAgain_andHistoryIsKept() throws Exception {
        String id = idOf(submit(ada, 1, TIER1));

        decide(id, "reviewer.jane", "{\"decision\":\"REJECT\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        decide(id, "reviewer.jane", "{\"decision\":\"REJECT\",\"reason\":\"Address could not be confirmed\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.customerLevel").value(0));

        viewStatus(ada).andExpect(jsonPath("$.tiers[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.tiers[0].rejectionReason").value("Address could not be confirmed"));
        submit(ada, 1, TIER1).andExpect(status().isCreated());
        assertThat(count("select count(*) from kyc_submissions where customer_id = ?", ada)).isEqualTo(2);
    }

    @Test
    void theReviewQueueMasksIdNumbers_andDetailRevealsThemAndLogsWhoLooked() throws Exception {
        makeLevelOne(ada);
        String id = idOf(submit(ada, 2, tier2("NIN", "12345678901")));

        internal(get("/internal/kyc/submissions"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].maskedIdNumber").value("*******8901"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("12345678901"));

        internal(get("/internal/kyc/submissions/" + id), "reviewer.jane").andExpect(status().isOk())
                .andExpect(jsonPath("$.idNumber").value("12345678901"))
                .andExpect(jsonPath("$.idType").value("NIN"));
        internal(get("/internal/kyc/submissions/" + id), "reviewer.omar").andExpect(status().isOk())
                .andExpect(jsonPath("$.history[?(@.type == 'SENSITIVE_DATA_VIEWED')].actor")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("reviewer.jane", "reviewer.omar")));
        assertThat(count("select count(*) from kyc_decisions where type = 'SENSITIVE_DATA_VIEWED'")).isEqualTo(2);
    }

    @Test
    void decidedSubmissionsLeaveTheQueue() throws Exception {
        String id = idOf(submit(ada, 1, TIER1));
        internal(get("/internal/kyc/submissions"), null).andExpect(jsonPath("$[0].id").value(id));

        decide(id, "reviewer.jane", "{\"decision\":\"APPROVE\"}");

        internal(get("/internal/kyc/submissions"), null).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void reviewEndpointsNeedTheInternalKey_theReviewerName_andAKnownSubmission() throws Exception {
        String id = idOf(submit(ada, 1, TIER1));

        mockMvc.perform(get("/internal/kyc/submissions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/kyc/submissions").header("X-Internal-Api-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        decide(id, null, "{\"decision\":\"APPROVE\"}").andExpect(status().isBadRequest());
        decide(id, "  ", "{\"decision\":\"APPROVE\"}").andExpect(status().isBadRequest());
        decide(UUID.randomUUID().toString(), "reviewer.jane", "{\"decision\":\"APPROVE\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("KYC_SUBMISSION_NOT_FOUND"));
        viewStatus(ada).andExpect(jsonPath("$.level").value(0));
    }

    @Test
    void customersCannotReachReviewEndpointsWithJustAToken() throws Exception {
        String id = idOf(submit(ada, 1, TIER1));

        as(ada, post("/internal/kyc/submissions/" + id + "/decision").header("X-Reviewer", ada.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isUnauthorized());
        viewStatus(ada).andExpect(jsonPath("$.level").value(0));
    }

    @Test
    void reviewersDecidingAtTheSameMomentCannotBothSucceed() throws Exception {
        String id = idOf(submit(ada, 1, TIER1));
        int reviewers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(reviewers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < reviewers; i++) {
            String name = "reviewer." + i;
            String decision = i % 2 == 0 ? "{\"decision\":\"APPROVE\"}" : "{\"decision\":\"REJECT\",\"reason\":\"No\"}";
            results.add(pool.submit(() -> {
                start.await();
                return decide(id, name, decision).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 200) succeeded++;
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(count("select count(*) from kyc_decisions where type in ('APPROVED','REJECTED')")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ events (outbox -> Kafka)

    List<ConsumerRecord<String, String>> eventsFor(UUID customer, int atLeast) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("kyc.events"));
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (customer.toString().equals(r.key())) found.add(r);
                });
                return found.size() >= atLeast;
            });
        }
        return found;
    }

    @Test
    void anApprovalPublishesAKycLevelChangedEvent_withIdsOnlyAndNoPersonalData() throws Exception {
        submitAndApprove(ada, 1, TIER1);

        var events = eventsFor(ada, 1);

        String payload = events.getFirst().value();
        assertThat(JsonPath.<String>read(payload, "$.type")).isEqualTo("KYC_LEVEL_CHANGED");
        assertThat(JsonPath.<String>read(payload, "$.customerId")).isEqualTo(ada.toString());
        assertThat(JsonPath.<Integer>read(payload, "$.level")).isEqualTo(1);
        assertThat(JsonPath.<String>read(payload, "$.eventId")).isNotBlank();
        assertThat(payload).doesNotContain("Marina").doesNotContain(ADULT_DOB).doesNotContain("Lagos");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(count("select count(*) from outbox_events where published_at is null")).isZero());
    }

    @Test
    void aRejectionPublishesNothing_andTierTwoApprovalReportsLevelTwo() throws Exception {
        String rejected = idOf(submit(ada, 1, TIER1));
        decide(rejected, "reviewer.jane", "{\"decision\":\"REJECT\",\"reason\":\"No\"}");
        assertThat(count("select count(*) from outbox_events")).isZero();

        submitAndApprove(bola, 1, TIER1);
        submitAndApprove(bola, 2, tier2("NIN", "12345678901"));

        var events = eventsFor(bola, 2);
        assertThat(events).extracting(r -> JsonPath.<Integer>read(r.value(), "$.level")).containsExactly(1, 2);
    }

    @Test
    void theDecisionAndItsEventAreSavedTogether() throws Exception {
        submitAndApprove(ada, 1, TIER1);

        assertThat(count("select count(*) from kyc_decisions where type = 'APPROVED' and customer_id = ?", ada)).isEqualTo(1);
        assertThat(count("select count(*) from outbox_events where event_key = ?", ada.toString())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ enforcement (policy)

    ResultActions authorize(UUID customer, String body) throws Exception {
        return internal(post("/internal/kyc/customers/" + customer + "/authorize")
                .contentType(MediaType.APPLICATION_JSON).content(body), null);
    }

    static String operation(String op, String amount, String currency) {
        return "{\"operation\":\"%s\",\"amount\":%s,\"currency\":\"%s\"}".formatted(op, amount, currency);
    }

    @Test
    void anUnverifiedCustomerIsRefusedEverythingWithAClearReason() throws Exception {
        authorize(ada, operation("FUND_WALLET", "1000.00", "NGN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.level").value(0))
                .andExpect(jsonPath("$.reasonCode").value("KYC_LEVEL_TOO_LOW"))
                .andExpect(jsonPath("$.requiredLevel").value(1));
    }

    @Test
    void enforcementFollowsTheLiveLevel_asTheCustomerGetsVerified() throws Exception {
        makeLevelOne(ada);

        authorize(ada, operation("P2P_TRANSFER", "10000.00", "NGN")).andExpect(jsonPath("$.allowed").value(true));
        authorize(ada, operation("P2P_TRANSFER", "60000.00", "NGN"))
                .andExpect(jsonPath("$.allowed").value(false)).andExpect(jsonPath("$.reasonCode").value("KYC_LIMIT_EXCEEDED"));
        authorize(ada, operation("EXTERNAL_TRANSFER", "100.00", "NGN"))
                .andExpect(jsonPath("$.reasonCode").value("KYC_LEVEL_TOO_LOW")).andExpect(jsonPath("$.requiredLevel").value(2));

        submitAndApprove(ada, 2, tier2("NIN", "12345678901"));

        authorize(ada, operation("EXTERNAL_TRANSFER", "1000000.00", "NGN")).andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.level").value(2));
    }

    @Test
    void aRejectedOrPendingTierGrantsNothing() throws Exception {
        submit(ada, 1, TIER1);
        authorize(ada, operation("FUND_WALLET", "10.00", "NGN")).andExpect(jsonPath("$.allowed").value(false));
    }

    @Test
    void currencyMustBeExplicitAndSupported_andBadAmountsAreRejected() throws Exception {
        makeLevelOne(ada);

        authorize(ada, operation("P2P_TRANSFER", "10.00", "USD")).andExpect(jsonPath("$.reasonCode").value("UNSUPPORTED_CURRENCY"));
        authorize(ada, "{\"operation\":\"P2P_TRANSFER\",\"amount\":10.00}").andExpect(status().isBadRequest());
        authorize(ada, operation("P2P_TRANSFER", "-5", "NGN")).andExpect(status().isBadRequest());
        authorize(ada, operation("P2P_TRANSFER", "0", "NGN")).andExpect(status().isBadRequest());
        authorize(ada, operation("P2P_TRANSFER", "1.234", "NGN")).andExpect(status().isBadRequest());
        authorize(ada, operation("TELEPORT", "1.00", "NGN")).andExpect(status().isBadRequest());
    }

    @Test
    void capabilitiesAreReadLive_andTheEndpointNeedsTheInternalKey() throws Exception {
        internal(get("/internal/kyc/customers/" + ada + "/capabilities"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value(0)).andExpect(jsonPath("$.allowedOperations").isEmpty());
        makeLevelOne(ada);
        internal(get("/internal/kyc/customers/" + ada + "/capabilities"), null)
                .andExpect(jsonPath("$.level").value(1))
                .andExpect(jsonPath("$.maxSingleTransactionAmount").value(50000.0))
                .andExpect(jsonPath("$.currency").value("NGN"));

        mockMvc.perform(get("/internal/kyc/customers/" + ada + "/capabilities")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/kyc/customers/" + ada + "/authorize").contentType(MediaType.APPLICATION_JSON)
                .content(operation("FUND_WALLET", "1.00", "NGN"))).andExpect(status().isUnauthorized());
    }
}
