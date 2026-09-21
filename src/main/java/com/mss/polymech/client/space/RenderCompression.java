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
     * 开关（默认<b>关</b>）：S3 期间可随时切回旧投影做 A/B，
     * 也保证"接进来"这一步本身是零风险的。
     */
    public static boolean enabled = false;

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
