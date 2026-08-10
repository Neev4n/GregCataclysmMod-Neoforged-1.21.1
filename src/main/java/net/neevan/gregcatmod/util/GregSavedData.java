package net.neevan.gregcatmod.util;


import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class GregSavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Key used for the .dat file stored in the overworld's data folder. */
    private static final String DATA_NAME = "gregtcatmod_greg";

    /** NBT key for the Greg UUID field. */
    private static final String KEY_GREG_UUID = "GregUUID";

    /** NBT key for the boss UUID field. */
    private static final String KEY_BOSS_UUID = "BossUUID";

    /** NBT key for the dodge points list. */
    private static final String KEY_DODGE_POINTS = "DodgePoints";

    /** NBT key for the debugger display-entity UUID list. */
    private static final String KEY_DEBUG_DISPLAYS = "DebugDisplays";

    /** NBT key for the debugger-active flag. */
    private static final String KEY_DEBUGGER_ACTIVE = "DebuggerActive";

    /** NBT key for the dodge-trail display-entity UUID list. */
    private static final String KEY_DODGE_TRAIL = "DodgeTrail";

    /** UUID of the most recently spawned Greg entity, or null if none has ever spawned. */
    @Nullable
    private UUID gregUUID;

    /** UUID of the boss set via /setBossTarget, or null if none has been set. */
    @Nullable
    private UUID bossUUID;

    /** Ordered list of manually-placed dodge positions. Persisted across restarts. */
    private final List<BlockPos> dodgePoints = new ArrayList<>();

    /** UUIDs of the glass block-display entities spawned by the debugger. Persisted so they can be cleaned up after a restart. */
    private final List<UUID> debugDisplayUUIDs = new ArrayList<>();

    /** Whether the dodge-point debugger is currently active. Persisted so /addDodgePoint knows to extend a live debugger. */
    private boolean debuggerActive = false;

    /**
     * UUIDs of the green concrete block-display entities forming the current dodge trail.
     * Tracked separately from debugDisplayUUIDs — the two sets have independent lifecycles, so
     * /stopDebugger must not clear the trail and a dodge must not clear the dodge-point markers.
     * Persisted so a restart mid-trail can still clean the displays up rather than orphaning them.
     */
    private final List<UUID> dodgeTrailUUIDs = new ArrayList<>();

    /**
     * Transient signal set by the boss mixin when an attack fires.
     * Not persisted to NBT — runtime only. Cleared by Greg after he reads it.
     */
    @Nullable
    private String pendingBossAlert;

    /**
     * True while a boss is performing an attack Greg should break line of sight to survive —
     * currently only the Harbinger's DEATHLASER. Drives GregEntity.tickHide.
     *
     * <p>Written every tick by HarbingerMixin (level-triggered, not edge-triggered), so it follows
     * the boss's real animation state and clears itself the tick the attack ends — no timer to
     * desync, and an animation cancelled early by a stun still clears correctly.
     *
     * <p>Deliberately NOT persisted, like pendingBossAlert: a flag saved mid-laser would reload with
     * Greg permanently hiding from an attack that ended before the restart, with no way out but a
     * command. Not named after the Harbinger so a second boss can opt in without a rename.
     */
    private boolean hideAlertActive;

    /**
     * One-shot hide request raised when a threat projectile spawns with line of sight to Greg
     * (see ModGameBusEvents.onEntityJoinLevel). Consumed by GregEntity.tickThreatHide on the next
     * tick via {@link #pollPendingThreatHide}, same transport pattern as pendingBossAlert.
     *
     * <p>Deliberately NOT persisted — a signal saved mid-flight would reload pointing at a
     * projectile that no longer exists. See plans/threat_revamp_plan.md.
     */
    private boolean pendingThreatHide;

    /** Creates a fresh instance with no Greg UUID recorded. */
    public static GregSavedData create() {
        return new GregSavedData();
    }

    /** Loads an existing instance from NBT, restoring the saved Greg and boss UUIDs if present. */
    public static GregSavedData load(CompoundTag tag, HolderLookup.Provider lookupProvider) {
        GregSavedData data = create();
        if (tag.hasUUID(KEY_GREG_UUID)) {
            data.gregUUID = tag.getUUID(KEY_GREG_UUID);
            LOGGER.info("GregSavedData: loaded gregUUID={}", data.gregUUID);
        }
        if (tag.hasUUID(KEY_BOSS_UUID)) {
            data.bossUUID = tag.getUUID(KEY_BOSS_UUID);
            LOGGER.info("GregSavedData: loaded bossUUID={}", data.bossUUID);
        }
        if (tag.contains(KEY_DODGE_POINTS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(KEY_DODGE_POINTS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                data.dodgePoints.add(new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z")));
            }
            LOGGER.info("GregSavedData: loaded {} dodge points", data.dodgePoints.size());
        }
        if (tag.contains(KEY_DEBUG_DISPLAYS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(KEY_DEBUG_DISPLAYS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (entry.hasUUID("id")) data.debugDisplayUUIDs.add(entry.getUUID("id"));
            }
            LOGGER.info("GregSavedData: loaded {} debug display UUIDs", data.debugDisplayUUIDs.size());
        }
        data.debuggerActive = tag.getBoolean(KEY_DEBUGGER_ACTIVE);
        if (tag.contains(KEY_DODGE_TRAIL, Tag.TAG_LIST)) {
            ListTag list = tag.getList(KEY_DODGE_TRAIL, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (entry.hasUUID("id")) data.dodgeTrailUUIDs.add(entry.getUUID("id"));
            }
            LOGGER.info("GregSavedData: loaded {} dodge trail display UUIDs", data.dodgeTrailUUIDs.size());
        }
        return data;
    }

    /** Writes the Greg and boss UUIDs to NBT so they persist across server restarts. */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (gregUUID != null) {
            tag.putUUID(KEY_GREG_UUID, gregUUID);
        }
        if (bossUUID != null) {
            tag.putUUID(KEY_BOSS_UUID, bossUUID);
        }
        ListTag list = new ListTag();
        for (BlockPos pos : dodgePoints) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", pos.getX());
            entry.putInt("y", pos.getY());
            entry.putInt("z", pos.getZ());
            list.add(entry);
        }
        tag.put(KEY_DODGE_POINTS, list);
        ListTag displayList = new ListTag();
        for (UUID id : debugDisplayUUIDs) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", id);
            displayList.add(entry);
        }
        tag.put(KEY_DEBUG_DISPLAYS, displayList);
        tag.putBoolean(KEY_DEBUGGER_ACTIVE, debuggerActive);
        ListTag trailList = new ListTag();
        for (UUID id : dodgeTrailUUIDs) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", id);
            trailList.add(entry);
        }
        tag.put(KEY_DODGE_TRAIL, trailList);
        return tag;
    }

    /**
     * Returns the saved data instance for this server, creating it if it doesn't exist yet.
     * Attached to the overworld so it persists regardless of which dimension Greg spawns in.
     */
    public static GregSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(GregSavedData::create, GregSavedData::load),
                DATA_NAME
        );
    }

    /**
     * Updates the stored Greg UUID and marks the data dirty so it will be written to disk.
     * Call this whenever a new Greg entity spawns — overwrites any previously recorded UUID.
     */
    public void setGregUUID(UUID uuid) {
        this.gregUUID = uuid;
        this.setDirty();
        LOGGER.info("GregSavedData: updated gregUUID={}", uuid);
    }

    /** Returns the UUID of the most recently spawned Greg, or null if none has spawned yet. */
    @Nullable
    public UUID getGregUUID() {
        return gregUUID;
    }

    /**
     * Updates the stored boss UUID and marks the data dirty so it will be written to disk.
     * Overwrites any previously recorded value.
     */
    public void setBossUUID(UUID uuid) {
        this.bossUUID = uuid;
        this.setDirty();
        LOGGER.info("GregSavedData: updated bossUUID={}", uuid);
    }

    /** Returns the UUID of the boss set via /setBossTarget, or null if none has been set. */
    @Nullable
    public UUID getBossUUID() {
        return bossUUID;
    }

    /** Appends a new dodge point to the list and marks dirty. */
    public void addDodgePoint(BlockPos pos) {
        dodgePoints.add(pos);
        this.setDirty();
        LOGGER.info("GregSavedData: added dodge point index={} pos={}", dodgePoints.size() - 1, pos);
    }

    /**
     * Removes the first dodge point at the given position and marks dirty.
     * Returns the index that was removed, or -1 if no point is stored there.
     *
     * <p>Note this shifts every later point's index down by one. Greg's dodge target is a BlockPos, not
     * an index, so it no longer goes stale on removal — but callers still clear it for a clean slate
     * (see DodgePointHandler.resetGregDodgeTarget).
     */
    public int removeDodgePoint(BlockPos pos) {
        int index = dodgePoints.indexOf(pos);
        if (index == -1) return -1;
        dodgePoints.remove(index);
        this.setDirty();
        LOGGER.info("GregSavedData: removed dodge point index={} pos={} ({} remain)", index, pos, dodgePoints.size());
        return index;
    }

    /** Clears all dodge points. The current dodge index lives on GregEntity and is reset there. */
    public void clearDodgePoints() {
        dodgePoints.clear();
        this.setDirty();
        LOGGER.info("GregSavedData: cleared all dodge points");
    }

    /** Returns an unmodifiable view of the dodge points list. */
    public List<BlockPos> getDodgePoints() {
        return Collections.unmodifiableList(dodgePoints);
    }

    /** Records the UUID of a spawned debugger display entity and marks dirty. */
    public void addDebugDisplay(UUID uuid) {
        debugDisplayUUIDs.add(uuid);
        this.setDirty();
    }

    /** Clears the tracked debugger display UUIDs and marks dirty. */
    public void clearDebugDisplays() {
        debugDisplayUUIDs.clear();
        this.setDirty();
    }

    /** Returns an unmodifiable view of the tracked debugger display UUIDs. */
    public List<UUID> getDebugDisplays() {
        return Collections.unmodifiableList(debugDisplayUUIDs);
    }

    /** Returns true while the dodge-point debugger is active. */
    public boolean isDebuggerActive() {
        return debuggerActive;
    }

    /** Sets the debugger-active flag and marks dirty. */
    public void setDebuggerActive(boolean active) {
        this.debuggerActive = active;
        this.setDirty();
    }

    /** Records the UUID of a spawned dodge-trail display entity and marks dirty. */
    public void addDodgeTrailDisplay(UUID uuid) {
        dodgeTrailUUIDs.add(uuid);
        this.setDirty();
    }

    /** Clears the tracked dodge-trail display UUIDs and marks dirty. */
    public void clearDodgeTrailDisplays() {
        dodgeTrailUUIDs.clear();
        this.setDirty();
    }

    /** Returns an unmodifiable view of the tracked dodge-trail display UUIDs. */
    public List<UUID> getDodgeTrailDisplays() {
        return Collections.unmodifiableList(dodgeTrailUUIDs);
    }

    /** Sets the pending boss alert signal. Overwrites any unread alert. */
    public void setPendingBossAlert(String alert) {
        this.pendingBossAlert = alert;
        LOGGER.info("GregSavedData: boss alert set={}", alert);
    }

    /** Returns the pending alert and clears it, or null if none is pending. */
    @Nullable
    public String pollPendingBossAlert() {
        String alert = this.pendingBossAlert;
        this.pendingBossAlert = null;
        return alert;
    }

    /** Raises the one-shot threat-hide signal. No setDirty() — runtime-only, never written to NBT. */
    public void setPendingThreatHide() {
        this.pendingThreatHide = true;
    }

    /** Returns whether a threat hide is pending and clears the signal. */
    public boolean pollPendingThreatHide() {
        boolean pending = this.pendingThreatHide;
        this.pendingThreatHide = false;
        return pending;
    }

    /**
     * Sets the hide-alert flag. Called every tick by HarbingerMixin with the current animation state,
     * so this is a level-triggered write, not an edge-triggered event — passing false is how the flag
     * clears. Logs only on a change, since this is called ~20 times a second.
     * No setDirty() — the flag is runtime-only and never written to NBT.
     */
    public void setHideAlertActive(boolean active) {
        if (this.hideAlertActive != active) {
            LOGGER.info("GregSavedData: hideAlertActive {} -> {}", this.hideAlertActive, active);
        }
        this.hideAlertActive = active;
    }

    /** Returns true while Greg should be breaking line of sight to the boss. */
    public boolean isHideAlertActive() {
        return hideAlertActive;
    }
}
