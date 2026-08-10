package net.neevan.gregcatmod.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neevan.gregcatmod.util.GregSavedData;

public class TestAlertCommand {

    /** Registers the /testAlert command on the given dispatcher. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("testAlert")
                .requires(source -> source.hasPermission(2))
                .executes(context -> execute(context.getSource()))
        );
    }

    /** Manually sets a TEST alert in GregSavedData so Greg's tickBossAlert can pick it up. */
    private static int execute(CommandSourceStack source) {
        GregSavedData.get(source.getServer()).setPendingBossAlert("TEST");
        source.sendSuccess(() -> Component.literal("Alert 'TEST' set — watch for Greg's response in chat."), false);
        return 1;
    }
}
