package com.flowpay.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Details needed to open a FlowPay account.")
public record RegisterCustomerRequest(
        @Schema(description = "Given name.", example = "Ada")
        @NotBlank @Size(max = 100) String firstName,
        @Schema(description = "Family name.", example = "Obi")
        @NotBlank @Size(max = 100) String lastName,
        @Schema(description = "Email address. Stored in lowercase and must be unique. Used to log in.",
                example = "ada.obi@example.com")
        @NotBlank @Email @Size(max = 254) String email,
        @Schema(description = "Phone number in international format (E.164). Must be unique.",
                example = "+2348012345678")
        @NotBlank
        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be in international format, e.g. +2348012345678")
        String phoneNumber,
        @Schema(description = "8-72 characters with at least one uppercase letter, one lowercase letter and one digit. "
                + "Hashed by auth-service; never stored or returned by user-service.",
                example = "Sup3rSecret", format = "password")
        @NotBlank
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$",
                message = "must contain an uppercase letter, a lowercase letter and a digit")
        String password,
        @Schema(description = "Country as an ISO 3166-1 alpha-2 code.", example = "NG")
        @NotBlank
        @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO 3166-1 alpha-2 code, e.g. NG")
        String country) {

    /** Keeps the password out of logs and exception messages. */
    @Override
    public String toString() {
        return "RegisterCustomerRequest[email=" + email + "]";
    }
}
