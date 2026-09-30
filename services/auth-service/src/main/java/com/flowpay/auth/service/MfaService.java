package com.flowpay.auth.service;

import com.flowpay.auth.dto.response.TokenResponse;
import com.flowpay.auth.entity.Credential;
import java.util.UUID;

public interface MfaService {

    /** Emails a login code. Called after the password was already checked. */
    LoginOutcome.MfaRequired startLoginChallenge(Credential credential);

    TokenResponse completeLogin(UUID challengeId, String code);

    LoginOutcome.MfaRequired requestEnable(UUID customerId);

    void confirmEnable(UUID customerId, UUID challengeId, String code);

    void disable(UUID customerId, String password);
}
