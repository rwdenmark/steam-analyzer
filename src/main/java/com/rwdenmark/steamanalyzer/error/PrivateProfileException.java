package com.rwdenmark.steamanalyzer.error;

/** Account exists but Game details privacy is not Public. Handled as 403, never as an empty library. */
public class PrivateProfileException extends RuntimeException {
    public PrivateProfileException(String message) {
        super(message);
    }
}
