package com.flowpay.kyc.service.impl;

import com.flowpay.kyc.dto.request.ReviewDecisionRequest;
import com.flowpay.kyc.dto.response.DecisionResponse;
import com.flowpay.kyc.dto.response.ReviewDetailResponse;
import com.flowpay.kyc.dto.response.ReviewQueueItem;
import com.flowpay.kyc.entity.DecisionType;
import com.flowpay.kyc.entity.KycDecision;
import com.flowpay.kyc.entity.KycStatus;
import com.flowpay.kyc.entity.KycSubmission;
import com.flowpay.kyc.exception.InvalidKycRequestException;
import com.flowpay.kyc.exception.KycConflictException;
import com.flowpay.kyc.exception.KycNotFoundException;
import com.flowpay.kyc.outbox.KycEventRecorder;
import com.flowpay.kyc.repository.KycDecisionRepository;
import com.flowpay.kyc.repository.KycSubmissionRepository;
import com.flowpay.kyc.service.IdNumberProtector;
import com.flowpay.kyc.service.KycPolicyService;
import com.flowpay.kyc.service.KycReviewService;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KycReviewServiceImpl implements KycReviewService {

    private static final Logger log = LoggerFactory.getLogger(KycReviewServiceImpl.class);
    private static final EnumSet<KycStatus> AWAITING = EnumSet.of(KycStatus.PENDING, KycStatus.REQUIRES_REVIEW);

    private final KycSubmissionRepository submissions;
    private final KycDecisionRepository decisions;
    private final KycPolicyService policy;
    private final IdNumberProtector protector;
    private final KycEventRecorder events;
    private final Clock clock;

    public KycReviewServiceImpl(KycSubmissionRepository submissions, KycDecisionRepository decisions,
                                KycPolicyService policy, IdNumberProtector protector, KycEventRecorder events, Clock clock) {
        this.submissions = submissions;
        this.decisions = decisions;
        this.policy = policy;
        this.protector = protector;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewQueueItem> queue(int page, int size) {
        return submissions.findByStatusInOrderBySubmittedAtAsc(AWAITING, PageRequest.of(page, size)).stream()
                .map(ReviewQueueItem::from).toList();
    }

    /** Reading the full ID number is itself recorded: who looked, and when. */
    @Override
    @Transactional
    public ReviewDetailResponse detail(UUID submissionId, String reviewer) {
        KycSubmission s = find(submissionId);
        decisions.save(new KycDecision(s.getId(), s.getCustomerId(), DecisionType.SENSITIVE_DATA_VIEWED, reviewer, null,
                clock.instant()));
        log.info("KYC detail viewed submissionId={} reviewer={}", s.getId(), reviewer);

        List<ReviewDetailResponse.HistoryEntry> history = decisions.findBySubmissionIdOrderByCreatedAtAsc(s.getId()).stream()
                .map(d -> new ReviewDetailResponse.HistoryEntry(d.getType().name(), d.getActor(), d.getReason(), d.getCreatedAt()))
                .toList();
        String idNumber = s.getIdNumberEncrypted() == null ? null : protector.decrypt(s.getIdNumberEncrypted());
        return new ReviewDetailResponse(s.getId(), s.getCustomerId(), s.getTier(), s.getStatus(), s.getSubmittedAt(),
                s.getDecidedAt(), s.getDecidedBy(), s.getDecisionReason(), s.getDateOfBirth(), s.getAddressLine1(),
                s.getAddressLine2(), s.getCity(), s.getState(), s.getPostalCode(), s.getAddressCountry(), s.getIdType(),
                idNumber, history);
    }

    /**
     * The decision, its audit line and (on approval) the event for other services are saved in ONE transaction,
     * so none can exist without the others.
     */
    @Override
    @Transactional
    public DecisionResponse decide(UUID submissionId, String reviewer, ReviewDecisionRequest request) {
        boolean approve = request.decision() == ReviewDecisionRequest.Decision.APPROVE;
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().trim();
        if (!approve && reason == null) {
            throw new InvalidKycRequestException("VALIDATION_ERROR", "A reason is required when rejecting");
        }

        KycSubmission s = find(submissionId);
        Instant now = clock.instant();
        KycStatus outcome = approve ? KycStatus.VERIFIED : KycStatus.REJECTED;
        // Conditional update: if two reviewers act at once, exactly one gets 1 here.
        if (submissions.decide(submissionId, outcome, reviewer, reason, now, AWAITING) != 1) {
            throw new KycConflictException("KYC_NOT_REVIEWABLE", "This submission has already been decided");
        }

        decisions.save(new KycDecision(submissionId, s.getCustomerId(),
                approve ? DecisionType.APPROVED : DecisionType.REJECTED, reviewer, reason, now));
        int level = policy.levelOf(s.getCustomerId());
        if (approve) {
            events.levelChanged(s.getCustomerId(), level, now);
        }
        log.info("KYC decision submissionId={} decision={} reviewer={} customerLevel={}", submissionId, outcome, reviewer, level);
        return new DecisionResponse(submissionId, outcome, level);
    }

    private KycSubmission find(UUID id) {
        return submissions.findById(id)
                .orElseThrow(() -> new KycNotFoundException("KYC_SUBMISSION_NOT_FOUND", "Submission not found"));
    }
}
