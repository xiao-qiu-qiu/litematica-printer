package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.utils.PacketUtils;
import me.aleksilassila.litematica.printer.mixin.extension.MultiPlayerGameModeExtension;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel implements PacketUtils.SequenceExtension {

    //#if MC > 11802
    @Final
    @Shadow
    private net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler blockStatePredictionHandler;

    @Override
    public void litematica_printer$sendSequenced(MultiPlayerGameModeExtension.PredictiveAction action) {
        // 与原版一致：开启预测时递增序号，并在发包完成后关闭本次预测作用域。
        try (net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler prediction = blockStatePredictionHandler.startPredicting()) {
            PacketUtils.sendPacket(action.predict(prediction.currentSequence()));
        }
    }
    //#endif
}
