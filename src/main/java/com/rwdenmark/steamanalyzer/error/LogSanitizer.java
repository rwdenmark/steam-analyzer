package com.rwdenmark.steamanalyzer.error;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.regex.Pattern;

/**
 * Strips the Steam API key from any text headed for a log. The key rides as a key= query
 * param, so exception messages that echo the request URI would leak it otherwise.
 */
public final class LogSanitizer {

    private static final Pattern KEY_PARAM = Pattern.compile("key=[^&\\s]*");

    private LogSanitizer() {
    }

    /** Replaces every key= query param value with ***. Null-safe. */
    public static String sanitize(String text) {
        return text == null ? null : KEY_PARAM.matcher(text).replaceAll("key=***");
    }

    /** The full stack trace, message lines included, with any key= value stripped. */
    public static String sanitizedStackTrace(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return sanitize(out.toString());
    }
}
