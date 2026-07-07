package com.rwdenmark.steamanalyzer.service;

import com.rwdenmark.steamanalyzer.config.CacheConfig;
import com.rwdenmark.steamanalyzer.dto.AppDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Runs the store client behind the real cache to prove a failed lookup is retried on the
 * next call while a successful one is served from cache.
 */
class SteamStoreClientCachingTest {

    @Configuration
    @EnableCaching
    static class CachingConfig {
    }

    private AnnotationConfigApplicationContext context;
    private MockRestServiceServer server;
    private SteamStoreClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://store.steampowered.com");
        server = MockRestServiceServer.bindTo(builder).build();
        context = new AnnotationConfigApplicationContext();
        context.register(CachingConfig.class, CacheConfig.class);
        // SteamStoreClient's constructor qualifier expects this exact bean name.
        context.registerBean("steamStoreRestClient", RestClient.class, builder::build);
        context.registerBean(SteamStoreClient.class);
        context.refresh();
        client = context.getBean(SteamStoreClient.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void failedLookupIsNotCachedAndIsRetried() {
        server.expect(requestTo(containsString("/api/appdetails")))
                .andRespond(withServerError());
        server.expect(requestTo(containsString("/api/appdetails")))
                .andRespond(withSuccess(
                        "{\"570\":{\"success\":true,\"data\":{\"type\":\"game\",\"is_free\":true}}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.appDetails(570)).isEqualTo(AppDetails.unknown());
        assertThat(client.appDetails(570)).isEqualTo(new AppDetails("game", true));
        server.verify();
    }

    @Test
    void successfulLookupIsCached() {
        server.expect(requestTo(containsString("/api/appdetails")))
                .andRespond(withSuccess(
                        "{\"220\":{\"success\":true,\"data\":{\"type\":\"game\",\"is_free\":false}}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.appDetails(220)).isEqualTo(new AppDetails("game", false));
        // Must come from the cache. The mock server has no second response queued.
        assertThat(client.appDetails(220)).isEqualTo(new AppDetails("game", false));
        server.verify();
    }
}
