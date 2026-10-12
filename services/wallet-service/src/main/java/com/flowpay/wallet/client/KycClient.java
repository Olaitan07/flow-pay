package com.flowpay.wallet.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flowpay.wallet.exception.WalletException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Asks kyc-service, live, how verified a customer is. Never cached: the answer decides whether money may be held. */
public class KycClient {

    private static final Logger log = LoggerFactory.getLogger(KycClient.class);

    private final RestClient restClient;

    public KycClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** The customer's verification level (0 if none). Fails closed: if kyc-service cannot answer, the caller is refused. */
    public int levelOf(UUID customerId) {
        try {
            Capabilities capabilities = restClient.get()
                    .uri("/internal/kyc/customers/{id}/capabilities", customerId)
                    .retrieve()
                    .body(Capabilities.class);
            if (capabilities == null) {
                throw unavailable(null);
            }
            return capabilities.level();
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    private static WalletException unavailable(Exception cause) {
        log.error("kyc-service could not be reached or refused the request", cause);
        return new WalletException(HttpStatus.SERVICE_UNAVAILABLE, "KYC_UNAVAILABLE",
                "Identity verification status cannot be checked right now. Try again shortly.");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Capabilities(int level) {
    }
}
