package com.mss.polymech.client.space;

import com.mss.polymech.client.physics.ClientPhysicsWorld;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.client.player.LocalPlayer;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;

/**
 * 舒适层：<b>滚转回正</b>（horizon assist）。玩家是人，不是载具 —— 第一人称下长时间歪着飞
 * 是最典型的致晕源（视-前庭冲突，且滚转是最敏感的一轴），而"没有稳定参考"还会额外造成迷向。
 *
 * <p><b>为什么它不再带回极点</b>：输入层（{@link SpacePlayerData#turn(double, double)}）已经改成
 * 绕视线自己的局部轴，任何姿态都能摆视线、不存在退化轴；回正只是<b>姿态上的后处理</b>——
 * 绕视线轴把"屏幕 up"朝参考 up 拧一点。旧设计是把"保持水平"实现成"绕世界竖轴偏航"，
 * 那才会在视线与竖轴平行时退化成画面空转。</p>
 *
 * <p><b>参考 up 的分区</b>：</p>
 * <ol>
 *   <li>有重力（行星/大气）：参考 = 重力方向（我们的重力恒沿 −Y 缩放，故为世界竖直上）；</li>
 *   <li>零重力 + 脚下（从脚底沿玩家自己的"下"，{@link #FLOOR_MAX_DIST} 格内）有物理体表面：
 *       参考 = 该命中面的朝外法线（人站在甲板上就以甲板为下）；</li>
 *   <li>零重力 + 什么都没有（深空）：默认开 <b>深空飞行辅助</b>（{@link #toggleFlightAssist()}，
 *       V 键切换）—— 以世界竖直为参考、做<b>快速的滚转锁定</b>（松手 Z/C 后约 0.2~0.5 秒内
 *       弹回水平），让你几乎来不及迷向。仍受 {@code rollKeyActive} 让路与极点淡出约束；
 *       想完全自由飞、或把某个方向当"上"时，按 V 关掉即可。</li>
 * </ol>
 *
 * <p><b>手动回正：按一下（不是长按）</b>。按下即开始，之后每 tick 自动推进，直到收敛
 * （误差 &lt; {@link #MANUAL_DONE_DEG}）或超过 {@link #MANUAL_MAX_TICKS} 兜底时长；
 * 期间玩家按 Z/C 主动滚会立刻作废（人接管）。</p>
 *
 * <p><b>回正方向：就近</b>。绕视线轴"水平"有两个解 —— 让参考 up 落在屏幕上方（回正）
 * 或落在下方（倒着但同样是水平）。这里选<b>离当前姿态更近</b>的那个，所以倒挂时不会硬转
 * 180° 回正，而是就近靠到"倒挂水平"。切换侧别带 {@link #LEVEL_SIDE_HYSTERESIS_DEG} 迟滞，
 * 避免正好 90° 时来回翻。</p>
 *
 * <p><b>为什么调用点必须在 {@code saveOld()} 之后</b>：相机 {@code SpaceCameraMixin} 用
 * {@code lerp(facingO/leftO → facing/left, partialTick)} 插值。若在 saveOld() 之前回正，
 * 存进 O 的就已经是回正后的值，插值拿不到这一帧的变化 —— 每 tick 一个台阶直接进画面，
 * 就是"20Hz 一顿一顿"。放在之后，O 是回正前、current 是回正后，插值把这一 tick 的
 * 角度摊到每一帧上，才丝滑。</p>
 *
 * <p><b>另一半同样重要</b>：{@code SpaceTurnMixin} 里"输入改变朝向时刷新 O"的那几行
 * <b>必须只在增量非零时执行</b>。{@code Minecraft.runTick()} 每帧都调
 * {@code mouseHandler.handleAccumulatedMovement()}，它在鼠标被 grab 时无条件调
 * {@code turnPlayer()} → {@code Entity.turn(0,0)} —— 鼠标不动也会走到那里。零增量若是也刷 O，
 * 上面这段插值每帧都被抹掉，回正依旧是一顿一顿的（这个坑踩过一次）。</p>
 *
 * <p>参数全部是常量，方便进游戏边飞边调。</p>
 */
public final class LevelAssist {

