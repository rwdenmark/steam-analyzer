package com.rwdenmark.steamanalyzer.service;

import com.rwdenmark.steamanalyzer.config.CacheConfig;
import com.rwdenmark.steamanalyzer.dto.AppDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Looks up an app's store type and free flag from the public appdetails endpoint. Any
 * failure returns {@link AppDetails#unknown()} so enrichment never breaks a load or hides a game.
 */
@Component
public class SteamStoreClient {

    private static final Logger log = LoggerFactory.getLogger(SteamStoreClient.class);

    /** The endpoint rate-limits near 200 requests / 5 min per IP, shared by every request. */
    private static final int DEFAULT_BUDGET_PER_WINDOW = 200;
    private static final long WINDOW_MS = 5 * 60_000;

    private final RestClient store;
    /** Package-private so tests can shrink the budget. */
    int budgetPerWindow = DEFAULT_BUDGET_PER_WINDOW;
    private final AtomicInteger callsInWindow = new AtomicInteger();
    private volatile long windowStart;

    // Two RestClient beans exist, the qualifier picks the store one explicitly.
    public SteamStoreClient(@Qualifier("steamStoreRestClient") RestClient steamStoreRestClient) {
        this.store = steamStoreRestClient;
    }

    /** Unknown results are not cached, so a failed lookup gets retried on the next request. */
    @Cacheable(value = CacheConfig.APP_DETAILS_CACHE, key = "#appId", unless = "#result.isUnknown()")
    @SuppressWarnings("unchecked")
    public AppDetails appDetails(long appId) {
        // Runs on cache misses only, so cache hits never spend budget. A skipped lookup
        // returns unknown(), which is never cached, so it retries once the window turns.
        if (!withinBudget()) {
            log.debug("appdetails budget exhausted, skipping lookup for {}", appId);
            return AppDetails.unknown();
        }
        try {
            Map<String, Object> body = store.get()
                    .uri(uri -> uri.path("/api/appdetails")
                            .queryParam("appids", appId)
                            .queryParam("filters", "basic")
                            .build())
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                return AppDetails.unknown();
            }
            Object entry = body.get(String.valueOf(appId));
            if (!(entry instanceof Map<?, ?> e) || !Boolean.TRUE.equals(((Map<String, Object>) e).get("success"))) {
                return AppDetails.unknown();
            }
            Object data = ((Map<String, Object>) e).get("data");
            if (!(data instanceof Map<?, ?> d)) {
                return AppDetails.unknown();
            }
            Map<String, Object> details = (Map<String, Object>) d;
            String type = (String) details.get("type");
            boolean free = Boolean.TRUE.equals(details.get("is_free"));
            return new AppDetails(type, free);
        } catch (RuntimeException ex) {
            log.debug("appdetails lookup failed for {}: {}", appId, ex.getMessage());
            return AppDetails.unknown();
        }
    }

    /**
     * Fixed-window budget across all requests. MAX_ENRICH caps one request at 200 lookups,
     * this stops two requests in the same window from firing 400 at Steam's per-IP limit.
     */
    private boolean withinBudget() {
        long now = nowMillis();
        if (now - windowStart >= WINDOW_MS) {
            synchronized (this) {
                if (now - windowStart >= WINDOW_MS) {
                    windowStart = now;
                    callsInWindow.set(0);
                }
            }
        }
        return callsInWindow.incrementAndGet() <= budgetPerWindow;
    }

    // Clock seam so tests can drive the window without sleeping.
    protected long nowMillis() {
        return System.currentTimeMillis();
    }
}
