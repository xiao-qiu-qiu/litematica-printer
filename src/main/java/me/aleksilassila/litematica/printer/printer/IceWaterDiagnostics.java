package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Opt-in client evidence only: dispatch does not mean that the server accepted placement. */
public final class IceWaterDiagnostics {
    private String lastStage;
    private BlockPos lastPos;
    private long lastTick;

    public void record(BlockPos pos, String stage, int iceRequests, boolean event) {
        if (!Configs.Print.ICE_DIAGNOSTICS.getBooleanValue()) {
            lastStage = null;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (pos == null || mc.player == null || mc.level == null) return;
        long now = ModuleManager.getCurrentHandlerTime();
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
