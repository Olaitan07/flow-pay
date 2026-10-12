package com.flowpay.transaction.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * The funding lifecycle. Only the moves listed here are legal, so a FAILED funding can never quietly become SUCCESS.
 * <pre>
 * PENDING ──► PROCESSING ──► SUCCESS
 *    │            │
 *    ├──► FAILED  └──► REQUIRES_REVIEW ──► PROCESSING (after the cause is fixed)
 *    └──► REQUIRES_REVIEW
 * </pre>
 * PENDING: waiting for the payment provider. PROCESSING: payment confirmed, wallet credit in progress.
 * SUCCESS and FAILED are final. REQUIRES_REVIEW means money may have been received but a person must look.
 */
public enum FundingStatus {
    PENDING, PROCESSING, SUCCESS, FAILED, REQUIRES_REVIEW;

    public Set<FundingStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(PROCESSING, FAILED, REQUIRES_REVIEW);
            case PROCESSING -> EnumSet.of(SUCCESS, REQUIRES_REVIEW);
            case REQUIRES_REVIEW -> EnumSet.of(PROCESSING);
            case SUCCESS, FAILED -> EnumSet.noneOf(FundingStatus.class);
        };
    }

    public boolean canMoveTo(FundingStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isFinal() {
        return allowedNext().isEmpty();
    }
}
