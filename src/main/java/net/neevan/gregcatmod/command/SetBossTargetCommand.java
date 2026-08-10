package net.neevan.gregcatmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neevan.gregcatmod.util.GregSavedData;
import net.neoforged.neoforge.entity.PartEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import org.slf4j.Logger;

public class SetBossTargetCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Range in blocks to ray-cast for the target entity. */
    private static final double RANGE = 50.0;

    /** Registers the /setBossTarget command on the given dispatcher. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("setBossTarget")
                .requires(source -> source.hasPermission(2))
                .executes(context -> execute(context.getSource()))
        );
    }

    /**
     * Ray-casts from the player's eye position along their look direction up to RANGE blocks.
     * If a mob is found, prints its UUID to the player's chat. Returns 1 on success, 0 if nothing found.
     */
    private static int execute(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();
        Vec3 endPos = eyePos.add(lookVec.scale(RANGE));

        // Expand the player's bounding box toward the look direction to form the search volume
        AABB searchBox = player.getBoundingBox().expandTowards(lookVec.scale(RANGE)).inflate(1.0);

        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eyePos, endPos, searchBox,
                // Only mobs; exclude the player and multi-part sub-entities
                e -> e instanceof Mob && !(e instanceof PartEntity<?>),
                RANGE * RANGE
        );

        if (hit == null) {
            source.sendFailure(Component.literal("No mob found in front of you within " + (int) RANGE + " blocks."));
            LOGGER.info("[SetBossTarget] {} found no target", player.getName().getString());
            return 0;
        }

        String uuid = hit.getEntity().getUUID().toString();
        String type = hit.getEntity().getType().toShortString();

        GregSavedData.get(source.getServer()).setBossUUID(hit.getEntity().getUUID());

        source.sendSuccess(() -> Component.literal("Boss target set: " + type + " | UUID: " + uuid), false);
        LOGGER.info("[SetBossTarget] player={} set boss target type={} uuid={}", player.getName().getString(), type, uuid);
        return 1;
    }
}
