package com.rwdenmark.steamanalyzer.error;

/** A vanity name that resolves to no account, or a SteamID with no public summary. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
