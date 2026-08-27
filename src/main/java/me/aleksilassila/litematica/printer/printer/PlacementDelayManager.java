package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.config.Configs;

/**
 * Coordinates delays that must happen between automatic placement operations.
 * This is ticked from the client player tick, before the printer modules run.
 */
public final class PlacementDelayManager {
    public static final PlacementDelayManager INSTANCE = new PlacementDelayManager();

    private int placementCooldown;
    private int inventoryCooldown;
    private boolean inventoryOperationThisTick;

    private PlacementDelayManager() {
    }

    public void tick() {
        inventoryOperationThisTick = false;
        if (!Configs.Core.WORK_SWITCH.getBooleanValue()) {
            placementCooldown = 0;
            inventoryCooldown = 0;
            return;
        }
        if (placementCooldown > 0) placementCooldown--;
        if (inventoryCooldown > 0) inventoryCooldown--;
    }

    public boolean isWaitingForPlacement() {
        return placementCooldown > 0 || inventoryCooldown > 0;
    }

    public void onPlacement() {
        placementCooldown = Math.max(placementCooldown, Configs.Placement.PLACE_INTERVAL.getIntegerValue());
    }

    public void onInventoryOperation() {
        int delay = Configs.Placement.HOTBAR_SWITCH_DELAY.getIntegerValue();
        if (delay <= 0) return;
        inventoryCooldown = Math.max(inventoryCooldown, delay);
        inventoryOperationThisTick = true;
    }

    public boolean wasInventoryOperationThisTick() {
        return inventoryOperationThisTick;
    }

    public void clear() {
        placementCooldown = 0;
        inventoryCooldown = 0;
        inventoryOperationThisTick = false;
    }
}
