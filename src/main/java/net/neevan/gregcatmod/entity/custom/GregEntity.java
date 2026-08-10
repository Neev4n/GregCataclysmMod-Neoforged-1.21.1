package net.neevan.gregcatmod.entity.custom;


import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.Ignis_Entity;
import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.The_Leviathan.*;
import com.github.L_Ender.cataclysm.entity.InternalAnimationMonster.IABossMonsters.Maledictus.Maledictus_Entity;
import com.github.L_Ender.cataclysm.entity.InternalAnimationMonster.IABossMonsters.Scylla.Scylla_Ceraunus_Entity;
import com.github.L_Ender.cataclysm.entity.effect.Cm_Falling_Block_Entity;
import com.github.L_Ender.cataclysm.entity.effect.Lightning_Area_Effect_Entity;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.neevan.gregcatmod.util.GregSavedData;
import net.neevan.gregcatmod.util.AttackHandler;
import net.neevan.gregcatmod.util.DodgeHandler;
import net.neevan.gregcatmod.util.DodgeSearch;
import net.neevan.gregcatmod.util.DodgeVisualizer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.gameevent.GameEvent;
import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.LLibrary_Boss_Monster;
import com.github.L_Ender.cataclysm.entity.InternalAnimationMonster.IABossMonsters.IABoss_monster;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

    /** Synced to client so the held bottle item renders during drinking. */
    private static final EntityDataAccessor<Boolean> DATA_USING_ITEM =
            SynchedEntityData.defineId(GregEntity.class, EntityDataSerializers.BOOLEAN);

    /** Ticks remaining in the current potion drink; 0 when idle. */
    private int usingTime = 0;

    private static final float ATTACK_RADIUS = 20.0F;
    private static final int ATTACK_INTERVAL = 1; // ticks between shots
    /** Ticks between dodges of any kind; prevents spamming one dodge per tick. Set by DodgeHandler.dodgeArrow. */
    public static final int THREAT_DODGE_COOLDOWN = 20;

    /** Counts down to the next shot; -1 means uninitialised. */
    private int attackTime = -1;
    /** Counts down between threat-triggered dodges; 0 means ready to dodge again. */
    private int threatDodgeCooldown = 0;
    /** Suppresses ranged attacks for 20 ticks after a dodge. Never assigned nonzero — see CLAUDE.md. */
    private int dodgeShootLockout = 0;
    /** Cooldown between boss-retarget dodge arrows; prevents firing every tick. */
    private int retargetDodgeCooldown = 0;
    /** Minimum ticks between hide arrows; stops tickHide re-firing while one is still in flight. */
    private int hideRepositionCooldown = 0;
    /** Consecutive ticks the boss has had line of sight to Greg while hiding; drives LOS_GRACE_TICKS. */
    private int hideLosTicks = 0;
    /** Consecutive ticks Greg has had NO line of sight to his target while settled; drives tickLosRecovery. */
    private int losLostTicks = 0;
    /** Consecutive ticks Greg has had line of sight but been out of damage range; drives tickRegainDistance. */
    private int rangeLostTicks = 0;
    /** True while tickRangedAttack is blocked on line of sight; edge-triggers its one log line. */
    private boolean holdingFire = false;
    /** True while tickRangedAttack is blocked by the boss's damage-range cap; edge-triggers its log. */
    private boolean outOfRange = false;
    /** Counts down between hazard-escape dodges; 0 means Greg may try to leave a damaging volume again. */
    private int hazardEscapeCooldown = 0;

    /**
     * Consecutive ticks of boss line of sight before Greg fires another hide arrow. Hysteresis:
     * line of sight flickers as the boss moves, and reacting to a single tick of it would thrash.
     */
    private static final int LOS_GRACE_TICKS = 5;
    /** Minimum ticks between hide arrows. Greg cannot walk, so this is his entire correction loop. */
    private static final int HIDE_REPOSITION_COOLDOWN = 20;

    /**
     * Ticks without line of sight to the target before Greg dodges to try to regain it.
     *
     * <p><b>Keep this >= THREAT_DODGE_COOLDOWN.</b> tickLosRecovery only counts while the dodge
     * cooldown is clear (so a dodge in flight isn't mistaken for a stall), so if the cooldown ever
     * outlasts this threshold the counter can never reach it and the feature silently stops working.
     * They are deliberately the same value today, which hides that coupling — hence this note.
     */
    private static final int LOS_RECOVERY_TICKS = 40;

    /**
     * Ticks out of damage range (with line of sight) before Greg dodges closer to the target.
     *
     * <p><b>Keep this >= THREAT_DODGE_COOLDOWN</b> — the exact coupling documented on
     * LOS_RECOVERY_TICKS applies here too: the counter only runs while the dodge cooldown is clear,
     * so a cooldown that outlasts this threshold makes the feature silently dead.
     */
    private static final int RANGE_RECOVERY_TICKS = 40;

    /**
     * Ticks between hazard-escape dodges. Deliberately far shorter than THREAT_DODGE_COOLDOWN.
     *
     * <p>Escaping is the one dodge where Greg is <b>already</b> taking damage: Cataclysm's area
     * effects apply every 5 ticks, i-frames stretch that to a landed hit every ~10-20, and 4 HP buys
     * him about three of them. Gating an escape behind the 20-tick alert-paced cooldown would cost him
     * one or two of those three. This is a separate timer rather than a bypass because the shared
     * cooldown exists for a real reason — ungated dodges left Greg permanently in transit — and the
     * fix is a gate sized to the damage cadence, not the absence of one.
     */
    public static final int HAZARD_ESCAPE_COOLDOWN = 5;

    /**
     * The single dodge/hide target Greg was last sent to — null when unset. This is the <b>stand spot</b>
     * (where Greg ends up), not the block an arrow embeds in, so hysteresis and the "am I there yet"
     * check compare against where he actually lands. Held on the entity, not persisted.
     *
     * <p>Used by both the dynamic {@link DodgeSearch} pickers and the hand-placed list fallback: the
     * list pickers exclude the current target by matching this BlockPos against their points, via
     * {@code DodgeHandler.effectiveExcludeIndex}. One exclusion signal, no separate index.
     */
    private BlockPos currentDodgeTarget = null;

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

    /**
     * Runs Greg's server-side behaviour each tick.
     *
     * <p>The dodge triggers are ordered by urgency, because they share threatDodgeCooldown and tick
     * order is what breaks a tie:
     * <ol>
     *   <li>tickHazardEscape — Greg is already losing health. Beats everything
     *   <li>tickThreatHide — a projectile that saw Greg at spawn is already in flight; beats
     *       "an attack animation started". Sits in the slot tickHide occupied
     *   <li>tickBossAlert / tickThreatDetection — an attack is incoming
     *   <li>tickLosRecovery / tickRegainDistance — last, because "I'm not currently shooting" is the
     *       least urgent of these. Their conditions are mutually exclusive (one needs line of sight,
     *       the other its absence), so their relative order is documentation: sight before range
     * </ol>
     *
     * <p>tickHide (the sustained hide loop) is suppressed for now — see
     * plans/threat_revamp_plan.md. Known cost: the Harbinger's DEATHLASER goes unanswered;
     * re-enable it (and the isHideAlertActive gate in tickThreatHide) before a Harbinger rematch.
     */
    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide()) {
            //tickCooldowns();
            //tickGrabEscape();
            //tickRangedAttack();
            //tickHazardEscape();
            //tickHide();
            //tickThreatHide();
            //tickBossAlert();
            //tickThreatDetection();
            //tickLosRecovery();
            //tickRegainDistance();
            //tickPotionDrinking();
        }
    }

    /**
     * Counts the threat-dodge cooldown down once per tick — the single place it decrements.
     * Consumers (tickThreatDetection, DodgeHandler.dodgeArrow) only ever read it and set it.
     * Previously each of them decremented it themselves, so on a tick carrying an alert the counter
     * burned twice and a "20 tick" gate really lasted 10 — and only during alert storms, which made
     * the effective rate limit depend on how busy the boss was.
     */
    private void tickCooldowns() {
        if (threatDodgeCooldown > 0) {
            threatDodgeCooldown--;
        }
        // Same rule as above: this is the only place it counts down. DodgeHandler.dodgeEscape is the
        // only writer.
        if (hazardEscapeCooldown > 0) {
            hazardEscapeCooldown--;
        }
    }

    /**
     * Breaks Scylla's anchor-hook grab. Her {@code Scylla_Ceraunus_Entity} (the thrown anchor) forces
     * whatever it hits to {@code startRiding} it, then reels the passenger back to Scylla for the
     * {@code grab_smash} attack (state 27) — 20 damage, a one-shot on Greg's 4 HP. Once he is a
     * passenger his motion is fully overridden, so no dodge can escape: the leash physics every other
     * escape relies on does not run on a riding entity. The only counter is to dismount immediately.
     *
     * <p>Runs first of all the tickers (right after cooldowns) because being grabbed outranks every
     * other threat — Greg is already committed to a lethal sequence the instant the anchor connects.
     */
    private void tickGrabEscape() {
        // Only the anchor grab locks Greg as a passenger; ignore any other vehicle.
        if (this.getVehicle() instanceof Scylla_Ceraunus_Entity anchor) {
            LOGGER.info("[Greg] Grabbed by Scylla anchor (id={}) — dismounting", anchor.getId());
            this.stopRiding();
        }
    }

    /**
     * Keeps Greg out of the boss's line of sight while a hide alert is active (currently only the
     * Harbinger's 124-tick DEATHLASER, which terrain blocks entirely). Fires a dodge arrow toward a
     * point Greg can see but the boss cannot; if line of sight is re-established while the alert is
     * still up, he repositions again.
     *
     * <p>Greg has no movement goals — every metre is leash physics — so this reposition loop is his
     * only correction mechanism. An arrow that leaves him short of cover is fixed by the next arrow,
     * not by walking, which is why LOS_GRACE_TICKS and HIDE_REPOSITION_COOLDOWN carry the whole
     * feedback loop: too slow and he stays exposed, too fast and he oscillates in transit.
     */
    private void tickHide() {
        ServerLevel serverLevel = (ServerLevel) this.level();
        GregSavedData data = GregSavedData.get(serverLevel.getServer());

        if (!data.isHideAlertActive()) {
            // Reset the loop so the next laser starts clean rather than mid-hysteresis
            hideRepositionCooldown = 0;
            hideLosTicks = 0;
            return;
        }

        // The boss is the saved bossUUID, not getTarget() — pickHidePoint must test the same entity
        // this method checks line of sight against, or the two disagree and the loop never converges
        LivingEntity boss = resolveSavedBoss(serverLevel, data);
        if (boss == null || !boss.isAlive() || boss.level() != this.level()) {
            // A dead or unloaded boss stops ticking HarbingerMixin, which would otherwise clear the
            // flag — so Greg clears it himself rather than hiding forever from nothing
            LOGGER.info("[Greg] Hide alert active but boss is gone, clearing flag");
            data.setHideAlertActive(false);
            return;
        }

        if (hideRepositionCooldown > 0) {
            hideRepositionCooldown--;
            return;
        }

        // Already hidden — nothing to do, and reset the grace counter
        if (!this.hasLineOfSight(boss)) {
            hideLosTicks = 0;
            return;
        }

        // Exposed, but wait out the grace window: line of sight flickers as the boss moves
        if (++hideLosTicks < LOS_GRACE_TICKS) return;

        // Dynamic search first: the nearest reachable spot the boss can't see. Ride the arrow to its
        // impact block, which lands Greg at the block face (not ~6 blocks short like the leash), so he
        // arrives where the search proved he is occluded. Retires block_laser_plan concern 1.
        DodgeSearch.Candidate cand = DodgeSearch.pickDynamicHide(this, boss, currentDodgeTarget);
        if (cand != null) {
            currentDodgeTarget = cand.stand();
            DodgeHandler.launchRide(this, serverLevel, cand.impact());
            hideLosTicks = 0;
            hideRepositionCooldown = HIDE_REPOSITION_COOLDOWN;
            LOGGER.info("[Greg] Hiding at {} (impact {}) from boss={}", cand.stand(), cand.impact(), boss.getType().toShortString());
            serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal("[Greg] Hiding at " + cand.stand()), false);
            return;
        }

        // Fallback: hand-placed dodge points, for arenas the ray-fan can't solve
        List<BlockPos> dodgePoints = data.getDodgePoints();
        if (dodgePoints.isEmpty()) {
            LOGGER.info("[Greg] Hide alert active but ray-fan found nothing and no dodge points set");
            return;
        }

        int targetIndex = DodgeHandler.pickHidePoint(this, boss, dodgePoints, currentDodgeTarget);
        if (targetIndex == -1) {
            // Distinct from "already hidden" and "hide arrow fired" — the boss can see every point
            LOGGER.info("[Greg] No hide point available (ray-fan empty, every dodge point visible to the boss or unreachable)");
            return;
        }

        BlockPos target = dodgePoints.get(targetIndex);
        currentDodgeTarget = target;
        DodgeHandler.launchRide(this, serverLevel, target);
        hideLosTicks = 0;
        hideRepositionCooldown = HIDE_REPOSITION_COOLDOWN;

        LOGGER.info("[Greg] Hiding at fallback point [{}] at {} from boss={}", targetIndex, target, boss.getType().toShortString());
        serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("[Greg] Hiding at point [" + targetIndex + "]"), false);
    }

    /**
     * Consumes the one-shot threat-hide signal raised by ModGameBusEvents.onEntityJoinLevel when a
     * Scylla threat projectile spawned with line of sight to Greg, and hides once via
     * DodgeHandler.hideOnce. See plans/threat_revamp_plan.md.
     *
     * <p><b>Poll before gates</b> — the signal must not survive a gated tick and fire stale: a
     * projectile that spawned during cooldown is 20 ticks closer or already gone by the time the
     * gate opens, and acting on it then is dodging a ghost.
     *
     * <p><b>Deliberately NO isHideAlertActive gate — load-bearing, not an omission.</b> With
     * tickHide suppressed, IABossAlertMixin still rewrites hideAlertActive every Scylla tick, and
     * her hide-states (8, 9, 11) are precisely the states that throw these projectiles — gating on
     * the flag would disable this hide during every volley, exactly when it exists to fire. If
     * tickHide is ever re-enabled, re-add the gate (a sustained hide owns the leash; two hide
     * writers would fight over setLeashedTo).
     */
    private void tickThreatHide() {
        ServerLevel serverLevel = (ServerLevel) this.level();
        GregSavedData data = GregSavedData.get(serverLevel.getServer());

        if (!data.pollPendingThreatHide()) return;

        // Read-only gate; hideOnce re-checks and is the writer. Bailing here just skips the boss
        // resolve below during the volley window.
        if (threatDodgeCooldown > 0) return;

        // Hide from the saved boss, not getTarget() — same rule as tickHide: the projectile flew
        // from the boss, so occlusion from the boss stands in for occlusion from its path
        LivingEntity boss = resolveSavedBoss(serverLevel, data);
        if (boss == null || !boss.isAlive() || boss.level() != this.level()) {
            LOGGER.info("[Greg] Threat hide signal but no live saved boss to hide from, ignoring");
            return;
        }

        DodgeHandler.hideOnce(this, data, boss);
    }

    /**
     * Resolves the boss recorded by /setBossTarget, searching every loaded dimension.
     * Returns null if none is set or it isn't loaded — callers bail rather than falling back to
     * getTarget(), which could be a different entity entirely.
     */
    @javax.annotation.Nullable
    private LivingEntity resolveSavedBoss(ServerLevel serverLevel, GregSavedData data) {
        java.util.UUID bossUUID = data.getBossUUID();
        if (bossUUID == null) return null;

        for (var level : serverLevel.getServer().getAllLevels()) {
            if (level.getEntity(bossUUID) instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
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
        AbstractArrow arrow = DodgeHandler.fireDodgeArrow(this, serverLevel, gregTarget.blockPosition());
        this.dropLeash(true, false);
        this.setLeashedTo(arrow, true);
        retargetDodgeCooldown = 40;
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

            } else if (!this.hasEffect(MobEffects.DAMAGE_RESISTANCE)) {
                potion = PotionContents.createItemStack(Items.POTION, Potions.STRONG_TURTLE_MASTER);
                potionName = "Turtle Master";
            } else if (!this.hasEffect(MobEffects.FIRE_RESISTANCE)) {
                potion = PotionContents.createItemStack(Items.POTION, Potions.LONG_FIRE_RESISTANCE);
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

        // tickHide owns Greg's movement while hiding. dodgeArrow picks a point the boss CAN see, so
        // letting it run here would fight tickHide over the leash — setLeashedTo just overwrites, so
        // whichever fired last would win at random. The alert is still polled and broadcast above.
        if (data.isHideAlertActive()) {
            LOGGER.info("[Greg] Hide alert active, suppressing alert dodge");
            return;
        }

        // Pre-empt Scylla's anchor throw. States 13 (ANCHOR_SHOT) and 14 (ANCHOR_SHOT_PULL) fire this
        // alert BEFORE the Scylla_Ceraunus_Entity spawns; the anchor then flies straight at Greg and its
        // impact deals 16 storm_bringer damage — a one-shot on a 4 HP chicken. A plain dodge keeps line
        // of sight, so the fast anchor just tracks to his new spot and connects anyway (killed Greg
        // repeatedly 2026-07-24). Hiding instead breaks line of sight to the boss now, while the anchor
        // still doesn't exist, so when it launches it embeds in the interposed terrain (onHitBlock) and
        // bounces back to Scylla without ever reaching him. Matches ANCHOR_SHOT and ANCHOR_SHOT_PULL but
        // NOT ANCHOR_EXPLOSION (state 18, a boss-centred nova the dodge's proximity floor handles).
        if (alert.contains("ANCHOR_SHOT")) {
            LivingEntity boss = resolveSavedBoss(serverLevel, data);
            if (boss != null && boss.isAlive() && boss.level() == this.level()) {
                LOGGER.info("[Greg] Anchor-shot alert — pre-empting with a hide instead of a dodge");
                DodgeHandler.hideOnce(this, data, boss);
                return;
            }
            LOGGER.info("[Greg] Anchor-shot alert but no live saved boss to hide from — falling back to dodge");
        }

        // Scylla's lightning_explosion (state 9): a boss-tracked sky barrage. Prefer breaking line of
        // sight so terrain eats it; if the arena has no cover, dodge as far from the boss as possible
        // rather than stand in the strike zone.
        if (alert.contains("LIGHTNING_EXPLOSION")) {
            LivingEntity boss = resolveSavedBoss(serverLevel, data);
            if (boss != null && boss.isAlive() && boss.level() == this.level()) {
                DodgeHandler.hideElseDodgeFar(this, data, boss, "Lightning explosion");
                return;
            }
            LOGGER.info("[Greg] Lightning-explosion alert but no live saved boss to hide from — falling back to dodge");
        }

        DodgeHandler.dodgeArrow(this, data);
    }

    /**
     * Checks for Netherite Monstrosity projectiles (Flare_Bomb_Entity, Lava_Bomb_Entity)
     * within 20 blocks each tick. Triggers a dodge on first detection; a 40-tick cooldown
     * prevents re-triggering for every tick the threat remains in range.
     */
    private void tickThreatDetection() {
        ServerLevel serverLevel = (ServerLevel) this.level();
        GregSavedData data = GregSavedData.get(serverLevel.getServer());

        // Suppressed while hiding, for the same reason as tickBossAlert — this dodge would fight
        // tickHide over the leash. Checked before the threat scan below to skip its per-entity clips.
        if (data.isHideAlertActive()) {
            return;
        }

        // Read-only gate; tickCooldowns owns the decrement and dodgeArrow owns the reset
        if (threatDodgeCooldown > 0) {
            return;
        }

        List<Entity> threats = getThreatList();

        if (threats.isEmpty()){
            return;
        }

//        for (Entity threat : threats){
//        }

        LOGGER.info("[Greg] {} threat(s) detected nearby, triggering dodge", threats.size());
        // dodgeArrow sets the cooldown itself now — setting it here too would be a second writer
        DodgeHandler.dodgeArrow(this, data);
    }

    /**
     * Dodges when Greg has been unable to see his target for LOS_RECOVERY_TICKS consecutive ticks, so
     * a bad landing can't silently stop him firing.
     *
     * <p><b>Why this is needed:</b> tickRangedAttack self-gates on hasLineOfSight and has no timeout,
     * Greg has no movement goals (every metre comes from a dodge leash), and the leash drops him
     * within ~6 blocks of the arrow rather than on the point — so pickDodgeTarget can verify line of
     * sight for the point and Greg can still land somewhere that doesn't share it. Without this he
     * stands there doing nothing until the next alert happens to move him.
     *
     * <p>This is a <b>retry loop, not a fix</b>: it bounds the stall at ~20 ticks instead of forever.
     * hasLineOfSight clips eye-to-eye while pickDodgeTarget clips boss-block to point-block, so the
     * two can disagree and a recovery dodge may land Greg somewhere equally blind.
     *
     * <p>Runs last of the dodge triggers so alerts and incoming projectiles win the shared cooldown.
     */
    private void tickLosRecovery() {
        ServerLevel serverLevel = (ServerLevel) this.level();
        GregSavedData data = GregSavedData.get(serverLevel.getServer());

        // Hiding is the deliberate opposite of this: tickHide is actively breaking line of sight to
        // survive the Harbinger's 124-tick DEATHLASER. Firing a recovery dodge would put Greg back in
        // it. This guard is the whole reason the feature isn't lethal.
        if (data.isHideAlertActive()) {
            losLostTicks = 0;
            return;
        }

        LivingEntity target = this.getTarget();
        // Only count while settled. A dodge in flight has no line of sight for entirely normal
        // reasons, and the dodge cooldown is already the "Greg is in transit" signal — reusing it
        // beats adding a second timer. See LOS_RECOVERY_TICKS for the coupling this creates.
        if (target == null || threatDodgeCooldown > 0 || this.hasLineOfSight(target)) {
            losLostTicks = 0;
            return;
        }

        if (++losLostTicks >= LOS_RECOVERY_TICKS) {
            LOGGER.info("[Greg] No line of sight to {} for {} ticks — dodging to regain it",
                    target.getType().toShortString(), losLostTicks);
            // Reset on attempt, not on success: dodgeArrow can decline (cooldown, no points,
            // pickDodgeTarget returning -1) and reports nothing back, so this is what gives a
            // predictable one-attempt-per-LOS_RECOVERY_TICKS cadence instead of a per-tick retry.
            losLostTicks = 0;
            // dodgeRegainLos, not dodgeArrow: it falls back to the nearest-to-boss point Greg can see
            // from eye level when pickDodgeTarget finds nothing, which is the state Greg gets stuck in.
            DodgeHandler.dodgeRegainLos(this, data);
        }
    }

    /**
     * Dodges closer to the target when Greg has had line of sight but been beyond the boss's
     * damage-range cap for RANGE_RECOVERY_TICKS consecutive ticks — the symmetric twin of
     * tickLosRecovery, covering the other reason tickRangedAttack goes silent. Without it Greg
     * stands out of range indefinitely (2026-07-22 log: "Holding fire — scylla beyond damage range
     * (23.7 > 12.2 blocks)" until the next alert happened to move him), and pickDynamicDodge's
     * tier-2 "escape past range to survive" comment promises a re-close that nothing delivered.
     * See plans/distance_recover_plan.md.
     *
     * <p>The two conditions are mutually exclusive on any tick (this one requires line of sight,
     * tickLosRecovery requires its absence), so the tickers never both fire; running after it is
     * documentation of relative urgency, not a behavioural tie-break.
     *
     * <p>Like tickLosRecovery this is a <b>retry loop, not a fix</b>: the pick measures range
     * against the boss's position at pick time and the boss moves during the ride, so a landing can
     * be out of range again — bounded at one attempt per RANGE_RECOVERY_TICKS, and
     * pickDynamicApproach's strictly-closer tier means each retry starts nearer.
     */
    private void tickRegainDistance() {
        ServerLevel serverLevel = (ServerLevel) this.level();
        GregSavedData data = GregSavedData.get(serverLevel.getServer());

        // Load-bearing, same as in tickLosRecovery: during Scylla's volley states the mixin holds
        // hideAlertActive true (even with tickHide suppressed), and "move closer to the boss
        // mid-volley" is exactly wrong. Keep this guard even while tickHide is commented out.
        if (data.isHideAlertActive()) {
            rangeLostTicks = 0;
            return;
        }

        LivingEntity target = this.getTarget();
        // Only count while settled and able to see the target: no LOS is tickLosRecovery's
        // jurisdiction, and a dodge in flight is legitimately wherever it is (the cooldown is
        // already the "in transit" signal — see RANGE_RECOVERY_TICKS for the coupling).
        if (target == null || threatDodgeCooldown > 0 || !this.hasLineOfSight(target)
                || this.distanceToSqr(target) <= effectiveRangeLimitSq(target)) {
            rangeLostTicks = 0;
            return;
        }

        if (++rangeLostTicks >= RANGE_RECOVERY_TICKS) {
            LOGGER.info("[Greg] Out of damage range of {} for {} ticks ({} > {} blocks) — dodging closer",
                    target.getType().toShortString(), rangeLostTicks,
                    String.format("%.1f", Math.sqrt(this.distanceToSqr(target))),
                    String.format("%.1f", Math.sqrt(effectiveRangeLimitSq(target))));
            // Reset on attempt, not success — same cadence rule as tickLosRecovery
            rangeLostTicks = 0;
            DodgeHandler.dodgeRegainDistance(this, data);
        }
    }

    /**
     * Returns the damaging volumes Greg is currently standing in.
     *
     * <p><b>Deliberately not part of getThreatList.</b> That method asks "is something flying at me"
     * and answers it with a line-of-sight clip to the threat's centre — which is exactly wrong here:
     * a cloud Greg is standing in can have its centre behind terrain and be discarded as "blocked"
     * while it burns him. Different question, different geometry, different response.
     *
     * <p>The test is <b>AABB intersection</b>, not distance-vs-radius, because that is what the hazard
     * itself uses: Lightning_Area_Effect_Entity damages via
     * {@code getEntitiesOfClass(LivingEntity.class, this.getBoundingBox())}, and setRadius keeps that
     * box in sync through refreshDimensions. Mirroring it means our answer can never disagree with the
     * game's at the edges. Passing Greg's own bounding box to getEntitiesOfClass is the same test from
     * the other side.
     *
     * <p><b>Waiting hazards count.</b> Scylla spawns hers with waitTime = 20, so it exists and renders
     * for a full second before dealing any damage. That telegraph is the only comfortable escape
     * window Greg gets — he takes ~4 damage per application out of 4 HP, so reacting to the first hit
     * is already nearly too late. Do not gate this on isWaiting().
     */
    public List<Entity> getOccupiedHazards() {
        List<Entity> hazards = new ArrayList<>(
                this.level().getEntitiesOfClass(Lightning_Area_Effect_Entity.class, this.getBoundingBox()));

        // Candidates for later, once the mechanism is proven on the one hazard that has actually
        // killed Greg. Wave_Entity is a moving volume with its own lifespan; Lightning_Storm_Entity is
        // a telegraphed strike. Cm_Falling_Block_Entity is NOT a volume and belongs in getThreatList.
        // hazards.addAll(this.level().getEntitiesOfClass(Wave_Entity.class, this.getBoundingBox()));

        return hazards;
    }

    /**
     * Gets Greg out of any damaging volume he is standing in.
     *
     * <p>Runs <b>before every other dodge trigger, including tickHide</b>. Standing in a hazard is the
     * only situation where Greg is already losing health, and "I am taking damage now" strictly
     * dominates "I may take damage soon". The hide it can pre-empt has 124 ticks to re-establish
     * itself; a lightning field kills a 4 HP chicken in about three hits. In practice the two cannot
     * co-occur today — hiding is Harbinger-only and the Harbinger has no radius hazards — so this
     * ordering costs nothing and is here to be correct when that stops being true.
     *
     * <p>Added after Scylla's lightning field killed Greg while he stood in it firing missiles, with
     * no system able to notice. See plans/radius_threat_plan.md.
     */
    private void tickHazardEscape() {
        List<Entity> hazards = getOccupiedHazards();
        if (hazards.isEmpty()) {
            return;
        }

        ServerLevel serverLevel = (ServerLevel) this.level();
        GregSavedData data = GregSavedData.get(serverLevel.getServer());
        DodgeHandler.dodgeEscape(this, data, hazards);
    }

    /** Returns the hazard-escape cooldown; 0 means Greg may attempt another escape. */
    public int getHazardEscapeCooldown() {
        return hazardEscapeCooldown;
    }

    /**
     * Sets the hazard-escape cooldown. DodgeHandler.dodgeEscape is the only writer and sets it on
     * attempt; tickCooldowns owns the decrement. Do not decrement through this setter.
     */
    public void setHazardEscapeCooldown(int ticks) {
        this.hazardEscapeCooldown = ticks;
    }

    /** Returns the stand spot the dynamic search last sent Greg to (null if unset). */
    @javax.annotation.Nullable
    public BlockPos getCurrentDodgeTarget() {
        return currentDodgeTarget;
    }

    /** Records where the dynamic search sent Greg. Called by DodgeHandler/DodgeSearch after a dodge fires. */
    public void setCurrentDodgeTarget(@javax.annotation.Nullable BlockPos target) {
        this.currentDodgeTarget = target;
    }

    /** Returns the remaining threat-dodge cooldown ticks. Used by DodgeHandler to gate dodges. */
    public int getThreatDodgeCooldown() {
        return threatDodgeCooldown;
    }

    /**
     * Sets the threat-dodge cooldown. Two writers: DodgeHandler starts it on a dodge attempt, and
     * IABossAlertMixin clears it to 0 on a priority dodge state (IABossStateNames.isPriorityDodgeState)
     * so that state's alert dodge cannot be gated by a cooldown left over from earlier in a volley.
     * The countdown itself belongs to tickCooldowns; do not decrement through this setter, or the
     * counter drains faster than once per tick again.
     */
    public void setThreatDodgeCooldown(int ticks) {
        this.threatDodgeCooldown = ticks;
    }

    /**
     * Runs the ranged attack timer each server tick. Fires once the countdown reaches zero, but only
     * while Greg can see the target — that line-of-sight gate is what silences him during a hide for
     * free, and what tickLosRecovery exists to notice when it isn't deliberate.
     */
    private void tickRangedAttack() {
        if (dodgeShootLockout > 0) {
            dodgeShootLockout--;
            return;
        }

        LivingEntity target = this.getTarget();
        if (target == null) {
            // Reset state when Greg has no target, so the next stall logs as a fresh transition
            attackTime = -1;
            holdingFire = false;
            outOfRange = false;
            return;
        }

        // Range gate: past the boss's RangeLimit() its hurt() discards Greg's damage entirely, so firing
        // is wasted (and spawns a homing missile that can never land damage). Reads the live per-boss
        // limit, so it tracks the server's Cataclysm config; an uncapped target returns MAX_VALUE and
        // never trips. Edge-triggered log, like holdingFire, to avoid per-tick spam.
        double rangeLimitSq = effectiveRangeLimitSq(target);
        if (this.distanceToSqr(target) > rangeLimitSq) {
            if (!outOfRange) {
                outOfRange = true;
                LOGGER.info("[Greg] Holding fire — {} beyond damage range ({} > {} blocks)",
                        target.getType().toShortString(),
                        String.format("%.1f", Math.sqrt(this.distanceToSqr(target))),
                        String.format("%.1f", Math.sqrt(rangeLimitSq)));
            }
            return;
        }
        outOfRange = false;

        if (!this.hasLineOfSight(target)){
            // Logged on the transition only — per-tick would be ~20 lines per stall and 124 during a
            // DEATHLASER hide. Without it, "Greg is behind a rock" and "Greg has no target" look
            // identical in the log: both are just an absence of Fired lines.
            //
            // This needs its own flag rather than reusing losLostTicks: that counter is reset every
            // tick while hiding or in transit, so it would read as a fresh transition each time.
            if (!holdingFire) {
                holdingFire = true;
                LOGGER.info("[Greg] Holding fire — no line of sight to {}", target.getType().toShortString());
            }
            return;
        }
        holdingFire = false;

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

    /**
     * The squared distance beyond which the target's hurt() discards Greg's damage, with the small
     * slack tickRangedAttack has always used (+5 on the squared distance, ~0.2 blocks at Scylla's 12).
     *
     * <p><b>Shared by tickRangedAttack (the fire gate) and tickRegainDistance (the recovery
     * trigger), and the two MUST read the same expression</b>: a stricter recovery threshold would
     * dodge Greg when he can already shoot, a looser one leaves a dead band where he neither fires
     * nor recovers. See plans/distance_recover_plan.md issue 3.
     */
    private double effectiveRangeLimitSq(LivingEntity target) {
        return DodgeHandler.damageRangeLimitSq(target) + 5;
    }

    /**
     * Fires Greg's ranged attack at the target. The attack itself lives in AttackHandler.
     *
     * <p>{@code distanceFactor} is accepted to satisfy RangedAttackMob and is deliberately unused —
     * neither attack scales with range.
     */
    @Override
    public void performRangedAttack(LivingEntity target, float distanceFactor) {
        if (!this.hasLineOfSight(target)){
            return;
        }

        //AttackHandler.fireWitherHomingMissileAt(this, target);
        AttackHandler.fireSonicBoomAt(this, target);
    }

    /**
     * Returns all tracked boss projectiles within 30 blocks of Greg that aren't blocked by geometry.
     * Maledictus: Phantom_Halberd_Entity, Phantom_Arrow_Entity.
     * Scylla: Spark_Entity, Scylla_Ceraunus_Entity (outbound only).
     * Harbinger, Netherite Monstrosity, Leviathan and Ignis entries are present but commented out.
     *
     * <p><b>Only travelling, self-expiring threats belong here.</b> The list is consumed by
     * tickThreatDetection, which dodges whenever it is non-empty, and the geometry filter below clips
     * from Greg's eye to each threat's bounding-box <i>centre</i>. That shape assumes a moving point
     * that goes away, and two kinds of entity break it:
     * <ul>
     *   <li><b>Persistent</b> ones keep the list non-empty for their whole lifetime, so Greg re-dodges
     *       every time the cooldown expires. This is why the anchor is filtered to its outbound phase
     *       and why Cm_Falling_Block_Entity is commented out
     *   <li><b>Radius</b> ones (Scylla's Lightning_Area_Effect_Entity, up to 32 blocks) make the
     *       centre clip the wrong question entirely: what matters is whether Greg is <i>inside</i>
     *       them, and a cloud he is standing in can have its centre behind terrain and be discarded as
     *       "blocked" while it burns him. Adding those needs a radius-aware branch first
     * </ul>
     *
     * <p><b>There is no owner filter.</b> Harmless while every entry is boss-owned, but Greg now fires
     * a Wither_Homing_Missile_Entity every tick and that type has a commented-out line below —
     * uncommenting it as-is would make his own shots threats and dodge him in circles forever.
     */
    public List<Entity> getThreatList() {
        List<Entity> threats = new ArrayList<>();
        var aabb = this.getBoundingBox().inflate(10);

        // Scylla projectiles. Only the two that are genuinely travelling points — see
        // plans/scylla_plan.md for why her other three damage entities are excluded.
        threats.addAll(this.level().getEntitiesOfClass(Spark_Entity.class, aabb));
        threats.addAll(this.level().getEntitiesOfClass(Lightning_Spear_Entity.class, aabb));
        threats.addAll(this.level().getEntitiesOfClass(Water_Spear_Entity.class, aabb));
        // The thrown anchor is deliberately NOT in this list. It is handled by the hide path instead:
        // ModGameBusEvents raises pendingThreatHide on its spawn and tickThreatHide breaks line of sight
        // so terrain eats it (the anchor flies straight outbound and onHitBlock bounces it back to
        // Scylla without hitting Greg). Keeping it here too made tickThreatDetection dodge Greg *toward*
        // the boss whenever a hide found no covered point, walking him into the anchor's impact hit
        // (cataclysm.storm_bringer) — the double-handling that killed Greg on 2026-07-24. The dodge and
        // the hide fight over the same threatDodgeCooldown, so the anchor gets exactly one responder.

        threats.removeIf(threat -> {
            Vec3 targetCenter = threat.getBoundingBox().getCenter();
            ClipContext ctx = new ClipContext(this.getEyePosition(), targetCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this);
            boolean blocked = this.level().clip(ctx).getType() != HitResult.Type.MISS;
            if (blocked) {
                LOGGER.info("[Greg] Threat blocked by geometry, ignoring: type={} pos={}", threat.getType().toShortString(), threat.blockPosition());
            }
            return blocked;
        });

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
     * Adds an extra elastic pull toward the dodge arrow past 3 blocks, so Greg is dragged to it
     * faster than vanilla alone would manage.
     *
     * <p>Note the return value: {@code false} is what makes {@code Leashable.tickLeash} bail early;
     * returning true lets it run its normal 6/10-block ladder afterwards. That is deliberate — the
     * ladder's elastic pull is what actually moves Greg most of the way. Between 3 and 10 blocks
     * both pulls apply, which is the intended extra yank; past 10 blocks vanilla would drop the
     * leash, but {@link #leashTooFarBehaviour} no-ops that, leaving only this pull.
     */
    @Override
    public boolean handleLeashAtDistance(Entity leashHolder, float distance) {
        if (distance > 1.0F) {
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
     *
     * <p>First refuses damage from any projectile Greg owns. He fires a homing missile every tick
     * (ATTACK_INTERVAL = 1), so ~80 are alive at once; they turn to follow their target and can cross
     * a spot he has been dragged to by a dodge leash, and each one explodes when its 80-tick fuse
     * expires. Vanilla does not protect a shooter from its own projectile — Projectile.canHitEntity
     * excludes only same-vehicle passengers, not the owner — so at 4 HP this would kill him quickly.
     *
     * <p>Both damage paths carry the missile as the damage source's <b>direct</b> entity: a direct hit
     * builds {@code mobProjectile(missile, owner)}, and the fuse explosion passes a null DamageSource
     * to Level.explode so Explosion builds {@code explosion(missile, owner)} itself. So one ownership
     * test covers both.
     *
     * <p>Deliberately keyed on ownership rather than on the damage type: filtering explosions by type
     * would also make Greg immune to Scylla's anchor_explosion and Maledictus's AoE, a large silent
     * buff. Keyed on Projectile rather than Wither_Homing_Missile_Entity so it stays correct if Greg
     * ever fires something else — and it covers the dodge arrow for free. Explosion <b>knockback</b>
     * is applied separately from damage and is NOT suppressed by this.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getDirectEntity() instanceof Projectile projectile && projectile.getOwner() == this) {
            if (!this.level().isClientSide()) {
                LOGGER.info("[Greg] Ignored self-inflicted damage: type={} direct={} amount={}",
                        source.type().msgId(), projectile.getType().toShortString(), amount);
            }
            return false;
        }

        boolean result = super.hurt(source, amount);
        if (!this.level().isClientSide()) {
            LOGGER.info("[Greg] hurt by={} amount={} result={} hp={}/{}",
                    source.type().msgId(), amount, result, this.getHealth(), this.getMaxHealth());
        }
        return result;
    }

    /**
     * Clears Greg's dodge trail and logs his death cause and position before delegating to vanilla
     * death logic (loot drops, death animation trigger, stat tracking).
     * Cleanup lives here rather than in remove() because remove() also fires on chunk unload and
     * dimension change, which would delete the trail while Greg is still alive.
     */
    @Override
    public void die(DamageSource cause) {
        if (!this.level().isClientSide()) {
            LOGGER.info("[Greg] died cause={} pos={}", cause.type().msgId(), this.blockPosition());
            ServerLevel serverLevel = (ServerLevel) this.level();
            DodgeVisualizer.clearTrail(serverLevel, GregSavedData.get(serverLevel.getServer()));
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