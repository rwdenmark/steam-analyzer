package com.rwdenmark.steamanalyzer.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct coverage of the sliding window and the 429 path. Time is driven through
 * the nowMillis seam so no test sleeps.
 */
class ProfileRateLimiterTest {

    /** Limiter on a hand-cranked clock. */
    private static class FixedClockLimiter extends ProfileRateLimiter {
        long now = 1_000;

        FixedClockLimiter(int maxPerWindow) {
            super(maxPerWindow);
        }

        @Override
        protected long nowMillis() {
            return now;
        }
    }

    private static MockHttpServletRequest profileRequest() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/profile/76561197960287930/library");
        request.setRemoteAddr("10.0.0.1");
        return request;
    }

    private static MockHttpServletResponse runFilter(ProfileRateLimiter limiter,
                                                     MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        limiter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void underLimitRequestsPassThrough() throws Exception {
        FixedClockLimiter limiter = new FixedClockLimiter(3);

        for (int i = 0; i < 3; i++) {
            assertThat(runFilter(limiter, profileRequest()).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void overLimitGets429WithTheErrorEnvelope() throws Exception {
        FixedClockLimiter limiter = new FixedClockLimiter(1);
        runFilter(limiter, profileRequest());

        MockHttpServletResponse blocked = runFilter(limiter, profileRequest());

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentType()).isEqualTo("application/json");
        JsonNode body = new ObjectMapper().readTree(blocked.getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("error").asText()).isEqualTo("Too Many Requests");
        assertThat(body.get("message").asText()).isEqualTo("Too many requests. Give it a minute and try again.");
        assertThat(body.get("timestamp").asText()).isNotBlank();
    }

    @Test
    void windowExpiryFreesTheBudget() throws Exception {
        FixedClockLimiter limiter = new FixedClockLimiter(1);
        assertThat(runFilter(limiter, profileRequest()).getStatus()).isEqualTo(200);
        assertThat(runFilter(limiter, profileRequest()).getStatus()).isEqualTo(429);

        limiter.now += 60_001; // the hit is now older than the 60s window

        assertThat(runFilter(limiter, profileRequest()).getStatus()).isEqualTo(200);
    }

    @Test
    void forwardedHeaderFirstHopIsTheClientKey() throws Exception {
        FixedClockLimiter limiter = new FixedClockLimiter(1);
        MockHttpServletRequest first = profileRequest();
        first.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
        runFilter(limiter, first);

        MockHttpServletRequest sameHop = profileRequest();
        sameHop.addHeader("X-Forwarded-For", "1.2.3.4, 9.9.9.9");
        assertThat(runFilter(limiter, sameHop).getStatus()).isEqualTo(429);

        MockHttpServletRequest otherHop = profileRequest();
        otherHop.addHeader("X-Forwarded-For", "4.3.2.1, 5.6.7.8");
        assertThat(runFilter(limiter, otherHop).getStatus()).isEqualTo(200);
    }

    @Test
    void clientsAreLimitedIndependently() throws Exception {
        FixedClockLimiter limiter = new FixedClockLimiter(1);
        assertThat(runFilter(limiter, profileRequest()).getStatus()).isEqualTo(200);
        assertThat(runFilter(limiter, profileRequest()).getStatus()).isEqualTo(429);

        MockHttpServletRequest other = profileRequest();
        other.setRemoteAddr("10.0.0.2");
        assertThat(runFilter(limiter, other).getStatus()).isEqualTo(200);
    }

    @Test
    void nonProfilePathsAreNeverCounted() throws Exception {
        FixedClockLimiter limiter = new FixedClockLimiter(1);
        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/api/health");
        health.setRemoteAddr("10.0.0.1");

        assertThat(runFilter(limiter, health).getStatus()).isEqualTo(200);
        assertThat(runFilter(limiter, health).getStatus()).isEqualTo(200);
        // The health hits spent none of the profile budget.
        assertThat(runFilter(limiter, profileRequest()).getStatus()).isEqualTo(200);
    }

    @Test
    void defaultBudgetIsFifteenPerMinute() {
        ProfileRateLimiter limiter = new ProfileRateLimiter();
        for (int i = 0; i < 15; i++) {
            assertThat(limiter.allow("key")).isTrue();
        }
        assertThat(limiter.allow("key")).isFalse();
    }

    @Test
    void clearResetsEveryWindow() throws Exception {
        FixedClockLimiter limiter = new FixedClockLimiter(1);
        runFilter(limiter, profileRequest());
        assertThat(runFilter(limiter, profileRequest()).getStatus()).isEqualTo(429);

        limiter.clear();

        assertThat(runFilter(limiter, profileRequest()).getStatus()).isEqualTo(200);
    }
}
