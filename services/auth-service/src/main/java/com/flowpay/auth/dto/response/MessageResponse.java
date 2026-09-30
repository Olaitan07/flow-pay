package com.flowpay.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A plain confirmation message.")
public record MessageResponse(
        @Schema(example = "If an account exists for that email, a reset link has been sent.") String message) {
}