    // ==================== 可调参数 ====================

    /** 手动回正的按键名（仅文档用；真正的轮询在 {@code SpaceTravelMixin#polymech$handleRollKeys} 里读 {@code InputConstants.KEY_X}）。 */
    public static final String MANUAL_KEY_NAME = "X";
    /** 地板检测：脚正下方多少格内有物理体表面才算"有地板"（格）。 */
    private static final double FLOOR_MAX_DIST = 2.0;
    /** 地板射线起点相对脚底的抬高量（格）：避免正好踩在表面上时起点落在盒内。 */
    private static final double FLOOR_RAY_BIAS = 0.1;
    /** 参考 up 的平滑时间常数（秒）。参考系切换/换面都靠它变成渐变。 */
    private static final double REF_TAU = 0.5;
    /** "回正到上方 / 就近回正到下方"切换的迟滞（度）：防止正好 90° 时来回翻。 */
    private static final double LEVEL_SIDE_HYSTERESIS_DEG = 12.0;

    /** 自动回正角速度上限（度/秒）。参考自己在转时靠它避免"被灌一段强制滚转"。 */
    private static final double MAX_RATE_DEG = 45.0;
    /** 手动回正角速度上限（度/秒）：按一下就要快、要利落，但仍是限速斜坡。 */
    private static final double MANUAL_RATE_DEG = 360.0;
    /**
     * 深空飞行辅助（<b>滚转锁定</b>）的回正角速度上限（度/秒）。
     * <p>从"柔和橡皮筋"改成"快速回弹的假锁定"：松手 Z/C 后约 0.2~0.5 秒内弹回水平，
     * 玩家几乎来不及迷向。仍受 {@code rollKeyActive} 让路（按住 Z/C 自由滚、松手即锁回），
     * 且就近侧别保留 —— 桶滚到倒挂会停在"倒挂水平"而不是硬翻 180°。
     * 关掉 {@link #flightAssistEnabled} 就是纯自由 6DOF。</p>
     */
    private static final double DEEP_SPACE_RATE_DEG = 200.0;
    /**
     * 深空飞行辅助（滚转锁定）的比例增益（每 tick）。
     * <p>误差 20° 时一步回 ~14°/tick（约 280°/s，被限速截到 200°/s）——
     * 收得果断但不硬停（比例项让收尾自然减速）。</p>
     */
    private static final double DEEP_SPACE_GAIN = 0.70;
    /**
     * 比例增益（每 tick）：误差小的时候按比例减速。
     * <p>为什么不用"限速 + 硬死区"：那样是匀速转到离目标一小段时<b>突然停住</b>，收尾很生硬。
     * 比例项让接近过程自然减速、以极小步长收尾，全程没有速度突变。</p>
     */
    private static final double AUTO_GAIN = 0.35;
    private static final double MANUAL_GAIN = 0.80;
    /** 单步小于这个角度就不动（等价于极小的死区）：避免无止境地做不可见的微调。 */
    private static final double MIN_STEP_DEG = 0.01;
    /** 手动回正判定"到位"的误差（度）。 */
    private static final double MANUAL_DONE_DEG = 0.3;
    /** 手动回正兜底时长（tick）：万一因为淡出/参考变动收敛不了，也不会一直拧。 */
    private static final int MANUAL_MAX_TICKS = 40;

    /** 极点淡出角（度）：视线与参考夹角小于它开始淡出，到 0 完全停手。 */
    private static final double POLE_FADE_DEG = 12.0;
    /** 一个 MC tick 的时长（秒）。 */
    private static final double TICK_DT = 1.0 / 20.0;

    // ==================== 状态 ====================

    private static final double POLE_FADE_RAD = Math.toRadians(POLE_FADE_DEG);
    private static final double LEVEL_SIDE_HYSTERESIS_SIN =
            Math.sin(Math.toRadians(LEVEL_SIDE_HYSTERESIS_DEG));

    /** 平滑后的参考 up（世界系，单位向量）。 */
    private static final Vector3d refUp = new Vector3d(0.0, 1.0, 0.0);
    /** 本 tick 算出的目标参考。 */
    private static final Vector3d target = new Vector3d(0.0, 1.0, 0.0);

