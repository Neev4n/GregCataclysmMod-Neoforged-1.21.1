package net.neevan.gregcatmod.data;


import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.UUID;

public class GregSavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Key used for the .dat file stored in the overworld's data folder. */
    private static final String DATA_NAME = "gregtcatmod_greg";

    /** NBT key for the Greg UUID field. */
    private static final String KEY_GREG_UUID = "GregUUID";

    /** UUID of the most recently spawned Greg entity, or null if none has ever spawned. */
    @Nullable
    private UUID gregUUID;

    /** Creates a fresh instance with no Greg UUID recorded. */
    public static GregSavedData create() {
        return new GregSavedData();
    }

    /** Loads an existing instance from NBT, restoring the saved Greg UUID if present. */
    public static GregSavedData load(CompoundTag tag, HolderLookup.Provider lookupProvider) {
        GregSavedData data = create();
        if (tag.hasUUID(KEY_GREG_UUID)) {
            data.gregUUID = tag.getUUID(KEY_GREG_UUID);
            LOGGER.info("GregSavedData: loaded gregUUID={}", data.gregUUID);
        }
        return data;
    }

    /** Writes the Greg UUID to NBT so it persists across server restarts. */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (gregUUID != null) {
            tag.putUUID(KEY_GREG_UUID, gregUUID);
        }
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
}
