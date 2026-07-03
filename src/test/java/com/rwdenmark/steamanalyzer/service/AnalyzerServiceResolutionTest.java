package com.rwdenmark.steamanalyzer.service;

import com.rwdenmark.steamanalyzer.dto.AppDetails;
import com.rwdenmark.steamanalyzer.dto.OwnedGame;
import com.rwdenmark.steamanalyzer.dto.ProfileSummary;
import com.rwdenmark.steamanalyzer.error.NotFoundException;
import com.rwdenmark.steamanalyzer.error.PrivateProfileException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AnalyzerServiceResolutionTest {

    @Mock
    SteamClient steam;
    @Mock
    SteamStoreClient store;
    @InjectMocks
    AnalyzerService service;

    private static final String STEAM_ID = "76561197960287930";

    @Test
    void numericIdSkipsVanityResolution() {
        given(steam.playerSummary(STEAM_ID)).willReturn(Optional.of(summary()));

        ProfileSummary result = service.getProfile(STEAM_ID);

        assertThat(result.steamId()).isEqualTo(STEAM_ID);
        assertThat(result.personaName()).isEqualTo("Rabscuttle");
        verify(steam, never()).resolveVanity(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void vanityNameIsResolvedFirst() {
        given(steam.resolveVanity("gabelogannewell")).willReturn(Optional.of(STEAM_ID));
        given(steam.playerSummary(STEAM_ID)).willReturn(Optional.of(summary()));

        ProfileSummary result = service.getProfile("gabelogannewell");

        assertThat(result.steamId()).isEqualTo(STEAM_ID);
        verify(steam).resolveVanity("gabelogannewell");
    }

    @Test
    void vanityMissThrowsNotFound() {
        given(steam.resolveVanity("nobody-here")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfile("nobody-here"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("No Steam account matches");
    }

    @Test
    void privateLibraryThrowsPrivateProfile() {
        // An empty response object with a real summary behind it means private.
        given(steam.ownedGames(STEAM_ID)).willReturn(Map.of());
        given(steam.playerSummary(STEAM_ID)).willReturn(Optional.of(summary()));

        assertThatThrownBy(() -> service.getLibrary(STEAM_ID, "playtime", false))
                .isInstanceOf(PrivateProfileException.class)
                .hasMessageContaining("private");
    }

    @Test
    void nonexistentNumericIdThrowsNotFoundNotPrivate() {
        // A made-up 17-digit ID also comes back empty, but there is no player behind it.
        given(steam.ownedGames(STEAM_ID)).willReturn(Map.of());
        given(steam.playerSummary(STEAM_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLibrary(STEAM_ID, "playtime", false))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("No public Steam profile");
    }

    @Test
    void publicButEmptyLibraryIsNotPrivate() {
        given(steam.ownedGames(STEAM_ID)).willReturn(Map.of("game_count", 0));

        assertThat(service.getLibrary(STEAM_ID, "playtime", false)).isEmpty();
    }

    @Test
    void playNextReturnsAtMostTwoNeverPlayedGames() {
        given(steam.ownedGames(STEAM_ID)).willReturn(Map.of(
                "game_count", 4,
                "games", List.of(
                        Map.of("appid", 1, "name", "Played Game", "playtime_forever", 300),
                        Map.of("appid", 2, "name", "Backlog A", "playtime_forever", 0),
                        Map.of("appid", 3, "name", "Backlog B", "playtime_forever", 0),
                        Map.of("appid", 4, "name", "Backlog C", "playtime_forever", 0))));

        List<OwnedGame> picks = service.getPlayNext(STEAM_ID);

        assertThat(picks).hasSize(2);
        assertThat(picks).allMatch(OwnedGame::neverPlayed);
        assertThat(picks).extracting(OwnedGame::name)
                .isSubsetOf("Backlog A", "Backlog B", "Backlog C");
    }

    @Test
    void playNextIsEmptyWhenBacklogIsClear() {
        given(steam.ownedGames(STEAM_ID)).willReturn(Map.of(
                "game_count", 1,
                "games", List.of(Map.of("appid", 1, "name", "Played Game", "playtime_forever", 300))));

        assertThat(service.getPlayNext(STEAM_ID)).isEmpty();
    }

    @Test
    void enrichLooksUpStoreTypeAndFreeFlagPerGame() {
        given(steam.ownedGames(STEAM_ID)).willReturn(Map.of(
                "game_count", 2,
                "games", List.of(
                        Map.of("appid", 220, "name", "Half-Life 2", "playtime_forever", 600),
                        Map.of("appid", 323, "name", "Aseprite", "playtime_forever", 0))));
        given(store.appDetails(220)).willReturn(new AppDetails("game", false));
        given(store.appDetails(323)).willReturn(new AppDetails("tool", true));

        List<OwnedGame> library = service.getLibrary(STEAM_ID, "name", true);

        assertThat(library).extracting(OwnedGame::name)
                .containsExactly("Aseprite", "Half-Life 2"); // sorted by name
        assertThat(library).filteredOn(g -> g.appId() == 323)
                .singleElement()
                .satisfies(g -> {
                    assertThat(g.type()).isEqualTo("tool");
                    assertThat(g.free()).isTrue();
                });
        assertThat(library).filteredOn(g -> g.appId() == 220)
                .singleElement()
                .satisfies(g -> assertThat(g.type()).isEqualTo("game"));
    }

    @Test
    void libraryWithoutEnrichNeverCallsTheStore() {
        given(steam.ownedGames(STEAM_ID)).willReturn(publicLibrary());

        service.getLibrary(STEAM_ID, "playtime", false);

        verify(store, never()).appDetails(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void enrichmentIsCappedToProtectTheRateLimit() {
        int count = 250;
        List<Map<String, Object>> games = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            games.add(Map.of("appid", i, "name", "Game " + i, "playtime_forever", 0));
        }
        given(steam.ownedGames(STEAM_ID)).willReturn(Map.of("game_count", count, "games", games));
        given(store.appDetails(org.mockito.ArgumentMatchers.anyLong()))
                .willReturn(new AppDetails("game", false));

        List<OwnedGame> library = service.getLibrary(STEAM_ID, "name", true);

        assertThat(library).hasSize(count);
        assertThat(library).filteredOn(g -> g.type() != null).hasSize(200);
        assertThat(library).filteredOn(g -> g.type() == null).hasSize(50);
    }

    private static Map<String, Object> summary() {
        return Map.of(
                "personaname", "Rabscuttle",
                "avatarfull", "https://avatar/full.jpg",
                "profileurl", "https://steamcommunity.com/id/rabscuttle/"
        );
    }

    private static Map<String, Object> publicLibrary() {
        return Map.of(
                "game_count", 2,
                "games", List.of(
                        Map.of("appid", 220, "name", "Half-Life 2", "playtime_forever", 600),
                        Map.of("appid", 400, "name", "Portal", "playtime_forever", 0)
                )
        );
    }
}