    /** 上一 tick 是否有可用的回正参考。用于"刚接管"时把参考从玩家当前 up 起步（不硬切）。 */
    private static boolean refActive;

    /** 手动回正剩余 tick（&gt;0 表示正在回正）。按一下键就开始，跑完自动结束。 */
    private static int manualTicks;
    /** 手动回正刚触发时，"就近侧别"要按当下姿态重新判一次（不受迟滞影响）。 */
    private static boolean manualSideFresh;
    /** 当前回正侧别：+1 = 让参考 up 在屏幕上方（回正），−1 = 就近倒挂水平。 */
    private static int levelSide = 1;

    /** 深空飞行辅助开关（默认开）：零重力且无邻近物理体时，把滚转柔和拉回世界竖直。 */
    private static boolean flightAssistEnabled = true;

    /**
     * 本 tick 玩家主动滚转的角速度（度/tick，符号与 Z/C 一致）；0 = 没按。
     * <p>由输入层每 tick 设置（{@code SpaceTravelMixin#polymech$handleRollKeys}）。
     * 见 {@link #applyCorrectionPerFrame} —— 回正与滚转现在都在渲染帧率上应用。</p>
     */
    private static double playerRollPerTick;
    /** 玩家是否正按着 Z/C 主动滚（每 tick 由 {@link #setPlayerRollInput} 更新）。 */
    private static boolean rollInputActive;

    /** 输入层每 tick 调用：记录玩家主动滚转的角速度（度/tick）。 */
    public static void setPlayerRollInput(double degPerTick) {
        playerRollPerTick = degPerTick;
        rollInputActive = Math.abs(degPerTick) > 1.0e-6;
    }

    // ── 每帧回正的状态（由 tick() 维护参考，applyCorrectionPerFrame() 消费）──
    /** 当前参考是否为深空飞行辅助模式（决定增益/限速，由 tick() 更新）。 */
    private static boolean refIsDeepSpace;
    /** 上一帧应用回正的时间戳（毫秒），用于算帧间隔。 */
    private static long lastCorrectionFrameMs;

    /** HUD 用：当前是否有活动的回正参考（即"自动回正"是否在生效）。 */
    public static boolean hasActiveReference() {
        return refActive && refUp.lengthSquared() > 1.0e-9;
    }

    /** HUD 用：是否正在跑一次手动回正（X 键触发的那次）。 */
    public static boolean isManualLeveling() {
        return manualTicks > 0;
    }

    /** 深空飞行辅助是否开启。 */
    public static boolean isFlightAssistEnabled() {
        return flightAssistEnabled;
    }

    /** 切换深空飞行辅助（V 键）。 */
    public static void toggleFlightAssist() {
        flightAssistEnabled = !flightAssistEnabled;
        // 关闭时清掉半途的辅助回正状态，避免残留的收敛倾向
        if (!flightAssistEnabled) {
            refActive = false;
        }
    }

    /**
     * HUD 姿态仪用：当前参考"上"。
     *
     * @return true = 有活动参考（写入平滑后的 {@code refUp}）；false = 无活动参考（写入世界竖直）
     */
    public static boolean getReferenceUp(Vector3d out) {
        if (refActive && refUp.lengthSquared() > 1e-9) {
            out.set(refUp);
            return true;
        }
        out.set(0.0, 1.0, 0.0);
        return false;
    }

    /** HUD 用（float 版）：当前参考"上"。无活动参考时回世界竖直。 */
    public static boolean getReferenceUp(org.joml.Vector3f out) {
        if (refActive && refUp.lengthSquared() > 1e-9) {
            out.set((float) refUp.x, (float) refUp.y, (float) refUp.z);
            return true;
        }
        out.set(0.0f, 1.0f, 0.0f);
        return false;
    }

    /** 局部 AABB 缓存：body id → {revision, minX, minY, minZ, maxX, maxY, maxZ}。 */
    private static final Map<Long, double[]> BOUNDS = new HashMap<>();

    private LevelAssist() {
    }

