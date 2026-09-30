package com.mss.polymech.client.space;

/**
 * 距离压缩 —— <b>照抄 space 的思路</b>（clean-room：抄机制与参数含义，不抄代码文本）。
 *
 * <h2>它解决什么</h2>
 * 天体位置是**真实米**（地球 1.5e11 m、冥王星 5.9e12 m）。若把这么大的范围直接塞进一个
 * 透视投影的 near/far，浮点深度精度会被拉到没边 —— 远处天体互相闪烁、与天空盒打架。
 * space 的办法是：<b>把"相机到天体"的距离非线性压进一个固定区间</b>，
 * 同时<b>按同一比例缩放天体半径</b>，于是
 * <pre>角直径 = (R · zoom) / compressed ≈ R / length</pre>
 * <b>保持不变</b> —— 看起来一模一样，但深度值落在一个健康的范围内。
 *
 * <h2>照抄来的参数（不要自己改）</h2>
 * <ul>
 *   <li>{@link #NEAR} = 16384：小于它的距离**原样保留**（近处必须精确，飞船/近地天体在这里）；</li>
 *   <li>{@link #FAR} = 262144：压缩的<b>渐近上界</b> —— 无论真实多远，压缩后都 &lt; FAR；</li>
 *   <li>4096：指数里的尺度常数（与 space 的 {@code PositionCompression} 逐字一致）。</li>
 * </ul>
 *
 * <h2>我们为什么不用 space 的 {@code getDepthFar() = FAR×2}</h2>
 * space 那样做是因为它的星球与 MC 地形**共用主深度缓冲**；我们给太空天体用的是
 * 自己的 {@code spaceProj}（{@link SpaceRenderer} 里 setProjectionMatrix），
 * 压缩之后 far 只要 ≥ FAR 就够，改 MC 的 depth far 反而会影响地形/实体。
 * <b>这就是"照抄思路而不是逐字照抄"的地方。</b>
 */
public final class RenderCompression {

    private RenderCompression() {
    }

    /** 小于它不压缩（近处必须精确）。 */
    public static final double NEAR = 16384.0;
    /** 压缩的渐近上界：压缩后恒 &lt; 它。 */
    public static final double FAR = 262144.0;

