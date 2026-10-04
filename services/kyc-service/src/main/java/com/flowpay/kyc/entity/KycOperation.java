package com.flowpay.kyc.entity;

/** Things a customer may be allowed to do, depending on their verification level. */
public enum KycOperation {
    FUND_WALLET,
    P2P_TRANSFER,
    EXTERNAL_TRANSFER
}