    /** 按一下手动回正键时调用：开始一次"自动跑完"的回正。 */
    public static void requestManualLevel() {
        manualTicks = MANUAL_MAX_TICKS;
        manualSideFresh = true;
    }

    /** 玩家主动滚（Z/C）时调用：立刻作废正在跑的手动回正。 */
    public static void cancelManualLevel() {
        manualTicks = 0;
        manualSideFresh = false;
    }

    /**
     * 每 tick 调用一次（太空维度、朝向已初始化）。<b>必须在 {@code SpacePlayerData#saveOld()}
     * 之后调用</b>（Z/C 主动滚转仍在这里应用，见类注释）。
     *
     * <p>职责：<b>维护回正的状态</b> —— 参考 up 选择（重力/船体面/深空辅助/手动目标）与平滑、
     * 侧别迟滞、手动进度、极点淡出判定。它<b>不再直接 rollBody</b>：自动回正与手动回正的
     * 实际应用挪到了 {@link #applyCorrectionPerFrame}（渲染帧率逐帧小步长），
     * 因为 tick 率（20Hz）的离散大台阶在玩家转动视角时（O 向量每帧被刷新）会以 20Hz 进画面，
     * 表现为"一顿一顿"。</p>
     *
     * @param rollKeyActive 本 tick 玩家是否按着 Z/C 主动滚 —— 是则作废手动回正、自动回正也让路
     */
    public static void tick(LocalPlayer player, SpacePlayerData data, boolean rollKeyActive) {
        if (player == null || player.level() == null || data == null || !data.isInitialized()) {
            cancelManualLevel();
            return;
        }
        if (rollKeyActive) {
            cancelManualLevel();
        }
        // 玩家主动滚转（Z/C）：与自动回正、手动回正在**同一条后处理路径**上应用。
        // 本方法在 saveOld() 之后被调用，所以这一步产生的姿态差会被相机 partialTick 插值
        // 摊到每一帧 —— 手感和 X 回正一致。绝不能挪回 tick 开头（那样 O 存的就是滚转后的值，
        // 插值拿不到变化，20Hz 一顿一顿）。
        if (rollKeyActive && Math.abs(playerRollPerTick) > 1.0e-6) {
            data.rollBody(Math.toRadians(playerRollPerTick));
        }
        boolean manual = manualTicks > 0;
        if (manual) {
            manualTicks--;
        }

        Vector3d facing = data.facing();
        Vector3d screenLeft = new Vector3d(data.left()).negate();
        Vector3d playerUp = new Vector3d(facing).cross(screenLeft, new Vector3d());
        if (playerUp.lengthSquared() < 1.0e-12) {
            return;
        }
        playerUp.normalize();

        // ── 1. 参考 up：重力 > 邻近物理体的最近面 > 手动回正 > 深空飞行辅助（世界竖直）──
        boolean hasReference;
        boolean deepSpaceAssist = false;
        if (PlanetDimensions.gravity(player.level().dimension()) > 0.0f) {
            // 有重力：重力就是"上"（我们的重力恒沿 −Y 缩放）
            target.set(0.0, 1.0, 0.0);
            hasReference = true;
        } else if (bodyFaceUp(player, playerUp, target)) {
            hasReference = true;
        } else if (manual) {
            // 深空手动回正：以原版上下为准
            target.set(0.0, 1.0, 0.0);
            hasReference = true;
        } else if (flightAssistEnabled) {
            // 深空飞行辅助：以世界竖直为参考，柔和回正滚转（不夹俯仰，只扶滚转）
            target.set(0.0, 1.0, 0.0);
            hasReference = true;
            deepSpaceAssist = true;
        } else {
            // 深空且没开辅助：自动回正**不做任何事**（太空的"上"由人自己选，别替他拧）
            hasReference = false;
        }
        refIsDeepSpace = deepSpaceAssist;
        if (!hasReference) {
            refActive = false;
            return;
        }

        // ── 2. 参考平滑：参考系切换不做硬切。刚接管时从玩家当前 up 起步 ──
        // 手动回正例外：**直接对准目标**，跳过平滑。否则 refUp 从玩家当前 up 慢慢转回目标，
        // 回正是"追着 refUp 转"，误差一小于阈值就提前收尾 —— refUp 还没到目标，残留几度
        // （这正是手动回正停在正负几度、而自动辅助能完全回平的原因）。
        if (manual) {
            refUp.set(target);
            refActive = true;
        } else {
            if (!refActive) {
                refUp.set(playerUp);
                refActive = true;
                levelSide = 1;
            }
            double alpha = 1.0 - Math.exp(-TICK_DT / REF_TAU);
            refUp.lerp(target, alpha);
            if (refUp.lengthSquared() < 1.0e-9) {
                refUp.set(0.0, 1.0, 0.0);
            }
            refUp.normalize();
        }

        // ── 3. 极点淡出：视线与参考平行时 roll 无定义 ──
        double align = Math.min(1.0, Math.abs(facing.dot(refUp)));
        double angleFromPole = Math.acos(align);
        double fade = Math.min(1.0, angleFromPole / POLE_FADE_RAD);
        fade = fade * fade * (3.0 - 2.0 * fade); // smoothstep
        if (fade <= 1.0e-4) {
            return;
        }
        // 自动回正遇玩家主动滚要让路；手动回正已经在上面被 rollKeyActive 作废
        if (rollKeyActive && !manual) {
            return;
        }

        // ── 4. 水平解：参考投影到视线垂面，再就近选"上方水平 / 下方水平" ──
        double d = refUp.dot(facing);
        Vector3d refP = new Vector3d(
                refUp.x - facing.x * d, refUp.y - facing.y * d, refUp.z - facing.z * d);
        if (refP.lengthSquared() < 1.0e-9) {
            return; // 参考与视线平行（已由淡出处理，这里兜底）
        }
        refP.normalize();
        // playerUp·refP == playerUp·refUp，即"我的上"与参考上的同向程度
        double side = playerUp.dot(refP);
        if (manual && manualSideFresh) {
            // 手动回正：按一下的那一刻就近判一次（不带迟滞），之后才上迟滞
            levelSide = side >= 0.0 ? 1 : -1;
            manualSideFresh = false;
        } else if (levelSide >= 0) {
            if (side < -LEVEL_SIDE_HYSTERESIS_SIN) {
                levelSide = -1;
            }
        } else if (side > LEVEL_SIDE_HYSTERESIS_SIN) {
            levelSide = 1;
        }
        if (levelSide < 0) {
            refP.negate();
        }

        double cosErr = Math.max(-1.0, Math.min(1.0, playerUp.dot(refP)));
        Vector3d cross = playerUp.cross(refP, new Vector3d());
        double errDeg = Math.toDegrees(Math.atan2(cross.dot(facing), cosErr));
        if (manual && Math.abs(errDeg) < MANUAL_DONE_DEG) {
            cancelManualLevel(); // 到位，结束本次手动回正
            return;
        }

        // 实际回正（比例 + 限速 + 极点淡出）**不在这里应用** —— 移到
        // {@link #applyCorrectionPerFrame}，按渲染帧率逐帧做小步长回正。
        // 为什么：tick 率（20Hz）下每 tick 一步最大 10°，而 SpaceTurnMixin 在玩家转动视角时
        // 每帧刷新 O 向量，partialTick 插值窗口被清零 —— 10° 的离散台阶直接以 20Hz 进画面，
        // 就是"转动视角时一顿一顿"。逐帧应用后每步只有 ~3.3°（60fps），连续追踪误差，不再有台阶。
    }

