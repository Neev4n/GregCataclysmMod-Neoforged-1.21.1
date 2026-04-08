package net.neevan.gregcatmod.entity.custom;


import com.mojang.logging.LogUtils;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.LLibrary_Boss_Monster;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import java.util.ArrayList;
import java.util.List;

/**
 * Greg — a custom chicken entity with reduced health (4 HP) and standard movement speed.
 * Extends vanilla Chicken, inheriting all AI goals, egg-laying, and rendering.
 * Always named "Greg" via EntityJoinLevelEvent in GregTwilightMod.
 * Actively targets all Twilight Forest bosses via NearestAttackableTargetGoal.
 */
public class GregEntity extends Chicken implements RangedAttackMob{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final float ATTACK_RADIUS = 20.0F;
    private static final int ATTACK_INTERVAL = 1; // ticks between shots

    /** Counts down to the next shot; -1 means uninitialised. */
    private int attackTime = -1;
    /** Consecutive ticks Greg has had line of sight on his target. */
    private int seeTime = 0;

    /** Constructs Greg and logs which dimension and side (client/server) he was created on. */
    public GregEntity(EntityType<? extends Chicken> entityType, Level level) {
        super(entityType, level);
        LOGGER.info("[Greg] Created in dimension={} isClient={}", level.dimension().location(), level.isClientSide());
    }

