package com.flowpay.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

@Schema(description = "The emailed one-time code together with the challenge it belongs to.")
public record VerifyOtpRequest(
        @Schema(description = "The challengeId returned when the code was requested.",
                example = "9636018a-2f90-46a7-add3-2549b9841b5f")
        @NotNull UUID challengeId,
        @Schema(description = "The 6-digit code from the email. Valid 5 minutes, single use, 5 attempts.",
                example = "904826")
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be a 6-digit code") String code) {

    @Override
    public String toString() {
        return "VerifyOtpRequest[challengeId=" + challengeId + "]";
    }
}
