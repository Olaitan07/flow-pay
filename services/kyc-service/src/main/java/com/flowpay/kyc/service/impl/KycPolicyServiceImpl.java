package com.flowpay.kyc.service.impl;

import com.flowpay.kyc.configuration.KycProperties;
import com.flowpay.kyc.dto.request.AuthorizeOperationRequest;
import com.flowpay.kyc.dto.response.AuthorizationDecision;
import com.flowpay.kyc.dto.response.CapabilitiesResponse;
import com.flowpay.kyc.entity.KycOperation;
import com.flowpay.kyc.repository.KycSubmissionRepository;
import com.flowpay.kyc.service.KycPolicyService;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KycPolicyServiceImpl implements KycPolicyService {

    private final KycSubmissionRepository submissions;
    private final KycProperties properties;

    public KycPolicyServiceImpl(KycSubmissionRepository submissions, KycProperties properties) {
        this.submissions = submissions;
        this.properties = properties;
    }

    /** Always read from the database, never from a token or cache: a stale level could let someone through. */
    @Override
    @Transactional(readOnly = true)
    public int levelOf(UUID customerId) {
        Integer tier = submissions.findHighestVerifiedTier(customerId);
        return tier == null ? 0 : tier;
    }

    @Override
    public CapabilitiesResponse capabilitiesFor(int level) {
        KycProperties.Level config = configFor(level);
        List<KycOperation> operations = config.allowedOperations().stream().sorted().toList();
        return new CapabilitiesResponse(level, operations, config.maxSingleTransaction(), properties.currency());
    }

    @Override
    @Transactional(readOnly = true)
    public CapabilitiesResponse capabilitiesOf(UUID customerId) {
        return capabilitiesFor(levelOf(customerId));
    }

    @Override
    @Transactional(readOnly = true)
    public AuthorizationDecision authorize(UUID customerId, AuthorizeOperationRequest request) {
        int level = levelOf(customerId);
        KycProperties.Level config = configFor(level);
        String currency = properties.currency();

        if (!currency.equals(request.currency())) {
            return deny(level, "UNSUPPORTED_CURRENCY", null, config);
        }
        if (!config.allowedOperations().contains(request.operation())) {
            return deny(level, "KYC_LEVEL_TOO_LOW", lowestLevelAllowing(request.operation()), config);
        }
        if (request.amount().compareTo(config.maxSingleTransaction()) > 0) {
            return deny(level, "KYC_LIMIT_EXCEEDED", null, config);
        }
        return new AuthorizationDecision(true, level, null, null, config.maxSingleTransaction(), currency);
    }

    private AuthorizationDecision deny(int level, String reason, Integer requiredLevel, KycProperties.Level config) {
        return new AuthorizationDecision(false, level, reason, requiredLevel, config.maxSingleTransaction(),
                properties.currency());
    }

    /** The configured level that applies: the exact one, or the highest one below it. */
    private KycProperties.Level configFor(int level) {
        return properties.levels().stream()
                .filter(l -> l.level() <= level)
                .max(Comparator.comparingInt(KycProperties.Level::level))
                .orElseThrow(() -> new IllegalStateException("No KYC level configured for " + level));
    }

    private Integer lowestLevelAllowing(KycOperation operation) {
        return properties.levels().stream()
                .filter(l -> l.allowedOperations().contains(operation))
                .map(KycProperties.Level::level)
                .min(Integer::compare)
                .orElse(null);
    }
}
