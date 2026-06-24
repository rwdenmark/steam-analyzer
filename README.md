# Steam Analyzer

A Spring Boot REST service that takes a Steam profile, pulls the library and playtime from
the Steam Web API, and serves a backlog dashboard of what you own and have not played, plus
a play-next pick. Same aggregate-a-third-party-API shape as
[`wow-explorer`](https://github.com/rwdenmark/wow-explorer), different game.

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
| `GET /api/profile/{idOrVanity}` | Profile identity (name, avatar, resolved SteamID64, account creation time) |
| `GET /api/profile/{idOrVanity}/library?sort=playtime\|least\|name` | Owned games with playtime, each tagged with store type and free flag |
| `GET /api/profile/{idOrVanity}/next` | Up to two random never-played games to start next, reshuffled each call |
| `GET /api/health` | Liveness probe |

`{idOrVanity}` accepts either the name from `steamcommunity.com/id/NAME` or a 17-digit
SteamID64. Numeric input skips the vanity-resolve call.

### Dashboard stats

The dashboard computes its summary in the browser from the library: games owned, hours
played, never-played count and percent, and a backlog percentage. They recompute as you
toggle filters, so they track the visible set. A Years of Service card by the profile shows
the account age when Steam exposes the creation time. Play-next is a separate endpoint that
returns up to two random never-played games, reshuffled each call, with non-game apps and
junk titles skipped.

### Dashboard filters (toggles)

The library table has three client-side toggles, so they apply instantly with no refetch:

- **Free** hides free-to-play games (uses the store `is_free` flag).
- **Tools** hides non-game store types (dlc, tools, soundtracks, etc.) using each app's `type`.
- **Played** hides games with any recorded playtime, leaving only the backlog.
- **Under 1h** counts games showing under 1 hour in the Hours column as backlog, so the backlog stats include them and, with **Played** active, they stay in the library instead of being hidden.
- **Group Games** collapses likely sequels into one expandable row in the library, matched on a shared base name (subtitle after a colon dropped; trailing numbers, roman numerals, and edition words stripped) and summing their playtime. While active, the stat cards switch to series: Groups (total series), Never Played (fully unplayed series), and Backlog (% of series untouched).

Sort options are most-played, least-played, and name.

Entries whose name marks them as non-games (test, server, dedicated, uploader, public,
unstable, beta, or staging builds) are filtered from the library on load and never shown, no toggle needed.
The same name rule (`isJunkTitle`) drives the backend Play Next pick, so the two stay in
sync.

Free and Tools need each app's `type` and `is_free`, which `GetOwnedGames` omits. That data
is fetched lazily: the dashboard loads the library without it, and the first press of Free or
Tools requests `/library?enrich=true` to pull those fields from the public store `appdetails`
endpoint. That endpoint has no batch form and rate-limits near 200 requests per five minutes,
so lookups run six at a time, capped at 200 per request, cached per appid for a week. A
failed lookup, or a game past the cap, stays unenriched and is never hidden. Played and
sorting need no extra data, so they stay instant.

## Error handling

| Situation | Response |
|---|---|
| Vanity name resolves to nothing | `404` with a message telling the user to check the name |
| Profile exists but Game details are private | `403` explaining how to make it Public (never an empty library) |
| Steam times out, rate-limits (429), or returns 5xx | `502` with a clear message |
| Input is already a 17-digit SteamID | resolve step is skipped |

## Caching

Caffeine, configured in `CacheConfig`:

- vanity name to SteamID64, 24h TTL (resolutions are stable)
- owned-games response, 5m TTL, shared by the library and next endpoints
- resolved profile identity, 5m TTL
- player summary (name, avatar) per SteamID64, 5m TTL
- store appdetails (type / free flag) per appid, 7-day TTL

## Tests

```
./mvnw test
```

`AnalyzerServiceTest` covers the play-next candidate filter as a pure function (no network). `AnalyzerServiceResolutionTest` covers vanity-vs-id branching,
vanity miss, the private-profile path, the play-next pick, the enrich path, and the
200-lookup cap with the Steam clients mocked. `SteamClientTest` and `SteamStoreClientTest`
use `MockRestServiceServer` to run real Steam-shaped JSON through the map parsing: the
vanity success/no-match codes, the response-envelope unwrapping, the timeout and
error-to-502 mapping, and the appdetails type/free extraction with its `unknown()`
fallbacks. `ProfileControllerTest` is a WebMvc slice test asserting the JSON and the
404/403 error mappings.

## Deploy

Build a jar with `./mvnw clean package` and run it with `STEAM_API_KEY` set, or use the
included `Dockerfile`:

```
docker build -t steam-analyzer .
docker run -p 8080:8080 -e STEAM_API_KEY=your_key steam-analyzer
```

Hosts that inject `$PORT` (and the free OCI A1 box) work without changes.
