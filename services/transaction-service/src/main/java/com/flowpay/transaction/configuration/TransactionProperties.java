package com.flowpay.transaction.configuration;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "flowpay.transaction")
public record TransactionProperties(Service wallet, Service kyc, Recovery recovery, Transfer transfer) {

    public TransactionProperties {
        if (wallet == null) wallet = new Service("http://localhost:8083", null, null);
        if (kyc == null) kyc = new Service("http://localhost:8087", null, null);
        if (recovery == null) recovery = new Recovery(null, null, 0);
        if (transfer == null) transfer = new Transfer(null);
    }

    /** Where another FlowPay service lives and how long to wait for it. */
    public record Service(@DefaultValue("http://localhost:8083") String url,
                          @DefaultValue("2s") Duration connectTimeout,
                          @DefaultValue("5s") Duration readTimeout) {
        public Service {
            if (connectTimeout == null) connectTimeout = Duration.ofSeconds(2);
            if (readTimeout == null) readTimeout = Duration.ofSeconds(5);
        }
    }

    /**
     * Finishing fundings that were confirmed but never credited (for example the wallet service was down at that
     * moment). The sweep retries any funding stuck in PROCESSING for at least minAge.
     */
    public record Recovery(@DefaultValue("30s") Duration interval, @DefaultValue("20s") Duration minAge,
                           @DefaultValue("100") int batchSize) {
        public Recovery {
            if (interval == null) interval = Duration.ofSeconds(30);
            if (minAge == null) minAge = Duration.ofSeconds(20);
            if (batchSize <= 0) batchSize = 100;
        }
    }

    /**
     * Person-to-person transfers. The fee is a flat amount for now; Epic 11 (Fees) replaces it with real rules
     * (by amount, tier and so on). The amount applied is saved on each transfer, so changing it never alters history.
     */
    public record Transfer(@DefaultValue("100.00") BigDecimal fee) {
        public Transfer {
            if (fee == null) fee = new BigDecimal("100.00");
            if (fee.signum() < 0 || fee.scale() > 2) {
                throw new IllegalArgumentException("flowpay.transaction.transfer.fee must be zero or more with at most 2 decimals");
            }
        }
    }
}
