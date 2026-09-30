package com.flowpay.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Confirms the account owner before two-step verification is turned off.")
public record DisableMfaRequest(
        @Schema(description = "The current account password.", example = "Sup3rSecret", format = "password")
        @NotBlank @Size(max = 128) String password) {

    @Override
    public String toString() {
        return "DisableMfaRequest[]";
    }
}
