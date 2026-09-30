package com.flowpay.auth.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flowpay.security")
public record SecurityProperties(String internalApiKey, Jwt jwt, Login login) {

    public SecurityProperties {
        if (jwt == null) jwt = new Jwt(null, null, null, null, null);
        if (login == null) login = new Login(null, null);
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
}
