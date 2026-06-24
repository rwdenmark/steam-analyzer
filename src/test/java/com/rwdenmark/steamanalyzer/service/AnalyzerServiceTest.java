package com.rwdenmark.steamanalyzer.service;

import com.rwdenmark.steamanalyzer.dto.AnalyzerStats;
import com.rwdenmark.steamanalyzer.dto.OwnedGame;
import com.rwdenmark.steamanalyzer.service.AnalyzerService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-logic tests for the backlog math. No Spring context, no network. */
class AnalyzerServiceTest {

    private static OwnedGame game(String name, int minutes) {
        return OwnedGame.of(name.hashCode() & 0xffff, name, minutes, "img/" + name);
    }

    @Test
    void computesTotalsAndBacklogRatio() {
        List<OwnedGame> games = List.of(
                game("Half-Life", 600),   // 10.0h
                game("Portal", 180),      // 3.0h
                game("Hades", 0),
                game("Celeste", 0)
        );

        AnalyzerStats stats = AnalyzerService.computeStats(games);

        assertThat(stats.totalGames()).isEqualTo(4);
        assertThat(stats.totalHours()).isEqualTo(13.0);
        assertThat(stats.neverPlayedCount()).isEqualTo(2);
        assertThat(stats.neverPlayedPct()).isEqualTo(50.0);
        assertThat(stats.backlogScore()).isEqualTo(0.5);
    }

    @Test
    void topPlayedIsHighestFirstAndExcludesNeverPlayed() {
        List<OwnedGame> games = List.of(
                game("A", 50),
                game("B", 300),
                game("C", 0),
                game("D", 120),
                game("E", 1000),
                game("F", 10),
                game("G", 0)
        );

        AnalyzerStats stats = AnalyzerService.computeStats(games);

        assertThat(stats.topPlayed()).hasSize(5);
        assertThat(stats.topPlayed().stream().map(OwnedGame::name))
                .containsExactly("E", "B", "D", "A", "F");
        assertThat(stats.topPlayed()).noneMatch(OwnedGame::neverPlayed);
    }

    @Test
    void recommendsTwoAlphabeticalNeverPlayedGames() {
        List<OwnedGame> games = List.of(
                game("Stardew Valley", 500),
                game("Witcher 3", 0),
                game("Disco Elysium", 0),
                game("Outer Wilds", 0)
        );

        AnalyzerStats stats = AnalyzerService.computeStats(games);

        assertThat(stats.recommendations()).hasSize(2);
        assertThat(stats.recommendations().stream().map(OwnedGame::name))
                .containsExactly("Disco Elysium", "Outer Wilds");
        assertThat(stats.recommendations()).allMatch(OwnedGame::neverPlayed);
    }

    @Test
    void recommendationsSkipNonGameApps() {
        List<OwnedGame> games = List.of(
                game("Aseprite", 0).enriched("tool", false),
                game("Celeste", 0),
                game("Wallpaper Engine", 0).enriched("application", false)
        );

        AnalyzerStats stats = AnalyzerService.computeStats(games);

        assertThat(stats.recommendations().stream().map(OwnedGame::name))
                .containsExactly("Celeste");
    }

    @Test
    void recommendationsSkipPublicTestAndServerTitles() {
        List<OwnedGame> games = List.of(
                game("Dedicated Server", 0),
                game("Game Public Test", 0),
                game("Hollow Knight", 0),
                game("Testbed Utility", 0),
                game("Starbound - Unstable", 0)
        );

        AnalyzerStats stats = AnalyzerService.computeStats(games);

        assertThat(stats.recommendations().stream().map(OwnedGame::name))
                .containsExactly("Hollow Knight");
    }

    @Test
    void recommendationsEmptyWhenEverythingPlayed() {
        List<OwnedGame> games = List.of(game("A", 10), game("B", 20));

        assertThat(AnalyzerService.computeStats(games).recommendations()).isEmpty();
    }

    @Test
    void emptyLibraryDoesNotDivideByZero() {
        AnalyzerStats stats = AnalyzerService.computeStats(List.of());

        assertThat(stats.totalGames()).isZero();
        assertThat(stats.totalHours()).isZero();
        assertThat(stats.neverPlayedCount()).isZero();
        assertThat(stats.neverPlayedPct()).isZero();
        assertThat(stats.backlogScore()).isZero();
        assertThat(stats.topPlayed()).isEmpty();
        assertThat(stats.recommendations()).isEmpty();
    }

    @Test
    void hoursAreRoundedToOneDecimal() {
        // 100 minutes = 1.6666...h -> 1.7
        AnalyzerStats stats = AnalyzerService.computeStats(List.of(game("A", 100)));

        assertThat(stats.totalHours()).isEqualTo(1.7);
    }
}
