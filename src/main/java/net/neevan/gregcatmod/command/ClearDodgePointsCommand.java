package net.neevan.gregcatmod.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neevan.gregcatmod.data.GregSavedData;

public class ClearDodgePointsCommand {

    /** Registers the /clearDodgePoints command on the given dispatcher. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("clearDodgePoints")
                .requires(source -> source.hasPermission(2))
                .executes(context -> execute(context.getSource()))
        );
    }

    /** Clears all saved dodge points and resets Greg's current dodge index to -1. */
    private static int execute(CommandSourceStack source) {
        GregSavedData.get(source.getServer()).clearDodgePoints();
        source.sendSuccess(() -> Component.literal("All dodge points cleared."), false);
        return 1;
    }
}
