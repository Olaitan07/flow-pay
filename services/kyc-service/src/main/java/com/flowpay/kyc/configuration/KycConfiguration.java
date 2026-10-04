package com.flowpay.kyc.configuration;

import com.flowpay.kyc.security.InternalApiKeyFilter;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(KycProperties.class)
public class KycConfiguration {

    public static final String EVENTS_TOPIC = "kyc.events";

    @Bean
    FilterRegistrationBean<InternalApiKeyFilter> internalApiKeyFilter(
            @Value("${flowpay.security.internal-api-key}") String internalApiKey) {
        var registration = new FilterRegistrationBean<>(new InternalApiKeyFilter(internalApiKey));
        registration.addUrlPatterns("/internal/*");
        return registration;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    NewTopic kycEventsTopic() {
        return TopicBuilder.name(EVENTS_TOPIC).partitions(1).replicas(1).build();
    }
}
