package com.mss.polymech.space;

import com.mss.polymech.Polymech;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import com.mss.polymech.mps.kelvin.physical.space_world.ClientSpaceWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.ServerSpaceWorld;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;

/**
 * 太空维度 MC 坐标与真实太阳系坐标的换算（大坐标系路线）。
 * <p>
 * 参考 space mod 新版的做法：太空维度里拆除原版世界边界、
 * 深空位置不进入区块系统之后，行星按<b>真实缩放坐标</b>（{@link #ZOOM}，
 * 1 MC 格 = 10000 米）直接放在同一个 space 维度里，不再做径向压缩 ——
 * 海王星最远约 4.4×10<sup>8</sup> 格，远小于双精度坐标的实用极限
 * （2<sup>53</sup> ≈ 9×10<sup>15</sup>）。
 * </p>
 * <p>
 * 唯一保留的妥协：非地球天体的 Y 压平到黄道面（0）。世界高度只有 ±2×10<sup>3</sup> 格，
 * 而真实 Y 跨度达 ±1.6×10<sup>6</sup> 格；Y 只影响观测位姿，着陆走影子维度传送，
 * 不影响轨道几何。渲染 / 遮挡 / 传送 / HUD 必须统一使用本映射，保证刚体一致。
 * </p>
 *
 * <h2>位置权威：静态数据（默认）还是 kelvin 积分结果</h2>
 * {@link #gamePos} 是本项目<b>唯一</b>的"天体游戏坐标"入口 ——
 * 渲染对象、光照/阴影投射、GUI 星图、HUD、传送、命令全部经它取值。
 * 迁移到 kelvin 的权威轨道时，只要改这一个方法，<b>所有消费方同时切换</b>，
 * 不会出现"本体不动、光照动了"这类分脑（那正是"只改一部分消费方"的典型症状）。
 *
 * <p>{@link #setKelvinAuthority} 打开后，X/Z 改读 kelvin 的积分位置，
 * <b>Y 仍按原规则压平</b>（见下），因为地表维度只有 ±2×10<sup>3</sup> 格高，
 * 直接用真实 Y 会把天体扔出世界高度之外。kelvin 未就绪（尚未收到同步、
 * 或本维度没有太空世界）时逐体回退到静态值，所以开关打开也不会瞬间失去画面。</p>
 */
public final class SpaceWorld {

    /**
     * <b>0.0.x 时代的遗留常量，不是当前约定</b>（订正见 {@code docs/mps-clone-plan.md} §31.6）：
     * space <b>0.0.6</b> 的 {@code SpaceModDataPackManger:77,84} 会读 {@code position_zoom}
     * （默认 10000），我们的世界/存档侧曾按它做 ÷×（引进于 {@code 95f586d 宇宙1}）；
     * 但<b>当前参考 0.1.x 已弃用该字段</b> —— 0.1.0 的 jar 里没有任何类命中
     * {@code position_zoom}，太空维度就是恒等（1 格 = 1 米）。
     * 本常量<b>不再是承重约定</b>，只留给 {@link com.mss.polymech.space.SpaceScaleMigration}
     * 迁移那段时期的老存档用。
     */
    public static final double ZOOM = 10000.0;

    /**
     * 深空守卫：|x|/|z| 超过此值的位置不再进入区块系统
     * （原版 BlockPos.asLong 只有 26 位：±33,554,431）。
     * 守卫范围以内仍是真实（flat 空生成器）区块，供地球/火星近旁活动。
     */
    public static final int DEEP_SPACE_LIMIT = 33_554_431;

    /** 太空维度专用边界已移除：{@code space.MixinWorldBorder} 把边界整套放开。 */

