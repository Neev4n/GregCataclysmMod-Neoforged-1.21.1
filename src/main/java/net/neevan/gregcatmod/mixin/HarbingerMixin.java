package net.neevan.gregcatmod.mixin;

import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.LLibrary_Boss_Monster;
import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.The_Harbinger_Entity;
import com.github.L_Ender.lionfishapi.server.animation.Animation;
import com.github.L_Ender.lionfishapi.server.animation.IAnimatedEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.neevan.gregcatmod.util.GregSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects Harbinger attack animation transitions and signals GregSavedData.
 * Uses IAnimatedEntity.getAnimation() — fires an alert on the first tick a new
 * attack animation begins (i.e. when the current animation changes).
 */
@Mixin(value = The_Harbinger_Entity.class, remap = false)
public abstract class HarbingerMixin extends LLibrary_Boss_Monster {

    /** The animation active on the previous tick — used to detect transitions. */
    @Unique private Animation prevAnimation = IAnimatedEntity.NO_ANIMATION;

    /** Required by Java; never called at runtime — Mixin operates at bytecode level. */
    @SuppressWarnings("rawtypes")
    protected HarbingerMixin(EntityType entityType, Level level) {
        super(entityType, level);
    }

    /**
     * Runs at the end of every server-side AI step (called each tick from LivingEntity.tick).
     * Fires a boss alert the first tick the Harbinger's animation changes to a known attack, and
     * republishes the hide-alert flag from the current animation.
     */
    @Inject(method = "aiStep", at = @At("TAIL"))
    private void onAiStep(CallbackInfo ci) {
        if (this.level().isClientSide()) return;

        GregSavedData data = GregSavedData.get(((ServerLevel) this.level()).getServer());
        Animation current = this.getAnimation();

        // Edge-triggered: the alert fires once, on the tick the animation changes
        if (current != prevAnimation && current != IAnimatedEntity.NO_ANIMATION) {
            String attackName = getAttackName(current);
            if (attackName != null) {
                data.setPendingBossAlert(attackName);
            }
        }

        // Level-triggered: rewritten every tick from the animation itself, so it clears the tick the
        // laser ends — including when the animation is cut short by a stun or phase change. A timer
        // seeded off the alert above would be a second source of truth and could only ever drift.
        data.setHideAlertActive(current == The_Harbinger_Entity.DEATHLASER_ANIMATION);

        prevAnimation = current;
    }

    /**
     * Maps a Harbinger Animation to a string alert name.
     * Returns null for non-attack animations (DEATH, STUN).
     */
    @Unique
    private String getAttackName(Animation animation) {
        if (animation == The_Harbinger_Entity.DEATHLASER_ANIMATION)        return "HARBINGER_DEATHLASER";
        if (animation == The_Harbinger_Entity.CHARGE_ANIMATION)            return "HARBINGER_CHARGE";
        if (animation == The_Harbinger_Entity.LAUNCH_ANIAMATION)           return "HARBINGER_LAUNCH";
        if (animation == The_Harbinger_Entity.MISSILE_FIRE_ANIAMATION)     return "HARBINGER_MISSILE_FIRE";
        if (animation == The_Harbinger_Entity.MISSILE_FIRE_FAST_ANIAMATION)return "HARBINGER_MISSILE_FIRE_FAST";
        // DEATH_ANIMATION and STUN_ANIAMATION are not attacks — excluded
        return null;
    }
}
