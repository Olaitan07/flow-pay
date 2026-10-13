package com.flowpay.wallet.configuration;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "flowpay.wallet")
public record WalletProperties(
        /* Currencies a wallet may be opened in. Anything else is refused. */
        @DefaultValue("NGN") Set<String> supportedCurrencies,
        /* The platform's own customer id. Its wallet receives the fees charged on transfers. */
        @DefaultValue("00000000-0000-0000-0000-0000000000fe") UUID feeCustomerId,
        Kyc kyc) {

    public WalletProperties {
        if (kyc == null) {
            kyc = new Kyc(null, 1, null, null);
        }
        if (feeCustomerId == null) {
            feeCustomerId = UUID.fromString("00000000-0000-0000-0000-0000000000fe");
        }
    }

    /** How the wallet service asks kyc-service whether a customer is verified. */
    public record Kyc(
            @DefaultValue("http://localhost:8087") String url,
            /* Lowest verification level that may open a wallet. */
            @DefaultValue("1") int requiredLevel,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("3s") Duration readTimeout) {
    }
}
