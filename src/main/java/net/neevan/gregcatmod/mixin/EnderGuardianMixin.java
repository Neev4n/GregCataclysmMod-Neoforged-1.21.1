package net.neevan.gregcatmod.mixin;

import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.Ender_Guardian_Entity;
import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.LLibrary_Boss_Monster;
import com.github.L_Ender.cataclysm.entity.AnimationMonster.LLibrary_Monster;
import com.github.L_Ender.lionfishapi.server.animation.Animation;
import com.github.L_Ender.lionfishapi.server.animation.IAnimatedEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.neevan.gregcatmod.data.GregSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects Ender Guardian attack animation transitions and signals GregSavedData.
 * Uses IAnimatedEntity.getAnimation() — fires an alert on the first tick a new
 * attack animation begins (i.e. when the current animation changes).
 */
@Mixin(value = Ender_Guardian_Entity.class, remap = false)
public abstract class EnderGuardianMixin extends LLibrary_Boss_Monster {

    /** The animation active on the previous tick — used to detect transitions. */
    @Unique private Animation prevAnimation = IAnimatedEntity.NO_ANIMATION;

    /** Required by Java; never called at runtime — Mixin operates at bytecode level. */
    @SuppressWarnings("rawtypes")
    protected EnderGuardianMixin(EntityType entityType, Level level) {
        super(entityType, level);
    }

    /**
     * Runs at the end of every server tick.
     * Fires a boss alert the first tick the Guardian's animation changes to a known attack.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        if (this.level().isClientSide()) return;

        Animation current = this.getAnimation();
        if (current != prevAnimation && current != IAnimatedEntity.NO_ANIMATION) {
            String attackName = getAttackName(current);
            if (attackName != null) {
                GregSavedData.get(((ServerLevel) this.level()).getServer()).setPendingBossAlert(attackName);
            }
        }
        prevAnimation = current;
    }

    /**
     * Maps a Guardian Animation to a string alert name.
     * Returns null for animations that are not attacks (e.g. FALLEN).
     */
    @Unique
    private String getAttackName(Animation animation) {
        if (animation == Ender_Guardian_Entity.GUARDIAN_RIGHT_STRONG_ATTACK) return "RIGHT_STRONG_ATTACK";
        if (animation == Ender_Guardian_Entity.GUARDIAN_LEFT_STRONG_ATTACK)  return "LEFT_STRONG_ATTACK";
        if (animation == Ender_Guardian_Entity.GUARDIAN_RIGHT_ATTACK)        return "RIGHT_ATTACK";
        if (animation == Ender_Guardian_Entity.GUARDIAN_LEFT_ATTACK)         return "LEFT_ATTACK";
        if (animation == Ender_Guardian_Entity.GUARDIAN_BURST_ATTACK)        return "BURST_ATTACK";
        if (animation == Ender_Guardian_Entity.GUARDIAN_UPPERCUT_AND_BULLET) return "UPPERCUT_AND_BULLET";
        if (animation == Ender_Guardian_Entity.GUARDIAN_RAGE_UPPERCUT)       return "RAGE_UPPERCUT";
        if (animation == Ender_Guardian_Entity.GUARDIAN_STOMP)               return "STOMP";
        if (animation == Ender_Guardian_Entity.GUARDIAN_RAGE_STOMP)         return "RAGE_STOMP";
        if (animation == Ender_Guardian_Entity.GUARDIAN_MASS_DESTRUCTION)    return "MASS_DESTRUCTION";
        if (animation == Ender_Guardian_Entity.GUARDIAN_HUG_ME)              return "HUG_ME";
        if (animation == Ender_Guardian_Entity.GUARDIAN_AIR_STRIKE1)         return "AIR_STRIKE1";
        if (animation == Ender_Guardian_Entity.GUARDIAN_AIR_STRIKE2)         return "AIR_STRIKE2";
        if (animation == Ender_Guardian_Entity.GUARDIAN_RIGHT_SWING)         return "RIGHT_SWING";
        if (animation == Ender_Guardian_Entity.GUARDIAN_LEFT_SWING)          return "LEFT_SWING";
        if (animation == Ender_Guardian_Entity.GUARDIAN_BLACKHOLE)           return "BLACKHOLE";
        if (animation == Ender_Guardian_Entity.GUARDIAN_ROCKETPUNCH)         return "ROCKETPUNCH";
        // GUARDIAN_FALLEN is a knockdown state, not an attack — excluded
        return null;
    }
}
