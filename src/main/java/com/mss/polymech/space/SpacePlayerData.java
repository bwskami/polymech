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

    /** 每 tick 开始：保存旧向量（插值用） */
    public void saveOld() {
        facingO.set(facing);
        leftO.set(left);
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

        facingO.set(facing);
        leftO.set(left);
        lastYaw = yawDeg;
        lastRoll = 0;
        initialized = true;
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
