package com.mss.polymech.space;

import com.mss.polymech.Polymech;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.joml.Vector3d;

import java.util.function.Supplier;

/**
 * 太空自由旋转：facing/left 双向量方案。
 *
 * <p>完整 6DOF 朝向由两个正交单位向量表示：
 * facing = 视线方向，left = 头部左侧方向。
 * 没有欧拉角奇点，roll 通过 facing/left 的相对关系自然表达。
 * yaw/pitch/roll 仅作为"为 vanilla 管线计算的输出"，每帧从向量推导，
 * 并用连续性约束消除符号跳变。</p>
 */
public final class SpacePlayerData {

    public static final DeferredRegister<AttachmentType<?>> REGISTRAR =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Polymech.MOD_ID);

    public static final Supplier<AttachmentType<SpacePlayerData>> TYPE =
            REGISTRAR.register("space_rotation", () ->
                    AttachmentType.<SpacePlayerData>builder(SpacePlayerData::new).build());

    /** 视线方向（单位向量）。初始朝 MC 默认方向 +Z */
    private final Vector3d facing = new Vector3d(0, 0, 1);
    /** 头部左侧方向（单位向量，与 facing 正交）。初始 +X 旋转后对应 left */
    private final Vector3d left = new Vector3d(1, 0, 0);

    /** 上一帧的 facing/left（渲染插值用） */
    private final Vector3d facingO = new Vector3d(0, 0, 1);
    private final Vector3d leftO = new Vector3d(1, 0, 0);

    /** 上帧计算出的 roll（用于连续性约束，防止符号跳变） */
    private double lastRoll = 0;
    /** 上帧计算出的 yaw（用于 yaw 连续性约束） */
    private double lastYaw = 0;

    private boolean initialized = false;

    // ==================== 连续头部角（第一人称手专用） ====================
    //
    // 用 (yaw,pitch) 欧拉角表示视线时，同一个朝向有**两个等价解**：
    //     (yaw, pitch)  与  (yaw + 180, 180 − pitch)
    // 外加 pitch ± 360k。vanilla 的 Entity.turn 把 pitch 夹在 ±90，所以视线一过极点，
    // 它就会从一支跳到另一支：**方向是连续的，但角度值一帧跳 180°**。
    //
    // 这对"只看朝向"的消费者无害（相机走 facing 基底，见 SpaceCameraMixin），
    // 但任何**对角度的值做差/平滑**的消费者都会被打断 —— 第一人称的手正是如此：
    //     ItemInHandRenderer#renderHandsWithItems:
    //         f2/f3 = lerp(pt, xBobO/xBob, …)          ← 每 tick 追 50% 的平滑角
    //         mulPose(Rx((getViewXRot(pt) − f2) * 0.1)) ← "手滞后于视角"的量
    //         mulPose(Ry((getViewYRot(pt) − f3) * 0.1))
    // yaw 一帧跳 180° 时，这一项瞬间变成 0.1 × 180 = 18°，几 tick 后再弹回来 ——
    // 表现就是"准星划过视野球正上/正下极时，手里的东西突然抽一下"。
    //
    // 这里维护一条**不跳的连续分支**（pitch 允许越过 ±90 继续走），并自带一份
    // 50%/tick 的平滑值（语义与 vanilla 的 xBob/yBob 完全一致，只是同源、不跳）。
    // 手那边由 SpaceFirstPersonHandMixin 把 vanilla 的这两项换成同源版本。

    /** 连续头部角（度）：pitch 可越过 ±90。 */
    private double contYaw = 0;
    private double contPitch = 0;
    /** 上一 tick 的连续角（partialTick 插值用，对应 vanilla 的 yRotO/xRotO）。 */
    private double contYawO = 0;
    private double contPitchO = 0;
    /** 手的平滑角（= vanilla 的 xBob/yBob，50%/tick 追连续角）。 */
    private double handYawLag = 0;
    private double handPitchLag = 0;
    private double handYawLagO = 0;
    private double handPitchLagO = 0;

    /** 连续头部角（度），供第一人称手使用。 */
    public float contYawLerped(float partialTick) {
        return (float) (contYawO + (contYaw - contYawO) * partialTick);
    }

    public float contPitchLerped(float partialTick) {
        return (float) (contPitchO + (contPitch - contPitchO) * partialTick);
    }

    /** 手的平滑角（度），对应 vanilla 的 lerp(xBobO, xBob, pt)。 */
    public float handYawLagLerped(float partialTick) {
        return (float) (handYawLagO + (handYawLag - handYawLagO) * partialTick);
    }

    public float handPitchLagLerped(float partialTick) {
        return (float) (handPitchLagO + (handPitchLag - handPitchLagO) * partialTick);
    }

    /**
     * 用当前视线刷新连续角：在两个等价欧拉解（含 pitch ±360k）里挑**离上一帧最近**的那个。
     * 于是角度本身也连续，过极点不再有 ±180 跳变。
     */
    private void updateContAnglesFromFacing() {
        double yawRaw = -Math.atan2(facing.x, facing.z) * TODEG;
        double pitchRaw = -Math.asin(Math.max(-1.0, Math.min(1.0, facing.y))) * TODEG;
        double bestYaw = contYaw;
        double bestPitch = contPitch;
        double bestCost = Double.MAX_VALUE;
        for (int branch = 0; branch < 2; branch++) {
            double yaw = branch == 0 ? yawRaw : yawRaw + 180.0;
            double pitch = branch == 0 ? pitchRaw : 180.0 - pitchRaw;
            // yaw 取离上一帧最近的等价角（等价于 ±360k）
            double yawRep = contYaw + wrapDeg(yaw - contYaw);
            for (int k = -1; k <= 1; k++) {
                double p = pitch + 360.0 * k;
                double cost = Math.abs(yawRep - contYaw) + Math.abs(p - contPitch);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestYaw = yawRep;
                    bestPitch = p;
                }
            }
        }
        contYaw = bestYaw;
        contPitch = bestPitch;
    }

    /**
     * tick 末尾调用一次：连续角快照给 O（partialTick 插值），并让手的平滑角追一格 50%。
     * 与 vanilla 在 {@code LocalPlayer#serverAiStep} 里更新 xBob/yBob 的时机/比例一致。
     */
    public void advanceHandAngles() {
        contYawO = contYaw;
        contPitchO = contPitch;
        handYawLagO = handYawLag;
        handPitchLagO = handPitchLag;
        handYawLag += (contYaw - handYawLag) * 0.5;
        handPitchLag += (contPitch - handPitchLag) * 0.5;
    }

    /** 角度归一化到 (-180, 180]，用于"最近的等价角"。 */
    private static double wrapDeg(double degrees) {
        double w = degrees % 360.0;
        if (w >= 180.0) w -= 360.0;
        if (w < -180.0) w += 360.0;
        return w;
    }

    // ── 访问 ──
    public Vector3d facing()  { return facing; }
    public Vector3d left()    { return left; }
    public Vector3d facingO() { return facingO; }
    public Vector3d leftO()   { return leftO; }

    public double getLastRoll() { return lastRoll; }
    public void setLastRoll(double v) { lastRoll = v; }
    public double getLastYaw() { return lastYaw; }
    public void setLastYaw(double v) { lastYaw = v; }

    public boolean isInitialized() { return initialized; }

    /**
     * 应用网络同步过来的朝向（远端玩家 / 服务端副本）。
     *
     * <p>身体与视线分开同步：两端都拿到身体基底与视线基底（身体给模型/碰撞箱，视线给移动方向/头部朝向），
     * 不必从一方反推另一方 —— 反推在俯仰 ±90° 附近会退化。</p>
     */
    public void applyRemote(double bodyFx, double bodyFy, double bodyFz,
                            double bodyLx, double bodyLy, double bodyLz,
                            double viewFx, double viewFy, double viewFz,
                            double viewLx, double viewLy, double viewLz) {
        saveOld();
        bodyFacing.set(bodyFx, bodyFy, bodyFz);
        bodyLeft.set(bodyLx, bodyLy, bodyLz);
        facing.set(viewFx, viewFy, viewFz);
        left.set(viewLx, viewLy, viewLz);
        orthonormalizeBody();
        orthonormalize();
        initialized = true;
        // 远端朝向整包换掉：连续角跟着重挑（本地玩家的手不用它，但保持一致）
        updateContAnglesFromFacing();
    }

    /** 让身体基底严格正交归一（网络/存档送进来的向量不保证）。 */
    private void orthonormalizeBody() {
        bodyFacing.normalize();
        double d = bodyLeft.dot(bodyFacing);
        bodyLeft.sub(bodyFacing.x * d, bodyFacing.y * d, bodyFacing.z * d);
        if (bodyLeft.lengthSquared() < 1e-12) {
            Vector3d arbitrary = Math.abs(bodyFacing.y) < 0.99
                    ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0);
            bodyLeft.set(arbitrary.cross(bodyFacing, new Vector3d()));
        }
        bodyLeft.normalize();
    }

    /** 每 tick 末尾保存旧向量（插值用）。 */
    public void saveOld() {
        facingO.set(facing);
        leftO.set(left);
        bodyFacingO.set(bodyFacing);
        bodyLeftO.set(bodyLeft);
    }

    /**
     * 用 vanilla 实体当前的 yaw/pitch 初始化向量（进入太空维度时调用一次）。
     * roll 初始为 0。
     */
    public void initFromVanilla(float yawDeg, float pitchDeg) {
        double pitchRad = pitchDeg * Math.PI / 180.0;
        double yawRad = -yawDeg * Math.PI / 180.0;
        double sy = Math.sin(yawRad), cy = Math.cos(yawRad), cp = Math.cos(pitchRad);
        facing.set(sy * cp, -Math.sin(pitchRad), cy * cp);

        left.set(1, 0, 0);
        left.rotateX(-pitchRad);
        left.rotateY(-(yawDeg + 180) * Math.PI / 180.0);
        // 正交化保险
        orthonormalize();

        // 身体基底与视线一致起步，头部偏角归零
        bodyFacing.set(facing);
        bodyLeft.set(left);
        bodyFacingO.set(bodyFacing);
        bodyLeftO.set(bodyLeft);
        headYaw = 0.0;
        headPitch = 0.0;

        facingO.set(facing);
        leftO.set(left);
        lastYaw = yawDeg;
        lastRoll = 0;

        // 连续角与手的平滑角一并归位 —— 否则传送/切维度后的第一帧，
        // 手会从旧角度"甩"到新角度（0.1 × 巨大差值）。
        contYaw = yawDeg;
        contPitch = pitchDeg;
        contYawO = contYaw;
        contPitchO = contPitch;
        handYawLag = contYaw;
        handPitchLag = contPitch;
        handYawLagO = handYawLag;
        handPitchLagO = handPitchLag;

        initialized = true;
    }

    /**
     * 把 6DOF 基底（facing / left）转成一个刚体姿态四元数。
     *
     * <p>用途：物理碰撞体要**跟着玩家朝向走** —— 滚转/俯仰之后碰撞箱也要跟着转，
     * 否则人看着是横的、碰撞箱还立着（穿不过该穿过的缝、又卡在该过的地方）。
     * 约定与 MC 实体局部轴一致：局部 +Z = 前、+Y = 上、+X = 左。</p>
     */
    public org.joml.Quaternionf orientation(org.joml.Quaternionf out) {
        return basisToQuat(bodyFacing, bodyLeft, out);
    }

    /** 上一帧的身体姿态（渲染插值用）。 */
    public org.joml.Quaternionf orientationOld(org.joml.Quaternionf out) {
        return basisToQuat(bodyFacingO, bodyLeftO, out);
    }

    // ==================== 超人姿态（钻一格洞） ====================

    /**
     * 该实体此刻是否处于"超人姿态"（太空维度 + 疾跑冲刺中）。
     *
     * <p><b>已按需求禁用（恒 false）</b>：超人姿态（疾跑趴平 + 碰撞箱缩成 0.6³ 头盒钻洞）
     * 整套功能已停用，下面的常量/方法只是留档。恢复时把本方法改回原来的
     * "太空维度 + isSprinting()" 派生即可。</p>
     */
    public static boolean isSuperman(net.minecraft.world.entity.Entity entity) {
        return false;
    }

    // ==================== 超人姿态的头盒 ====================
    //
    // 注意：普通姿态<b>没有任何自定义碰撞箱</b>（走原版 0.6×1.8）；
    // 曾经的"旋转身体盒外包盒 / 宽度减半 / 头盒+下半身双盒"等方案都已废弃。

    /** 超人姿态头盒半尺寸：0.6³ → 半 0.3。 */
    public static final double HEAD_BOX_HALF = 0.3;
    /**
     * 超人姿态头盒中心相对实体原点（脚底基准）的竖直高度 = <b>1.6</b>。
     *
     * <p>与 {@link #SUPERMAN_CENTER_HEIGHT} 取同一个值，于是物理刚体中心与头盒中心重合 ——
     * 那个 0.6³ 碰撞体本身就是头盒，不需要再挂第二个碰撞体。</p>
     *
     * <p>取 1.6 而不是 1.62：让"相机高度 = 头盒中心"精确成立（相机在盒正中心，
     * 撞墙是"脸撞盒壁"，头不会伸进方块）；相对 1.62 只差 0.02 格。</p>
     */
    public static final double HEAD_BOX_CENTER = 1.6;

    /**
     * 超人姿态"身体碰撞盒"中心相对实体原点（脚底基准）的竖直偏移 = 1.6（与头盒中心重合）。
     *
     * <p><b>为什么是 1.6、而不是原版滑翔位姿的 0.4</b>：0.4 是"整个身体缩进 0.6 高盒子"时的高度，
     * 相对 1.8 高的玩家模型正好落在<b>小腿</b>上 —— 看上去就是"盒子还在脚上"。
     * 这里要的是<b>只包住头、身体悬在盒外</b>：头在 1.6，盒子 0.6 高 → 覆盖 1.3~1.9，正好裹住整个头。</p>
     *
     * <p>顺带的两个好处：</p>
     * <ul>
     *   <li>第一人称相机 = 实体位置 + {@code getEyeHeight()}，普通 1.62 / 超人 1.6
     *       → 切换姿态时视角几乎不跳；且相机落在头盒正中心，撞墙是"脸撞盒壁"，头不会伸进方块（穿模）。</li>
     *   <li>{@code SpaceBodyTiltMixin} 以本偏移为支点做"趴平"旋转 →
     *       支点在头，趴平后身体<b>从头部向后平铺</b>，正是超人"头在前、身体在后"的形态。</li>
     * </ul>
     *
     * <p>与头盒中心取同一个值，于是超人姿态的那一个 0.6³ 碰撞体<b>本身就是头盒</b>
     * （物理头盒偏移 = 1.6 − 1.6 = 0）。</p>
     */
    public static final double SUPERMAN_CENTER_HEIGHT = HEAD_BOX_CENTER;

    /**
     * "实体原点 → 物理碰撞体中心"的竖直偏移。
     *
     * <p>普通姿态 = 半高（盒子坐底在脚底，与 vanilla 逐位一致）；
     * 超人姿态 = 眼高（头盒中心在头部，见 {@link #SUPERMAN_CENTER_HEIGHT}）。</p>
     *
     * <p>物理建体与位置回写必须<b>同源</b>使用这个值，否则玩家位置会整体漂移。</p>
     */
    public static double bodyCenterOffset(net.minecraft.world.entity.Entity entity) {
        if (isSuperman(entity)) {
            return SUPERMAN_CENTER_HEIGHT;
        }
        return entity.getBbHeight() * 0.5;
    }

    /** 身体姿态四元数，做上一帧→本帧的 slerp（近反向时直接用本帧，避免扫过 180°）。 */
    public org.joml.Quaternionf bodyQuat(float partialTick, org.joml.Quaternionf out) {
        org.joml.Quaternionf now = orientation(new org.joml.Quaternionf());
        org.joml.Quaternionf old = orientationOld(new org.joml.Quaternionf());
        if (old.dot(now) < -0.9999f) {
            return out.set(now);
        }
        return out.set(old).slerp(now, partialTick);
    }

    // ==================== 物理真实姿态 ====================

    /**
     * Rapier 刚体的真实姿态（客户端每帧读回，服务端每 tick 读回）。
     *
     * <p>身体不再每帧被硬写姿态，而是用"角速度伺服"朝运动学目标转 —— 这样<b>碰撞接触力能真的把它顶偏</b>：
     * 钻洞口时身体会被障碍物挤着转向，直到整体足以通过。渲染和头部偏移都用这个真实姿态，
     * 否则你会看到"碰撞箱被顶转了、模型还停在原地"。</p>
     */
    private final org.joml.Quaternionf physicalBody = new org.joml.Quaternionf();
    private boolean hasPhysicalBody = false;

    public void setPhysicalBodyQuat(double x, double y, double z, double w) {
        physicalBody.set((float) x, (float) y, (float) z, (float) w);
        hasPhysicalBody = true;
    }

    public void clearPhysicalBodyQuat() {
        hasPhysicalBody = false;
    }

    public boolean hasPhysicalBodyQuat() {
        return hasPhysicalBody;
    }

    /** 物理真实姿态（诊断用）。 */
    public boolean physicalBodyQuat(org.joml.Quaternionf out) {
        if (!hasPhysicalBody) {
            return false;
        }
        out.set(physicalBody);
        return true;
    }

    /** 渲染用身体姿态：对物理真实姿态做低通，滤掉接触解算的高频抖动，保留被顶转的大趋势。 */
    private final org.joml.Quaternionf renderBody = new org.joml.Quaternionf();
    private boolean hasRenderBody = false;

    public void clearRenderBody() {
        hasRenderBody = false;
    }

    /**
     * 渲染用身体姿态：<b>运动学基底</b>（"视线 − 颈部领先量"的结果，逐帧更新、逐帧存旧值、按 partialTick 插值）。
     *
     * <p>为什么不用 Rapier 刚体的真实姿态：它只在物理步进线程里按 100Hz 更新，
     * 而"身体正在持续转动"的姿态会把这个采样率暴露出来 —— 人物每 2~3 帧才动一次，
     * 看起来就是"帧数低/抽搐"。站立时身体基本静止，所以那种情况更容易被忽略。</p>
     *
     * <p>物理刚体照旧由角速度伺服驱动、照样被障碍物顶偏，碰撞判定用的也是它 ——
     * 只是"画出来的身体"走运动学姿态（这也是之前第二/第三人称抽搐的修法）。</p>
     */
    public org.joml.Quaternionf bodyQuatForRender(float partialTick, org.joml.Quaternionf out) {
        return bodyQuat(partialTick, out);
    }

    /**
     * 身体坐标系里的"眼睛位置"偏移 → 世界偏移（第三人称镜头锚点用）。
     *
     * <p>相机转的是<b>头</b>的朝向，所以第三人称/正面本来就该站在"头的前后方"：
     * 锚点放在头上，原版再沿视线方向后退，镜头就正对头部 —— 人物永远在画面中心。</p>
     */
    public Vector3d eyeOffset(double eyeHeight, float partialTick, Vector3d out) {
        org.joml.Vector3f local = new org.joml.Vector3f(0.0f, (float) eyeHeight, 0.0f);
        bodyQuat(partialTick, new org.joml.Quaternionf()).transform(local);
        return out.set(local.x, local.y, local.z);
    }

    // ==================== 身体 / 头部（颈部锥） ====================

    /**
     * 身体基底（躯干朝向）。头部偏角相对它计算；碰撞体与身体模型用它。
     * <p>{@code facing}/{@code left} 是<b>头部</b>（视线）基底，由"身体 ∘ 头部偏角"推导。</p>
     */
    private final Vector3d bodyFacing = new Vector3d(0, 0, 1);
    private final Vector3d bodyLeft = new Vector3d(1, 0, 0);
    private final Vector3d bodyFacingO = new Vector3d(0, 0, 1);
    private final Vector3d bodyLeftO = new Vector3d(1, 0, 0);

    /** 头部相对身体的偏角（弧度），限制在颈部锥内。 */
    private double headYaw = 0.0;
    private double headPitch = 0.0;

    // ── 颈部活动范围（弧度）──
    // 低头/抬头、左右转头、左右歪头是三组不同的量，不能用一个数兜住：
    // 颈椎屈伸（上下）约 45~60°、旋转（左右转头）约 70~80°、侧倾（歪头）约 40°，
    // 而且仰头通常比低头大一点。这里取"看着自然"的值（比极限略小）。
    // 这几个数同时决定两件事：① 头领先到多少身体才开始跟（超出部分推给身体）；
    //                        ② 头部零件渲染时允许的相对偏角上限。

    /** 抬头（仰头）上限。 */
    public static final double NECK_PITCH_UP = Math.toRadians(30.0);
    /** 低头（屈颈）上限。 */
    public static final double NECK_PITCH_DOWN = Math.toRadians(26.0);
    /** 向右转头上限。 */
    public static final double NECK_YAW_RIGHT = Math.toRadians(40.0);
    /** 向左转头上限。 */
    public static final double NECK_YAW_LEFT = Math.toRadians(42.0);
    /** 向右歪头（侧倾）上限。 */
    public static final double NECK_ROLL_RIGHT = Math.toRadians(15.0);
    /** 向左歪头（侧倾）上限。 */
    public static final double NECK_ROLL_LEFT = Math.toRadians(16.0);

    public Vector3d bodyFacing() {
        return bodyFacing;
    }

    public Vector3d bodyLeft() {
        return bodyLeft;
    }

    public Vector3d bodyFacingO() {
        return bodyFacingO;
    }

    public Vector3d bodyLeftO() {
        return bodyLeftO;
    }

    public double headYaw() {
        return headYaw;
    }

    public double headPitch() {
        return headPitch;
    }

    /**
     * 头部相对身体的<b>完整局部旋转</b>，ZYX 分解 —— 给头部零件渲染用。
     *
     * <p>现在颈部领先量本身就是<b>身体局部轴</b>上的量（{@code Ry(headYaw)·Rx(headPitch)}，
     * 见 {@code rederiveBody()}），所以这里分解出来的横滚项恒为 0，只剩 (低头, 转头, 0)。
     * 保留"分解四元数"而不是直接喂 headYaw/headPitch，是为了让模型侧永远拿到
     * "身体⁻¹·视线"的<b>精确</b>值（身体由物理/运动学推导，可能带微小差异）。</p>
     *
     * <p>分解用 ZYX 是为了配合 {@code ModelPart} 的 {@code rotationZYX(zRot, yRot, xRot)} 组合顺序。</p>
     */
    /**
     * 头部相对身体的完整局部旋转（<b>未夹取</b>）。
     * <p>它的逆就是"身体 → 视线"，所以同步给远端时直接用这个四元数即可精确还原视线。</p>
     */
    public org.joml.Quaternionf headLocalQuat(org.joml.Quaternionf out) {
        org.joml.Quaternionf body = bodyQuatForRender(1.0f, new org.joml.Quaternionf());
        org.joml.Quaternionf head = basisToQuat(facing, left, new org.joml.Quaternionf());
        return out.set(body).conjugate().mul(head);
    }

    public void headLocalEuler(org.joml.Vector3f out) {
        headLocalQuat(new org.joml.Quaternionf()).getEulerAnglesZYX(out);
        // 夹进"脖子做得到"的范围（turn() 里已经夹过一次，这里是渲染侧的保险）。
        // 符号约定（与模型一致）：e.x > 0 = 低头，e.y < 0 = 向右，e.z < 0 = 向右歪。
        out.x = (float) clampRange(out.x, NECK_PITCH_UP, NECK_PITCH_DOWN);
        out.y = (float) clampRange(out.y, NECK_YAW_RIGHT, NECK_YAW_LEFT);
        out.z = (float) clampRange(out.z, NECK_ROLL_RIGHT, NECK_ROLL_LEFT);
    }

    // ==================== 超人姿态的"趴平"模型 ====================

    /**
     * 超人姿态下"趴平"所需的局部俯仰角（度）。
     *
     * <p>本项目 {@code SpaceBodyTiltMixin} <b>整个替换</b>了原版 {@code setupRotations}，
     * 因此原版鞘翅滑翔那一步"把站姿模型绕 X 轴转到趴平"（{@code Rx(-90)}）也被一并吃掉了 ——
     * 光靠 6DOF 身体四元数只会得到"站着往前飞"。这里显式补回这一步。</p>
     *
     * <p>角度取 +90：本 mixin 的镜像约定是总变换 {@code Q·Ry(180)}，
     * 而 {@code Ry(180)·Rx(θ) = Rx(-θ)·Ry(180)}，故在栈里写 {@code +90} 等价于最终世界的 {@code Rx(-90)}
     * （与原版鞘翅一致）。<b>若实测发现身体前后颠倒，把这个值取负即可。</b></p>
     */
    public static final float SUPERMAN_PRONE_PITCH_DEG = 90.0F;

    /**
     * 超人姿态的<b>头部零件</b>局部旋转：相对"趴平后的身体"，让头沿着飞行（视线）方向。
     *
     * <p>为什么必须单独算：趴平之后，身体的局部 +Z（胸口法线）朝下，而头要朝飞行方向。
     * 若不处理，头就跟着身体"一直盯着地面"。这里的做法是把
     * {@link #headLocalQuat}（身体⁻¹·视线）再左乘一个 {@code Rx(-90)}，
     * 换算成"趴平身体 ⁻¹·视线" —— 头于是正好看向飞行方向。</p>
     *
     * <p><b>不夹颈部锥</b>：这里的偏角天然接近 90°，被
     * {@link #headLocalEuler} 的颈部上限（±30°）一夹就会歪掉，所以单独走一条不夹取的路径。</p>
     */
    public void headLocalEulerSuperman(org.joml.Vector3f out) {
        org.joml.Quaternionf hl = headLocalQuat(new org.joml.Quaternionf());
        // hl = 身体⁻¹·视线；左乘 Rx(-90) 得到 (身体·Rx90)⁻¹·视线 = 趴平身体⁻¹·视线
        hl.premul(new org.joml.Quaternionf()
                .rotationX((float) Math.toRadians(-SUPERMAN_PRONE_PITCH_DEG)));
        hl.getEulerAnglesZYX(out);
    }

    // ==================== 碰撞箱（原版 AABB） ====================
    //
    // 曾经的"旋转身体盒最小包围 AABB / 按躺平程度收缩"两套动态算法都已移除：
    // 碰撞箱现在就是下面的固定 0.6³ 盒子（头盒 + 下半身盒），见
    // MixinEntity#polymech$spaceBoundingBox。要找回旧实现见 git 历史（提交 2992d52 前后）。

    /**
     * 鼠标转向：<b>俯仰绕屏幕左轴、偏航绕屏幕竖轴</b> —— 两个轴都在<b>视线自己的局部系</b>里。
     *
     * <p><b>为什么是局部轴（真 6DOF），而不是绕世界竖轴（水平锁定相机）：</b>
     * 世界竖轴在视线指向正上/正下时与视线平行，旋转轴退化成"绕视线自转"——
     * 鼠标左右只能让画面整体旋转、视线方向一动不动，这就是"视野球上下两个极点"。
     * 而且这不是实现不够好：<b>球面上不存在处处非零的连续切向量场（毛球定理）</b>，
     * 只要坚持"打转永远保持地平线水平"，极点就必然存在，只能把俯仰夹在 ±90 让玩家够不到。</p>
     *
     * <p>局部轴（屏幕左轴 / 屏幕竖轴，两者恒垂直于视线）则任何姿态下都非退化：
     * 鼠标左右永远是"摆动视线"，360° 任意方向都能到，与 space 0.0.6 的
     * {@code bodyRotation.mul(Ry·Rx·Rz)}（右乘 = 体轴）是同一套模型。</p>
     *
     * <p><b>代价（有意接受）</b>：俯仰状态下打转会让<b>地平线跟着滚</b>（世界竖直相对屏幕在变）——
     * 这是 3 自由度自由视角的固有性质。本项目全场景使用纯 6DOF；晕动靠舒适层缓解：
     * 深空飞行辅助（默认开，V 键切换）把滚转柔和拉回世界竖直、X 键手动就近回正、
     * Z/C 自己滚（见 {@link #rollBody(double)}），HUD 另加人工地平线姿态仪
     * （{@code SpaceAttitudeOverlay}）。</p>
     *
     * <p>颈部锥（照 space 0.0.6 的用意）：头先在局部锥内领先，领先量夹住之后多出来的
     * 部分自然落到身体上，于是"头先转、转不动了身体跟上来"。领先量本身也是局部量
     * （见 {@link #rederiveBody()}），所以过极点时它同样不会退化。</p>
     *
     * @param yawDelta   {@code Entity#turn} 的 yRot 增量
     * @param pitchDelta {@code Entity#turn} 的 xRot 增量
     */
    public void turn(double yawDelta, double pitchDelta) {
        // 先自保一次正交（远端/存档送进来的基底不保证），下面两个轴才必然非退化。
        orthonormalize();

        // ── 1. 视线（头部）基底：绕视线自己的轴转 ──
        Vector3d screenLeft = new Vector3d(left).negate();
        // 俯仰：绕屏幕左轴（该轴自身不变，所以只转 facing）
        double appliedPitch = 0.15 * pitchDelta * TORAD;
        facing.rotateAxis(appliedPitch, screenLeft.x, screenLeft.y, screenLeft.z);
        // 偏航：绕**屏幕竖轴**（= facing × screenLeft，俯仰后重算）。
        // 它与视线恒垂直，任何姿态下都不会退化成"绕视线自转"。
        // 符号：绕 +screenUp 转正角会把视线摆向屏幕左，所以鼠标右取负。
        Vector3d screenUp = new Vector3d(facing).cross(screenLeft, new Vector3d()).normalize();
        double appliedYaw = -0.15 * yawDelta * TORAD;
        facing.rotateAxis(appliedYaw, screenUp.x, screenUp.y, screenUp.z);
        left.rotateAxis(appliedYaw, screenUp.x, screenUp.y, screenUp.z);
        orthonormalize();

        // ── 2. 头部领先量：累加并夹在颈部活动范围内（超出的部分就是身体的转动量）──
        // 符号（局部系）：headYaw > 0 = 头向左转，headPitch > 0 = 低头。
        // 与 NECK_* 常数的对应关系不变：负方向上限 = RIGHT/UP，正方向上限 = LEFT/DOWN。
        headYaw = clampRange(headYaw + appliedYaw, NECK_YAW_RIGHT, NECK_YAW_LEFT);
        headPitch = clampRange(headPitch + appliedPitch, NECK_PITCH_UP, NECK_PITCH_DOWN);

        // ── 3. 身体 = 视线去掉头部领先量（同样是局部轴上的四元数求逆）──
        rederiveBody();

        // ── 4. 连续角（第一人称手用）──
        // 视线这一步刚动过，立刻重挑"离上一帧最近"的欧拉分支，过极点才不会跳。
        updateContAnglesFromFacing();
    }

    /**
     * 用"当前视线 + 当前颈部领先量"重算身体基底（turn() 的第 3 步；挤洞口时也要用）。
     *
     * <p>视线 = 身体 ∘ {@code Ry(headYaw) ∘ Rx(headPitch)}，所以身体 = 视线 ∘ 领先量的逆。
     * 全程在<b>局部轴</b>上做四元数右乘/求逆，<b>不出现世界竖轴</b> —— 这正是过极点不退化
     * 的原因（旧实现"绕世界竖轴反解偏航 + 绕视线左轴反解俯仰"，视线与任一轴平行时就退化）。</p>
     *
     * <p>轴的对应关系：{@link #basisToQuat} 的列是 (screenLeft, up, facing)，即局部 X = 屏幕左、
     * Y = 屏幕竖、Z = 视线，所以 {@code Ry} 就是"绕屏幕竖轴"、{@code Rx} 就是"绕屏幕左轴"，
     * 与 {@link #turn(double, double)} 施加的方向一致。</p>
     */
    private void rederiveBody() {
        org.joml.Quaternionf view = basisToQuat(facing, left, new org.joml.Quaternionf());
        // 注意 JOML：rotationY 是"设置成纯 Y 旋转"，rotateX 才是"右乘"（= 局部 X）。
        org.joml.Quaternionf headLocal = new org.joml.Quaternionf()
                .rotationY((float) headYaw)
                .rotateX((float) headPitch);
        org.joml.Quaternionf body = view.mul(headLocal.conjugate(), new org.joml.Quaternionf());
        // 反解回 (bodyFacing, bodyLeft)：Q·(0,0,1) = 前方；Q·(1,0,0) = screenLeft = -left
        bodyFacing.set(body.transform(new Vector3d(0.0, 0.0, 1.0)));
        bodyLeft.set(body.transform(new Vector3d(-1.0, 0.0, 0.0)));
        orthonormalizeBody();
    }

    /**
     * "挤过去"辅助：把颈部领先量按比例收回（身体立即对齐视线），视线本身不动。
     *
     * <p>用途：玩家低头/转头去钻一格大小的洞口时，如果身体还停留在"头领先"的姿态，
     * 身体相对洞口是斜的 —— 原版 AABB（旋转身体盒的外包盒）横截面会超过一格，怎么推都进不去。
     * 正在用力推且被挡住时把领先量收回，身体正对洞口，横截面回到 0.6×0.6 就能挤进去。</p>
     *
     * <p>注意这<b>不改视线</b>：只有身体和领先量变，相机/朝向一格都不动。</p>
     *
     * @param keep 每 tick 保留比例（例如 0.5 = 每 tick 减半）
     */
    public void relaxLead(double keep) {
        if (headYaw == 0.0 && headPitch == 0.0) {
            return;
        }
        headYaw *= keep;
        headPitch *= keep;
        if (Math.abs(headYaw) < 1.0e-4) {
            headYaw = 0.0;
        }
        if (Math.abs(headPitch) < 1.0e-4) {
            headPitch = 0.0;
        }
        rederiveBody();
    }

    private static final double TORAD = Math.PI / 180.0;
    private static final double TODEG = 180.0 / Math.PI;

    /** 按"负方向上限 / 正方向上限"分别夹取（颈部的三个轴、上下左右各不相同）。 */
    private static double clampRange(double value, double negativeLimit, double positiveLimit) {
        if (value > positiveLimit) {
            return positiveLimit;
        }
        return value < -negativeLimit ? -negativeLimit : value;
    }

    /** 主动滚转：绕视线前方轴转（Z/C 键）。滚的是视线，身体随后重算。 */
    public void rollBody(double rollRad) {
        if (rollRad == 0.0) {
            return;
        }
        left.rotateAxis(rollRad, facing.x, facing.y, facing.z);
        orthonormalize();
        rederiveBody();
    }

    /** 由两个基底向量构造姿态四元数（局部 +Z = 前、+Y = 上、+X = 左）。 */
    private static org.joml.Quaternionf basisToQuat(Vector3d face, Vector3d leftMirror,
                                                    org.joml.Quaternionf out) {
        Vector3d screenLeft = new Vector3d(leftMirror).negate();
        Vector3d up = new Vector3d(face).cross(screenLeft, new Vector3d()).normalize();
        org.joml.Matrix3f m = new org.joml.Matrix3f();
        m.setColumn(0, (float) screenLeft.x, (float) screenLeft.y, (float) screenLeft.z);
        m.setColumn(1, (float) up.x, (float) up.y, (float) up.z);
        m.setColumn(2, (float) face.x, (float) face.y, (float) face.z);
        return out.setFromNormalized(m);
    }

    /** 让 left 与 facing 严格正交并归一化 */
    public void orthonormalize() {
        facing.normalize();
        double d = left.dot(facing);
        left.sub(facing.x * d, facing.y * d, facing.z * d);
        if (left.lengthSquared() < 1e-12) {
            // facing 与 left 退化平行时，任取一个与 facing 垂直的向量
            Vector3d arbitrary = Math.abs(facing.y) < 0.99
                    ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0);
            left.set(arbitrary.cross(facing, new Vector3d()));
        }
        left.normalize();
    }

    public static SpacePlayerData get(Entity entity) {
        return entity.getData(TYPE.get());
    }

    public static void register(net.neoforged.bus.api.IEventBus bus) {
        REGISTRAR.register(bus);
    }
}
