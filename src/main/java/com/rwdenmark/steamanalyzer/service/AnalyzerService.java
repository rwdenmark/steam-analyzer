package com.rwdenmark.steamanalyzer.service;

import com.rwdenmark.steamanalyzer.config.CacheConfig;
import com.rwdenmark.steamanalyzer.dto.AppDetails;
import com.rwdenmark.steamanalyzer.dto.AnalyzerStats;
import com.rwdenmark.steamanalyzer.dto.OwnedGame;
import com.rwdenmark.steamanalyzer.dto.ProfileSummary;
import com.rwdenmark.steamanalyzer.error.NotFoundException;
import com.rwdenmark.steamanalyzer.error.PrivateProfileException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves a profile, fetches its library, and computes the stats. {@link #computeStats}
 * is a pure function so it can be unit-tested without the network.
 */
@Service
public class AnalyzerService {

    private static final Pattern STEAMID64 = Pattern.compile("\\d{17}");
    private static final String CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps/";
    private static final int TOP_PLAYED_LIMIT = 5;
    private static final int RECOMMENDATION_LIMIT = 2;

    private final SteamClient steam;
    private final SteamStoreClient store;

    public AnalyzerService(SteamClient steam, SteamStoreClient store) {
        this.steam = steam;
        this.store = store;
    }

    @Cacheable(value = CacheConfig.PROFILE_CACHE, key = "#idOrVanity.toLowerCase()")
    public ProfileSummary getProfile(String idOrVanity) {
        String steamId = resolveSteamId(idOrVanity);
        Map<String, Object> summary = steam.playerSummary(steamId)
                .orElseThrow(() -> new NotFoundException("No public Steam profile for ID " + steamId + "."));
        // Stats need only playtime, not the per-app store lookups, so skip enrichment here.
        AnalyzerStats stats = computeStats(fetchLibrary(steamId, false));
        return new ProfileSummary(
                steamId,
                (String) summary.get("personaname"),
                (String) summary.get("avatarfull"),
                (String) summary.get("profileurl"),
                stats
        );
    }

    /**
     * enrich adds each game's store type and free flag, which the Free and Tools filters
     * need. Off by default to keep the first load fast.
     */
    public List<OwnedGame> getLibrary(String idOrVanity, String sort, boolean enrich) {
        List<OwnedGame> games = fetchLibrary(resolveSteamId(idOrVanity), enrich);
        return sortLibrary(games, sort);
    }

    public List<OwnedGame> getPlayNext(String idOrVanity) {
        List<OwnedGame> picks = computeStats(fetchLibrary(resolveSteamId(idOrVanity), false)).recommendations();
        if (picks.isEmpty()) {
            throw new NotFoundException("No never-played games in this library. The backlog is clear.");
        }
        return picks;
    }

    private String resolveSteamId(String idOrVanity) {
        if (STEAMID64.matcher(idOrVanity).matches()) {
            return idOrVanity;
        }
        return steam.resolveVanity(idOrVanity)
                .orElseThrow(() -> new NotFoundException(
                        "No Steam account matches '" + idOrVanity + "'. Use the name from the profile URL "
                                + "(steamcommunity.com/id/NAME) or a 17-digit SteamID."));
    }

    private List<OwnedGame> fetchLibrary(String steamId, boolean enrich) {
        Map<String, Object> response = steam.ownedGames(steamId);
        if (isPrivate(response)) {
            throw new PrivateProfileException(
                    "This profile's Game details are private. Set Game details to Public in Steam "
                            + "privacy settings to use this.");
        }
        List<OwnedGame> games = mapGames(response);
        return enrich ? enrichDetails(games) : games;
    }

    /** A failed appdetails lookup leaves the game unenriched, so it is never wrongly hidden. */
    private List<OwnedGame> enrichDetails(List<OwnedGame> games) {
        return games.stream()
                .map(g -> {
                    AppDetails details = store.appDetails(g.appId());
                    return g.enriched(details.type(), details.free());
                })
                .toList();
    }

    /**
     * A public profile always returns game_count; a private one returns an empty object.
     * Absence of both keys is the private signal, never treated as owning nothing.
     */
    private static boolean isPrivate(Map<String, Object> ownedResponse) {
        return !ownedResponse.containsKey("game_count") && !ownedResponse.containsKey("games");
    }

    @SuppressWarnings("unchecked")
    private static List<OwnedGame> mapGames(Map<String, Object> ownedResponse) {
        Object games = ownedResponse.get("games");
        if (!(games instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(g -> (Map<String, Object>) g)
                .map(AnalyzerService::toOwnedGame)
                .toList();
    }

    private static OwnedGame toOwnedGame(Map<String, Object> g) {
        long appId = asLong(g.get("appid"));
        String name = g.get("name") instanceof String s && !s.isBlank() ? s : "Unknown (" + appId + ")";
        return OwnedGame.of(appId, name, asInt(g.get("playtime_forever")), headerImage(appId));
    }

    /**
     * Top-played excludes never-played games (they all tie at zero). Recommendations are the
     * alphabetically first two never-played games, tools skipped, deterministic for testing.
     */
    public static AnalyzerStats computeStats(List<OwnedGame> games) {
        int total = games.size();
        long totalMinutes = games.stream().mapToLong(OwnedGame::playtimeMinutes).sum();
        double totalHours = round1(totalMinutes / 60.0);

        int neverPlayed = (int) games.stream().filter(OwnedGame::neverPlayed).count();
        double neverPct = total == 0 ? 0.0 : round1(neverPlayed * 100.0 / total);
        double backlogScore = total == 0 ? 0.0 : round3((double) neverPlayed / total);

        List<OwnedGame> topPlayed = games.stream()
                .filter(g -> g.playtimeMinutes() > 0)
                .sorted(Comparator.comparingInt(OwnedGame::playtimeMinutes).reversed())
                .limit(TOP_PLAYED_LIMIT)
                .toList();

        List<OwnedGame> recommendations = games.stream()
                .filter(OwnedGame::neverPlayed)
                .filter(OwnedGame::isGame)
                .filter(g -> !isJunkTitle(g.name()))
                .sorted(Comparator.comparing((OwnedGame g) -> g.name().toLowerCase()))
                .limit(RECOMMENDATION_LIMIT)
                .toList();

        return new AnalyzerStats(total, totalHours, neverPlayed, neverPct, backlogScore, topPlayed, recommendations);
    }

    private static List<OwnedGame> sortLibrary(List<OwnedGame> games, String sort) {
        Comparator<OwnedGame> comparator = switch (sort == null ? "" : sort.toLowerCase()) {
            case "name" -> Comparator.comparing((OwnedGame g) -> g.name().toLowerCase());
            case "least" -> Comparator.comparingInt(OwnedGame::playtimeMinutes);
            default -> Comparator.comparingInt(OwnedGame::playtimeMinutes).reversed();
        };
        return games.stream().sorted(comparator).toList();
    }

    private static boolean isJunkTitle(String name) {
        String n = name.toLowerCase();
        return n.contains("public") || n.contains("test") || n.contains("server") || n.contains("unstable");
    }

    private static String headerImage(long appId) {
        return CDN + appId + "/header.jpg";
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
