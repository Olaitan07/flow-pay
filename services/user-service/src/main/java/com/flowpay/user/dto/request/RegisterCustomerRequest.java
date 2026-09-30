package com.flowpay.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterCustomerRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank
        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be in international format, e.g. +2348012345678")
        String phoneNumber,
        @NotBlank
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$",
                message = "must contain an uppercase letter, a lowercase letter and a digit")
        String password,
        @NotBlank
        @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO 3166-1 alpha-2 code, e.g. NG")
        String country) {

    /** Keeps the password out of logs and exception messages. */
    @Override
    public String toString() {
        return "RegisterCustomerRequest[email=" + email + "]";
    }
}
