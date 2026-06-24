"use strict";

const $ = (id) => document.getElementById(id);
const form = $("lookup");
const input = $("input");
const goBtn = $("go");
const statusEl = $("status");
const sortSelect = $("sort");

// Current profile's library. Filtering and sorting are client-side. Free and Tools need
// per-app store data, fetched on demand the first time one is pressed (see ensureEnriched).
let allGames = [];
let currentSteamId = null;
let enriched = false;
const filters = { free: false, tools: false, played: false };

const COG_SVG =
  '<svg class="cog" viewBox="0 0 24 24" aria-hidden="true"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>';

form.addEventListener("submit", (e) => {
  e.preventDefault();
  const id = input.value.trim();
  if (id) analyze(id);
});

sortSelect.addEventListener("change", renderLibrary);

document.querySelectorAll(".toggle").forEach((btn) => {
  btn.addEventListener("click", async () => {
    const key = btn.dataset.filter;
    const next = !filters[key];
    filters[key] = next;
    btn.classList.toggle("active", next);

    if (next && (key === "free" || key === "tools") && !enriched) {
      // Delay the loading state so a cached (few-ms) fetch shows no flash of cogs.
      const loadingTimer = setTimeout(showLoadingState, 200);
      try {
        await ensureEnriched();
      } catch {
        clearTimeout(loadingTimer);
        filters[key] = !next;
        btn.classList.toggle("active", filters[key]);
        renderLibrary();
        return;
      }
      clearTimeout(loadingTimer);
    }
    renderLibrary();
  });
});

async function ensureEnriched() {
  if (enriched || !currentSteamId) return;
  try {
    allGames = await getJson(`api/profile/${encodeURIComponent(currentSteamId)}/library?enrich=true`);
    enriched = true;
  } catch (err) {
    showError(err.message);
    throw err;
  }
}

// Analyze the default profile on first load.
window.addEventListener("DOMContentLoaded", () => {
  const id = input.value.trim();
  if (id) analyze(id);
});

async function analyze(idOrVanity) {
  setBusy(true);
  showStatus("Looking up profile...");
  hideResults();
  try {
    const profile = await getJson(`api/profile/${encodeURIComponent(idOrVanity)}`);
    currentSteamId = profile.steamId;
    enriched = false;
    resetFilters();
    allGames = await getJson(`api/profile/${encodeURIComponent(profile.steamId)}/library`);
    renderProfile(profile);
    renderNext();
    renderLibrary();
    show("toggles");
    clearStatus();
  } catch (err) {
    showError(err.message);
  } finally {
    setBusy(false);
  }
}

async function getJson(url) {
  const res = await fetch(url);
  const text = await res.text();
  const data = text ? JSON.parse(text) : {};
  if (!res.ok) {
    // The backend returns { message } for handled errors; fall back to the status line.
    throw new Error(data.message || `${res.status} ${res.statusText}`);
  }
  return data;
}

function renderProfile(p) {
  $("avatar").src = p.avatarUrl || "";
  const persona = $("persona");
  persona.textContent = p.personaName || "(no name)";
  persona.href = p.profileUrl || "#";
  $("steamid").textContent = `SteamID ${p.steamId}`;
  show("profile");
}

// Stats track the filtered games. Mirrors the backend: hours rounded to one decimal.
function statsFor(games) {
  const total = games.length;
  const totalMinutes = games.reduce((sum, g) => sum + g.playtimeMinutes, 0);
  const never = games.filter((g) => g.playtimeMinutes === 0).length;
  return {
    totalGames: total,
    totalHours: round1(totalMinutes / 60),
    neverPlayedCount: never,
    neverPlayedPct: total === 0 ? 0 : round1((never * 100) / total),
  };
}

function round1(v) {
  return Math.round(v * 10) / 10;
}

function statCards(s) {
  return [
    [s.totalGames, "Games Owned"],
    [s.totalHours.toLocaleString(), "Hours Played"],
    [s.neverPlayedCount, "Never Played"],
    [`${s.neverPlayedPct}%`, "Backlog"],
  ];
}

