package com.rwdenmark.steamanalyzer.service;

import com.rwdenmark.steamanalyzer.dto.AppDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Covers the nested appdetails parsing (keyed by appid, success flag, data object) and the
 * {@link AppDetails#unknown()} fallback that keeps a bad lookup from ever hiding a game.
 */
class SteamStoreClientTest {

    private MockRestServiceServer server;
    private SteamStoreClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://store.steampowered.com");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SteamStoreClient(builder.build());
    }

    @Test
    void parsesTypeAndFreeFlagForFreeGame() {
        server.expect(requestTo(containsString("/api/appdetails")))
                .andRespond(withSuccess(
                        "{\"570\":{\"success\":true,\"data\":{\"type\":\"game\",\"is_free\":true}}}",
                        MediaType.APPLICATION_JSON));

        AppDetails details = client.appDetails(570);

        assertThat(details.type()).isEqualTo("game");
        assertThat(details.free()).isTrue();
    }

    @Test
    void parsesPaidGame() {
        server.expect(requestTo(containsString("/api/appdetails")))
                .andRespond(withSuccess(
                        "{\"220\":{\"success\":true,\"data\":{\"type\":\"game\",\"is_free\":false}}}",
                        MediaType.APPLICATION_JSON));

        AppDetails details = client.appDetails(220);

        assertThat(details.type()).isEqualTo("game");
        assertThat(details.free()).isFalse();
    }

    @Test
    void unknownWhenStoreReportsFailure() {
        server.expect(requestTo(containsString("/api/appdetails")))
                .andRespond(withSuccess("{\"999\":{\"success\":false}}", MediaType.APPLICATION_JSON));

        assertThat(client.appDetails(999)).isEqualTo(AppDetails.unknown());
    }

    @Test
    void unknownWhenDataMissing() {
        server.expect(requestTo(containsString("/api/appdetails")))
                .andRespond(withSuccess("{\"1\":{\"success\":true}}", MediaType.APPLICATION_JSON));

        assertThat(client.appDetails(1)).isEqualTo(AppDetails.unknown());
    }

    @Test
    void unknownOnUpstreamError() {
        server.expect(requestTo(containsString("/api/appdetails")))
                .andRespond(withServerError());

        assertThat(client.appDetails(1)).isEqualTo(AppDetails.unknown());
    }
}
