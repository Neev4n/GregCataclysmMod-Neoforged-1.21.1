package net.neevan.gregcatmod.event;

import net.neevan.gregcatmod.GregCataclysmMod;
import net.neevan.gregcatmod.command.AddDodgePointCommand;
import net.neevan.gregcatmod.command.ClearDodgePointsCommand;
import net.neevan.gregcatmod.command.ResetTargettingCommand;
import net.neevan.gregcatmod.command.SetBossTargetCommand;
import net.neevan.gregcatmod.command.TestAlertCommand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Subscribes to game bus events (runtime events, commands, etc.). */
@EventBusSubscriber(modid = GregCataclysmMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class ModGameBusEvents {

    /** Registers all mod commands when the server sets up its command dispatcher. */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        SetBossTargetCommand.register(event.getDispatcher());
        ResetTargettingCommand.register(event.getDispatcher());
        TestAlertCommand.register(event.getDispatcher());
        AddDodgePointCommand.register(event.getDispatcher());
        ClearDodgePointsCommand.register(event.getDispatcher());
    }
}
