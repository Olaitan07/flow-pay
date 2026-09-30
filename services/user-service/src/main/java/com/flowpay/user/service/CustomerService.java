package com.flowpay.user.service;

import com.flowpay.user.dto.request.RegisterCustomerRequest;
import com.flowpay.user.dto.response.CustomerResponse;

public interface CustomerService {

    CustomerResponse registerCustomer(RegisterCustomerRequest request);
}
