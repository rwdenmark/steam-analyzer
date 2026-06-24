package com.rwdenmark.steamanalyzer.dto;

import java.util.List;

/**
 * Computed backlog summary for a profile.
 *
 * @param totalGames        count of owned games
 * @param totalHours        sum of playtime across the library, in hours
 * @param neverPlayedCount  owned games with zero recorded playtime
 * @param neverPlayedPct    never-played as a percent of the library (0-100, one decimal)
 * @param backlogScore      same ratio as a 0-1 fraction (never-played / total)
 * @param topPlayed         up to five most-played games, highest first
 * @param recommendations   up to two never-played games to start next (may be empty)
 */
public record AnalyzerStats(
        int totalGames,
        double totalHours,
        int neverPlayedCount,
        double neverPlayedPct,
        double backlogScore,
        List<OwnedGame> topPlayed,
        List<OwnedGame> recommendations
) {
}
