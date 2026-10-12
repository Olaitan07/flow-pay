package com.flowpay.wallet.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// The examples are identical in every service so the merged API docs have a single ErrorResponse.
@Schema(description = "Standard error body used by every endpoint.")
public record ErrorResponse(
        @Schema(example = "2026-09-30T16:13:30.942Z") Instant timestamp,
        @Schema(description = "HTTP status code.", example = "400") int status,
        @Schema(description = "Stable machine-readable code to branch on.", example = "VALIDATION_ERROR") String error,
        @Schema(description = "Human-readable explanation.", example = "email must be a well-formed email address") String message,
        @Schema(description = "The request path.", example = "/api/v1/users") String path) {
}
