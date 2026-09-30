package com.flowpay.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The gateway holds only the PUBLIC key (base64 X.509 DER): it can verify tokens but never create them. */
@ConfigurationProperties(prefix = "flowpay.security.jwt")
public record JwtProperties(String publicKey, String issuer) {

    public JwtProperties {
        if (issuer == null) issuer = "flowpay-auth";
    }
}
