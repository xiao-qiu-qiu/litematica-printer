package me.aleksilassila.litematica.printer.printer.action;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** 从实际轮廓的各个小面取点，避免点到楼梯缺口或台阶交界线。 */
public final class IcePlacementHit {
    private IcePlacementHit() {}

    @Nullable
    public static Vec3 find(VoxelShape shape, BlockPos support, Direction face) {
        if (shape.isEmpty()) return null;
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 origin = Vec3.atLowerCornerOf(support);
        Vec3 best = null;
        double bestDepth = Double.NEGATIVE_INFINITY;
        for (AABB box : shape.toAabbs()) {
            // 四个面内点避开 x/z=0.5 的楼梯台阶交界，不使用整个盒子的面中心。
            for (int i = 1; i < 4; i += 2) {
                for (int j = 1; j < 4; j += 2) {
                    double u = i * 0.25;
                    double v = j * 0.25;
                    Vec3 center = origin.add(
                            box.minX + (box.maxX - box.minX) * (face.getAxis() == Direction.Axis.X ? 0.5 : u),
                            box.minY + (box.maxY - box.minY) * (face.getAxis() == Direction.Axis.Y ? 0.5
                                    : face.getAxis() == Direction.Axis.X ? u : v),
                            box.minZ + (box.maxZ - box.minZ) * (face.getAxis() == Direction.Axis.Z ? 0.5 : v));
                    // 从所需面外侧射入整个形状，排除组合盒之间被遮住的内部面。
                    BlockHitResult result = shape.clip(center.add(normal.scale(2)),
                            center.subtract(normal.scale(2)), support);
                    if (result == null || result.getDirection() != face) continue;
                    Vec3 candidate = result.getLocation();
                    double depth = candidate.subtract(origin).dot(normal);
                    if (depth > bestDepth) {
                        bestDepth = depth;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }
}
