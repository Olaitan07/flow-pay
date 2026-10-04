package com.flowpay.kyc.service;

import com.flowpay.kyc.dto.request.SubmitTierOneRequest;
import com.flowpay.kyc.dto.request.SubmitTierTwoRequest;
import com.flowpay.kyc.dto.response.KycStatusResponse;
import com.flowpay.kyc.dto.response.SubmissionResponse;
import java.util.UUID;

/** What a customer can do with their own verification. */
public interface KycSubmissionService {

    KycStatusResponse getStatus(UUID customerId);

    SubmissionResponse submitTierOne(UUID customerId, SubmitTierOneRequest request);

    SubmissionResponse submitTierTwo(UUID customerId, SubmitTierTwoRequest request);
}
