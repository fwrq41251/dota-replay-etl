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
dota-replay-etl pipeline <matchIdOrFile> [--out DIR] [--cache DIR] [--sample SEC]
                          [--report] [--player-review HERO]
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

# extract + metrics in one step
java -jar target/dota-replay-etl-0.1.0-SNAPSHOT.jar pipeline replays/6676393091.dem --out out

# extract + metrics + assemble the match prompt (and one player review)
java -jar target/dota-replay-etl-0.1.0-SNAPSHOT.jar pipeline replays/6676393091.dem --report --player-review slark

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
  wards.ndjson       one lifecycle row per observer/sentry ward
  metrics.json       computed metrics (from the `metrics` command)
  metrics.duckdb     same metrics as persisted DuckDB tables for ad-hoc SQL
  prompt.md          LLM review prompt (from the `report` command, dry-run)
  player-review-<hero>.md  single-player review prompt (from `player-review`, dry-run)
```

### match.json

```json
{
  "match_id": 6676393091,
  "schema_version": 4,
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
  "player_samples": 25990,
  "ward_lifecycles": 72
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
  "items": { "slot0": "item_quelling_blade", "slot3": "item_tango" }
}
```

- `t` is the **in-game clock** (same clock as `combatlog.ndjson` `t`), not the demo tick —
  a Source 2 replay shifts demo tick parity when the game starts, so sampling is anchored to
  the combat-log clock instead. A `t: 0` roster row is emitted before the game starts.
- `team`: 2 = Radiant, 3 = Dire (standard Dota ids).
- Coordinates are world units; Source 2 stores positions as `(cell, vec)` pairs and the
  conversion applied is `(cell - 128) * 128 + vec` (world origin sits at cell 128).
  Verified against combat-log locations (radiant fountain reads ≈ `(-6700, -6700)`).
- `items` is a convenience map of resolved canonical names, not completeness evidence. Schema 4
  resolves names through `m_pEntity.m_nameStringTableIndex` into `EntityNames`, not DTClass lowercase.
- `equipment` is the authoritative sampled inventory package: `observed_raw_t`, `source`,
  `clock_source`, `status` (`complete`/`partial`/`missing`), selected `hero_handle`, `slot_count`,
  and one `slots` entry per actual `m_hItems` array element. Each slot retains `slot`, `region`,
  raw `handle`, `entity_uid`, canonical `item`, `dt_class`/`name_index` when resolved, and
  `status` (`empty`/`occupied`/`unknown`). Missing handles/entities/names are not empty slots.
- Equipment is read only from the current PlayerResource-selected active hero entity, never by
  searching same-name heroes/illusions. `observed_raw_t` is the latest combat-log clock at the
  observation tick, **not** the older scheduled row `t`. The existing player row schedule is retained
  for other metrics; no equipment state is backdated to that schedule. It is still a tick-end
  observation clock, not sub-tick event ordering or an exact delivery timestamp.
- The audited build 10836 has **25** elements, slots **0..24**, not 12: `0..5` main, `6..8`
  backpack, `9..14` stash, `15` teleport, `16` neutral, `17` neutral_enhancement. `18..24` have
  unknown semantics. Other builds retain raw indices/names but all regions stay `unknown` until
  their layouts are audited. The old documentation's `slot9 = neutral` was incorrect.

### wards.ndjson

One row per observer or sentry ward, sourced from the actual ward entity rather than purchase
events. Each row includes `ward_id`, `type`, owner `team` / `player`, `placed_t`, coordinates,
and (when removed) `removed_t` / `lifetime_sec`. `removal_reason` is `destroyed`, `expired`,
`unknown`, or `active_at_end`. Destruction/expiry is correlated with the ward death combat-log
entry; `destroyer` and `destroyer_team` are retained for deward attribution. Timestamps are raw
game timestamps in this extraction stream and are normalized to horn-relative time by metrics.

## Metrics

### Schema 16 Equipment Evidence

Equipment is evidence about potential power windows, **not a computed combat-strength score**.
Purchasing, observing inventory, carrying, entering the main inventory and being able to operate
an item are separate facts. CLI options have not changed.

#### Position Clock Correction

Recompute `metrics`, `report` and `player-review` for schema-4 extraction after this correction;
no replay re-extraction is needed. Earlier schema-16 metrics used the scheduled player `t` for
local ITEM positions and member-event locations, even though the coordinates came from tick-end
`equipment.observed_raw_t`. That could attach future coordinates and understate sample age.

`PlayerObservationClock` now supplies both local member locations (target and attacker presence)
and equipment-use positions with `observed_raw_t - game_start_time_raw`. As-of eligibility, output
sample time and age all use this actual clock. Scheduled labels are used **only** to locate
unknown-clock blockers; selection is ordered by replay tick before validating clock, identity,
HP and coordinates. Missing/nonfinite clocks never become positions or get filtered out before
latest-row selection. A known future observation is excluded; an older genuine as-of observation
may still be used if fresh enough. Equipment windows use the same raw-clock normalization.
The shared clock does not require complete inventory contents to establish a position timestamp.

Local events retain `sample_tick` and `attacker_sample_tick`; uses retain `position_tick` and
`position_clock_source`. Limits remain 5 seconds inclusive. A missing actual clock in older
extraction now leaves local interactions unlocated and local equipment-use attribution unknown,
instead of treating a planned label as observed evidence. Other global metrics/death classification
have not been rewritten by this correction. Local membership and IDs may change: regenerate all
derived artifacts together, rather than joining IDs from before and after recomputation.

Independent audit on `8987316716`: future local-use positions **22 -> 0** (old maximum lead
`0.200010` seconds); local uses **360 -> 359**, death uses unchanged at **102**, total uses
**462 -> 461**. Of those 22 formerly future-backed events, 21 retain a valid older-position
association; event `13653` no longer has a local-use association. Local clusters **182 -> 183**,
located member events **6185 -> 6187**, unlocated events **3 -> 1**, equipment windows **641 -> 644**.
Changing the clock can recover formerly stale samples as well as reject future ones, so located
counts need not decrease. All retained use positions and both member-position roles resolved to
raw player ticks with exact actual timestamps, no future samples and correct ages. Eight local/
equipment JSON arrays matched DuckDB row-for-row. Raw extraction hashes were unchanged.

#### Migration

Reports reject metrics versions other than 16, including incomplete version 16 files missing
the equipment arrays. Run `metrics` again before `report` / `player-review`. Existing extraction
schema 3 data is supported **only with unknown equipment**: its lossy `items` names, omitted
generic items and truncated slots cannot be repaired using purchases. No automatic renaming,
delivery inference, default empty inventory or zero cooldown is applied to old data.

To obtain the new evidence, re-extract a **local** replay into a separate output root first:

```sh
mvn -q verify
java -jar target/dota-replay-etl-0.1.0-SNAPSHOT.jar pipeline replays/8987316716.dem \
  --out out/equipment-validation --report --player-review nevermore
