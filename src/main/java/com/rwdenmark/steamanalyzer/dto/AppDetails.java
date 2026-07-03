package com.rwdenmark.steamanalyzer.dto;

/**
 * The store appdetails fields we use, app type ("game", "dlc", "tool", ...) and free flag.
 * {@link #unknown()} is the safe default on a failed lookup, so a miss never hides a game.
 */
public record AppDetails(String type, boolean free) {

    public static AppDetails unknown() {
        return new AppDetails(null, false);
    }

    /** True for the failed-lookup fallback, so callers can skip caching it. */
    public boolean isUnknown() {
        return type == null && !free;
    }
}
