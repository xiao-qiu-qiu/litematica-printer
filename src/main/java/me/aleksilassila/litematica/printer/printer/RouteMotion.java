package me.aleksilassila.litematica.printer.printer;

import net.minecraft.world.phys.Vec3;

/** 每 tick 采样实际水平位移；不依赖视角，也不依赖扫描是否正在等破冰。 */
public final class RouteMotion {
    private Object world;
    private Vec3 previous;
    private Vec3 anchor;
    private Vec3 direction = Vec3.ZERO;
    private Vec3 reportedDirection = Vec3.ZERO;
    private long lastTick;
    private long lastMoved;
    private long revision;
    private double speed;

    public void sample(Object world, Vec3 position, long tick) {
        if (world == null || position == null) {
            reset();
            return;
        }
        if (this.world != world || previous == null || tick - lastTick > 20 || tick < lastTick
                || position.distanceToSqr(previous) > 16) {
            reset();
            this.world = world;
            previous = anchor = position;
            lastTick = lastMoved = tick;
            return;
        }
        if (tick == lastTick) return;
        double dx = position.x - previous.x;
        double dz = position.z - previous.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        long elapsed = tick - lastTick;
        previous = position;
        lastTick = tick;
        if (distance < 0.0001) {
            if (tick - lastMoved >= 6 && speed != 0) {
                speed = 0;
                anchor = position;
                revision++;
            }
            return; // 停步保留方向；恢复时不把转头当成转弯。
        }
        if (speed == 0) revision++;
        double measured = distance / elapsed;
        speed = speed == 0 ? measured : speed * 0.7 + measured * 0.3;
        lastMoved = tick;

        Vec3 displacement = new Vec3(position.x - anchor.x, 0, position.z - anchor.z);
        double length = displacement.length();
        if (length < 0.12) return;
        Vec3 candidate = displacement.scale(1 / length);
        // 明显掉头/转弯需要持续一小段位移，过滤碰撞和短促反向抖动。
        if (direction.lengthSqr() > 0 && candidate.dot(direction) < 0.8 && length < 0.25) return;
        if (reportedDirection.lengthSqr() == 0 || candidate.dot(reportedDirection) < 0.985) {
            revision++;
            reportedDirection = candidate;
        }
        direction = candidate;
        anchor = position;
    }

    public void reset() {
        world = null;
        previous = anchor = null;
        direction = Vec3.ZERO;
        reportedDirection = Vec3.ZERO;
        speed = 0;
        revision++;
    }

    public Vec3 direction() { return direction; }
    public double speed() { return speed; }
    public long revision() { return revision; }
}
