package dev.dota.etl.extract;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dota.etl.sink.NdjsonWriter;
import skadistats.clarity.event.Insert;
import skadistats.clarity.io.Util;
import skadistats.clarity.model.CombatLogEntry;
import skadistats.clarity.model.DTClass;
import skadistats.clarity.model.EngineId;
import skadistats.clarity.model.Entity;
import skadistats.clarity.model.FieldPath;
import skadistats.clarity.processor.entities.Entities;
import skadistats.clarity.processor.entities.OnEntityCreated;
import skadistats.clarity.processor.entities.OnEntityDeleted;
import skadistats.clarity.processor.entities.OnEntityUpdated;
import skadistats.clarity.processor.entities.OnEntityPropertyChanged;
import skadistats.clarity.processor.entities.UsesEntities;
import skadistats.clarity.processor.gameevents.OnCombatLogEntry;
import skadistats.clarity.processor.reader.OnTickEnd;
import skadistats.clarity.processor.runner.Context;
import skadistats.clarity.processor.sendtables.DTClasses;
import skadistats.clarity.processor.sendtables.OnDTClassesComplete;
import skadistats.clarity.wire.dota.common.proto.DOTACombatLog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Annotation-driven extraction processor for a single .dem file.
 *
 * Emits two NDJSON streams:
 *  - combatlog.ndjson: every combat log entry
 *  - players.ndjson:   per-player state sampled every {@code sampleIntervalSec}
 *                      seconds of in-game clock (combat-log time)
 *
 * Match-level facts are exposed via {@link #lastTick()} and {@link #combatLogCount()}
 * and written to match.json by {@link ReplayExtractor}.
 *
 * Per-player state is split across two entities:
 *  - PlayerResource holds team/name/level/kills/deaths/assists + the selected hero handle
 *  - the hero entity holds position (CBodyComponent cell+vec), health/mana and item handles
 * Hero assignment is deferred to tick end because the hero entity spawns after the
 * PlayerResource update inside the same tick.
 *
 * Sampling is driven by the in-game clock carried by combat log timestamps rather than
 * by absolute demo ticks: at the moment the actual game begins, the demo tick stream
 * shifts parity (a Source 2 replay quirk), so a naive {@code tick % n == 0} rule stops
 * firing for the whole match. Combat log events fire throughout the game, so the latest
 * seen game time is a reliable clock.
 */
@UsesEntities
public class ExtractionProcessor {

    private static final String[] HERO_PREFIX_S2 = {"CDOTA_Unit_Hero_"};
    private static final String[] HERO_PREFIX_S1 = {"DT_DOTA_Unit_Hero_"};
    private static final int ITEM_SLOTS = 12;

    @Insert
    private Context ctx;

    @Insert
    private Entities entities;

    @Insert
    private DTClasses dtClasses;

    private final NdjsonWriter combatLogWriter;
    private final NdjsonWriter playersWriter;
    private final NdjsonWriter wardsWriter;
    private final float sampleIntervalSec;

    private DTClass playerResourceClass;
    private Entity playerResourceEntity;
    private boolean s2;
    private boolean legacyFormat;

    private final PlayerLookup[] players = new PlayerLookup[10];
    private final int[] pendingHeroHandles = new int[10];
    private DataLookup radiantData;
    private DataLookup direData;

    private int lastTick;
    private long combatLogCount;
    private float currentGameTime;
    private float nextSampleAt = Float.NaN;
    private boolean initialSampleWritten;
    private float gameStartTime;
    private float gameEndTime;
    private int gameWinner;
    private int radiantScore = -1;
    private int direScore = -1;
    private final Map<Long, WardPlacement> wards = new LinkedHashMap<>();
    private final List<WardDeathEvidence> wardDeaths = new ArrayList<>();

    public ExtractionProcessor(NdjsonWriter combatLogWriter, NdjsonWriter playersWriter,
                               NdjsonWriter wardsWriter, int sampleIntervalSec) {
        this.combatLogWriter = combatLogWriter;
        this.playersWriter = playersWriter;
        this.wardsWriter = wardsWriter;
        this.sampleIntervalSec = Math.max(1, sampleIntervalSec);
    }

    @OnEntityCreated(classPattern = "CDOTA_NPC_Observer_Ward(_TrueSight)?")
    protected void onWardCreated(Entity e) {
        String type = wardType(e.getDtClass().getDtName());
        float[] position = entityPosition(e);
        Float created = propertyFloat(e, "m_flCreateTime");
        Integer ownerId = propertyInt(e, "m_nPlayerOwnerID");
        wards.put((long) e.getUid(), new WardPlacement(
            e.getUid(), type, created == null ? currentGameTime : created,
            propertyInt(e, "m_iTeamNum"), ownerId,
            playerIndexFromOwnerId(ownerId), position));
    }

    @OnEntityDeleted(classPattern = "CDOTA_NPC_Observer_Ward(_TrueSight)?")
    protected void onWardDeleted(Entity e) {
        WardPlacement ward = wards.get((long) e.getUid());
        if (ward != null) {
            ward.removedAt = currentGameTime;
        }
    }

    @OnDTClassesComplete
    protected void onDtClassesComplete() {
        s2 = ctx.getEngineType().getId() == EngineId.DOTA_S2;
        String prefix = s2 ? "CDOTA_" : "DT_DOTA_";
        playerResourceClass = dtClasses.forDtName(prefix + "PlayerResource");
        if (playerResourceClass == null) {
            throw new IllegalStateException("cannot find PlayerResource datatable class for engine " + ctx.getEngineType());
        }
        legacyFormat = playerResourceClass.getFieldPathForName("m_iPlayerTeams." + Util.arrayIdxToString(0)) != null;
        for (int i = 0; i < 10; i++) {
            players[i] = new PlayerLookup(playerResourceClass, i, legacyFormat);
        }
    }

    @OnEntityUpdated
    protected void onEntityUpdated(Entity e, FieldPath[] changed, int nChanged) {
        if (playerResourceClass == null || e.getDtClass() != playerResourceClass) {
            return;
        }
        for (int p = 0; p < 10; p++) {
            PlayerLookup lookup = players[p];
            if (lookup.selectedHeroPath != null && lookup.containsAny(changed, nChanged, lookup.selectedHeroPath)) {
                Integer handle = intOrNull(e, lookup.selectedHeroPath);
                pendingHeroHandles[p] = handle == null ? -1 : handle;
            }
        }
    }

    @OnEntityPropertyChanged(
        classPattern = "CDOTAGamerulesProxy",
        propertyPattern = "m_pGameRules.m_(flGameStartTime|flGameEndTime|nGameWinner)"
    )
    protected void onGameRulesPropertyChanged(Entity e, FieldPath fp) {
        String name = e.getDtClass().getNameForFieldPath(fp);
        Object value = e.getPropertyForFieldPath(fp);
        if (name.endsWith("m_flGameStartTime") && value instanceof Number n && n.floatValue() > 0) {
            gameStartTime = n.floatValue();
        } else if (name.endsWith("m_flGameEndTime") && value instanceof Number n && n.floatValue() > 0) {
            gameEndTime = n.floatValue();
        } else if (name.endsWith("m_nGameWinner") && value instanceof Number n
                   && (n.intValue() == 2 || n.intValue() == 3)) {
            gameWinner = n.intValue();
        }
    }

    @OnEntityPropertyChanged(
        classPattern = "CDOTATeam",
        propertyPattern = "m_iHeroKills"
    )
    protected void onTeamScoreChanged(Entity e, FieldPath fp) {
        Integer team = propertyInt(e, "m_iTeamNum");
        Object value = e.getPropertyForFieldPath(fp);
        if (!(value instanceof Number n)) {
            return;
        }
        if (team != null && team == 2) {
            radiantScore = n.intValue();
        } else if (team != null && team == 3) {
            direScore = n.intValue();
        }
    }

    @OnCombatLogEntry
    protected void onCombatLog(CombatLogEntry cle) {
        float t = cle.getTimestamp();
        if (t > 0) {
            currentGameTime = Math.max(currentGameTime, t);
        }
        ObjectNode rec = combatLogWriter.newRecord();
        rec.put("t", t);
        DOTACombatLog.DOTA_COMBATLOG_TYPES type = safeType(cle);
        put(rec, "type", type == null ? null : type.name());
        rec.put("type_id", type == null ? -1 : type.getNumber());
        put(rec, "attacker", str(cle.getAttackerName()));
        rec.put("attacker_illusion", cle.isAttackerIllusion());
        rec.put("attacker_hero", cle.isAttackerHero());
        put(rec, "target", str(cle.getTargetName()));
        rec.put("target_illusion", cle.isTargetIllusion());
        rec.put("target_hero", cle.isTargetHero());
        rec.put("target_self", cle.isTargetSelf());
        put(rec, "inflictor", str(cle.getInflictorName()));
        put(rec, "value", cle.hasValue() ? cle.getValue() : null);
        put(rec, "value_name", str(cle.getValueName()));
        put(rec, "health", cle.hasHealth() ? cle.getHealth() : null);
        put(rec, "x", cle.hasLocationX() ? cle.getLocationX() : null);
        put(rec, "y", cle.hasLocationY() ? cle.getLocationY() : null);
        put(rec, "attacker_team", cle.hasAttackerTeam() ? cle.getAttackerTeam() : null);
        put(rec, "target_team", cle.hasTargetTeam() ? cle.getTargetTeam() : null);
        put(rec, "visible_radiant", cle.isVisibleRadiant());
        put(rec, "visible_dire", cle.isVisibleDire());
        put(rec, "gold_reason", cle.hasGoldReason() ? cle.getGoldReason() : null);
        put(rec, "xp_reason", cle.hasXpReason() ? cle.getXpReason() : null);
        put(rec, "ability_level", cle.hasAbilityLevel() ? cle.getAbilityLevel() : null);
        put(rec, "rune_type", cle.hasRuneType() ? cle.getRuneType() : null);
        put(rec, "stack_count", cle.hasStackCount() ? cle.getStackCount() : null);
        put(rec, "last_hits", cle.hasLastHits() ? cle.getLastHits() : null);
        put(rec, "networth", cle.hasNetworth() ? cle.getNetworth() : null);
        put(rec, "obs_wards", cle.hasObsWardsPlaced() ? cle.getObsWardsPlaced() : null);
        put(rec, "neutral_camp_type", cle.hasNeutralCampType() ? cle.getNeutralCampType() : null);
        put(rec, "modifier_duration", cle.hasModifierDuration() ? cle.getModifierDuration() : null);
        put(rec, "stun_duration", cle.hasStunDuration() ? cle.getStunDuration() : null);
        put(rec, "slow_duration", cle.hasSlowDuration() ? cle.getSlowDuration() : null);
        put(rec, "damage_type", cle.hasDamageType() ? cle.getDamageType() : null);
        put(rec, "damage_category", cle.hasDamageCategory() ? cle.getDamageCategory() : null);
        put(rec, "event_location", cle.hasEventLocation() ? cle.getEventLocation() : null);
        put(rec, "xpm", cle.hasXpm() ? cle.getXpm() : null);
        put(rec, "gpm", cle.hasGpm() ? cle.getGpm() : null);
        put(rec, "attacker_hero_level", cle.hasAttackerHeroLevel() ? cle.getAttackerHeroLevel() : null);
        put(rec, "target_hero_level", cle.hasTargetHeroLevel() ? cle.getTargetHeroLevel() : null);
        put(rec, "target_source", str(cle.getTargetSourceName()));
        put(rec, "damage_source", str(cle.getDamageSourceName()));
        if (cle.hasAssistPlayers()) {
            var assists = cle.getAssistPlayers();
            if (!assists.isEmpty()) {
                com.fasterxml.jackson.databind.node.ArrayNode arr = rec.putArray("assists");
                assists.forEach(arr::add);
            }
        }
        combatLogWriter.write(rec);
        combatLogCount++;

        String target = str(cle.getTargetName());
        String wardType = wardTypeFromCombatName(target);
        if (type == DOTACombatLog.DOTA_COMBATLOG_TYPES.DOTA_COMBATLOG_DEATH && wardType != null) {
            wardDeaths.add(new WardDeathEvidence(t, wardType,
                cle.hasTargetTeam() ? cle.getTargetTeam() : null,
                str(cle.getAttackerName()), cle.hasAttackerTeam() ? cle.getAttackerTeam() : null));
        }
    }

    /** Finalizes one lifecycle row per ward after the replay has been fully consumed. */
    public void finishWardEvents() {
        List<WardPlacement> removed = new ArrayList<>(wards.values());
        removed.removeIf(w -> w.removedAt == null);
        removed.sort(Comparator.comparingDouble((WardPlacement w) -> w.removedAt).reversed());
        boolean[] claimedDeaths = new boolean[wardDeaths.size()];
        Map<Long, WardDeathEvidence> matchedDeaths = new LinkedHashMap<>();
        for (WardPlacement ward : removed) {
            WardDeathEvidence death = matchWardDeath(ward, claimedDeaths);
            if (death != null) matchedDeaths.put(ward.uid, death);
        }

        List<WardPlacement> ordered = new ArrayList<>(wards.values());
        ordered.sort(Comparator.comparingDouble(w -> w.createdAt));
        for (WardPlacement ward : ordered) {
            WardDeathEvidence death = matchedDeaths.get(ward.uid);
            ObjectNode rec = wardsWriter.newRecord();
            rec.put("ward_id", Long.toUnsignedString(ward.uid));
            rec.put("type", ward.type);
            rec.put("placed_t", round1(ward.createdAt));
            Float removedAt = death == null ? ward.removedAt : Float.valueOf(death.t);
            if (removedAt != null) {
                rec.put("removed_t", round1(removedAt));
                rec.put("lifetime_sec", round1(Math.max(0, removedAt - ward.createdAt)));
            }
            putNullable(rec, "team", ward.team);
            putNullable(rec, "player_owner_id", ward.ownerId);
            putNullable(rec, "player", ward.player);
            if (ward.position != null) {
                rec.put("x", round1(ward.position[0]));
                rec.put("y", round1(ward.position[1]));
                rec.put("z", round1(ward.position[2]));
            } else {
                rec.putNull("x");
                rec.putNull("y");
                rec.putNull("z");
            }
            if (death == null) {
                rec.put("removal_reason", ward.removedAt == null ? "active_at_end" : "unknown");
            } else {
                boolean expired = death.attacker != null && death.attacker.equals(combatWardName(ward.type));
                rec.put("removal_reason", expired ? "expired" : "destroyed");
                putNullable(rec, "destroyer", death.attacker);
                putNullable(rec, "destroyer_team", death.attackerTeam);
            }
            if (!rec.has("destroyer")) rec.putNull("destroyer");
            if (!rec.has("destroyer_team")) rec.putNull("destroyer_team");
            if (!rec.has("removed_t")) rec.putNull("removed_t");
            if (!rec.has("lifetime_sec")) rec.putNull("lifetime_sec");
            wardsWriter.write(rec);
        }
    }

    @OnTickEnd
    protected void onTickEnd(boolean synthetic) {
        if (synthetic) {
            return;
        }
        lastTick = ctx.getTick();
        applyPendingHeroAssignments();
        if (!initialSampleWritten) {
            writePlayerStates(0.0f, lastTick);
            initialSampleWritten = true;
        }
        if (currentGameTime > 0) {
            if (Float.isNaN(nextSampleAt)) {
                nextSampleAt = Math.max(1.0f, currentGameTime);
            }
            int guard = 0;
            while (currentGameTime >= nextSampleAt && guard < 8) {
                writePlayerStates(nextSampleAt, lastTick);
                nextSampleAt += sampleIntervalSec;
                guard++;
            }
        }
    }

    private void applyPendingHeroAssignments() {
        for (int p = 0; p < 10; p++) {
            int handle = pendingHeroHandles[p];
            if (handle == 0) {
                continue;
            }
            pendingHeroHandles[p] = 0;
            if (handle > 0) {
                Entity hero = entities.getByHandle(handle);
                if (hero != null) {
                    players[p].assignHero(hero);
                }
            }
        }
    }

    private void writePlayerStates(float gameTime, int tick) {
        ensureEntities();
        for (int p = 0; p < 10; p++) {
            PlayerLookup lookup = players[p];
            ObjectNode rec = playersWriter.newRecord();
            rec.put("t", round1(gameTime));
            rec.put("tick", tick);
            rec.put("player", p);
            Integer team = intOrNull(playerResourceEntity, lookup.teamPath);
            put(rec, "team", team);
            put(rec, "name", strOrNull(playerResourceEntity, lookup.namePath));
            put(rec, "hero", lookup.heroName());
            put(rec, "level", intOrNull(playerResourceEntity, lookup.levelPath));
            put(rec, "kills", intOrNull(playerResourceEntity, lookup.killsPath));
            put(rec, "deaths", intOrNull(playerResourceEntity, lookup.deathsPath));
            put(rec, "assists", intOrNull(playerResourceEntity, lookup.assistsPath));

            int pos = team == null ? -1 : teamPos(p, team);
            DataLookup data = team != null && team == 2 ? radiantData : (team != null && team == 3 ? direData : null);
            if (data != null && pos >= 0) {
                put(rec, "total_earned_gold", intOrNull(data.entity, data.totalEarnedGold[pos]));
                put(rec, "last_hits", intOrNull(data.entity, data.lastHits[pos]));
                put(rec, "denies", intOrNull(data.entity, data.denies[pos]));
            }

            Entity hero = lookup.heroEntity;
            if (hero != null) {
                float[] position = lookup.position();
                if (position != null) {
                    rec.put("x", round1(position[0]));
                    rec.put("y", round1(position[1]));
                    rec.put("z", round1(position[2]));
                }
                put(rec, "hp", floatOrNull(hero, lookup.fpHp));
                put(rec, "max_hp", floatOrNull(hero, lookup.fpMaxHp));
                put(rec, "mana", floatOrNull(hero, lookup.fpMana));
                put(rec, "max_mana", floatOrNull(hero, lookup.fpMaxMana));

                String[] items = lookup.items();
                if (items != null) {
                    ObjectNode itemArr = rec.putObject("items");
                    for (int slot = 0; slot < items.length; slot++) {
                        if (items[slot] != null) {
                            itemArr.put("slot" + slot, items[slot]);
                        }
                    }
                }
            }
            playersWriter.write(rec);
        }
    }

    private void ensureEntities() {
        if (playerResourceEntity == null) {
            playerResourceEntity = entities.getByDtName((s2 ? "CDOTA_" : "DT_DOTA_") + "PlayerResource");
        }
        if (radiantData == null) {
            radiantData = DataLookup.tryCreate(entities.getByDtName((s2 ? "CDOTA_" : "DT_DOTA_") + "DataRadiant"));
        }
        if (direData == null) {
            direData = DataLookup.tryCreate(entities.getByDtName((s2 ? "CDOTA_" : "DT_DOTA_") + "DataDire"));
        }
    }

    /** Position of the player within its team's Data entity (0-4), or -1 when unknown. */
    private int teamPos(int playerIdx, int team) {
        if (team != 2 && team != 3) {
            return -1;
        }
        int pos = 0;
        for (int p = 0; p < playerIdx; p++) {
            Integer t = intOrNull(playerResourceEntity, players[p].teamPath);
            if (t != null && t == team) {
                pos++;
            }
        }
        return pos < 5 ? pos : -1;
    }

    public int lastTick() {
        return lastTick;
    }

    public long combatLogCount() {
        return combatLogCount;
    }

    public float gameStartTime() {
        return gameStartTime;
    }

    public float gameEndTime() {
        return gameEndTime;
    }

    public int gameWinner() {
        return gameWinner;
    }

    public int radiantScore() {
        return radiantScore;
    }

    public int direScore() {
        return direScore;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static DOTACombatLog.DOTA_COMBATLOG_TYPES safeType(CombatLogEntry cle) {
        try {
            return cle.getType();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void put(ObjectNode node, String field, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Integer i) {
            node.put(field, i);
        } else if (value instanceof Long l) {
            node.put(field, l);
        } else if (value instanceof Float f) {
            node.put(field, f);
        } else if (value instanceof Double d) {
            node.put(field, d);
        } else if (value instanceof Boolean b) {
            node.put(field, b);
        } else {
            node.put(field, value.toString());
        }
    }

    private static void putNullable(ObjectNode node, String field, Object value) {
        if (value == null) node.putNull(field);
        else put(node, field, value);
    }

    private static String str(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static float round1(float v) {
        return Math.round(v * 10.0f) / 10.0f;
    }

    private static Integer intOrNull(Entity e, FieldPath fp) {
        if (fp == null) {
            return null;
        }
        try {
            Object v = e == null ? null : e.getPropertyForFieldPath(fp);
            if (v instanceof Number n) {
                return n.intValue();
            }
            return null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Integer propertyInt(Entity e, String name) {
        try {
            Object value = e.hasProperty(name) ? e.getProperty(name) : null;
            return value instanceof Number n ? n.intValue() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Float propertyFloat(Entity e, String name) {
        try {
            Object value = e.hasProperty(name) ? e.getProperty(name) : null;
            return value instanceof Number n ? n.floatValue() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static float[] entityPosition(Entity e) {
        Integer cellX = propertyInt(e, "CBodyComponent.m_cellX");
        Integer cellY = propertyInt(e, "CBodyComponent.m_cellY");
        Float vecX = propertyFloat(e, "CBodyComponent.m_vecX");
        Float vecY = propertyFloat(e, "CBodyComponent.m_vecY");
        if (cellX == null || cellY == null || vecX == null || vecY == null) {
            return null;
        }
        Integer cellZ = propertyInt(e, "CBodyComponent.m_cellZ");
        Float vecZ = propertyFloat(e, "CBodyComponent.m_vecZ");
        float z = cellZ == null || vecZ == null ? 0 : positionComponent(cellZ, vecZ);
        return new float[]{positionComponent(cellX, vecX), positionComponent(cellY, vecY), z};
    }

    private static Float floatOrNull(Entity e, FieldPath fp) {
        if (fp == null) {
            return null;
        }
        try {
            Object v = e.getPropertyForFieldPath(fp);
            if (v instanceof Number n) {
                return n.floatValue();
            }
            return null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String strOrNull(Entity e, FieldPath fp) {
        if (fp == null) {
            return null;
        }
        try {
            Object v = e.getPropertyForFieldPath(fp);
            return v instanceof String s && !s.isEmpty() ? s : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static FieldPath fp(DTClass dt, String name) {
        try {
            return dt.getFieldPathForName(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Source 2 cell+vec decomposition: world coord = (cell - 128) * 128 + vec.
     *  The world origin (0,0) sits at cell 128, so 128 cells (16384 units) must be
     *  subtracted; without it the fountain reads +9600 instead of -6700. */
    public static float positionComponent(int cell, float vec) {
        return (cell - 128) * 128.0f + vec;
    }

    public static String heroNameFromClass(String dtName) {
        for (String prefix : HERO_PREFIX_S2) {
            if (dtName.startsWith(prefix)) {
                return dtName.substring(prefix.length());
            }
        }
        for (String prefix : HERO_PREFIX_S1) {
            if (dtName.startsWith(prefix)) {
                return dtName.substring(prefix.length());
            }
        }
        return dtName;
    }

    static Integer playerIndexFromOwnerId(Integer ownerId) {
        return ownerId != null && ownerId >= 0 && ownerId <= 18 && ownerId % 2 == 0
            ? ownerId / 2 : null;
    }

    private static String wardType(String className) {
        return className != null && className.endsWith("_TrueSight") ? "sentry" : "observer";
    }

    private static String wardTypeFromCombatName(String name) {
        if ("npc_dota_observer_wards".equals(name)) {
            return "observer";
        }
        if ("npc_dota_sentry_wards".equals(name)) {
            return "sentry";
        }
        return null;
    }

    private static String combatWardName(String type) {
        return "sentry".equals(type) ? "npc_dota_sentry_wards" : "npc_dota_observer_wards";
    }

    private WardDeathEvidence matchWardDeath(WardPlacement ward, boolean[] claimed) {
        if (ward.removedAt == null) {
            return null;
        }
        int best = -1;
        float bestDelta = Float.MAX_VALUE;
        for (int i = 0; i < wardDeaths.size(); i++) {
            WardDeathEvidence death = wardDeaths.get(i);
            if (claimed[i] || !ward.type.equals(death.type)
                || (ward.team != null && death.team != null && !ward.team.equals(death.team))) {
                continue;
            }
            float delta = ward.removedAt - death.t;
            // Ward entities linger for several seconds after their combat-log death.
            if (delta >= 0 && delta <= 15.0f && delta < bestDelta) {
                best = i;
                bestDelta = delta;
            }
        }
        if (best < 0) {
            return null;
        }
        claimed[best] = true;
        return wardDeaths.get(best);
    }

    private static final class WardPlacement {
        final long uid;
        final String type;
        final float createdAt;
        final Integer team;
        final Integer ownerId;
        final Integer player;
        final float[] position;
        Float removedAt;

        WardPlacement(long uid, String type, float createdAt, Integer team, Integer ownerId,
                      Integer player, float[] position) {
            this.uid = uid;
            this.type = type;
            this.createdAt = createdAt;
            this.team = team;
            this.ownerId = ownerId;
            this.player = player;
            this.position = position;
        }
    }

    private record WardDeathEvidence(float t, String type, Integer team,
                                     String attacker, Integer attackerTeam) {
    }

    // ------------------------------------------------------------------
    // player resource lookup
    // ------------------------------------------------------------------

    private final class PlayerLookup {
        final FieldPath teamPath;
        final FieldPath namePath;
        final FieldPath levelPath;
        final FieldPath killsPath;
        final FieldPath deathsPath;
        final FieldPath assistsPath;
        final FieldPath selectedHeroPath;

        Entity heroEntity;
        private DTClass heroClass;
        private FieldPath fpCellX, fpCellY, fpCellZ, fpVecX, fpVecY, fpVecZ;
        private FieldPath fpHp, fpMaxHp, fpMana, fpMaxMana;
        private final FieldPath[] itemPaths = new FieldPath[ITEM_SLOTS];
        private String cachedHeroName;

        PlayerLookup(DTClass prClass, int idx, boolean legacy) {
            String a = Util.arrayIdxToString(idx);
            if (legacy) {
                teamPath = fp(prClass, "m_iPlayerTeams." + a);
                namePath = fp(prClass, "m_iszPlayerNames." + a);
                levelPath = fp(prClass, "m_iLevel." + a);
                killsPath = fp(prClass, "m_iKills." + a);
                deathsPath = fp(prClass, "m_iDeaths." + a);
                assistsPath = fp(prClass, "m_iAssists." + a);
                selectedHeroPath = null;
            } else {
                teamPath = fp(prClass, "m_vecPlayerData." + a + ".m_iPlayerTeam");
                namePath = fp(prClass, "m_vecPlayerData." + a + ".m_iszPlayerName");
                levelPath = fp(prClass, "m_vecPlayerTeamData." + a + ".m_iLevel");
                killsPath = fp(prClass, "m_vecPlayerTeamData." + a + ".m_iKills");
                deathsPath = fp(prClass, "m_vecPlayerTeamData." + a + ".m_iDeaths");
                assistsPath = fp(prClass, "m_vecPlayerTeamData." + a + ".m_iAssists");
                selectedHeroPath = fp(prClass, "m_vecPlayerTeamData." + a + ".m_hSelectedHero");
            }
        }

        boolean containsAny(FieldPath[] changed, int n, FieldPath fp) {
            for (int i = 0; i < n; i++) {
                if (changed[i].equals(fp)) {
                    return true;
                }
            }
            return false;
        }

        void assignHero(Entity hero) {
            heroEntity = hero;
            heroClass = hero.getDtClass();
            String body = "CBodyComponent.m_";
            fpCellX = fp(heroClass, body + "cellX");
            fpCellY = fp(heroClass, body + "cellY");
            fpCellZ = fp(heroClass, body + "cellZ");
            fpVecX = fp(heroClass, body + "vecX");
            fpVecY = fp(heroClass, body + "vecY");
            fpVecZ = fp(heroClass, body + "vecZ");
            fpHp = fp(heroClass, "m_iHealth");
            fpMaxHp = fp(heroClass, "m_iMaxHealth");
            fpMana = fp(heroClass, "m_flMana");
            fpMaxMana = fp(heroClass, "m_flMaxMana");
            for (int j = 0; j < itemPaths.length; j++) {
                itemPaths[j] = fp(heroClass, "m_hItems." + Util.arrayIdxToString(j));
            }
            cachedHeroName = heroNameFromClass(heroClass.getDtName());
        }

        float[] position() {
            if (heroEntity == null) {
                return null;
            }
            try {
                boolean hasCell = fpCellX != null && fpVecX != null;
                Float x = hasCell
                    ? positionComponent(Objects.requireNonNull(intOrNull(heroEntity, fpCellX)), floatOrNull(heroEntity, fpVecX))
                    : floatOrNull(heroEntity, fpVecX);
                Float y = hasCell
                    ? positionComponent(Objects.requireNonNull(intOrNull(heroEntity, fpCellY)), floatOrNull(heroEntity, fpVecY))
                    : floatOrNull(heroEntity, fpVecY);
                Float z = hasCell
                    ? positionComponent(Objects.requireNonNull(intOrNull(heroEntity, fpCellZ)), floatOrNull(heroEntity, fpVecZ))
                    : floatOrNull(heroEntity, fpVecZ);
                if (x == null || y == null || z == null) {
                    return null;
                }
                return new float[]{x, y, z};
            } catch (RuntimeException e) {
                return null;
            }
        }

        String heroName() {
            return cachedHeroName;
        }

        String[] items() {
            if (heroEntity == null) {
                return null;
            }
            String[] out = new String[itemPaths.length];
            for (int j = 0; j < itemPaths.length; j++) {
                FieldPath fp = itemPaths[j];
                if (fp == null) {
                    continue;
                }
                Integer handle = intOrNull(heroEntity, fp);
                if (handle == null || handle <= 0) {
                    continue;
                }
                Entity item = entities.getByHandle(handle);
                if (item == null) {
                    continue;
                }
                out[j] = itemName(item);
            }
            return out;
        }

        private String itemName(Entity item) {
            String dt = item.getDtClass().getDtName();
            if (dt.equals("CDOTA_Item") || dt.equals("DT_DOTA_Item")) {
                return null;
            }
            if (dt.startsWith("CDOTA_Item_")) {
                return "item_" + dt.substring("CDOTA_Item_".length()).toLowerCase();
            }
            if (dt.startsWith("DT_DOTA_Item_")) {
                return "item_" + dt.substring("DT_DOTA_Item_".length()).toLowerCase();
            }
            return dt;
        }
    }

    private static final class DataLookup {
        final Entity entity;
        final FieldPath[] totalEarnedGold = new FieldPath[5];
        final FieldPath[] lastHits = new FieldPath[5];
        final FieldPath[] denies = new FieldPath[5];

        private DataLookup(Entity entity) {
            this.entity = entity;
        }

        static DataLookup tryCreate(Entity dataEntity) {
            if (dataEntity == null) {
                return null;
            }
            DataLookup lk = new DataLookup(dataEntity);
            for (int pos = 0; pos < 5; pos++) {
                String a = Util.arrayIdxToString(pos);
                lk.totalEarnedGold[pos] = fp(dataEntity.getDtClass(), "m_vecDataTeam." + a + ".m_iTotalEarnedGold");
                lk.lastHits[pos] = fp(dataEntity.getDtClass(), "m_vecDataTeam." + a + ".m_iLastHitCount");
                lk.denies[pos] = fp(dataEntity.getDtClass(), "m_vecDataTeam." + a + ".m_iDenyCount");
            }
            return lk;
        }
    }
}
