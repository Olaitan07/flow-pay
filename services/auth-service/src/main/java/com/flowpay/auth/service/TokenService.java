package com.flowpay.auth.service;

import com.flowpay.auth.dto.response.TokenResponse;
import java.util.UUID;

public interface TokenService {

    /** Starts a new session (a new refresh-token family). */
    TokenResponse issueNewSession(UUID customerId);

    /** Exchanges a refresh token for a new pair; the used token can never be used again. */
    TokenResponse refresh(String refreshToken);

    /** Ends the session the refresh token belongs to. Unknown tokens are ignored. */
    void revokeSession(String refreshToken);

    void revokeAllSessions(UUID customerId);
}
