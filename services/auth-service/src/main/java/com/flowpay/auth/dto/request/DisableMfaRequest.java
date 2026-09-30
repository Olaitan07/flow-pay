package com.flowpay.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DisableMfaRequest(@NotBlank @Size(max = 128) String password) {

    @Override
    public String toString() {
        return "DisableMfaRequest[]";
    }
}
