package com.rwdenmark.steamanalyzer.dto;

import java.util.regex.Pattern;

/**
 * One owned game. playtimeMinutes is Steam's lifetime playtime_forever. playtimeHours is the
 * rounded value the frontend shows. type and free come from a later store lookup and stay
 * null/false until enriched, so an unenriched game counts as non-free and is never hidden.
 * junk marks non-game entries by name so the frontend can hide them without its own list.
 */
public record OwnedGame(
        long appId,
        String name,
        int playtimeMinutes,
        double playtimeHours,
        String imageUrl,
        String type,
        boolean free,
        boolean junk
) {
    /**
     * Words that mark non-game entries (beta/server/dev builds, uploaders). Matched on word
     * boundaries so Testament and Republic stay visible. The only junk classifier, the
     * play-next filter and the frontend both read the flag it sets.
     */
    private static final Pattern JUNK_WORDS = Pattern.compile(
            "\\b(public|test|server|unstable|dedicated|uploader|beta|staging)\\b",
            Pattern.CASE_INSENSITIVE);

    public static OwnedGame of(long appId, String name, int playtimeMinutes, String imageUrl) {
        double hours = Math.round(playtimeMinutes / 60.0 * 10.0) / 10.0;
        return new OwnedGame(appId, name, playtimeMinutes, hours, imageUrl, null, false,
                JUNK_WORDS.matcher(name).find());
    }

    public OwnedGame enriched(String type, boolean free) {
        return new OwnedGame(appId, name, playtimeMinutes, playtimeHours, imageUrl, type, free, junk);
    }

    public boolean neverPlayed() {
        return playtimeMinutes == 0;
    }

    /** Unknown type counts as a game, so a lookup miss never drops it. */
    public boolean isGame() {
        return type == null || "game".equalsIgnoreCase(type);
    }
}
