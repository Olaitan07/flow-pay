package com.flowpay.auth.dto.response;

import java.util.UUID;

/** A code was emailed. Send it back with {@code challengeId} to finish. */
public record MfaChallengeResponse(boolean mfaRequired, UUID challengeId, long expiresIn) {

    public static MfaChallengeResponse of(UUID challengeId, long expiresInSeconds) {
        return new MfaChallengeResponse(true, challengeId, expiresInSeconds);
    }
}