    /**
     * 每帧应用一次回正（渲染帧率，不是 tick 率）。由 {@code SpaceCameraMixin} 在算出相机朝向
     * <b>之前</b>调用（每渲染帧一次，步长按帧间隔缩放）。
     *
     * <p>参考 up（重力/船体面/深空辅助/手动目标）、侧别与手动进度都由 {@link #tick} 维护，
     * 这里只做<b>应用</b>：对当前姿态重算误差，走同一套"比例 + 限速 + 极点淡出"模型。
     * 因为误差是逐帧重算的（玩家转视角时它也在变），回正永远追得上，不会像 tick 率那样
     * 出现 20Hz 的方向抖动台阶。</p>
     */
    public static void applyCorrectionPerFrame(SpacePlayerData data) {
        if (data == null || !data.isInitialized() || !refActive) {
            return;
        }
        boolean manual = manualTicks > 0;
        if (rollInputActive && !manual) {
            return; // 玩家正按着 Z/C 主动滚，让路
        }

        // ── 帧间隔：上限 50ms（=2 tick），帧率掉到 20 以下也不会一步跨太大 ──
        long now = net.minecraft.Util.getMillis();
        double frameDt = lastCorrectionFrameMs == 0L ? TICK_DT
                : Math.max(0.0, Math.min(0.05, (now - lastCorrectionFrameMs) / 1000.0));
        lastCorrectionFrameMs = now;
        double dtRatio = frameDt / TICK_DT; // 1.0 = 20fps；0.33 = 60fps

        Vector3d facing = data.facing();
        Vector3d screenLeft = new Vector3d(data.left()).negate();
        Vector3d playerUp = new Vector3d(facing).cross(screenLeft, new Vector3d());
        if (playerUp.lengthSquared() < 1.0e-12) {
            return;
        }
        playerUp.normalize();

        // ── 极点淡出（每帧重算，便宜）──
        double align = Math.min(1.0, Math.abs(facing.dot(refUp)));
        double angleFromPole = Math.acos(align);
        double fade = Math.min(1.0, angleFromPole / POLE_FADE_RAD);
        fade = fade * fade * (3.0 - 2.0 * fade); // smoothstep
        if (fade <= 1.0e-4) {
            return;
        }

        // ── 水平解：就近侧别由 tick() 维护的 levelSide 决定 ──
        double d = refUp.dot(facing);
        Vector3d refP = new Vector3d(
                refUp.x - facing.x * d, refUp.y - facing.y * d, refUp.z - facing.z * d);
        if (refP.lengthSquared() < 1.0e-9) {
            return; // 参考与视线平行（已由淡出处理）
        }
        refP.normalize();
        if (levelSide < 0) {
            refP.negate();
        }

        double cosErr = Math.max(-1.0, Math.min(1.0, playerUp.dot(refP)));
        Vector3d cross = playerUp.cross(refP, new Vector3d());
        double errDeg = Math.toDegrees(Math.atan2(cross.dot(facing), cosErr));
        if (manual && Math.abs(errDeg) < MANUAL_DONE_DEG) {
            cancelManualLevel(); // 到位，结束本次手动回正
            return;
        }

        // ── 比例 + 限速（按帧间隔缩放，动力等效于 tick 率版本）──
        double gain = manual ? MANUAL_GAIN : (refIsDeepSpace ? DEEP_SPACE_GAIN : AUTO_GAIN);
        double maxStepDeg = (manual ? MANUAL_RATE_DEG
                : (refIsDeepSpace ? DEEP_SPACE_RATE_DEG : MAX_RATE_DEG)) * frameDt;
        double rawStepDeg = errDeg * gain * dtRatio;
        double stepDeg = Math.max(-maxStepDeg, Math.min(maxStepDeg, rawStepDeg)) * fade;
        if (Math.abs(stepDeg) < MIN_STEP_DEG) {
            return;
        }
        // rollBody：绕视线前方轴滚 left（facing 不动）→ 身体随后重算，视线一格不偏
        data.rollBody(Math.toRadians(stepDeg));
    }

