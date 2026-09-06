package com.example.amaltea.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The HTTP clients AMALTEA uses to reach the systems it depends on.
 * <p>
 * Two separate clients, because their needs are opposite: an analysis takes minutes and
 * must be allowed to, whereas a registry lookup that hangs for minutes is a fault. A
 * single shared client would force one of the two into the wrong budget.
 */
@Configuration
public class RestClientConfig {

    /** Reaches the system under test. Long read budget: an analysis takes minutes. */
    @Bean
    public RestClient capraRestClient(AmalteaProperties properties) {
        AmalteaProperties.Capra capra = properties.capra();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(capra.runTimeoutSeconds()));

        return RestClient.builder()
                .baseUrl(capra.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Reaches the model registry. Short budget: these are metadata lookups, and the
     * catalogue build issues a few hundred of them.
     */
    @Bean
    public RestClient registryRestClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(30));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
