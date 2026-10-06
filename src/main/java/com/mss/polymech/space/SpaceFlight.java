package com.mss.polymech.space;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>创造飞行的"输入 → 世界速度"</b>：原版那一套的<b>视线基底化</b>版本。
 *
 * <p>这是 {@code SpaceFlightMixin}（{@code Player.travel} HEAD 取消）用的纯数学，
 * 单独抽出来是为了让离线探针能调<b>真实现</b>而不是复刻一份公式
 * —— 本项目为此栽过 5 次（"复刻对复刻必然自洽" = 死判据，见
 * {@code docs/space-input-basis.md} §四）。</p>
 *
 * <h2>为什么必须替换掉原版的飞行分支（不是"补一个特例"）</h2>
 * 原版 1.21.1 的飞行竖直有<b>两个</b>独立的地方，而且它们互相打架：
 * <ol>
 *   <li>{@code LocalPlayer.aiStep}：{@code setDeltaMovement(delta.add(0, j·flySpeed·3, 0))}
 *       —— 竖直冲量沿 <b>世界 +Y</b>；</li>
 *   <li>{@code Player.travel}：{@code d2 = delta.y}（读于 {@code super.travel} <b>之前</b>），
 *       然后 {@code setDeltaMovement(vec31.x, d2 * 0.6, vec31.z)} —— <b>y 通道被整条覆盖</b>。</li>
 * </ol>
 * 两个后果（都实测/离线可判）：
 * <ul>
 *   <li>竖直方向永远是世界 +Y ⇒ 头朝下/滚 180° 时按空格往<b>屏幕下</b>飞（用户 2026-10-03 报）；</li>
 *   <li>{@code moveRelative} 里沿视线基底算出的那份竖直分量（{@link SpaceInputRotationMixin}
 *       注入的那一步）<b>加进去就被丢掉</b>，而且水平输入里只要带一点世界竖直分量
 *       （滚到 ±90° 时"屏幕左"就是世界竖直）也会一起丢 —— 所以水平在极端姿态下同样是坏的。</li>
 * </ul>
 *
 * <p>根因不是"少了一个方向"，而是<b>阻尼的基底与冲量的基底不一致</b>：
 * 原版把 (x,z) 乘 {@code f3=0.91}、y 乘 {@code 0.6} —— 那是<b>世界轴</b>的分解；
 * 而输入意图是<b>视线轴</b>的。只要两者不同基，稳态速度方向就必然偏，
 * 偏多少取决于姿态（水平姿态下两者恰好重合，所以平时看不出来）。</p>
 *
 * <h2>做法（照 space 0.3.0 的 {@code MixinPlayerFlight}）</h2>
 * 把"阻尼"也搬到视线基底里逐轴做，与冲量同基：
 * <pre>
 *   v ← v + 冲量(视线基底)
 *   v ← 沿视线基底逐轴阻尼： 水平(屏幕左/视线) × 0.91，竖直(屏幕上) × 0.6
 * </pre>
 * 这就是原版 {@code (x,z)×f3、y×0.6} 的基底化 —— <b>水平姿态下与原版等价</b>
 * （差只在 float 精度内 ~1e-7：基底来自 {@link SpacePlayerData#viewFrame} 的 float 四元数，
 * 分解/合成各一次 ⇒ 相对误差约 float epsilon。见探针的"中性"判据，容差取 1e-6），
 * 任意姿态下则让速度方向 = 输入方向。
 *
 * <p><b>注意</b>：{@code SpaceInputRotationMixin.moveRelative} 那条路仍然是
 * <b>走路/游泳</b>用的（飞行被本 mixin 在 {@code Player.travel} HEAD 拦掉了，
 * 根本走不到 {@code moveRelative}）。两条路读同一个 {@link SpacePlayerData#viewFrame}
 * —— 这正是"一处定义、处处引用"。</p>
 */
public final class SpaceFlight {

    /**
     * 水平轴的每 tick 阻尼 = 原版 {@code f3}（{@code onGround ? 摩擦×0.91 : 0.91}）。
     *
     * <p>取字面 {@code 0.91} 照 space 0.3.0。飞行贴地时原版会是 {@code 摩擦×0.91}，
     * 但 {@code LocalPlayer.aiStep} 一旦发现 {@code onGround} 就把 {@code flying} 关掉
     * （{@code isAlwaysFlying} 除外），所以那是一个 tick 量级的瞬态，不值得为它引入分支。</p>
     */
    public static final double HORIZONTAL_DAMP = 0.91;

    /** 竖直（屏幕上）轴的每 tick 阻尼 = 原版 {@code Player.travel} 飞行分支里的 {@code 0.6}。 */
    public static final double UP_DAMP = 0.6;

    /** 竖直冲量倍数：原版 {@code LocalPlayer.aiStep} 的 {@code j * abilities.getFlyingSpeed() * 3.0F}。 */
    public static final float ASCEND_IMPULSE = 3.0F;

    private SpaceFlight() {
    }

    /**
     * 取视线基底的三个轴（就是 {@link SpacePlayerData#viewFrame} 的三列）。
     *
     * <p>约定与 {@link SpacePlayerData#viewFrame} 一致：{@code +X} 屏幕左、{@code +Y} 屏幕上、
     * {@code +Z} 视线前方 —— 所以本类里<b>不需要任何叉积/取负</b>。</p>
     */
    public static void basis(Quaternionf viewFrame, Vector3f left, Vector3f up, Vector3f forward) {
        left.set(1.0F, 0.0F, 0.0F);
        up.set(0.0F, 1.0F, 0.0F);
        forward.set(0.0F, 0.0F, 1.0F);
        viewFrame.transform(left);
        viewFrame.transform(up);
        viewFrame.transform(forward);
    }

    /**
     * 原版 {@code LocalPlayer.aiStep} 在 {@code travel} <b>之前</b>加进去的那一份世界 +Y 冲量。
     *
     * <p>返回值是<b>要减掉</b>的量：我们的竖直由视线基底给，这一份必须撤掉，
     * 否则它会留在 {@code delta.y} 里被 {@code Player.travel} 的 {@code ×0.6} 继续放大，
     * 变成"世界竖直 + 屏幕竖直"两份叠加。</p>
     *
     * <p><b>稳态账</b>（两处都容易算错，写清楚）：原版竖直通道是
     * {@code v ← 0.6·(v + 冲量)}（`Player.travel` 把 `d2 = v + 冲量` 乘 0.6），
     * 所以固定点是 {@code 0.6·冲量/(1−0.6) = 1.5·冲量}，<b>不是</b> {@code 冲量/(1−0.6) = 2.5·冲量}
     * —— 后者是 {@code v ← 0.6·v + 冲量} 的解，两者差一个 {@code d} 因子。
     * 水平同理：{@code 0.91·冲量/(1−0.91) = 10.11·冲量}。</p>
     *
     * <p>{@code ascend = (jumping ? 1 : 0) - (shiftKeyDown ? 1 : 0)}，与原版 {@code j} 同号同义。</p>
     */
    public static double cancelVanillaVertical(float ascend, float baseFlySpeed) {
        return ascend * baseFlySpeed * ASCEND_IMPULSE;
    }

    /**
     * 本 tick 的飞行输入冲量（视线基底），等价于原版
     * {@code moveRelative(getFlyingSpeed(), (xxa, yya, zza))} 的<b>意图</b>，但
     * ① 竖直那一份改走视线基底、② 不经过会被覆盖的 y 通道。
     *
     * <p>水平：原版 {@code getInputVector} 先"长度 >1 才归一化"、再乘
     * {@code getFrictionInfluencedSpeed}；飞行时后者 = {@code Player.getFlyingSpeed()} =
     * {@code abilities.getFlyingSpeed() × (疾跑 ? 2 : 1)}。这里逐条对应。
     * （{@code xxa/zza} 在 {@code LivingEntity.aiStep} 里已经先乘过 0.98，所以不必再乘。）</p>
     *
     * <p>竖直：{@code ascend × baseFlySpeed × 3}，方向取<b>屏幕上</b>。
     * 竖直这一份<b>不</b>乘疾跑倍率 —— 原版 {@code aiStep} 用的也是
     * {@code abilities.getFlyingSpeed()} 而不是 {@code getFlyingSpeed()}。</p>
     *
     * @param viewFrame      {@link SpacePlayerData#viewFrame}
     * @param xxa            {@code LivingEntity.xxa}（左正）
     * @param zza            {@code LivingEntity.zza}（前正）
     * @param ascend         {@code (jumping?1:0) - (shiftKeyDown?1:0)}
     * @param baseFlySpeed   {@code abilities.getFlyingSpeed()}
     * @param sprinting      是否疾跑（只放大水平分量）
     * @param out            输出冲量（格/tick）
     * @return {@code out}（方便调用方一行写完）
     */
    public static Vector3d impulse(Quaternionf viewFrame, float xxa, float zza, float ascend,
                                   float baseFlySpeed, boolean sprinting, Vector3d out) {
        float strafe = xxa;
        float forward = zza;
        float len = (float) Math.sqrt(strafe * strafe + forward * forward);
        if (len > 1.0F) {
            strafe /= len;
            forward /= len;
        }
        float horizontalSpeed = baseFlySpeed * (sprinting ? 2.0F : 1.0F);
        float ascendSpeed = ascend * baseFlySpeed * ASCEND_IMPULSE;

        Vector3f left = new Vector3f();
        Vector3f up = new Vector3f();
        Vector3f view = new Vector3f();
        basis(viewFrame, left, up, view);

        out.set(
                (left.x * strafe + view.x * forward) * horizontalSpeed + up.x * ascendSpeed,
                (left.y * strafe + view.y * forward) * horizontalSpeed + up.y * ascendSpeed,
                (left.z * strafe + view.z * forward) * horizontalSpeed + up.z * ascendSpeed);
        return out;
    }

    /**
     * 把速度<b>沿视线基底逐轴</b>阻尼：屏幕左/视线 × {@link #HORIZONTAL_DAMP}、
     * 屏幕上 × {@link #UP_DAMP}。
     *
     * <p>这就是原版 {@code setDeltaMovement(vec35.x * f3, d2 * 0.98, vec35.z * f3)} +
     * {@code Player.travel} 的 {@code d2 * 0.6} 的基底化版本。因为阻尼在<b>与冲量同一个基</b>下
     * 是对角的，稳态速度方向 = 输入方向（原版在世界基下对角 ⇒ 稳态方向 = 世界轴方向，
     * 这就是"头朝下按空格往屏幕下飞"）。</p>
     *
     * <p>水平姿态（pitch=0, roll=0）时视线基底 = 世界基底，本方法与原版等价（差在 float 精度内）。</p>
     *
     * @return {@code out}（方便调用方一行写完）
     */
    public static Vector3d damp(Quaternionf viewFrame, double vx, double vy, double vz, Vector3d out) {
        Vector3f left = new Vector3f();
        Vector3f up = new Vector3f();
        Vector3f view = new Vector3f();
        basis(viewFrame, left, up, view);

        double alongLeft = (vx * left.x + vy * left.y + vz * left.z) * HORIZONTAL_DAMP;
        double alongUp = (vx * up.x + vy * up.y + vz * up.z) * UP_DAMP;
        double alongForward = (vx * view.x + vy * view.y + vz * view.z) * HORIZONTAL_DAMP;

        out.set(
                left.x * alongLeft + up.x * alongUp + view.x * alongForward,
                left.y * alongLeft + up.y * alongUp + view.y * alongForward,
                left.z * alongLeft + up.z * alongUp + view.z * alongForward);
        return out;
    }
}
