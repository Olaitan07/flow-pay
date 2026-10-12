package com.flowpay.wallet.configuration;

import com.flowpay.wallet.client.KycClient;
import com.flowpay.wallet.security.InternalApiKeyFilter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(WalletProperties.class)
public class WalletConfiguration {

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
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    KycClient kycClient(WalletProperties properties, @Value("${flowpay.security.internal-api-key}") String internalApiKey) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.kyc().connectTimeout());
        factory.setReadTimeout(properties.kyc().readTimeout());
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.kyc().url())
                .requestFactory(factory)
                .defaultHeader(InternalApiKeyFilter.HEADER, internalApiKey)
                .build();
        return new KycClient(restClient);
    }
}
