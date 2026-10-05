package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
//#if MC >= 260100
import me.aleksilassila.litematica.printer.mixin.extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import java.lang.ref.WeakReference;
//#endif

/** Opt-in client evidence only: dispatch does not mean that the server accepted placement. */
public final class IceWaterDiagnostics {
    private String lastStage;
    private BlockPos lastPos;
    private long lastTick;
    //#if MC >= 260100
    private static WeakReference<ClientLevel> traceLevel = new WeakReference<>(null);
    private static BlockPos tracePos;
    private static long traceTick;
    private static long packetOrder;

    /** Connection send entry, after the printer's packet rewriting; not a server acceptance log. */
    public static void recordOutgoing(Packet<?> packet) {
        if (!Configs.Print.ICE_DIAGNOSTICS.getBooleanValue() || !ConfigUtils.isPrinterEnable()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.player == null || mc.gameMode == null
                || tracePos == null || traceLevel.get() != mc.level) return;
        long tick = ModuleManager.getCurrentHandlerTime();
        if (tick - traceTick > 100) return;
        String detail;
        if (packet instanceof ServerboundUseItemOnPacket use) {
            detail = "use_on seq=" + use.getSequence() + " hand=" + use.getHand()
                    + " clicked=" + use.getHitResult().getBlockPos().toShortString()
                    + " face=" + use.getHitResult().getDirection() + " hit=" + use.getHitResult().getLocation();
        } else if (packet instanceof ServerboundSwingPacket swing) {
            detail = "swing hand=" + swing.getHand();
        } else if (packet instanceof ServerboundPlayerActionPacket action) {
            detail = "dig action=" + action.getAction() + " seq=" + action.getSequence()
                    + " pos=" + action.getPos().toShortString() + " face=" + action.getDirection();
        } else if (packet instanceof ServerboundMovePlayerPacket move) {
            detail = "move hasPos=" + move.hasPosition() + " hasRot=" + move.hasRotation()
                    + " x=" + move.getX(Double.NaN) + " y=" + move.getY(Double.NaN) + " z=" + move.getZ(Double.NaN)
                    + " yaw=" + move.getYRot(Float.NaN) + " pitch=" + move.getXRot(Float.NaN)
                    + " grounded=" + move.isOnGround();
        } else if (packet instanceof ServerboundSetCarriedItemPacket slot) {
            detail = "slot selected=" + slot.getSlot();
        } else if (packet instanceof ServerboundPlayerInputPacket input) {
            detail = "input " + input.input();
        } else if (packet instanceof ServerboundClientTickEndPacket) {
            detail = "client_tick_end";
        } else {
            return;
        }
        Reference.LOGGER.info("[IceWaterPacket] order={} tick={} target={} packet={} attackKey={} useKey={} usingItem={} destroying={} attackScale={} swapScale={} hand={} yaw={} pitch={} crosshair={}",
                ++packetOrder, tick, tracePos.toShortString(), detail,
                mc.options.keyAttack.isDown(), mc.options.keyUse.isDown(), mc.player.isUsingItem(),
                ((MultiPlayerGameModeExtension) mc.gameMode).litematica_printer$isDestroying(),
                mc.player.getAttackStrengthScale(0.0F), mc.player.getItemSwapScale(0.0F),
                mc.player.getMainHandItem(), mc.player.getYRot(), mc.player.getXRot(),
                mc.hitResult == null ? "none" : mc.hitResult.getType());
    }
    //#endif

    public void record(BlockPos pos, String stage, int iceRequests, boolean event) {
        if (!Configs.Print.ICE_DIAGNOSTICS.getBooleanValue()) {
            lastStage = null;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (pos == null || mc.player == null || mc.level == null) return;
        long now = ModuleManager.getCurrentHandlerTime();
        //#if MC >= 260100
        if (traceLevel.get() != mc.level) traceLevel = new WeakReference<>(mc.level);
        tracePos = pos.immutable();
        traceTick = now;
        //#endif
        // Repeated waits produce a heartbeat every five seconds, not a line every tick.
        if (!event && stage.equals(lastStage) && pos.equals(lastPos) && now - lastTick < 100) return;
        lastStage = stage;
        lastPos = pos.immutable();
        lastTick = now;
        Reference.LOGGER.info("[IceWater] tick={} pos={} stage={} iceRequests={} slot={} hand={} worldState={} eyeDistance={} airPlace={}",
                now, pos.toShortString(), stage, iceRequests,
                InventoryUtils.getSelectedSlot(mc.player.getInventory()), mc.player.getMainHandItem(),
                mc.level.getBlockState(pos), Math.sqrt(pos.distToCenterSqr(mc.player.getEyePosition())),
                Configs.Print.PLACE_IN_AIR.getBooleanValue());
    }
}
