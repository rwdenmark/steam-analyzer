package com.rwdenmark.steamanalyzer.service;

import com.rwdenmark.steamanalyzer.config.CacheConfig;
import com.rwdenmark.steamanalyzer.config.SteamProperties;
import com.rwdenmark.steamanalyzer.error.SteamUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Wraps the Steam Web API. Attaches the server-side key, returns the raw response map, and
 * turns timeouts and 5xx into {@link SteamUnavailableException} so callers never see a
 * transport error.
 */
@Component
public class SteamClient {

    private static final Logger log = LoggerFactory.getLogger(SteamClient.class);

    /** ResolveVanityURL reports success code 1 for a match and 42 for no match. */
    private static final int VANITY_MATCH = 1;

    private final RestClient client;
    private final String apiKey;

    public SteamClient(RestClient steamApiClient, SteamProperties props) {
        this.client = steamApiClient;
        this.apiKey = props.apiKey();
    }

    /**
     * Resolves a vanity name to a SteamID64. Empty when Steam reports no match. Misses are
     * not cached, so a freshly claimed vanity name resolves on the next try. Spring's cache
     * layer stores the unwrapped Optional, so in the unless expression #result is the
     * unwrapped value and a miss shows up as null, not as an empty Optional.
     */
    @Cacheable(value = CacheConfig.VANITY_CACHE, key = "#vanityName.toLowerCase()", unless = "#result == null")
    public Optional<String> resolveVanity(String vanityName) {
        Map<String, Object> response = get("/ISteamUser/ResolveVanityURL/v1/", uri -> uri
                .queryParam("key", apiKey)
                .queryParam("vanityurl", vanityName));
        if (asInt(response.get("success")) != VANITY_MATCH) {
            return Optional.empty();
        }
        return Optional.ofNullable((String) response.get("steamid"));
    }

    /** Display name, avatar, and profile URL for one SteamID64. Empty if none. */
    @Cacheable(value = CacheConfig.SUMMARY_CACHE, key = "#steamId")
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> playerSummary(String steamId) {
        Map<String, Object> response = get("/ISteamUser/GetPlayerSummaries/v2/", uri -> uri
                .queryParam("key", apiKey)
                .queryParam("steamids", steamId));
        if (response.get("players") instanceof List<?> list && !list.isEmpty()) {
            return Optional.of((Map<String, Object>) list.get(0));
        }
        return Optional.empty();
    }

    /** Raw owned-games response, returned as-is so the service can tell "no games" from "private". */
    @Cacheable(value = CacheConfig.LIBRARY_CACHE, key = "#steamId")
    public Map<String, Object> ownedGames(String steamId) {
        return get("/IPlayerService/GetOwnedGames/v1/", uri -> uri
                .queryParam("key", apiKey)
                .queryParam("steamid", steamId)
                .queryParam("include_appinfo", true)
                .queryParam("include_played_free_games", true));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, UnaryOperator<UriBuilder> queryFn) {
        try {
            Map<String, Object> envelope = client.get()
                    .uri(uri -> queryFn.apply(uri.path(path)).build())
                    .retrieve()
                    .onStatus(SteamClient::isUnavailable, (req, resp) -> {
                        throw new SteamUnavailableException(
                                "Steam returned " + resp.getStatusCode() + " for " + path, null);
                    })
                    .body(Map.class);
            if (envelope == null || envelope.get("response") == null) {
                // A well-formed Steam reply always has a response object, even for private
                // profiles. A missing one means Steam is broken, not that the data is empty.
                throw new SteamUnavailableException("Steam returned an unexpected reply for " + path + ".", null);
            }
            return (Map<String, Object>) envelope.get("response");
        } catch (ResourceAccessException failure) {
            // Log only the class and path. The exception message echoes the full URI, key included.
            log.warn("Steam request to {} failed: {}", path, failure.getClass().getSimpleName());
            throw new SteamUnavailableException("Steam did not respond in time.", failure);
        }
    }

    /** 5xx and 429 mean Steam is down or throttling us, so surface 502. Other 4xx are our bad request. */
    private static boolean isUnavailable(HttpStatusCode status) {
        return status.is5xxServerError() || status.value() == 429;
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
