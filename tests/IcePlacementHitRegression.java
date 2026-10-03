import me.aleksilassila.litematica.printer.printer.action.IcePlacementHit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Standalone geometry checks; no Minecraft client/server startup required. */
public final class IcePlacementHitRegression {
    public static void main(String[] args) {
        BlockPos support = new BlockPos(-23, 64, 17);
        int checks = 0;
        // All quarter masks cover straight, inner/outer corner stairs in every orientation.
        for (boolean top : new boolean[]{false, true}) {
            for (int mask = 1; mask < 16; mask++) {
                VoxelShape shape = Shapes.box(0, top ? 0.5 : 0, 0, 1, top ? 1 : 0.5, 1);
                for (int quadrant = 0; quadrant < 4; quadrant++) {
                    if ((mask & (1 << quadrant)) == 0) continue;
                    double x = (quadrant & 1) * 0.5;
                    double z = (quadrant >> 1) * 0.5;
                    shape = Shapes.or(shape, Shapes.box(x, top ? 0 : 0.5, z,
                            x + 0.5, top ? 0.5 : 1, z + 0.5));
                }
                for (Direction face : Direction.values()) {
                    checkFace(shape, support, face);
                    checks++;
                }
                Vec3 hit = IcePlacementHit.find(shape, support, Direction.UP);
                if (Math.abs(hit.y - support.getY() - 1) > 1e-6) {
                    throw new AssertionError("Stair placement must use its uppermost tread");
                }
            }
        }
        // Half slab: the valid surface is at y=0.5, not the old cube-face y=1.
        VoxelShape slab = Shapes.box(0, 0, 0, 1, 0.5, 1);
        checkFace(slab, support, Direction.UP);
        if (Math.abs(IcePlacementHit.find(slab, support, Direction.UP).y - support.getY() - 0.5) > 1e-6) {
            throw new AssertionError("Slab hit height");
        }
        if (IcePlacementHit.find(Shapes.empty(), support, Direction.UP) != null) {
            throw new AssertionError("Empty shape must not provide support");
        }
        System.out.println("PASS: " + checks + " stair faces, slab height and empty support");
    }

    private static void checkFace(VoxelShape shape, BlockPos support, Direction face) {
        Vec3 hit = IcePlacementHit.find(shape, support, face);
        if (hit == null) throw new AssertionError("Missing hit for " + face);
        Vec3 local = hit.subtract(Vec3.atLowerCornerOf(support));
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        // A small square just inside the face must be solid; this catches stair seam hits.
        for (double a : new double[]{-0.01, 0.01}) {
            for (double b : new double[]{-0.01, 0.01}) {
                Vec3 tangent = switch (face.getAxis()) {
                    case X -> new Vec3(0, a, b);
                    case Y -> new Vec3(a, 0, b);
                    case Z -> new Vec3(a, b, 0);
                };
                if (!contains(shape, local.add(tangent).subtract(normal.scale(0.001)))) {
                    throw new AssertionError("Hit crosses a gap/seam: " + face + " " + local);
                }
            }
        }
        if (contains(shape, local.add(normal.scale(0.001)))) {
            throw new AssertionError("Hit is an internal face");
        }
    }

    private static boolean contains(VoxelShape shape, Vec3 point) {
        for (AABB box : shape.toAabbs()) {
            if (box.contains(point)) return true;
        }
        return false;
    }
}
