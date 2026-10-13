package com.flowpay.transaction.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * The transfer lifecycle. Only the moves listed here are legal, so a FAILED transfer can never quietly become SUCCESS.
 * <pre>
 * PROCESSING ──► SUCCESS
 *     └───────► FAILED
 * </pre>
 * PROCESSING: recorded, money not yet confirmed as moved (it may be moving right now, or the wallet service could not
 * be reached and the recovery sweep will finish it). SUCCESS and FAILED are final.
 */
public enum TransferStatus {
    PROCESSING, SUCCESS, FAILED;

    public Set<TransferStatus> allowedNext() {
        return switch (this) {
            case PROCESSING -> EnumSet.of(SUCCESS, FAILED);
            case SUCCESS, FAILED -> EnumSet.noneOf(TransferStatus.class);
        };
    }

    public boolean canMoveTo(TransferStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isFinal() {
        return allowedNext().isEmpty();
    }
}
