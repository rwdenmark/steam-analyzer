package com.rwdenmark.steamanalyzer.service;

import com.rwdenmark.steamanalyzer.dto.ProfileSummary;
import com.rwdenmark.steamanalyzer.error.NotFoundException;
import com.rwdenmark.steamanalyzer.error.PrivateProfileException;
import com.rwdenmark.steamanalyzer.service.AnalyzerService;
import com.rwdenmark.steamanalyzer.service.SteamClient;
import com.rwdenmark.steamanalyzer.service.SteamStoreClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        given(steam.ownedGames(STEAM_ID)).willReturn(publicLibrary());

        ProfileSummary result = service.getProfile(STEAM_ID);

        assertThat(result.steamId()).isEqualTo(STEAM_ID);
        assertThat(result.personaName()).isEqualTo("Rabscuttle");
        assertThat(result.stats().totalGames()).isEqualTo(2);
        assertThat(result.stats().neverPlayedCount()).isEqualTo(1);
        verify(steam, never()).resolveVanity(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void vanityNameIsResolvedFirst() {
        given(steam.resolveVanity("gabelogannewell")).willReturn(Optional.of(STEAM_ID));
        given(steam.playerSummary(STEAM_ID)).willReturn(Optional.of(summary()));
        given(steam.ownedGames(STEAM_ID)).willReturn(publicLibrary());

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
        given(steam.playerSummary(STEAM_ID)).willReturn(Optional.of(summary()));
        given(steam.ownedGames(STEAM_ID)).willReturn(Map.of()); // empty response = private

        assertThatThrownBy(() -> service.getProfile(STEAM_ID))
                .isInstanceOf(PrivateProfileException.class)
                .hasMessageContaining("private");
    }

    @Test
    void publicButEmptyLibraryIsNotPrivate() {
        given(steam.playerSummary(STEAM_ID)).willReturn(Optional.of(summary()));
        given(steam.ownedGames(STEAM_ID)).willReturn(Map.of("game_count", 0));

        ProfileSummary result = service.getProfile(STEAM_ID);

        assertThat(result.stats().totalGames()).isZero();
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
