package com.flowpay.transaction.service;

import com.flowpay.transaction.configuration.TransactionProperties;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Works out the fee on a transfer. Today it is one flat amount from configuration. Epic 11 (Fees) will make it depend
 * on amount, currency and customer tier; everything else already uses this one place and the fee saved on each transfer.
 */
@Component
public class TransferFeeCalculator {

    private final TransactionProperties properties;

    public TransferFeeCalculator(TransactionProperties properties) {
        this.properties = properties;
    }

    public BigDecimal feeFor(BigDecimal amount, String currency) {
        return properties.transfer().fee().setScale(2);
    }
}