    /** kelvin 空间世界的 id —— 与 {@code space_data/space/type.json} 的目录名一致。 */
    private static final ResourceLocation KELVIN_SPACE_ID =
            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "space");

    /** 是否让 kelvin 成为位置权威。由配置在加载/重载时喂入（见 {@code Polymech}）。 */
    private static volatile boolean kelvinAuthority = false;

    private SpaceWorld() {
    }

    /** 由配置事件调用；{@code false} 时行为与迁移前<b>逐位相同</b>。 */
    public static void setKelvinAuthority(boolean value) {
        kelvinAuthority = value;
    }

    public static boolean kelvinAuthority() {
        return kelvinAuthority;
    }

    public static ResourceKey<Level> dimension() {
        return PlanetDimensions.SPACE;
    }

    /** 该 Level 是否为太空维度（ResourceKey 是注册表内化的，引用比较即可）。 */
    public static boolean isSpace(Level level) {
        return level != null && level.dimension() == PlanetDimensions.SPACE;
    }

    /** 是否为"深空"：方块访问直接按真空处理，不进入区块系统。 */
    public static boolean isDeepSpace(double x, double z) {
        return Math.abs(x) > DEEP_SPACE_LIMIT || Math.abs(z) > DEEP_SPACE_LIMIT;
    }

    public static double j2000Seconds() {
        return (System.currentTimeMillis() - 946_728_000_000.0) / 1000.0;
    }

    public static double toMc(double real) {
        return identityMode ? real : real / ZOOM;
    }

    public static double toReal(double mc) {
        return identityMode ? mc : mc * ZOOM;
    }

    // ==================== 坐标约定的回退开关（方案 B / S4 的闸门） ====================

    /**
     * 坐标约定开关：{@code false} = 历史约定（**1 格 = {@link #ZOOM} 米** + Y 压平）；
     * {@code true} = space 的恒等约定（**1 格 = 1 米**）。
     *
     * <p><b>为什么要做成运行时开关而不是直接改常量</b>：目标要求"每步可回退、可 A/B"，
     * 而换约定会让**所有以格为单位的数值**整体变 10⁴ 倍（§30.5 的清单）。有一个开关，
     * 就能"同一份代码、两种模式"逐项对照，出问题一键切回 —— 这比改回去再编译一遍可靠得多。</p>
     *
     * <p><b>2026-09 轮 10：已按用户选择（方案 b）翻为 true</b> —— 太空维度方块坐标 = 宇宙坐标
     * （1 格 = 1 米）。可达性随之改由**维度跃迁**承担（传送路径两侧都走本咽喉，
     * 所以目标自动变成天体的真实坐标，无需手改传送）。要回退：把下面这个初值改回 false 重新编译，
     * 或运行时调 {@link #setIdentityMode(boolean)}。</p>
     *
     * <p><b>注意它只管"缩放"这一半</b>：方块口径的 Y 仍然压平（§30.1 的 12 位硬约束，单独一步），
     * "太空里看到真实倾角"靠的是**渲染口径** `renderPos`（真实三维），不是让方块口径不压平。</p>
     */
    private static volatile boolean identityMode = true;

    public static boolean identityMode() {
        return identityMode;
    }

    public static void setIdentityMode(boolean value) {
        identityMode = value;
    }

    // ==================== 坐标换算的单一咽喉（方案 B / S1） ====================

    /**
     * MC 方块坐标 → 宇宙坐标（当前 = ×{@link #ZOOM}）。
     *
     * <p><b>这是方案 B 要立的"单一咽喉"</b>（见 {@code docs/mps-clone-plan.md} §30）：
     * 全仓所有"方块 ⇄ 宇宙"的换算都必须经这两个方法，别处一律不许再出现 ZOOM 或自造缩放。
     * S1 只是把它<b>立起来</b>（行为与改造前逐位相同）；S4 才会把它的实现换成恒等
     * （1 格 = 1 米）并把距离问题移到渲染侧。</p>
     */
    public static Vector3d toSpace(double mcX, double mcY, double mcZ) {
        return new Vector3d(toReal(mcX), toReal(mcY), toReal(mcZ));
    }

    /** 宇宙坐标 → MC 方块坐标（当前 = ÷{@link #ZOOM}）。见 {@link #toSpace}。 */
    public static Vector3d toGame(double realX, double realY, double realZ) {
        return new Vector3d(toMc(realX), toMc(realY), toMc(realZ));
    }

    /**
     * 坐标体系自检 —— <b>方案 B 的守门断言</b>（每个改动步骤前后都要跑）。
     *
     * <p>两项：</p>
     * <ol>
     *   <li><b>换算往返</b>：{@code toGame(toSpace(p)) ≈ p}。用相对判据而不是"逐位"——
     *       {@code ×ZOOM} 再 {@code ÷ZOOM} 在二进制浮点下本身就不保证位精确
     *       （实测 0.1 这类数会差 1 ulp），把不精确当失败只会制造噪音。</li>
     *   <li><b>blockPos 口径校验和</b>：对全部天体把 {@link #gamePosMc} 取到 1e-3 格再按
     *       固定权重求和。<b>S1–S3 都不该改变它</b>；一旦它变了，就说明"只想重构"的那一步
     *       动了坐标数值 —— 立刻停手。S4（换成恒等）之后这个数<b>应当</b>改变，
     *       届时改为盯 {@code renderPos} 口径的校验和。</li>
     * </ol>
     */
    public static String coordinateSelfCheck() {
        double maxRel = 0.0;
        for (double sample : new double[]{0.0, 1.0, -1.0, 1234.5, -9876.54321, 3.0e7, -3.0e7, 1.2345e9}) {
            double back = toMc(toReal(sample));
            double rel = sample == 0.0 ? Math.abs(back) : Math.abs(back - sample) / Math.abs(sample);
            maxRel = Math.max(maxRel, rel);
        }
        // ★ 闸门必须用**静态口径**（与 kelvin 积分无关），否则它会随时间漂移 ——
        //   2026-09 实测：用 `gamePosMc` 算出来的和写日志里是 -5410990681030，
        //   而数据侧金值是 -5410992856234，差的 2.17e6 正是 kelvin 权威打开后
        //   天体**已经跑掉的那点距离**。拿一个会自己变的数当"不许变"的红线，
        //   只会制造假警报 —— 这是仪器缺陷，不是坐标出错。
        //
        // ★★ 另外：**不许走 `toMc`/`gamePosMc`**（2026-09 轮 7 修正）。
        //   金值 -5410992856234 是在**历史约定**下取的，其定义是 `gamePosMc × 1000 = 米 / 10`。
        //   而 `toMc` 现在受 `identityMode` 影响：翻了恒等之后它变成恒等映射，
        //   同一个和会整体 ×10⁴ —— 一个**完全正确的**基线会看起来像"回归"，
        //   于是人会去追一个不存在的坐标错误（本会话已经栽过两次同类假警报）。
        //   写成 `米 / 10` 就与坐标约定**彻底无关**：两种模式下都得到同一个金值。
        long sumStatic = 0L;
        long sumLive = 0L;
        for (RealAstroData b : RealAstroData.BODIES) {
            double[] st = staticGamePos(b);
            sumStatic += (long) Math.floor(st[0] / 10.0 + 0.5)
                    + 2L * (long) Math.floor(st[1] / 10.0 + 0.5)
                    + 3L * (long) Math.floor(st[2] / 10.0 + 0.5);
            double[] live = blockPos(b);
            sumLive += (long) Math.floor(live[0] / 10.0 + 0.5)
                    + 2L * (long) Math.floor(live[1] / 10.0 + 0.5)
                    + 3L * (long) Math.floor(live[2] / 10.0 + 0.5);
        }
        return String.format(java.util.Locale.ROOT,
                "约定=%s | 往返最大相对误差=%.3e | 静态blockPos校验和=%d（闸门，权威值 -5410990681030，与坐标约定无关）"
                        + " | 实时blockPos校验和=%d（随 kelvin 积分漂移，仅参考） | 大气数=%d",
                identityMode ? "恒等(1格=1米)" : "历史(1格=" + (long) ZOOM + "米)",
                maxRel, sumStatic, sumLive, RealAstroData.BODIES.size());
    }

    /**
     * <b>方块口径</b>（米，天文坐标系）：天体在"游戏坐标"里的位置 ——
     * X/Z 来自 kelvin（若已就绪），<b>Y 一律取静态规则（黄道面压平）</b>。
     *
     * <p><b>给谁用</b>：传送落点、方块空间、以及任何要变成 {@code BlockPos}/区块的东西。
     * Y 压平不是可达性妥协，而是方块空间的硬要求 —— {@code BlockPos} 的 Y 只有 12 位（±2048）
     * 且被 {@code asLong} 静默掩码截断，真实 Y（火星 −6.4e9 m）进去会<b>读错方块</b>（§30.1）。</p>
     *
     * <p><b>不要用它做渲染</b>：渲染要真实三维位姿，见 {@link #renderPos(RealAstroData)}。</p>
     */
    public static double[] blockPos(RealAstroData b) {
        double[] base = staticGamePos(b);
        Vector3d authoritative = kelvinPos(b);
        if (authoritative == null) {
            return base;
        }
        return new double[]{authoritative.x(), base[1], authoritative.z()};
    }

    /**
     * 方块口径的<b>插值</b>版本（2026-09-25 新增）—— 太空维度渲染用它。
     *
     * <h2>为什么必须补这一版</h2>
     * 地表维度的渲染走 {@link #renderPos(RealAstroData, float)}（含 {@code getSmoothPos} 插值），
     * 而<b>太空维度</b>走的是 {@link #blockPos(RealAstroData)} → {@code kelvinPos} → {@code getPos()}，
     * 也就是<b>物理步进后的原始值、完全没有插值</b>。物理是 20Hz、渲染是 60fps，
     * 于是太空里天体的位置只在每个 tick 跳一次 —— 用户实机看到的就是"太空维度里的星球移动不够流畅"。
     *
     * <p>数值有多大：天体走的是<b>真实轨道速度 × 71.8 倍时间</b>。地球 30.15 km/s
     * ⇒ 每 tick 位移 <b>108.2 km</b>。近地观察时这是肉眼可见的台阶（十几像素一跳），
     * 插值之后才连续。Y 仍按方块口径压平（理由见 {@link #blockPos(RealAstroData)} 的注释：
     * 真实 Y 跨度会把天体扔出维度高度、且会让 {@code gamePos} 的口径不一致）。</p>
     *
     * @param partialTick 与本帧相机同一来源的插值系数
     */
    public static double[] blockPos(RealAstroData b, float partialTick) {
        double[] base = staticGamePos(b);
        CelestialBody body = kelvinBody(b);
        if (body == null) {
            return base;
        }
        Vector3d p = body.getSmoothPos(partialTick);
        if (p == null || !p.isFinite()) {
            return blockPos(b);
        }
        return new double[]{p.x(), base[1], p.z()};
    }

    /**
     * <b>渲染口径</b>（米，宇宙系）：天体的<b>真实三维位姿</b>（含真实 Y）。
     *
     * <p><b>给谁用</b>：天体渲染、光照/阴影投射、头盔 HUD、星图。它们都只把结果喂给
     * 矩阵/投影/双精度运算，不碰方块空间，所以真实 Y 是安全且必要的 ——
     * 用 {@link #blockPos} 会把所有天体压进一个平面（实测"天空里天体连成一条线"，§28.1）。</p>
     *
     * <p>kelvin 缺席时退回 {@link #blockPos}（绝不把天体画丢）。</p>
     */
    public static double[] renderPos(RealAstroData b) {
        Vector3d p = kelvinPos(b);
        return p == null ? blockPos(b) : new double[]{p.x(), p.y(), p.z()};
    }

    /** 渲染口径的<b>插值</b>版本（同一帧的 {@code partialTick}，与相机帧同一来源）。 */
    public static double[] renderPos(RealAstroData b, float partialTick) {
        CelestialBody body = kelvinBody(b);
        if (body == null) {
            return blockPos(b);
        }
        Vector3d p = body.getSmoothPos(partialTick);
        return p != null && p.isFinite() ? new double[]{p.x(), p.y(), p.z()} : blockPos(b);
    }

    /**
     * 旧名，等价于 {@link #blockPos}（历史调用点很多，保留以免一次改爆）。
     *
     * @deprecated 新代码请显式选口径：方块空间用 {@link #blockPos}，渲染用 {@link #renderPos}。
     */
    @Deprecated
    public static double[] gamePos(RealAstroData b) {
        return blockPos(b);
    }

    /** {@link #gamePos} 的 MC 格版本。 */
    public static double[] gamePosMc(RealAstroData b) {
        double[] p = gamePos(b);
        return new double[]{toMc(p[0]), toMc(p[1]), toMc(p[2])};
    }

    public static double[] earthMcPos(double secondsSinceJ2000) {
        double[] real = RealAstroData.EARTH.realPositionAt(secondsSinceJ2000);
        return new double[]{toMc(real[0]), toMc(real[1]), toMc(real[2])};
    }

    // ==================== kelvin 权威位置 ====================

    /**
     * 取天体在 kelvin 里的权威位置（米）；不启用 / 未就绪 / 坐标非有限时返回 {@code null}
     * （调用方回退静态值，所以 kelvin 缺席不会导致天体消失）。
     *
     * <p>客户端优先读<b>显示世界</b>：它由 {@code ClientSpaceWorld.syncMoveData()}
     * 每帧从缓冲世界同步过来，是"渲染该用的那份"（见 kelvin 侧的类注释）。
     * 服务端则读 {@link ServerSpaceWorld} —— 它是物理线程积分的那一份。</p>
     */
    private static Vector3d kelvinPos(RealAstroData b) {
        CelestialBody body = kelvinBody(b);
        if (body == null) {
            return null;
        }
        Vector3d pos = body.getPos();
        return pos.isFinite() ? pos : null;
    }

    /**
     * 查出该天体在 kelvin 侧的那个对象（<b>客户端显示世界优先，服务端兜底</b>），
     * 并顺手记录来源（诊断用）。找不到返回 {@code null}。
     *
     * <p>抽出来是为了让 {@link #kelvinPos}（取 {@code getPos}）与
     * {@link #renderPos(RealAstroData, float)}（取 {@code getSmoothPos}）<b>共用同一套查找与来源标注</b>，
     * 不再各写一份 —— 本项目已经吃过"同一概念三份实现"的亏（见 §29.3）。</p>
     */
    private static CelestialBody kelvinBody(RealAstroData b) {
        if (!kelvinAuthority) {
            kelvinPosSource = SOURCE_STATIC;
            return null;
        }
        CelestialBody body = null;
        ClientSpaceWorld clientWorld = ClientSpaceWorld.getSpaceWorld();
        if (clientWorld != null) {
            body = clientWorld.getCelestialBody(b.id());
        }
        if (body != null) {
            kelvinPosSource = SOURCE_CLIENT;
        } else {
            ServerSpaceWorld serverWorld = ServerSpaceWorld.getSpaceWorld(KELVIN_SPACE_ID);
            if (serverWorld != null) {
                body = serverWorld.getCelestialBody(b.id());
            }
            kelvinPosSource = body != null ? SOURCE_SERVER : SOURCE_STATIC;
        }
        return body;
    }

    private static final String SOURCE_STATIC = "静态（未启用/未就绪）";
    private static final String SOURCE_CLIENT = "客户端显示世界";
    private static final String SOURCE_SERVER = "服务端（积分）";

    /**
     * 上一次 {@link #kelvinPos} 取值的<b>来源</b>（诊断用，见 {@code /polymech kelvin}）。
     *
     * <p>为什么需要它：本方法有<b>三个</b>会静默回退的分支（未开权威 / 显示世界没有该天体
     * / 服务端世界没有该天体），三者症状完全一样 —— 天体不动。没有来源标注时，
     * "本体不动"既可能是积分没跑，也可能是读了一份冻结的显示世界，只能靠猜。
     * 2026-09 排查"星球不动"时就卡在这里，最后是查出渲染路径漏调
     * {@code ClientSpaceWorld.syncMoveData()}（缓冲区永远搬不到显示世界）。</p>
     */
    private static volatile String kelvinPosSource = SOURCE_STATIC;

    /** {@link #kelvinPosSource} 的可读名。 */
    public static String kelvinPosSource() {
        return kelvinPosSource;
    }

    /**
     * 静态规则下的游戏坐标（米）—— 迁移前的原样实现，也是 {@link #gamePos} 的 Y 来源。
     * <p>
     * 地球完全保持真实（玩家基准点）；其余天体 X-Z 不再压缩（木星/土星/天王星/海王星
     * 位于 MC ±3×10<sup>7</sup>~4.4×10<sup>8</sup> 格，由太空维度的
     * 边界/深空守卫 mixin 保证可达），Y 压平到黄道面（0）。
     * </p>
     */
    private static double[] staticGamePos(RealAstroData b) {
        if (b == RealAstroData.EARTH) {
            return new double[]{b.posX(), b.posY(), b.posZ()};
        }
        RealAstroData parent = RealAstroData.parentOf(b);
        if (parent != null) {
            // 卫星 = 母星游戏坐标 + 真实 X-Z 偏移（轨道几何保持真实比例），Y 取母星高度（0）。
            double[] pg = staticGamePos(parent);
            return new double[]{pg[0] + (b.posX() - parent.posX()), pg[1],
                    pg[2] + (b.posZ() - parent.posZ())};
        }
        return new double[]{b.posX(), 0.0, b.posZ()};
    }
}
