package com.rwdenmark.steamanalyzer.service;

import com.rwdenmark.steamanalyzer.config.CacheConfig;
import com.rwdenmark.steamanalyzer.dto.AppDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Looks up an app's store type and free flag from the public appdetails endpoint. Any
 * failure returns {@link AppDetails#unknown()} so enrichment never breaks a load or hides a game.
 */
@Component
public class SteamStoreClient {

    private static final Logger log = LoggerFactory.getLogger(SteamStoreClient.class);

    private final RestClient store;

    public SteamStoreClient(RestClient steamStoreRestClient) {
        this.store = steamStoreRestClient;
    }

    /** Unknown results are not cached, so a failed lookup gets retried on the next request. */
    @Cacheable(value = CacheConfig.APP_DETAILS_CACHE, key = "#appId", unless = "#result.isUnknown()")
    @SuppressWarnings("unchecked")
    public AppDetails appDetails(long appId) {
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
}
