package com.flowpay.transaction.entity;

/** Why a funding needs a person to look at it. */
public enum ReviewCode {
    /** The provider confirmed a different amount or currency than was requested. Never credited automatically. */
    AMOUNT_MISMATCH,
    /** The provider confirmed payment but the wallet refused the money (for example it was blocked or closed). */
    CREDIT_REFUSED
}
