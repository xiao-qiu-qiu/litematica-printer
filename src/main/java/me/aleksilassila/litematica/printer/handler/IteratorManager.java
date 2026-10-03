package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.LayerRange;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.IterationOrderType;
import me.aleksilassila.litematica.printer.enums.PrintOrderMode;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.ScanOrder;
import me.aleksilassila.litematica.printer.printer.RouteMotion;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 迭代管理器 — 从 Module 中分离出的迭代相关逻辑。
 * 负责：PrinterBox 生命周期、迭代器缓存、形状过滤、范围裁剪。
 */
public class IteratorManager {
    private PrinterBox box;
    private Iterator<BlockPos> cachedIterator;
    private RadiusShapeType shapeType;
    private Vec3 eyePos;
    private double effectiveRange;

    private Vec3 lastEyePos;
    private double lastEffectiveRange = -1;
    private double lastRefreshDistance = -1;
    private int lastLayerMin = Integer.MIN_VALUE;
    private int lastLayerMax = Integer.MIN_VALUE;
    private int lastLayerSingle = Integer.MIN_VALUE;
    private int lastLayerAbove = Integer.MIN_VALUE;
    private int lastLayerBelow = Integer.MIN_VALUE;
    @Nullable
    private Direction.Axis lastLayerAxis = null;
    @Nullable
    private LayerMode lastLayerMode = null;
    @Nullable
    private SelectionType lastSelectionType = null;
    @Nullable
    private PrinterBox lastBox;
    private boolean useSchematicCandidates;
    private PrintOrderMode orderMode = PrintOrderMode.COORDINATES;
    private Vec3 routeDirection = Vec3.ZERO;
    private double routeSpeed;
    private double completionTicks;
    private double sortedCompletionTicks;
    private long routeRevision = -1;

    private boolean needsRebuild;
    private boolean dirtyIterator;

    public IteratorManager() {
        this.needsRebuild = true;
        this.dirtyIterator = true;
    }

