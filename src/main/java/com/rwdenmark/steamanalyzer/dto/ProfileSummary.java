package com.rwdenmark.steamanalyzer.dto;

/**
 * Steam profile identity. Stats are computed client-side from the library. createdAt is the
 * account creation time (epoch seconds), null when the profile hides it.
 */
public record ProfileSummary(
        String steamId,
        String personaName,
        String avatarUrl,
        String profileUrl,
        Long createdAt
) {
}
