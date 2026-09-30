package com.flowpay.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmRequest(
        @NotBlank @Size(max = 200) String token,
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
