package com.yukimura.oogabooga.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import static com.yukimura.oogabooga.bot.BotMath.yawToward;
import static com.yukimura.oogabooga.bot.BotTuning.ARRIVAL_MAX_DY;
import static com.yukimura.oogabooga.bot.BotTuning.ATTACK_REACH_SQ;
import static com.yukimura.oogabooga.bot.BotTuning.CRIT_COMBO_AIRBORNE_TIMEOUT;
import static com.yukimura.oogabooga.bot.BotTuning.CRIT_COMBO_INTERVAL_TICKS;
import static com.yukimura.oogabooga.bot.BotTuning.FULL_CHARGE_THRESHOLD;
import static com.yukimura.oogabooga.bot.BotTuning.LEDGE_KNOCKOFF_DROP;
import static com.yukimura.oogabooga.bot.BotTuning.SHIELD_BREAK_CHARGE_THRESHOLD;
import static com.yukimura.oogabooga.bot.BotTuning.TOOL_COMMIT_TICKS;
import static com.yukimura.oogabooga.bot.BotTuning.WEAPON_AXE;
import static com.yukimura.oogabooga.bot.BotTuning.WEAPON_SWORD;

final class CombatController {

    private enum CombatTool { NONE, SWORD, AXE }

    private enum CritPhase { NONE, CHARGE, JUMP, DESCEND }

    private final TerminatorBot bot;
    private boolean inCombat = false;
    private CombatTool combatTool = CombatTool.NONE;
    private CritPhase critPhase = CritPhase.NONE;
    private int toolCommitTicks = 0;
    private int ticksSinceCrit = 0;
    private int critComboTicks = 0;
    private @Nullable ItemStack swordStack = null;
    private @Nullable ItemStack axeStack = null;

    CombatController(TerminatorBot bot) {
        this.bot = bot;
    }

    void commence() {
        this.inCombat = false;
        this.combatTool = CombatTool.NONE;
        this.critPhase = CritPhase.NONE;
        this.toolCommitTicks = 0;
        this.ticksSinceCrit = 0;
        bot.setItemInHand(InteractionHand.MAIN_HAND, this.weaponStack(WEAPON_SWORD));
    }

    boolean isInCombat() {
        return this.inCombat;
    }

    boolean isCritActive() {
        return this.critPhase != CritPhase.NONE;
    }

    boolean isWithinAttackReach(ServerPlayer target) {
        if (!target.isAlive()) {
            return false;
        }
        AABB box = target.getBoundingBox();
        Vec3 eye = bot.getEyePosition();
        double deltaX = Math.max(Math.max(box.minX - eye.x, eye.x - box.maxX), 0.0);
        double deltaY = Math.max(Math.max(box.minY - eye.y, eye.y - box.maxY), 0.0);
        double deltaZ = Math.max(Math.max(box.minZ - eye.z, eye.z - box.maxZ), 0.0);
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= ATTACK_REACH_SQ;
    }

