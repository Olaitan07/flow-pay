package com.flowpay.auth.service.impl;

import com.flowpay.auth.configuration.NotificationServiceProperties;
import com.flowpay.auth.exception.NotificationFailedException;
import com.flowpay.auth.service.NotificationClient;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestNotificationClient implements NotificationClient {

    private final RestClient restClient;

    public RestNotificationClient(NotificationServiceProperties properties,
                                  @Value("${flowpay.security.internal-api-key}") String internalApiKey) {
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
    public void send(Type type, String recipientEmail, Map<String, String> variables) {
        try {
            restClient.post().uri("/internal/notifications/email")
                    .body(Map.of("type", type.name(), "recipient", recipientEmail, "variables", variables))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            // Deliberately not including the request: it holds a code or link.
            throw new NotificationFailedException("notification-service call failed for " + type, ex);
        }
    }
}
