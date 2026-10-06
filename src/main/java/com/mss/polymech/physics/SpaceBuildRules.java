package com.mss.polymech.physics;

import org.joml.Quaterniond;
import org.joml.Vector3f;

/**
 * 太空里"放方块"的**前置判据** —— 纯函数、零 Minecraft 依赖，所以能离线验证
 * （{@code native/jni-smoketest/SpaceBuildRulesProbe.java}）。
 *
 * <h2>为什么太空里放方块要变成物理体（用户给的理由，也是硬约束）</h2>
 * <b>原版方块到了 ~3×10⁷ 格（30m）之后就放不出来了</b>：{@code BlockPos} 的 X/Z 各只有 26 位
 * （±33,554,431），超出会静默别名到原点附近；我们另有 {@code LevelSpaceAccessMixin:59-76}
 * 在 {@code isDeepSpace} 时把 {@code setBlock} 直接返回 false。缩放约定下地球在 1.53e6 格（放得下）、
 * 木星及以外 7.8e7 格（放不下）—— 所以"在外行星那边盖东西"只能靠**物理体**。
 *
 * <h2>用户拍板的放置规则（2026-09-27 实机反馈，两轮；2026-10-06 第三轮）</h2>
 * 第一轮："只有在物理体旁边和人旁边的空地才能放" —— 我实现成"贴着体，或离玩家 ≤2.5 格"。
 * 第二轮（实机）："离玩家的保护就别这么远了，不然不好放啊"。
 *
 * <p>查证后确认**那条门是我加过头了**：于是去掉该门，只保留真正必要的那条：
 * <b>目标格不得与玩家身体相交</b>（别把自己的腿封进方块里）。</p>
 *
 * <p>第三轮（2026-10-06 实机，两轮）。第一句原话："你这个放置逻辑就很像基岩版的放置逻辑：玩家甚至可以
 * 点方块前面的空气进行延申搭路，瞄准方块边缘甚至会往相对方块延申一格"。当时"目标格"有两种来源，
 * 第二种是<b>准星前方固定 3 格</b>的兜底，而它<b>不分场合</b>：只要射线打空就用 ⇒ 贴着结构瞄空气也会放，
 * 方块还落到 3 格外（"往方块外延一格"）。第二句原话（拍板）："你应该区分一下创建物理体和对物理体
 * 扩增的区别，现在这个是影响到了扩大物理体的手感"。</p>
 *
 * <p>⇒ 现在的规则（见 {@link #targetKind}：创建与扩增<b>分开</b>）：
 * <ol>
 *   <li><b>扩增</b>：射线精确命中体的面 ⇒ 落点 = 被命中那一格沿命中面走一格，并进那个体。
 *       与 space 0.3.0 的 {@code useBlock} 里 {@code clickedPos.relative(hit.getDirection())} 同构。</li>
 *   <li><b>拒绝</b>：打空，但周围 {@code REACH} 内<b>有</b>体 ⇒ 什么都不做。这是"没瞄中"，
 *       不是"要在空处起新体" —— 挡住"点空气一路搭路"。</li>
 *   <li><b>创建</b>：打空，且周围 {@code REACH} 内<b>完全没有</b>体 ⇒ 在准星前方
 *       {@link #PLACE_DISTANCE} 格起一个新体（凭空起第一块只能这样）。</li>
 * </ol>
 * 于是那条兜底<b>只在"周围没有体"时才可能被用到</b>，不再污染扩增的手感。</p>
 *
 * <h2>为什么太空里放方块要变成物理体（用户给的理由，也是硬约束）</h2>
 * <b>原版方块到了 ~3×10⁷ 格（30m）之后就放不出来了</b>：{@code BlockPos} 的 X/Z 各只有 26 位
 * （±33,554,431），超出会静默别名到原点附近；我们另有 {@code LevelSpaceAccessMixin:59-76}
 * 在 {@code isDeepSpace} 时把 {@code setBlock} 直接返回 false。缩放约定下地球在 1.53e6 格（放得下）、
 * 木星及以外 7.8e7 格（放不下）—— 所以"在外行星那边盖东西"只能靠**物理体**。
 *
 * <h2>本类**只做判据**，不做实现</h2>
 * 真正"并进邻体 / 由这一块新建体"（含碰撞体构建、客户端广播、落地皮、存档）全部交给
 * {@link PhysicsBodyTracker#placeBlockAt(net.minecraft.server.level.ServerLevel,
 * net.minecraft.core.BlockPos, int)} —— 那是项目里已有的原语，注释原文就是
 * "太空维度里玩家摆出来的方块应当是物理体……并入面相邻的现有物理体，找不到就由这一块新建一个物理体"。
 * 2026-09-27 的教训：我第一版绕过它自己拼投影，结果体没有碰撞体、也没广播
 * ⇒ 实机现象是"放出来不显示、也打不到它"。
 */
