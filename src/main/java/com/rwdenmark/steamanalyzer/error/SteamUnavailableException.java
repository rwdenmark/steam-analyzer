package com.rwdenmark.steamanalyzer.error;

/** Steam timed out, rate-limited us (429), or returned a 5xx. Surfaced as 502. */
public class SteamUnavailableException extends RuntimeException {
    public SteamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