```

This produces extraction schema 4 and metrics schema 16 at
`out/equipment-validation/8987316716/`, leaving `out/8987316716/` intact. No download is required.
After inspecting the independent result, either use that directory or explicitly re-run extraction
at the desired output root. Re-extraction there invalidates its old metrics/prompts. Recompute
downstream artifacts together; do not mix sample/event IDs across generations. A schema-4 result
needs only `metrics`, then `report` / `player-review`, for subsequent metric/report-only updates.

#### Contracts

- `item_timeline` still preserves **every PURCHASE**, including identical repeats. It is not
  linked one-to-one to inventory entities: recipes, combinations, transfers and consumable stacks
  make that inference unsafe. Raw `combatlog.event_id` retains the underlying event evidence.
- `equipment_samples` includes all roster-player samples, with `sample_id`, `previous_sample_id`,
  `player`, `hero_key`, `team`, `tick`, `raw_sample_t`, horn-normalized `sample_t`, source/clock,
  slot count and completeness. JSON and DuckDB store the same rows. For compactness only
  occupied/unknown slot entries are retained here; explicit empty entries remain in raw extraction.
  Omitted slots mean empty **only in a complete sample with a validated slot count**.
- `equipment_changes` compares the same player's immediately adjacent samples by `entity_uid`.
  At most 5 seconds apart and both complete: `appeared_observed`, `slot_moved_observed`, or
  `disappeared_observed`. Otherwise differences are `comparison_unknown`. `from_slot`, `to_slot`,
  both sample IDs/times and observed gap are retained. Disappearance means not observed in the
  selected hero's inventory, **not sold**: courier transfer, ground drop, consumption, combination,
  destruction, or other transitions are not distinguished. Counter-only updates are in snapshots,
  not inventory change rows. No sample creates a sale event.
- `equipment_first_observations` is per player and canonical item **type**, not per purchase:
  `inventory` includes stash/unknown slots; `carried` includes main/backpack/TP/neutral/enhancement
  but excludes stash/unknown slots; `main` means slots classified as main. `sample_t` is the first
  observed upper endpoint. `lower_bound_t` is the previous complete sample only if at most 5 seconds
  earlier; otherwise it and `uncertainty_sec` are null (unbounded, not zero). These bounds describe
  the first **observed transition**, not proof that the item never existed between earlier samples.
  Sampling may miss short-lived items and rapid slot swaps. Partial samples can establish presence
  of a resolved item but never absence of unresolved items.
- `equipment_windows` keys death evidence by `(context_type='death', context_id=kill_id, hero_key)`
  and local evidence by `('local_fight', fight_id, hero_key)`. The anchors are death time and local
  fight start respectively. Select the latest snapshot at or before the anchor **before** checking
  completeness and a maximum age of 5 seconds (inclusive). No future snapshots or fallback behind
  a newer incomplete/identity-unknown/clock-unknown snapshot. Stale/missing/partial data has null
  window slots, retains available sample metadata, and never becomes an empty inventory assertion.
  Observation order uses replay tick, so a newer missing-clock record cannot be bypassed just
  because its scheduled label precedes an older record's actual observation clock. For missing or
  nonfinite observation clocks the scheduled label is retained only to locate an unknown blocker,
  not as a valid equipment observation or reliable age.
  Death incidents also embed their matching window as `incidents.deaths[].equipment`.
- Local equipment contexts require membership with `presence_events > 0`; credited-only illusion
  owners have no body equipment context. Equipment at the start does not imply the hero was present
  for the entire fight, or that equipment stayed unchanged until its end.
- `equipment_uses` links only real hero ITEM events with `roster_team_match` or verified non-illusion
  `hero_action_identity`. Death bounds are `[death-15, death]`; local bounds are `[start, end]`.
  Local uses additionally require that member's latest living, valid position at most 5 seconds old,
  within 2400 units of a member event involving that body and at most 5 seconds from that event.
  `member_event_id`, position sample/time/age/coordinates, raw/normalized action time, event ID,
  actual attacker and attribution/association source are retained. No full-map time-only join.
  These are associated action records, not extra clustering seeds or additional damage members.
  **No recorded use does not mean non-use**, and no successful cast/cancel analysis is claimed.
- `availability` is always `unknown`. `cooldown_remaining_sec` (`m_fCooldown`, seconds remaining
  at the sample, not an absolute expiry time) and `item_charges` (`m_iCurrentCharges`, item count,
  not a universal number of casts) are provided only for BKB and Magic Wand on audited build 10836.
  Other items/builds, missing fields, negative sentinels and nonfinite cooldowns remain null.
  BKB's item count 0 is a real observed value, not evidence it needs charges or cannot be cast.
  Cooldown 0 is **not** operational availability: disables/mute/silence interactions, death,
  mana, backpack reactivation, targets and other restrictions are not combined into an eligibility
  model. Counters are not extrapolated from an old sample to the anchor. Ability-charge restoration,
  secondary charges, frozen cooldown and backpack-enable fields are **not provided as interpreted metrics**.
  `parameters.equipment` records limits, replay/audited build, counter item scope, source and units.

#### Replay Audit

Audited locally on 2026-09-08: match `8987316716`, build `10836`, replay SHA-256
`e1a3ab5b048ba9c4fa855bc412a80e38649af218003856818a97e2eb7cadccd0`, horn offset `231.4`.
The initial inventory audit below preceded the position-clock correction; its local IDs/counts
are historical. Corrected local counts and clock guarantees are listed above.

- Datatable/entity inspection reported `m_hItems = 25`; querying paths beyond 24 returned null
  values despite resolvable field paths, so field-path existence alone is not used as array length.
  Empty Source-2 handles were `16777215`. EntityNames mapped `CDOTA_Item_BlinkDagger` to `item_blink`,
  `CDOTA_Item_IronwoodBranch` to `item_branches`, and `CDOTA_Item_QuellingBlade` to `item_quelling_blade`.
  Generic `CDOTA_Item` had an unresolved name index at creation in the probe; no named generic item
  appeared in this replay's player snapshots. It now follows the same name-table path, never a DT
  fallback, and remains unknown if the table cannot identify it.
- Actual occupied indices included 0..11 and 15..17. Slots 6..8 showed repeated swaps with 0..5
  (backpack/main); the script inventory enum places the six stash slots at 9..14
  ([DOTAScriptInventorySlot_t](https://docs.moddota.com/lua_server_enums/#dotascriptinventoryslot_t)).
  Slot 9 held shop recipes and Blink before disappearing from stash and appearing later in main.
  All 20,919 occupied slot-15 samples were `item_tpscroll`. Slots 16/17 each had 16,444 occupied
  samples; entity updates independently showed `m_bIsNeutralActiveDrop=true` for slot-16 items
  (e.g. Chipped Vest/Weighted Dice) and `m_bIsNeutralPassiveDrop=true` for slot-17 enhancements.
  Those flags name neutral categories, **not whether the item has an active cast**. Slots 18..24
  stayed empty and are not assigned invented roles.
- Legion Commander Blink: PURCHASE `871.3667` (event `18060`), first inventory observation in
  stash slot 9 at `871.9`, disappeared from selected inventory at `873.7334`, first main/carried
  observation in slot 0 at `890.8001`. Previous complete sample `889.7667`, observed interval
  `1.0334` seconds. Windrunner Blink: purchase `884.3334`, stash `885.0`, main slot 2 `909.7667`.
  The gaps are observable; a courier route or exact delivery is not inferred.
- Nevermore BKB: purchase `1469.9667` (event `38420`), first main slot 3 observation `1470.8001`
  after complete sample `1469.7334` (interval `1.0667` seconds), recorded use `1473.1334`
  (event `38656`) linked to local fight `133`. That fight started at `1462.0001`; its equipment
  snapshot is `1461.8001`, age `0.2`, **without BKB**. Later possession/use is not backfilled into
  the start snapshot. Nevermore death `37` at `1256.7667` uses sample `1256.7334`, age `0.0333`.
- Counter probe for that BKB: raw times `1704.0334`, `1705.0334`, `1706.0334` had `m_fCooldown`
  `0`, `94.40001`, `93.400024`, around ITEM event raw `1704.5334`. Magic Wand owner ID 6
  had charges `10 -> 0` from raw `1703.0001 -> 1704.0334`, with cooldown `0 -> 14.699999`,
  then `13.699995` one second later. `m_fAbilityChargeRestoreTimeRemaining=-1000000` and
  `m_bItemEnabled=false` even around recorded uses demonstrate why those names/zeros/flags are
  not blindly interpreted as cast eligibility. Owner IDs here are entity owner IDs, not roster indices.
- Independent output contained 24,500 equipment samples (23,344 complete, 1,156 identity-unknown
  early roster samples), 1,275 inventory changes, 801 first-observation rows, 641 equipment
  windows and 462 associated use rows (one event can belong to a death and a local context).
  There were 193 slot moves and 494 observed disappearances, **zero inferred sales**.
  Five equipment tables were compared row-for-row and field-for-field with JSON, including nulls
  and nested slots. No window belonged to a credited-only local member. Existing 79 scored deaths
  and one Aegis respawn remained classified separately. Combatlog and wards were byte-identical
  to the original extraction; player rows matched exactly after excluding equipment/items. Original
  `out/8987316716` match/player/combat/metrics JSON/DB hashes were unchanged.

### Schema 15 Local Interactions

Historical migration to schema 15 required only `metrics`, `report` and `player-review` on existing
extraction output. For current schema 16 equipment evidence, use the migration instructions above.
CLI arguments are unchanged. Death classification and attribution rules from schema 14 remain intact.

- `local_fights` is a new layer, separate from the unchanged `teamfights` global activity windows.
  It includes single-hit harassment, not just elevated-activity episodes. Candidate events are
  positive enemy damage from hero units to verified real hero targets, and hero deaths other than
  known illusion deaths (including non-player last hits). Creep/tower damage does not seed clusters;
  heals, casts and modifiers do not currently seed clusters either.
- Event locations use the verified real target's latest player sample at or before the event,
  no older than 5 seconds, matched by hero and team. Damage requires a living target sample. Nonfinite
  and `(0,0)` positions are rejected. Both target and attacker select the latest sample **before**
  validating HP/coordinates: a newer dead or invalid sample blocks fallback to an older valid one.
  DEATH targets may have zero HP but still need valid coordinates. Combat-log coordinates are deliberately not used here because
  the extraction does not establish a general target-position contract for them. Missing, stale or
  identity-unknown targets remain explicit unlocated `local_fight_events` with null `fight_id`/position;
  they are not silently dropped or attached through time alone.
- Events sorted by `(t,event_id)` join the first compatible cluster: a verified real body must be
  shared with a member at most 5 seconds earlier, total cluster duration must be at most 30 seconds,
  and **every** pair of member target positions must be within 2400 world units. Bounds are inclusive.
  Clusters are never unioned via a bridging hero. This intentionally splits long or moving fights
  rather than allowing continuous harassment or a shared hero to connect distant locations forever.
- `credited_attacker_key`/`attribution_source` describe output ownership, not presence. Attacker
  presence additionally requires direct real-hero attribution and a living as-of sample within
  2400 units of the event target. Illusion owners are never given presence or used as connecting
  bodies merely because their illusions deal damage. Target/attacker sample times, ages, coordinates,
  presence keys, actual unit names, `event_id` and `kill_id` are retained for tracing to raw DB events.
- `local_fight_players` aggregates only member events: output, damage taken, kills, deaths and
  presence evidence counts. There is no full-map time join for personal participation or deaths.
  `scored_deaths`, `unknown_deaths` and `aegis_respawns` are separate; `deaths` includes the first two
  only. An Aegis event can be a member interaction but never a scored death or personal kill.
  Attacker kills require opposing legal teams 2/3; denies and suicides retain the victim death
  without crediting a personal kill. This does not change the legacy match-wide `kills` list.
- Centers are event-weighted target-position means; min/max x/y bound those observations, not all
  hero bodies. `kind` is a presence-count heuristic: at least 3v3 => `teamfight`; a verified death
  with 1v2+ => `pickoff`; other 3+ bodies with both sides => `skirmish`; both sides and no deaths
  => `harassment`; otherwise `unknown`. These labels do not establish intent or complete attendance.
- Local `economy_status` is `unknown_no_spatial_causal_attribution`. No full-team simultaneous income
  is displayed as local profit, and no participant-window income is fabricated. Existing global-window
  economy remains compatibility/comparison data only. Death-follow-up objectives remain temporal
  associations, never automatic causal conversions.
- JSON arrays and DuckDB tables `local_fights`, `local_fight_events`, `local_fight_players` contain
  the same rows and fields. Stable ordering uses fight ID, `(t,event_id)`, and `(fight_id,hero_key)`
  respectively. Combat event IDs now use canonical content ordering (exact duplicates are interchangeable);
  IDs should not be mixed across schema generations. Parameters are in `parameters.local_fights`.

### Schema 13 Semantic Corrections

Schema 14 tightens these rules after review: missing illusion flags no longer establish real-unit
identity (including actions and control recipients). Death uniqueness ignores known illusions and
other-team copies. Income comparisons require coverage of every relevant roster hero and an
available sample at the compared time; before the first sample or more than 60 seconds after the
latest sample, values are unknown, not zero. Opponent ranking uses a common comparison time and
requires the entire enemy roster. Missing last-hit/deny counters no longer discard income samples,
and missing counters remain unknown in JSON/reports. Recompute metrics to replace schema 13 output.

Re-run `metrics` and then `report` / `player-review` on existing extraction output to migrate.
Reports reject missing, older and newer metrics schema versions, even without a replay hash.
No replay re-extraction is required. DuckDB records its version in `metric_metadata`.

- `attacker` / `attacker_key` in DuckDB retain the actual unit identity. Personal attribution uses
  `credited_attacker_key`; JSON kills expose `attacker_unit`, `killer_key` and `attribution_source`.
  A direct hero needs a matching roster team. An illusion needs matching source/team evidence;
  enemy hero copies are attributed only for the explicitly supported Dark Seer source rule.
  Other `damage_source` values are not assumed to be owners. Unverifiable attribution stays unknown.
  Personal damage, kill and activity-window statistics share these rules; illusion activity does
  not prove that the owner's real hero was present. Damage to known illusions is not player damage.
- `hero_death_events` retains all raw hero DEATHs with stable IDs and classification evidence.
  `scored_death` requires a single event and a player death-counter increment, comparing a living
  sample within 5 seconds before the event with the first sample within 5 seconds after it.
  `aegis_respawn` additionally requires a living Aegis holder, HP zero with Aegis gone after DEATH,
  and a living sample within 10 seconds with the same death counter and no Aegis. It is excluded
  from `kills`, death incidents and activity-window death exchanges, but remains in JSON and DuckDB.
  Known illusion deaths are likewise separated. Missing or ambiguous evidence is `unknown`, retained
  in death lists/window counts and explicitly not claimed to be verified scoreboard deaths.
- Summary reconciliation distinguishes official team counters, final personal kill sums, raw death
  classifications and non-player last hits. Tower kills need not increment any player's kill count.
- The raw combat-log `networth` is retained as `raw_networth` in JSON, not `victim_networth`;
  neither victim nor killer net worth is inferred from it.
- `gold_curves` is a signed GOLD-event cumulative sum, not cumulative income or net worth.
  Reports use authoritative `farm_curves.total_earned_gold` for income comparisons and do not infer
  an income stall from negative GOLD events. JSON `semantics` documents each metric's meaning.
- `teamfights` keeps its existing algorithm/API name but means **global activity windows**, not
  spatially isolated fights. Window GOLD/XP includes objectives, passive gains and spending;
  it is temporal correlation, not causal fight profit. Equipment times mean PURCHASE, not delivery.

`dota-replay-etl metrics <out>/<matchId>` loads the NDJSON streams into an in-memory
DuckDB, computes the metrics below, and writes `metrics.json` plus a persistent
`metrics.duckdb`. The DuckDB file exposes the raw streams (`combatlog`, `players`,
`kills`, `hero_damage`, `wards`) and computed metrics as tables: `roster`, `lanes`, `gold_curves`,
`xp_curves`, `farm_curves`, `item_timeline`, `damage`, `damage_per_minute`, `teamfights`,
`teamfight_economy`, `death_costs`, `conceded_objectives`, `roshan_kills` and
`building_kills`, `ward_lifetimes`, `dewards`, `smoke_events`, `incident_vision` and
`incident_smoke_events`. Derived tables use the same SQL that drives the JSON sections, so ad-hoc
SQL sees exactly the metrics the reports use.
The inputs are validated up front: if a critical column (`t`, `type`, hero names/keys,
teams, `value`, ...) is missing from either NDJSON file, the command fails with a clear
error instead of producing silently wrong metrics (re-run `analyze` to regenerate).
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
               "side": "radiant", "level": 25, "kills": 3, "deaths": 3, "assists": 9,
               "lane": "top", "lane_confidence": 94} ],
  "kills": [ { "kill_id": 0, "t": 884.9, "killer": "...", "killer_key": "marci", "victim": "...", "victim_key": "lone_druid",
                 "killer_team": 3, "victim_team": 2, "location": [-6111.0, -5903.0], "raw_networth": 870,
                "assist_players": [9, 8, 5], "killer_team_gold": 284, "killer_team_xp": 120,
                "conceded_objective": { "t": 892.4, "target": "npc_dota_badguys_tower1_top",
                                        "target_key": "badguys_tower1_top", "kind": "building" } } ],
  "teamfights": [ { "id": 0, "start": 880.0, "end": 885.0, "duration": 5.0, "hero_damage": 750, "deaths": 1,
                    "participants": ["ember_spirit", "enchantress", "lone_druid", ...],
                    "economy": { "radiant": {"gold": 250, "xp": 180}, "dire": {"gold": 100, "xp": 60},
                                 "gold_delta": 150, "xp_delta": 120 } } ],
  "gold_curves": [ { "hero": "lone_druid", "points": [ {"t": 795.0, "gold": 600}, ... ] } ],
  "xp_curves": [ { "hero": "lone_druid", "points": [ {"t": 795.0, "xp": 0}, ... ] } ],
  "item_timeline": [ { "hero": "marci", "items": [ {"item": "item_orb_of_venom", "t": 827.5}, ... ] } ],
  "damage": [ { "hero": "lone_druid", "dealt_total": 25000, "taken_total": 18000,
                "per_minute": [ {"min": 13, "dealt": 800}, ... ] } ],
  "objectives": {
    "roshan_kills": [ { "t": 2679.0, "killer": "npc_dota_hero_drow_ranger", "killer_key": "drow_ranger",
                        "team": 2, "side": "radiant" } ],
    "building_kills": [ { "t": 1147.0, "building": "npc_dota_badguys_tower1_top", "building_key": "badguys_tower1_top",
                          "kind": "tower", "owner_team": 3, "owner_side": "dire",
                          "destroyer_team": 2, "destroyer_side": "radiant" } ]
  },
  "farm_curves": [ { "hero": "drow_ranger", "points": [ { "t": 30.0, "total_earned_gold": 600, "last_hits": 0, "denies": 0 },
                                                         { "t": 90.0, "total_earned_gold": 820, "last_hits": 7, "denies": 1 }, ... ] } ]
}
```

