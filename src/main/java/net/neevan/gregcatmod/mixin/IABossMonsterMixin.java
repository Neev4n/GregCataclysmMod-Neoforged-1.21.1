package net.neevan.gregcatmod.mixin;

import com.github.L_Ender.cataclysm.entity.InternalAnimationMonster.IABossMonsters.IABoss_monster;
import com.github.L_Ender.cataclysm.entity.InternalAnimationMonster.Internal_Animation_Monster;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.level.Level;
import net.neevan.gregcatmod.entity.custom.GregEntity;
import org.spongepowered.asm.mixin.Mixin;

/** Injects a NearestAttackableTargetGoal into every IABoss_monster so they target Greg. */
@Mixin(value = IABoss_monster.class, remap = false)
public abstract class IABossMonsterMixin extends Internal_Animation_Monster {

    /** Required by Java; never called at runtime — Mixin operates at bytecode level. */
    protected IABossMonsterMixin(EntityType<? extends Internal_Animation_Monster> entityType, Level level) {
        super(entityType, level);
    }

    /** Appends a Greg-targeting goal after the boss registers its own goals. */
    @Override
    protected void registerGoals() {
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, GregEntity.class, true));
        super.registerGoals();
    }
}
