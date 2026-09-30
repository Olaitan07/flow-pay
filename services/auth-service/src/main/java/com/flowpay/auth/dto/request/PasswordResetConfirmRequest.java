package com.flowpay.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "The token from the reset email plus the new password.")
public record PasswordResetConfirmRequest(
        @Schema(description = "The value of ?token= in the emailed link. Single use, valid 30 minutes.",
                example = "SsCed_IPmKTsFYekt7bVScwtaMIB1whQezv9bXeKxrI")
        @NotBlank @Size(max = 200) String token,
        @Schema(description = "8-72 characters with an uppercase letter, a lowercase letter and a digit.",
                example = "Brand9NewPass", format = "password")
        @NotBlank
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$",
                message = "must contain an uppercase letter, a lowercase letter and a digit")
        String newPassword) {

    @Override
    public String toString() {
        return "PasswordResetConfirmRequest[]";
    }
}