function paintStats(cards) {
  $("stats").innerHTML = cards
    .map(([v, l]) => `<div class="stat"><div class="value">${v}</div><div class="label">${l}</div></div>`)
    .join("");
  show("stats");
}

// Spinning cogs while the on-demand store details load.
function showLoadingState() {
  paintStats([
    [COG_SVG, "Games Owned"],
    [COG_SVG, "Hours Played"],
    [COG_SVG, "Never Played"],
    [COG_SVG, "Backlog"],
  ]);
  $("library-table").classList.add("loading");
  $("library-body").innerHTML =
    `<tr><td colspan="2" class="lib-loading">${COG_SVG}<span>Loading</span></td></tr>`;
  show("library-section");
}

// Two random never-played picks, refreshed each load. Junk titles (see isJunkTitle) are skipped.
function renderNext() {
  const candidates = allGames.filter(
    (g) => g.playtimeMinutes === 0 && !isJunkTitle(g.name)
  );
  const picks = shuffle(candidates).slice(0, 2);
  if (picks.length === 0) {
    hide("next");
    return;
  }
  $("next-list").innerHTML = picks
    .map(
      (g) => `<div class="next-item">
        <img class="next-img" src="${g.imageUrl || "favicon.svg"}" alt=""
             onerror="this.onerror=null;this.src='favicon.svg';" />
        <div class="next-name">${escapeHtml(g.name)}</div>
      </div>`
    )
    .join("");
  show("next");
}

function isJunkTitle(name) {
  const n = name.toLowerCase();
  return n.includes("public") || n.includes("test") || n.includes("server") || n.includes("unstable");
}

function shuffle(arr) {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

function renderLibrary() {
  $("library-table").classList.remove("loading");
  const filtered = allGames.filter(passesFilters);
  paintStats(statCards(statsFor(filtered)));
  const games = sortGames(filtered);
  const rows = games
    .map(
      (g) => `<tr class="${g.playtimeMinutes === 0 ? "unplayed" : ""}">
        <td>${escapeHtml(g.name)}</td>
        <td class="num">${g.playtimeHours.toLocaleString()}</td>
      </tr>`
    )
    .join("");
  $("library-body").innerHTML = rows;
  show("library-section");
}

function resetFilters() {
  Object.keys(filters).forEach((k) => (filters[k] = false));
  document.querySelectorAll(".toggle").forEach((b) => b.classList.remove("active"));
}

function passesFilters(g) {
  if (filters.free && g.free) return false;
  if (filters.tools && isToolLike(g)) return false;
  if (filters.played && g.playtimeMinutes > 0) return false;
  return true;
}

// Hides non-games (store type known and not "game"), plus test/server titles.
function isToolLike(g) {
  if (g.type && g.type.toLowerCase() !== "game") return true;
  const n = g.name.toLowerCase();
  return n.includes("test") || n.includes("server");
}

function sortGames(games) {
  const sort = sortSelect.value;
  const copy = [...games];
  if (sort === "name") {
    copy.sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
  } else if (sort === "least") {
    copy.sort((a, b) => a.playtimeMinutes - b.playtimeMinutes);
  } else {
    copy.sort((a, b) => b.playtimeMinutes - a.playtimeMinutes);
  }
  return copy;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])
  );
}

function setBusy(busy) {
  goBtn.disabled = busy;
  goBtn.textContent = busy ? "Working..." : "Analyze";
}

function showStatus(msg) {
  statusEl.textContent = msg;
  statusEl.classList.remove("error");
  statusEl.hidden = false;
}

function showError(msg) {
  statusEl.textContent = msg;
  statusEl.classList.add("error");
  statusEl.hidden = false;
  hideResults();
}

function clearStatus() {
  statusEl.hidden = true;
}

function hideResults() {
  ["profile", "stats", "next", "toggles", "library-section"].forEach(hide);
}

function show(id) { $(id).hidden = false; }
function hide(id) { $(id).hidden = true; }
