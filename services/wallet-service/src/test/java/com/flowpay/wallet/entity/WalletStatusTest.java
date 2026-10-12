package com.flowpay.wallet.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WalletStatusTest {

    @Test
    void onlyAnActiveWalletMaySendMoney() {
        assertThat(WalletStatus.ACTIVE.allowsOutgoing()).isTrue();
        assertThat(EnumSet.of(WalletStatus.FROZEN, WalletStatus.BLOCKED, WalletStatus.CLOSED))
                .noneMatch(WalletStatus::allowsOutgoing);
    }

    @Test
    void activeAndFrozenWalletsMayReceiveMoney_blockedAndClosedMayNot() {
        assertThat(WalletStatus.ACTIVE.allowsIncoming()).isTrue();
        assertThat(WalletStatus.FROZEN.allowsIncoming()).isTrue();
        assertThat(WalletStatus.BLOCKED.allowsIncoming()).isFalse();
        assertThat(WalletStatus.CLOSED.allowsIncoming()).isFalse();
    }

    @Test
    void aClosedWalletCanNeverChange() {
        assertThat(WalletStatus.CLOSED.allowedNext()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(WalletStatus.class)
    void aStatusNeverMovesToItself(WalletStatus status) {
        assertThat(status.canMoveTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = WalletStatus.class, names = {"ACTIVE", "FROZEN", "BLOCKED"})
    void anyLiveStatusCanBeClosed(WalletStatus status) {
        assertThat(status.canMoveTo(WalletStatus.CLOSED)).isTrue();
    }

    @Test
    void liveStatusesCanMoveBetweenEachOther() {
        assertThat(WalletStatus.ACTIVE.allowedNext()).containsExactlyInAnyOrder(WalletStatus.FROZEN, WalletStatus.BLOCKED, WalletStatus.CLOSED);
        assertThat(WalletStatus.FROZEN.allowedNext()).containsExactlyInAnyOrder(WalletStatus.ACTIVE, WalletStatus.BLOCKED, WalletStatus.CLOSED);
        assertThat(WalletStatus.BLOCKED.allowedNext()).containsExactlyInAnyOrder(WalletStatus.ACTIVE, WalletStatus.FROZEN, WalletStatus.CLOSED);
    }
}