public final class SpaceBuildRules {

    /**
     * 周围（{@code REACH} 内）**完全没有**物理体时，"创建"新体的第一块取准星前方这么远（格）。
     *
     * <p>2026-10-06：这个值一度被整条删除（当时把"打空"一律判成不放）。现在它回来了，但用法收窄成
     * <b>只有"周围没有体"这一种情形</b>才会走到 —— 见 {@link #targetKind}。</p>
     */
    public static final double PLACE_DISTANCE = 3.0;
    /** 每维度最多多少个体（防连点把物理世界塞爆）；只挡"新建"，并进已有体不受限。 */
    public static final int MAX_BODIES_PER_DIMENSION = 256;
    /** 同一玩家的放置间隔（tick）。 */
    public static final int COOLDOWN_TICKS = 4;

    private SpaceBuildRules() {
    }

    /** 放置判据的结果。 */
    public enum Decision {
        /** 允许：交给 {@link PhysicsBodyTracker#placeBlockAt} 去并体或建体。 */
        PLACE,
        /** 拒绝：目标格已有真实方块。 */
        REFUSE_OCCUPIED,
        /** 拒绝：目标格与玩家身体相交（会把自己的腿封进方块里）。 */
        REFUSE_TOO_CLOSE,
        /** 拒绝：该维度体数已达上限（只挡"新建"，并进已有体不受限）。 */
        REFUSE_BODY_LIMIT
    }

    /**
     * 判据（顺序即优先级）。
     *
     * <p>注意<b>没有</b>"离玩家多远"那一条了 —— 见类注释：那是加过头的保护。
     * 能放多远由调用方的 {@code REACH}（命中距离）自己界定；<b>打空根本不放</b>。</p>
     *
     * @param cellOccupied     目标格是否已有真实方块
     * @param intersectsPlayer 目标格的包围盒是否与玩家身体相交
     * @param adjacentToBody   目标格是否与某个物理体面相邻（曼哈顿距离 1）⇒ 这种情况是"并进去"，
     *                         不新建体，所以也不受体数上限限制
     * @param bodyCount        当前维度的物理体数量
     */
    public static Decision decide(boolean cellOccupied, boolean intersectsPlayer, boolean adjacentToBody,
                                  int bodyCount) {
        if (cellOccupied) {
            return Decision.REFUSE_OCCUPIED;
        }
        if (intersectsPlayer) {
            return Decision.REFUSE_TOO_CLOSE;
        }
        if (!adjacentToBody && bodyCount >= MAX_BODIES_PER_DIMENSION) {
            return Decision.REFUSE_BODY_LIMIT;
        }
        return Decision.PLACE;
    }