Notes:

- `team_kills` uses official scores when available. `kills` and window `deaths` exclude verified
  Aegis/illusion deaths, retaining explicitly unknown classifications (see schema 13 above).
- Every hero death has a stable `kill_id`, shared by `kills`, `death_costs` and
  `conceded_objectives` for lossless DuckDB joins.
- Hero keys are normalised snake_case (`npc_dota_hero_lone_druid` -> `lone_druid`) so the
  combat-log-derived sections join with `roster.hero_key`.
- Each roster entry carries an inferred `lane` (`top` / `mid` / `bottom`) plus `lane_confidence`
  (percentage of early samples matching the winning region). Lane inference is **inferential,
  not factual**: it takes each player's positions during the first 90 seconds after the horn,
  assigns every sample to the top (`x < 0, y > 0`), bottom (`x > 0, y < 0`) or mid (x,y same sign)
  map region, and picks the region holding the majority (fountain trips excluded, min 10 samples).
  The reports label this as 推断 and quote the confidence.
- `gold_curves` / `xp_curves` are signed cumulative sums of combat-log GOLD / XP events per hero,
  bucketed every 30 / 60 s (bucket centre time). No synthetic starting gold is added.
- `item_timeline` retains every purchase event, including repeated purchases of the same item.
- `teamfights` are runs of 5-second activity buckets where
  `damage_events + 4*deaths >= 8`, where `damage_events` counts **hero-to-hero** damage
  (attacker and target are both heroes) and `deaths` are hero deaths. `hero_damage` in each
  episode counts damage dealt *by* heroes. Each episode also carries an `economy` object:
  the net gold / XP gained by each team's heroes inside the window (summed from combat-log
  GOLD / XP events attributed via hero -> team; buyback costs count as negative gold,
   building / Roshan gold is included), plus `gold_delta` / `xp_delta` as radiant minus dire.
  The knobs (`BUCKET_SEC`, `WEIGHT_DEATH`,
  `MIN_ACTIVE_SCORE`) live at the top of `MetricQueries`.
