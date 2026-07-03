package com.rwdenmark.steamanalyzer.service;

import com.rwdenmark.steamanalyzer.config.CacheConfig;
import com.rwdenmark.steamanalyzer.dto.AppDetails;
import com.rwdenmark.steamanalyzer.dto.OwnedGame;
import com.rwdenmark.steamanalyzer.dto.ProfileSummary;
import com.rwdenmark.steamanalyzer.error.NotFoundException;
import com.rwdenmark.steamanalyzer.error.PrivateProfileException;
import com.rwdenmark.steamanalyzer.error.SteamUnavailableException;
import jakarta.annotation.PreDestroy;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Resolves a profile and serves its library and play-next pick. {@link #playNextCandidates}
 * is a pure function so it can be unit-tested without the network.
 */
@Service
public class AnalyzerService {

    private static final Pattern STEAMID64 = Pattern.compile("\\d{17}");
    private static final String CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps/";
    private static final int PLAY_NEXT_LIMIT = 2;
    /** Steam's store appdetails endpoint rate-limits near 200 requests / 5 min per IP. */
    private static final int MAX_ENRICH = 200;
    private static final int ENRICH_CONCURRENCY = 6;

    private final SteamClient steam;
    private final SteamStoreClient store;
    private final Random random = new Random();
    /** One pool shared by all enrich calls. A per-request pool would churn threads for nothing. */
    private final ExecutorService enrichPool;

    public AnalyzerService(SteamClient steam, SteamStoreClient store) {
        this.steam = steam;
        this.store = store;
        this.enrichPool = Executors.newFixedThreadPool(ENRICH_CONCURRENCY);
    }

    @PreDestroy
    void shutdownEnrichPool() {
        enrichPool.shutdown();
    }

    @Cacheable(value = CacheConfig.PROFILE_CACHE, key = "#idOrVanity.toLowerCase()")
    public ProfileSummary getProfile(String idOrVanity) {
        String steamId = resolveSteamId(idOrVanity);
        Map<String, Object> summary = steam.playerSummary(steamId)
                .orElseThrow(() -> new NotFoundException("No public Steam profile for ID " + steamId + "."));
        Object created = summary.get("timecreated");
        return new ProfileSummary(
                steamId,
                (String) summary.get("personaname"),
                (String) summary.get("avatarfull"),
                (String) summary.get("profileurl"),
                created instanceof Number n ? n.longValue() : null
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

    /** Up to two random never-played games, reshuffled each call. Empty when the backlog is clear. */
    public List<OwnedGame> getPlayNext(String idOrVanity) {
        List<OwnedGame> candidates = new ArrayList<>(
                playNextCandidates(fetchLibrary(resolveSteamId(idOrVanity), false)));
        Collections.shuffle(candidates, random);
        return candidates.stream().limit(PLAY_NEXT_LIMIT).toList();
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
            // A nonexistent SteamID and a private profile both come back empty here, so
            // check the summary before calling it private. Only runs on this empty path.
            if (steam.playerSummary(steamId).isEmpty()) {
                throw new NotFoundException("No public Steam profile for ID " + steamId + ".");
            }
            throw new PrivateProfileException(
                    "This profile's Game details are private. Set Game details to Public in Steam "
                            + "privacy settings to use this.");
        }
        List<OwnedGame> games = mapGames(response);
        return enrich ? enrichDetails(games) : games;
    }

    /**
     * Looks up each game's store type and free flag. The appdetails endpoint has no batch form
     * and rate-limits near 200 requests / 5 min per IP, so lookups run a few at a time and are
     * capped at {@link #MAX_ENRICH} per request. Games past the cap, and any lookup that fails,
     * stay unenriched, so they count as non-free games and are never wrongly hidden.
     */
    private List<OwnedGame> enrichDetails(List<OwnedGame> games) {
        int cap = Math.min(games.size(), MAX_ENRICH);
        try {
            List<Future<OwnedGame>> futures = games.stream()
                    .limit(cap)
                    .map(g -> enrichPool.submit(() -> {
                        AppDetails details = store.appDetails(g.appId());
                        return g.enriched(details.type(), details.free());
                    }))
                    .toList();
            List<OwnedGame> enriched = new ArrayList<>(games.size());
            for (Future<OwnedGame> future : futures) {
                enriched.add(future.get());
            }
            // Games beyond the cap keep their unenriched form, so they are never hidden.
            enriched.addAll(games.subList(cap, games.size()));
            return enriched;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SteamUnavailableException("Library enrichment was interrupted.", e);
        } catch (ExecutionException e) {
            // store.appDetails handles its own failures and returns unknown(), so this is unexpected.
            throw new SteamUnavailableException("Library enrichment failed.", e.getCause());
        }
    }

    /**
     * A public profile always returns game_count. A private one returns an empty object.
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

    /** Never-played actual games with junk titles skipped, the pool getPlayNext draws random picks from. */
    static List<OwnedGame> playNextCandidates(List<OwnedGame> games) {
        return games.stream()
                .filter(OwnedGame::neverPlayed)
                .filter(OwnedGame::isGame)
                .filter(g -> !g.junk())
                .toList();
    }

    private static List<OwnedGame> sortLibrary(List<OwnedGame> games, String sort) {
        Comparator<OwnedGame> comparator = switch (sort == null ? "" : sort.toLowerCase()) {
            case "name" -> Comparator.comparing((OwnedGame g) -> g.name().toLowerCase());
            case "least" -> Comparator.comparingInt(OwnedGame::playtimeMinutes);
            default -> Comparator.comparingInt(OwnedGame::playtimeMinutes).reversed();
        };
        return games.stream().sorted(comparator).toList();
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
}