    boolean hasAttackLineOfSight(ServerPlayer target) {
        Vec3 eye = bot.getEyePosition();
        Vec3[] aimPoints = {
            target.getEyePosition(),
            target.position().add(0.0, target.getBbHeight() * 0.5, 0.0),
            target.position()
        };
        for (Vec3 point : aimPoints) {
            BlockHitResult hit = bot.level().clip(new ClipContext(
                    eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    boolean tryBreakAttackObstruction(ServerPlayer target) {
        Vec3 eye = bot.getEyePosition();
        Vec3[] aimPoints = {
            target.getEyePosition(),
            target.position().add(0.0, target.getBbHeight() * 0.5, 0.0),
            target.position()
        };
        for (Vec3 point : aimPoints) {
            BlockHitResult hit = bot.level().clip(new ClipContext(
                    eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
            if (hit.getType() != HitResult.Type.BLOCK) {
                continue;
            }
            BlockPos obstructionPosition = hit.getBlockPos();
            if (obstructionPosition.equals(target.blockPosition())
                    || obstructionPosition.equals(target.blockPosition().above())) {
                continue;
            }
            if (!bot.terrain.isBreakableObstruction(obstructionPosition)) {
                continue;
            }
            float yaw = yawToward(
                    obstructionPosition.getX() + 0.5 - bot.getX(),
                    obstructionPosition.getZ() + 0.5 - bot.getZ());
            bot.setYRot(yaw);
            bot.setYBodyRot(yaw);
            bot.lookAtCell(obstructionPosition);
            bot.setSprinting(false);
            bot.wantedForward = 0f;
            bot.wantedUpward = 0f;
            bot.wantedJumping = false;
            bot.setJumping(false);
            Vec3 velocity = bot.getDeltaMovement();
            bot.setDeltaMovement(0.0, velocity.y, 0.0);
            bot.terrain.progressMine(obstructionPosition);
            return true;
        }
        return false;
    }

    void runCombat(ServerPlayer target) {
        this.inCombat = true;
        bot.currentJumpDistance = 0;
        this.ticksSinceCrit++;
        if (this.toolCommitTicks > 0) {
            this.toolCommitTicks--;
        }

        float yaw = yawToward(target.getX() - bot.getX(), target.getZ() - bot.getZ());
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);
        bot.lookAtPlayer(target);
        bot.wantedForward = 0f;
        bot.wantedUpward = 0f;

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
        bot.setSprinting(knockOff);
        bot.wantedJumping = false;
        bot.setJumping(false);
        Vec3 velocity = bot.getDeltaMovement();
        bot.setDeltaMovement(0.0, velocity.y, 0.0);
        float requiredCharge = (shieldUp && this.combatTool == CombatTool.AXE)
                ? SHIELD_BREAK_CHARGE_THRESHOLD : FULL_CHARGE_THRESHOLD;
        if (bot.getAttackStrengthScale(0.5f) > requiredCharge) {
            bot.executeAttack(target);
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
        if (bot.stackUp.isActive() || !bot.isBlockBelow() || !this.hasFootingForJump()) {
            return false;
        }
        if (this.ticksSinceCrit < CRIT_COMBO_INTERVAL_TICKS) {
            return false;
        }
        return target.getY() <= bot.getY() + ARRIVAL_MAX_DY;
    }

    private boolean hasFootingForJump() {
        BlockPos below = bot.blockPosition().below();
        if (!bot.isSolid(below)) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (bot.isSolid(below.relative(direction))) {
                return true;
            }
        }
        return false;
    }

    private void runAxeCritCombo(ServerPlayer target) {
        bot.setSprinting(false);

        if (this.critPhase == CritPhase.JUMP || this.critPhase == CritPhase.DESCEND) {
            this.critComboTicks++;
            if (this.critComboTicks > CRIT_COMBO_AIRBORNE_TIMEOUT) {
                if (this.isAttackCharged()) {
                    bot.executeAttack(target);
                }
                this.abortCritCombo();
                return;
            }
        }

        switch (this.critPhase) {
            case NONE, CHARGE -> {
                this.critPhase = CritPhase.CHARGE;
                bot.wantedJumping = false;
                bot.setJumping(false);
                Vec3 velocity = bot.getDeltaMovement();
                bot.setDeltaMovement(0.0, velocity.y, 0.0);
                if (this.isAttackCharged() && bot.isBlockBelow()) {
                    this.critPhase = CritPhase.JUMP;
                    this.critComboTicks = 0;
                }
            }
            case JUMP -> {
                bot.wantedJumping = true;
                if (!bot.isBlockBelow()) {
                    this.critPhase = CritPhase.DESCEND;
                }
            }
            case DESCEND -> {
                bot.wantedJumping = false;
                bot.setJumping(false);
                boolean descending = !bot.isBlockBelow() && bot.getDeltaMovement().y < 0.0;
                if (descending && this.isWithinAttackReach(target)) {
                    bot.executeDescendingCritAttack(target);
                    this.abortCritCombo();
                } else if (bot.isBlockBelow()) {
                    this.abortCritCombo();
                }
            }
        }
    }

    private void abortCritCombo() {
        this.critPhase = CritPhase.NONE;
        this.critComboTicks = 0;
        this.ticksSinceCrit = 0;
        bot.wantedJumping = false;
        bot.setJumping(false);
    }

    private boolean isAttackCharged() {
        return bot.getAttackStrengthScale(0.5f) > FULL_CHARGE_THRESHOLD;
    }

    private boolean isPlayerNearLedge(ServerPlayer target) {
        double deltaX = target.getX() - bot.getX();
        double deltaZ = target.getZ() - bot.getZ();
        if (deltaX * deltaX + deltaZ * deltaZ < 1.0e-3) {
            return false;
        }
        Direction knockDirection = Direction.fromYRot(yawToward(deltaX, deltaZ));
        BlockPos beyond = target.blockPosition().relative(knockDirection);
        if (bot.isSolid(beyond.below())) {
            return false;
        }
        BlockPos scan = beyond.below();
        for (int dropped = 0; dropped < LEDGE_KNOCKOFF_DROP; dropped++) {
            if (bot.isSolid(scan)) {
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
        if (bot.getMainHandItem().getItem() != item) {
            bot.setItemInHand(InteractionHand.MAIN_HAND, this.weaponStack(item));
            bot.cobbleEquipped = false;
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
        Holder<Enchantment> unbreaking = bot.level().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.UNBREAKING);
        stack.enchant(unbreaking, 3);
        return stack;
    }

    void holdChaseWeapon() {
        if (bot.terrain.isBreakingOrMining() || bot.navigator.isPlacingStep()) {
            return;
        }
        if (bot.getMainHandItem().getItem() != WEAPON_SWORD) {
            bot.setItemInHand(InteractionHand.MAIN_HAND, this.weaponStack(WEAPON_SWORD));
            bot.cobbleEquipped = false;
        }
    }

    void exitCombat() {
        this.inCombat = false;
        this.combatTool = CombatTool.NONE;
        this.critPhase = CritPhase.NONE;
        this.critComboTicks = 0;
        this.toolCommitTicks = 0;
        bot.setSprinting(false);
    }
}
