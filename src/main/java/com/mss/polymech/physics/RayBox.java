package com.mss.polymech.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 射线 ↔ 轴对齐盒（AABB）的求交 —— <b>纯函数、零 Minecraft 依赖</b>，所以能离线钉
 * （{@code native/jni-smoketest/SpaceBuildRulesProbe.java} 第 4 节）。
 *
 * <h2>它服务什么</h2>
 * 牵引枪的<b>视觉</b>：客户端要知道"准星现在指着哪个物理体、指在多远"，
 * 才能画 GMod/机械动力那种「高亮 + 枪口射线 + 抓住后的光束与落点环」。
 *
 * <p><b>为什么自己在客户端算，而不是用 {@code PhysicalRaycast}</b>：那个走的是 Rapier 射线，
 * 依赖客户端镜子体上有没有碰撞体；而客户端手里已经有每个体的<b>方块列表 + 位姿</b>
 * （{@code PhysicsBodySyncPacket} 同步来的），拿这份数据做解析求交更稳、也不占用物理线程。
 * 服务端那侧仍然是权威（真正抓谁由服务端射线决定），这里只负责"看起来对"。</p>
 *
 * <h2>为什么用"体局部空间"求交</h2>
 * 体可能被转过。把射线变换到体局部（{@code R⁻¹·(原 − 体位置)}、方向同乘 {@code R⁻¹}），
 * 盒就是轴对齐的普通 AABB，判定退化成教科书的 slab 法；
 * 直接在世界空间做旋转盒求交则会写出一堆符号错误（本项目在姿态帧上已经栽过同类）。
 * 因为旋转是正交变换，参量 {@code t} 在换算前后一致（方向保持单位长度）⇒ 世界命中点 = 原点 + 方向·t。
 */
public final class RayBox {

    private RayBox() {
    }

    /**
     * 把世界点/方向变换到"体局部"空间。
     *
     * @param bodyPos      体的世界位置
     * @param bodyRotation 体的世界旋转（会被复制）
     * @param x/y/z        世界点
     */
    public static Vector3d toLocalPoint(Vector3d bodyPos, Quaterniond bodyRotation,
                                        double x, double y, double z) {
        return new Quaterniond(bodyRotation).invert()
                .transform(new Vector3d(x, y, z).sub(bodyPos));
    }

    /** 把世界方向变换到体局部空间（不改变长度）。 */
    public static Vector3d toLocalDirection(Quaterniond bodyRotation, double dx, double dy, double dz) {
        return new Quaterniond(bodyRotation).invert().transform(new Vector3d(dx, dy, dz));
    }

    /**
     * 射线与 AABB 求交（slab 法）。
     *
     * @param origin 射线起点（局部空间）
     * @param dir    单位方向（局部空间）
     * @param min    盒最小角
     * @param max    盒最大角
     * @return 命中处的参量 {@code t}（= 沿 dir 的距离，单位与坐标同）；未命中返回 {@link Double#NaN}。
     *         起<b>点在盒内</b>时返回 0（视作"贴着"）。
     */
    public static double intersect(Vector3d origin, Vector3d dir, Vector3d min, Vector3d max) {
        double tMin = 0.0;
        double tMax = Double.MAX_VALUE;
        double[] o = {origin.x, origin.y, origin.z};
        double[] d = {dir.x, dir.y, dir.z};
        double[] lo = {min.x, min.y, min.z};
        double[] hi = {max.x, max.y, max.z};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0e-12) {
                // 平行于这一对平面：起点必须已经在板内，否则永远打不到
                if (o[i] < lo[i] || o[i] > hi[i]) {
                    return Double.NaN;
                }
                continue;
            }
            double inv = 1.0 / d[i];
            double t1 = (lo[i] - o[i]) * inv;
            double t2 = (hi[i] - o[i]) * inv;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) {
                tMin = t1;
            }
            if (t2 < tMax) {
                tMax = t2;
            }
            if (tMin > tMax) {
                return Double.NaN;
            }
        }
        return tMin;
    }

    /**
     * 方块集合的局部 AABB —— <b>块占 {@code [x, x+1)}（最小角约定）</b>，再按 {@code inflate} 外扩一点，
     * 便于瞄准/高亮。
     *
     * <p>⚠️ 2026-09-29 修：这里原来写的是"每格 ±0.5"（把整数当<b>块中心</b>），<b>差了半格</b>。
     * 后果不是"差一点点"，而是<b>整个拾取盒平移半格、并且比真实体大一圈</b>：
     * 客户端的准星判定与悬停框都画在错的位置上，玩家于是照着错的框去瞄，
     * 而服务端 {@code ShipRaycast} 走的是真实方块形状 ⇒ 症状是"客户端命中、服务端两发全空"、
     * 右键只说"准星没指到物理体"，以及光束的抓点落在体旁边。</p>
     *
     * <p>「块占 {@code [x, x+1)}」这个约定由<b>三处互相独立的</b>代码钉死（都不是从本类推导的）：</p>
     * <ul>
     *   <li>{@code PhysicsBodyTracker.localToWorld}：{@code new Vector3d(dx + 0.5, dy + 0.5, dz + 0.5)}
     *       —— 块中心在 {@code dx+0.5}（服务端物理用的世界坐标）；</li>
     *   <li>{@code PhysicsBodyRenderer}：{@code pose.translate(entry.dx(), dy(), dz())} 之后画原版方块模型
     *       （模型本身占 {@code [0,1]}）⇒ 块占 {@code [dx, dx+1)}；</li>
     *   <li>{@code PhysicsBodyInteractionClient}：{@code tmp.set(hit.dx + 0.5F, ...)} 求块中心。</li>
     * </ul>
     * <p><b>教训</b>：{@code SpaceBuildRulesProbe} 第 4 节当时把期望写成 {@code x∈[-0.5, 2.5]} ——
     * 那是从<b>本类的实现</b>推出来的，于是"判据通过"只证明了实现自洽，证明不了约定正确。
     * 期望必须来自独立证据；这一轮已按上面的证据改正。</p>
     */
    public static Vector3d[] blockBounds(int[] xs, int[] ys, int[] zs, double inflate) {
        if (xs.length == 0) {
            return new Vector3d[]{new Vector3d(), new Vector3d()};
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]);
            minY = Math.min(minY, ys[i]);
            minZ = Math.min(minZ, zs[i]);
            maxX = Math.max(maxX, xs[i] + 1.0);
            maxY = Math.max(maxY, ys[i] + 1.0);
            maxZ = Math.max(maxZ, zs[i] + 1.0);
        }
        return new Vector3d[]{
                new Vector3d(minX - inflate, minY - inflate, minZ - inflate),
                new Vector3d(maxX + inflate, maxY + inflate, maxZ + inflate)};
    }
}
