package net.neevan.gregcatmod.mixin;

import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.LLibrary_Boss_Monster;
import com.github.L_Ender.cataclysm.entity.AnimationMonster.LLibrary_Monster;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.level.Level;
import net.neevan.gregcatmod.entity.custom.GregEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Injects a NearestAttackableTargetGoal into every LLibrary_Boss_Monster so they target Greg. */
@Mixin(value = LLibrary_Boss_Monster.class, remap = false)
public abstract class LLibraryBossMonsterMixin extends LLibrary_Monster {

    /** Required by Java; never called at runtime — Mixin operates at bytecode level. */
    protected LLibraryBossMonsterMixin(EntityType<? extends LLibrary_Monster> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected void registerGoals() {
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, GregEntity.class, false));
        super.registerGoals();
    }


}
