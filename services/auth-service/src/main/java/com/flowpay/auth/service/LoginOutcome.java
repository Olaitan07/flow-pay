package com.flowpay.auth.service;

import com.flowpay.auth.dto.response.TokenResponse;
import java.util.UUID;

/** A correct password either finishes the login or, with two-step verification on, starts the second step. */
public sealed interface LoginOutcome {

    record Authenticated(TokenResponse tokens) implements LoginOutcome {
    }

    record MfaRequired(UUID challengeId, long expiresInSeconds) implements LoginOutcome {
    }
}
