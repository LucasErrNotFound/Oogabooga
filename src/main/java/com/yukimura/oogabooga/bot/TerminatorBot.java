package com.yukimura.oogabooga.bot;

import com.mojang.authlib.GameProfile;
import com.yukimura.oogabooga.ai.MovementKind;
import com.yukimura.oogabooga.ai.PathStep;
import com.yukimura.oogabooga.ai.TerminatorPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
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
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class TerminatorBot extends ServerPlayer {

    public static final String BOT_NAME = "Oogaboooga";
    public static final UUID BOT_UUID =
            UUID.nameUUIDFromBytes(("OfflinePlayer:" + BOT_NAME).getBytes(StandardCharsets.UTF_8));

    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int STUCK_REPATH_TICKS = 60;
    private static final int NO_PROGRESS_STACK_TICKS = 50;
    private static final double APPROACH_PROGRESS_EPSILON_SQ = 0.5;
    private static final double TARGET_MOVED_THRESHOLD_SQUARED = 64.0;
    private static final double WAYPOINT_REACHED_DISTANCE_SQUARED = 0.36;
    private static final double STUCK_MOVEMENT_THRESHOLD_SQUARED = 0.0004;
    private static final int BREAK_GRACE_TICKS = 8;
    private static final double MOMENTUM_JUMP_MIN_DISTANCE_SQUARED = 25.0;
    private static final double FLUID_ESCAPE_PUSH = 0.32;

    private static final double JUMP_TAKEOFF_EDGE = 0.7;
    private static final double JUMP_BRINK_EDGE = 0.92;
    private static final double JUMP_CROSS_AXIS_TOLERANCE = 0.35;
    private static final double JUMP_LAUNCH_BASE = 0.24;
    private static final double JUMP_LAUNCH_PER_BLOCK = 0.14;
    private static final int WALK_JUMP_MAX_DISTANCE = 3;

    private static final double CLIMB_SPEED = 0.2;
    private static final double CLIMB_EXIT_SPEED = 0.12;

    private static final double ARRIVAL_ENTER_SQ = 4.0;
    private static final double ARRIVAL_EXIT_SQ = 9.0;
    private static final double ARRIVAL_MAX_DY = 1.5;

    private static final float MAX_YAW_STEP_DEGREES = 12.0f;
    private static final double AIM_LOOKAHEAD_BLOCKS = 2.5;
    private static final double STUCK_FORWARD_PROGRESS = 0.02;

    private static final double STACK_TRIGGER_MIN_HEIGHT = 2.0;
    private static final double STACK_TRIGGER_HORIZONTAL_SQ = 9.0;
    private static final double STACK_OVERRIDE_HORIZONTAL_SQ = 16.0;
    private static final double STACK_ABORT_HORIZONTAL_SQ = 64.0;
    private static final double BUILD_LEVEL_EPSILON = 0.5;
    private static final double BUILD_REACH_SQ = 2.25;
    private static final int BUILD_STALL_LIMIT = 60;
    private static final int STACK_MAX_BLOCKS = 64;

    private static final double BREAK_BUDGET_PER_BLOCK = 1.0;
    private static final int BREAK_BUDGET_MIN = 8;
    private static final int BREAK_BUDGET_MAX = 64;

    private static final Item BUILD_ITEM = Items.DIRT;
    private static final Block BUILD_BLOCK = Blocks.DIRT;
    private static final double PLACE_REACH_SQ = 20.25;
    private static final Direction[] SUPPORT_SEARCH_ORDER = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN, Direction.UP
    };
    private static final int PLACE_FAIL_LIMIT = 10;

    private static final double FALL_SAVE_MIN_DESCENT = 0.15;
    private static final int FALL_SAVE_BAD_DROP = 3;
    private static final int FALL_SAVE_SCAN = 24;

    private static final Item WEAPON_SWORD = Items.DIAMOND_SWORD;
    private static final Item WEAPON_AXE = Items.DIAMOND_AXE;
    private static final double ATTACK_REACH = 3.0;
    private static final double ATTACK_REACH_SQ = ATTACK_REACH * ATTACK_REACH;
    private static final float FULL_CHARGE_THRESHOLD = 0.9f;
    private static final float SHIELD_BREAK_CHARGE_THRESHOLD = 0.45f;
    private static final int TOOL_COMMIT_TICKS = 10;
    private static final int CRIT_COMBO_INTERVAL_TICKS = 140;
    private static final int CRIT_COMBO_AIRBORNE_TIMEOUT = 25;
    private static final int LEDGE_KNOCKOFF_DROP = 3;

    private static @Nullable TerminatorBot activeBot = null;
    private boolean isHunting = false;
    private float wantedForward = 0f;
    private float wantedUpward = 0f;
    private boolean wantedJumping = false;
    private @Nullable Direction escapeFluidDirection = null;
    private int currentJumpDistance = 0;
    private boolean engaged = false;

    private @Nullable List<PathStep> currentPath = null;
    private boolean pathReachesTarget = false;
    private int pathIndex = 0;
    private int ticksSinceRepath = 0;
    private @Nullable BlockPos lastTargetBlock = null;
    private int stuckTicks = 0;
    private Vec3 previousPosition = Vec3.ZERO;
    private @Nullable ServerPlayer huntTarget = null;
    private double bestApproachDistanceSq = Double.MAX_VALUE;
    private int noProgressTicks = 0;

    private boolean stackingUp = false;
    private boolean cobbleEquipped = false;
    private boolean placePending = false;
    private int placeFloorY = Integer.MIN_VALUE;
    private int pillarX = Integer.MIN_VALUE;
    private int pillarZ = Integer.MIN_VALUE;
    private int pillarBlocksPlaced = 0;
    private double buildBestY = Double.NEGATIVE_INFINITY;
    private double buildBestHorizontalSq = Double.MAX_VALUE;
    private int buildStallTicks = 0;

    private @Nullable BlockPos miningTarget = null;
    private float miningProgress = 0f;
    private boolean breakModeActive = false;

    private int placeFailTicks = 0;

    private enum CombatTool { NONE, SWORD, AXE }
    private enum CritPhase { NONE, CHARGE, JUMP, DESCEND }
    private boolean inCombat = false;
    private CombatTool combatTool = CombatTool.NONE;
    private CritPhase critPhase = CritPhase.NONE;
    private int toolCommitTicks = 0;
    private int ticksSinceCrit = 0;
    private int critComboTicks = 0;
    private @Nullable ItemStack swordStack = null;
    private @Nullable ItemStack axeStack = null;

    private TerminatorBot(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
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
        this.ticksSinceRepath = REPATH_INTERVAL_TICKS;
        this.stuckTicks = 0;
        this.engaged = false;
        this.inCombat = false;
        this.combatTool = CombatTool.NONE;
        this.critPhase = CritPhase.NONE;
        this.toolCommitTicks = 0;
        this.ticksSinceCrit = 0;
        this.setItemInHand(InteractionHand.MAIN_HAND, this.weaponStack(WEAPON_SWORD));
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
        this.currentPath = null;
        this.pathIndex = 0;
        this.ticksSinceRepath = 0;
        this.lastTargetBlock = null;
        this.stuckTicks = 0;
        this.engaged = false;
        this.pillarBlocksPlaced = 0;
        this.placeFailTicks = 0;
        this.exitStackUp();
        this.exitCombat();
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

    private boolean isBlockBelow() {
        BlockPos below = BlockPos.containing(this.getX(), this.getY() - 0.01, this.getZ());
        return !this.level().getBlockState(below).getCollisionShape(this.level(), below).isEmpty();
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
    }

    private void runHuntAI() {
        ServerPlayer target = findNearestRealPlayer();
        if (target == null) {
            this.huntTarget = null;
            this.wantedForward = 0f;
            this.wantedUpward = 0f;
            this.wantedJumping = false;
            this.setSprinting(false);
            this.clearMiningProgress();
            return;
        }
        this.huntTarget = target;

        this.lookAtPosition(target.getX(), target.getEyeY(), target.getZ());

        this.recomputePathIfNeeded(target);

        if (this.shouldFallSave()) {
            this.runFallSave(target);
            this.updateStuckTracking();
            return;
        }

        if (this.tryFluidEscape(target)) {
            this.updateStuckTracking();
            return;
        }

        boolean lineOfSight = this.hasAttackLineOfSight(target);

        if (this.isWithinAttackReach(target) && (lineOfSight || this.critPhase != CritPhase.NONE)) {
            if (this.stackingUp) {
                this.exitStackUp();
            }
            this.clearMiningProgress();
            this.runCombat(target);
            this.updateStuckTracking();
            return;
        } else if (this.inCombat) {
            this.exitCombat();
        }

        if (lineOfSight && this.updateArrival(target)) {
            if (this.stackingUp) {
                this.exitStackUp();
            } else {
                this.clearMiningProgress();
            }
            this.brakeAndFace(target);
            this.updateStuckTracking();
            return;
        }

        if (this.shouldStackUp(target)) {
            if (!this.stackingUp) {
                this.enterStackUp();
            }
            this.runStackUp(target);
            this.updateStuckTracking();
            return;
        } else if (this.stackingUp) {
            this.exitStackUp();
        }

        boolean hasPath = this.currentPath != null && !this.currentPath.isEmpty();
        if (hasPath) {
            this.advancePathIndex();
        }

        PathStep currentStep = (hasPath && this.pathIndex < this.currentPath.size())
                ? this.currentPath.get(this.pathIndex)
                : null;
        boolean jumpMode = currentStep != null && currentStep.kind() == MovementKind.JUMP;
        boolean climbMode = currentStep != null
                && (currentStep.kind() == MovementKind.CLIMB_UP || currentStep.kind() == MovementKind.CLIMB_DOWN);
        boolean breakMode = currentStep != null
                && (currentStep.kind() == MovementKind.BREAK_WALK
                    || currentStep.kind() == MovementKind.BREAK_UP
                    || currentStep.kind() == MovementKind.BREAK_DOWN);
        boolean placeMode = currentStep != null && currentStep.kind() == MovementKind.PLACE_BRIDGE;

        float desiredYaw;
        double destinationY;
        boolean waypointIsHigher = false;
        if (jumpMode) {
            BlockPos takeoff = this.currentPath.get(this.pathIndex - 1).position();
            BlockPos landing = currentStep.position();
            desiredYaw = cardinalToward(takeoff, landing).toYRot();
            destinationY = landing.getY();
            this.currentJumpDistance = Math.max(
                    Math.abs(landing.getX() - takeoff.getX()),
                    Math.abs(landing.getZ() - takeoff.getZ()));
        } else if (climbMode) {
            destinationY = currentStep.position().getY();
            desiredYaw = yawToward(target.getX() - this.getX(), target.getZ() - this.getZ());
            this.currentJumpDistance = 0;
        } else if (currentStep != null) {
            BlockPos waypoint = currentStep.position();
            destinationY = waypoint.getY();
            waypointIsHigher = destinationY > this.getY() + 0.5;
            Vec3 aim = this.computeAimPoint();
            desiredYaw = yawToward(aim.x - this.getX(), aim.z - this.getZ());
            this.currentJumpDistance = 0;
        } else {
            destinationY = target.getY();
            desiredYaw = yawToward(target.getX() - this.getX(), target.getZ() - this.getZ());
            this.currentJumpDistance = 0;
        }

        float bodyYaw = jumpMode
                ? desiredYaw
                : approachYaw(this.getYRot(), desiredYaw, MAX_YAW_STEP_DEGREES);
        this.setYRot(bodyYaw);
        this.setYBodyRot(bodyYaw);

        if (climbMode) {
            this.runClimb(currentStep);
            this.updateStuckTracking();
            this.clearMiningProgress();
            return;
        }

        if (breakMode) {
            this.runBreakStep(currentStep);
            this.updateStuckTracking();
            return;
        }

        if (placeMode) {
            this.runPlaceStep(currentStep, target);
            this.updateStuckTracking();
            return;
        }

        this.wantedForward = 1.0f;
        this.setSprinting(!jumpMode || this.currentJumpDistance > WALK_JUMP_MAX_DISTANCE);

        boolean followingRealStep = currentStep != null && this.pathReachesTarget;
        if (this.isInWater()) {
            if (followingRealStep) {
                double verticalDelta = destinationY - this.getY();
                this.wantedUpward = verticalDelta > 0.5 ? 1.0f : (verticalDelta < -0.5 ? -1.0f : 0.0f);
            } else {
                this.wantedUpward = 1.0f;
            }
        } else if (this.isInLava()) {
            this.wantedUpward = 1.0f;
        } else {
            this.wantedUpward = 0.0f;
        }

        boolean steppable = false;
        if (jumpMode) {
            this.wantedJumping = this.shouldLaunchParkourJump(bodyYaw);
        } else {
            steppable = this.isSteppableWallAhead(bodyYaw);
            boolean momentumJump = this.shouldMomentumJump(bodyYaw, target);
            this.wantedJumping = waypointIsHigher || steppable || momentumJump;
        }

        this.setJumping(this.isInLava() && this.wantedForward != 0f);

        boolean patched = !jumpMode && this.tryPatchLaneAhead(bodyYaw, target);

        this.updateStuckTracking();
        if (!jumpMode
                && this.horizontalCollision
                && this.stuckTicks > BREAK_GRACE_TICKS
                && !this.isInWater()
                && !steppable) {
            this.breakBlockInFront(bodyYaw);
        } else if (!patched) {
            this.clearMiningProgress();
            this.holdChaseWeapon();
        }
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


    private boolean isWithinAttackReach(ServerPlayer target) {
        if (!target.isAlive()) {
            return false;
        }
        AABB box = target.getBoundingBox();
        Vec3 eye = this.getEyePosition();
        double deltaX = Math.max(Math.max(box.minX - eye.x, eye.x - box.maxX), 0.0);
        double deltaY = Math.max(Math.max(box.minY - eye.y, eye.y - box.maxY), 0.0);
        double deltaZ = Math.max(Math.max(box.minZ - eye.z, eye.z - box.maxZ), 0.0);
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= ATTACK_REACH_SQ;
    }

    private boolean hasAttackLineOfSight(ServerPlayer target) {
        Vec3 eye = this.getEyePosition();
        Vec3[] aimPoints = {
            target.getEyePosition(),
            target.position().add(0.0, target.getBbHeight() * 0.5, 0.0),
            target.position()
        };
        for (Vec3 point : aimPoints) {
            BlockHitResult hit = this.level().clip(new ClipContext(
                    eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    private void runCombat(ServerPlayer target) {
        this.inCombat = true;
        this.currentJumpDistance = 0;
        this.ticksSinceCrit++;
        if (this.toolCommitTicks > 0) {
            this.toolCommitTicks--;
        }

        float yaw = yawToward(target.getX() - this.getX(), target.getZ() - this.getZ());
        this.setYRot(yaw);
        this.setYBodyRot(yaw);
        this.wantedForward = 0f;
        this.wantedUpward = 0f;

        boolean shieldUp = target.isBlocking();
        if (shieldUp) {
            this.toolCommitTicks = 0;
        }
        CombatTool desired = this.chooseCombatTool(target, shieldUp);
        this.equipCombatTool(desired);

        if (this.combatTool == CombatTool.AXE && !shieldUp
                && (this.critPhase != CritPhase.NONE || this.canStartCritCombo(target))) {
            this.runAxeCritCombo(target);
            return;
        }

        this.critPhase = CritPhase.NONE;
        boolean knockOff = this.combatTool == CombatTool.SWORD && !shieldUp && this.isPlayerNearLedge(target);
        this.setSprinting(knockOff);
        this.wantedJumping = false;
        this.setJumping(false);
        Vec3 velocity = this.getDeltaMovement();
        this.setDeltaMovement(0.0, velocity.y, 0.0);
        float requiredCharge = (shieldUp && this.combatTool == CombatTool.AXE)
                ? SHIELD_BREAK_CHARGE_THRESHOLD : FULL_CHARGE_THRESHOLD;
        if (this.getAttackStrengthScale(0.5f) > requiredCharge) {
            this.executeAttack(target);
        }
    }

    private CombatTool chooseCombatTool(ServerPlayer target, boolean shieldUp) {
        if (shieldUp) {
            return CombatTool.AXE;
        }
        if (this.critPhase != CritPhase.NONE) {
            return CombatTool.AXE;
        }
        if (this.canStartCritCombo(target)) {
            return CombatTool.AXE;
        }
        return CombatTool.SWORD;
    }

    private boolean canStartCritCombo(ServerPlayer target) {
        if (this.stackingUp || !this.isBlockBelow() || !this.hasFootingForJump()) {
            return false;
        }
        if (this.ticksSinceCrit < CRIT_COMBO_INTERVAL_TICKS) {
            return false;
        }
        return target.getY() <= this.getY() + ARRIVAL_MAX_DY;
    }

    private boolean hasFootingForJump() {
        BlockPos below = this.blockPosition().below();
        if (!this.isSolid(below)) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (this.isSolid(below.relative(direction))) {
                return true;
            }
        }
        return false;
    }

    private void runAxeCritCombo(ServerPlayer target) {
        this.setSprinting(false);

        if (this.critPhase == CritPhase.JUMP || this.critPhase == CritPhase.DESCEND) {
            this.critComboTicks++;
            if (this.critComboTicks > CRIT_COMBO_AIRBORNE_TIMEOUT) {
                if (this.isAttackCharged()) {
                    this.executeAttack(target);
                }
                this.abortCritCombo();
                return;
            }
        }

        switch (this.critPhase) {
            case NONE, CHARGE -> {
                this.critPhase = CritPhase.CHARGE;
                this.wantedJumping = false;
                this.setJumping(false);
                Vec3 velocity = this.getDeltaMovement();
                this.setDeltaMovement(0.0, velocity.y, 0.0);
                if (this.isAttackCharged() && this.isBlockBelow()) {
                    this.critPhase = CritPhase.JUMP;
                    this.critComboTicks = 0;
                }
            }
            case JUMP -> {
                this.wantedJumping = true;
                if (!this.isBlockBelow()) {
                    this.critPhase = CritPhase.DESCEND;
                }
            }
            case DESCEND -> {
                this.wantedJumping = false;
                this.setJumping(false);
                boolean descending = !this.isBlockBelow() && this.getDeltaMovement().y < 0.0;
                if (descending && this.isWithinAttackReach(target)) {
                    this.fallDistance = Math.max(this.fallDistance, 1.0);
                    this.executeAttack(target);
                    this.fallDistance = 0.0;
                    this.abortCritCombo();
                } else if (this.isBlockBelow()) {
                    this.abortCritCombo();
                }
            }
        }
    }

    private void abortCritCombo() {
        this.critPhase = CritPhase.NONE;
        this.critComboTicks = 0;
        this.ticksSinceCrit = 0;
        this.wantedJumping = false;
        this.setJumping(false);
    }

    private void executeAttack(ServerPlayer target) {
        this.swing(InteractionHand.MAIN_HAND);
        this.attack(target);
        this.resetAttackStrengthTicker();
    }

    private boolean isAttackCharged() {
        return this.getAttackStrengthScale(0.5f) > FULL_CHARGE_THRESHOLD;
    }

    private boolean isPlayerNearLedge(ServerPlayer target) {
        double deltaX = target.getX() - this.getX();
        double deltaZ = target.getZ() - this.getZ();
        if (deltaX * deltaX + deltaZ * deltaZ < 1.0e-3) {
            return false;
        }
        Direction knockDirection = Direction.fromYRot(yawToward(deltaX, deltaZ));
        BlockPos beyond = target.blockPosition().relative(knockDirection);
        if (this.isSolid(beyond.below())) {
            return false;
        }
        BlockPos scan = beyond.below();
        for (int dropped = 0; dropped < LEDGE_KNOCKOFF_DROP; dropped++) {
            if (this.isSolid(scan)) {
                return false;
            }
            scan = scan.below();
        }
        return true;
    }

    private void equipCombatTool(CombatTool tool) {
        if (this.toolCommitTicks > 0 && this.combatTool != CombatTool.NONE && tool != this.combatTool) {
            return;
        }
        Item item = tool == CombatTool.AXE ? WEAPON_AXE : WEAPON_SWORD;
        if (this.getMainHandItem().getItem() != item) {
            this.setItemInHand(InteractionHand.MAIN_HAND, this.weaponStack(item));
            this.cobbleEquipped = false;
            this.toolCommitTicks = TOOL_COMMIT_TICKS;
        }
        this.combatTool = tool;
    }

    private ItemStack weaponStack(Item item) {
        if (item == WEAPON_AXE) {
            if (this.axeStack == null || this.axeStack.isEmpty()) {
                this.axeStack = this.buildEnchantedWeapon(WEAPON_AXE);
            }
            return this.axeStack;
        }
        if (this.swordStack == null || this.swordStack.isEmpty()) {
            this.swordStack = this.buildEnchantedWeapon(WEAPON_SWORD);
        }
        return this.swordStack;
    }

    private ItemStack buildEnchantedWeapon(Item item) {
        ItemStack stack = new ItemStack(item);
        Holder<Enchantment> unbreaking = this.level().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.UNBREAKING);
        stack.enchant(unbreaking, 3);
        return stack;
    }

    private void holdChaseWeapon() {
        if (this.breakModeActive || this.miningTarget != null || this.placeStepActive()) {
            return;
        }
        if (this.getMainHandItem().getItem() != WEAPON_SWORD) {
            this.setItemInHand(InteractionHand.MAIN_HAND, this.weaponStack(WEAPON_SWORD));
            this.cobbleEquipped = false;
        }
    }

    private void exitCombat() {
        this.inCombat = false;
        this.combatTool = CombatTool.NONE;
        this.critPhase = CritPhase.NONE;
        this.critComboTicks = 0;
        this.toolCommitTicks = 0;
        this.setSprinting(false);
    }

    private void runClimb(PathStep climbStep) {
        this.setSprinting(false);
        this.wantedJumping = false;
        this.setJumping(false);
        if (this.onClimbable()) {
            this.wantedForward = 0f;
            this.wantedUpward = climbStep.kind() == MovementKind.CLIMB_UP ? 1.0f : -1.0f;
        } else {
            BlockPos column = climbStep.position();
            float yaw = yawToward(column.getX() + 0.5 - this.getX(), column.getZ() + 0.5 - this.getZ());
            this.setYRot(yaw);
            this.setYBodyRot(yaw);
            this.wantedForward = 1.0f;
            this.wantedUpward = 0f;
        }
    }

    private void recomputePathIfNeeded(ServerPlayer target) {
        this.ticksSinceRepath++;
        BlockPos targetBlock = target.blockPosition();
        boolean targetMovedFar = this.lastTargetBlock == null
                || this.lastTargetBlock.distSqr(targetBlock) > TARGET_MOVED_THRESHOLD_SQUARED;

        boolean midModify = (this.breakModeActive && this.miningTarget != null) || this.placeStepActive();
        boolean needsPath;
        if (this.stackingUp) {
            needsPath = targetMovedFar;
        } else {
            needsPath = this.currentPath == null
                    || (!midModify && this.ticksSinceRepath >= REPATH_INTERVAL_TICKS)
                    || targetMovedFar
                    || (!midModify && this.stuckTicks > STUCK_REPATH_TICKS);
        }
        if (needsPath) {
            if (targetMovedFar) {
                this.clearMiningProgress();
                this.resetProgressWatchdog();
            }
            BlockPos start = this.blockPosition();
            BlockPos searchGoal = this.approachGoal(target);
            TerminatorPathfinder.SearchResult terrain =
                    TerminatorPathfinder.findPath(this.level(), start, searchGoal);
            List<PathStep> chosen = terrain != null ? terrain.steps() : null;
            boolean reached = terrain != null && terrain.reachedGoal();

            if (!reached
                    && TerminatorPathfinder.withinSearchRange(start, searchGoal)
                    && (target.getY() - this.getY()) <= STACK_TRIGGER_MIN_HEIGHT) {
                int modifyBudget = Mth.clamp(
                        (int) (Math.sqrt(start.distSqr(searchGoal)) * BREAK_BUDGET_PER_BLOCK),
                        BREAK_BUDGET_MIN, BREAK_BUDGET_MAX);
                TerminatorPathfinder.SearchResult modifying = TerminatorPathfinder.findPathModifying(
                        this.level(), start, searchGoal, modifyBudget, modifyBudget);
                if (modifying != null && (modifying.reachedGoal() || chosen == null)) {
                    chosen = modifying.steps();
                    reached = modifying.reachedGoal();
                }
            }

            this.currentPath = chosen;
            this.pathReachesTarget = reached;
            this.pathIndex = nearestPathIndex();
            this.ticksSinceRepath = 0;
            this.stuckTicks = 0;
            this.lastTargetBlock = targetBlock;
        }
    }

    private BlockPos approachGoal(ServerPlayer target) {
        BlockPos playerFeet = target.blockPosition();
        if (!this.isSolid(playerFeet.below())) {
            return playerFeet;
        }
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos cell = playerFeet.relative(direction);
            if (!this.isStandableExit(cell)) {
                continue;
            }
            double distanceSq = cell.distSqr(this.blockPosition());
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = cell;
            }
        }
        return best != null ? best : playerFeet;
    }

    private boolean placeStepActive() {
        return this.currentPath != null
                && this.pathIndex < this.currentPath.size()
                && this.currentPath.get(this.pathIndex).kind() == MovementKind.PLACE_BRIDGE;
    }

    private void advancePathIndex() {
        while (this.pathIndex < this.currentPath.size()) {
            BlockPos waypoint = this.currentPath.get(this.pathIndex).position();
            double centerX = waypoint.getX() + 0.5;
            double centerZ = waypoint.getZ() + 0.5;
            double horizontalDistanceSquared =
                    (centerX - this.getX()) * (centerX - this.getX())
                  + (centerZ - this.getZ()) * (centerZ - this.getZ());
            boolean verticallyClose = Math.abs(waypoint.getY() - this.getY()) <= 1.5;
            if (horizontalDistanceSquared <= WAYPOINT_REACHED_DISTANCE_SQUARED && verticallyClose) {
                this.pathIndex++;
            } else {
                break;
            }
        }
    }

    private int nearestPathIndex() {
        if (this.currentPath == null || this.currentPath.isEmpty()) {
            return 0;
        }
        int bestIndex = 0;
        double bestDistanceSquared = Double.MAX_VALUE;
        for (int index = 0; index < this.currentPath.size(); index++) {
            BlockPos position = this.currentPath.get(index).position();
            double centerX = position.getX() + 0.5;
            double centerZ = position.getZ() + 0.5;
            double deltaY = position.getY() - this.getY();
            double distanceSquared = (centerX - this.getX()) * (centerX - this.getX())
                    + (centerZ - this.getZ()) * (centerZ - this.getZ())
                    + deltaY * deltaY;
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                bestIndex = index;
            }
        }
        if (bestIndex > 0 && this.currentPath.get(bestIndex).kind() == MovementKind.JUMP) {
            bestIndex--;
        }
        return bestIndex;
    }

    private Vec3 computeAimPoint() {
        BlockPos first = this.currentPath.get(this.pathIndex).position();
        double previousX = first.getX() + 0.5;
        double previousZ = first.getZ() + 0.5;
        double aimX = previousX;
        double aimZ = previousZ;
        double remaining = AIM_LOOKAHEAD_BLOCKS;
        for (int index = this.pathIndex + 1; index < this.currentPath.size(); index++) {
            PathStep step = this.currentPath.get(index);
            if (step.kind() != MovementKind.WALK && step.kind() != MovementKind.SWIM) {
                break;
            }
            double nextX = step.position().getX() + 0.5;
            double nextZ = step.position().getZ() + 0.5;
            if (!this.hasHorizontalLineOfSight(this.getX(), this.getZ(), nextX, nextZ)) {
                break;
            }
            double segment = Math.sqrt((nextX - previousX) * (nextX - previousX)
                    + (nextZ - previousZ) * (nextZ - previousZ));
            if (segment >= remaining) {
                double fraction = segment > 1.0e-6 ? remaining / segment : 0.0;
                return new Vec3(previousX + (nextX - previousX) * fraction, 0.0,
                        previousZ + (nextZ - previousZ) * fraction);
            }
            remaining -= segment;
            previousX = nextX;
            previousZ = nextZ;
            aimX = nextX;
            aimZ = nextZ;
        }
        return new Vec3(aimX, 0.0, aimZ);
    }

    private boolean hasHorizontalLineOfSight(double fromX, double fromZ, double toX, double toZ) {
        double deltaX = toX - fromX;
        double deltaZ = toZ - fromZ;
        double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        int samples = Math.max(1, (int) Math.ceil(distance * 2.0));
        int feetY = Mth.floor(this.getY());
        for (int sample = 1; sample <= samples; sample++) {
            double fraction = (double) sample / samples;
            BlockPos feet = BlockPos.containing(fromX + deltaX * fraction, feetY, fromZ + deltaZ * fraction);
            if (!this.isPassable(feet) || !this.isPassable(feet.above())) {
                return false;
            }
        }
        return true;
    }

    private static float yawToward(double deltaX, double deltaZ) {
        return (float) (Mth.atan2(deltaZ, deltaX) * (180.0 / Math.PI)) - 90.0f;
    }

    private static Direction cardinalToward(BlockPos from, BlockPos to) {
        int deltaX = to.getX() - from.getX();
        int deltaZ = to.getZ() - from.getZ();
        if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
            return deltaX >= 0 ? Direction.EAST : Direction.WEST;
        }
        return deltaZ >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static float approachYaw(float current, float target, float maxStep) {
        float difference = Mth.degreesDifference(current, target);
        return Mth.wrapDegrees(current + Mth.clamp(difference, -maxStep, maxStep));
    }

    private boolean shouldLaunchParkourJump(float bodyYaw) {
        if (!this.isBlockBelow()) {
            return false;
        }
        double fractionalX = this.getX() - Math.floor(this.getX());
        double fractionalZ = this.getZ() - Math.floor(this.getZ());
        double edgeProgress = 0.0;
        double crossOffset = 1.0;
        switch (Direction.fromYRot(bodyYaw)) {
            case EAST -> { edgeProgress = fractionalX; crossOffset = Math.abs(fractionalZ - 0.5); }
            case WEST -> { edgeProgress = 1.0 - fractionalX; crossOffset = Math.abs(fractionalZ - 0.5); }
            case SOUTH -> { edgeProgress = fractionalZ; crossOffset = Math.abs(fractionalX - 0.5); }
            case NORTH -> { edgeProgress = 1.0 - fractionalZ; crossOffset = Math.abs(fractionalX - 0.5); }
            default -> { return false; }
        }
        boolean atEdgeCentred = edgeProgress >= JUMP_TAKEOFF_EDGE && crossOffset <= JUMP_CROSS_AXIS_TOLERANCE;
        return atEdgeCentred || edgeProgress >= JUMP_BRINK_EDGE;
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

    private boolean isSteppableWallAhead(float bodyYaw) {
        if (this.isStepUpAt(Direction.fromYRot(bodyYaw))) {
            return true;
        }
        Vec3 velocity = this.getDeltaMovement();
        if (velocity.x * velocity.x + velocity.z * velocity.z > 1.0e-4) {
            float velocityYaw = (float) (Mth.atan2(velocity.z, velocity.x) * (180.0 / Math.PI)) - 90.0f;
            return this.isStepUpAt(Direction.fromYRot(velocityYaw));
        }
        return false;
    }

    private boolean isStepUpAt(Direction facing) {
        BlockPos frontFeet = this.blockPosition().relative(facing);
        BlockPos frontHead = frontFeet.above();
        boolean feetBlocked =
                !this.level().getBlockState(frontFeet).getCollisionShape(this.level(), frontFeet).isEmpty();
        boolean headClear =
                this.level().getBlockState(frontHead).getCollisionShape(this.level(), frontHead).isEmpty();
        return feetBlocked && headClear;
    }

    private boolean shouldMomentumJump(float bodyYaw, ServerPlayer target) {
        if (this.isInWater() || !this.isSprinting() || !this.isBlockBelow()) {
            return false;
        }
        double deltaX = target.getX() - this.getX();
        double deltaZ = target.getZ() - this.getZ();
        if (deltaX * deltaX + deltaZ * deltaZ < MOMENTUM_JUMP_MIN_DISTANCE_SQUARED) {
            return false;
        }
        Direction facing = Direction.fromYRot(bodyYaw);
        BlockPos self = this.blockPosition();
        for (int step = 1; step <= 2; step++) {
            BlockPos feet = self.relative(facing, step);
            if (!this.isPassable(feet) || !this.isPassable(feet.above())) {
                return false;
            }
            if (this.isPassable(feet.below())) {
                return false;
            }
        }
        return this.isPassable(self.above().above());
    }

    private boolean isPassable(BlockPos position) {
        return this.level().getBlockState(position).getCollisionShape(this.level(), position).isEmpty();
    }

    private void updateStuckTracking() {
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

    private void resetProgressWatchdog() {
        this.bestApproachDistanceSq = Double.MAX_VALUE;
        this.noProgressTicks = 0;
    }

    private double forwardProgress(double deltaX, double deltaZ) {
        float yawRadians = this.getYRot() * ((float) Math.PI / 180.0f);
        return deltaX * (-Mth.sin(yawRadians)) + deltaZ * Mth.cos(yawRadians);
    }

    private void breakBlockInFront(float bodyYaw) {
        Direction facing = Direction.fromYRot(bodyYaw);
        BlockPos frontFeet = this.blockPosition().relative(facing);
        BlockPos frontHead = frontFeet.above();
        BlockPos obstruction = this.isBreakableObstruction(frontHead) ? frontHead : frontFeet;
        this.progressMine(obstruction);
    }

    private void runBreakStep(PathStep step) {
        this.setSprinting(false);
        this.setJumping(false);
        this.wantedUpward = 0f;
        this.wantedJumping = false;

        if (step.kind() == MovementKind.BREAK_DOWN) {
            this.wantedForward = 0f;
            BlockPos floorCell = this.blockPosition().below();
            if (this.isBreakableObstruction(floorCell)) {
                this.breakModeActive = true;
                this.progressMine(floorCell);
            } else {
                this.breakModeActive = false;
                this.clearMiningProgress();
            }
            return;
        }

        BlockPos previous = this.currentPath.get(Math.max(0, this.pathIndex - 1)).position();
        BlockPos destination = step.position();
        Direction facing = cardinalToward(previous, destination);
        this.setYRot(facing.toYRot());
        this.setYBodyRot(facing.toYRot());

        BlockPos destinationHead = destination.above();
        BlockPos jumpHeadroom = this.blockPosition().above().above();
        BlockPos obstruction = null;
        if (step.kind() == MovementKind.BREAK_UP && this.isBreakableObstruction(jumpHeadroom)) {
            obstruction = jumpHeadroom;
        } else if (this.isBreakableObstruction(destinationHead)) {
            obstruction = destinationHead;
        } else if (this.isBreakableObstruction(destination)) {
            obstruction = destination;
        }

        if (obstruction != null) {
            this.breakModeActive = true;
            this.wantedForward = 0f;
            this.progressMine(obstruction);
            this.dampCrossAxis(facing);
        } else {
            this.breakModeActive = false;
            this.clearMiningProgress();
            this.wantedForward = 1.0f;
            this.wantedJumping = step.kind() == MovementKind.BREAK_UP && this.isBlockBelow();
        }
    }

    private boolean isBreakableObstruction(BlockPos position) {
        BlockState state = this.level().getBlockState(position);
        if (state.isAir() || state.getCollisionShape(this.level(), position).isEmpty()) {
            return false;
        }
        return state.getDestroySpeed(this.level(), position) >= 0.0f;
    }

    private boolean progressMine(BlockPos target) {
        BlockState state = this.level().getBlockState(target);
        if (state.isAir()
                || state.getCollisionShape(this.level(), target).isEmpty()
                || state.getDestroySpeed(this.level(), target) < 0.0f) {
            this.clearMiningProgress();
            return false;
        }
        if (!target.equals(this.miningTarget)) {
            this.clearMiningProgress();
            this.miningTarget = target;
        }
        this.ensureHolding(selectMiningTool(state));
        this.miningProgress += state.getDestroyProgress(this, this.level(), target);
        this.swing(InteractionHand.MAIN_HAND);
        int stage = Mth.clamp((int) (this.miningProgress * 10.0f), 0, 9);
        this.level().destroyBlockProgress(this.getId(), target, stage);
        if (this.miningProgress >= 1.0f) {
            this.level().destroyBlock(target, false);
            this.clearMiningProgress();
            return true;
        }
        return false;
    }

    private void clearMiningProgress() {
        if (this.miningTarget != null) {
            this.level().destroyBlockProgress(this.getId(), this.miningTarget, -1);
            this.miningTarget = null;
        }
        this.miningProgress = 0f;
    }

    private static Item selectMiningTool(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return Items.DIAMOND_PICKAXE;
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return Items.DIAMOND_SHOVEL;
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return Items.DIAMOND_AXE;
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return Items.DIAMOND_HOE;
        return Items.AIR;
    }

    private void ensureHolding(Item item) {
        if (this.getMainHandItem().getItem() != item) {
            this.setItemInHand(InteractionHand.MAIN_HAND,
                    item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item));
        }
    }


    private void holdBuildBlock() {
        ItemStack held = this.getMainHandItem();
        if (held.getItem() != BUILD_ITEM || held.getCount() < 1) {
            this.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BUILD_ITEM, 64));
        }
    }

    private boolean placeBlockSurvival(BlockPos cell, @Nullable ServerPlayer target) {
        if (this.isSolid(cell)) {
            return true;
        }
        if (!this.canPlaceBuildBlock(cell, target)) {
            return false;
        }
        Direction towardSupport = this.findSupportDirection(cell);
        if (towardSupport == null) {
            return false;
        }
        BlockPos supportPos = cell.relative(towardSupport);
        Direction face = towardSupport.getOpposite();
        Vec3 hitVec = Vec3.atCenterOf(supportPos)
                .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        if (this.getEyePosition().distanceToSqr(hitVec) > PLACE_REACH_SQ) {
            return false;
        }
        this.holdBuildBlock();
        BlockHitResult hit = new BlockHitResult(hitVec, face, supportPos, false);
        this.getMainHandItem().useOn(new UseOnContext(this, InteractionHand.MAIN_HAND, hit));
        boolean placed = this.level().getBlockState(cell).is(BUILD_BLOCK);
        if (placed) {
            this.swing(InteractionHand.MAIN_HAND);
        }
        return placed;
    }

    private @Nullable Direction findSupportDirection(BlockPos cell) {
        for (Direction direction : SUPPORT_SEARCH_ORDER) {
            if (this.isSolid(cell.relative(direction))) {
                return direction;
            }
        }
        return null;
    }

    private boolean canPlaceBuildBlock(BlockPos cell, @Nullable ServerPlayer target) {
        BlockState existing = this.level().getBlockState(cell);
        if (!existing.canBeReplaced() && !existing.isAir()) {
            return false;
        }
        if (target != null
                && (cell.equals(target.blockPosition()) || cell.equals(target.blockPosition().above()))) {
            return false;
        }
        if (cell.equals(this.blockPosition()) || cell.equals(this.blockPosition().above())) {
            return false;
        }
        return this.level().isUnobstructed(BUILD_BLOCK.defaultBlockState(), cell, CollisionContext.empty());
    }

    private void runPlaceStep(PathStep step, ServerPlayer target) {
        this.wantedUpward = 0f;
        this.wantedJumping = false;
        this.setJumping(false);

        BlockPos previous = this.currentPath.get(Math.max(0, this.pathIndex - 1)).position();
        BlockPos destination = step.position();
        Direction facing = cardinalToward(previous, destination);
        this.setYRot(facing.toYRot());
        this.setYBodyRot(facing.toYRot());

        this.holdBuildBlock();
        BlockPos floorCell = destination.below();
        boolean supported = this.isSolid(floorCell) || this.placeBlockSurvival(floorCell, target);
        if (supported) {
            this.placeFailTicks = 0;
            this.setSprinting(true);
            this.wantedForward = 1.0f;
        } else {
            this.placeFailTicks++;
            this.setSprinting(false);
            this.wantedForward = 0.0f;
            if (this.placeFailTicks > PLACE_FAIL_LIMIT) {
                this.currentPath = null;
                this.placeFailTicks = 0;
            }
        }
        this.dampCrossAxis(facing);
    }


    private boolean shouldFallSave() {
        if (this.stackingUp || this.placePending) {
            return false;
        }
        if (this.isBlockBelow() || this.onClimbable() || this.isInWater() || this.isInLava()) {
            return false;
        }
        if (this.getDeltaMovement().y >= -FALL_SAVE_MIN_DESCENT) {
            return false;
        }
        if (this.isExecutingPlannedDescent()) {
            return false;
        }
        if (!this.hasAdjacentWall(this.blockPosition().below())) {
            return false;
        }
        return this.isFallLandingBad();
    }

    private boolean isExecutingPlannedDescent() {
        if (this.currentPath == null || this.pathIndex >= this.currentPath.size()) {
            return false;
        }
        MovementKind kind = this.currentPath.get(this.pathIndex).kind();
        return kind == MovementKind.FALL || kind == MovementKind.BREAK_DOWN;
    }

    private boolean hasAdjacentWall(BlockPos cell) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (this.isSolid(cell.relative(direction))) {
                return true;
            }
        }
        return false;
    }

    private boolean isFallLandingBad() {
        BlockPos feet = this.blockPosition();
        for (int depth = 1; depth <= FALL_SAVE_SCAN; depth++) {
            BlockPos cell = feet.below(depth);
            if (!this.level().isLoaded(cell)) {
                return true;
            }
            if (this.level().getFluidState(cell).is(FluidTags.WATER)
                    || this.level().getFluidState(cell).is(FluidTags.LAVA)) {
                return true;
            }
            if (this.isSolid(cell)) {
                return (depth - 1) > FALL_SAVE_BAD_DROP;
            }
        }
        return true;
    }

    private void runFallSave(ServerPlayer target) {
        this.setSprinting(false);
        this.wantedForward = 0f;
        this.wantedUpward = 0f;
        this.wantedJumping = false;
        this.setJumping(false);
        Vec3 velocity = this.getDeltaMovement();
        this.setDeltaMovement(velocity.x * 0.6, velocity.y, velocity.z * 0.6);
        this.placeBlockSurvival(this.blockPosition().below(), target);
    }


    private boolean tryFluidEscape(ServerPlayer target) {
        boolean feetInFluid = !this.level().getFluidState(this.blockPosition()).isEmpty();
        if (!feetInFluid && !this.isInWater() && !this.isInLava()) {
            return false;
        }
        if (!this.isBlockBelow()) {
            return false;
        }
        Direction exit = this.chooseFluidExit(target);
        if (exit == null) {
            return false;
        }
        float yaw = exit.toYRot();
        this.setYRot(yaw);
        this.setYBodyRot(yaw);
        this.wantedForward = 1.0f;
        this.wantedUpward = 1.0f;
        this.wantedJumping = true;
        this.escapeFluidDirection = exit;
        return true;
    }

    private @Nullable Direction chooseFluidExit(ServerPlayer target) {
        BlockPos feet = this.blockPosition();
        double toPlayerX = target.getX() - this.getX();
        double toPlayerZ = target.getZ() - this.getZ();
        double playerLength = Math.sqrt(toPlayerX * toPlayerX + toPlayerZ * toPlayerZ);
        if (playerLength > 1.0e-4) {
            toPlayerX /= playerLength;
            toPlayerZ /= playerLength;
        }
        Direction best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int stepUp = this.fluidExitRise(feet.relative(direction));
            if (stepUp < 0) {
                continue;
            }
            double bearing = direction.getStepX() * toPlayerX + direction.getStepZ() * toPlayerZ;
            double score = bearing + (stepUp == 1 ? 0.5 : 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = direction;
            }
        }
        return best;
    }

    private int fluidExitRise(BlockPos sideFeet) {
        if (this.isStandableExit(sideFeet)) {
            return 0;
        }
        if (this.isStandableExit(sideFeet.above()) && this.isPassable(this.blockPosition().above().above())) {
            return 1;
        }
        return -1;
    }

    private boolean isStandableExit(BlockPos cell) {
        return this.isSolid(cell.below())
                && this.isPassable(cell)
                && this.isPassable(cell.above())
                && this.level().getFluidState(cell).isEmpty()
                && !TerminatorPathfinder.isHazardousFooting(this.level(), cell);
    }

    private boolean tryPatchLaneAhead(float bodyYaw, ServerPlayer target) {
        if (this.isInWater() || this.isInLava() || !this.isBlockBelow()) {
            return false;
        }
        Direction facing = Direction.fromYRot(bodyYaw);
        BlockPos frontFeet = this.blockPosition().relative(facing);
        BlockPos frontSupport = frontFeet.below();
        boolean shallowWaterAhead = this.level().getFluidState(frontFeet).is(FluidTags.WATER)
                && this.isSolid(frontSupport)
                && this.isPassable(frontFeet.above());
        if (shallowWaterAhead) {
            return this.placeBlockSurvival(frontFeet, target);
        }
        boolean oneDeepHoleAhead = this.isPassable(frontFeet)
                && this.isPassable(frontSupport)
                && this.isSolid(frontSupport.below());
        if (oneDeepHoleAhead) {
            return this.placeBlockSurvival(frontSupport, target);
        }
        return false;
    }


    private boolean shouldStackUp(ServerPlayer target) {
        if (!this.isBlockBelow() && (this.isInWater() || this.isInLava())) {
            return false;
        }
        if (this.pillarBlocksPlaced >= STACK_MAX_BLOCKS) {
            return false;
        }
        double deltaX = target.getX() - this.getX();
        double deltaZ = target.getZ() - this.getZ();
        double horizontalSquared = deltaX * deltaX + deltaZ * deltaZ;
        double heightToPlayer = target.getY() - this.getY();

        if (this.stackingUp) {
            if (horizontalSquared <= BUILD_REACH_SQ && heightToPlayer <= 1.5) {
                return false;
            }
            if (heightToPlayer < -2.0) {
                return false;
            }
            if (horizontalSquared > STACK_ABORT_HORIZONTAL_SQ) {
                return false;
            }
            if (this.buildStallTicks > BUILD_STALL_LIMIT) {
                return false;
            }
            return true;
        }

        boolean upwardStalled = this.noProgressTicks > NO_PROGRESS_STACK_TICKS;
        if (upwardStalled
                && heightToPlayer > STACK_TRIGGER_MIN_HEIGHT
                && horizontalSquared <= STACK_OVERRIDE_HORIZONTAL_SQ
                && this.isBlockBelow()) {
            return true;
        }

        if (heightToPlayer <= STACK_TRIGGER_MIN_HEIGHT || horizontalSquared > STACK_TRIGGER_HORIZONTAL_SQ) {
            return false;
        }
        boolean hasUpwardPath = this.currentPath != null && this.maxPathY() >= target.getY() - 1.0;
        if (hasUpwardPath) {
            return false;
        }
        return this.isBlockBelow();
    }

    private double maxPathY() {
        if (this.currentPath == null || this.currentPath.isEmpty()) {
            return Double.NEGATIVE_INFINITY;
        }
        double highest = Double.NEGATIVE_INFINITY;
        for (PathStep step : this.currentPath) {
            highest = Math.max(highest, step.position().getY());
        }
        return highest;
    }

    private void enterStackUp() {
        this.stackingUp = true;
        this.placePending = false;
        this.clearMiningProgress();
        this.resetProgressWatchdog();
        this.pillarBlocksPlaced = 0;
        this.pillarX = Mth.floor(this.getX());
        this.pillarZ = Mth.floor(this.getZ());
        this.buildBestY = this.getY();
        this.buildBestHorizontalSq = Double.MAX_VALUE;
        this.buildStallTicks = 0;
        if (!this.cobbleEquipped) {
            this.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE));
            this.cobbleEquipped = true;
        }
    }

    private void exitStackUp() {
        this.stackingUp = false;
        this.placePending = false;
        this.clearMiningProgress();
        if (this.cobbleEquipped) {
            this.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            this.cobbleEquipped = false;
        }
    }

    private void runStackUp(ServerPlayer target) {
        this.setSprinting(false);
        this.wantedUpward = 0f;
        this.wantedForward = 0f;

        if (this.placePending) {
            this.finishPillarPlacement(target);
        } else if (!this.isBlockBelow()) {
            this.wantedJumping = false;
        } else if (target.getY() - this.getY() > BUILD_LEVEL_EPSILON) {
            BlockPos headRiseCell = this.blockPosition().above().above();
            if (this.isBreakableObstruction(headRiseCell)) {
                this.setJumping(false);
                this.wantedJumping = false;
                this.recenterOnPillar();
                this.buildStallTicks = 0;
                if (this.progressMine(headRiseCell)) {
                    this.ensureHolding(Items.COBBLESTONE);
                }
            } else if (this.isSolid(headRiseCell)) {
                this.setJumping(false);
                this.wantedJumping = false;
            } else {
                this.clearMiningProgress();
                this.startPillarCycle();
            }
        } else {
            this.bridgeTowardPlayer(target);
        }

        this.updateBuildProgress(target);
    }

    private void recenterOnPillar() {
        float yaw = yawToward(this.pillarX + 0.5 - this.getX(), this.pillarZ + 0.5 - this.getZ());
        this.setYRot(yaw);
        this.setYBodyRot(yaw);
        Vec3 velocity = this.getDeltaMovement();
        this.setDeltaMovement(0.0, velocity.y, 0.0);
        this.move(MoverType.SELF, new Vec3(
                (this.pillarX + 0.5 - this.getX()) * 0.2, 0.0, (this.pillarZ + 0.5 - this.getZ()) * 0.2));
    }

    private void startPillarCycle() {
        this.setJumping(false);
        this.recenterOnPillar();
        this.placeFloorY = Mth.floor(this.getY()) - 1;
        this.wantedJumping = true;
        this.placePending = true;
    }

    private void finishPillarPlacement(ServerPlayer target) {
        this.wantedJumping = false;
        if (this.getY() >= this.placeFloorY + 2.0) {
            BlockPos cell = new BlockPos(this.pillarX, this.placeFloorY + 1, this.pillarZ);
            if (this.canPlacePillarBlock(cell, target)) {
                this.level().setBlockAndUpdate(cell, Blocks.COBBLESTONE.defaultBlockState());
                this.swing(InteractionHand.MAIN_HAND);
                this.pillarBlocksPlaced++;
            }
            this.placePending = false;
        }
    }

    private void bridgeTowardPlayer(ServerPlayer target) {
        this.wantedJumping = false;
        this.setJumping(false);
        Direction facing = cardinalToward(this.blockPosition(), target.blockPosition());
        this.setYRot(facing.toYRot());
        this.setYBodyRot(facing.toYRot());

        BlockPos frontFeet = this.blockPosition().relative(facing);
        BlockPos frontHead = frontFeet.above();
        BlockPos frontSupport = frontFeet.below();
        boolean laneClear = this.isPassable(frontFeet) && this.isPassable(frontHead);

        if (!laneClear) {
            BlockPos obstruction = this.isBreakableObstruction(frontFeet) ? frontFeet
                    : (this.isBreakableObstruction(frontHead) ? frontHead : null);
            if (obstruction != null) {
                this.buildStallTicks = 0;
                if (this.progressMine(obstruction)) {
                    this.ensureHolding(Items.COBBLESTONE);
                }
            }
            this.wantedForward = 0.0f;
            this.dampCrossAxis(facing);
            return;
        }

        this.clearMiningProgress();
        boolean supported = this.isSolid(frontSupport);
        if (!supported && this.canPlacePillarBlock(frontSupport, target)) {
            this.ensureHolding(Items.COBBLESTONE);
            this.level().setBlockAndUpdate(frontSupport, Blocks.COBBLESTONE.defaultBlockState());
            this.swing(InteractionHand.MAIN_HAND);
            this.pillarBlocksPlaced++;
            supported = true;
        }
        this.wantedForward = supported ? 1.0f : 0.0f;
        this.dampCrossAxis(facing);
    }

    private void dampCrossAxis(Direction facing) {
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

    private void updateBuildProgress(ServerPlayer target) {
        double deltaX = target.getX() - this.getX();
        double deltaZ = target.getZ() - this.getZ();
        double horizontalSquared = deltaX * deltaX + deltaZ * deltaZ;
        if (this.getY() > this.buildBestY + 0.1 || horizontalSquared < this.buildBestHorizontalSq - 0.1) {
            this.buildBestY = Math.max(this.buildBestY, this.getY());
            this.buildBestHorizontalSq = Math.min(this.buildBestHorizontalSq, horizontalSquared);
            this.buildStallTicks = 0;
        } else {
            this.buildStallTicks++;
        }
    }

    private boolean isSolid(BlockPos position) {
        return !this.level().getBlockState(position).getCollisionShape(this.level(), position).isEmpty();
    }

    private boolean canPlacePillarBlock(BlockPos cell, ServerPlayer target) {
        BlockState existing = this.level().getBlockState(cell);
        if (!existing.canBeReplaced() && !existing.isAir()) {
            return false;
        }
        if (!this.level().getFluidState(cell).isEmpty()) {
            return false;
        }
        if (cell.equals(target.blockPosition()) || cell.equals(target.blockPosition().above())) {
            return false;
        }
        if (this.getY() < cell.getY() + 1.0) {
            return false;
        }
        BlockState cobblestone = Blocks.COBBLESTONE.defaultBlockState();
        return this.level().isUnobstructed(cobblestone, cell, CollisionContext.empty());
    }

    @Override
    public void move(MoverType moverType, Vec3 movement) {
        super.move(moverType, movement);
    }
}
