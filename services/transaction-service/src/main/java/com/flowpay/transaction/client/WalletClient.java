package com.flowpay.transaction.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flowpay.transaction.exception.TransactionException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to wallet-service. Wallet balances belong to wallet-service alone: this service never touches its database,
 * it only asks it to credit a wallet. The funding reference is the wallet's movement reference, so asking twice
 * credits once.
 */
public class WalletClient {

    private static final Logger log = LoggerFactory.getLogger(WalletClient.class);

    /** What the wallet service answered to a credit request. */
    public enum Kind {
        /** The money is in the wallet (now, or already from an earlier identical request). */
        CREDITED,
        /** The wallet will not take this money and retrying will not help. A person must decide. */
        REFUSED,
        /** Could not get a clear answer. Safe to try again with the same reference. */
        UNAVAILABLE
    }

    public record CreditOutcome(Kind kind, String code) {
        static CreditOutcome credited() { return new CreditOutcome(Kind.CREDITED, null); }
        static CreditOutcome refused(String code) { return new CreditOutcome(Kind.REFUSED, code); }
        static CreditOutcome unavailable() { return new CreditOutcome(Kind.UNAVAILABLE, null); }
    }

    public record WalletView(UUID id, UUID customerId, String currency, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Detail(@JsonIgnoreProperties(ignoreUnknown = true) Wallet wallet) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Wallet(UUID id, UUID customerId, String currency, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String error) {
    }

    record CreditRequest(String reference, BigDecimal amount, String currency) {
    }

    private final RestClient restClient;

    public WalletClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** The wallet, or empty if there is no such wallet. Throws 503 if the wallet service cannot answer. */
    public Optional<WalletView> find(UUID walletId) {
        try {
            return restClient.get().uri("/internal/wallets/{id}", walletId).exchange((request, response) -> {
                int code = response.getStatusCode().value();
                if (code == 404) {
                    return Optional.<WalletView>empty();
                }
                if (code != 200) {
                    throw unavailable("wallet lookup answered " + code, null);
                }
                Detail detail = response.bodyTo(Detail.class);
                if (detail == null || detail.wallet() == null) {
                    throw unavailable("wallet lookup returned no wallet", null);
                }
                Wallet w = detail.wallet();
                return Optional.of(new WalletView(w.id(), w.customerId(), w.currency(), w.status()));
            });
        } catch (RestClientException e) {
            throw unavailable("wallet service unreachable", e);
        }
    }

    /** Asks the wallet service to put money in. Never throws: every outcome is one of the three kinds. */
    public CreditOutcome credit(UUID walletId, String reference, BigDecimal amount, String currency) {
        try {
            return restClient.post().uri("/internal/wallets/{id}/credit", walletId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CreditRequest(reference, amount, currency))
                    .exchange((request, response) -> {
                        int code = response.getStatusCode().value();
                        if (code == 200 || code == 201) {
                            return CreditOutcome.credited();
                        }
                        // 401/403: our key is wrong. 408/429/5xx: the wallet service is struggling. None of these
                        // say anything about the money, so keep it and try again.
                        if (code == 401 || code == 403 || code == 408 || code == 429 || code >= 500) {
                            log.warn("Wallet credit answered {} for reference {}", code, reference);
                            return CreditOutcome.unavailable();
                        }
                        return CreditOutcome.refused(reasonOf(response, code));
                    });
        } catch (RestClientException e) {
            log.warn("Wallet credit failed for reference {}: {}", reference, e.getMessage());
            return CreditOutcome.unavailable();
        }
    }

    /** The wallet's error code if it sent a readable one; otherwise the status alone, which is already a clear refusal. */
    private static String reasonOf(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response, int code) {
        try {
            ErrorBody body = response.bodyTo(ErrorBody.class);
            if (body != null && body.error() != null) {
                return body.error();
            }
        } catch (RestClientException e) {
            // an unreadable body does not change what the status code says
        }
        return "HTTP_" + code;
    }

    private static TransactionException unavailable(String why, Exception cause) {
        log.error("wallet-service problem: {}", why, cause);
        return TransactionException.unavailable("WALLET_UNAVAILABLE",
                "The wallet cannot be checked right now. Try again shortly.");
    }
}
