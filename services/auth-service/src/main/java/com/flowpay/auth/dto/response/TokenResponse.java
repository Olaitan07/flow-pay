package com.flowpay.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A signed-in session: a short-lived access token and a single-use refresh token.")
public record TokenResponse(
        @Schema(description = "JWT (RS256). Send as 'Authorization: Bearer <token>'. Valid 15 minutes. "
                + "The 'sub' claim is the customer id.",
                example = "eyJraWQiOiJmbG93cGF5LTEiLCJhbGciOiJSUzI1NiJ9...") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(description = "Access token lifetime in seconds.", example = "900") long expiresIn,
        @Schema(description = "Opaque token valid 7 days. Exchange it at /refresh; each one works once.",
                example = "SsCed_IPmKTsFYekt7bVScwtaMIB1whQezv9bXeKxrI") String refreshToken) {

    public static TokenResponse bearer(String accessToken, long expiresInSeconds, String refreshToken) {
        return new TokenResponse(accessToken, "Bearer", expiresInSeconds, refreshToken);
    }

    @Override
    public String toString() {
        return "TokenResponse[]";
    }
}
