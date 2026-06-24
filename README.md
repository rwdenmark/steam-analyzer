# Steam Analyzer

A Spring Boot REST service that takes a Steam profile, pulls the user's library and
playtime from the Steam Web API, and serves a backlog dashboard: what they own, what
they have barely touched, and a "what to play next" pick. Same aggregate-a-third-party-API
shape as [`wow-explorer`](https://github.com/rwdenmark/wow-explorer), different game.

The backend and a single static dashboard ship in one runnable jar. No database is
required for v1. Responses are cached in memory, so repeated lookups do not re-hit Steam.

## Stack

Java 21, Spring Boot 3.5 (Web, Cache), Caffeine for in-memory caching, Spring `RestClient`
for the Steam calls. Frontend is a single static page (plain HTML/CSS/JS) served from
`src/main/resources/static`.

## Prerequisites

- JDK 21
- A free Steam Web API key from https://steamcommunity.com/dev/apikey
- The target Steam profile must have **Game details** privacy set to **Public**, or the
  library comes back empty.

## Run it locally

1. Copy the env template and add your key.

   ```
   cp .env.example .env
   # edit .env and set STEAM_API_KEY
   ```

2. Start the app (it reads `.env` from the project root).

   ```
   ./mvnw spring-boot:run
   ```

   Or with a system Maven: `mvn spring-boot:run`. You can also export the key instead of
   using a file: `STEAM_API_KEY=xxxx mvn spring-boot:run`.

3. Open http://localhost:8080 and enter a vanity name or a 17-digit SteamID.

## API

| Method & path | Returns |
|---|---|
| `GET /api/profile/{idOrVanity}` | Profile identity plus computed backlog stats |
| `GET /api/profile/{idOrVanity}/library?sort=playtime\|least\|name` | Owned games with playtime, each tagged with store type and free flag |
| `GET /api/profile/{idOrVanity}/next` | Up to two never-played games to start next |
| `GET /api/health` | Liveness probe |

`{idOrVanity}` accepts either the name from `steamcommunity.com/id/NAME` or a 17-digit
SteamID64. Numeric input skips the vanity-resolve call.

### Computed stats

Total games owned, total hours played, never-played count and percent, a backlog score
(never-played / total), the top five most-played, and up to two play-next
recommendations. Recommendations are the alphabetically first two never-played actual
games (non-game apps such as tools are skipped), a deterministic choice that is stable
across calls.

### Dashboard filters (toggles)

The library table has three client-side toggles, so they apply instantly with no refetch:

- **Free** hides free-to-play games (uses the store `is_free` flag).
- **Tools** hides anything that is not a game (dlc, tools, soundtracks, etc.).
- **Played** hides games with any recorded playtime, leaving only the backlog.

Sort options are most-played, least-played, and name.

Free and Tools rely on each app's store metadata, which `GetOwnedGames` does not provide.
To keep the initial load fast, that data is fetched lazily: the dashboard loads the
library without it, and only the first time you press Free or Tools does it request
`/library?enrich=true`, which enriches every game with its `type` and `is_free` from the
public store `appdetails` endpoint. Those lookups are cached per appid for a week, so the
wait happens once, and a lookup that fails leaves the game unenriched and never hidden.
The Played toggle and all sorting need no extra data and are always instant.

## Error handling

| Situation | Response |
|---|---|
| Vanity name resolves to nothing | `404` with a message telling the user to check the name |
| Profile exists but Game details are private | `403` explaining how to make it Public (never an empty library) |
| Steam times out, rate-limits (429), or returns 5xx | `502` with a friendly message |
| Input is already a 17-digit SteamID | resolve step is skipped |

## Caching

Caffeine, configured in `CacheConfig`:

- vanity name to SteamID64, 24h TTL (resolutions are stable)
- owned-games response, 5m TTL, shared by the library and next endpoints
- assembled profile + stats, 5m TTL
- store appdetails (type / free flag) per appid, 7-day TTL (app type is effectively static)

## Tests

```
./mvnw test
```

`AnalyzerServiceTest` covers the stat math as a pure function (no network).
`AnalyzerServiceResolutionTest` covers vanity-vs-id branching, vanity miss, and the
private-profile path with the Steam client mocked. `ProfileControllerTest` is a WebMvc
slice test asserting the JSON and the 404/403 error mappings.

## Deploy

Build a jar with `./mvnw clean package` and run it with `STEAM_API_KEY` set, or use the
included `Dockerfile`:

```
docker build -t steam-analyzer .
docker run -p 8080:8080 -e STEAM_API_KEY=your_key steam-analyzer
```

Hosts that inject `$PORT` (and the free OCI A1 box) work without changes.
