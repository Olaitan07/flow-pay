package com.flowpay.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** A code was emailed. Send it back with {@code challengeId} to finish. */
@Schema(description = "A one-time code was emailed. Submit it with this challengeId to continue.")
public record MfaChallengeResponse(
        @Schema(description = "Always true: the client must complete a second step.", example = "true")
        boolean mfaRequired,
        @Schema(description = "Identifies the emailed code. Send it with the code.",
                example = "9636018a-2f90-46a7-add3-2549b9841b5f") UUID challengeId,
        @Schema(description = "Seconds until the code expires.", example = "300") long expiresIn) {

    public static MfaChallengeResponse of(UUID challengeId, long expiresInSeconds) {
        return new MfaChallengeResponse(true, challengeId, expiresInSeconds);
    }
}
