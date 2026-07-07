package com.rwdenmark.steamanalyzer.error;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;

/** The one JSON error envelope, shared by the exception advice and the rate-limit filter. */
public final class ApiError {

    private ApiError() {
    }

    public static Map<String, Object> body(HttpStatus status, String message) {
        return Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message,
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * The same envelope serialized by hand, for callers that answer before the dispatcher
     * and its JSON converter. Nothing is escaped, so only pass ASCII-constant messages.
     */
    public static String json(HttpStatus status, String message) {
        return "{\"status\":" + status.value()
                + ",\"error\":\"" + status.getReasonPhrase() + "\","
                + "\"message\":\"" + (message == null ? "" : message) + "\","
                + "\"timestamp\":\"" + Instant.now() + "\"}";
    }
}
