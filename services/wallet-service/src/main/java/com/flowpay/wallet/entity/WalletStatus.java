package com.flowpay.wallet.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * The wallet-status rules. These decide what a wallet may do, and which status changes are allowed.
 * <ul>
 *   <li>ACTIVE: money may go out and come in.</li>
 *   <li>FROZEN: nothing may go out; money may still come in (a hold, for example during a dispute).</li>
 *   <li>BLOCKED: nothing in or out (compliance or fraud).</li>
 *   <li>CLOSED: final. Nothing in or out, and it can never be reopened.</li>
 * </ul>
 */
public enum WalletStatus {
    ACTIVE, FROZEN, BLOCKED, CLOSED;

    public boolean allowsOutgoing() {
        return this == ACTIVE;
    }

    public boolean allowsIncoming() {
        return this == ACTIVE || this == FROZEN;
    }

    /** The statuses this one may move to. A wallet never moves to the status it already has. */
    public Set<WalletStatus> allowedNext() {
        return switch (this) {
            case ACTIVE -> EnumSet.of(FROZEN, BLOCKED, CLOSED);
            case FROZEN -> EnumSet.of(ACTIVE, BLOCKED, CLOSED);
            case BLOCKED -> EnumSet.of(ACTIVE, FROZEN, CLOSED);
            case CLOSED -> EnumSet.noneOf(WalletStatus.class);
        };
    }

    public boolean canMoveTo(WalletStatus next) {
        return allowedNext().contains(next);
    }
}
