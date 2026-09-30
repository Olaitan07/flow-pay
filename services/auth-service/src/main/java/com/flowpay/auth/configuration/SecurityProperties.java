package com.flowpay.auth.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flowpay.security")
public record SecurityProperties(String internalApiKey, Jwt jwt, Login login, PasswordReset passwordReset,
                                 Otp otp) {

    public SecurityProperties {
        if (jwt == null) jwt = new Jwt(null, null, null, null, null);
        if (login == null) login = new Login(null, null);
        if (passwordReset == null) passwordReset = new PasswordReset(null, null);
        if (otp == null) otp = new Otp(null, null);
    }

    /** Keys are base64 DER: PKCS#8 private key and X.509 public key. Only auth-service holds the private key. */
    public record Jwt(String privateKey, String publicKey, String issuer, Duration accessTokenTtl,
                      Duration refreshTokenTtl) {
        public Jwt {
            if (issuer == null) issuer = "flowpay-auth";
            if (accessTokenTtl == null) accessTokenTtl = Duration.ofMinutes(15);
            if (refreshTokenTtl == null) refreshTokenTtl = Duration.ofDays(7);
        }
    }

    public record Login(Integer maxFailedAttempts, Duration lockDuration) {
        public Login {
            if (maxFailedAttempts == null) maxFailedAttempts = 5;
            if (lockDuration == null) lockDuration = Duration.ofMinutes(15);
        }
    }

    /** {@code url} is the page in the customer-facing app that receives the token from the emailed link. */
    public record PasswordReset(Duration tokenTtl, String url) {
        public PasswordReset {
            if (tokenTtl == null) tokenTtl = Duration.ofMinutes(30);
            if (url == null || url.isBlank()) url = "http://localhost:3000/reset-password";
        }
    }

    public record Otp(Duration ttl, Integer maxAttempts) {
        public Otp {
            if (ttl == null) ttl = Duration.ofMinutes(5);
            if (maxAttempts == null) maxAttempts = 5;
        }
    }
}