- `objectives` is the objective timeline. `roshan_kills` lists every Roshan death with its time,
  the last-hitting hero and the killing team. `building_kills` lists every
  `DOTA_COMBATLOG_TEAM_BUILDING_KILL`: time, building entity, `kind` (`tower` / `rax` / `ancient`
  / `base_tower` / `other`), owner team, last-hit team and `denied`. Equal owner/last-hit teams
  are reported as building denies rather than enemy objectives (the fort / ancient death ends the
  game). Building events are **facts**, so reports can correlate fights with objectives without
  the model guessing.
- `farm_curves` comes straight from the authoritative player resource (`CDOTA_PlayerResource`),
  not reconstructed from GOLD events: per hero, cumulative `total_earned_gold`, `last_hits` and
  `denies`, bucketed every 60 s (bucket centre time, MAX per bucket keeps the monotonic counters
  after resets). Reports quote these as facts (e.g. final totals, last-hits-per-minute) instead of
  labelling the combat-log income curve as a "bank balance".
- Each `kills` entry carries a death-cost assessment. `killer_team_gold` / `killer_team_xp` are
  the gold / XP the killer team accrued in the 2 s after the kill (attributed via hero -> team);
  they are **approximations** — the true kill bounty is not a reliable combat-log field, so the
  window also includes passive income / last-hits / buyback spend (which can make the value
  negative). `conceded_objective` (when present) is the first building or Roshan the killer team
   took within 20 s of the death; the ordering is factual, but causation is not established.
   Knobs: `KILL_GOLD_WINDOW_SEC`, `KILL_FOLLOWUP_WINDOW_SEC` in `MetricQueries`.
  When the death combat-log event lacks coordinates, `location` falls back to the victim's latest
  player-state sample from at most 5 seconds earlier and is explicitly labelled with
  `location_source: player_sample` plus `location_age_sec`; reports render it as approximate.