    /**
     * 开关（<b>2026-09-27 起默认开</b>，照抄两个参考版本：0.0.6 与 0.1.3 都默认启用）。
     *
     * <h2>启用前已经验过的三件事（离线，见 {@code native/jni-smoketest/RenderCompressionTest}）</h2>
     * <ol>
     *   <li><b>角直径不变</b>：{@code atan(R/L) == atan((R·zoom)/compress(L))}，浮点误差内逐位相同；</li>
     *   <li><b>不会被远平面裁掉</b>：压缩后恒 ≤ FAR，而启用时 {@code spaceProj} 的 far 自动取
     *       {@code FAR×2}（{@code SpaceRenderer} 里那行三元表达式）；</li>
     *   <li><b>UBO 与该压的一起压</b>：{@code CelestialBodyDataBuffer} 的 Pos / 半径 / 大气壳厚度
     *       都走压缩帧，而 {@code RealPos} 与真实大气高度保持米 —— 实测光照方向误差
     *       <b>0.003°</b>（全压则是 <b>4.34°</b>），所以这里**有意不照抄** space 的"全压"。</li>
     * </ol>
     *
     * <h2>回退</h2>
     * 把下面这行改回 {@code false} 重新编译即可（{@code active} 随之恒假 ⇒ 渲染路径逐位回到启用前）。
     *
     * <h2>⚠️ 两套投影的深度值不可比 —— 2026-09-30 已按结构处理，别再靠"东西都近"兜</h2>
     * 压缩只作用于天体（以及它们的大气壳），而地形/实体/粒子仍用 MC 自己的投影画真实距离。
     * 距离压缩是**单调**的，所以空间上的远近顺序本身没错；但**深度缓冲里的数值**来自两套投影
     * （天体走 {@code spaceProj} near=1000m / far=524288m，世界走 MC 主投影 near=0.05 / far=768），
     * 同一个距离在两边差着数量级 ⇒ 任何"拿主深度与天体深度比大小"的判据都是假的。
     *
     * <p>这里原来写的是"放置的方块/船都在几百米内，所以安全" —— <b>那句是错的</b>，
     * 而且正是"远处的星球把近处的物理体挡住"的根因。实测（{@code DepthOcclusionProbe}；
     * 用户 09-30 会话，玩家离地球 15849 格）：地球表面压缩后 50853 m → spaceProj 深度
     * <b>0.98221</b>，而 5 格处的方块用 MC 主投影是 <b>0.99006</b> —— 方块反而"更远"
     * ⇒ 星球赢得深度测试（物理体被 LEQUAL 挡掉），后处理的遮挡掩码也恒为 0。
     * 临界距离只有 <b>2.80 格</b>。<b>而且这不是压缩引入的</b>：换回压缩前的 far=1e13
     * 时临界距离是 <b>2.53 格</b>，旧判据从来只对贴脸的东西成立。</p>
     *
     * <p><b>第十二轮（2026-09-30）起改成 space 的结构，不再是权宜之计</b>：
     * 天体画进独立的 {@code spaceRenderTarget}（color+depth），主深度从头到尾只属于 MC 世界
     * ⇒ 世界几何体永远画在天体上面，不需要"画完把主深度清一遍"那种补丁；
     * 同时 {@code SpaceDepthFarMixin} 把太空维度的 {@code getDepthFar()} 抬到 {@code FAR × 2}，
     * 天体投影取 {@code setPerspective(fov, aspect, getDepthFar(), 0.05F)}（反向 Z）
     * ⇒ 两套投影共用同一对数字、只是对调顺序，{@code 1 - mainDepth} 与 {@code spaceDepth}
     * 成为<b>精确镜像</b>，后处理里 space 的 {@code max(1 - mainDepth, spaceDepth)} 才成立。
     * （此前"有意不采用 mixin"的写法已废弃：清深度虽然也能让主深度干净，
     * 但两套投影的数值仍然不可比，任何需要跨缓冲比大小的判据都不能写。）</p>
     */
    public static boolean enabled = true;

    /**
     * 本帧是否真的对<b>当前这次绘制的天体</b>启用压缩（默认 false）。
     *
     * <p>与 {@link #enabled} 分开是为了作用域：{@code enabled} 是"总开关"，
     * 而 {@code active} 由 {@code SpaceRenderer} 每帧设定 ——
     * 目前只在<b>太空维度</b>置真（地表天空用的是宇宙系真实位姿 + 另一套相机帧，
     * 先不混进来，见 {@code docs/mps-clone-plan.md} §30.7 的"S3 剩余"）。</p>
     *
     * <p>{@code enabled=false} ⇒ 这里恒为 false ⇒ <b>接进渲染路径也不改变任何画面</b>。</p>
     */
    public static boolean active = false;

    /** 指数尺度常数（照 space）。 */
    private static final double SCALE = 4096.0;

    /**
     * 把"相机到天体表面"的距离压进 {@code (NEAR, FAR)}。
     *
     * @param x 相机到天体<b>表面</b>的距离（= 中心距 − 半径）
     */
    public static double compress(double x) {
        if (x <= NEAR) {
            return x;
        }
        return FAR - (FAR - NEAR) * Math.exp(-((x - NEAR) / SCALE) / (FAR - NEAR));
    }

    /**
     * 该天体应当被缩放的比例（位置与半径<b>同乘</b>它，角直径不变）。
     *
     * @param centerToBody 相机到天体<b>中心</b>的距离
     * @param radius       天体半径
     */
    public static double zoomFor(double centerToBody, double radius) {
        double length = centerToBody - radius;
        if (length <= 0.0) {
            // 相机在天体内部/表面：不压缩（压缩在这里无意义，且会除零）
            return 1.0;
        }
        return compress(length) / length;
    }
}
