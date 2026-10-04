package com.flowpay.kyc.entity;

/** Status of one tier. A tier with no submission is reported as NOT_STARTED. */
public enum KycStatus {
    NOT_STARTED,
    PENDING,
    REQUIRES_REVIEW,
    VERIFIED,
    REJECTED;

    /** Statuses that mean "a person still has to decide". */
    public boolean isAwaitingReview() {
        return this == PENDING || this == REQUIRES_REVIEW;
    }
}
