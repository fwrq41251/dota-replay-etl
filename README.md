# dota-replay-etl

Dota 2 `.dem` replay ETL built on [skadistats/clarity](https://github.com/skadistats/clarity) (v4, Source 2).
Parses a replay into plain NDJSON streams that feed a downstream analytics / LLM pipeline.

## Build

Requires JDK 17 or newer and Maven.

```
mvn -DskipTests package
```

Produces `target/dota-replay-etl-0.1.0-SNAPSHOT.jar` (shaded fat jar).

## Usage

```
dota-replay-etl analyze <matchIdOrFile> [--out DIR] [--cache DIR] [--sample SEC]
dota-replay-etl metrics <outputDir>
dota-replay-etl report <outputDir>
dota-replay-etl player-review <outputDir> <heroOrNameOrIndex>
dota-replay-etl download <matchId> [--out DIR]
```

`<matchIdOrFile>` is either a numeric match id (downloaded first) or a path to a `.dem` file.
When a local filename does not contain a match id, output is written under
`<out>/local-<replay-hash>/` instead of a shared `out/0` directory.

```
# from a local replay
java -jar target/dota-replay-etl-0.1.0-SNAPSHOT.jar analyze replays/6676393091.dem --out out

# by match id (downloads via Valve CDN, then extracts)
java -jar target/dota-replay-etl-0.1.0-SNAPSHOT.jar analyze 6676393091 --cache replays --out out

# faster state sampling
java -jar target/dota-replay-etl-0.1.0-SNAPSHOT.jar analyze replays/6676393091.dem --sample 5

# compute match metrics from an analyze result
java -jar target/dota-replay-etl-0.1.0-SNAPSHOT.jar metrics out/6676393091

# assemble a Chinese LLM review prompt (dry-run, writes prompt.md; no API call)
java -jar target/dota-replay-etl-0.1.0-SNAPSHOT.jar report out/6676393091

# assemble a single-player review prompt for one hero
# (selector: hero_key, hero name, player name, or player index)
java -jar target/dota-replay-etl-0.1.0-SNAPSHOT.jar player-review out/8943544578 slark
```

### Replay download

Replay download does **not** require a Steam API key. The match's `replay_salt` and `cluster`
are resolved through the public OpenDota API (`/api/matches/{id}`); for very recent matches
OpenDota often hasn't parsed them yet, in which case the tool requests a parse and polls until
`salt`/`cluster` appear. The replay is pulled from
`http://replay<cluster>.valve.net/570/<matchId>_<salt>.dem.bz2` and decompressed by sniffing the
magic bytes: classic replays are BZip2, newer ones are Zstandard (both supported). If
`STEAM_API_KEY` is set, resolution goes through the official `GetReplayInfo` endpoint instead.

Downloaded replays are cached under `--cache` (default `replays/`) and reused if present.
Downloads and extraction outputs are written through temporary files, so a failed run does not
replace a previously valid replay or ETL result. Re-running extraction invalidates derived metrics
and prompts for that match.

## Output layout

```
<out>/<matchId>/
  match.json         match-level facts
  combatlog.ndjson   one JSON object per combat log entry
  players.ndjson     per-player state sampled every SEC seconds of in-game clock
  metrics.json       computed metrics (from the `metrics` command)
  metrics.duckdb     same metrics as persisted DuckDB tables for ad-hoc SQL
  prompt.md          LLM review prompt (from the `report` command, dry-run)
  player-review-<hero>.md  single-player review prompt (from `player-review`, dry-run)
```

### match.json

```json
{
  "match_id": 6676393091,
  "schema_version": 2,
  "etl_version": "0.1.0-SNAPSHOT",
  "source_replay_sha256": "...",
  "map_name": "start",
  "demo_file_stamp": "TI10 ...",
  "network_protocol": 8,
  "build_num": 9358,
  "playback_ticks": 110029,
  "duration_sec": 3667.6,
  "game_start_time_raw": 740.4,
  "game_end_time_raw": 3338.4,
  "game_duration_sec": 2598.0,
  "winner_team": 3,
  "sample_interval_sec": 1,
  "combat_log_entries": 55020,
  "player_samples": 25990
}
```

### combatlog.ndjson

Every `CombatLogEntry` field, keyed by in-game time `t`. Examples of `type` values:
`DOTA_COMBATLOG_DAMAGE`, `DOTA_COMBATLOG_KILL`, `DOTA_COMBATLOG_GOLD`,
`DOTA_COMBATLOG_XP`, `DOTA_COMBATLOG_PURCHASE`, `DOTA_COMBATLOG_DEATH`, ...

Key columns: `attacker` / `target` (unit names like `npc_dota_hero_pudge`),
`inflictor` (ability or item), `value` (damage/gold/xp amount), `value_name`,
`health`, `x` / `y` (world coordinates), team ids (`attacker_team` / `target_team`),
visibility (`visible_radiant` / `visible_dire`), `last_hits`, `networth`, `gpm`, `xpm`,
`assists` (list of assisting hero names).

### players.ndjson

One row per player per sample:

```json
{
  "t": 801.4, "tick": 23881, "player": 0,
  "team": 2, "name": "xiao8", "hero": "LoneDruid",
  "level": 1, "kills": 0, "deaths": 0, "assists": 0,
  "x": -6611.0, "y": -6503.6, "z": 384.0,
  "hp": 640.0, "max_hp": 640.0, "mana": 278.9, "max_mana": 278.9,
  "items": { "slot0": "item_quellingblade", "slot3": "item_tango" }
}
```

- `t` is the **in-game clock** (same clock as `combatlog.ndjson` `t`), not the demo tick —
  a Source 2 replay shifts demo tick parity when the game starts, so sampling is anchored to
  the combat-log clock instead. A `t: 0` roster row is emitted before the game starts.
- `team`: 2 = Radiant, 3 = Dire (standard Dota ids).
- Coordinates are world units; Source 2 stores positions as `(cell, vec)` pairs and the
  conversion applied is `(cell - 128) * 128 + vec` (world origin sits at cell 128).
  Verified against combat-log locations (radiant fountain reads ≈ `(-6700, -6700)`).
- `items.slot0..5` main inventory, `6..8` backpack, `9` neutral slot; empty slots are omitted.

## Metrics

`dota-replay-etl metrics <out>/<matchId>` loads both NDJSON streams into an in-memory
DuckDB, computes the metrics below, and writes `metrics.json` plus a persistent
`metrics.duckdb` (tables: `combatlog`, `players`, `kills`, `hero_damage`).
The metrics layer subtracts `match.json.game_start_time_raw`, so all `t` values in
`metrics.json` and `metrics.duckdb` use the official game clock (`0:00` = horn; negative
values are pre-horn). The original timestamp remains available as `raw_t`. Match duration
and winner come from `CDOTAGamerulesProxy`; team scores come from each `CDOTATeam` entity's
official hero-kill counter (with final roster deaths as a fallback for older extracted data).

```json
{
  "summary": {
    "game_start_sec": 740.4, "game_end_sec": 3338.4, "duration_sec": 2597.9,
    "team_kills": [ {"team": 2, "side": "radiant", "kills": 18}, {"team": 3, "side": "dire", "kills": 28} ],
    "first_blood": { "t": 884.9, "killer": "npc_dota_hero_marci", "victim": "npc_dota_hero_lone_druid" },
    "roshan_kills": 2
  },
  "roster": [ {"player": 0, "name": "xiao8", "hero": "LoneDruid", "hero_key": "lone_druid", "team": 2,
               "side": "radiant", "level": 25, "kills": 3, "deaths": 3, "assists": 9} ],
  "kills": [ { "t": 884.9, "killer": "...", "killer_key": "marci", "victim": "...", "victim_key": "lone_druid",
               "killer_team": 3, "victim_team": 2, "location": [-6111.0, -5903.0], "victim_networth": 870,
               "assist_players": [9, 8, 5] } ],
  "teamfights": [ { "id": 0, "start": 880.0, "end": 885.0, "duration": 5.0, "hero_damage": 750, "deaths": 1,
                    "participants": ["ember_spirit", "enchantress", "lone_druid", ...] } ],
  "gold_curves": [ { "hero": "lone_druid", "points": [ {"t": 795.0, "gold": 600}, ... ] } ],
  "xp_curves": [ { "hero": "lone_druid", "points": [ {"t": 795.0, "xp": 0}, ... ] } ],
  "item_timeline": [ { "hero": "marci", "items": [ {"item": "item_orb_of_venom", "t": 827.5}, ... ] } ],
  "damage": [ { "hero": "lone_druid", "dealt_total": 25000, "taken_total": 18000,
                "per_minute": [ {"min": 13, "dealt": 800}, ... ] } ]
}
```

Notes:

- `team_kills`, `kills` and teamfight `deaths` count **hero** deaths only (the raw combat log
  also records creep / tower / neutral deaths).
- Hero keys are normalised snake_case (`npc_dota_hero_lone_druid` -> `lone_druid`) so the
  combat-log-derived sections join with `roster.hero_key`.
- `gold_curves` / `xp_curves` are cumulative sums of combat-log GOLD / XP events per hero,
  bucketed every 30 / 60 s (bucket centre time). Gold starts at 600 (starting gold).
- `item_timeline` retains every purchase event, including repeated purchases of the same item.
- `teamfights` are runs of 5-second activity buckets where
  `damage_events + 4*deaths >= 8`, where `damage_events` counts **hero-to-hero** damage
  (attacker and target are both heroes) and `deaths` are hero deaths. `hero_damage` in each
  episode counts damage dealt *by* heroes. The knobs (`BUCKET_SEC`, `WEIGHT_DEATH`,
  `MIN_ACTIVE_SCORE`) live at the top of `MetricsRunner`.
- `damage` is per-hero hero-to-hero damage: `dealt_total` (attacker is a hero), `taken_total`
  (target is a hero, any source), and `per_minute` buckets of damage dealt. Drives the
  single-player review's engagement windows.

## LLM review prompt (`report`)

`dota-replay-etl report <out>/<matchId>` reads `metrics.json` and assembles a Chinese-language
review prompt into `prompt.md` (dry-run only — it never calls an LLM). All numbers are
pre-computed by the metrics layer; the prompt explicitly forbids the model from inventing or
recalculating values, and asks it to close with an **MVP** and a **lowest role-completion** pick, each
with data-backed reasons (the lower-performer pick may be declined when evidence is insufficient).
The economy section shows the five-minute team income differential
(carry-forward of each hero's cumulative income, labelled as a trend, not a bank balance).
Copy `prompt.md` into any LLM to get the report, or paste it into a future `--api` mode.

## Single-player review (`player-review`)

`dota-replay-etl player-review <out>/<matchId> <heroOrNameOrIndex>` assembles a focused
review prompt for one hero into `player-review-<hero>.md` (dry-run). The selector matches a
roster entry by `hero_key`, hero name, player name, or player index. Beyond the match-level
metrics it adds: the hero's full purchase timeline, kills/deaths with positions, an income
comparison against the enemy team's top earner (with auto-detected overtake / stall facts),
per-minute hero damage, the fights the hero actually participated in (with kill/death outcome
per fight), and a farming/position table derived from `players.ndjson` (share of time spent in
the enemy half and deep in enemy territory per game phase, split along the river diagonal).
It also includes each death's preceding 15-second cast, control, damage-source, and last-BKB-use
evidence, plus per-fight personal damage dealt/taken. The prompt asks for a data-backed review of
出装决策 / 团战切入 / 打钱路线 / 关键决策 plus a
prioritised improvement list. Position facts are skipped when `players.ndjson` is absent.

## How extraction works

`ExtractionProcessor` is an annotation-driven clarity runner processor:

- `@OnCombatLogEntry` writes every combat log entry.
- `@OnEntityUpdated` watches `CDOTA_PlayerResource` for the selected hero handle.
  Assignment is deferred to `@OnTickEnd` because the hero entity spawns after the
  PlayerResource update within the same tick.
- `@OnTickEnd` samples all 10 players from PlayerResource (team/name/level/kda) and the hero
  entity (position, hp/mana, items), then writes one NDJSON line per player.

Field paths are resolved once against the replay's datatables (`@OnDTClassesComplete`) and
resolve to `null` (omitted) when a path does not exist in that replay's schema, so the ETL
degrades gracefully across game versions.

## Tests

```
mvn test
```

Covers coordinate conversion, hero name parsing, match-id parsing from filenames, NDJSON
writer round-trip / overwrite semantics, the DuckDB metrics computation (summary, roster,
kills, teamfights, curves, item timeline, damage) against a synthetic fixture, the match
review prompt assembly, and the single-player review prompt assembly.
