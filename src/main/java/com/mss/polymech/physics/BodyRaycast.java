package com.mss.polymech.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;

/**
 * 服务端"准星指着哪个物理体" —— <b>纯函数、只依赖 joml</b>，所以能离线钉
 * （{@code native/jni-smoketest/BodyRaycastProbe.java}）。
 *
 * <h2>为什么要有它（2026-09-29 的"抓不住"根因）</h2>
 * 牵引枪原来把服务端求交交给 MPS 层的 {@code PhysicalRaycast}→{@code ShipRaycast}，
 * 而玩家**看得见、客户端也在拾取**的体在 {@code PhysicsBodyTracker}（太空放方块造的就是它）。
 * 两边查的不是一张表，于是出现"客户端解析求交命中、服务端两发全空"的怪象 ——
 * 实机表现就是用户报的<b>"点一下右键只有一瞬间的射线，无法长按"</b>：
 * 服务端判定失败 ⇒ 发 {@code released} ⇒ 客户端那条本地预测的光束立刻被收回。
 *
 * <p>更深一层的证据：那批 MPS 体的位姿是 <b>NaN</b>（我加的诊断打出"最近
 * 1.7976931348623157E308 格"= {@code Double.MAX_VALUE}，说明每个体的距离都是 NaN），
 * 而 {@code ShipRaycast.pose()} 里恰好有 {@code position.isFinite()} 检查 ⇒ 它把所有体都跳过。
 * 所以这里<b>用与客户端完全相同的那套算法</b>（{@code RayBox}：把射线变到体局部做 slab 求交），
 * 数据源换成 tracker —— 两边从此是"同一张表 + 同一套数学"，不再有判据分裂。</p>
 *
 * <h2>为什么不用原生 Rapier 射线</h2>
 * tracker 的体确实有原生体素碰撞体，但"打物理体"要拿到<b>局部格坐标</b>（抓点、放置面都靠它），
 * 而原生射线只给到世界命中点与 collider 句柄；并且客户端手上只有"方块清单 + 位姿"，
 * 拿它做解析求交才能让两端算法一致（客户端 {@code PhysgunTarget} 就是这么做的）。
 */
public final class BodyRaycast {

    /** 包围盒外扩（格）：与客户端 {@code PhysgunTarget.INFLATE} 保持一致，让"擦边"也能抓。 */
    public static final double INFLATE = 0.15;

    private BodyRaycast() {
    }

    /**
     * 一个候选体：id + 世界位姿 + 方块局部格坐标（块占 {@code [x, x+1)}，见 {@link RayBox#blockBounds}）。
     */
    public record Body(long id, double px, double py, double pz,
                       double qx, double qy, double qz, double qw,
                       int[] xs, int[] ys, int[] zs) {
    }

    /** 命中：体 id + 沿视线的距离（格）+ 命中点（体局部）。 */
    public record Hit(long id, double distance, double localX, double localY, double localZ) {
    }

    /**
     * 取最近命中的体。
     *
     * <p>位姿非法（NaN / 零四元数）或没有方块的体<b>直接跳过</b> —— 这正是用来防住
     * "MPS 那批 NaN 体把整个求交废掉"那类故障的（探针里有专门的回归项）。</p>
     */
    public static Hit nearest(List<Body> bodies, double ox, double oy, double oz,
                              double dx, double dy, double dz, double maxDistance) {
        if (bodies == null || bodies.isEmpty() || !(maxDistance > 0.0)) {
            return null;
        }
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > 1.0e-9) || !Double.isFinite(len)) {
            return null;
        }
        double nx = dx / len;
        double ny = dy / len;
        double nz = dz / len;

        Hit best = null;
        double bestT = maxDistance;
        for (Body body : bodies) {
            if (body.xs() == null || body.xs().length == 0 || !(body.px() == body.px())) {
                continue;
            }
            Quaterniond rot = new Quaterniond(body.qx(), body.qy(), body.qz(), body.qw());
            if (!rot.isFinite() || rot.lengthSquared() < 1.0e-12) {
                continue;
            }
            rot.normalize();
            Vector3d pos = new Vector3d(body.px(), body.py(), body.pz());
            if (!pos.isFinite()) {
                continue;
            }
            Vector3d[] bounds = RayBox.blockBounds(body.xs(), body.ys(), body.zs(), INFLATE);
            Vector3d localOrigin = RayBox.toLocalPoint(pos, rot, ox, oy, oz);
            Vector3d localDir = RayBox.toLocalDirection(rot, nx, ny, nz);
            double t = RayBox.intersect(localOrigin, localDir, bounds[0], bounds[1]);
            if (Double.isNaN(t) || t > bestT) {
                continue;
            }
            bestT = t;
            Vector3d localHit = new Vector3d(localDir).mul(t).add(localOrigin);
            best = new Hit(body.id(), t, localHit.x, localHit.y, localHit.z);
        }
        return best;
    }
}
