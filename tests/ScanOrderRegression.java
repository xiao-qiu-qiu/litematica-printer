import me.aleksilassila.litematica.printer.enums.IterationOrderType;
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
        System.out.println("PASS: coordinate rows/layers, mirrored nearest order and stable ties");
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
