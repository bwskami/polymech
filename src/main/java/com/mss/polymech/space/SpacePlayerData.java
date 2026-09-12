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
     * 应用来自网络同步的朝向（远端玩家 / 服务端副本）。
     * <p>同时写入 O 向量，让渲染插值从"上一帧的朝向"过渡到新朝向，
     * 并把状态标记为已初始化（否则渲染混入会跳过 roll）。</p>
     */
    public void applyRemote(double fx, double fy, double fz, double lx, double ly, double lz,
                            double headYawRad, double headPitchRad) {
        saveOld();
        bodyFacing.set(fx, fy, fz);
        bodyLeft.set(lx, ly, lz);
        orthonormalizeBody();
        this.headYaw = headYawRad;
        this.headPitch = headPitchRad;
        rebuildHeadFromBody();
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

    /**
     * 颈部活动范围：头在这个范围内自由转，<b>完全不带身体</b>；超出后身体才开始跟上。
     *
     * <p>（space 0.0.6 用的是 ±π/18 偏航 / ±π/180 俯仰 —— 俯仰只有 1°，
     * 等于头刚动身体就跟着动，非常突兀。这里改成接近真实颈椎活动度：
     * 旋转约 ±60°、屈伸约 ±45°。）</p>
     */
    public static final double CONE_YAW = Math.toRadians(60.0);
    public static final double CONE_PITCH = Math.toRadians(45.0);

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
    public void headLocalEuler(org.joml.Vector3f out) {
        org.joml.Quaternionf body = basisToQuat(bodyFacing, bodyLeft, new org.joml.Quaternionf());
        org.joml.Quaternionf head = basisToQuat(facing, left, new org.joml.Quaternionf());
        new org.joml.Quaternionf(body).conjugate().mul(head).getEulerAnglesZYX(out);
    }

    /**
     * 由"身体基底 + 头部领先量"还原视线基底 —— {@link #turn(double, double)} 里
     * "身体 = 视线去掉领先量"的逆运算：先绕身体屏幕左轴补回俯仰领先量，再绕世界竖轴补回偏航领先量。
     *
     * <p>（不能用局部的 {@code Ry(+headYaw)}：那等于把"向右转"的符号用反，会让服务端/远端
     * 还原出的视线左右镜像 —— 服务端的移动方向正是从视线基底算的。）</p>
     */
    public void rebuildHeadFromBody() {
        facing.set(bodyFacing);
        left.set(bodyLeft);
        // 俯仰领先量：绕身体（≈视线）的屏幕左轴补回
        Vector3d bodyLeftTrue = new Vector3d(bodyLeft).negate();
        facing.rotateAxis(headPitch, bodyLeftTrue.x, bodyLeftTrue.y, bodyLeftTrue.z);
        // 偏航领先量：绕世界竖轴补回（turn() 里是绕 (0,-1,0) 减掉的）
        facing.rotateAxis(headYaw, 0, -1, 0);
        left.rotateAxis(headYaw, 0, -1, 0);
        orthonormalize();
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

        // ── 2. 头部领先量：累加并夹在颈部锥内（超出的部分就是身体的转动量）──
        headYaw = clampCone(headYaw + appliedYaw, CONE_YAW);
        headPitch = clampCone(headPitch + appliedPitch, CONE_PITCH);

        // ── 3. 身体 = 视线去掉头部领先量 ──
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

    private static final double TORAD = Math.PI / 180.0;

    private static double clampCone(double value, double limit) {
        if (value > limit) {
            return limit;
        }
        return value < -limit ? -limit : value;
    }

    /** 主动滚转：绕身体前方轴转（Z/C 键）。 */
    public void rollBody(double rollRad) {
        if (rollRad == 0.0) {
            return;
        }
        bodyLeft.rotateAxis(rollRad, bodyFacing.x, bodyFacing.y, bodyFacing.z);
        bodyLeft.normalize();
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
