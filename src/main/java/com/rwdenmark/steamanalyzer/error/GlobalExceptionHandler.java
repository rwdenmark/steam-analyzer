package com.rwdenmark.steamanalyzer.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(PrivateProfileException.class)
    public ResponseEntity<Map<String, Object>> handlePrivate(PrivateProfileException ex) {
        return body(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /** A request for a static file that does not exist (e.g. /favicon.ico). A plain 404, not an error. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleMissingResource(NoResourceFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "No such resource.");
    }

    @ExceptionHandler(SteamUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleUnavailable(SteamUnavailableException ex) {
        log.warn("Steam upstream unavailable: {}", LogSanitizer.sanitize(ex.getMessage()));
        return body(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception ex) {
        // Logged as a sanitized string, never the raw exception. Transport errors carry the
        // request URI in their message and the Steam key rides on it as a query param.
        log.error("Unhandled exception: {}", LogSanitizer.sanitizedStackTrace(ex));
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong.");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiError.body(status, message));
    }
}
