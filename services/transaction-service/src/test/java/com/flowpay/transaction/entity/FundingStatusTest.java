package com.flowpay.transaction.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FundingStatusTest {

    @Test
    void theHappyPathIsPendingProcessingSuccess() {
        assertThat(FundingStatus.PENDING.canMoveTo(FundingStatus.PROCESSING)).isTrue();
        assertThat(FundingStatus.PROCESSING.canMoveTo(FundingStatus.SUCCESS)).isTrue();
    }

    @Test
    void aPaymentCanFailOnlyBeforeItIsConfirmed() {
        assertThat(FundingStatus.PENDING.canMoveTo(FundingStatus.FAILED)).isTrue();
        assertThat(FundingStatus.PROCESSING.canMoveTo(FundingStatus.FAILED)).isFalse();
    }

    @Test
    void aFundingCannotSkipStraightToSuccess() {
        assertThat(FundingStatus.PENDING.canMoveTo(FundingStatus.SUCCESS)).isFalse();
        assertThat(FundingStatus.REQUIRES_REVIEW.canMoveTo(FundingStatus.SUCCESS)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = FundingStatus.class, names = {"SUCCESS", "FAILED"})
    void successAndFailedAreFinal(FundingStatus status) {
        assertThat(status.isFinal()).isTrue();
        assertThat(status.allowedNext()).isEmpty();
    }

    @Test
    void aFailedFundingCanNeverBecomeSuccess() {
        assertThat(FundingStatus.FAILED.canMoveTo(FundingStatus.SUCCESS)).isFalse();
        assertThat(FundingStatus.SUCCESS.canMoveTo(FundingStatus.FAILED)).isFalse();
    }

    @Test
    void aReviewCanOnlyGoBackToProcessing() {
        assertThat(FundingStatus.REQUIRES_REVIEW.allowedNext()).containsExactly(FundingStatus.PROCESSING);
    }

    @ParameterizedTest
    @EnumSource(FundingStatus.class)
    void noStatusMovesToItself(FundingStatus status) {
        assertThat(status.canMoveTo(status)).isFalse();
    }
}
