package com.rwdenmark.steamanalyzer.dto;

/** Steam profile identity plus its computed stats. */
public record ProfileSummary(
        String steamId,
        String personaName,
        String avatarUrl,
        String profileUrl,
        AnalyzerStats stats
) {
}
