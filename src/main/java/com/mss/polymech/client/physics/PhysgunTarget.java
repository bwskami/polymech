package com.mss.polymech.client.physics;

import com.mss.polymech.physics.RayBox;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 客户端"准星指着哪个物理体、指在多远" —— 用**同步来的方块 + 位姿**做解析求交。
 *
 * <h2>为什么不用服务端那套 {@code PhysicalRaycast}</h2>
 * 那个走 Rapier 射线，依赖客户端镜子体上有没有碰撞体（不保证）；
 * 而客户端手里已经有每个体的方块列表与位姿（{@code PhysicsBodySyncPacket}），
 * 用 {@link RayBox} 在<b>体局部空间</b>做 slab 求交更稳、也不碰物理线程。
 * 服务端那侧仍是权威（真正抓谁由服务端射线决定），这里只服务视觉与"抓住瞬间的距离"。
 *
 * <h2>命中判据</h2>
 * 对每个体：把射线变换到体局部 ⇒ 与该体方块集合的局部 AABB 求交 ⇒ 取最近的 {@code t}。
 * AABB 外扩 {@link #INFLATE} 格，让"边缘擦过"也能被高亮（GMod 里贴边也能抓）。
 * 只在一个体<b>整体包围盒</b>级别求交（不做逐格）：高亮与瞄准够用，且每帧开销与体数成正比。
 */
public final class PhysgunTarget {

    /** 包围盒外扩（格）：让擦边也能瞄上。 */
    public static final double INFLATE = 0.15;

    private PhysgunTarget() {
    }

    /**
     * 命中结果：体 id、距离（格，沿视线）、以及命中点在<b>体局部</b>空间的坐标。
     *
     * <p>局部命中点是给"准星指着哪一格"用的（渲染器据此画棋盘悬停面）——
     * 体的方块局部坐标是"块占 {@code [x, x+1)}"（最小角约定，证据见 {@code RayBox.blockBounds}），
     * 所以局部点逐轴 {@code floor} 就是那一格。</p>
     */
    public record Hit(long bodyId, double distance, double localX, double localY, double localZ) {
    }

    /** 从本地玩家眼睛沿视线在 {@code reach} 格内找最近的物理体；没打中返回 null。 */
    public static Hit find(double reach) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || ClientPhysicsWorld.size() == 0) {
            return null;
        }
        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);
        Vec3 eye = mc.player.getEyePosition(partialTick);
        Vec3 look = mc.player.getViewVector(partialTick);
        return find(eye, look, reach);
    }

    /** 给定眼睛与视线求交（渲染器与物品共用同一条路径）。 */
    public static Hit find(Vec3 eye, Vec3 look, double reach) {
        Hit best = null;
        double bestT = reach;
        for (ClientPhysicsWorld.ClientBody body : ClientPhysicsWorld.bodies()) {
            double[] livePos = new double[3];
            float[] liveRot = new float[4];
            boolean hasLive = ClientPhysics.liveTransform(body.id(), livePos, liveRot);
            double bx, by, bz;
            Quaterniond rot = new Quaterniond();
            if (hasLive) {
                bx = livePos[0];
                by = livePos[1];
                bz = livePos[2];
                rot.set(liveRot[0], liveRot[1], liveRot[2], liveRot[3]);
            } else {
                bx = body.renderX(0.0F);
                by = body.renderY(0.0F);
                bz = body.renderZ(0.0F);
                float[] r = new float[4];
                body.renderRotation(0.0F, r);
                rot.set(r[0], r[1], r[2], r[3]);
            }
            Vector3d[] bounds = boundsOf(body);
            if (bounds == null) {
                continue;
            }
            Vector3d localOrigin = RayBox.toLocalPoint(new Vector3d(bx, by, bz), rot, eye.x, eye.y, eye.z);
            Vector3d localDir = RayBox.toLocalDirection(rot, look.x, look.y, look.z);
            double t = RayBox.intersect(localOrigin, localDir, bounds[0], bounds[1]);
            if (!Double.isNaN(t) && t <= bestT) {
                bestT = t;
                // 命中点（体局部）：渲染器要用它定位"准星指着的那一格"
                Vector3d localHit = new Vector3d(localDir).mul(t).add(localOrigin);
                best = new Hit(body.id(), t, localHit.x, localHit.y, localHit.z);
            }
        }
        return best;
    }

    /** 体方块集合的局部 AABB（外扩 {@link #INFLATE}）；空体返回 null。 */
    public static Vector3d[] boundsOf(ClientPhysicsWorld.ClientBody body) {
        var blocks = body.blocks();
        if (blocks.isEmpty()) {
            return null;
        }
        int n = blocks.size();
        int[] xs = new int[n];
        int[] ys = new int[n];
        int[] zs = new int[n];
        for (int i = 0; i < n; i++) {
            var e = blocks.get(i);
            xs[i] = e.dx();
            ys[i] = e.dy();
            zs[i] = e.dz();
        }
        return RayBox.blockBounds(xs, ys, zs, INFLATE);
    }
}
