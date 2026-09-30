package com.flowpay.auth.service;

import com.flowpay.auth.dto.request.CreateCredentialRequest;
import com.flowpay.auth.dto.request.LoginRequest;
import com.flowpay.auth.dto.response.TokenResponse;

public interface AuthenticationService {

    TokenResponse login(LoginRequest request);

    /** @return true if created, false if identical credentials already existed (safe retry) */
    boolean createCredential(CreateCredentialRequest request);
}
