package com.flowpay.transaction.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TransferStatusTest {

    @Test
    void aProcessingTransferCanSucceedOrFail() {
        assertThat(TransferStatus.PROCESSING.canMoveTo(TransferStatus.SUCCESS)).isTrue();
        assertThat(TransferStatus.PROCESSING.canMoveTo(TransferStatus.FAILED)).isTrue();
        assertThat(TransferStatus.PROCESSING.isFinal()).isFalse();
    }

    @Test
    void successAndFailureAreFinal_soAFailedTransferCanNeverBecomeASuccess() {
        for (TransferStatus done : new TransferStatus[] {TransferStatus.SUCCESS, TransferStatus.FAILED}) {
            assertThat(done.isFinal()).isTrue();
            for (TransferStatus next : TransferStatus.values()) {
                assertThat(done.canMoveTo(next)).as(done + " -> " + next).isFalse();
            }
        }
    }
}
