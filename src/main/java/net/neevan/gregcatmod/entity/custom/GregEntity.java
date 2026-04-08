package net.neevan.gregcatmod.entity.custom;


import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.neevan.gregcatmod.data.GregSavedData;
import net.minecraft.util.Mth;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.gameevent.GameEvent;
import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.LLibrary_Boss_Monster;
import com.github.L_Ender.cataclysm.entity.InternalAnimationMonster.IABossMonsters.IABoss_monster;
import com.github.L_Ender.cataclysm.entity.projectile.Flare_Bomb_Entity;
import com.github.L_Ender.cataclysm.entity.projectile.Lava_Bomb_Entity;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import javax.annotation.Nullable;
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

    /** Synced to client so the held bottle item renders during drinking. */
    private static final EntityDataAccessor<Boolean> DATA_USING_ITEM =
            SynchedEntityData.defineId(GregEntity.class, EntityDataSerializers.BOOLEAN);

    /** Ticks remaining in the current potion drink; 0 when idle. */
    private int usingTime = 0;

    private static final float ATTACK_RADIUS = 20.0F;
    private static final int ATTACK_INTERVAL = 1; // ticks between shots
    /** Ticks between threat-triggered dodges; prevents spamming one dodge per tick. */
    private static final int THREAT_DODGE_COOLDOWN = 40;

    /** Counts down to the next shot; -1 means uninitialised. */
    private int attackTime = -1;
    /** Counts down between threat-triggered dodges; 0 means ready to dodge again. */
    private int threatDodgeCooldown = 0;
    /** Suppresses wither skull firing for 20 ticks after a dodge to avoid self-damage. */
    private int dodgeShootLockout = 0;

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

    /** Registers DATA_USING_ITEM alongside Chicken's existing synced data. */
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_USING_ITEM, false);
    }

    /** Returns true while Greg is in the middle of drinking a potion. */
    public boolean isDrinkingPotion() {
        return this.getEntityData().get(DATA_USING_ITEM);
    }

    /** Sets the drinking flag (synced to client for the bottle visual). */
    private void setUsingItem(boolean using) {
        this.getEntityData().set(DATA_USING_ITEM, using);
    }

    /**
     * Extends Chicken's default goals with a boss targeting goal.
     * Targets any LLibrary_Boss_Monster within Greg's follow range (100 blocks).
     */
    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(3, new AvoidEntityGoal<>(this, IABoss_monster.class, 20.0F, 1.0, 1.0));
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, LLibrary_Boss_Monster.class, false));
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, IABoss_monster.class, false));
        LOGGER.info("[Greg] Registered goals");
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide()) {
            tickRangedAttack();
            tickBossAlert();
            tickThreatDetection();
            tickPotionDrinking();
        }
    }

    /**
     * Mirrors the Witch's potion-drinking logic.
     * While drinking: counts down usingTime and applies the effect when it reaches 0.
     * While idle: checks conditions and starts drinking regeneration, fire resistance,
     * or resistance potions as needed.
     */
    private void tickPotionDrinking() {
        if (this.isDrinkingPotion()) {
            if (--usingTime <= 0) {
                // Finish drinking — apply the potion effect and clear the hand
                this.setUsingItem(false);
                ItemStack held = this.getMainHandItem();
                this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                PotionContents contents = held.get(DataComponents.POTION_CONTENTS);
                if (held.is(Items.POTION) && contents != null) {
                    contents.forEachEffect(this::addEffect);
                    LOGGER.info("[Greg] Finished drinking potion");
                }
                this.gameEvent(GameEvent.DRINK);
            }
        } else {
            // Decide which potion to drink, in priority order
            ItemStack potion = null;
            if (!this.hasEffect(MobEffects.FIRE_RESISTANCE)) {
                potion = PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE);
                LOGGER.info("[Greg] Starting fire resistance potion");
            } else if (!this.hasEffect(MobEffects.REGENERATION)) {
                potion = PotionContents.createItemStack(Items.POTION, Potions.REGENERATION);
                LOGGER.info("[Greg] Starting regeneration potion");

            }

            if (potion != null) {
                this.setItemSlot(EquipmentSlot.MAINHAND, potion);
                this.usingTime = this.getMainHandItem().getUseDuration(this);
                this.setUsingItem(true);
                this.level().playSound(
                        null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.WITCH_DRINK, this.getSoundSource(),
                        1.0F, 0.8F + this.random.nextFloat() * 0.4F
                );
            }
        }
    }

    /**
     * Polls GregSavedData for a pending boss alert each server tick.
     * On alert, broadcasts the alert to players and triggers a dodge.
     */
    private void tickBossAlert() {
        ServerLevel serverLevel = (ServerLevel) this.level();
        GregSavedData data = GregSavedData.get(serverLevel.getServer());
        String alert = data.pollPendingBossAlert();
        if (alert == null) return;

        LOGGER.info("[Greg] Received boss alert: {}", alert);
        serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("[Greg] Boss alert: " + alert), false);

        if (alert.equals("FLARE_SHOT") || alert.equals("OVERPOWER") || alert.equals("FIRE")){
            return;
        }
        performDodge(serverLevel, data);
    }

    /**
     * Checks for Netherite Monstrosity projectiles (Flare_Bomb_Entity, Lava_Bomb_Entity)
     * within 20 blocks each tick. Triggers a dodge on first detection; a 40-tick cooldown
     * prevents re-triggering for every tick the threat remains in range.
     */
    private void tickThreatDetection() {
        if (threatDodgeCooldown > 0) {
            threatDodgeCooldown--;
            return;
        }
        List<Entity> threats = getThreatList();
        if (threats.isEmpty()) return;

        LOGGER.info("[Greg] {} threat(s) detected nearby, triggering dodge", threats.size());
        ServerLevel serverLevel = (ServerLevel) this.level();
        GregSavedData data = GregSavedData.get(serverLevel.getServer());
        performDodge(serverLevel, data);
        threatDodgeCooldown = THREAT_DODGE_COOLDOWN;
    }

    /**
     * Core dodge logic: picks the next dodge point, fires an arrow toward it,
     * and leashes Greg to that arrow. Called by both tickBossAlert and tickThreatDetection.
     */
    private void performDodge(ServerLevel serverLevel, GregSavedData data) {
        List<BlockPos> dodgePoints = data.getDodgePoints();
        if (dodgePoints.isEmpty()) {
            LOGGER.info("[Greg] No dodge points set, skipping dodge");
            return;
        }

        // Drop existing leash if present
        if (this.isLeashed()) {
            this.dropLeash(true, false);
            LOGGER.info("[Greg] Dropped existing leash");
        }

        // Pick target dodge point — any point except the current index
        int currentIndex = data.getCurrentDodgeIndex();
        int targetIndex = pickDodgeTarget(dodgePoints, currentIndex);
        BlockPos target = dodgePoints.get(targetIndex);
        data.setCurrentDodgeIndex(targetIndex);

        // Fire an arrow toward the target dodge point and leash Greg to it
        AbstractArrow arrow = fireDodgeArrow(serverLevel, target);
        this.setLeashedTo(arrow, true);
        dodgeShootLockout = 40;

        // --- Mount approach (disabled — arrow dismount on ground not reliable) ---
        // if (this.isPassenger()) {
        //     this.stopRiding();
        //     LOGGER.info("[Greg] Stopped riding previous arrow");
        // }
        // this.startRiding(arrow, true);

        LOGGER.info("[Greg] Dodging to point [{}] at {} via arrow", targetIndex, target);
        serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("[Greg] Dodging to point [" + targetIndex + "]"), false);
    }

    /**
     * Picks a dodge target index from the list, excluding the current index.
     * If index is -1 (unset), picks the closest point to Greg's current position.
     */
    private int pickDodgeTarget(List<BlockPos> dodgePoints, int currentIndex) {
        if (currentIndex == -1) {
            // Pick closest point
            int closest = 0;
            double closestDist = Double.MAX_VALUE;
            for (int i = 0; i < dodgePoints.size(); i++) {
                double dist = this.blockPosition().distSqr(dodgePoints.get(i));
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = i;
                }
            }
            return closest;
        }

        if (this.getTarget() == null){
            return dodgePoints.size() - 1;
        }

        BlockPos targetPos = this.getTarget().getOnPos();
        int furthestInd = -1;
        double furthestDist = 0;

        for (int i = 0; i < dodgePoints.size(); i++){

            if (i == currentIndex){
                continue;
            }

            double dist = targetPos.distSqr(dodgePoints.get(i));
            if (dist > furthestDist) {
                furthestDist = dist;
                furthestInd = i;
            }
        }

        return furthestInd;
    }

    /**
     * Spawns an arrow aimed at the given BlockPos and adds it to the level.
     * Uses ProjectileUtil.getMobArrow so the arrow is created the same way a skeleton would fire it.
     * Sets high velocity and no gravity so it travels directly toward the dodge point.
     */
    private AbstractArrow fireDodgeArrow(ServerLevel serverLevel, BlockPos target) {
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, new ItemStack(Items.ARROW), 1.0F, null);
        arrow.setPos(this.getX(), this.getEyeY(), this.getZ());

        // Aim toward the centre of the target block
        double dx = target.getX() + 0.5 - this.getX();
        double dy = target.getY() + 0.5 - this.getEyeY();
        double dz = target.getZ() + 0.5 - this.getZ();
        Vec3 direction = new Vec3(dx, dy, dz).normalize();
        arrow.setDeltaMovement(direction.scale(3.0));
        arrow.setNoGravity(true);

        serverLevel.addFreshEntity(arrow);
        return arrow;
    }

    /**
     * Runs the ranged attack timer each server tick.
     * Fires unconditionally once the countdown reaches zero — no line of sight required.
     */
    private void tickRangedAttack() {
        if (dodgeShootLockout > 0) {
            dodgeShootLockout--;
            return;
        }

        LivingEntity target = this.getTarget();
        if (target == null) {
            // Reset state when Greg has no target
            attackTime = -1;
            return;
        }

        if (--attackTime == 0) {
            float distanceFactor = Mth.clamp(
                    (float) Math.sqrt(this.distanceToSqr(target)) / ATTACK_RADIUS,
                    0.1F, 1.0F
            );
            this.performRangedAttack(target, distanceFactor);
            attackTime = ATTACK_INTERVAL;
        } else if (attackTime < 0) {
            attackTime = ATTACK_INTERVAL;
        }
    }

    /** Delegates to fireWitherSkullAt so the interface contract is satisfied. */
    @Override
    public void performRangedAttack(LivingEntity target, float distanceFactor) {
        if (this.distanceTo(target) <= 20.0F) {
            fireSonicBoomAt(target);
            LOGGER.info("[Greg] Target too close to shoot (distance={}), skipping", this.distanceTo(target));

        } else{
            fireWitherSkullAt(target);
        }

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

        this.playSound(SoundEvents.WARDEN_SONIC_BOOM, 1.0F, 1.0F);

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
     * Returns all Netherite Monstrosity projectiles within 20 blocks of Greg.
     * Currently tracks: Flare_Bomb_Entity (flareshoot) and Lava_Bomb_Entity (magmashoot).
     */
    public List<Entity> getThreatList() {
        List<Entity> threats = new ArrayList<>();
        var aabb = this.getBoundingBox().inflate(5);
        threats.addAll(this.level().getEntitiesOfClass(Flare_Bomb_Entity.class, aabb));
        threats.addAll(this.level().getEntitiesOfClass(Lava_Bomb_Entity.class, aabb));

        for (Entity threat : threats) {
            LOGGER.info("[Greg] Threat: type={} pos={}", threat.getType().toShortString(), threat.blockPosition());
        }

        return threats;
    }

    /**
     * Prevents the dodge leash from dropping due to distance.
     * The leash only drops when a new dodge fires or the arrow is removed from the world.
     */
    @Override
    public void leashTooFarBehaviour() {
        // Do nothing — suppress the default dropLeash call
    }

    /**
     * Tightens the leash pull threshold from the default 6 blocks down to 2,
     * so Greg stays close to the dodge arrow at all times.
     * Returning true tells tickLeash to skip its default 6/10 block distance checks.
     */
    @Override
    public boolean handleLeashAtDistance(Entity leashHolder, float distance) {
        if (distance > 7.0F) {
            this.elasticRangeLeashBehaviour(leashHolder, distance);
        }
        return true;
    }

    /** Suppresses all fall damage so Greg survives landing after dodge arrows. */
    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        return false;
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