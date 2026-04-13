package net.neevan.gregcatmod.event;

import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.Ignis_Entity;
import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.The_Harbinger_Entity;
import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.The_Leviathan.The_Leviathan_Entity;
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
        ClearBlocksCommand.register(event.getDispatcher());
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
        } else if (entity instanceof The_Leviathan_Entity) {
            alertName = getLeviathanAlertName(event.getAnimation());
        } else if (entity instanceof Ignis_Entity) {
            alertName = getIgnisAlertName(event.getAnimation());
        }

        if (alertName == null) return;

        LOGGER.info("[Greg] AnimationEvent.Start: entity={} alert={}", entity.getType().toShortString(), alertName);
        GregSavedData.get(serverLevel.getServer()).setPendingBossAlert(alertName);
    }

    /**
     * Maps an Ignis Animation to an alert name string.
     * Returns null for non-attack animations (PHASE_2, PHASE_3, IGNIS_DEATH).
     */
    private static String getIgnisAlertName(Animation animation) {
        if (animation == Ignis_Entity.SWING_ATTACK)                        return "IGNIS_SWING_ATTACK";
        if (animation == Ignis_Entity.SWING_ATTACK_SOUL)                   return "IGNIS_SWING_ATTACK_SOUL";
        if (animation == Ignis_Entity.SWING_ATTACK_BERSERK)                return "IGNIS_SWING_ATTACK_BERSERK";
        if (animation == Ignis_Entity.HORIZONTAL_SWING_ATTACK)             return "IGNIS_HORIZONTAL_SWING_ATTACK";
        if (animation == Ignis_Entity.HORIZONTAL_SWING_ATTACK_SOUL)        return "IGNIS_HORIZONTAL_SWING_ATTACK_SOUL";
        if (animation == Ignis_Entity.SHIELD_SMASH_ATTACK)                 return "IGNIS_SHIELD_SMASH_ATTACK";
        if (animation == Ignis_Entity.POKE_ATTACK)                         return "IGNIS_POKE_ATTACK";
        if (animation == Ignis_Entity.POKE_ATTACK2)                        return "IGNIS_POKE_ATTACK2";
        if (animation == Ignis_Entity.POKE_ATTACK3)                        return "IGNIS_POKE_ATTACK3";
        if (animation == Ignis_Entity.POKED_ATTACK)                        return "IGNIS_POKED_ATTACK";
        if (animation == Ignis_Entity.MAGIC_ATTACK)                        return "IGNIS_MAGIC_ATTACK";
        if (animation == Ignis_Entity.SMASH_IN_AIR)                        return "IGNIS_SMASH_IN_AIR";
        if (animation == Ignis_Entity.SMASH)                               return "IGNIS_SMASH";
        if (animation == Ignis_Entity.BODY_CHECK_ATTACK1)                  return "IGNIS_BODY_CHECK_ATTACK1";
        if (animation == Ignis_Entity.BODY_CHECK_ATTACK2)                  return "IGNIS_BODY_CHECK_ATTACK2";
        if (animation == Ignis_Entity.BODY_CHECK_ATTACK3)                  return "IGNIS_BODY_CHECK_ATTACK3";
        if (animation == Ignis_Entity.BODY_CHECK_ATTACK4)                  return "IGNIS_BODY_CHECK_ATTACK4";
        if (animation == Ignis_Entity.BODY_CHECK_ATTACK_SOUL1)             return "IGNIS_BODY_CHECK_ATTACK_SOUL1";
        if (animation == Ignis_Entity.BODY_CHECK_ATTACK_SOUL2)             return "IGNIS_BODY_CHECK_ATTACK_SOUL2";
        if (animation == Ignis_Entity.BODY_CHECK_ATTACK_SOUL3)             return "IGNIS_BODY_CHECK_ATTACK_SOUL3";
        if (animation == Ignis_Entity.BODY_CHECK_ATTACK_SOUL4)             return "IGNIS_BODY_CHECK_ATTACK_SOUL4";
        if (animation == Ignis_Entity.COUNTER)                             return "IGNIS_COUNTER";
        if (animation == Ignis_Entity.STRIKE)                              return "IGNIS_STRIKE";
        if (animation == Ignis_Entity.COMBO1)                              return "IGNIS_COMBO1";
        if (animation == Ignis_Entity.COMBO2)                              return "IGNIS_COMBO2";
        if (animation == Ignis_Entity.BREAK_THE_SHIELD)                    return "IGNIS_BREAK_THE_SHIELD";
        if (animation == Ignis_Entity.SWING_UPPERCUT)                      return "IGNIS_SWING_UPPERCUT";
        if (animation == Ignis_Entity.SWING_UPPERSLASH)                    return "IGNIS_SWING_UPPERSLASH";
        if (animation == Ignis_Entity.SPIN_ATTACK)                         return "IGNIS_SPIN_ATTACK";
        if (animation == Ignis_Entity.EARTH_SHUDDERS_ATTACK)               return "IGNIS_EARTH_SHUDDERS_ATTACK";
        if (animation == Ignis_Entity.HORIZONTAL_SMALL_SWING_ATTACK)       return "IGNIS_HORIZONTAL_SMALL_SWING_ATTACK";
        if (animation == Ignis_Entity.HORIZONTAL_SMALL_SWING_ALT_ATTACK2)  return "IGNIS_HORIZONTAL_SMALL_SWING_ALT_ATTACK2";
        if (animation == Ignis_Entity.REINFORCED_SMASH_IN_AIR)             return "IGNIS_REINFORCED_SMASH_IN_AIR";
        if (animation == Ignis_Entity.REINFORCED_SMASH)                    return "IGNIS_REINFORCED_SMASH";
        if (animation == Ignis_Entity.REINFORCED_SMASH_IN_AIR_SOUL)        return "IGNIS_REINFORCED_SMASH_IN_AIR_SOUL";
        if (animation == Ignis_Entity.REINFORCED_SMASH_SOUL)               return "IGNIS_REINFORCED_SMASH_SOUL";
        if (animation == Ignis_Entity.SHIELD_BREAK_COUNTER)                return "IGNIS_SHIELD_BREAK_COUNTER";
        if (animation == Ignis_Entity.SHIELD_BREAK_STRIKE)                 return "IGNIS_SHIELD_BREAK_STRIKE";
        if (animation == Ignis_Entity.ULTIMATE_ATTACK)                     return "IGNIS_ULTIMATE_ATTACK";
        // PHASE_2, PHASE_3, IGNIS_DEATH excluded
        return null;
    }

    /**
     * Maps a Leviathan Animation to an alert name string.
     * Returns null for non-attack animations (STUN, PHASE2, DEATH).
     */
    private static String getLeviathanAlertName(Animation animation) {
        if (animation == The_Leviathan_Entity.LEVIATHAN_GRAB)                     return "LEVIATHAN_GRAB";
        if (animation == The_Leviathan_Entity.LEVIATHAN_GRAB_BITE)                return "LEVIATHAN_GRAB_BITE";
        if (animation == The_Leviathan_Entity.LEVIATHAN_BITE)                     return "LEVIATHAN_BITE";
        if (animation == The_Leviathan_Entity.LEVIATHAN_ABYSS_BLAST)              return "LEVIATHAN_ABYSS_BLAST";
        if (animation == The_Leviathan_Entity.LEVIATHAN_ABYSS_BLAST_FIRE)         return "LEVIATHAN_ABYSS_BLAST_FIRE";
        if (animation == The_Leviathan_Entity.LEVIATHAN_RUSH)                     return "LEVIATHAN_RUSH";
        if (animation == The_Leviathan_Entity.LEVIATHAN_ABYSS_BLAST_PORTAL)       return "LEVIATHAN_ABYSS_BLAST_PORTAL";
        if (animation == The_Leviathan_Entity.LEVIATHAN_TENTACLE_STRIKE_UPPER_R)  return "LEVIATHAN_TENTACLE_STRIKE_UPPER_R";
        if (animation == The_Leviathan_Entity.LEVIATHAN_TENTACLE_STRIKE_LOWER_R)  return "LEVIATHAN_TENTACLE_STRIKE_LOWER_R";
        if (animation == The_Leviathan_Entity.LEVIATHAN_TENTACLE_STRIKE_UPPER_L)  return "LEVIATHAN_TENTACLE_STRIKE_UPPER_L";
        if (animation == The_Leviathan_Entity.LEVIATHAN_TENTACLE_STRIKE_LOWER_L)  return "LEVIATHAN_TENTACLE_STRIKE_LOWER_L";
        if (animation == The_Leviathan_Entity.LEVIATHAN_TENTACLE_HOLD)            return "LEVIATHAN_TENTACLE_HOLD";
        if (animation == The_Leviathan_Entity.LEVIATHAN_TENTACLE_HOLD_BLAST)      return "LEVIATHAN_TENTACLE_HOLD_BLAST";
        if (animation == The_Leviathan_Entity.LEVIATHAN_TAIL_WHIPS)               return "LEVIATHAN_TAIL_WHIPS";
        if (animation == The_Leviathan_Entity.LEVIATHAN_BREAK_DIMENSION)          return "LEVIATHAN_BREAK_DIMENSION";
        if (animation == The_Leviathan_Entity.LEVIATHAN_MINE)                     return "LEVIATHAN_MINE";
        // LEVIATHAN_STUN, LEVIATHAN_PHASE2, LEVIATHAN_DEATH excluded
        return null;
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
