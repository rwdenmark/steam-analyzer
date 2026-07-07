package com.rwdenmark.steamanalyzer.service;

/**
 * Lenient numeric reads from Steam's untyped JSON maps. Steam omits fields freely, so a
 * missing or non-numeric value reads as zero instead of blowing up a whole response.
 */
final class JsonNumbers {

    private JsonNumbers() {
    }

    static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
