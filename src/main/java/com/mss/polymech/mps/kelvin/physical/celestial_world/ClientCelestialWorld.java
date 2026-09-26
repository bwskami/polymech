package com.mss.polymech.mps.kelvin.physical.celestial_world;

import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Planet;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端天体世界 —— <b>与
 * {@code org.cn_grass_block.kelvin.physical.celestial_world.ClientCelestialWorld}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 客户端<b>只保留"我当前所在的那一个"天体世界</b>，并提供带自愈的读取。
 *
 * <h2>为什么和 {@link ServerCelestialWorld} 长得完全不同（照 space 0.1.3）</h2>
 * 服务端要同时服务所有维度，所以是"池 + 多种反查"；客户端同一时刻<b>只有</b>一个
 * 已加载维度，维护一个池没有意义，反而要处理"切维度时哪几个该留"。
 * 于是客户端退化成<b>单例缓存</b>。
 *
 * <p>关键是 {@link #getCelestialWorld()} 里的<b>自愈</b>：缓存失效有两种成因，
 * 都不可能靠事件可靠捕捉 ——
 * <ol>
 *   <li>{@code Minecraft.level == null}（退回主菜单 / 换存档途中），</li>
 *   <li>当前维度已经<b>不是</b>缓存里那个（走出地表、进了别的维度）。</li>
 * </ol>
 * 两种情况都<b>顺手把缓存置空再返回 null</b>，而不是抛异常或返回过期对象。
 * 这样调用方永远只需判 {@code == null}，不必自己管失效 —— 把"状态可能过期"这件事
 * 收进读取路径，是这一层的设计要点。</p>
 */
public class ClientCelestialWorld extends CelestialWorld {

    private static ClientCelestialWorld celestialWorld = null;

    public ClientCelestialWorld(Planet celestialBody, ResourceLocation WorldID, ResourceLocation SpaceWorldID) {
        super(celestialBody, WorldID, SpaceWorldID);
    }

    public static void init() {
        celestialWorld = null;
    }

    /** 取当前维度的天体世界；不在行星地表（或未初始化）时返回 null，并清掉过期缓存。 */
    public static ClientCelestialWorld getCelestialWorld() {
        if (celestialWorld == null) {
            return null;
        } else if (Minecraft.getInstance().level == null) {
            celestialWorld = null;
            return null;
        } else if (!Minecraft.getInstance().level.dimension().location().equals(celestialWorld.WorldID)) {
            celestialWorld = null;
            return null;
        } else {
            return celestialWorld;
        }
    }

    /** 由同步包在收到"进入天体世界"时写入。 */
    public static void setCelestialWorld(ClientCelestialWorld celestialWorld) {
        ClientCelestialWorld.celestialWorld = celestialWorld;
    }

    /**
     * 地表天空用<b>原版时钟</b>驱动行星自转 —— 用户拍板：贴合原版昼夜
     * （别的模组只认原版时间，我们不可能让它们接受真实世界时钟）。
     *
     * <p><b>为什么"提高天体时间倍率"不够</b>：倍率只对齐<b>速率</b>（转一圈 = 原版一天），
     * 对齐不了<b>相位</b> —— 相位由"世界加载时刻 + 累计模拟时间"决定，与原版 {@code dayTime}
     * 没有共同原点；而且原版掉 TPS 时 {@code dayTime} 走慢、物理线程仍按墙钟走，会漂。
     * 所以这里直接<b>由 dayTime 推自转角</b>，速率与相位一起绑，永不漂。</p>
     *
     * <p><b>式子</b>（轴倾角目前还是单位四元数，所以能直接解；等补上倾角后要改成
     * "绕数据里的自转轴"）：</p>
     * <pre>
     *   φ = atan2(太阳方向.z, 太阳方向.x)        行星未自转坐标系里的太阳经度
     *   c = 2π·(dayTime%24000 − 6000)/24000     原版约定：0=日出、6000=正午、12000=日落、18000=午夜
     *   θ = c + φ                               ⇒ 观察者时角 H = c + 观察者经度、太阳赤纬 δ = 0
     *                                           ⇒ 高度角 = 90° − |H|
     * </pre>
     * 于是：正午 H=0 太阳当头；日出/日落 |H|=90° 正好在地平线上；午夜 H=180° 在地平线下。</p>
     *
     * <p><b>代价，如实记录</b>：① 这<b>偏离</b> space 0.1.3（它的地表自转是物理积分、全树不看
     * {@code dayTime}）；② 它按"世界经度 0 处 = 原版时间"标定，离世界原点越远有固定时差
     * （{@code pos_shadow_longitude_length} = 100000 格 = 90° ⇒ 25000 格 ≈ 6 小时）；
     * ③ 服务端那条换算（{@link CelestialWorld#getSpacePosFromWorldPos} 被物理体事件调用时）
     * 走的仍是物理自转，与客户端天空可能有时角差。</p>
     */
    /**
     * 2026-09-22 实机确认后**改为启用**（历史：中途曾按"现实 ×60"关掉过它，见下）。
     *
     * <p><b>为什么必须启用</b>：不锁相位时，天体自转的相位与世界 {@code dayTime} 无关 ——
     * 于是 {@code /time set 1000}（白天）时太阳可能仍在地平线下（用户实机截图）。
     * 用户要求"**白天太阳必须在上面**"。</p>
     *
     * <p><b>现在锁相位不再有代价</b>：天体时间尺度已经改成"天体的一天 = 原版的一天 = 20 分钟"
     * （{@code OrbitPhysicalThread.timeScale()}），两者**周期相同**，所以锁定相位不是"两套时钟二选一"，
     * 而只是额外保证"天空昼夜与 worlds 亮度永不错开"。当时否掉它的理由（速率冲突）已消失。</p>
     *
     * <p>机制：自转角 {@code θ = 2π·(dayTime%24000 − 6000)/24000 + φ_太阳}（6000 = 原版正午），
     * ⇒ 观察者时角 {@code H = 时钟角 + 观察者经度}、δ 取自真实自转轴 ⇒ 正午太阳当头、午夜在地平线下。</p>
     */
    private static final boolean BIND_SURFACE_SPIN_TO_DAYTIME = true;

    @Override
    protected org.joml.Quaterniond surfaceSpinRotate(float partialTick) {
        if (!BIND_SURFACE_SPIN_TO_DAYTIME) {
            return super.surfaceSpinRotate(partialTick);
        }
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null || this.celestialBody == null || this.celestialBody.spaceWorld == null) {
            return super.surfaceSpinRotate(partialTick);
        }
        com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody sun =
                this.celestialBody.spaceWorld.getCelestialBody("sun");
        if (sun == null) {
            return super.surfaceSpinRotate(partialTick);
        }
        org.joml.Vector3d sunDir = new org.joml.Vector3d(sun.getSmoothPos(partialTick))
                .sub(this.celestialBody.getSmoothPos(partialTick));
        double phiSun = Math.atan2(sunDir.z, sunDir.x);
        // ★ 时钟角必须带 partialTick（2026-09-25 用户报"移动跟抽帧一样"）。
        //   原来只用整数 `getDayTime()`：dayTime 每 tick 才 +1，于是**整片天空（含太阳）
        //   一秒钟只动 20 次**，每次 0.3°/20 = 0.015° —— 在 60fps 下就是每 3 帧才动一下，
        //   而其它所有按 partialTick 插值的量（相机、天体位置）都是连续的，
        //   两种节奏混在一起就是"不丝滑"。加 partialTick 后时钟角连续，
        //   天空转速一点不变（仍是 360°/20 分钟），只是不再以 tick 为粒度跳。
        double dayTimeFrac = (level.getDayTime() % 24000L) + partialTick;
        double clock = 2.0 * Math.PI * (dayTimeFrac - 6000.0) / 24000.0;
        // ★ 2026-09-22 实测归因（一次只改一个变量）：
        //   ① 先把 rotate 落盘顺序对齐 space 的 (w,x,y,z)（见 RealAstroData#rotateQuaternion）——
        //      单独改它之后，97 条自检**仍然全部 FAIL**（`方位=269.8°(正西)` 在上午）⇒ 它不是镜像的来源；
        //   ② 因此剩下唯一的变量就是这里的**时钟项符号**：取负。
        //      昼夜里高度角 = 90° − |H| 只依赖 |H| ⇒ 取负**不改昼夜曲线**（正午仍头顶、午夜仍在下），
        //      只把东西方位翻正（上午在东、下午在西）。
        return new org.joml.Quaterniond().rotateY(-clock + phiSun);
    }
}
