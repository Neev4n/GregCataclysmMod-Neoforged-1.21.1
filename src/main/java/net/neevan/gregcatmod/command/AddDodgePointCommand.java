package net.neevan.gregcatmod.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neevan.gregcatmod.data.GregSavedData;

public class AddDodgePointCommand {

    /** Registers the /addDodgePoint command on the given dispatcher. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("addDodgePoint")
                .requires(source -> source.hasPermission(2))
                .executes(context -> execute(context.getSource()))
        );
    }

    /** Adds the block the player is looking at as a new dodge point (max 20 block reach). */
    private static int execute(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        HitResult hit = player.pick(20.0, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Not looking at a block (max range 20 blocks)"));
            return 0;
        }
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        GregSavedData data = GregSavedData.get(source.getServer());

        data.addDodgePoint(pos);
        int index = data.getDodgePoints().size() - 1;

        source.sendSuccess(() -> Component.literal(
                "Dodge point [" + index + "] added at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                + " (" + data.getDodgePoints().size() + " total)"), false);
        return 1;
    }
}
