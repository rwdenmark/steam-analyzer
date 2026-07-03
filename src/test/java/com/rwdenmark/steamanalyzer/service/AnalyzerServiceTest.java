package com.rwdenmark.steamanalyzer.service;

import com.rwdenmark.steamanalyzer.dto.OwnedGame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-logic tests for the play-next candidate filter. No Spring context, no network. */
class AnalyzerServiceTest {

    private static OwnedGame game(String name, int minutes) {
        return OwnedGame.of(name.hashCode() & 0xffff, name, minutes, "img/" + name);
    }

    @Test
    void playNextCandidatesAreTheNeverPlayedGames() {
        List<OwnedGame> games = List.of(
                game("Stardew Valley", 500),
                game("Witcher 3", 0),
                game("Disco Elysium", 0),
                game("Outer Wilds", 0)
        );

        assertThat(AnalyzerService.playNextCandidates(games))
                .extracting(OwnedGame::name)
                .containsExactlyInAnyOrder("Witcher 3", "Disco Elysium", "Outer Wilds");
        assertThat(AnalyzerService.playNextCandidates(games)).allMatch(OwnedGame::neverPlayed);
    }

    @Test
    void playNextCandidatesSkipNonGameApps() {
        List<OwnedGame> games = List.of(
                game("Aseprite", 0).enriched("tool", false),
                game("Celeste", 0),
                game("Wallpaper Engine", 0).enriched("application", false)
        );

        assertThat(AnalyzerService.playNextCandidates(games))
                .extracting(OwnedGame::name)
                .containsExactly("Celeste");
    }

    @Test
    void playNextCandidatesSkipJunkTitles() {
        List<OwnedGame> games = List.of(
                game("Dedicated Server", 0),
                game("Game Public Test", 0),
                game("Hollow Knight", 0),
                game("Beta Branch", 0),
                game("Starbound - Unstable", 0),
                game("Source SDK Uploader", 0)
        );

        assertThat(AnalyzerService.playNextCandidates(games))
                .extracting(OwnedGame::name)
                .containsExactly("Hollow Knight");
    }

    @Test
    void playNextCandidatesKeepTitlesWhereJunkWordsAreOnlySubstrings() {
        // Word-boundary matching. "test" inside Testament or "public" inside Republic
        // must not hide a real game.
        List<OwnedGame> games = List.of(
                game("The Testament of Sherlock Holmes", 0),
                game("Star Wars: The Old Republic", 0),
                game("Observer", 0)
        );

        assertThat(AnalyzerService.playNextCandidates(games))
                .extracting(OwnedGame::name)
                .containsExactlyInAnyOrder(
                        "The Testament of Sherlock Holmes",
                        "Star Wars: The Old Republic",
                        "Observer");
    }

    @Test
    void playNextCandidatesEmptyWhenEverythingPlayed() {
        List<OwnedGame> games = List.of(game("A", 10), game("B", 20));

        assertThat(AnalyzerService.playNextCandidates(games)).isEmpty();
    }
}
