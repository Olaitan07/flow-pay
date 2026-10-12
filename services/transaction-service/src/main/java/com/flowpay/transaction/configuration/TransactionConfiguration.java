package com.flowpay.transaction.configuration;

import com.flowpay.transaction.client.KycClient;
import com.flowpay.transaction.client.WalletClient;
import com.flowpay.transaction.security.InternalApiKeyFilter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(TransactionProperties.class)
public class TransactionConfiguration {

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
    WalletClient walletClient(TransactionProperties properties, @Value("${flowpay.security.internal-api-key}") String key) {
        return new WalletClient(restClient(properties.wallet(), key));
    }

    @Bean
    KycClient kycClient(TransactionProperties properties, @Value("${flowpay.security.internal-api-key}") String key) {
        return new KycClient(restClient(properties.kyc(), key));
    }

    private static RestClient restClient(TransactionProperties.Service service, String internalApiKey) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(service.connectTimeout());
        factory.setReadTimeout(service.readTimeout());
        return RestClient.builder().baseUrl(service.url()).requestFactory(factory)
                .defaultHeader(InternalApiKeyFilter.HEADER, internalApiKey).build();
    }
}
