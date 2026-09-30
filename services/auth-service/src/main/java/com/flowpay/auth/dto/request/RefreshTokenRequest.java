package com.flowpay.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "A refresh token previously issued by login, login/verify or refresh.")
public record RefreshTokenRequest(
        @Schema(description = "The opaque refresh token. Each one works only once.",
                example = "SsCed_IPmKTsFYekt7bVScwtaMIB1whQezv9bXeKxrI")
        @NotBlank @Size(max = 200) String refreshToken) {

    @Override
    public String toString() {
        return "RefreshTokenRequest[]";
    }
}
