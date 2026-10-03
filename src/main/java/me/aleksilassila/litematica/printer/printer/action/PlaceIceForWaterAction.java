package me.aleksilassila.litematica.printer.printer.action;

import net.minecraft.world.item.Items;

/** 明确标记临时冰的放置，普通含水方块的放置不进入等水流程。 */
public final class PlaceIceForWaterAction extends Action {
    public PlaceIceForWaterAction() {
        setItem(Items.ICE);
        setRequiresSupport();
    }
}
