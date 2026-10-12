package com.flowpay.kyc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.flowpay.kyc.configuration.KycProperties;
import com.flowpay.kyc.dto.request.AuthorizeOperationRequest;
import com.flowpay.kyc.entity.KycOperation;
import com.flowpay.kyc.repository.KycSubmissionRepository;
import com.flowpay.kyc.service.impl.KycPolicyServiceImpl;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KycPolicyServiceTest {

    @Mock KycSubmissionRepository submissions;

    final UUID customer = UUID.randomUUID();

    KycPolicyServiceImpl policyAtLevel(Integer highestVerifiedTier) {
        when(submissions.findHighestVerifiedTier(customer)).thenReturn(highestVerifiedTier);
        return new KycPolicyServiceImpl(submissions, new KycProperties("k", "NGN", null));
    }

    static AuthorizeOperationRequest ask(KycOperation op, String amount, String currency) {
        return new AuthorizeOperationRequest(op, new BigDecimal(amount), currency);
    }

    @Test
    void anUnverifiedCustomerIsLevelZeroAndMayDoNothing() {
        var policy = policyAtLevel(null);

        assertThat(policy.levelOf(customer)).isZero();
        assertThat(policy.capabilitiesOf(customer).allowedOperations()).isEmpty();
        var decision = policy.authorize(customer, ask(KycOperation.FUND_WALLET, "1000.00", "NGN"));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("KYC_LEVEL_TOO_LOW");
        assertThat(decision.requiredLevel()).isEqualTo(1);
    }

    @Test
    void levelOneMayFundAndSendLocally_withinItsCap_butNotSendExternally() {
        var policy = policyAtLevel(1);

        assertThat(policy.authorize(customer, ask(KycOperation.P2P_TRANSFER, "10000.00", "NGN")).allowed()).isTrue();
        assertThat(policy.authorize(customer, ask(KycOperation.P2P_TRANSFER, "50000.00", "NGN")).allowed())
                .as("exactly at the cap is allowed").isTrue();
        var overCap = policy.authorize(customer, ask(KycOperation.P2P_TRANSFER, "50000.01", "NGN"));
        assertThat(overCap.allowed()).isFalse();
        assertThat(overCap.reasonCode()).isEqualTo("KYC_LIMIT_EXCEEDED");
        var external = policy.authorize(customer, ask(KycOperation.EXTERNAL_TRANSFER, "100.00", "NGN"));
        assertThat(external.reasonCode()).isEqualTo("KYC_LEVEL_TOO_LOW");
        assertThat(external.requiredLevel()).isEqualTo(2);
    }

    @Test
    void levelTwoMayDoEverythingUpToTheHigherCap() {
        var policy = policyAtLevel(2);

        assertThat(policy.authorize(customer, ask(KycOperation.EXTERNAL_TRANSFER, "5000000.00", "NGN")).allowed()).isTrue();
        assertThat(policy.authorize(customer, ask(KycOperation.EXTERNAL_TRANSFER, "5000000.01", "NGN")).reasonCode())
                .isEqualTo("KYC_LIMIT_EXCEEDED");
    }

    @Test
    void currencyIsNeverAssumed() {
        var decision = policyAtLevel(2).authorize(customer, ask(KycOperation.P2P_TRANSFER, "10.00", "USD"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void aLevelAboveTheConfiguredOnesUsesTheHighestConfigured() {
        assertThat(policyAtLevel(7).capabilitiesOf(customer).allowedOperations()).hasSize(3);
    }
}