- `damage` is per-hero hero-to-hero damage: `dealt_total` (attacker is a hero), `taken_total`
  (target is a hero, any source), and `per_minute` buckets of damage dealt. Drives the
  single-player review's engagement windows.
- `incidents.deaths` is the standardized evidence package for every hero death. Each stable
  `death-<kill_id>` record contains the 15 seconds before death (victim ability/item actions,
  controls received, damage sources and health observations), living heroes sampled within 2500
  world units, active wards within 2500 units, nearby smoke events, other deaths from 15 seconds
  before through 5 seconds after, the last recorded BKB use and any follow-up objective.
  A nearby ward is spatial evidence only and does not prove line of sight through terrain or trees.
  Sample-derived positions and distances retain their age/source
  metadata and are explicitly approximate. The same evidence is persisted as the DuckDB tables
  `death_incidents` and `incident_*` for ad-hoc analysis.
- `vision` summarizes observer/sentry placements by team, per-player placements and dewards,
  individual deward events, smoke uses and smoke modifiers that ended before their normal 45-second
  duration (`broken_early`). Early removal is evidence that smoke ended, not proof of its precise
  cause (enemy proximity, an attack, death, and other game interactions are not distinguished).

## LLM review prompt (`report`)

`dota-replay-etl report <out>/<matchId>` reads `metrics.json` and assembles a Chinese-language
review prompt into `prompt.md` (dry-run only — it never calls an LLM). All numbers are
pre-computed by the metrics layer; the prompt explicitly forbids the model from inventing or
recalculating values, and asks it to close with an **MVP** and a **lowest role-completion** pick, each
with data-backed reasons (the lower-performer pick may be declined when evidence is insufficient).
The lineup table shows each player's inferred lane with its confidence (labelled as 推断).
The economy section shows the five-minute team income differential
(carry-forward of each hero's cumulative income, labelled as a trend, not a bank balance),
followed by an authoritative per-hero farm table (total earned gold, last hits, denies,
last-hits-per-minute) from the player resource.
The global activity timeline includes each window's per-team signed gold change (see
`teamfight.economy`), which must not be interpreted as causal fight profit.
A new **客观目标时间线** section lists every Roshan kill (time + killer + team) and every
building kill (time + building + destroying team) in game-clock order. A later objective is not
proof that a fight caused or enabled it. The final ancient kill
marks the end of the game.
Copy `prompt.md` into any LLM to get the report, or paste it into a future `--api` mode.

