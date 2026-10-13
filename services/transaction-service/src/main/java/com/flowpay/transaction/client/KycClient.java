package com.flowpay.transaction.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flowpay.transaction.exception.TransactionException;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Asks kyc-service, live, whether a customer may do something. Never cached. Fails closed. */
public class KycClient {

    private static final Logger log = LoggerFactory.getLogger(KycClient.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Decision(boolean allowed, int level, String reasonCode, Integer requiredLevel) {
    }

    record AuthorizeRequest(String operation, BigDecimal amount, String currency) {
    }

    private final RestClient restClient;

    public KycClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** May this customer add this much money to a wallet? Throws 503 if the answer cannot be obtained. */
    public Decision mayFundWallet(UUID customerId, BigDecimal amount, String currency) {
        return authorize(customerId, "FUND_WALLET", amount, currency);
    }

    /** May this customer send this much to another customer? Throws 503 if the answer cannot be obtained. */
    public Decision mayTransfer(UUID customerId, BigDecimal amount, String currency) {
        return authorize(customerId, "P2P_TRANSFER", amount, currency);
    }

    private Decision authorize(UUID customerId, String operation, BigDecimal amount, String currency) {
        try {
            Decision decision = restClient.post().uri("/internal/kyc/customers/{id}/authorize", customerId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AuthorizeRequest(operation, amount, currency))
                    .retrieve()
                    .body(Decision.class);
            if (decision == null) {
                throw unavailable(null);
            }
            return decision;
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    private static TransactionException unavailable(Exception cause) {
        log.error("kyc-service could not be reached or refused the request", cause);
        return TransactionException.unavailable("KYC_UNAVAILABLE",
                "Identity verification status cannot be checked right now. Try again shortly.");
    }
}
