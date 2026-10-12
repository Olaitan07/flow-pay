package com.flowpay.kyc.service.impl;

import com.flowpay.kyc.dto.request.SubmitTierOneRequest;
import com.flowpay.kyc.dto.request.SubmitTierTwoRequest;
import com.flowpay.kyc.dto.response.KycStatusResponse;
import com.flowpay.kyc.dto.response.SubmissionResponse;
import com.flowpay.kyc.dto.response.TierStatusResponse;
import com.flowpay.kyc.entity.DecisionType;
import com.flowpay.kyc.entity.KycDecision;
import com.flowpay.kyc.entity.KycStatus;
import com.flowpay.kyc.entity.KycSubmission;
import com.flowpay.kyc.exception.AuthenticationRequiredException;
import com.flowpay.kyc.exception.InvalidKycRequestException;
import com.flowpay.kyc.exception.KycConflictException;
import com.flowpay.kyc.exception.KycRuleViolationException;
import com.flowpay.kyc.repository.KycDecisionRepository;
import com.flowpay.kyc.repository.KycSubmissionRepository;
import com.flowpay.kyc.service.IdNumberProtector;
import com.flowpay.kyc.service.KycPolicyService;
import com.flowpay.kyc.service.KycSubmissionService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KycSubmissionServiceImpl implements KycSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(KycSubmissionServiceImpl.class);
    private static final int MINIMUM_AGE = 18;
    private static final EnumSet<KycStatus> OPEN = EnumSet.of(KycStatus.PENDING, KycStatus.REQUIRES_REVIEW);
    private static final EnumSet<KycStatus> COUNTS_AS_DUPLICATE =
            EnumSet.of(KycStatus.PENDING, KycStatus.REQUIRES_REVIEW, KycStatus.VERIFIED);

    private final KycSubmissionRepository submissions;
    private final KycDecisionRepository decisions;
    private final KycPolicyService policy;
    private final IdNumberProtector protector;
    private final Clock clock;

    public KycSubmissionServiceImpl(KycSubmissionRepository submissions, KycDecisionRepository decisions,
                                    KycPolicyService policy, IdNumberProtector protector, Clock clock) {
        this.submissions = submissions;
        this.decisions = decisions;
        this.policy = policy;
        this.protector = protector;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public KycStatusResponse getStatus(UUID customerId) {
        requireCustomer(customerId);
        List<KycSubmission> all = submissions.findByCustomerIdOrderBySubmittedAtDesc(customerId);
        List<TierStatusResponse> tiers = List.of(tierStatus(all, 1), tierStatus(all, 2));
        int level = policy.levelOf(customerId);
        return new KycStatusResponse(customerId, level, tiers, policy.capabilitiesFor(level));
    }

    @Override
    @Transactional
    public SubmissionResponse submitTierOne(UUID customerId, SubmitTierOneRequest request) {
        requireCustomer(customerId);
        Instant now = clock.instant();
        rejectIfAlreadyHandled(customerId, 1);

        if (Period.between(request.dateOfBirth(), LocalDate.ofInstant(now, ZoneOffset.UTC)).getYears() < MINIMUM_AGE) {
            throw new KycRuleViolationException("KYC_NOT_ELIGIBLE", "You must be at least " + MINIMUM_AGE + " years old");
        }

        KycSubmission submission = KycSubmission.tierOne(customerId, request.dateOfBirth(), request.addressLine1().trim(),
                blankToNull(request.addressLine2()), request.city().trim(), request.state().trim(),
                request.postalCode().trim(), request.country(), now);
        save(submission, customerId);
        decisions.save(new KycDecision(submission.getId(), customerId, DecisionType.SUBMITTED, customerId.toString(), null, now));
        log.info("KYC tier 1 submitted customerId={} submissionId={}", customerId, submission.getId());
        return SubmissionResponse.from(submission);
    }

    @Override
    @Transactional
    public SubmissionResponse submitTierTwo(UUID customerId, SubmitTierTwoRequest request) {
        requireCustomer(customerId);
        Instant now = clock.instant();
        rejectIfAlreadyHandled(customerId, 2);

        if (!submissions.existsByCustomerIdAndTierAndStatusIn(customerId, 1, EnumSet.of(KycStatus.VERIFIED))) {
            throw new KycRuleViolationException("KYC_TIER_PREREQUISITE", "Tier 1 must be verified before tier 2");
        }

        String number = IdNumberProtector.normalise(request.idNumber());
        if (request.idType().requiresElevenDigits() && !number.matches("\\d{11}")) {
            throw new InvalidKycRequestException("VALIDATION_ERROR", "idNumber must be exactly 11 digits for " + request.idType());
        }

        String fingerprint = protector.fingerprint(request.idType(), number);
        // If another customer already used this ID, a person must look closer. The customer is not told why.
        boolean flagged = submissions.existsOtherCustomerWithIdHash(fingerprint, customerId, COUNTS_AS_DUPLICATE);

        KycSubmission submission = KycSubmission.tierTwo(customerId, request.idType(), protector.encrypt(number),
                fingerprint, IdNumberProtector.last4(number), flagged, now);
        save(submission, customerId);
        decisions.save(new KycDecision(submission.getId(), customerId, DecisionType.SUBMITTED, customerId.toString(), null, now));
        if (flagged) {
            decisions.save(new KycDecision(submission.getId(), customerId, DecisionType.FLAGGED, "system",
                    "ID number matches another customer's submission", now));
            log.warn("KYC tier 2 flagged for review customerId={} submissionId={}", customerId, submission.getId());
        } else {
            log.info("KYC tier 2 submitted customerId={} submissionId={}", customerId, submission.getId());
        }
        return SubmissionResponse.from(submission);
    }

    private void rejectIfAlreadyHandled(UUID customerId, int tier) {
        if (submissions.existsByCustomerIdAndTierAndStatusIn(customerId, tier, EnumSet.of(KycStatus.VERIFIED))) {
            throw new KycConflictException("KYC_ALREADY_VERIFIED", "Tier " + tier + " is already verified");
        }
        if (submissions.existsByCustomerIdAndTierAndStatusIn(customerId, tier, OPEN)) {
            throw new KycConflictException("KYC_ALREADY_SUBMITTED", "A tier " + tier + " submission is already awaiting review");
        }
    }

    /** The database's unique indexes are the real guarantee; the checks above only give a friendlier message. */
    private void save(KycSubmission submission, UUID customerId) {
        try {
            submissions.saveAndFlush(submission);
        } catch (DataIntegrityViolationException ex) {
            throw new KycConflictException("KYC_ALREADY_SUBMITTED",
                    "A tier " + submission.getTier() + " submission already exists");
        }
    }

    private static TierStatusResponse tierStatus(List<KycSubmission> newestFirst, int tier) {
        return newestFirst.stream().filter(s -> s.getTier() == tier).findFirst()
                .map(TierStatusResponse::from).orElse(TierStatusResponse.notStarted(tier));
    }

    private static void requireCustomer(UUID customerId) {
        if (customerId == null) {
            throw new AuthenticationRequiredException();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
