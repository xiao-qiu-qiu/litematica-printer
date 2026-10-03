package me.aleksilassila.litematica.printer.printer.action;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 明确标记临时冰的放置，普通含水方块的放置不进入等水流程。 */
public final class PlaceIceForWaterAction extends Action {
    // 先点下方真实支撑；没有可用表面再尝试其他邻格，避免 HashMap 遍历顺序影响结果。
    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
    };
    @Nullable
    private Vec3 hit;

    public PlaceIceForWaterAction() {
        setItem(Items.ICE);
    }

    @Override
    public @Nullable Direction getValidSide(ClientLevel world, BlockPos pos) {
        hit = null;
        // AirPlace 点击目标格本身；液体没有可点击表面，但仍可作为破冰产水的下方方块。
        if (Configs.Print.PLACE_IN_AIR.getBooleanValue()) {
            return BlockUtils.isReplaceable(world.getBlockState(pos)) ? Direction.DOWN : null;
        }
        for (Direction side : SUPPORT_ORDER) {
            BlockPos support = pos.relative(side);
            if (BlockUtils.isReplaceable(world.getBlockState(support))) continue;
            hit = IcePlacementHit.find(world.getBlockState(support).getShape(world, support),
                    support, side.getOpposite());
            if (hit != null) return side;
        }
        return null;
    }

    @Override
    public Action queueAction(@NotNull BlockPos blockPos, @NotNull Direction side,
                              boolean useShift, @NotNull LocalPlayer player) {
        if (Configs.Print.PLACE_IN_AIR.getBooleanValue()) {
            return super.queueAction(blockPos, side, useShift, player);
        }
        if (hit != null) {
            ActionManager.INSTANCE.queueClickAt(blockPos.relative(side), side.getOpposite(), hit, useShift);
        }
        return this;
    }
}
