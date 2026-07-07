package com.rwdenmark.steamanalyzer.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CacheConfig {

    // Cache names are all lowercase plural.
    /** Resolved vanity name to SteamID64. Stable, so cached longer. */
    public static final String VANITY_CACHE = "vanities";
    /** idOrVanity to resolved profile identity. Short TTL keeps it fresh. */
    public static final String PROFILE_CACHE = "profiles";
    /** SteamID64 to player summary (name, avatar). Short TTL keeps it fresh. */
    public static final String SUMMARY_CACHE = "summaries";
    /** SteamID64 to owned-games list, shared by the library and next endpoints. */
    public static final String LIBRARY_CACHE = "libraries";
    /** AppID to store type and free flag. App types rarely change, so cached for a week. */
    public static final String APP_DETAILS_CACHE = "appdetails";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(PROFILE_CACHE, LIBRARY_CACHE, SUMMARY_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(1_000));
        manager.registerCustomCache(VANITY_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(24))
                .maximumSize(10_000)
                .build());
        manager.registerCustomCache(APP_DETAILS_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofDays(7))
                .maximumSize(50_000)
                .build());
        return manager;
    }
}
