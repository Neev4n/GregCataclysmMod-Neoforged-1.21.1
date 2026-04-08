package net.neevan.gregcatmod.data;


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

    /** NBT key for the current dodge point index. */
    private static final String KEY_DODGE_INDEX = "CurrentDodgeIndex";

    /** UUID of the most recently spawned Greg entity, or null if none has ever spawned. */
    @Nullable
    private UUID gregUUID;

    /** UUID of the boss set via /setBossTarget, or null if none has been set. */
    @Nullable
    private UUID bossUUID;

    /** Ordered list of manually-placed dodge positions. Persisted across restarts. */
    private final List<BlockPos> dodgePoints = new ArrayList<>();

    /**
     * Index into dodgePoints indicating which point Greg is currently at.
     * -1 means unset — Greg will choose the closest point when dodging begins.
     */
    private int currentDodgeIndex = -1;

    /**
     * Transient signal set by the boss mixin when an attack fires.
     * Not persisted to NBT — runtime only. Cleared by Greg after he reads it.
     */
    @Nullable
    private String pendingBossAlert;

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
        data.currentDodgeIndex = tag.getInt(KEY_DODGE_INDEX); // defaults to 0 if absent, corrected below
        if (!tag.contains(KEY_DODGE_INDEX)) data.currentDodgeIndex = -1;
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
        tag.putInt(KEY_DODGE_INDEX, currentDodgeIndex);
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

    /** Clears all dodge points and resets the current index to -1. */
    public void clearDodgePoints() {
        dodgePoints.clear();
        currentDodgeIndex = -1;
        this.setDirty();
        LOGGER.info("GregSavedData: cleared all dodge points");
    }

    /** Returns an unmodifiable view of the dodge points list. */
    public List<BlockPos> getDodgePoints() {
        return Collections.unmodifiableList(dodgePoints);
    }

    /**
     * Returns the index of Greg's current dodge point, or -1 if unset.
     * When -1, the dodge system should pick the closest point.
     */
    public int getCurrentDodgeIndex() {
        return currentDodgeIndex;
    }

    /** Sets Greg's current dodge point index and marks dirty. */
    public void setCurrentDodgeIndex(int index) {
        this.currentDodgeIndex = index;
        this.setDirty();
        LOGGER.info("GregSavedData: currentDodgeIndex={}", index);
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
}
