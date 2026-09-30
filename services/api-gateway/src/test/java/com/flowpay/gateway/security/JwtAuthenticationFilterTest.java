package com.flowpay.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.http.HttpServletRequest;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtAuthenticationFilterTest {

    static KeyPair trusted;
    static KeyPair attacker;
    static JwtAuthenticationFilter filter;

    @BeforeAll
    static void createKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        trusted = generator.generateKeyPair();
        attacker = generator.generateKeyPair();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) trusted.getPublic()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("flowpay-auth"));
        filter = new JwtAuthenticationFilter(decoder);
    }

    static String token(KeyPair signWith, String issuer, String subject, Duration validFor) {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) signWith.getPublic())
                .privateKey((RSAPrivateKey) signWith.getPrivate()).keyID("k").build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        Instant issuedAt = Instant.now().minus(Duration.ofHours(1));
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(subject).issuedAt(issuedAt)
                .expiresAt(Instant.now().plus(validFor)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    record Outcome(int status, String customerIdSeenDownstream, boolean reachedService) {
    }

    static Outcome call(String method, String path, String authorization, String spoofedId) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        if (authorization != null) request.addHeader("Authorization", authorization);
        if (spoofedId != null) request.addHeader("X-Authenticated-Customer-Id", spoofedId);
        var response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> downstream.set((HttpServletRequest) req));
        HttpServletRequest seen = downstream.get();
        return new Outcome(response.getStatus(), seen == null ? null : seen.getHeader("X-Authenticated-Customer-Id"),
                seen != null);
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void validToken_passesThrough_andDownstreamSeesTheCustomerIdFromTheToken() throws Exception {
        var outcome = call("GET", "/api/v1/users/abc",
                bearer(token(trusted, "flowpay-auth", "customer-1", Duration.ofMinutes(5))), null);

        assertThat(outcome.reachedService()).isTrue();
        assertThat(outcome.customerIdSeenDownstream()).isEqualTo("customer-1");
    }

    @Test
    void spoofedIdentityHeaderIsReplacedByTheTokensIdentity() throws Exception {
        var outcome = call("GET", "/api/v1/users/abc",
                bearer(token(trusted, "flowpay-auth", "customer-1", Duration.ofMinutes(5))), "someone-else");

        assertThat(outcome.customerIdSeenDownstream()).isEqualTo("customer-1");
    }

    @Test
    void protectedEndpointWithoutToken_is401_andNeverReachesTheService() throws Exception {
        var outcome = call("GET", "/api/v1/users/abc", null, "someone-else");

        assertThat(outcome.status()).isEqualTo(401);
        assertThat(outcome.reachedService()).isFalse();
    }

    @Test
    void forgedExpiredAndWrongIssuerTokensAreRejected() throws Exception {
        assertThat(call("GET", "/api/v1/users/abc",
                bearer(token(attacker, "flowpay-auth", "customer-1", Duration.ofMinutes(5))), null).status())
                .as("signed with another key").isEqualTo(401);
        assertThat(call("GET", "/api/v1/users/abc",
                bearer(token(trusted, "flowpay-auth", "customer-1", Duration.ofSeconds(-120))), null).status())
                .as("expired").isEqualTo(401);
        assertThat(call("GET", "/api/v1/users/abc",
                bearer(token(trusted, "someone-else", "customer-1", Duration.ofMinutes(5))), null).status())
                .as("wrong issuer").isEqualTo(401);
        assertThat(call("GET", "/api/v1/users/abc", bearer("garbage"), null).status())
                .as("garbage").isEqualTo(401);
        assertThat(call("GET", "/api/v1/users/abc", "Basic dXNlcjpwYXNz", null).status())
                .as("not a bearer token").isEqualTo(401);
    }

    @Test
    void unsignedTokenIsRejected() throws Exception {
        String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        String body = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"iss\":\"flowpay-auth\",\"sub\":\"customer-1\",\"exp\":"
                        + (Instant.now().getEpochSecond() + 300) + "}").getBytes());

        assertThat(call("GET", "/api/v1/users/abc", bearer(header + "." + body + "."), null).status())
                .isEqualTo(401);
    }

    @Test
    void publicEndpointsNeedNoToken_butClientIdentityHeaderIsStillStripped() throws Exception {
        for (String[] endpoint : new String[][] {{"POST", "/api/v1/users"}, {"POST", "/api/v1/auth/login"},
                {"POST", "/api/v1/auth/refresh"}, {"POST", "/api/v1/auth/logout"}, {"GET", "/actuator/health"}}) {
            var outcome = call(endpoint[0], endpoint[1], null, "someone-else");

            assertThat(outcome.reachedService()).as(endpoint[1]).isTrue();
            assertThat(outcome.customerIdSeenDownstream()).as(endpoint[1]).isNull();
        }
    }

    @Test
    void otherMethodsOnPublicPathsStayProtected() throws Exception {
        assertThat(call("GET", "/api/v1/users", null, null).status()).isEqualTo(401);
        assertThat(call("PATCH", "/api/v1/users/abc", null, null).status()).isEqualTo(401);
        assertThat(call("POST", "/api/v1/auth/logout-all", null, null).status()).isEqualTo(401);
        assertThat(call("POST", "/internal/credentials", null, null).status()).isEqualTo(401);
    }
}
