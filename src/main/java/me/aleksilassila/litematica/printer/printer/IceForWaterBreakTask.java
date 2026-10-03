package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.handler.handlers.Print;
import me.aleksilassila.litematica.printer.mixin.extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.PacketUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** 一次仅处理一个目标，完整挖掘后等待服务端结果，再决定是否重试。 */
public final class IceForWaterBreakTask {
    private static final int MAX_TASK_TICKS = 200;
    private final Minecraft client = Minecraft.getInstance();
    private BlockPos pos;
    private ClientLevel level;
    private long startedAt;
    private long retryAt;
    private float progress;
    private boolean mining;
    private ItemStack miningTool = ItemStack.EMPTY;

    public void start(BlockPos target) {
        if (level != client.level) clear();
        if (target == null || client.level == null || pos != null) return;
        pos = target.immutable();
        level = client.level;
        startedAt = ModuleManager.getCurrentHandlerTime();
        retryAt = startedAt;
    }

    @Nullable
    public BlockPos getPos() {
        return level == client.level ? pos : null;
    }

    public void clear() {
        abortMining();
        pos = null;
        level = null;
    }

    private void sendDigPacket(ServerboundPlayerActionPacket.Action action) {
        BlockPos target = pos;
        PacketUtils.sendPacket(sequence -> {
            //#if MC > 11802
            return new ServerboundPlayerActionPacket(action, target, Direction.UP, sequence);
            //#else
            //$$ return new ServerboundPlayerActionPacket(action, target, Direction.UP);
            //#endif
        });
    }

    private void abortMining() {
        if (mining && level == client.level && client.getConnection() != null) {
            sendDigPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
            if (client.player != null) level.destroyBlockProgress(client.player.getId(), pos, -1);
        }
        mining = false;
        progress = 0;
        miningTool = ItemStack.EMPTY;
    }

    public void tick() {
        if (pos == null) return;
        if (level != client.level || client.player == null || client.gameMode == null
                || !Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()
                || !Configs.Print.ENABLED.getBooleanValue()) {
            clear();
            return;
        }
        // 每个 tick 重读；冰已变成水/其他方块时，旧任务立即作废。
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.ICE)) {
            ModuleManager.PRINT.onIceBroken(pos);
            clear();
            return;
        }
        if (!PlayerUtils.canInteracted(pos)
                || !BreakUtils.canBreakBlock(pos) || !BreakUtils.breakRestriction(level.getBlockState(pos))
                || client.gameMode.getPlayerMode().isCreative()) {
            clear();
            return;
        }
        long now = ModuleManager.getCurrentHandlerTime();
        // 等待确认使用独立配置；任务看门狗只约束挖掘/工具准备，避免长确认时间被截断。
        if (now < retryAt) return;
        if (now - startedAt >= MAX_TASK_TICKS) {
            BlockPos retryPos = pos;
            clear();
            BlockPosCooldownManager.INSTANCE.setCooldown(client.level, Print.NAME, retryPos,
                    Print.WATER_RETRY_COOLDOWN_TICKS);
            return;
        }
        // 发出 STOP 后保留任务，避免打印模块抢走工具或重复发包。
        int oldSlot = InventoryUtils.getSelectedSlot(client.player.getInventory());
        if (!InventoryUtils.selectIceBreakingTool(client.player)) {
            abortMining();
            return;
        }
        if (mining && !ItemStack.matches(miningTool, client.player.getMainHandItem())) abortMining();
        if (oldSlot != InventoryUtils.getSelectedSlot(client.player.getInventory())
                || PlacementDelayManager.INSTANCE.isWaitingForPlacement()) {
            return;
        }
        // 使用独立进度，避免原版在攻击键松开时 stopDestroyBlock 清零/发送 ABORT。
        ((MultiPlayerGameModeExtension) client.gameMode).litematica_printer$syncSelectedSlot();
        float delta = state.getDestroyProgress(client.player, level, pos);
        if (!mining) {
            client.gameMode.stopDestroyBlock();
            sendDigPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
            startedAt = now;
            mining = true;
            miningTool = client.player.getMainHandItem().copy();
            // 非瞬间破坏从下一 tick 开始累计，与原版持续挖掘时序一致。
            progress = delta >= 1.0F ? 1.0F : 0.0F;
        } else {
            progress += delta;
        }
        client.player.swing(InteractionHand.MAIN_HAND);
        if (progress >= 1.0F) {
            sendDigPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
            mining = false;
            progress = 0;
            level.destroyBlockProgress(client.player.getId(), pos, -1);
            retryAt = now + Configs.Print.ICE_BREAK_WAIT_TICKS.getIntegerValue();
            startedAt = retryAt;
        } else {
            level.destroyBlockProgress(client.player.getId(), pos, (int) (progress * 10.0F));
        }
    }
}