    /**
     * 根据玩家位置和配置重建 PrinterBox，返回是否需要重置扫描状态。
     */
    public boolean tryBuildBox(LocalPlayer player, @Nullable Object selectionTypeObj, boolean needSchematic,
                               PrintOrderMode orderMode, @Nullable RouteMotion motion, double completionTicks) {
        Vec3 currentEyePos = player.getEyePosition();
        double effectiveRange = ConfigUtils.getEffectiveRange();
        double refreshDistance = Configs.Core.SCAN_REFRESH_DISTANCE.getDoubleValue();
        RadiusShapeType currentShape = Configs.Core.ITERATOR_SHAPE.getOptionListValue() instanceof RadiusShapeType s ? s : null;

        LayerRange layerRange = DataManager.getRenderLayerRange();
        LayerMode layerMode = layerRange.getLayerMode();
        Direction.Axis layerAxis = layerRange.getAxis();
        int layerMin = layerRange.getLayerMin();
        int layerMax = layerRange.getLayerMax();
        int layerSingle = layerRange.getLayerSingle();
        int layerAbove = layerRange.getLayerAbove();
        int layerBelow = layerRange.getLayerBelow();

        SelectionType selectionType = selectionTypeObj instanceof SelectionType s ? s : null;
        boolean useSchematicCandidates = needSchematic;
        long currentRouteRevision = motion != null ? motion.revision() : -1;

        boolean needRebuild = this.needsRebuild
                || this.box == null
                || !this.box.equals(lastBox)
                || lastEyePos == null
                || lastEyePos.distanceToSqr(currentEyePos) >= refreshDistance * refreshDistance
                || Double.compare(lastEffectiveRange, effectiveRange) != 0
                || Double.compare(lastRefreshDistance, refreshDistance) != 0
                || shapeType != currentShape
                || layerMin != lastLayerMin
                || layerMax != lastLayerMax
                || layerSingle != lastLayerSingle
                || layerAbove != lastLayerAbove
                || layerBelow != lastLayerBelow
                || layerAxis != lastLayerAxis
                || layerMode != lastLayerMode
                || selectionType != lastSelectionType
                || this.orderMode != orderMode
                || (orderMode == PrintOrderMode.ROUTE && (routeRevision != currentRouteRevision
                    || (cachedIterator != null && Math.abs(sortedCompletionTicks - completionTicks) >= 4)))
                || (box != null && (box.iterationMode != Configs.Core.ITERATION_ORDER.getOptionListValue()
                    || box.xIncrement == Configs.Core.X_REVERSE.getBooleanValue()
                    || box.yIncrement == Configs.Core.Y_REVERSE.getBooleanValue()
                    || box.zIncrement == Configs.Core.Z_REVERSE.getBooleanValue()))
                || useSchematicCandidates != this.useSchematicCandidates;

        // 范围筛选始终使用本 tick 的眼睛位置，不依赖扫描盒是否重建。
        this.eyePos = currentEyePos;
        this.effectiveRange = effectiveRange;
        this.shapeType = currentShape;
        routeDirection = motion != null ? motion.direction() : Vec3.ZERO;
        routeSpeed = motion != null ? motion.speed() : 0;
        this.completionTicks = completionTicks;

        if (needRebuild) {
            lastEyePos = currentEyePos;
            lastEffectiveRange = effectiveRange;
            lastRefreshDistance = refreshDistance;
            lastLayerMin = layerMin;
            lastLayerMax = layerMax;
            lastLayerSingle = layerSingle;
            lastLayerAbove = layerAbove;
            lastLayerBelow = layerBelow;
            lastLayerAxis = layerAxis;
            lastLayerMode = layerMode;
            lastSelectionType = selectionType;
            this.useSchematicCandidates = useSchematicCandidates;
            this.orderMode = orderMode;
            routeRevision = currentRouteRevision;

            int minX = (int) Math.floor(player.getX() - effectiveRange);
            int maxX = (int) Math.ceil(player.getX() + effectiveRange);
            int minY = (int) Math.floor(player.getEyeY() - effectiveRange);
            int maxY = (int) Math.ceil(player.getEyeY() + effectiveRange);
            int minZ = (int) Math.floor(player.getZ() - effectiveRange);
            int maxZ = (int) Math.ceil(player.getZ() + effectiveRange);

            // 层范围裁剪应对所有选区模式生效，而非仅限"可见层"模式
            if (layerMode != LayerMode.ALL) {
                switch (layerMode) {
                    case SINGLE_LAYER -> {
                        switch (layerAxis) {
                            case Y -> { minY = layerSingle; maxY = layerSingle; }
                            case X -> { minX = layerSingle; maxX = layerSingle; }
                            case Z -> { minZ = layerSingle; maxZ = layerSingle; }
                        }
                    }
                    case LAYER_RANGE -> {
                        switch (layerAxis) {
                            case Y -> { minY = Math.max(minY, layerMin); maxY = Math.min(maxY, layerMax); }
                            case X -> { minX = Math.max(minX, layerMin); maxX = Math.min(maxX, layerMax); }
                            case Z -> { minZ = Math.max(minZ, layerMin); maxZ = Math.min(maxZ, layerMax); }
                        }
                    }
                    case ALL_BELOW -> {
                        switch (layerAxis) {
                            case Y -> maxY = Math.min(maxY, layerBelow);
                            case X -> maxX = Math.min(maxX, layerBelow);
                            case Z -> maxZ = Math.min(maxZ, layerBelow);
                        }
                    }
                    case ALL_ABOVE -> {
                        switch (layerAxis) {
                            case Y -> minY = Math.max(minY, layerAbove);
                            case X -> minX = Math.max(minX, layerAbove);
                            case Z -> minZ = Math.max(minZ, layerAbove);
                        }
                    }
                }
            }

            if (selectionType != null) {
                if (selectionType == SelectionType.LITEMATICA_SELECTION_BELOW_PLAYER) {
                    maxY = Math.min(maxY, (int) Math.floor(player.getY()));
                } else if (selectionType == SelectionType.LITEMATICA_SELECTION_ABOVE_PLAYER) {
                    minY = Math.max(minY, (int) Math.ceil(player.getY()));
                }
            }

            box = new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
            lastBox = box;

            box.iterationMode = (IterationOrderType) Configs.Core.ITERATION_ORDER.getOptionListValue();
            box.xIncrement = !Configs.Core.X_REVERSE.getBooleanValue();
            box.yIncrement = !Configs.Core.Y_REVERSE.getBooleanValue();
            box.zIncrement = !Configs.Core.Z_REVERSE.getBooleanValue();

            cachedIterator = null;
            dirtyIterator = true;

            this.needsRebuild = false;
            return true;
        }

        this.needsRebuild = false;
        return false;
    }

    public void markNeedsRebuild() {
        this.needsRebuild = true;
    }

    public boolean isNeedsRebuild() {
        return needsRebuild;
    }

    public boolean isDirtyIterator() {
        return dirtyIterator;
    }

