package com.rwdenmark.steamanalyzer.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The junk flag is set once at construction. Everything downstream just reads it. */
class OwnedGameTest {

    private static OwnedGame game(String name) {
        return OwnedGame.of(1, name, 0, "img");
    }

    @Test
    void junkWordsFlagTheGame() {
        assertThat(game("Dedicated Server").junk()).isTrue();
        assertThat(game("Game Public Test").junk()).isTrue();
        assertThat(game("Starbound - Unstable").junk()).isTrue();
        assertThat(game("Source SDK Uploader").junk()).isTrue();
        assertThat(game("BETA Branch").junk()).isTrue();
    }

    @Test
    void realTitlesStayUnflagged() {
        assertThat(game("Hollow Knight").junk()).isFalse();
        assertThat(game("Portal 2").junk()).isFalse();
    }

    @Test
    void junkWordsMatchOnWordBoundariesOnly() {
        // "test" inside Testament or "public" inside Republic must not flag a real game.
        assertThat(game("The Testament of Sherlock Holmes").junk()).isFalse();
        assertThat(game("Star Wars: The Old Republic").junk()).isFalse();
    }

    @Test
    void enrichingKeepsTheJunkFlag() {
        assertThat(game("Dedicated Server").enriched("tool", true).junk()).isTrue();
        assertThat(game("Celeste").enriched("game", false).junk()).isFalse();
    }
}
