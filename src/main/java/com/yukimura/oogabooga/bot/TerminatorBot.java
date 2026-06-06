package com.yukimura.oogabooga.bot;

import com.mojang.authlib.GameProfile;
import com.yukimura.oogabooga.ai.TerminatorPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static com.yukimura.oogabooga.bot.BotMath.yawToward;
import static com.yukimura.oogabooga.bot.BotTuning.APPROACH_PROGRESS_EPSILON_SQ;
import static com.yukimura.oogabooga.bot.BotTuning.ARRIVAL_ENTER_SQ;
import static com.yukimura.oogabooga.bot.BotTuning.ARRIVAL_EXIT_SQ;
import static com.yukimura.oogabooga.bot.BotTuning.ARRIVAL_MAX_DY;
import static com.yukimura.oogabooga.bot.BotTuning.BUILD_ITEM;
import static com.yukimura.oogabooga.bot.BotTuning.CLIMB_EXIT_SPEED;
import static com.yukimura.oogabooga.bot.BotTuning.CLIMB_SPEED;
import static com.yukimura.oogabooga.bot.BotTuning.FLUID_ESCAPE_PUSH;
import static com.yukimura.oogabooga.bot.BotTuning.HIT_RECOVERY_TICKS;
import static com.yukimura.oogabooga.bot.BotTuning.JUMP_LAUNCH_BASE;
import static com.yukimura.oogabooga.bot.BotTuning.JUMP_LAUNCH_PER_BLOCK;
import static com.yukimura.oogabooga.bot.BotTuning.STUCK_FORWARD_PROGRESS;
import static com.yukimura.oogabooga.bot.BotTuning.STUCK_MOVEMENT_THRESHOLD_SQUARED;

public class TerminatorBot extends ServerPlayer {

    public static final String BOT_NAME = "Oogaboooga";
    public static final UUID BOT_UUID =
            UUID.nameUUIDFromBytes(("OfflinePlayer:" + BOT_NAME).getBytes(StandardCharsets.UTF_8));

    private static @Nullable TerminatorBot activeBot = null;

    final CombatController combat;
    final ChaseNavigator navigator;
    final TerrainModifier terrain;
    final StackUpBuilder stackUp;
    final FluidEscape fluidEscape;
    final FallSave fallSave;

    private boolean isHunting = false;
    private @Nullable ServerPlayer huntTarget = null;
    private boolean engaged = false;

    float wantedForward = 0f;
    float wantedUpward = 0f;
    boolean wantedJumping = false;
    @Nullable Direction escapeFluidDirection = null;
    int currentJumpDistance = 0;
    boolean cobbleEquipped = false;
    int stuckTicks = 0;
    int noProgressTicks = 0;
    int recoveryTicks = 0;

    private Vec3 previousPosition = Vec3.ZERO;
    private double bestApproachDistanceSq = Double.MAX_VALUE;

    private TerminatorBot(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
        this.combat = new CombatController(this);
        this.navigator = new ChaseNavigator(this);
        this.terrain = new TerrainModifier(this);
        this.stackUp = new StackUpBuilder(this);
        this.fluidEscape = new FluidEscape(this);
        this.fallSave = new FallSave(this);
    }

    public static boolean isActive() {
        return activeBot != null;
    }

    public static @Nullable TerminatorBot getActiveBot() {
        return activeBot;
    }

    public static void create(MinecraftServer server, Vec3 spawnPosition, float yaw, float pitch) {
        GameProfile profile = new GameProfile(BOT_UUID, BOT_NAME);
        ServerLevel overworld = server.overworld();
        TerminatorBotConnection connection = new TerminatorBotConnection();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);

        TerminatorBot bot = new TerminatorBot(server, overworld, profile);
        bot.setPos(spawnPosition.x, spawnPosition.y, spawnPosition.z);
        bot.setYRot(yaw);
        bot.setXRot(pitch);
        server.getPlayerList().placeNewPlayer(connection, bot, cookie);

        bot.getAbilities().flying = false;
        bot.getAbilities().mayfly = false;
        bot.onUpdateAbilities();

