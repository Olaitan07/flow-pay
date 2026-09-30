package com.flowpay.user.service.impl;

import com.flowpay.user.configuration.AuthServiceProperties;
import com.flowpay.user.exception.DuplicateCustomerException;
import com.flowpay.user.exception.RegistrationUnavailableException;
import com.flowpay.user.service.CredentialClient;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@EnableConfigurationProperties(AuthServiceProperties.class)
public class RestCredentialClient implements CredentialClient {

    private final RestClient restClient;

    public RestCredentialClient(AuthServiceProperties properties,
                                @Value("${flowpay.security.internal-api-key}") String internalApiKey) {
        // Timeouts matter: without them a slow auth-service would hold registrations (and DB connections) forever.
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.url())
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Api-Key", internalApiKey)
                .build();
    }

    @Override
    public void createCredentials(UUID customerId, String email, String password) {
        try {
            restClient.post().uri("/internal/credentials")
                    .body(Map.of("customerId", customerId.toString(), "email", email, "password", password))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                throw new DuplicateCustomerException("A customer with this email already exists");
            }
            throw new RegistrationUnavailableException("auth-service rejected credential creation: "
                    + ex.getStatusCode(), ex);
        } catch (RestClientException ex) {
            throw new RegistrationUnavailableException("auth-service is unreachable", ex);
        }
    }
}
