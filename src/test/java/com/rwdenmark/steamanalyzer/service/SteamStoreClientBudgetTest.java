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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Covers the cross-request appdetails budget. No cache here, so every call reaches the
 * method body. Time is driven through the nowMillis seam so no test sleeps.
 */
class SteamStoreClientBudgetTest {

    /** Client on a hand-cranked clock. */
    private static class FixedClockClient extends SteamStoreClient {
        long now = 1_000;

        FixedClockClient(RestClient restClient) {
            super(restClient);
        }

        @Override
        protected long nowMillis() {
            return now;
        }
    }

    private MockRestServiceServer server;
    private FixedClockClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://store.steampowered.com");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new FixedClockClient(builder.build());
        client.budgetPerWindow = 2;
    }

    private void expectSuccess(long appId) {
        server.expect(requestTo(containsString("appids=" + appId)))
                .andRespond(withSuccess(
                        "{\"" + appId + "\":{\"success\":true,\"data\":{\"type\":\"game\",\"is_free\":true}}}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void lookupsPastTheBudgetAreSkipped() {
        expectSuccess(570);
        expectSuccess(220);

        assertThat(client.appDetails(570)).isEqualTo(new AppDetails("game", true));
        assertThat(client.appDetails(220)).isEqualTo(new AppDetails("game", true));
        // Budget spent. The third lookup answers unknown() without hitting the store,
        // proven by the mock server having no third response queued.
        assertThat(client.appDetails(999)).isEqualTo(AppDetails.unknown());
        server.verify();
    }

    @Test
    void windowTurnRestoresTheBudget() {
        expectSuccess(570);
        expectSuccess(220);
        expectSuccess(999);

        client.appDetails(570);
        client.appDetails(220);
        assertThat(client.appDetails(999)).isEqualTo(AppDetails.unknown());

        client.now += 5 * 60_000; // the window turns

        assertThat(client.appDetails(999)).isEqualTo(new AppDetails("game", true));
        server.verify();
    }
}
