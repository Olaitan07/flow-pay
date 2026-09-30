package com.flowpay.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(description = "Internal: login credentials for a newly registered customer.")
public record CreateCredentialRequest(
        @Schema(description = "The customer id created by user-service.",
                example = "6fbf8203-6ee0-4b39-aa85-d721d712480c")
        @NotNull UUID customerId,
        @Schema(example = "ada.obi@example.com") @NotBlank @Size(max = 254) String email,
        @Schema(description = "Plain password; auth-service hashes it with BCrypt.", example = "Sup3rSecret",
                format = "password")
        @NotBlank @Size(min = 8, max = 72) String password) {

    @Override
    public String toString() {
        return "CreateCredentialRequest[customerId=" + customerId + "]";
    }
}
