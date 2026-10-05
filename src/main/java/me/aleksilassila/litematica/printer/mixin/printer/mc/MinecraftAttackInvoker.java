package me.aleksilassila.litematica.printer.mixin.printer.mc;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAttackInvoker {
    @Invoker("startAttack")
    boolean litematica_printer$startAttack();
}
