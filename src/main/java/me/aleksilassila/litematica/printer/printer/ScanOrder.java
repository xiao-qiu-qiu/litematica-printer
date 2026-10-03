package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.enums.IterationOrderType;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
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

    /** 先收尾身后有足够处理余量的位置，再处理紧急后沿、脚边和前方。 */
    public static Comparator<BlockPos> alongRoute(Vec3 eye, Vec3 direction, double speed,
                                                  double range, RadiusShapeType shape, double completionTicks,
                                                  Comparator<BlockPos> tieBreaker) {
        if (direction.lengthSqr() == 0) return nearest(eye, tieBreaker);
        return Comparator.comparingInt((BlockPos pos) -> routeTier(pos, eye, direction, speed, range, shape, completionTicks))
                .thenComparingDouble(pos -> along(pos, eye, direction) < -0.5
                        ? Math.floor(along(pos, eye, direction)) : 0)
                .thenComparingDouble(pos -> along(pos, eye, direction) < -0.5
                        ? Math.abs((pos.getX() + 0.5 - eye.x) * direction.z
                            - (pos.getZ() + 0.5 - eye.z) * direction.x)
                        : pos.distToCenterSqr(eye))
                .thenComparing(nearest(eye, tieBreaker));
    }

    private static int routeTier(BlockPos pos, Vec3 eye, Vec3 direction, double speed,
                                 double range, RadiusShapeType shape, double ticks) {
        double along = along(pos, eye, direction);
        if (along < -0.5) return hasCompletionRoom(pos, eye, direction, speed, range, shape, ticks) ? 0 : 1;
        return along <= 0.5 ? 2 : 3;
    }

    public static double along(BlockPos pos, Vec3 eye, Vec3 direction) {
        return (pos.getX() + 0.5 - eye.x) * direction.x + (pos.getZ() + 0.5 - eye.z) * direction.z;
    }

    public static boolean hasCompletionRoom(BlockPos pos, Vec3 eye, Vec3 direction, double speed,
                                            double range, RadiusShapeType shape, double ticks) {
        if (speed <= 0) return true;
        // 向前预测完成时的眼睛位置，并留半格余量；中心距离估计比实际方块表面判定保守。
        double travel = speed * ticks + 0.5;
        double dx = Math.abs(pos.getX() + 0.5 - eye.x - direction.x * travel);
        double dy = Math.abs(pos.getY() + 0.5 - eye.y);
        double dz = Math.abs(pos.getZ() + 0.5 - eye.z - direction.z * travel);
        return switch (shape) {
            case CUBE -> Math.max(dx, Math.max(dy, dz)) <= range;
            case OCTAHEDRON -> dx + dy + dz <= range;
            case SPHERE -> dx * dx + dy * dy + dz * dz <= range * range;
        };
    }

    private static int value(BlockPos pos, int axis) {
        return switch (axis) {
            case 0 -> pos.getX();
            case 1 -> pos.getY();
            default -> pos.getZ();
        };
    }
}
