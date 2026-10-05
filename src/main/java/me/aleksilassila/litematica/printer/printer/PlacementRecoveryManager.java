package me.aleksilassila.litematica.printer.printer;

import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.mixin.extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.mixin.printer.mc.MinecraftAttackInvoker;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//#if MC >= 260100
import net.minecraft.core.component.DataComponents;
//#endif
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only dispatched print requests count; scan, material and support waits never trigger clicks. */
public final class PlacementRecoveryManager implements IClientTickHandler {
    public static final PlacementRecoveryManager INSTANCE = new PlacementRecoveryManager();
    private static final int CONFIRM_TICKS = 10;
    private static final int MIN_ATTEMPTS = 3;
    private static final int RECENT_REQUEST_TICKS = 20;
    private static final int EXPIRY_TICKS = 200;
    private static final int MAX_PENDING = 256;
    private final Map<BlockPos, Pending> pending = new LinkedHashMap<>();
    private WeakReference<ClientLevel> level = new WeakReference<>(null);
    private long nextRecoveryTick;

    private PlacementRecoveryManager() {}

    private boolean enabled() {
        return Configs.Placement.PLACE_STUCK_ATTACK.getBooleanValue()
                && ConfigUtils.isPrinterEnable() && ConfigUtils.isPrintEnabled();
    }

    private void reset(ClientLevel current) {
        pending.clear();
        level = new WeakReference<>(current);
        nextRecoveryTick = 0;
    }

    public void onSent(BlockPos pos, BlockState expected, boolean localPrediction) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled() || mc.level == null) return;
        if (level.get() != mc.level) reset(mc.level);
        long now = ModuleManager.getCurrentHandlerTime();
        Pending request = pending.get(pos);
        if (request == null || !expected.equals(request.expected) || request.localPrediction != localPrediction
                || now - request.lastSent > EXPIRY_TICKS) {
            if (pending.size() >= MAX_PENDING) pending.remove(pending.keySet().iterator().next());
            request = new Pending(expected, localPrediction, now);
            pending.put(pos.immutable(), request);
        }
        request.attempts++;
        request.lastSent = now;
    }

    /** Run after movement and client-tick-end packets, matching Tweakeroo's periodic click phase. */
    @Override
    public void onClientTick(Minecraft mc) {
        if (!enabled() || mc.level == null || mc.player == null || mc.gameMode == null) {
            reset(null);
            return;
        }
        if (level.get() != mc.level) reset(mc.level);
        long now = ModuleManager.getCurrentHandlerTime();
        // Give local placement prediction time to be confirmed or rolled back before dropping a request.
        pending.entrySet().removeIf(entry -> now - entry.getValue().lastSent > EXPIRY_TICKS
                || ((!entry.getValue().localPrediction || now - entry.getValue().lastSent >= CONFIRM_TICKS)
                && mc.level.getBlockState(entry.getKey()).equals(entry.getValue().expected)));
        if (pending.isEmpty() || now < nextRecoveryTick || mc.screen != null
                || mc.player.isUsingItem() || mc.player.isHandsBusy()
                || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown()
                || !(mc.player.getMainHandItem().getItem() instanceof BlockItem)
                //#if MC >= 260100
                || mc.player.getMainHandItem().has(DataComponents.PIERCING_WEAPON)
                //#endif
                || ((MultiPlayerGameModeExtension) mc.gameMode).litematica_printer$isDestroying()
                || BreakUtils.INSTANCE.isNeedHandle()
                || PlacementDelayManager.INSTANCE.isWaitingForInventory()
                || ActionManager.INSTANCE.target != null) return;

        for (Map.Entry<BlockPos, Pending> entry : pending.entrySet()) {
            Pending request = entry.getValue();
            if (request.attempts < MIN_ATTEMPTS || now - request.firstSent < CONFIRM_TICKS
                    || now - request.lastSent > RECENT_REQUEST_TICKS
                    || mc.level.getBlockState(entry.getKey()).equals(request.expected)
                    || !PlayerUtils.canInteracted(entry.getKey())) continue;
            // Run vanilla's MISS branch, including its attack/swap timers, without targeting nearby blocks/entities.
            HitResult originalHit = mc.hitResult;
            Vec3 miss = mc.player.getEyePosition().add(mc.player.getViewVector(1.0F).scale(5.0));
            try {
                mc.hitResult = BlockHitResult.miss(miss, Direction.UP, BlockPos.containing(miss));
                ((MinecraftAttackInvoker) mc).litematica_printer$startAttack();
            } finally {
                mc.hitResult = originalHit;
            }
            int interval = Configs.Placement.PLACE_STUCK_ATTACK_INTERVAL.getIntegerValue();
            nextRecoveryTick = now + interval;
            // Keep retrying a stuck target at the selected cadence; completion/expiry clears it above.
            if (Configs.Print.ICE_DIAGNOSTICS.getBooleanValue()) {
                Reference.LOGGER.info("[PlacementRecovery] tick={} pos={} action=vanilla_air_click_tick_end interval={}",
                        now, entry.getKey().toShortString(), interval);
            }
            return;
        }
    }

    private static final class Pending {
        final BlockState expected;
        final boolean localPrediction;
        long firstSent;
        long lastSent;
        int attempts;

        Pending(BlockState expected, boolean localPrediction, long now) {
            this.expected = expected;
            this.localPrediction = localPrediction;
            this.firstSent = now;
            this.lastSent = now;
        }
    }
}
