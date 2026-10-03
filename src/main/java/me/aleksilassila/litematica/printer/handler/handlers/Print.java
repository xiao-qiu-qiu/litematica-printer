package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchingType;
import me.aleksilassila.litematica.printer.enums.HighlightType;
import me.aleksilassila.litematica.printer.enums.PrintOrderMode;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.interfaces.Implementation;
import me.aleksilassila.litematica.printer.interfaces.compat.TakeItOutCompat;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.printer.action.PlaceIceForWaterAction;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.utils.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class Print extends Module {
    public final static String NAME = "print";

    private final PlacementGuide guide;

    @Getter
    @Setter
    private boolean pistonNeedFix;

    @Getter
    @Setter
    private boolean printerMemorySync;

    private Action action;
    private boolean waitingForMaterials;

    private SchematicBlockContext ctx;

    // canProcessPos 缓存
    private List<String> lastSkipConfig = Collections.emptyList();
    private Set<String> skipSet = Collections.emptySet();
    private Block lastSkipBlock = null;
    private boolean lastSkipResult = false;

    // 等待水产生队列
    @Getter @Setter
    private BlockPos watingForWaterPos;

    // 挖掘看门狗的退避；正常放冰/出水确认超时不额外冷却。
    public static final int WATER_RETRY_COOLDOWN_TICKS = 20;
    private long waterWaitStartedAt;
    private boolean waitingForIcePlacement;

    // 完整含水打印任务独立于扫描游标；产水只结束等待阶段，不释放最终目标。
    @Nullable private BlockPos waterTarget;
    @Nullable private BlockState waterTargetState;
    @Nullable private ClientLevel waterTargetLevel;
    private long finalConfirmationUntil;
    private int finalPlacementAttempts;
    private static final int MAX_FINAL_PLACEMENT_ATTEMPTS = 3;
    private final RouteMotion routeMotion = new RouteMotion();
    private ClientLevel routeLevel;
    private long waterTargetStartedAt;
    private boolean measureFullWaterTask;
    private double averageCompletionTicks = -1;
    private long lastRouteHint = -100;

    public Print() {
        super(NAME, Configs.Print.ENABLED, Configs.Print.PRINT_SELECTION_TYPE, true);
        this.guide = new PlacementGuide(client);
        this.needSchematic = true;
    }

    @Override
    protected int getMaxExecutions() {
        return Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected boolean isPlacementModule() {
        return true;
    }

    @Override
    protected PrintOrderMode getPrintOrderMode() {
        return (PrintOrderMode) Configs.Print.PRINT_ORDER_MODE.getOptionListValue();
    }

    @Override
    protected RouteMotion getRouteMotion() {
        return routeMotion;
    }

    @Override
    protected double getRouteCompletionTicks() {
        if (averageCompletionTicks >= 0) return Math.max(6, averageCompletionTicks + 4);
        return Configs.Print.ICE_PLACEMENT_WAIT_TICKS.getIntegerValue()
                + Configs.Print.ICE_BREAK_WAIT_TICKS.getIntegerValue()
                + Configs.Print.WATER_WAIT_TICKS.getIntegerValue()
                + 2 * Configs.Placement.HOTBAR_SWITCH_DELAY.getIntegerValue() + 4;
    }

    /** 必须在 ModuleManager 的补料、破冰等等待判断之前采样。 */
    public void updateRouteMotion() {
        if (client.level != routeLevel) {
            routeLevel = client.level;
            averageCompletionTicks = -1;
        }
        if (client.player == null || client.level == null || !ConfigUtils.isPrinterEnable()
                || !Configs.Print.ENABLED.getBooleanValue()) {
            routeMotion.reset();
            return;
        }
        routeMotion.sample(client.level, client.player.position(), ModuleManager.getCurrentHandlerTime());
    }

    private void warnIfRouteTooFast(BlockPos pos) {
        if (getPrintOrderMode() != PrintOrderMode.ROUTE || routeMotion.speed() <= 0
                || routeMotion.direction().lengthSqr() == 0) return;
        Vec3 eye = player.getEyePosition();
        RadiusShapeType shape = Configs.Core.ITERATOR_SHAPE.getOptionListValue() instanceof RadiusShapeType value
                ? value : RadiusShapeType.SPHERE;
        long now = ModuleManager.getCurrentHandlerTime();
        if (ScanOrder.along(pos, eye, routeMotion.direction()) < -0.5
                && !ScanOrder.hasCompletionRoom(pos, eye, routeMotion.direction(), routeMotion.speed(),
                    ConfigUtils.getEffectiveRange(), shape, getRouteCompletionTicks())
                && now - lastRouteHint >= 100) {
            MessageUtils.setOverlayMessage(MessageUtils.translatable("litematica-printer.message.routePrintSlowDown"));
            lastRouteHint = now;
        }
    }

    @Override
    protected boolean shouldKeepWaiting(BlockPos pos) {
        return pos.equals(waterTarget);
    }

    @Override
    protected void onDisabled() {
        if (waterTarget != null || watingForWaterPos != null) releaseWaterTarget();
    }

    /** 挖冰任务也调用此检查，确保暂停扫描时仍响应离开范围、换世界和投影变化。 */
    public boolean isWaterTargetValid(BlockPos pos) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        return pos.equals(waterTarget) && waterTargetLevel == client.level
                && Configs.Print.ENABLED.getBooleanValue()
                && Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()
                && schematic != null && schematic.getBlockState(pos).equals(waterTargetState)
                && client.level.getWorldBorder().isWithinBounds(pos)
                && isPosInWorkspace(pos) && PlayerUtils.canInteracted(pos);
    }

    private void retainWaterTarget(BlockPos pos) {
        if (pos.equals(waterTarget)) return;
        warnIfRouteTooFast(pos);
        waterTarget = pos.immutable();
        waterTargetState = ctx.requiredState;
        waterTargetLevel = level;
        waterTargetStartedAt = ModuleManager.getCurrentHandlerTime();
        measureFullWaterTask = action instanceof PlaceIceForWaterAction && BlockUtils.isWaterlogged(ctx.requiredState);
        finalConfirmationUntil = 0;
        finalPlacementAttempts = 0;
    }

    private void releaseWaterTarget() {
        if (waterTarget != null) leaveWaiting(waterTarget);
        waterTarget = null;
        waterTargetState = null;
        waterTargetLevel = null;
        finalConfirmationUntil = 0;
        finalPlacementAttempts = 0;
        BreakUtils.INSTANCE.cancelIceBreak();
        clearWaterWait();
    }

    private boolean skipWaterTarget(BlockPos pos) {
        if (pos.equals(waterTarget)) releaseWaterTarget();
        return false;
    }

    @Override
    public boolean isOnCooldown(@Nullable BlockPos pos) {
        return (pos != null && pos.equals(waterTarget)
                && ModuleManager.getCurrentHandlerTime() < finalConfirmationUntil) || super.isOnCooldown(pos);
    }

    @Override
    protected void preprocess() {
        if (waterTarget != null) {
            boolean valid = isWaterTargetValid(waterTarget);
            boolean complete = valid && isCorrectBlock(waterTarget);
            if (complete && measureFullWaterTask && finalPlacementAttempts <= 1) {
                double elapsed = Math.max(1, ModuleManager.getCurrentHandlerTime() - waterTargetStartedAt);
                // 不把单独放楼梯、补料长等待或反复被拒绝的耗时混入正常完整流程。
                if (elapsed <= 200) {
                    averageCompletionTicks = averageCompletionTicks < 0 ? elapsed
                            : averageCompletionTicks * 0.75 + elapsed * 0.25;
                }
            }
            if (!valid || complete) {
                if (!complete && waterTargetLevel == level && !PlayerUtils.canInteracted(waterTarget)) {
                    warnIfRouteTooFast(waterTarget);
                }
                releaseWaterTarget();
            }
        }
        if (waterTarget != null && finalPlacementAttempts >= MAX_FINAL_PLACEMENT_ATTEMPTS
                && ModuleManager.getCurrentHandlerTime() >= finalConfirmationUntil) {
            // 连续被服务端拒绝时让其他目标先执行，随后仍可重新扫描并重试。
            BlockPos retryPos = waterTarget;
            releaseWaterTarget();
            setCooldown(retryPos, Math.max(WATER_RETRY_COOLDOWN_TICKS, ConfigUtils.getPlaceCooldown()));
        }
        // Module 刷新扫描范围后恢复当前目标；不让新进入范围的位置抢占半成品。
        if (waterTarget != null) enterWaiting(waterTarget);
        // 正确方块会在 needsWork 中提前跳过，必须在筛选前清理已完成的等水状态。
        if (watingForWaterPos == null) return;
        BlockState current = level.getBlockState(watingForWaterPos);
        if (!Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()
                || BlockUtils.isWaterSource(current) || BlockUtils.isWaterlogged(current)
                || (!current.is(Blocks.ICE) && !BlockUtils.isReplaceable(current))) {
            clearWaterWait();
        } else if (waitingForIcePlacement && current.is(Blocks.ICE)) {
            waitingForIcePlacement = false;
            waterWaitStartedAt = ModuleManager.getCurrentHandlerTime();
        }
    }

    private void clearWaterWait() {
        watingForWaterPos = null;
        waterWaitStartedAt = 0;
        waitingForIcePlacement = false;
    }

    /** 冰已消失后才开始计出水等待时间，不把挖掘耗时计入其中。 */
    public void onIceBroken(BlockPos pos) {
        if (pos.equals(watingForWaterPos)) {
            waitingForIcePlacement = false;
            waterWaitStartedAt = ModuleManager.getCurrentHandlerTime();
        }
    }

    @Override
    public boolean canProcessPos(BlockPos blockPos) {
        if (!level.getWorldBorder().isWithinBounds(blockPos)) return skipWaterTarget(blockPos);
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return false;

        BlockState required = schematic.getBlockState(blockPos);
        BlockState current = level.getBlockState(blockPos);

        // 如果原理图为空气且不破坏多余方块，则无法处理
        if (required.isAir()) {
            if (!Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue()) return false;
        }

        this.ctx = new SchematicBlockContext(client, level, schematic, blockPos, current, required);

        if (Configs.Print.PRINT_SKIP.getBooleanValue()) {
            List<String> currentConfig = Configs.Print.PRINT_SKIP_LIST.getStrings();
            if (!currentConfig.equals(lastSkipConfig)) {
                skipSet = new HashSet<>(currentConfig);
                lastSkipConfig = currentConfig;
                lastSkipBlock = null;
            }

            Block block = ctx.requiredState.getBlock();
            if (block != lastSkipBlock) {
                lastSkipBlock = block;
                lastSkipResult = false;
                for (String s : skipSet) {
                    if (PinYinSearchUtils.matchName(s, ctx.requiredState)) {
                        lastSkipResult = true;
                        break;
                    }
                }
            }
            if (lastSkipResult) return skipWaterTarget(blockPos);
        }

        Action action = guide.getAction(ctx);
        if (action == null) return skipWaterTarget(blockPos);
        this.action = action;
        waitingForMaterials = false;
        // 已发出的放冰请求仍需收取确认，背包里最后一块冰耗尽也继续等待。
        if (blockPos.equals(watingForWaterPos)) return true;
        if (isPlacementBlockedByPlayer()) return skipWaterTarget(blockPos);
        if (!prepareMaterials(blockPos)) return skipWaterTarget(blockPos);
        return true;
    }

    private boolean prepareMaterials(BlockPos pos) {
        Item[] items = action.getRequiredItems(ctx.requiredState.getBlock());
        if (InventoryUtils.hasAnyRequiredItem(player, items)) return true;

        // 分类筛选稍后才会锁定材料；提前只读检查，避免给被跳过的候选取物。
        if ((!pos.equals(waterTarget) && !isCycleItemAllowed(items))
                || action.getValidSide(level, pos) == null) return false;

        // 实际补料才进入处理/等待状态，单纯缺料不占用本轮执行次数和黄框。
        waitingForMaterials = RemoteContainerUtils.hasPendingExchange()
                || QuickShulkerUtils.isOpenHandler() || TakeItOutCompat.isAwaitingItem()
                || QuickShulkerUtils.requestShulkerItem(player, items);
        if (waitingForMaterials) return true;

        recordMissingMaterial(items);
        if (items != null && items.length > 0 && items[0] != null
                && Configs.Print.USE_REMOTE_CONTAINER.getBooleanValue()) {
            RemoteContainerUtils.tryGetItemFromContainers(items[0]);
            waitingForMaterials = RemoteContainerUtils.hasPendingExchange();
            if (waitingForMaterials) return true;
        }
        setCooldown(pos, ConfigUtils.getPlaceCooldown());
        return false;
    }

    private boolean isPlacementBlockedByPlayer() {
        if (player.noPhysics) return false;
        Item[] items = action.getRequiredItems(ctx.requiredState.getBlock());
        if (items == null || items.length == 0) return false;
        // 无物品交互、工具操作和破冰不属于放方块；叠半砖/雪层仍要检查。
        Item selected = items[0];
        for (Item item : items) {
            if (InventoryUtils.hasAnyRequiredItem(player, new Item[]{item})) {
                selected = item;
                break;
            }
        }
        if (!(selected instanceof BlockItem blockItem)) return false;
        BlockState placed = blockItem.getBlock() == ctx.requiredState.getBlock()
                ? ctx.requiredState : blockItem.getBlock().defaultBlockState();
        return intersectsPlayer(placed, ctx.blockPos);
    }

    private boolean intersectsPlayer(BlockState state, BlockPos pos) {
        AABB playerBox = player.getBoundingBox();
        for (AABB part : state.getCollisionShape(level, pos, CollisionContext.of(player)).toAabbs()) {
            if (part.move(pos.getX(), pos.getY(), pos.getZ()).intersects(playerBox)) return true;
        }
        return false;
    }

    @Override
    public boolean isCorrectBlock(BlockPos pos) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return true;
        BlockState required = schematic.getBlockState(pos);
        BlockState current = level.getBlockState(pos);
        return BlockMatchingType.get(required, current) == BlockMatchingType.CORRECT;
    }

    @Override
    @Nullable
    protected Item[] getRequiredItems(BlockPos pos) {
        // canProcessPos 已设置 this.action 和 this.ctx
        if (this.action != null && this.ctx != null) {
            return this.action.getRequiredItems(this.ctx.requiredState.getBlock());
        }
        return null;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (waitingForMaterials) {
            enterWaiting(blockPos);
            skipIteration.set(true);
            return;
        }
        boolean placingIceForWater = action instanceof PlaceIceForWaterAction;
        if (Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()
                && BlockUtils.needsWater(ctx.requiredState)
                && (placingIceForWater || ctx.currentState.is(Blocks.ICE)
                    || blockPos.equals(watingForWaterPos))) {
            boolean isWaitingHere = watingForWaterPos != null && watingForWaterPos.equals(blockPos);
            boolean isIce = ctx.currentState.is(Blocks.ICE);
            boolean matchesWaterRequest = BlockUtils.isWaterSource(ctx.currentState) || BlockUtils.isWaterlogged(ctx.currentState);
            long elapsedTicks = ModuleManager.getCurrentHandlerTime() - waterWaitStartedAt;
            int waitTicks = (int) Math.min(Integer.MAX_VALUE, Math.max(0, elapsedTicks));
            int maxWaitTicks = waitingForIcePlacement
                    ? Configs.Print.ICE_PLACEMENT_WAIT_TICKS.getIntegerValue()
                    : Configs.Print.WATER_WAIT_TICKS.getIntegerValue();
            switch (IceForWaterFlow.decide(
                    true, isWaitingHere, isIce, matchesWaterRequest, waitTicks, maxWaitTicks)) {
                case PLACE_BLOCK -> {
                    if (isWaitingHere) {
                        clearWaterWait();
                    }
                    // 水已生成：走下方正常放置流程，放置含水方块（buildAction 已返回对应 Action）
                }
                case KEEP_WAITING -> {
                    if (isIce) {
                        ensureIceBreakQueued(blockPos);
                    }
                    enterWaiting(blockPos);
                    skipIteration.set(true);
                    return;
                }
                case WAIT_TIMEOUT -> {
                    // 本轮 Action 已按最新世界状态生成；确认失败立即继续放置，不额外冷却。
                    clearWaterWait();
                    BreakUtils.INSTANCE.cancelIceBreak();
                    if (isIce) {
                        ensureIceBreakQueued(blockPos);
                        watingForWaterPos = blockPos.immutable();
                        waterWaitStartedAt = ModuleManager.getCurrentHandlerTime();
                        enterWaiting(blockPos);
                        skipIteration.set(true);
                        return;
                    }
                }
                case BREAK_ICE_AND_WAIT -> {
                    retainWaterTarget(blockPos);
                    ensureIceBreakQueued(blockPos);
                    watingForWaterPos = blockPos.immutable();
                    waterWaitStartedAt = ModuleManager.getCurrentHandlerTime();
                    waitingForIcePlacement = false;
                    enterWaiting(blockPos);
                    skipIteration.set(true);
                    return;
                }
                case PLACE_ICE -> {
                    // 是否放冰以实际 Action 为准，不把放楼梯等普通动作误记成放冰。
                }
                case SKIP -> {
                }
            }
        }
        // 等水超时会在本次执行中重试；等待期间玩家可能已走进目标格。
        if (isPlacementBlockedByPlayer()) {
            skipWaterTarget(blockPos);
            return;
        }
        // 下落检查
        if (Configs.Placement.FALLING_CHECK.getBooleanValue()
                && ctx.requiredState.getBlock() instanceof FallingBlock) {
            BlockPos downPos = blockPos.below();

            if (FallingBlock.isFree(level.getBlockState(downPos))) {
                MessageUtils.setOverlayMessage(
                        I18n.BLOCK_NO_SUPPORT.getName(ctx.getRequiredBlockName().getString()));
                addHighlight(blockPos, HighlightType.FAILED);
                skipWaterTarget(blockPos);
                return;
            } else if (level.getBlockState(downPos) != ctx.schematic.getBlockState(downPos)) {
                MessageUtils.setOverlayMessage(
                        I18n.BLOCK_MISMATCH.getName(ctx.getRequiredBlockName().getString()));
                addHighlight(blockPos, HighlightType.FAILED);
                skipWaterTarget(blockPos);
                return;
            }
        }
        Item[] reqItems = action.getRequiredItems(ctx.requiredState.getBlock());
        // 检查是否有待交换的物品
        if (RemoteContainerUtils.hasPendingExchange() || QuickShulkerUtils.isOpenHandler()
                || TakeItOutCompat.isAwaitingItem()) {
            enterWaiting(blockPos);
            skipIteration.set(true);
            return;
        }
        Direction side = action.getValidSide(level, blockPos);
        if (side == null) {
            addHighlight(blockPos, HighlightType.FAILED);
            skipWaterTarget(blockPos);
            return;
        }
        if (!InventoryUtils.switchToItems(player, reqItems)) {
            if (QuickShulkerUtils.isOpenHandler()) {
                enterWaiting(blockPos);
                skipIteration.set(true);
                return;
            }
            setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
            recordMissingMaterial(reqItems);
            if (reqItems != null && reqItems.length > 0 && reqItems[0] != null
                    && !QuickShulkerUtils.isOpenHandler()
                    && Configs.Print.USE_REMOTE_CONTAINER.getBooleanValue()) {
                RemoteContainerUtils.tryGetItemFromContainers(reqItems[0]);
            }
            addHighlight(blockPos, HighlightType.FAILED);
            skipWaterTarget(blockPos);
            return;
        }
        if (PlacementDelayManager.INSTANCE.wasInventoryOperationThisTick()) {
            enterWaiting(blockPos);
            skipIteration.set(true);
            return;
        }
        boolean useShift;
        if (action.getShift() == null) {
            useShift =
                    (Implementation.isInteractive(
                                            level.getBlockState(blockPos.relative(side)).getBlock())
                                    && !(action instanceof ClickAction))
                            || Configs.Print.PRINT_FORCED_SNEAK.getBooleanValue();
        } else {
            useShift = action.getShift();
        }
        if (Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue() && BlockUtils.needsWater(ctx.requiredState)
                && (placingIceForWater || BlockUtils.isWaterSource(ctx.currentState))) {
            retainWaterTarget(blockPos);
        }
        action.queueAction(blockPos, side, useShift, player);
        // 放冰只发送请求；等服务器回传目标格确实是冰，再开始挖掘。
        if (placingIceForWater) {
            ActionManager.INSTANCE.setLook(action.getPlayerLook());
            ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.getNeedWaitModifyLook());
            ActionManager.INSTANCE.sendQueue(player, false);
            watingForWaterPos = blockPos.immutable();
            waterWaitStartedAt = ModuleManager.getCurrentHandlerTime();
            waitingForIcePlacement = true;
            enterWaiting(blockPos);
            skipIteration.set(true);
            return;
        }
        Vec3 hitModifier = LitematicaUtils.usePrecisionPlacement(blockPos, ctx.requiredState);
        if (hitModifier != null) {
            ActionManager.INSTANCE.hitModifier = hitModifier;
            ActionManager.INSTANCE.useProtocol = true;
        }
        ActionManager.INSTANCE.setLook(action.getPlayerLook());
        ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.getNeedWaitModifyLook());
        // 多阶段任务以服务端回传的最终状态为完成依据，避免预测成功后过早切目标。
        boolean completingWaterTarget = blockPos.equals(waterTarget);
        if (completingWaterTarget) {
            ActionManager.INSTANCE.setOnSent(() -> {
                if (blockPos.equals(waterTarget)) {
                    finalPlacementAttempts++;
                    finalConfirmationUntil = ModuleManager.getCurrentHandlerTime()
                            + Configs.Print.WATER_WAIT_TICKS.getIntegerValue();
                }
            });
        }
        boolean needWait = ActionManager.INSTANCE.sendQueue(player,
                !completingWaterTarget && !Configs.Placement.PRINT_USE_PACKET.getBooleanValue()).needWaitModifyLook;
        if (completingWaterTarget) {
            enterWaiting(blockPos);
            skipIteration.set(true);
        }
        if (needWait || hitModifier != null || PlacementDelayManager.INSTANCE.isWaitingForPlacement()) {
            skipIteration.set(true);
        }
        setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
        if (reqItems != null)
            addHighlight(blockPos, HighlightType.PLACE);
        else
            addHighlight(blockPos, HighlightType.ADJUST);
    }

    @Override
    public void resetScanState() {
        super.resetScanState();
        releaseWaterTarget();
    }

    private void ensureIceBreakQueued(BlockPos pos) {
        BreakUtils.INSTANCE.addIce(pos);
    }

    private void recordMissingMaterial(Item[] reqItems) {
        if (reqItems != null && reqItems.length > 0 && reqItems[0] != null) {
            MissingMaterialTracker.getInstance()
                    .recordMissing(reqItems[0], ctx.getRequiredBlockName());
        }
    }
}
