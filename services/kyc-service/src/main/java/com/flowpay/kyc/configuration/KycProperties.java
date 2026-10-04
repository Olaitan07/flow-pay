package com.flowpay.kyc.configuration;

import com.flowpay.kyc.entity.KycOperation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What each verification level may do. Defaults are below; override under {@code flowpay.kyc.levels}.
 * Money limits that vary per day or per period belong to the transaction-limits epic; this is the cap a
 * level imposes on a single transaction.
 */
@ConfigurationProperties(prefix = "flowpay.kyc")
public record KycProperties(String encryptionKey, String currency, List<Level> levels) {

    public KycProperties {
        if (currency == null || currency.isBlank()) currency = "NGN";
        if (levels == null || levels.isEmpty()) {
            levels = List.of(
                    new Level(0, Set.of(), BigDecimal.ZERO),
                    new Level(1, Set.of(KycOperation.FUND_WALLET, KycOperation.P2P_TRANSFER), new BigDecimal("50000.00")),
                    new Level(2, Set.of(KycOperation.FUND_WALLET, KycOperation.P2P_TRANSFER, KycOperation.EXTERNAL_TRANSFER),
                            new BigDecimal("5000000.00")));
        }
    }

    public record Level(int level, Set<KycOperation> allowedOperations, BigDecimal maxSingleTransaction) {
        public Level {
            if (allowedOperations == null) allowedOperations = Set.of();
            if (maxSingleTransaction == null) maxSingleTransaction = BigDecimal.ZERO;
        }
    }
}
