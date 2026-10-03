import me.aleksilassila.litematica.printer.enums.IterationOrderType;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.printer.RouteMotion;
import me.aleksilassila.litematica.printer.printer.ScanOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ScanOrderRegression {
    public static void main(String[] args) {
        Comparator<BlockPos> axes = ScanOrder.coordinates(IterationOrderType.XZY, true, true, true);
        // XZY 的真实行进顺序：先走完 X，再换 Z 行，最后换 Y 层。
        List<BlockPos> expected = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(0, 0, 1), new BlockPos(1, 0, 1), new BlockPos(0, 1, 0));
        List<BlockPos> shuffled = new ArrayList<>(expected);
        Collections.reverse(shuffled);
        shuffled.sort(axes);
        check(shuffled.equals(expected), "coordinate merge disagrees with row/layer traversal");

        List<BlockPos> line = List.of(new BlockPos(0, 0, -4), new BlockPos(0, 0, -1),
                new BlockPos(0, 0, 2), new BlockPos(0, 0, 6));
        List<BlockPos> south = sort(line, new Vec3(0.5, 1.62, 5.25), axes);
        check(south.getFirst().equals(new BlockPos(0, 0, 6)), "nearby block must beat coordinate order");
        List<BlockPos> northLine = line.stream().map(p -> new BlockPos(p.getX(), p.getY(), -p.getZ() - 1)).toList();
        List<BlockPos> north = sort(northLine, new Vec3(0.5, 1.62, -5.25), axes);
        List<BlockPos> eastLine = line.stream().map(p -> new BlockPos(p.getZ(), p.getY(), p.getX())).toList();
        List<BlockPos> east = sort(eastLine, new Vec3(5.25, 1.62, 0.5), axes);
        for (int i = 0; i < south.size(); i++) {
            check(north.get(i).getZ() == -south.get(i).getZ() - 1, "north/south mirror changed priority");
            check(east.get(i).getX() == south.get(i).getZ(), "east/west axis changed priority");
        }
        List<BlockPos> tied = List.of(new BlockPos(0, 0, -1), new BlockPos(0, 0, 0));
        List<BlockPos> reversed = new ArrayList<>(tied);
        Collections.reverse(reversed);
        check(sort(tied, new Vec3(0.5, 1.62, 0), axes)
                .equals(sort(reversed, new Vec3(0.5, 1.62, 0), axes)), "equal distances need stable ordering");
        checkRouteOrder(axes);
        checkRouteMotion();
        System.out.println("PASS: nearest/route mirrors, rear rows, completion margin, pause/turn/world reset");
    }

    private static void checkRouteOrder(Comparator<BlockPos> axes) {
        Vec3 eye = new Vec3(0.5, 1.62, 0.5);
        Vec3 south = new Vec3(0, 0, 1);
        BlockPos rearLeft = new BlockPos(-1, 0, -2);
        BlockPos rearRight = new BlockPos(1, 0, -2);
        BlockPos adjacentRoad = new BlockPos(2, 0, -2);
        BlockPos newerRear = new BlockPos(0, 0, -1);
        BlockPos feet = new BlockPos(0, 0, 0);
        BlockPos front = new BlockPos(0, 0, 1);
        List<BlockPos> input = List.of(feet, front, newerRear, adjacentRoad, rearRight, rearLeft);
        List<BlockPos> ordered = new ArrayList<>(input);
        ordered.sort(ScanOrder.alongRoute(eye, south, 0.06, 4.5, RadiusShapeType.SPHERE, 12, axes));
        check(ordered.equals(List.of(rearLeft, rearRight, adjacentRoad, newerRear, feet, front)),
                "finish both sides of the older rear row before feet/front");
        List<BlockPos> mirrored = input.stream().map(p -> new BlockPos(p.getX(), p.getY(), -p.getZ())).toList();
        List<BlockPos> north = new ArrayList<>(mirrored);
        north.sort(ScanOrder.alongRoute(eye, new Vec3(0, 0, -1), 0.06, 4.5, RadiusShapeType.SPHERE, 12, axes));
        for (int i = 0; i < ordered.size(); i++) {
            check(north.get(i).equals(new BlockPos(ordered.get(i).getX(), ordered.get(i).getY(), -ordered.get(i).getZ())),
                    "route priority must mirror with walking direction");
        }
        BlockPos edge = new BlockPos(0, 0, -4);
        Comparator<BlockPos> moving = ScanOrder.alongRoute(eye, south, 0.1, 4.5, RadiusShapeType.SPHERE, 16, axes);
        check(moving.compare(newerRear, edge) < 0, "start a finishable rear target before the unsafe edge");
        Comparator<BlockPos> stopped = ScanOrder.alongRoute(eye, south, 0, 4.5, RadiusShapeType.SPHERE, 16, axes);
        check(stopped.compare(edge, newerRear) < 0, "stopping must let the oldest edge be finished");
        List<BlockPos> noDirection = new ArrayList<>(input);
        noDirection.sort(ScanOrder.alongRoute(eye, Vec3.ZERO, 0, 4.5, RadiusShapeType.SPHERE, 16, axes));
        check(noDirection.equals(sort(input, eye, axes)), "initial stationary mode should use nearest order");
    }

    private static void checkRouteMotion() {
        RouteMotion motion = new RouteMotion();
        Object world = new Object();
        motion.sample(world, Vec3.ZERO, 0);
        motion.sample(world, new Vec3(0, 0, 0.1), 1);
        motion.sample(world, new Vec3(0, 0, 0.2), 2);
        check(motion.direction().z > 0.99, "forward movement should establish direction");
        for (int tick = 3; tick <= 10; tick++) motion.sample(world, new Vec3(0, 0, 0.2), tick);
        check(motion.speed() == 0 && motion.direction().z > 0.99, "pause must keep heading without old speed");
        motion.sample(world, new Vec3(0, 0, 0.15), 11);
        motion.sample(world, new Vec3(0, 0, 0.2), 12);
        check(motion.direction().z > 0.99, "brief backward jitter must not flip heading");
        motion.sample(world, new Vec3(0, 0, 0.1), 13);
        motion.sample(world, new Vec3(0, 0, 0), 14);
        motion.sample(world, new Vec3(0, 0, -0.1), 15);
        check(motion.direction().z < -0.99, "sustained backward movement must flip heading");
        motion.sample(world, new Vec3(100, 0, 100), 16);
        check(motion.direction().lengthSqr() == 0, "teleport must reset heading");
        motion.sample(world, new Vec3(100.3, 0, 100), 17);
        check(motion.direction().x > 0.99, "new movement after teleport must establish direction");
        motion.sample(new Object(), Vec3.ZERO, 18);
        check(motion.direction().lengthSqr() == 0 && motion.speed() == 0, "world change must reset motion");
    }

    private static List<BlockPos> sort(List<BlockPos> input, Vec3 eye, Comparator<BlockPos> axes) {
        List<BlockPos> result = new ArrayList<>(input);
        result.sort(ScanOrder.nearest(eye, axes));
        return result;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
