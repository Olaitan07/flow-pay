package com.flowpay.gateway.security;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfiguration {

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) throws Exception {
        if (properties.publicKey() == null || properties.publicKey().isBlank()) {
            throw new IllegalStateException("JWT_PUBLIC_KEY must be configured");
        }
        RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(properties.publicKey().trim())));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        // Default validators check expiry and not-before; the issuer check rejects tokens minted by anyone else.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilter(JwtDecoder decoder) {
        var registration = new FilterRegistrationBean<>(new JwtAuthenticationFilter(decoder));
        // After the rate limiters, so floods of bad tokens on login/registration are throttled first.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
