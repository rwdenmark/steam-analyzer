"use strict";

const $ = (id) => document.getElementById(id);
const form = $("lookup");
const input = $("input");
const goBtn = $("go");
const statusEl = $("status");
const sortSelect = $("sort");

let allGames = [];
let currentSteamId = null;
let enriched = false;
const filters = { free: false, tools: false, played: false, underHour: false, group: false };

const COG_SVG =
  '<svg class="cog" viewBox="0 0 24 24" aria-hidden="true"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>';

form.addEventListener("submit", (e) => {
  e.preventDefault();
  const id = input.value.trim();
  if (id) analyze(id);
});

sortSelect.addEventListener("change", renderLibrary);

// Expand or collapse a grouped series (event-delegated, survives table re-renders).
$("library-body").addEventListener("click", (e) => {
  const header = e.target.closest(".group-row");
  if (!header) return;
  const open = header.classList.toggle("open");
  header.querySelector(".group-caret").textContent = open ? "-" : "+";
  document
    .querySelectorAll(`.group-member[data-group="${header.dataset.group}"]`)
    .forEach((m) => (m.hidden = !open));
});

document.querySelectorAll(".toggle").forEach((btn) => {
  btn.addEventListener("click", async () => {
    const key = btn.dataset.filter;
    const next = !filters[key];
    filters[key] = next;
    btn.classList.toggle("active", next);
    if (next && (key === "free" || key === "tools") && !enriched) {
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
    // Message only, no hideResults. The profile, toggles, and library are still valid,
    // and the user retries by pressing the toggle again.
    showErrorMessage(err.message);
    throw err;
  }
}

const DEFAULT_PROFILE = "mrzeu";
window.addEventListener("DOMContentLoaded", () => analyze(DEFAULT_PROFILE));

// Bumped on every analyze and on showError, so an in-flight renderNext from a
// run that already failed cannot re-show its card under the error banner.
let analyzeGen = 0;

async function analyze(idOrVanity) {
  setBusy(true);
  showStatus("Looking up profile...");
  hideResults();
  try {
    const profile = await getJson(`api/profile/${encodeURIComponent(idOrVanity)}`);
    currentSteamId = profile.steamId;
    enriched = false;
    resetFilters();
    renderProfile(profile);
    // The library and play-next fetches only depend on the profile, not on each other,
    // so they run in parallel and each renders as soon as its data lands.
    const gen = ++analyzeGen;
    const libraryDone = getJson(`api/profile/${encodeURIComponent(profile.steamId)}/library`)
      .then((games) => {
        allGames = games;
        renderLibrary();
        show("toggles");
      });
    await Promise.all([libraryDone, renderNext(gen)]);
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
  let data = {};
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      // A proxy 502 page is HTML, not JSON. Never surface a raw parse error.
      throw new Error("The server returned an unexpected error.");
    }
  }
  if (!res.ok) {
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
  renderService(p.createdAt);
  show("profile");
}

function yearsOfService(createdAt) {
  return Math.floor((Date.now() - createdAt * 1000) / (365.25 * 24 * 3600 * 1000));
}

// Years-of-service card next to the profile. Hidden when the profile hides its join date.
function renderService(createdAt) {
  if (!createdAt) {
    hide("service-card");
    return;
  }
  $("yos-value").textContent = yearsOfService(createdAt);
  show("service-card");
}

function round1(v) {
  return Math.round(v * 10) / 10;
}

// One card builder for both modes. Flat counts games, Group Games counts series,
// so count and never arrive pre-counted while hours always sum the visible games.
function statCards(count, countLabel, games, never) {
  const totalMinutes = games.reduce((sum, g) => sum + g.playtimeMinutes, 0);
  return [
    [count, countLabel],
    [round1(totalMinutes / 60).toLocaleString(), "Hours Played"],
    [never, "Never Played"],
    [`${count === 0 ? 0 : round1((never * 100) / count)}%`, "Backlog"],
  ];
}

// A series is unplayed when every game in it is unplayed. A singleton is unplayed when its game is.
function entryUnplayed(e) {
  return e.group ? e.members.every(isUnplayed) : isUnplayed(e.game);
}

function paintStats(cards) {
  $("stats").innerHTML = cards
    .map(([v, l]) => `<div class="stat"><div class="value">${v}</div><div class="label">${l}</div></div>`)
    .join("");
  show("stats");
}

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

async function renderNext(gen) {
  let picks = [];
  try {
    picks = await getJson(`api/profile/${encodeURIComponent(currentSteamId)}/next`);
  } catch {
    hide("next");
    return;
  }
  if (gen !== analyzeGen) return;
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

function renderLibrary() {
  $("library-table").classList.remove("loading");
  const filtered = allGames.filter(passesFilters);
  const games = sortByCurrent(filtered, (g) => g.playtimeMinutes);
  if (filters.group) {
    const entries = buildGroups(games);
    paintStats(statCards(entries.length, "Groups", filtered, entries.filter(entryUnplayed).length));
    $("library-body").innerHTML = entries.map(renderEntry).join("");
  } else {
    paintStats(statCards(filtered.length, "Games Owned", filtered, filtered.filter(isUnplayed).length));
    $("library-body").innerHTML = flatRows(games);
  }
  show("library-section");
}

function flatRows(games) {
  return games.map(gameRow).join("");
}

function gameRow(g) {
  return `<tr class="${isUnplayed(g) ? "unplayed" : ""}">
        <td>${escapeHtml(g.name)}</td>
        <td class="num">${g.playtimeHours.toLocaleString()}</td>
      </tr>`;
}

const EDITION_WORDS = new Set([
  "remastered", "remaster", "definitive", "enhanced", "complete", "deluxe",
  "goty", "hd", "redux", "anniversary", "edition", "collection", "ultimate",
]);

// The base name drops the subtitle at the colon, then strips trailing numbers, roman numerals, editions.
function seriesBase(name) {
  let s = name.toLowerCase().replace(/[™®©]/g, "");
  s = s.split(/:|\s[-–—]\s/)[0];
  const tokens = s.replace(/[^\w\s']/g, " ").split(/\s+/).filter(Boolean);
  while (tokens.length > 1) {
    const last = tokens[tokens.length - 1];
    if (EDITION_WORDS.has(last) || /^\d+$/.test(last) || /^[ivx]+$/.test(last)) {
      tokens.pop();
    } else break;
  }
  return tokens.join(" ");
}

function buildGroups(games) {
  const buckets = new Map();
  for (const g of games) {
    const key = seriesBase(g.name);
    let bucket = buckets.get(key);
    if (!bucket) buckets.set(key, (bucket = []));
    bucket.push(g);
  }
  const root = groupBases([...buckets.keys()]);
  const merged = new Map();
  for (const [base, list] of buckets) {
    const r = root.get(base);
    let arr = merged.get(r);
    if (!arr) merged.set(r, (arr = []));
    arr.push(...list);
  }
  let id = 0;
  const entries = [];
  for (const members of merged.values()) {
    if (members.length >= 2) {
      const minutes = members.reduce((sum, m) => sum + m.playtimeMinutes, 0);
      entries.push({ group: true, id: id++, members, minutes, name: groupLabel(members) });
    } else {
      entries.push({ group: false, game: members[0], minutes: members[0].playtimeMinutes, name: members[0].name });
    }
  }
  return sortByCurrent(entries, (e) => e.minutes);
}

const CONNECTORS = new Set(["of", "the", "and", "a", "an", "to", "for", "vs", "in", "on", "&"]);

// Same universe = same first two words (unless the 2nd is a connector, e.g. "Age of ...").
// Same series = one base's words sit as a contiguous run inside another's.
function groupBases(bases) {
  const parent = new Map(bases.map((b) => [b, b]));
  function find(x) {
    while (parent.get(x) !== x) {
      parent.set(x, parent.get(parent.get(x)));
      x = parent.get(x);
    }
    return x;
  }
  function union(a, b) {
    const ra = find(a), rb = find(b);
    if (ra !== rb) parent.set(ra, rb);
  }
  // Split every base once up front. Splitting inside the pairwise loop is O(n^2) splits,
  // which is millions of them on a large library.
  const words = bases.map((b) => b.split(" "));
  const byPrefix = new Map();
  for (let i = 0; i < bases.length; i++) {
    const w = words[i];
    if (w.length >= 2 && !CONNECTORS.has(w[1])) {
      const k = w[0] + " " + w[1];
      if (byPrefix.has(k)) union(bases[i], byPrefix.get(k));
      else byPrefix.set(k, bases[i]);
    }
  }
  for (let i = 0; i < bases.length; i++) {
    for (let j = 0; j < bases.length; j++) {
      if (i !== j && isContiguous(words[i], words[j])) union(bases[i], bases[j]);
    }
  }
  const root = new Map();
  for (const b of bases) root.set(b, find(b));
  return root;
}

function isContiguous(a, b) {
  if (a.length >= b.length) return false;
  for (let i = 0; i + a.length <= b.length; i++) {
    if (a.every((w, k) => w === b[i + k])) return true;
  }
  return false;
}

// One sorter for flat games and group entries, driven by the dropdown.
// minutesOf bridges the two shapes (g.playtimeMinutes vs e.minutes).
function sortByCurrent(items, minutesOf) {
  const sort = sortSelect.value;
  const copy = [...items];
  if (sort === "name") {
    copy.sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
  } else if (sort === "least") {
    copy.sort((a, b) => minutesOf(a) - minutesOf(b));
  } else {
    copy.sort((a, b) => minutesOf(b) - minutesOf(a));
  }
  return copy;
}

function renderEntry(e) {
  if (!e.group) return gameRow(e.game);
  const hours = round1(e.minutes / 60).toLocaleString();
  const header = `<tr class="group-row" data-group="${e.id}">
        <td><span class="group-caret">+</span> ${escapeHtml(e.name)}<span class="group-count">${e.members.length} games</span></td>
        <td class="num">${hours}</td>
      </tr>`;
  const members = e.members
    .map(
      (g) => `<tr class="group-member" data-group="${e.id}" hidden>
        <td class="member-name">${escapeHtml(g.name)}</td>
        <td class="num">${g.playtimeHours.toLocaleString()}</td>
      </tr>`
    )
    .join("");
  return header + members;
}

// Label = words the members' bases share up front, else the shortest base. Connectors stay low.
function groupLabel(members) {
  const bases = members.map((m) => seriesBase(m.name));
  const prefix = commonWordPrefix(bases);
  const stem = prefix || [...bases].sort(
    (a, b) => a.split(" ").length - b.split(" ").length || a.localeCompare(b)
  )[0];
  return titleCase(stem);
}

function commonWordPrefix(bases) {
  const split = bases.map((b) => b.split(" "));
  const first = split[0];
  let n = 0;
  while (n < first.length && split.every((w) => w[n] === first[n])) n++;
  return first.slice(0, n).join(" ");
}

function titleCase(s) {
  return s
    .split(" ")
    .map((w, i) => (i > 0 && CONNECTORS.has(w) ? w : w.replace(/^[a-z]/, (c) => c.toUpperCase())))
    .join(" ");
}

function resetFilters() {
  Object.keys(filters).forEach((k) => (filters[k] = false));
  document.querySelectorAll(".toggle").forEach((b) => b.classList.remove("active"));
}

function passesFilters(g) {
  // The backend classifies junk titles and tags each game, no name list here.
  if (g.junk) return false;
  if (filters.free && g.free) return false;
  if (filters.tools && isToolLike(g)) return false;
  if (filters.played && !isUnplayed(g)) return false;
  return true;
}

// "Under 1h" treats games showing under 1 hour (the rounded Hours column) as backlog.
function isUnplayed(g) {
  return filters.underHour ? g.playtimeHours < 1 : g.playtimeMinutes === 0;
}

function isToolLike(g) {
  return g.type != null && g.type.toLowerCase() !== "game";
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])
  );
}

function setBusy(busy) {
  if (busy) {
    goBtn.style.width = `${goBtn.offsetWidth}px`;  // lock the Analyze width
    goBtn.classList.add("busy");
  } else {
    goBtn.classList.remove("busy");
    goBtn.style.width = "";
  }
  goBtn.disabled = busy;
  goBtn.textContent = busy ? "Working..." : "Analyze";
}

function showStatus(msg) {
  statusEl.textContent = msg;
  statusEl.classList.remove("error");
  statusEl.hidden = false;
}

// Error message with the current results left in place, for recoverable failures.
function showErrorMessage(msg) {
  statusEl.textContent = msg;
  statusEl.classList.add("error");
  statusEl.hidden = false;
}

function showError(msg) {
  analyzeGen++;
  showErrorMessage(msg);
  hideResults();
}

function clearStatus() {
  statusEl.hidden = true;
}

function hideResults() {
  ["profile", "service-card", "stats", "next", "toggles", "library-section"].forEach(hide);
}

function show(id) { $(id).hidden = false; }

function hide(id) { $(id).hidden = true; }
