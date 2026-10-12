package com.flowpay.kyc.service;

import com.flowpay.kyc.dto.request.ReviewDecisionRequest;
import com.flowpay.kyc.dto.response.DecisionResponse;
import com.flowpay.kyc.dto.response.ReviewDetailResponse;
import com.flowpay.kyc.dto.response.ReviewQueueItem;
import java.util.List;
import java.util.UUID;

/** What a human reviewer can do. Only reachable with the internal API key. */
public interface KycReviewService {

    List<ReviewQueueItem> queue(int page, int size);

    ReviewDetailResponse detail(UUID submissionId, String reviewer);

    DecisionResponse decide(UUID submissionId, String reviewer, ReviewDecisionRequest request);
}
