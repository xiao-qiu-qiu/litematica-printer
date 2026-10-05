package me.aleksilassila.litematica.printer;

import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import fi.dy.masa.malilib.event.TickHandler;
import me.aleksilassila.litematica.printer.printer.PlacementRecoveryManager;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.config.HotkeysCallback;
import fi.dy.masa.malilib.event.RenderEventHandler;
import me.aleksilassila.litematica.printer.render.BlockHighlightRenderer;
import me.aleksilassila.litematica.printer.render.MissingMaterialHudRenderer;

public class InitHandler implements IInitializationHandler {
    @Override
    public void registerModHandlers() {
        Configs.init();
        HotkeysCallback.initCallbacks();
        // Use the same end-of-client-tick phase as Tweakeroo's periodic attack.
        TickHandler.getInstance().registerClientTickHandler(PlacementRecoveryManager.INSTANCE);
        fi.dy.masa.litematica.render.infohud.InfoHud.getInstance()
                .addInfoHudRenderer(MissingMaterialHudRenderer.INSTANCE, true);

        RenderEventHandler.getInstance().registerWorldLastRenderer(new BlockHighlightRenderer());
    }
}