    /**
     * 物理体表面参考（v2，重写）：<b>脚下优先，其次"最近的那个面"</b>。
     *
     * <p>为什么要重写：v1 只看"脚正下方 2 格内的细射线"，于是<b>贴着舰体侧面</b>时
     * 永远取不到参考（射线打的是脚下，侧面不在路径上）——
     * 零重力下贴着大块结构悬停，身体就只会退回世界竖直，看起来"回正很不智能"。</p>
     *
     * <p>现在的判定顺序：</p>
     * <ol>
     *   <li><b>脚下</b>（沿玩家自己的"下"，{@link #FLOOR_MAX_DIST} 格内，且法线与 up 同侧）
     *       —— 站着/贴近地板时最稳，优先。</li>
     *   <li><b>最近表面</b>：在 {@link #SURFACE_MAX_DIST} 格内找"方块包围盒离玩家最近的物理体"，
     *       朝它的最近点打一条 Rapier 射线，用<b>真表面法线</b>当参考"上"。
     *       这一步**不加"必须朝上"的角度闸门** —— 贴着侧壁/天花板时，那个面就是你的地板
     *       （这正是"站在左侧那个靠近我的面上"要的行为）。</li>
     * </ol>
     *
     * <p>几何全部走 Rapier 原生射线（真体素 + 100Hz 实时姿态），AABB 只用来决定"往哪个方向打"。</p>
     *
     * @return true = 命中并写入 out；false = 附近没有（够近的）可当地板的表面
     */
    private static boolean bodyFaceUp(LocalPlayer player, Vector3d playerUp, Vector3d out) {
        long world = com.mss.polymech.client.physics.ClientPhysics.worldHandle();
        if (world <= 0 || !com.mss.polymech.physics.PhysicsNatives.hasTier1()) {
            return false;
        }
        // ── ① 脚下优先 ──
        // 起点从脚底**沿自己的"下"再退一点**：正好踩在表面上时起点会落在实体面上，
        // Rapier 的 solid 射线会给出 toi=0 + 不可靠法线；退一点就稳定命中"进面"。
        double fx = player.getX() - playerUp.x * FLOOR_RAY_BIAS;
        double fy = player.getY() - playerUp.y * FLOOR_RAY_BIAS;
        double fz = player.getZ() - playerUp.z * FLOOR_RAY_BIAS;
        if (castSurface(world, fx, fy, fz,
                -playerUp.x, -playerUp.y, -playerUp.z, FLOOR_MAX_DIST + FLOOR_RAY_BIAS, out)
                && out.dot(playerUp) >= FLOOR_MIN_ALIGN) {
            return true;
        }
        // ── ② 最近表面（贴着侧壁/天花板时用）──
        return nearestBodyFace(world, player, out);
    }

