package com.rwdenmark.steamanalyzer.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the Steam key never survives into a log line, whatever text carries it. */
class LogSanitizerTest {

    @Test
    void stripsTheKeyQueryParamValue() {
        String text = "I/O error on GET request for "
                + "\"https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/"
                + "?key=AAAA1111BBBB2222&steamid=76561197960287930\": timeout";

        String sanitized = LogSanitizer.sanitize(text);

        assertThat(sanitized).doesNotContain("AAAA1111BBBB2222");
        assertThat(sanitized).contains("key=***");
        assertThat(sanitized).contains("steamid=76561197960287930");
    }

    @Test
    void stripsEveryOccurrence() {
        String sanitized = LogSanitizer.sanitize("key=SECRET1 then ?key=SECRET2&x=1");

        assertThat(sanitized).doesNotContain("SECRET1").doesNotContain("SECRET2");
        assertThat(sanitized).isEqualTo("key=*** then ?key=***&x=1");
    }

    @Test
    void leavesTextWithoutAKeyAlone() {
        assertThat(LogSanitizer.sanitize("plain message")).isEqualTo("plain message");
        assertThat(LogSanitizer.sanitize(null)).isNull();
    }

    @Test
    void stackTraceIsSanitizedIncludingCauses() {
        RuntimeException cause = new RuntimeException("GET https://x/?key=TOPSECRET failed");
        RuntimeException wrapped = new RuntimeException("request failed for ?key=TOPSECRET", cause);

        String trace = LogSanitizer.sanitizedStackTrace(wrapped);

        assertThat(trace).doesNotContain("TOPSECRET");
        assertThat(trace).contains("key=***");
        assertThat(trace).contains("Caused by");
        assertThat(trace).contains("LogSanitizerTest"); // frames survive
    }
}
