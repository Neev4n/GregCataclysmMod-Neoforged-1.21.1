package net.neevan.gregcatmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neevan.gregcatmod.data.GregSavedData;
import net.neevan.gregcatmod.entity.custom.GregEntity;
import org.slf4j.Logger;

import java.util.UUID;

public class ResetTargettingCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Registers the /resetTargetting command on the given dispatcher. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("resetTargetting")
                .requires(source -> source.hasPermission(2))
                .executes(context -> execute(context.getSource()))
        );
    }

    /**
     * Looks up Greg and the boss by their saved UUIDs, then mutually sets their targets.
     * Does nothing if either UUID is unset or the entity is not currently loaded.
     */
    private static int execute(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        GregSavedData data = GregSavedData.get(server);

        UUID gregUUID = data.getGregUUID();
        UUID bossUUID = data.getBossUUID();

        if (gregUUID == null) {
            source.sendFailure(Component.literal("No Greg UUID saved — spawn Greg first."));
            return 0;
        }
        if (bossUUID == null) {
            source.sendFailure(Component.literal("No boss UUID saved — use /setBossTarget first."));
            return 0;
        }

        // Search all loaded dimensions for the entities
        GregEntity greg = null;
        Mob boss = null;
        for (var level : server.getAllLevels()) {
            Entity e = level.getEntity(gregUUID);
            if (e instanceof GregEntity g) greg = g;

            e = level.getEntity(bossUUID);
            if (e instanceof Mob m) boss = m;
        }

        if (greg == null) {
            source.sendFailure(Component.literal("Greg is not currently loaded (UUID: " + gregUUID + ")."));
            LOGGER.info("[ResetTargetting] Greg not found uuid={}", gregUUID);
            return 0;
        }
        if (boss == null) {
            source.sendFailure(Component.literal("Boss is not currently loaded (UUID: " + bossUUID + ")."));
            LOGGER.info("[ResetTargetting] Boss not found uuid={}", bossUUID);
            return 0;
        }

        greg.setTarget(boss);
        boss.setTarget(greg);

        String bossType = boss.getType().toShortString();
        source.sendSuccess(() -> Component.literal(
                "Targetting reset: Greg -> " + bossType + " | " + bossType + " -> Greg"), false);
        LOGGER.info("[ResetTargetting] Greg uuid={} -> boss type={} uuid={}", gregUUID, bossType, bossUUID);
        return 1;
    }
}