## Single-player review (`player-review`)

`dota-replay-etl player-review <out>/<matchId> <heroOrNameOrIndex>` assembles a focused
review prompt for one hero into `player-review-<hero>.md` (dry-run). The selector matches a
roster entry by `hero_key`, hero name, player name, or player index. Beyond the match-level
metrics it adds: the hero's full purchase timeline, kills/deaths with positions, an income
comparison against the enemy team's top earner using player-resource counters (not signed GOLD),
per-minute hero damage, local fights with the hero's member-event output or body-presence evidence
(separately labelled, with member-only kill/death statistics and unknown causal economy), and a
farming/position table derived from `players.ndjson`
(the hero's inferred lane with its confidence is shown at the top; the table reports the share
of time spent in the enemy half and deep in enemy territory per game phase, split along the
river diagonal).
It also includes each death's standardized incident evidence: preceding 15-second casts,
control and damage sources, nearby living heroes and ward entities, nearby smoke events, other
deaths in the window, and last-BKB-use. A separate vision section lists the player's ward and
deward counts plus the team's smoke timeline;
older metrics fall back to scanning the raw combat log. The 打钱/位置分析 section opens with the
hero's authoritative farm totals (cumulative earned gold, last hits + per-minute pace, denies)
from the player resource. A **本方目标进度** section lists the
Roshan kills and building destroys performed by the hero's team, so the review can judge whether
kills converted into objectives. The prompt asks for a data-backed review of
出装决策 / 团战切入 / 打钱路线 / 关键决策 plus a
prioritised improvement list. Position facts are skipped when `players.ndjson` is absent.

## How extraction works

`ExtractionProcessor` is an annotation-driven clarity runner processor:

- `@OnCombatLogEntry` writes every combat log entry.
- `@OnEntityCreated` / `@OnEntityDeleted` track observer and sentry ward lifecycles; ward death
  combat-log entries distinguish natural expiry from destruction and identify the destroyer.
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
kills, teamfights, curves, item timeline, damage) against a synthetic fixture, the
persisted DuckDB tables, the match review prompt assembly, the single-player review prompt
assembly, HTTP User-Agent wiring, and CLI argument validation (including `pipeline`).

Schema 16 adds deterministic unit/integration coverage for canonical item identity, audited slot
boundaries, delayed holding after duplicate purchases, stash/backpack/main transitions, disappearance
versus missing data, stale/future/nonfinite clocks, latest-invalid blocking, player isolation,
real-unit and local-body action attribution, inclusive window bounds, counter null versus zero,
report schema rejection and every equipment JSON field versus DuckDB. `mvn -q verify` runs the
full suite and packages the executable jar; the position-clock correction passed all 101 tests,
including initially failing future/missing/nonfinite/actual-age regressions and the delayed
observed-clock 5-second boundary. Tests use fixed clocks and local in-memory DuckDB, not a network.
