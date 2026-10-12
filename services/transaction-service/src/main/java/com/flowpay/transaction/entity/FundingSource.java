package com.flowpay.transaction.entity;

/** Where the money comes from. Moving money between FlowPay customers is a transfer (a separate feature), not funding. */
public enum FundingSource {
    BANK_TRANSFER, CARD, EXTERNAL_PROVIDER
}
