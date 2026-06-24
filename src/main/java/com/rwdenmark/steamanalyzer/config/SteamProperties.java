package com.rwdenmark.steamanalyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Steam Web API config. The key is server-side only, injected from the STEAM_API_KEY env var. */
@ConfigurationProperties(prefix = "steam")
public record SteamProperties(
        String apiKey,
        String apiBaseUrl
) {
}
