package com.flowpay.auth.dto.response;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken) {

    public static TokenResponse bearer(String accessToken, long expiresInSeconds, String refreshToken) {
        return new TokenResponse(accessToken, "Bearer", expiresInSeconds, refreshToken);
    }

    @Override
    public String toString() {
        return "TokenResponse[]";
    }
}
