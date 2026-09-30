package com.flowpay.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Credentials used to sign in.")
public record LoginRequest(
        @Schema(description = "The email used at registration (case-insensitive).", example = "ada.obi@example.com")
        @NotBlank @Size(max = 254) String email,
        @Schema(description = "The account password.", example = "Sup3rSecret", format = "password")
        @NotBlank @Size(max = 128) String password) {

    @Override
    public String toString() {
        return "LoginRequest[]";
    }
}