    /** 拒绝时的玩家可见文案（放这里是为了离线判据也能对口径）。 */
    public static String message(Decision decision) {
        return switch (decision) {
            case REFUSE_OCCUPIED -> "那一格已经有方块了";
            case REFUSE_TOO_CLOSE -> "太贴着你了，把准星往前挪一点";
            case REFUSE_BODY_LIMIT -> "这个维度的物理体已达上限（" + MAX_BODIES_PER_DIMENSION + "）";
            case PLACE -> "";
        };
    }

    /**
     * 这一次右键到底要"创建"还是"扩增" —— <b>两件事，必须分开</b>（用户 2026-10-06 拍板）。
     *
     * <p>用户原话："你应该区分一下创建物理体和对物理体扩增的区别，现在这个是影响到了扩大物理体的手感"。
     * 上一版把两者合成一条规则（打空一律不放），结果是扩增的手感被切掉、创建也一起没了。</p>
     */
    public enum Placement {
        /** 扩增：射线命中体 ⇒ 落点 = 被命中那一格沿命中面走一格，并进那个体。 */
        GROW,
        /** 创建：周围 REACH 内完全没有体 ⇒ 准星前方 {@link #PLACE_DISTANCE} 格起新体。 */
        CREATE,
        /** 拒绝：没瞄中体，但周围**有**体 ⇒ 什么都不做（这是"没瞄中"，不是"要在空处起新体"）。 */
        REFUSE_MISSED_BODY
    }

    /**
     * 创建 / 扩增的分流（纯逻辑 ⇒ 离线有真值表，见 {@code SpaceBuildRulesProbe} 第 5 节）。
     *
     * <p><b>为什么"周围有体"时要拒绝，而不是就近吸附到那个体</b>：吸附看着手感好，但它的表现正是
     * "瞄着结构旁边的空气也能贴上去" —— 就是用户说的"点方块前面的空气进行延申搭路"。
     * 用户拍板选了严格版：<b>扩增必须精确瞄中体的面</b>；吸附方案被明确否掉。</p>
     *
     * <p>反过来，只有"周围完全没有体"才允许创建：这时玩家确实是在空处，无处可瞄，
     * 给他一个 {@link #PLACE_DISTANCE} 格的落点才能凭空起第一块。</p>
     *
     * @param bodyHit          射线是否命中了物理体
     * @param bodyWithinReach  {@code REACH} 内是否存在任何物理体（命中与否都算）
     */
    public static Placement targetKind(boolean bodyHit, boolean bodyWithinReach) {
        if (bodyHit) {
            return Placement.GROW;
        }
        return bodyWithinReach ? Placement.REFUSE_MISSED_BODY : Placement.CREATE;
    }

    /** 被拒绝时的玩家可见文案（{@link Placement} 那一半）。 */
    public static String message(Placement placement) {
        return switch (placement) {
            case REFUSE_MISSED_BODY -> "没瞄到方块：加块要瞄在体的面上（周围没有体时才允许在空处起新块）";
            case GROW, CREATE -> "";
        };
    }

    /**
     * 把体<b>局部</b>的方块面方向转到<b>世界</b>方向（纯数学，joml）。
     *
     * <p>为什么必须转：物理体被牵引枪转过之后，命中面是<b>体局部</b>的
     * （{@code PhysicalRaycast.Hit.localFace()}），直接用会把方块放到体的另一侧。
     * 离线判据里专有一组"体绕 Y 转 90°"的用例钉这个。</p>
     *
     * @param sx/sy/sz 局部面方向的三个分量（每个 ∈ {-1,0,1}）
     * @param rotation 刚体世界旋转（会被复制，不修改入参）
     * @return 世界方向分量（每个 ∈ {-1,0,1}）；旋转为 null 时原样返回
     */
    public static int[] rotateFace(int sx, int sy, int sz, Quaterniond rotation) {
        if (rotation == null) {
            return new int[]{sx, sy, sz};
        }
        Vector3f v = new Quaterniond(rotation).transform(new Vector3f(sx, sy, sz));
        return new int[]{Math.round(v.x), Math.round(v.y), Math.round(v.z)};
    }
}
