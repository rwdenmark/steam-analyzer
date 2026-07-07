package com.rwdenmark.steamanalyzer.service;

import com.rwdenmark.steamanalyzer.config.CacheConfig;
import com.rwdenmark.steamanalyzer.config.SteamProperties;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Runs the Steam client behind the real cache to prove a vanity or summary miss is
 * retried on the next call while a match is served from cache.
 */
class SteamClientCachingTest {

    private static final String BASE = "https://api.steampowered.com";
    private static final String STEAM_ID = "76561197960287930";

    @Configuration
    @EnableCaching
    static class CachingConfig {
    }

    private AnnotationConfigApplicationContext context;
    private MockRestServiceServer server;
    private SteamClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        context = new AnnotationConfigApplicationContext();
        context.register(CachingConfig.class, CacheConfig.class);
        // SteamClient's constructor qualifier expects this exact bean name.
        context.registerBean("steamApiRestClient", RestClient.class, builder::build);
        context.registerBean(SteamProperties.class, () -> new SteamProperties("TEST_KEY", BASE));
        context.registerBean(SteamClient.class);
        context.refresh();
        client = context.getBean(SteamClient.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void vanityMissIsNotCachedAndIsRetried() {
        server.expect(requestTo(containsString("ResolveVanityURL")))
                .andRespond(withSuccess("{\"response\":{\"success\":42}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("ResolveVanityURL")))
                .andRespond(withSuccess(
                        "{\"response\":{\"success\":1,\"steamid\":\"" + STEAM_ID + "\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.resolveVanity("freshname")).isEmpty();
        assertThat(client.resolveVanity("freshname")).contains(STEAM_ID);
        server.verify();
    }

    @Test
    void vanityMatchIsCached() {
        server.expect(requestTo(containsString("ResolveVanityURL")))
                .andRespond(withSuccess(
                        "{\"response\":{\"success\":1,\"steamid\":\"" + STEAM_ID + "\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.resolveVanity("gabe")).contains(STEAM_ID);
        // Must come from the cache. The mock server has no second response queued.
        assertThat(client.resolveVanity("gabe")).contains(STEAM_ID);
        server.verify();
    }

    @Test
    void summaryMissIsNotCachedAndIsRetried() {
        server.expect(requestTo(containsString("GetPlayerSummaries")))
                .andRespond(withSuccess("{\"response\":{\"players\":[]}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("GetPlayerSummaries")))
                .andRespond(withSuccess(
                        "{\"response\":{\"players\":[{\"personaname\":\"Rabscuttle\"}]}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.playerSummary(STEAM_ID)).isEmpty();
        // The miss was not cached, so the second call reaches Steam and finds the player.
        assertThat(client.playerSummary(STEAM_ID))
                .hasValueSatisfying(s -> assertThat(s.get("personaname")).isEqualTo("Rabscuttle"));
        server.verify();
    }

    @Test
    void summaryMatchIsCached() {
        server.expect(requestTo(containsString("GetPlayerSummaries")))
                .andRespond(withSuccess(
                        "{\"response\":{\"players\":[{\"personaname\":\"Rabscuttle\"}]}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.playerSummary(STEAM_ID)).isPresent();
        // Must come from the cache. The mock server has no second response queued.
        assertThat(client.playerSummary(STEAM_ID)).isPresent();
        server.verify();
    }
}
