package com.flowpay.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Asks for a password reset link to be emailed.")
public record PasswordResetRequest(
        @Schema(description = "The account email. The response is identical whether or not it exists.",
                example = "ada.obi@example.com")
        @NotBlank @Size(max = 254) String email) {
}
