package net.neevan.gregcatmod.event;

import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.The_Harbinger_Entity;
import com.github.L_Ender.lionfishapi.server.animation.Animation;
import com.github.L_Ender.lionfishapi.server.event.AnimationEvent;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neevan.gregcatmod.GregCataclysmMod;
import net.neevan.gregcatmod.command.*;
import net.neevan.gregcatmod.data.GregSavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/** Subscribes to game bus events (runtime events, commands, etc.). */
@EventBusSubscriber(modid = GregCataclysmMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class ModGameBusEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Registers all mod commands when the server sets up its command dispatcher. */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        SetBossTargetCommand.register(event.getDispatcher());
        ResetTargettingCommand.register(event.getDispatcher());
        TestAlertCommand.register(event.getDispatcher());
        AddDodgePointCommand.register(event.getDispatcher());
        ClearDodgePointsCommand.register(event.getDispatcher());
        GetBossHealthCommand.register(event.getDispatcher());
    }

    /**
     * Fired by AnimationHandler.updateAnimations on the first tick of every new lionfishapi animation.
     * Used for bosses whose animations cannot be reliably detected via mixin tick injection
     * (e.g. The Harbinger, which overrides aiStep without a consistent injection point).
     * Add further instanceof checks here for any additional LLibrary bosses that need alert coverage.
     */
    @SubscribeEvent
    public static void onAnimationStart(AnimationEvent.Start<?> event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel serverLevel)) return;

        String alertName = null;

        if (entity instanceof The_Harbinger_Entity) {
            alertName = getHarbingerAlertName(event.getAnimation());
        }

        if (alertName == null) return;

        LOGGER.info("[Greg] AnimationEvent.Start: entity={} alert={}", entity.getType().toShortString(), alertName);
        GregSavedData.get(serverLevel.getServer()).setPendingBossAlert(alertName);
    }

    /**
     * Maps a Harbinger Animation to an alert name string.
     * Returns null for non-attack animations (DEATH, STUN).
     */
    private static String getHarbingerAlertName(Animation animation) {
        if (animation == The_Harbinger_Entity.DEATHLASER_ANIMATION)         return "HARBINGER_DEATHLASER";
        if (animation == The_Harbinger_Entity.CHARGE_ANIMATION)             return "HARBINGER_CHARGE";
        if (animation == The_Harbinger_Entity.LAUNCH_ANIAMATION)            return "HARBINGER_LAUNCH";
        if (animation == The_Harbinger_Entity.MISSILE_FIRE_ANIAMATION)      return "HARBINGER_MISSILE_FIRE";
        if (animation == The_Harbinger_Entity.MISSILE_FIRE_FAST_ANIAMATION) return "HARBINGER_MISSILE_FIRE_FAST";
        // DEATH_ANIMATION and STUN_ANIAMATION excluded
        return null;
    }
}
