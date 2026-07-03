package com.rwdenmark.steamanalyzer.service;

import com.rwdenmark.steamanalyzer.config.SteamProperties;
import com.rwdenmark.steamanalyzer.error.SteamUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Drives real Steam-shaped JSON through the cast-heavy map parsing with a mock transport, so the
 * envelope unwrapping, vanity success codes, and timeout/error mapping are covered without a network.
 */
class SteamClientTest {

    private static final String BASE = "https://api.steampowered.com";

    private MockRestServiceServer server;
    private SteamClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SteamClient(builder.build(), new SteamProperties("TEST_KEY", BASE));
    }

    @Test
    void resolveVanityReturnsSteamIdOnMatch() {
        server.expect(requestTo(containsString("/ISteamUser/ResolveVanityURL/v1/")))
                .andExpect(queryParam("vanityurl", "gabe"))
                .andRespond(withSuccess(
                        "{\"response\":{\"success\":1,\"steamid\":\"76561197960287930\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.resolveVanity("gabe")).contains("76561197960287930");
        server.verify();
    }

    @Test
    void resolveVanityIsEmptyOnNoMatch() {
        server.expect(requestTo(containsString("ResolveVanityURL")))
                .andRespond(withSuccess("{\"response\":{\"success\":42}}", MediaType.APPLICATION_JSON));

        assertThat(client.resolveVanity("ghost")).isEmpty();
    }

    @Test
    void playerSummaryReturnsFirstPlayer() {
        server.expect(requestTo(containsString("GetPlayerSummaries")))
                .andRespond(withSuccess(
                        "{\"response\":{\"players\":[{\"personaname\":\"Gabe\",\"avatarfull\":\"a.jpg\"}]}}",
                        MediaType.APPLICATION_JSON));

        Optional<Map<String, Object>> result = client.playerSummary("76561197960287930");

        assertThat(result).isPresent();
        assertThat(result.get()).containsEntry("personaname", "Gabe");
    }

    @Test
    void playerSummaryIsEmptyWhenNoPlayers() {
        server.expect(requestTo(containsString("GetPlayerSummaries")))
                .andRespond(withSuccess("{\"response\":{\"players\":[]}}", MediaType.APPLICATION_JSON));

        assertThat(client.playerSummary("1")).isEmpty();
    }

    @Test
    void ownedGamesReturnsTheResponseObject() {
        server.expect(requestTo(containsString("GetOwnedGames")))
                .andRespond(withSuccess(
                        "{\"response\":{\"game_count\":1,\"games\":"
                                + "[{\"appid\":220,\"name\":\"Half-Life 2\",\"playtime_forever\":600}]}}",
                        MediaType.APPLICATION_JSON));

        Map<String, Object> owned = client.ownedGames("1");

        assertThat(owned).containsKey("game_count");
        assertThat(owned).containsKey("games");
    }

    @Test
    void missingResponseEnvelopeBecomesSteamUnavailable() {
        server.expect(requestTo(containsString("GetOwnedGames")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.ownedGames("1"))
                .isInstanceOf(SteamUnavailableException.class);
    }

    @Test
    void presentButEmptyResponseObjectReturnsEmptyMap() {
        // This is what Steam sends for a private profile. It must stay distinguishable
        // from a missing envelope, which means Steam itself is broken.
        server.expect(requestTo(containsString("GetOwnedGames")))
                .andRespond(withSuccess("{\"response\":{}}", MediaType.APPLICATION_JSON));

        assertThat(client.ownedGames("1")).isEmpty();
    }

    @Test
    void upstreamErrorBecomesSteamUnavailable() {
        server.expect(requestTo(containsString("GetPlayerSummaries")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.playerSummary("1"))
                .isInstanceOf(SteamUnavailableException.class);
    }

    @Test
    void timeoutBecomesSteamUnavailable() {
        server.expect(requestTo(containsString("GetPlayerSummaries")))
                .andRespond(withException(new SocketTimeoutException("timed out")));

        assertThatThrownBy(() -> client.playerSummary("1"))
                .isInstanceOf(SteamUnavailableException.class);
    }

    @Test
    void rateLimitBecomesSteamUnavailable() {
        server.expect(requestTo(containsString("GetPlayerSummaries")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.playerSummary("1"))
                .isInstanceOf(SteamUnavailableException.class);
    }

    @Test
    void clientErrorIsNotTreatedAsUnavailable() {
        server.expect(requestTo(containsString("GetPlayerSummaries")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.playerSummary("1"))
                .isInstanceOf(RestClientResponseException.class)
                .isNotInstanceOf(SteamUnavailableException.class);
    }
}
