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
 * Wraps the Steam Web API: attaches the server-side key, returns the raw response map, and
 * turns timeouts and 5xx into {@link SteamUnavailableException} so callers never see a
 * transport error.
 */
@Component
public class SteamClient {

    private static final Logger log = LoggerFactory.getLogger(SteamClient.class);

    /** ResolveVanityURL success codes: 1 = match found, 42 = no match. */
    private static final int VANITY_MATCH = 1;

    private final RestClient client;
    private final String apiKey;

    public SteamClient(RestClient steamApiClient, SteamProperties props) {
        this.client = steamApiClient;
        this.apiKey = props.apiKey();
    }

    /** Resolves a vanity name to a SteamID64. Empty when Steam reports no match. */
    @Cacheable(value = CacheConfig.VANITY_CACHE, key = "#vanityName.toLowerCase()")
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
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        throw new SteamUnavailableException(
                                "Steam returned " + resp.getStatusCode() + " for " + path, null);
                    })
                    .body(Map.class);
            if (envelope == null || envelope.get("response") == null) {
                return Map.of();
            }
            return (Map<String, Object>) envelope.get("response");
        } catch (ResourceAccessException timeout) {
            log.warn("Steam request to {} timed out: {}", path, timeout.getMessage());
            throw new SteamUnavailableException("Steam did not respond in time.", timeout);
        }
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
