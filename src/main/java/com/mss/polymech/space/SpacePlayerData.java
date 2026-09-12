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
     * 头部相对身体的<b>完整局部旋转</b>（含横滚），ZYX 分解 —— 给头部零件渲染用。
     *
     * <p>为什么不能只喂 headYaw/headPitch：领先量是"绕世界竖轴偏航 + 绕视线左轴俯仰"，
     * 一旦身体俯仰/倒挂/平躺，这个领先量换算到<b>身体局部坐标</b>里就带横滚分量
     * （身体平躺时几乎全是横滚）。只给 yaw/pitch 就会把头顶着画歪 —— 这就是"颠倒时头部不对"的原因。</p>
     *
     * <p>分解用 ZYX 是为了配合 {@code ModelPart} 的 {@code rotationZYX(zRot, yRot, xRot)} 组合顺序。
     * 正立时 x/y 两项与原来的 (headPitch, headYaw) 完全一致，所以平地手感不变。</p>
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
        // 用**物理真实姿态**：身体被障碍物顶转时，头相对躯干的偏角才是真实的那一个
        headLocalQuat(new org.joml.Quaternionf()).getEulerAnglesZYX(out);
        // 夹进"脖子做得到"的范围。分解出来的值在身体倾斜时会超出锥，尤其是横滚那一项
        // （领先量是绕世界竖轴的，换算到躺倒的身体局部坐标里几乎全是横滚）。
        // 符号约定（与模型一致）：e.x > 0 = 低头，e.y < 0 = 向右，e.z < 0 = 向右歪。
        out.x = (float) clampRange(out.x, NECK_PITCH_UP, NECK_PITCH_DOWN);
        out.y = (float) clampRange(out.y, NECK_YAW_RIGHT, NECK_YAW_LEFT);
        out.z = (float) clampRange(out.z, NECK_ROLL_RIGHT, NECK_ROLL_LEFT);
    }

    // ==================== 碰撞箱（原版 AABB） ====================

    /**
     * 原版游泳位姿的半高：{@code Pose.SWIMMING}/{@code GLIDING} 时玩家是 0.6×0.6。
     * 原版就是这样钻一格洞的 —— 不是把盒子转过去，而是<b>换一个矮盒子</b>
     * （{@code Player.updatePlayerPose()} 会用"小盒子装不装得下"来决定是否切位姿）。
     */
    private static final double CRAWL_HALF_HEIGHT = 0.3;

    private double extHalfHorizontal = 0.3;
    private double extHalfVertical = 0.9;

    /**
     * 算出"原版 AABB"该用多大 —— 照原版的位姿思路：<b>直立时 0.6×1.8，躺平时收缩到 0.6×0.6</b>。
     *
     * <p>为什么不再用"旋转身体盒的外包盒"：那个盒子又长又宽，虽然看着包裹了身体，
     * 却会把原版那套移动校验一起卡死 —— 想爬一格洞时怎么推都进不去。
     * 真正的碰撞由 Rapier 的旋转 OBB 负责（它始终贴着身体，不会被卡住）；
     * 原版 AABB 只需要**不挡路**，所以按原版游泳位姿那样收缩即可（模型视觉上溢出盒子是原版也接受的做法）。</p>
     *
     * <p>盒子中心固定在 实体位置 + 身高/2（与刚体平移点、模型旋转支点同一个点），
     * 所以直立时结果与 vanilla 逐位相同；越躺平，盒子越矮。</p>
     */
    public void computeWorldExtents(double halfWidth, double halfHeight) {
        // 只用**运动学**身体基底，绝不读物理刚体姿态（这点非常重要）：
        // 物理姿态只有 100Hz 更新、而且在接触解算里高频抖动，一旦拿它算 AABB 尺寸，
        // 盒子就会每帧在 0.3~0.9 之间被拉来拉去 → 原版 collide() 裁出的位移跟着抖
        // → 喂给物理的冲量抖 → **玩家位置每帧抖**（看起来就是"人物抽搐/帧数低"）。
        // 站立时 |up.y|≈1，盒子恒等于 vanilla 的 0.6×1.8，所以那种情况看不出来。
        double upY = bodyFacing.x * bodyLeft.z - bodyFacing.z * bodyLeft.x;
        double upright = Math.min(1.0, Math.max(0.0, Math.abs(upY)));
        extHalfHorizontal = halfWidth;
        extHalfVertical = CRAWL_HALF_HEIGHT + (halfHeight - CRAWL_HALF_HEIGHT) * upright;
    }

    public double extHalfHorizontal() {
        return extHalfHorizontal;
    }

    public double extHalfVertical() {
        return extHalfVertical;
    }

    /**
     * 鼠标转向：<b>俯仰绕屏幕左轴、偏航绕世界竖轴</b>。
     *
     * <p>这是早就定下的硬要求：<b>打转（偏航）绝不能让视角歪头</b>。偏航如果绕"身体/头的 up"
     * 转，那么在低头或抬头之后再打转，up 是斜的 → 地平线会跟着歪 —— 必须绕世界竖轴转。
     * 屏幕 up 倒挂时翻转偏航符号，否则鼠标左右会反向。</p>
     *
     * <p>颈部锥（照 space 0.0.6 的用意）：头先在自己的锥内领先，领先量夹住之后多出来的
     * 部分自然落到身体上，于是"头先转、转不动了身体跟上来"。</p>
     *
     * <p><b>身体的领先量是按"世界竖轴偏航 + 视线左轴俯仰"去掉的</b>，不是把视线相对身体的
     * 偏角做欧拉分解再丢掉横滚 —— 后者在身体已经俯仰时会把"绕世界竖轴转"的部分解成
     * 横滚丢给身体，于是左右转体时身体（以及跟着身体姿态走的碰撞箱）会一边转一边滚，
     * 贴地时刮地抽搐。现在身体的横滚恒等于视线的横滚，转体只动偏航。</p>
     *
     * @param yawDelta   {@code Entity#turn} 的 yRot 增量
     * @param pitchDelta {@code Entity#turn} 的 xRot 增量
     */
    public void turn(double yawDelta, double pitchDelta) {
        // ── 1. 视线（头部）基底 ──
        Vector3d screenLeft = new Vector3d(left).negate();
        // 俯仰：绕屏幕左轴（该轴自身不变，所以只转 facing）
        double appliedPitch = 0.15 * pitchDelta * TORAD;
        facing.rotateAxis(appliedPitch, screenLeft.x, screenLeft.y, screenLeft.z);
        // 偏航：绕世界竖轴
        Vector3d up = new Vector3d(facing).cross(screenLeft, new Vector3d());
        double yawSign = up.y > 0 ? 1.0 : -1.0;
        if (Math.abs(up.y) < 1.0e-6) yawSign = 1.0; // roll≈90° 的瞬时退化，保持原方向
        double appliedYaw = 0.15 * yawDelta * yawSign * TORAD;
        facing.rotateAxis(appliedYaw, 0, -1, 0);
        left.rotateAxis(appliedYaw, 0, -1, 0);
        orthonormalize();

        // ── 2. 头部领先量：累加并夹在颈部活动范围内（超出的部分就是身体的转动量）──
        // 符号：headYaw > 0 = 向右，headPitch > 0 = 低头
        headYaw = clampRange(headYaw + appliedYaw, NECK_YAW_RIGHT, NECK_YAW_LEFT);
        headPitch = clampRange(headPitch + appliedPitch, NECK_PITCH_UP, NECK_PITCH_DOWN);

        // ── 3. 身体 = 视线去掉头部领先量 ──
        rederiveBody();
    }

    /**
     * 用"当前视线 + 当前领先量"重算身体基底（turn() 的第 3 步；挤洞口时也要用）。
     */
    private void rederiveBody() {
        bodyFacing.set(facing);
        bodyLeft.set(left);
        // 俯仰领先量：绕更新后视线的屏幕左轴（= 视线的局部 X）反向转回。
        // 该轴与 bodyLeft（镜像左）平行，所以 bodyLeft 不用动。
        Vector3d viewLeft = new Vector3d(left).negate();
        bodyFacing.rotateAxis(-headPitch, viewLeft.x, viewLeft.y, viewLeft.z);
        // 偏航领先量：绕世界竖轴反向转回
        bodyFacing.rotateAxis(-headYaw, 0, -1, 0);
        bodyLeft.rotateAxis(-headYaw, 0, -1, 0);
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