        activeBot = bot;
    }

    public static void remove(MinecraftServer server) {
        if (activeBot == null) return;
        TerminatorBot bot = activeBot;
        activeBot = null;

        bot.stopHunt();
        server.getPlayerList().remove(bot);
        bot.unRide();
        bot.level().removePlayerImmediately(bot, Entity.RemovalReason.DISCARDED);
    }

    public void commence() {
        this.isHunting = true;
        this.previousPosition = this.position();
        this.navigator.primeRepath();
        this.stuckTicks = 0;
        this.engaged = false;
        this.combat.commence();
    }

    public void stopHunt() {
        this.isHunting = false;
        this.zza = 0f;
        this.xxa = 0f;
        this.yya = 0f;
        this.jumping = false;
        this.wantedForward = 0f;
        this.wantedUpward = 0f;
        this.wantedJumping = false;
        this.setSprinting(false);
        this.navigator.resetPath();
        this.stuckTicks = 0;
        this.engaged = false;
        this.stackUp.clearPillarCount();
        this.terrain.clearPlaceFailTicks();
        this.stackUp.exitStackUp();
        this.combat.exitCombat();
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel serverLevel, DamageSource damageSource) {
        return !(damageSource.getDirectEntity() instanceof Player);
    }

    @Override
    public boolean hurtServer(ServerLevel serverLevel, DamageSource damageSource, float damage) {
        Entity directAttacker = damageSource.getDirectEntity();
        if (directAttacker instanceof Player attackingPlayer) {
            double deltaX = attackingPlayer.getX() - this.getX();
            double deltaZ = attackingPlayer.getZ() - this.getZ();
            this.knockback(0.4, deltaX, deltaZ);
            this.recoveryTicks = HIT_RECOVERY_TICKS;
            this.hurtDuration = 10;
            this.hurtTime = this.hurtDuration;
            this.markHurt();
            this.playHurtSound(damageSource);
            serverLevel.broadcastDamageEvent(this, damageSource);
        }
        return false;
    }

    @Override
    protected void onBelowWorld() {
        teleportTo(level(), getX(), 64.0, getZ(), Set.<Relative>of(), getYRot(), getXRot(), false);
    }

    @Override
    public boolean isEffectiveAi() {
        return true;
    }

    @Override
    public void travel(Vec3 travelVector) {
        this.getAbilities().flying = false;

        if (!this.isHunting) {
            super.travel(travelVector);
            return;
        }

        if (this.escapeFluidDirection != null) {
            Direction exit = this.escapeFluidDirection;
            this.escapeFluidDirection = null;
            this.setDeltaMovement(
                    exit.getStepX() * FLUID_ESCAPE_PUSH,
                    0.42,
                    exit.getStepZ() * FLUID_ESCAPE_PUSH);
            this.move(MoverType.SELF, this.getDeltaMovement());
            return;
        }

        if (this.isInWater()) {
            super.travel(new Vec3(0.0, this.wantedUpward, this.wantedForward));
            return;
        }

        if (this.isInLava()) {
            super.travel(new Vec3(0.0, this.wantedUpward, this.wantedForward));
            return;
        }

        if (this.onClimbable()) {
            float yawRadians = this.getYRot() * ((float) Math.PI / 180.0f);
            double horizontal = this.wantedForward * CLIMB_EXIT_SPEED;
            this.setDeltaMovement(
                    -Mth.sin(yawRadians) * horizontal,
                    this.wantedUpward * CLIMB_SPEED,
                    Mth.cos(yawRadians) * horizontal);
            this.move(MoverType.SELF, this.getDeltaMovement());
            return;
        }

        double forward = (double) this.wantedForward;
        boolean grounded = isBlockBelow();
        if (!grounded) {
            this.setDeltaMovement(this.getDeltaMovement().add(0.0, -0.08, 0.0));
        }
        if (this.wantedJumping && grounded) {
            this.jumpFromGround();
            this.applyParkourLaunchImpulse();
        }
        float speedFactor = grounded
                ? (this.isSprinting() ? 0.13f : 0.1f)
                : 0.02f;
        this.moveRelative(speedFactor, new Vec3(0.0, 0.0, (float) forward));
        this.move(MoverType.SELF, this.getDeltaMovement());
        Vec3 velocity = this.getDeltaMovement();
        double horizontalFriction = isBlockBelow() ? 0.546 : 0.91;
        this.setDeltaMovement(new Vec3(
            velocity.x * horizontalFriction,
            velocity.y * 0.98,
            velocity.z * horizontalFriction
        ));
    }

    boolean isBlockBelow() {
        BlockPos below = BlockPos.containing(this.getX(), this.getY() - 0.01, this.getZ());
        return !this.level().getBlockState(below).getCollisionShape(this.level(), below).isEmpty();
    }

    boolean inHitRecovery() {
        return this.recoveryTicks > 0;
    }

    @Override
    public void tick() {
        super.tick();
        this.doTick();
    }

    @Override
    public void doTick() {
        super.doTick();
        if (this.isHunting) {
            this.runHuntAI();
        }
        if (this.recoveryTicks > 0) {
            this.recoveryTicks--;
        }
    }

    private void runHuntAI() {
        ServerPlayer target = findNearestRealPlayer();
        if (target == null) {
            this.huntTarget = null;
            this.wantedForward = 0f;
            this.wantedUpward = 0f;
            this.wantedJumping = false;
            this.setSprinting(false);
            this.terrain.clearMiningProgress();
            return;
        }
        this.huntTarget = target;

        this.lookAtPosition(target.getX(), target.getEyeY(), target.getZ());

        this.navigator.recomputePathIfNeeded(target);

        if (this.fallSave.shouldFallSave()) {
            this.fallSave.runFallSave(target);
            this.updateStuckTracking();
            return;
        }

        if (this.fluidEscape.tryFluidEscape(target)) {
            this.updateStuckTracking();
            return;
        }

        boolean lineOfSight = this.combat.hasAttackLineOfSight(target);

        if (this.combat.isWithinAttackReach(target) && (lineOfSight || this.combat.isCritActive())) {
            if (this.stackUp.isActive()) {
                this.stackUp.exitStackUp();
            }
            this.terrain.clearMiningProgress();
            this.combat.runCombat(target);
            this.updateStuckTracking();
            return;
        } else if (this.combat.isInCombat()) {
            this.combat.exitCombat();
        }

        if (this.combat.isWithinAttackReach(target) && !lineOfSight && !this.combat.isCritActive()) {
            if (this.combat.tryBreakAttackObstruction(target)) {
                this.updateStuckTracking();
                return;
            }
        }

        if (lineOfSight && this.updateArrival(target)) {
            if (this.stackUp.isActive()) {
                this.stackUp.exitStackUp();
            } else {
                this.terrain.clearMiningProgress();
            }
            this.brakeAndFace(target);
            this.updateStuckTracking();
            return;
        }

        if (this.stackUp.shouldStackUp(target)) {
            if (!this.stackUp.isActive()) {
                this.stackUp.enterStackUp();
            }
            this.stackUp.runStackUp(target);
            this.updateStuckTracking();
            return;
        } else if (this.stackUp.isActive()) {
            this.stackUp.exitStackUp();
        }

        this.navigator.followPath(target);
    }

    private boolean updateArrival(ServerPlayer target) {
        double deltaX = target.getX() - this.getX();
        double deltaZ = target.getZ() - this.getZ();
        double horizontalSquared = deltaX * deltaX + deltaZ * deltaZ;
        double verticalGap = Math.abs(target.getY() - this.getY());
        if (this.engaged) {
            if (horizontalSquared > ARRIVAL_EXIT_SQ || verticalGap > ARRIVAL_MAX_DY) {
                this.engaged = false;
            }
        } else if (horizontalSquared <= ARRIVAL_ENTER_SQ && verticalGap <= ARRIVAL_MAX_DY) {
            this.engaged = true;
            this.resetProgressWatchdog();
        }
        return this.engaged;
    }

    private void brakeAndFace(ServerPlayer target) {
        this.setSprinting(false);
        this.wantedForward = 0f;
        this.wantedUpward = 0f;
        this.wantedJumping = false;
        this.setJumping(false);
        float yaw = yawToward(target.getX() - this.getX(), target.getZ() - this.getZ());
        this.setYRot(yaw);
        this.setYBodyRot(yaw);
        Vec3 velocity = this.getDeltaMovement();
        this.setDeltaMovement(0.0, velocity.y, 0.0);
    }

    void executeAttack(ServerPlayer target) {
        this.swing(InteractionHand.MAIN_HAND);
        this.attack(target);
        this.resetAttackStrengthTicker();
    }

    void executeDescendingCritAttack(ServerPlayer target) {
        this.fallDistance = Math.max(this.fallDistance, 1.0);
        this.executeAttack(target);
        this.fallDistance = 0.0;
    }

    private @Nullable ServerPlayer findNearestRealPlayer() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        ServerPlayer nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (ServerPlayer candidate : serverLevel.players()) {
            if (candidate.getUUID().equals(BOT_UUID) || candidate.isSpectator() || !candidate.isAlive()) {
                continue;
            }
            double distanceSquared = this.distanceToSqr(candidate);
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private void lookAtPosition(double targetX, double targetY, double targetZ) {
        double deltaX = targetX - this.getX();
        double deltaY = targetY - this.getEyeY();
        double deltaZ = targetZ - this.getZ();
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        float yaw = (float) (Mth.atan2(deltaZ, deltaX) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(Mth.atan2(deltaY, horizontalDistance) * (180.0 / Math.PI)));
        this.setYHeadRot(yaw);
        this.setXRot(Mth.clamp(pitch, -90.0f, 90.0f));
    }

    private void applyParkourLaunchImpulse() {
        if (this.currentJumpDistance < 2) {
            return;
        }
        double launchSpeed = JUMP_LAUNCH_BASE + (this.currentJumpDistance - 2) * JUMP_LAUNCH_PER_BLOCK;
        float yawRadians = this.getYRot() * ((float) Math.PI / 180.0f);
        double directionX = -Mth.sin(yawRadians);
        double directionZ = Mth.cos(yawRadians);
        Vec3 velocity = this.getDeltaMovement();
        double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (launchSpeed > horizontalSpeed) {
            this.setDeltaMovement(directionX * launchSpeed, velocity.y, directionZ * launchSpeed);
        }
    }

    void updateStuckTracking() {
        double deltaX = this.getX() - this.previousPosition.x;
        double deltaZ = this.getZ() - this.previousPosition.z;
        double horizontalMovementSquared = deltaX * deltaX + deltaZ * deltaZ;
        boolean barelyMoved = horizontalMovementSquared < STUCK_MOVEMENT_THRESHOLD_SQUARED;
        boolean scrapingWall = this.horizontalCollision
                && this.forwardProgress(deltaX, deltaZ) < STUCK_FORWARD_PROGRESS;
        if (barelyMoved || scrapingWall) {
            this.stuckTicks++;
        } else {
            this.stuckTicks = 0;
        }
        this.previousPosition = this.position();
        this.updateProgressWatchdog();
    }

    private void updateProgressWatchdog() {
        ServerPlayer target = this.huntTarget;
        if (target == null) {
            return;
        }
        double distanceSq = this.distanceToSqr(target);
        if (distanceSq < this.bestApproachDistanceSq - APPROACH_PROGRESS_EPSILON_SQ) {
            this.bestApproachDistanceSq = distanceSq;
            this.noProgressTicks = 0;
        } else {
            this.noProgressTicks++;
        }
    }

    void resetProgressWatchdog() {
        this.bestApproachDistanceSq = Double.MAX_VALUE;
        this.noProgressTicks = 0;
    }

    private double forwardProgress(double deltaX, double deltaZ) {
        float yawRadians = this.getYRot() * ((float) Math.PI / 180.0f);
        return deltaX * (-Mth.sin(yawRadians)) + deltaZ * Mth.cos(yawRadians);
    }

    void ensureHolding(Item item) {
        if (this.getMainHandItem().getItem() != item) {
            this.setItemInHand(InteractionHand.MAIN_HAND,
                    item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item));
        }
    }

    void holdBuildBlock() {
        ItemStack held = this.getMainHandItem();
        if (held.getItem() != BUILD_ITEM || held.getCount() < 1) {
            this.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BUILD_ITEM, 64));
        }
    }

    void dampCrossAxis(Direction facing) {
        Vec3 velocity = this.getDeltaMovement();
        if (facing.getAxis() == Direction.Axis.X) {
            double centerZ = Mth.floor(this.getZ()) + 0.5;
            this.setDeltaMovement(velocity.x, velocity.y, 0.0);
            this.move(MoverType.SELF, new Vec3(0.0, 0.0, (centerZ - this.getZ()) * 0.2));
        } else {
            double centerX = Mth.floor(this.getX()) + 0.5;
            this.setDeltaMovement(0.0, velocity.y, velocity.z);
            this.move(MoverType.SELF, new Vec3((centerX - this.getX()) * 0.2, 0.0, 0.0));
        }
    }

    boolean isPassable(BlockPos position) {
        return this.level().getBlockState(position).getCollisionShape(this.level(), position).isEmpty();
    }

    boolean isSolid(BlockPos position) {
        return !this.level().getBlockState(position).getCollisionShape(this.level(), position).isEmpty();
    }

    boolean isStandableExit(BlockPos cell) {
        return this.isSolid(cell.below())
                && this.isPassable(cell)
                && this.isPassable(cell.above())
                && this.level().getFluidState(cell).isEmpty()
                && !TerminatorPathfinder.isHazardousFooting(this.level(), cell);
    }

    @Override
    public void move(MoverType moverType, Vec3 movement) {
        super.move(moverType, movement);
    }
}
