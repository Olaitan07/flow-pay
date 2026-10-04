package com.flowpay.kyc.service;

import com.flowpay.kyc.dto.request.AuthorizeOperationRequest;
import com.flowpay.kyc.dto.response.AuthorizationDecision;
import com.flowpay.kyc.dto.response.CapabilitiesResponse;
import java.util.UUID;

/**
 * The single place that decides what a verification level allows. Other services call this before moving money,
 * so the rule applies whether the request came from an app, a browser or a direct API call.
 */
public interface KycPolicyService {

    int levelOf(UUID customerId);

    CapabilitiesResponse capabilitiesFor(int level);

    CapabilitiesResponse capabilitiesOf(UUID customerId);

    AuthorizationDecision authorize(UUID customerId, AuthorizeOperationRequest request);
}