    /**
     * 获取下一个需要迭代的位置（已过滤形状和可达性）。
     * 返回 null 表示迭代结束。
     */
    @Nullable
    public BlockPos next() {
        if (box == null) return null;

        if (cachedIterator == null) {
            cachedIterator = createIterator();
            dirtyIterator = false;
        }

        while (cachedIterator.hasNext()) {
            BlockPos pos = cachedIterator.next();
            if (pos == null) continue;

            if (shapeType != null) {
                if (!PlayerUtils.canInteracted(pos, eyePos, effectiveRange, shapeType)) continue;
            } else if (!PlayerUtils.canInteracted(pos)) continue;

            return pos;
        }

        cachedIterator = null;
        return null;
    }

    public boolean hasNext() {
        if (box == null) return false;
        if (cachedIterator == null) {
            cachedIterator = createIterator();
            dirtyIterator = false;
        }
        return cachedIterator.hasNext();
    }

    private Iterator<BlockPos> createIterator() {
        Iterator<BlockPos> candidates = createCoordinateIterator();
        if (orderMode == PrintOrderMode.COORDINATES) return candidates;
        // 只对当前投影和可达范围内的位置排序，不在排序阶段读取方块或触发取物。
        List<BlockPos> positions = new ArrayList<>();
        while (candidates.hasNext()) {
            BlockPos pos = candidates.next();
            if (shapeType != null ? PlayerUtils.canInteracted(pos, eyePos, effectiveRange, shapeType)
                    : PlayerUtils.canInteracted(pos)) positions.add(pos);
        }
        Comparator<BlockPos> coordinates = ScanOrder.coordinates(box.iterationMode,
                box.xIncrement, box.yIncrement, box.zIncrement);
        positions.sort(orderMode == PrintOrderMode.ROUTE
                ? ScanOrder.alongRoute(eyePos, routeDirection, routeSpeed, effectiveRange,
                    shapeType != null ? shapeType : RadiusShapeType.SPHERE, completionTicks, coordinates)
                : ScanOrder.nearest(eyePos, coordinates));
        sortedCompletionTicks = completionTicks;
        return positions.iterator();
    }

    private Iterator<BlockPos> createCoordinateIterator() {
        if (!useSchematicCandidates) {
            return box.iterator();
        }

        List<PrinterBox> schematicBoxes = LitematicaUtils.getSchematicBoxes(box);
        if (schematicBoxes.isEmpty()) {
            return List.<BlockPos>of().iterator();
        }
        if (schematicBoxes.size() == 1) {
            return schematicBoxes.get(0).iterator();
        }
        return new SchematicBoxIterator(schematicBoxes);
    }

    public void reset() {
        cachedIterator = null;
        dirtyIterator = true;
    }

    @Nullable
    public PrinterBox getBox() {
        return box;
    }

    public boolean hasBox() {
        return box != null;
    }

    /**
     * 使用脏区域迭代器替换当前迭代器（用于 PARTIAL 模式）。
     */
    public void setDirtyRegionIterator(Iterator<BlockPos> dirtyIter) {
        this.cachedIterator = dirtyIter;
        this.dirtyIterator = false;
    }

    /**
     * Iterates the projection boxes while removing duplicates from overlapping placements.
     */
    private static final class SchematicBoxIterator implements Iterator<BlockPos> {
        private final PriorityQueue<Cursor> queue;
        private final Set<Long> seen = new HashSet<>();
        private BlockPos next;

        private SchematicBoxIterator(List<PrinterBox> boxes) {
            PrinterBox order = boxes.get(0);
            Comparator<BlockPos> comparator = ScanOrder.coordinates(order.iterationMode,
                    order.xIncrement, order.yIncrement, order.zIncrement);
            this.queue = new PriorityQueue<>((left, right) -> comparator.compare(left.current, right.current));
            for (PrinterBox box : boxes) {
                Iterator<BlockPos> iterator = box.iterator();
                if (iterator.hasNext()) {
                    queue.add(new Cursor(iterator, iterator.next()));
                }
            }
        }

        @Override
        public boolean hasNext() {
            prepareNext();
            return next != null;
        }

        @Override
        public BlockPos next() {
            prepareNext();
            if (next == null) throw new java.util.NoSuchElementException();
            BlockPos result = next;
            next = null;
            return result;
        }

        private void prepareNext() {
            if (next != null) return;
            while (!queue.isEmpty()) {
                Cursor cursor = queue.poll();
                BlockPos candidate = cursor.current;
                if (cursor.iterator.hasNext()) {
                    cursor.current = cursor.iterator.next();
                    queue.add(cursor);
                }
                if (seen.add(candidate.asLong())) {
                    next = candidate;
                    return;
                }
            }
        }

        private static final class Cursor {
            private final Iterator<BlockPos> iterator;
            private BlockPos current;

            private Cursor(Iterator<BlockPos> iterator, BlockPos current) {
                this.iterator = iterator;
                this.current = current;
            }
        }
    }
}
