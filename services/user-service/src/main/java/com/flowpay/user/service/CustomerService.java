package com.flowpay.user.service;

import com.flowpay.user.dto.request.RegisterCustomerRequest;
import com.flowpay.user.dto.request.UpdateCustomerProfileRequest;
import com.flowpay.user.dto.response.CustomerProfileResponse;
import com.flowpay.user.dto.response.CustomerResponse;
import java.util.UUID;

public interface CustomerService {

    CustomerResponse registerCustomer(RegisterCustomerRequest request);

    /** @param requesterId the authenticated customer making the call; null when unauthenticated */
    CustomerProfileResponse viewProfile(UUID requesterId, UUID customerId);

    CustomerProfileResponse updateProfile(UUID requesterId, UUID customerId, UpdateCustomerProfileRequest request);
}
