package net.neevan.gregcatmod.mixin;

import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.LLibrary_Boss_Monster;
import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.The_Harbinger_Entity;
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
     * Fires a boss alert the first tick the Harbinger's animation changes to a known attack.
     */
    @Inject(method = "aiStep", at = @At("TAIL"))
    private void onAiStep(CallbackInfo ci) {
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
