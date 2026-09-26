package com.mss.polymech.space;

/**
 * 地球行星维度 ↔ 真实太空坐标的球面映射。
 *
 * <p><b>⚠️ 这是"地球专用"的历史实现，不是唯一来源 —— 合并进
 * {@code mps.kelvin.physical.celestial_world.CelestialWorld} 是待办（见下）。</b></p>
 *
 * <p>它等价于 space mod 的 {@code CelestialWorld#getSpacePosFromWorldPos / getWorldPosFromSpacePos}，
 * 但把参数**硬编码成地球的**（无自转、中心 0,0、经度长度 100000、高度 10000、MinY −64）。
 * 而 {@code CelestialWorld} 那套是<b>按天体数据驱动</b>的（每颗行星各自的
 * {@code pos_shadow_*} / {@code Height} / {@code MinY}，由 {@code space_data} 里的
 * {@code world} JSON 喂入），因此火星/金星等**只有它能算对**。</p>
 *
 * <h2>现状：同一个概念在活代码里有两套实现</h2>
 * <ul>
 *   <li>本类 —— 被 {@code SpaceTransitionHandler}（地表⇄太空传送）使用；</li>
 *   <li>{@code CelestialWorld} —— 被地表天空渲染、头盔 HUD、{@code PhysicalBodySpaceEvent} 使用。</li>
 * </ul>
 *
 * <h2>合并怎么做（待办，别盲改）</h2>
 * 让本类<b>转发</b>到 {@code ClientCelestialWorld.getCelestialWorld()}（拿到当前行星的参数），
 * 取不到再回落到下面这些地球常量；然后 {@code SpaceTransitionHandler} 就不再有地球特例。
 * <p><b>为什么不现在就改</b>：本类是<b>传送路径</b>的一环，改错了人会落到空处/掉出世界，
 * 必须实机验证（"从地球传上去再传回来，落点与改造前逐位一致"）——不是可以顺手改的地方。</p>
 */
public final class EarthSpaceMapping {

    private static final double CENTER_X = 0.0;
    private static final double CENTER_Z = 0.0;
    private static final double LONGITUDE_LENGTH = 100000.0;
    private static final double HEIGHT = 10000.0;
    private static final double MIN_Y = -64.0;

    /**
     * 升空**到达距离**倍率（离行星中心 = 倍率 × 半径）——与
     * {@code PlanetDimensions.teleportToSpaceAbove} 的 2.2×半径 同口径。
     *
     * <p>调它的判据只有一条：进太空后**帧率**。若 2.2 之下仍然因为行星 shader 覆盖面积大而掉帧，
     * 就调大（5×半径 ≈ 24° 视直径）；调到"看不见行星"就没意义了。**先实测再调**，
     * 别凭感觉改（这是渲染开销与观感的直接权衡）。</p>
     */
    private static final double ARRIVAL_RADIUS_FACTOR = 2.2;

    private EarthSpaceMapping() {
    }

    /** 主世界坐标 → 真实太空坐标。 */
    public static double[] worldToSpace(double x, double y, double z, double seconds) {
        // ★ 2026-09-22：与 CelestialWorld.getSpacePosFromWorldPos 同步（§31.15）——
        //   **天空必须与 MC 的东西南北对应**：经度 ← 世界 +X(东)、纬度 ← −世界 +Z(南)，
        //   并去掉原来的 x=-x 镜像。两条映射必须同改，否则"飞上太空的落点"与"天空朝向"差 90°。
        double dx = x - CENTER_X;
        double dz = z - CENTER_Z;
        // 经度取负：物理东 = 极轴 × 上方向 在 lon=0 处指向 −Z ⇒ 经度增大方向是"西"（见 §31.15）。
        double longitude = -dx / LONGITUDE_LENGTH * (Math.PI / 2);
        double latitude = -dz / LONGITUDE_LENGTH * (Math.PI / 2);
        double heightRatio = (y - MIN_Y) / (HEIGHT - MIN_Y);
        double surfaceRadius = RealAstroData.EARTH.radiusMeters()
                + heightRatio * RealAstroData.EARTH.carmenLineHeightMeters();
        // ★ 升空**到达距离**：离行星中心 2.2×半径（与 PlanetDimensions.teleportToSpaceAbove 同口径）。
        //   为什么不能就到"地表上方一点点"：太空维度的职责是"在行星之间飞"，**贴着行星看它**
        //   属于地表维度。2026-09 实机踩过：修好落点后玩家被正确放到地球上方 ~100 km，
        //   于是地球本体 + 大气壳 + 云 + 光环整套 shader **铺满半个屏幕**，
        //   渲染线程 84% 一个核满转、帧极慢 —— 表现就是"卡死"（转储证明：服务端空闲、无我们代码帧）。
        //   2.2×半径 ≈ 54° 视直径（地球：14,016 km），既看得见行星，也不再让它占满屏。
        double radius = Math.max(surfaceRadius, ARRIVAL_RADIUS_FACTOR * RealAstroData.EARTH.radiusMeters());
        double cosLat = Math.cos(latitude);
        double localX = Math.cos(latitude) * Math.cos(longitude) * radius;
        double localY = Math.sin(latitude) * radius;
        double localZ = Math.cos(latitude) * Math.sin(longitude) * radius;
        // ⚠️ 这里必须用**权威位置**（kelvin 积分后的），不能用 `realPositionAt` ——
        //    后者是**静态 J2000 快照**（还忽略 seconds 参数）。用静态快照会出现最典型的"分脑"：
        //    传送把你放到地球的**旧位置**，而渲染/天空/物理用的是积分后的位置。
        //    2026-09 实机证据：目标 X=1.5306e10 而地球 gamePos X=1.6694e10（差 1.387e9 米 ≈ 140 万公里，
        //    对应该存档里已经积分掉的 ≈12.8 小时模拟时间，与用户此前开过的时间倍率吻合），
        //    表现就是"地球升入太空发现不在地球旁边"。
        //    kelvin 未就绪时 SpaceWorld.blockPos 自己会退回静态值，所以这条改动不会让落点崩掉。
        double[] earth = SpaceWorld.blockPos(RealAstroData.EARTH);
        return new double[]{localX + earth[0], localY + earth[1], localZ + earth[2]};
    }

    /** 真实太空坐标 → 主世界坐标。 */
    public static double[] spaceToWorld(double sx, double sy, double sz, double seconds) {
        // 与 worldToSpace 同理：逆变换必须用**同一个**权威位置，否则一来一回就漂 1.4e9 米。
        double[] earth = SpaceWorld.blockPos(RealAstroData.EARTH);
        double relX = sx - earth[0];
        double relY = sy - earth[1];
        double relZ = sz - earth[2];
        double r = Math.sqrt(relX * relX + relY * relY + relZ * relZ);
        if (r < 1.0) {
            return new double[]{0.0, 100.0, 0.0};
        }
        double latitude = Math.asin(relY / r);
        double longitude = Math.atan2(relZ, relX);
        // 与 worldToSpace 同一约定：世界 X ← −经度、世界 Z ← −纬度。
        double x0 = -longitude / (Math.PI / 2) * LONGITUDE_LENGTH;
        double z0 = -latitude / (Math.PI / 2) * LONGITUDE_LENGTH;
        double worldX = x0 + CENTER_X;
        double worldZ = z0 + CENTER_Z;
        double worldY = MIN_Y
                + (r - RealAstroData.EARTH.radiusMeters())
                / RealAstroData.EARTH.carmenLineHeightMeters() * HEIGHT;
        return new double[]{worldX, worldY, worldZ};
    }
}
