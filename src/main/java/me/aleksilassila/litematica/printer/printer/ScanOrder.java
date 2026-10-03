package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.enums.IterationOrderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

/** 单区域遍历、多区域合并和就近排序共用的顺序定义。 */
public final class ScanOrder {
    private ScanOrder() {}

    public static Comparator<BlockPos> coordinates(IterationOrderType mode,
                                                   boolean xForward, boolean yForward, boolean zForward) {
        // 模式首轴变化最快，比较时从变化最慢的轴开始。
        int[] axes = switch (mode) {
            case XYZ -> new int[]{2, 1, 0};
            case XZY -> new int[]{1, 2, 0};
            case YXZ -> new int[]{2, 0, 1};
            case YZX -> new int[]{0, 2, 1};
            case ZXY -> new int[]{1, 0, 2};
            case ZYX -> new int[]{0, 1, 2};
        };
        boolean[] forward = {xForward, yForward, zForward};
        return (left, right) -> {
            for (int axis : axes) {
                int result = Integer.compare(value(left, axis), value(right, axis));
                if (result != 0) return forward[axis] ? result : -result;
            }
            return 0;
        };
    }

    public static Comparator<BlockPos> nearest(Vec3 eye, Comparator<BlockPos> tieBreaker) {
        return Comparator.comparingDouble((BlockPos pos) -> pos.distToCenterSqr(eye))
                .thenComparing(tieBreaker);
    }

    private static int value(BlockPos pos, int axis) {
        return switch (axis) {
            case 0 -> pos.getX();
            case 1 -> pos.getY();
            default -> pos.getZ();
        };
    }
}