    /**
     * Defines Greg's base attributes: 4 HP max health, 0.25 movement speed,
     * and 100 block follow range so Greg detects bosses from far away.
     * Called by EntityAttributeCreationEvent in ModEventBusEvents.
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 4.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.FOLLOW_RANGE, 100.0);
    }

    /**
     * Extends Chicken's default goals with a boss targeting goal.
     * Targets any LLibrary_Boss_Monster within Greg's follow range (100 blocks).
     */
    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, LLibrary_Boss_Monster.class, true));
        LOGGER.info("[Greg] Registered goals");
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide()) {
            tickRangedAttack();
        }
    }

    /**
     * Runs the ranged attack timer each server tick.
     * Mirrors RangedAttackGoal's seeTime + countdown logic but without movement,
     * so it fires independently of whatever movement goal is currently active.
     */
    private void tickRangedAttack() {
        LivingEntity target = this.getTarget();
        if (target == null) {
            // Reset state when Greg has no target
            attackTime = -1;
            seeTime = 0;
            return;
        }

        // Track consecutive ticks with line of sight — require 5 before the first shot
        if (this.getSensing().hasLineOfSight(target)) {
            seeTime++;

        } else {
            seeTime = 0;
        }

        if (--attackTime == 0) {
            if (seeTime >= 5) {
                float distanceFactor = Mth.clamp(
                        (float) Math.sqrt(this.distanceToSqr(target)) / ATTACK_RADIUS,
                        0.1F, 1.0F
                );
                this.performRangedAttack(target, distanceFactor);
            }
            attackTime = ATTACK_INTERVAL;
        } else if (attackTime < 0) {
            attackTime = ATTACK_INTERVAL;
        }
    }

    /** Delegates to fireSonicBoomAt so the interface contract is satisfied. */
    @Override
    public void performRangedAttack(LivingEntity target, float distanceFactor) {
        fireSonicBoomAt(target);
    }

    /**
     * Fires a sonic boom from Greg's eye position toward the centre of the target's bounding box.
     * Mirrors the Warden's SonicBoom behavior: sends SONIC_BOOM particles along the ray,
     */
    private void fireSonicBoomAt(Entity target) {

        double eyeX = this.getX();
        double eyeY = this.getEyeY();
        double eyeZ = this.getZ();

        // Aim at the centre of the target's bounding box
        double aimY = target.getBoundingBox().getCenter().y;
        Vec3 direction = new Vec3(target.getX() - eyeX, aimY - eyeY, target.getZ() - eyeZ).normalize();

        // Origin is 3 blocks ahead of Greg in the direction of the target
        Vec3 origin = new Vec3(eyeX, eyeY, eyeZ).add(direction.scale(3.0));

        // Send particles along the ray from the origin to the target
        ServerLevel serverLevel = (ServerLevel) this.level();
        int rayLength = Mth.floor(this.distanceTo(target)) + 7;
        for (int i = 1; i < rayLength; i++) {
            Vec3 particlePos = origin.add(direction.scale(i));
            serverLevel.sendParticles(ParticleTypes.SONIC_BOOM, particlePos.x, particlePos.y, particlePos.z, 1, 0.0, 0.0, 0.0, 0.0);
        }

        this.playSound(SoundEvents.WARDEN_SONIC_BOOM, 3.0F, 1.0F);

        // Deal damage and apply knockback matching the Warden's sonic boom
        if (target.hurt(this.level().damageSources().sonicBoom(this), 10.0F)) {
            if (target instanceof LivingEntity living) {
                double knockH = 2.5 * (1.0 - living.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
                double knockV = 0.5 * (1.0 - living.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
                living.push(direction.x * knockH, direction.y * knockV, direction.z * knockH);
            }
        }

        LOGGER.info("[Greg] Fired SonicBoom at entity={} pos={}", target.getType().toShortString(), this.blockPosition());
    }

    /**
     * Fires a WitherSkull from 1 block ahead of Greg toward the given entity.
     * Aims at the centre of the entity's bounding box. Skips if the entity is within 3 blocks.
     */
    private void fireWitherSkullAt(Entity target) {
        if (this.distanceTo(target) <= 3.0F) {
            LOGGER.info("[Greg] Target too close to shoot (distance={}), skipping", this.distanceTo(target));
            return;
        }

        double eyeX = this.getX();
        double eyeY = this.getEyeY();
        double eyeZ = this.getZ();

        // Aim at the centre of the target's bounding box
        double aimY = target.getBoundingBox().getCenter().y;
        double dx = target.getX() - eyeX;
        double dy = aimY - eyeY;
        double dz = target.getZ() - eyeZ;
        Vec3 direction = new Vec3(dx, dy, dz).normalize();

        // Spawn 1 block ahead of Greg in the direction of the target
        WitherSkull skull = new WitherSkull(this.level(), this, direction);
        skull.setOwner(this);
        skull.setPosRaw(eyeX + direction.x, eyeY + direction.y, eyeZ + direction.z);
        this.level().addFreshEntity(skull);

        if (!this.isSilent()) {
            this.level().levelEvent(null, 1024, this.blockPosition(), 0);
        }

        LOGGER.info("[Greg] Fired WitherSkull at entity={} pos={}", target.getType().toShortString(), this.blockPosition());
    }

    /**
     * Returns all threats within 20 blocks of Greg.
     */
    public List<Entity> getThreatList() {
        List<Entity> threats = new ArrayList<>();
        var aabb = this.getBoundingBox().inflate(20.0);
        //threats.addAll(this.level().getEntitiesOfClass(LichBomb.class, aabb));

        for (Entity threat : threats) {
            LOGGER.info("[Greg] Threat detected: type={} pos={}", threat.getType().toShortString(), threat.blockPosition());
        }

        return threats;
    }

    /**
     * Intercepts incoming damage to log the source, amount, whether it landed,
     * and Greg's remaining HP. Server-side only to avoid duplicate logs.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean result = super.hurt(source, amount);
        if (!this.level().isClientSide()) {
            LOGGER.info("[Greg] hurt by={} amount={} result={} hp={}/{}",
                    source.type().msgId(), amount, result, this.getHealth(), this.getMaxHealth());
        }
        return result;
    }

    /**
     * Logs Greg's death cause and position before delegating to vanilla death logic
     * (loot drops, death animation trigger, stat tracking).
     */
    @Override
    public void die(DamageSource cause) {
        if (!this.level().isClientSide()) {
            LOGGER.info("[Greg] died cause={} pos={}", cause.type().msgId(), this.blockPosition());
        }
        super.die(cause);
    }

    /**
     * Fires after Greg is fully added to the level (chunk loaded, entity tracked).
     * Logs spawn position and dimension. Server-side only.
     */
    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (!this.level().isClientSide()) {
            LOGGER.info("[Greg] added to level pos={} dimension={}", this.blockPosition(), this.level().dimension().location());
        }
    }

    /**
     * Fires whenever Greg is removed from the world for any reason (killed, unloaded,
     * /kill command, dimension change). Logs the reason and last known position.
     */
    @Override
    public void remove(Entity.RemovalReason reason) {
        if (!this.level().isClientSide()) {
            LOGGER.info("[Greg] removed reason={} pos={}", reason, this.blockPosition());
        }
        super.remove(reason);
    }
}