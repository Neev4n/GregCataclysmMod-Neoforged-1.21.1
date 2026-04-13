package net.neevan.gregcatmod.entity.custom;


import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.Ignis_Entity;
import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.The_Leviathan.*;
import com.github.L_Ender.cataclysm.entity.effect.Flame_Strike_Entity;
import com.github.L_Ender.cataclysm.entity.projectile.*;
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
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.neevan.gregcatmod.data.GregSavedData;
import net.minecraft.util.Mth;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.gameevent.GameEvent;
import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.LLibrary_Boss_Monster;
import com.github.L_Ender.cataclysm.entity.InternalAnimationMonster.IABossMonsters.IABoss_monster;
import com.github.L_Ender.cataclysm.init.ModEntities;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
    private static final int THREAT_DODGE_COOLDOWN = 20;

    /**
     * Combat states that alter Greg's ranged attack behaviour.
     * Add new entries here to introduce further timed overrides.
     */
    private enum CombatState {
        /** Default: fires lava bombs at long range, sonic boom up close. */
        NORMAL,
        /** Fires wind charges for {@code combatStateTimer} ticks after a ROCKETPUNCH alert. */
        WIND_CHARGE
    }

    /** Counts down to the next shot; -1 means uninitialised. */
    private int attackTime = -1;
    /** Counts down between threat-triggered dodges; 0 means ready to dodge again. */
    private int threatDodgeCooldown = 0;
    /** Suppresses wither skull firing for 20 ticks after a dodge to avoid self-damage. */
    private int dodgeShootLockout = 0;
    /** Cooldown between boss-retarget dodge arrows; prevents firing every tick. */
    private int retargetDodgeCooldown = 0;

    /** Current combat state; determines which attack is fired each cycle. */
    private CombatState combatState = CombatState.NORMAL;
    /** Remaining ticks in the current non-NORMAL state; 0 means revert to NORMAL next tick. */
    private int combatStateTimer = 0;

    /** Maps each active wall block position to its remaining ticks before removal. */
    private final Map<BlockPos, Integer> activeWallBlocks = new HashMap<>();

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
                .add(Attributes.FOLLOW_RANGE, 100.0)
                .add(Attributes.ATTACK_DAMAGE, 1.0);
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
        //super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        //this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.0, false));
        //this.goalSelector.addGoal(3, new AvoidEntityGoal<>(this, IABoss_monster.class, 20.0F, 1.0, 1.0));
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, LLibrary_Boss_Monster.class, false));
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, IABoss_monster.class, false));
        LOGGER.info("[Greg] Registered goals");
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide()) {
            tickCombatState();
            tickWall();
            tickRangedAttack();
            tickBossAlert();
            tickThreatDetection();
            tickPotionDrinking();
            tickBossRetargetCheck();
        }
    }

    /**
     * Checks each tick whether the boss saved in GregSavedData has lost its target.
     * If the boss entity is loaded but has no target, Greg fires a dodge arrow toward
     * his own current target to reposition himself. A cooldown prevents repeated firing.
     */
    private void tickBossRetargetCheck() {
        if (retargetDodgeCooldown > 0) {
            retargetDodgeCooldown--;
            return;
        }

        LivingEntity gregTarget = this.getTarget();
        if (gregTarget == null) return;

        ServerLevel serverLevel = (ServerLevel) this.level();
        GregSavedData data = GregSavedData.get(serverLevel.getServer());
        java.util.UUID bossUUID = data.getBossUUID();
        if (bossUUID == null) return;

        // Find the saved boss across all loaded dimensions
        net.minecraft.world.entity.Mob boss = null;
        for (var level : serverLevel.getServer().getAllLevels()) {
            net.minecraft.world.entity.Entity e = level.getEntity(bossUUID);
            if (e instanceof net.minecraft.world.entity.Mob m) {
                boss = m;
                break;
            }
        }

        if (boss == null || boss.getTarget() != null) return;

        // Boss has no target — fire a dodge arrow toward Greg's current target to reposition
        LOGGER.info("[Greg] Boss has no target, firing retarget dodge arrow toward {}", gregTarget.getType().toShortString());
        AbstractArrow arrow = fireDodgeArrow(serverLevel, gregTarget.blockPosition());
        this.dropLeash(true, false);
        this.setLeashedTo(arrow, true);
        retargetDodgeCooldown = 40;
    }

    /**
     * Counts down the combat state timer each tick and reverts to NORMAL when it expires.
     * Runs before tickRangedAttack so the correct state is always used for the current tick.
     */
    private void tickCombatState() {
        if (combatState == CombatState.NORMAL) return;
        if (--combatStateTimer <= 0) {
            LOGGER.info("[Greg] Combat state {} expired, reverting to NORMAL", combatState);
            combatState = CombatState.NORMAL;
            combatStateTimer = 0;
        }
    }

    /**
     * Transitions Greg into the given combat state for {@code durationTicks} ticks.
     * If the same state is already active, the timer is refreshed rather than stacked.
     */
    private void setCombatState(CombatState state, int durationTicks) {
        LOGGER.info("[Greg] Combat state {} -> {} for {} ticks", combatState, state, durationTicks);
        combatState = state;
        combatStateTimer = durationTicks;
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
                    LOGGER.info("[Greg] Finished drinking potion: {}", held.getHoverName().getString());
                }
                this.gameEvent(GameEvent.DRINK);
            }
        } else {
            // Decide which potion to drink, in priority order
            ItemStack potion = null;
            String potionName = null;
//            if (!this.hasEffect(MobEffects.FIRE_RESISTANCE)) {
//                potion = PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE);
//                potionName = "Fire Resistance";
            if (!this.hasEffect(MobEffects.REGENERATION)) {
                potion = PotionContents.createItemStack(Items.POTION, Potions.STRONG_REGENERATION);
                potionName = "Regeneration";
            } else if (!this.hasEffect(MobEffects.WATER_BREATHING)) {
                potion = PotionContents.createItemStack(Items.POTION, Potions.LONG_WATER_BREATHING);
                potionName = "Water Breathing";
            } else if (!this.hasEffect(MobEffects.DAMAGE_RESISTANCE)) {
                potion = PotionContents.createItemStack(Items.POTION, Potions.STRONG_TURTLE_MASTER);
                potionName = "Turtle Master";
            }

            if (potion != null) {
                this.setItemSlot(EquipmentSlot.MAINHAND, potion);
                this.usingTime = this.getMainHandItem().getUseDuration(this);
                this.setUsingItem(true);
                LOGGER.info("[Greg] Starting to drink potion: {}", potionName);
                this.level().playSound(
                        null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.WITCH_DRINK, this.getSoundSource(),
                        2.0F, 0.8F + this.random.nextFloat() * 0.4F
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

        if (alert.equals("IGNIS_ULTIMATE_ATTACK")){
            performDodge(serverLevel, data);
            buildWallBox(this.getTarget(), 5, 100);

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

        ServerLevel serverLevel = (ServerLevel) this.level();
        GregSavedData data = GregSavedData.get(serverLevel.getServer());

        List<Entity> threats = getThreatList();
        if (threats.isEmpty() && this.getTarget() != null) {
//            dodgeShootLockout = 20;
//            AbstractArrow arrow = fireDodgeArrow(serverLevel, this.getTarget().getOnPos().above(8));
//            this.setLeashedTo(arrow, true);
            return;
        };

        for (Entity threat : threats){
            if (threat instanceof Ignis_Entity || threat instanceof Flame_Strike_Entity){
                performDodge(serverLevel, data);
            } else if (threat instanceof Ignis_Abyss_Fireball_Entity || threat instanceof Ignis_Fireball_Entity){
                buildWallBox(threat, 2, 40);
            }
        }

        LOGGER.info("[Greg] {} threat(s) detected nearby, triggering dodge", threats.size());
        threatDodgeCooldown = THREAT_DODGE_COOLDOWN;
    }

    /**
     * Core dodge logic: picks the next dodge point, fires an arrow toward it,
     * and leashes Greg to that arrow. Called by both tickBossAlert and tickThreatDetection.
     */
    private void performDodge(ServerLevel serverLevel, GregSavedData data) {

        if (threatDodgeCooldown > 0) {
            threatDodgeCooldown--;
            return;
        }

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
     * Picks the dodge point furthest from Greg's target that Greg has an unobstructed
     * line of sight to, excluding the current index.
     * Falls back to the overall furthest if no visible point exists.
     */
    private int pickDodgeTarget(List<BlockPos> dodgePoints, int currentIndex) {
        if (this.getTarget() == null) {
            return 0;
        }

        BlockPos bossPos = this.getTarget().blockPosition();
        int bestLosIndex = -1;
        double bestLosDist = -1;
        int fallbackIndex = 0;
        double fallbackDist = -1;

        for (int i = 0; i < dodgePoints.size(); i++) {
            if (i == currentIndex) continue;

            double dist = bossPos.distSqr(dodgePoints.get(i));

            if (dist > fallbackDist) {
                fallbackDist = dist;
                fallbackIndex = i;
            }

            if (!hasLineOfSightToPos(dodgePoints.get(i))) continue;

            if (dist > bestLosDist) {
                bestLosDist = dist;
                bestLosIndex = i;
            }
        }

        if (bestLosIndex != -1) {
            return bestLosIndex;
        }

        LOGGER.info("[Greg] No LOS dodge point found, falling back to furthest");
        return fallbackIndex;
    }

    /**
     * Returns true if Greg has an unobstructed block-collision line of sight to the centre of target.
     * Uses the same COLLIDER shape check that vanilla mob AI uses for visibility.
     */
    private boolean hasLineOfSightToPos(BlockPos target) {
        Vec3 eyePos = this.getEyePosition();
        Vec3 targetCenter = Vec3.atCenterOf(target);
        ClipContext ctx = new ClipContext(eyePos, targetCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this);
        BlockHitResult hit = this.level().clip(ctx);
        return hit.getType() == HitResult.Type.MISS;
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

        if (!this.hasLineOfSight(target)){
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

    /** Dispatches the ranged attack based on the current combat state. */
    @Override
    public void performRangedAttack(LivingEntity target, float distanceFactor) {
        if (!this.hasLineOfSight(target)){
            return;
        }

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
     * Fires a Wither_Homing_Missile_Entity from Greg's eye position toward the given entity.
     * Mirrors the Harbinger's mlaunch: constructs with (shooter, direction, level, damage, target),
     * spawns 1 block ahead in the direction of the target.
     */
    private void fireWitherHomingMissileAt(LivingEntity target) {
        double eyeX = this.getX();
        double eyeY = this.getEyeY();
        double eyeZ = this.getZ();

        // Aim at the centre of the target's bounding box
        double aimY = target.getBoundingBox().getCenter().y;
        Vec3 direction = new Vec3(target.getX() - eyeX, aimY - eyeY, target.getZ() - eyeZ).normalize();

        Wither_Homing_Missile_Entity missile = new Wither_Homing_Missile_Entity(this, direction, this.level(), 10.0F, target);
        // Spawn 2 blocks ahead of Greg in the direction of the target
        missile.setPosRaw(eyeX + direction.x * 3, eyeY + direction.y * 3, eyeZ + direction.z * 3);
        this.level().addFreshEntity(missile);

        LOGGER.info("[Greg] Fired Wither Homing Missile at entity={} pos={}", target.getType().toShortString(), this.blockPosition());
    }

    /**
     * Fires an Abyss_Orb_Entity from 2 blocks ahead of Greg toward the given entity.
     * The orb homes in on the target; damage mirrors the default config value of 4.
     */
    private void fireAbyssOrbAt(LivingEntity target) {
        double eyeX = this.getX();
        double eyeY = this.getEyeY();
        double eyeZ = this.getZ();

        // Aim at the centre of the target's bounding box
        double aimY = target.getBoundingBox().getCenter().y;
        Vec3 direction = new Vec3(target.getX() - eyeX, aimY - eyeY, target.getZ() - eyeZ).normalize();

        // Spawn 2 blocks ahead of Greg in the direction of the target
        double spawnX = eyeX + direction.x * 2;
        double spawnY = eyeY + direction.y * 2;
        double spawnZ = eyeZ + direction.z * 2;

        Abyss_Orb_Entity orb = new Abyss_Orb_Entity(this, spawnX, spawnY, spawnZ, this.level(), 4.0F, target);
        this.level().addFreshEntity(orb);

        LOGGER.info("[Greg] Fired Abyss Orb at entity={} pos={}", target.getType().toShortString(), this.blockPosition());
    }

    /**
     * Fires a Flare_Bomb_Entity from Greg's eye position toward the given entity.
     * Aims at the lower third of the target's bounding box with a slight upward arc
     * (+ 0.2 * horizontal distance) matching the Netherite Monstrosity's own shoot logic.
     * Skips if the target is within 3 blocks.
     */
    private void fireFlareBombAt(Entity target) {
        if (this.distanceTo(target) <= 3.0F) {
            LOGGER.info("[Greg] Target too close to shoot flare bomb (distance={}), skipping", this.distanceTo(target));
            return;
        }

        double eyeX = this.getX();
        double eyeY = this.getEyeY();
        double eyeZ = this.getZ();

        // Aim at the lower third of the target's bounding box
        double aimY = target.getBoundingBox().minY + target.getBbHeight() / 3.0;
        double dx = target.getX() - eyeX;
        double dy = aimY - eyeY;
        double dz = target.getZ() - eyeZ;
        // Arc factor mirrors the Monstrosity: adds +0.2 blocks of lift per block of horizontal distance
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        Flare_Bomb_Entity flare = new Flare_Bomb_Entity(
                ModEntities.FLARE_BOMB.get(),
                this.level(),
                this
        );
        flare.setPosRaw(eyeX, eyeY, eyeZ);
        flare.shoot(dx, dy + 0.2 * horizontalDist, dz, 1.0F, 1.0F);
        this.level().addFreshEntity(flare);

        LOGGER.info("[Greg] Fired FlareBomb at entity={} pos={}", target.getType().toShortString(), this.blockPosition());
    }

    /**
     * Fires a Lava_Bomb_Entity from Greg's eye position toward the given entity.
     * On hit, the bomb creates a power-2 explosion (no block destruction).
     * Uses the same arc and aim logic as fireFlareBombAt.
     * Skips if the target is within 3 blocks.
     */
    private void fireLavaBombAt(Entity target) {
        if (this.distanceTo(target) <= 3.0F) {
            LOGGER.info("[Greg] Target too close to shoot lava bomb (distance={}), skipping", this.distanceTo(target));
            return;
        }

        double eyeX = this.getX();
        double eyeY = this.getEyeY();
        double eyeZ = this.getZ();

        double aimY = target.getBoundingBox().minY + target.getBbHeight() / 3.0;
        double dx = target.getX() - eyeX;
        double dy = aimY - eyeY;
        double dz = target.getZ() - eyeZ;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        Lava_Bomb_Entity lava = new Lava_Bomb_Entity(
                ModEntities.LAVA_BOMB.get(),
                this.level(),
                this
        );
        lava.setPosRaw(eyeX, eyeY, eyeZ);
        lava.shoot(dx, dy + 0.2 * horizontalDist, dz, 1.0F, 1.0F);
        this.level().addFreshEntity(lava);

        LOGGER.info("[Greg] Fired LavaBomb at entity={} pos={}", target.getType().toShortString(), this.blockPosition());
    }

    /**
     * Builds a hollow 5×5 obsidian box centred on the target's block position,
     * 5 blocks tall, with no floor (boss stands on existing ground) and no ceiling.
     * Only air blocks are replaced; existing blocks are never overwritten.
     * Placed positions are recorded in {@code activeWallBlocks} and torn down after 80 ticks
     * by the shared {@code tickWall} mechanism.
     */
    public void buildWallBox(Entity target, int radius, int timer) {
        ServerLevel serverLevel = (ServerLevel) this.level();

        int centerX = target.getBlockX();
        int baseY   = Mth.floor(target.getY());
        int centerZ = target.getBlockZ();

        BlockState wallBlock = Blocks.BEDROCK.defaultBlockState();
        int placed = 0;

        // place on the XZ perimeter (sides) and the top/bottom caps
        for (int wx = -radius; wx <= radius; wx++) {
            for (int wz = -radius; wz <= radius; wz++) {
                for (int h = -radius; h <= radius; h++) {
                    boolean onXZPerimeter = wx == -radius || wx == radius || wz == -radius || wz == radius;
                    boolean onYCap = h == -radius || h == radius;
                    if (!onXZPerimeter && !onYCap) continue; // skip interior
                    BlockPos pos = new BlockPos(centerX + wx, baseY + h, centerZ + wz);
                    if (serverLevel.getBlockState(pos).isAir() || serverLevel.getBlockState(pos).is(Blocks.WATER)) {
                        serverLevel.setBlock(pos, wallBlock, 3);
                        activeWallBlocks.put(pos, timer);
                        placed++;
                    }
                }
            }
        }

        LOGGER.info("[Greg] Built box around {}: {} blocks placed, each expires in {} ticks",
                target.blockPosition(), placed, timer);
    }

    /**
     * Builds a 10-wide × 5-tall obsidian wall perpendicular to the Greg-to-target direction,
     * placed with its base at Greg's feet and its centre 3 blocks in front of Greg.
     * Only air blocks are replaced; existing blocks are never overwritten.
     * All placed positions are recorded in {@code activeWallBlocks} and torn down after 40 ticks.
     * Calling this while a wall is already active resets the timer and extends the existing wall.
     */
    public void buildWall(Entity target) {
        ServerLevel serverLevel = (ServerLevel) this.level();

        // Horizontal direction from Greg toward the target (Y ignored so the wall stays vertical)
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        Vec3 dir = new Vec3(dx, 0, dz).normalize();

        // Wall centre: 3 blocks ahead of Greg in the direction of the target
        double centerX = this.getX() + dir.x * 10;
        double centerZ = this.getZ() + dir.z * 10;
        int baseY = Mth.floor(this.getY());

        // Perpendicular direction in the horizontal plane (rotate dir 90° around Y)
        Vec3 perp = new Vec3(-dir.z, 0, dir.x);

        BlockState wallBlock = Blocks.OBSIDIAN.defaultBlockState();
        int placed = 0;

        // 10 columns centred on the midpoint (-5 to +4), 5 rows from baseY upward
        for (int w = -5; w < 5; w++) {
            for (int h = -5; h < 5; h++) {
                BlockPos pos = BlockPos.containing(
                        centerX + perp.x * w,
                        baseY + h,
                        centerZ + perp.z * w
                );
                // Only place in air — never overwrite existing blocks
                if (serverLevel.getBlockState(pos).isAir()) {
                    serverLevel.setBlock(pos, wallBlock, 3);
                    activeWallBlocks.put(pos, 80);
                    placed++;
                }
            }
        }

        LOGGER.info("[Greg] Built wall: {} blocks placed, each expires in 80 ticks", placed);
    }

    /**
     * Decrements each active wall block's individual timer every tick.
     * Blocks whose timer reaches zero are removed from the world and the tracking map.
     */
    private void tickWall() {
        if (activeWallBlocks.isEmpty()) return;

        ServerLevel serverLevel = (ServerLevel) this.level();
        int broken = 0;
        Iterator<Map.Entry<BlockPos, Integer>> it = activeWallBlocks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                BlockPos pos = entry.getKey();
                if (serverLevel.getBlockState(pos).is(Blocks.OBSIDIAN) || serverLevel.getBlockState(pos).is(Blocks.BEDROCK)) {
                    serverLevel.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    broken++;
                }
                it.remove();
            } else {
                entry.setValue(remaining);
            }
        }
        if (broken > 0) {
            LOGGER.info("[Greg] tickWall: removed {} expired blocks", broken);
        }
    }

    /**
     * Fires a WindCharge from Greg's eye position toward the given entity.
     * Uses the player WindCharge (not BreezeWindCharge, which is Breeze-specific).
     * Spawned at eye level and aimed at the centre of the target's bounding box.
     * The Breeze shoots at velocity 0.7F; we match that here.
     * Skips if the target is within 3 blocks.
     */
    private void fireWindChargeAt(Entity target) {
        if (this.distanceTo(target) <= 3.0F) {
            LOGGER.info("[Greg] Target too close to shoot wind charge (distance={}), skipping", this.distanceTo(target));
            return;
        }

        double eyeX = this.getX();
        double eyeY = this.getEyeY();
        double eyeZ = this.getZ();

        Vec3 direction = new Vec3(
                target.getX() - eyeX,
                target.getBoundingBox().getCenter().y - eyeY,
                target.getZ() - eyeZ
        ).normalize();

        // WindCharge(Level, x, y, z, direction) positions the charge and sets its trajectory
        WindCharge charge = new WindCharge(this.level(), eyeX, eyeY, eyeZ, direction);
        this.level().addFreshEntity(charge);

        LOGGER.info("[Greg] Fired WindCharge at entity={} pos={}", target.getType().toShortString(), this.blockPosition());
    }

    /**
     * Returns all tracked boss projectiles within 5 blocks of Greg.
     * Netherite Monstrosity: Flare_Bomb_Entity, Lava_Bomb_Entity.
     * Harbinger: Laser_Beam_Entity, Wither_Missile_Entity, Wither_Homing_Missile_Entity.
     */
    public List<Entity> getThreatList() {
        List<Entity> threats = new ArrayList<>();
        var aabb = this.getBoundingBox().inflate(15);

        // Netherite Monstrosity projectiles
//        threats.addAll(this.level().getEntitiesOfClass(Flare_Bomb_Entity.class, aabb));
//        threats.addAll(this.level().getEntitiesOfClass(Lava_Bomb_Entity.class, aabb));

        // Harbinger projectiles
//        threats.addAll(this.level().getEntitiesOfClass(Laser_Beam_Entity.class, aabb));
//        threats.addAll(this.level().getEntitiesOfClass(Wither_Missile_Entity.class, aabb));
//        threats.addAll(this.level().getEntitiesOfClass(Wither_Homing_Missile_Entity.class, aabb));
        // Leviathan projectiles
//        threats.addAll(this.level().getEntitiesOfClass(Abyss_Blast_Portal_Entity.class, aabb));
//        threats.addAll(this.level().getEntitiesOfClass(Abyss_Mine_Entity.class, aabb));
//        threats.addAll(this.level().getEntitiesOfClass(Abyss_Orb_Entity.class, aabb));
//        threats.addAll(this.level().getEntitiesOfClass(The_Leviathan_Entity.class, aabb));
//        threats.addAll(this.level().getEntitiesOfClass(Dimensional_Rift_Entity.class, aabb));

        // Ignis projectiles
        threats.addAll(this.level().getEntitiesOfClass(Ignis_Entity.class, aabb));
        threats.addAll(this.level().getEntitiesOfClass(Ignis_Fireball_Entity.class, aabb));
        threats.addAll(this.level().getEntitiesOfClass(Ignis_Abyss_Fireball_Entity.class, aabb));
        threats.addAll(this.level().getEntitiesOfClass(Flame_Strike_Entity.class, aabb));

//        threats.removeIf(threat -> {
//            Vec3 targetCenter = threat.getBoundingBox().getCenter();
//            ClipContext ctx = new ClipContext(this.getEyePosition(), targetCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this);
//            boolean blocked = this.level().clip(ctx).getType() != HitResult.Type.MISS;
//            if (blocked) {
//                LOGGER.info("[Greg] Threat blocked by geometry, ignoring: type={} pos={}", threat.getType().toShortString(), threat.blockPosition());
//            }
//            return blocked;
//        });

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
        if (distance > 8.0F) {
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