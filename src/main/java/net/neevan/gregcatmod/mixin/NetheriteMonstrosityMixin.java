package net.neevan.gregcatmod.mixin;

import com.github.L_Ender.cataclysm.entity.InternalAnimationMonster.IABossMonsters.IABoss_monster;
import com.github.L_Ender.cataclysm.entity.InternalAnimationMonster.IABossMonsters.NewNetherite_Monstrosity.Netherite_Monstrosity_Entity;
import com.github.L_Ender.cataclysm.entity.InternalAnimationMonster.Internal_Animation_Monster;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.neevan.gregcatmod.data.GregSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Detects Netherite Monstrosity attack phases via animation state transitions and signals GregSavedData. */
@Mixin(value = Netherite_Monstrosity_Entity.class, remap = false)
public abstract class NetheriteMonstrosityMixin extends IABoss_monster {

    @Shadow public AnimationState smashsAnimationState;
    @Shadow public AnimationState fireAnimationState;
    @Shadow public AnimationState drainAnimationState;
    @Shadow public AnimationState shouldercheckAnimationState;
    @Shadow public AnimationState overpowerAnimationState;
    @Shadow public AnimationState flareshotAnimationState;
    @Shadow public AnimationState phaseAnimationState;

    /** Previous-tick started state for each animation — used to detect transitions. */
    @Unique private boolean prev_smash;
    @Unique private boolean prev_fire;
    @Unique private boolean prev_drain;
    @Unique private boolean prev_shouldercheck;
    @Unique private boolean prev_overpower;
    @Unique private boolean prev_flareshot;
    @Unique private boolean prev_phase;

    /** Required by Java; never called at runtime — Mixin operates at bytecode level. */
    protected NetheriteMonstrosityMixin(EntityType<? extends Internal_Animation_Monster> entityType, Level level) {
        super(entityType, level);
    }

    /**
     * Checks each animation state at the end of every server tick.
     * Fires an alert the first tick an animation transitions from stopped to started.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        if (this.level().isClientSide()) return;

        checkTransition(smashsAnimationState.isStarted(),       prev_smash,        "SMASH");
        checkTransition(fireAnimationState.isStarted(),         prev_fire,         "FIRE");
        checkTransition(drainAnimationState.isStarted(),        prev_drain,        "DRAIN");
        checkTransition(shouldercheckAnimationState.isStarted(),prev_shouldercheck,"SHOULDER_CHECK");
        checkTransition(overpowerAnimationState.isStarted(),    prev_overpower,    "OVERPOWER");
        checkTransition(flareshotAnimationState.isStarted(),    prev_flareshot,    "FLARE_SHOT");
        checkTransition(phaseAnimationState.isStarted(),        prev_phase,        "PHASE_TRANSITION");

        prev_smash         = smashsAnimationState.isStarted();
        prev_fire          = fireAnimationState.isStarted();
        prev_drain         = drainAnimationState.isStarted();
        prev_shouldercheck = shouldercheckAnimationState.isStarted();
        prev_overpower     = overpowerAnimationState.isStarted();
        prev_flareshot     = flareshotAnimationState.isStarted();
        prev_phase         = phaseAnimationState.isStarted();
    }

    /** Fires the alert only on the tick the animation transitions from stopped to started. */
    @Unique
    private void checkTransition(boolean current, boolean previous, String attackName) {
        if (current && !previous) {
            GregSavedData.get(((ServerLevel) this.level()).getServer()).setPendingBossAlert(attackName);
        }
    }
}
