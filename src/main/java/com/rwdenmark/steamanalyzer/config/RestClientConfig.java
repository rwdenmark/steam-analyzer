package com.rwdenmark.steamanalyzer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    /** Steam Web API client. Short timeouts keep a slow Steam from hanging a request thread. */
    @Bean
    public RestClient steamApiClient(SteamProperties props) {
        return RestClient.builder()
                .requestFactory(timeoutFactory())
                .baseUrl(props.apiBaseUrl())
                .build();
    }

    /** Public store appdetails client, separate base URL from the Web API. */
    @Bean
    public RestClient steamStoreRestClient() {
        return RestClient.builder()
                .requestFactory(timeoutFactory())
                .baseUrl("https://store.steampowered.com")
                .build();
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }
}
