package com.rwdenmark.steamanalyzer.dto;

/**
 * One owned game. playtimeMinutes is Steam's lifetime playtime_forever. playtimeHours is the
 * rounded value the frontend shows. type and free come from a later store lookup and stay
 * null/false until enriched, so an unenriched game counts as non-free and is never hidden.
 */
public record OwnedGame(
        long appId,
        String name,
        int playtimeMinutes,
        double playtimeHours,
        String imageUrl,
        String type,
        boolean free
) {
    public static OwnedGame of(long appId, String name, int playtimeMinutes, String imageUrl) {
        double hours = Math.round(playtimeMinutes / 60.0 * 10.0) / 10.0;
        return new OwnedGame(appId, name, playtimeMinutes, hours, imageUrl, null, false);
    }

    public OwnedGame enriched(String type, boolean free) {
        return new OwnedGame(appId, name, playtimeMinutes, playtimeHours, imageUrl, type, free);
    }

    public boolean neverPlayed() {
        return playtimeMinutes == 0;
    }

    /** Unknown type counts as a game, so a lookup miss never drops it. */
    public boolean isGame() {
        return type == null || "game".equalsIgnoreCase(type);
    }
}
