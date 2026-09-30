package com.flowpay.auth.configuration;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class JwtConfiguration {

    @Bean
    JwtEncoder jwtEncoder(SecurityProperties properties) throws Exception {
        var jwt = properties.jwt();
        if (jwt.privateKey() == null || jwt.privateKey().isBlank()
                || jwt.publicKey() == null || jwt.publicKey().isBlank()) {
            throw new IllegalStateException("JWT_PRIVATE_KEY and JWT_PUBLIC_KEY must be configured");
        }
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        RSAPrivateKey privateKey = (RSAPrivateKey) keyFactory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(jwt.privateKey().trim())));
        RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(jwt.publicKey().trim())));
        RSAKey rsaKey = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("flowpay-1").build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