    /**
     * 朝"最近的物理体表面"打一条射线，把真表面法线当成参考"上"。
     *
     * <p>先用各物理体的方块包围盒算出"哪个体最近、朝哪个方向打"，再用 Rapier 射线拿到
     * 真几何的表面法线 —— 包围盒只当指路牌，不参与求交（v1 拿它当碰撞体正是它失效的原因：
     * 站在舱内甲板上时脚底已经在整船包围盒<b>内部</b>，射线被判定为"从盒内出发"而丢弃）。</p>
     */
    private static boolean nearestBodyFace(long world, LocalPlayer player, Vector3d out) {
        // 起点取身体中心（脚底往上半个身高）：贴墙时它离墙面更近、也更不容易埋进方块里
        double px = player.getX();
        double py = player.getY() + player.getBbHeight() * 0.5;
        double pz = player.getZ();

        double bestDist = Double.MAX_VALUE;
        double bx = 0.0;
        double by = 0.0;
        double bz = 0.0;
        for (ClientPhysicsWorld.ClientBody body : ClientPhysicsWorld.bodies()) {
            double[] b = localBounds(body);
            if (b == null) {
                continue;
            }
            org.joml.Quaternionf q = new org.joml.Quaternionf(body.qx(), body.qy(), body.qz(), body.qw());
            if (q.lengthSquared() < 1.0e-9) {
                continue;
            }
            q.normalize();
            // 世界 → 体局部（刚体原点就是 body.tickX/Y/Z）
            org.joml.Quaternionf qc = new org.joml.Quaternionf(q).conjugate();
            Vector3d local = new Vector3d(px - body.tickX(), py - body.tickY(), pz - body.tickZ());
            qc.transform(local);
            // 局部最近点 = 各轴夹到包围盒内
            double cx = Math.min(Math.max(local.x, b[1]), b[4]);
            double cy = Math.min(Math.max(local.y, b[2]), b[5]);
            double cz = Math.min(Math.max(local.z, b[3]), b[6]);
            Vector3d closest = new Vector3d(cx, cy, cz);
            q.transform(closest);
            closest.add(body.tickX(), body.tickY(), body.tickZ());
            double dx = closest.x - px;
            double dy = closest.y - py;
            double dz = closest.z - pz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < bestDist) {
                bestDist = dist;
                bx = dx;
                by = dy;
                bz = dz;
            }
        }
        if (bestDist > SURFACE_MAX_DIST || bestDist < 1.0e-6) {
            return false;
        }
        double inv = 1.0 / bestDist;
        // 打到真表面上：射程留一点余量，避免"最近点恰好在包围盒角上"时差一点点打空
        return castSurface(world, px, py, pz,
                bx * inv, by * inv, bz * inv, bestDist + SURFACE_RAY_SLACK, out);
    }

    /**
     * 用 Rapier 射线取表面法线（朝玩家的一侧 = 参考"上"）。
     *
     * <p>查询组 {@code (2, 5)} 是 space 的玩家组：能打地形与物理体，
     * 且<b>打不到玩家自己那一对盒子</b>（自己的 mem 是 2 / 5，与 filter 5 相与为 0）。</p>
     */
    private static boolean castSurface(long world, double ox, double oy, double oz,
                                       double dx, double dy, double dz, double reach, Vector3d out) {
        double[] hit = new double[5];
        if (!com.mss.polymech.physics.NativePhysics.worldCastRay(world,
                ox, oy, oz, dx, dy, dz, reach, 2, 5, hit)) {
            return false;
        }
        out.set(hit[1], hit[2], hit[3]);
        if (out.lengthSquared() < 1.0e-9) {
            // 法线退化（起点埋进实体）→ 用"背向射线方向"兜底：那正是面对玩家的那一侧
            out.set(-dx, -dy, -dz);
        }
        out.normalize();
        return true;
    }

    /**
     * "最近表面"参考的最大距离（格）。
     *
     * <p>3.0：身体（半高 0.9）贴上去、或离面 3 格以内才认这个面。
     * 调大 → 巡航时也会被旁边的舰体"吸"过去（更灵敏但更抢镜）；
     * 调小 → 必须真的贴住才生效。想更灵敏改这一个数即可。</p>
     */
    private static final double SURFACE_MAX_DIST = 3.0;

    /** 打"最近表面"时射程的额外余量（格）：抵消"最近点落在包围盒角上"的误差。 */
    private static final double SURFACE_RAY_SLACK = 0.5;

    /**
     * 地板面法线与玩家"上"的最小同向度（cos）—— **只用于①脚下那条射线**。
     *
     * <p>0.25 ≈ 75°：斜坡、微倾甲板照样算地板；侧壁（水平法线，dot≈0）被 ① 挡掉
     * （它会走②"最近表面"，那是刻意的）。</p>
     */
    private static final double FLOOR_MIN_ALIGN = 0.25;

    /**
     * 【已废弃】物理体的局部 AABB。
     *
     * <p>v1 拿它<b>当碰撞体</b>做 slab 求交，这是"贴着舰体取不到参考面"的根因
     * （站在舱内甲板上时脚底已在整船包围盒内部 → `tmin &lt; 0` → 命中被丢弃）。
     * v2 只把它当"往哪个方向打射线"的指路牌，求交一律走 Rapier 真几何。
     * 现在仅 {@code nearestBodyFace} 用它。</p>
     */
    private static double[] localBounds(ClientPhysicsWorld.ClientBody body) {
        var blocks = body.blocks();
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        double[] cached = BOUNDS.get(body.id());
        if (cached != null && (int) cached[0] == body.revision()) {
            return cached;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (ClientPhysicsWorld.BlockEntry block : blocks) {
            minX = Math.min(minX, block.dx());
            minY = Math.min(minY, block.dy());
            minZ = Math.min(minZ, block.dz());
            maxX = Math.max(maxX, block.dx() + 1.0);
            maxY = Math.max(maxY, block.dy() + 1.0);
            maxZ = Math.max(maxZ, block.dz() + 1.0);
        }
        if (BOUNDS.size() > 256) {
            BOUNDS.clear(); // 简单上限：物理体 id 回收后不会有陈旧条目堆积
        }
        double[] bounds = {body.revision(), minX, minY, minZ, maxX, maxY, maxZ};
        BOUNDS.put(body.id(), bounds);
        return bounds;
    }
}
