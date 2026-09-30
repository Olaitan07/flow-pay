package com.flowpay.auth.service;

import com.flowpay.auth.dto.request.CreateCredentialRequest;
import com.flowpay.auth.dto.request.LoginRequest;

public interface AuthenticationService {

    LoginOutcome login(LoginRequest request);

    /** @return true if created, false if identical credentials already existed (safe retry) */
    boolean createCredential(CreateCredentialRequest request);
}
