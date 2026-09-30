package com.flowpay.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateCredentialRequest(
        @NotNull UUID customerId,
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password) {

    @Override
    public String toString() {
        return "CreateCredentialRequest[customerId=" + customerId + "]";
    }
}
