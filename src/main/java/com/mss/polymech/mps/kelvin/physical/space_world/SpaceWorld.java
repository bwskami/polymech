package com.mss.polymech.mps.kelvin.physical.space_world;

import com.google.gson.JsonObject;
import com.mss.polymech.mps.kelvin.physical.CelestialBodyForce;
import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import com.mss.polymech.physics.NativePhysics;
import com.mss.polymech.physics.PhysicsNatives;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * 天体世界 —— <b>与 {@code org.cn_grass_block.kelvin.physical.space_world.SpaceWorld}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 一个"宇宙"：持有该宇宙里所有 {@link CelestialBody}（恒星/行星/卫星/小行星），
 * 每步推进它们的运动与自转，并提供引力查询。
 *
 * <h2>两条积分路径（照 space 0.1.3，都保留）</h2>
 * <ol>
 *   <li><b>原生 cosmos（首选）</b>：{@link #step} 里懒建 cosmos 世界，把天体逐个登记为引力源，
 *       由原生做 N 体积分，再把位置/速度读回来。这样"行星绕恒星"是原生算的，稳定且快。</li>
 *   <li><b>纯 Java 兜底</b>：原生不可用时（老 dll / 平台不支持），走
 *       "引力 → 加速度 → 速度 → 位置"的显式一步积分。精度不如原生，但<b>天体仍会动</b>，
 *       不会因为一个能力缺失就整块玩法停摆。</li>
 * </ol>
 * <b>自转永远在 Java 里做</b>（{@code rotate * rotateY(rotateSpeed·dt)}）—— 原生只管平动，
 * 因为自转是纯姿态、不需要求解器。
 *
 * <h2>与 space 的唯一改形（必须知道）</h2>
 * kelvin 用 {@code RapierConnect.unsafe.allocateMemory(24)} 开一块裸内存当读回缓冲，
 * 再用 {@code unsafe.getDouble(ptr + 0/8/16)} 取三个 double。我们改成
 * <b>{@code double[3]}</b> 成员 —— 同语义（3 个 double 出参）、没有 Unsafe、也无需释放。
 * 原生侧的返回约定随之明确：<b>非 0 = 成功，0 = 失败</b>（kelvin 判 {@code == 0 ? null : 值}）。
 */
public class SpaceWorld {

    private final List<CelestialBody> CelestialBodyPool = new ArrayList<>();
    public final ResourceLocation WorldID;
    public final ResourceLocation SkyBoxTexture;

    private long cosmos_world_memory_handle = -1L;
    /** 读回缓冲（替代 kelvin 的 Unsafe 裸指针）；cosmos 世界建好后才有值。 */
    private double[] cosmosReadBuffer = null;
    private final Map<CelestialBody, Long> cosmosBodyHandles = new HashMap<>();
    private final Set<CelestialBody> cosmosRegistered = new HashSet<>();

    /** 跨线程操作队列（见 {@link CelestialBody} 类注释：所有改动排队）。 */
    private final Queue<OperationBuffer> operationBufferList = new ConcurrentLinkedQueue<>();

    protected SpaceWorld(ResourceLocation WorldID, ResourceLocation SkyBoxTexture) {
        this.WorldID = ResourceLocation.parse(WorldID.toString());
        this.SkyBoxTexture = ResourceLocation.parse(SkyBoxTexture.toString());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SpaceWorld that && Objects.equals(this.WorldID, that.WorldID);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.WorldID);
    }

    // ==================== 天体池 ====================

    public List<CelestialBody> getAllCelestialBody() {
        return new ArrayList<>(this.CelestialBodyPool);
    }

    public CelestialBody getCelestialBody(String name) {
        for (CelestialBody celestialBody : new ArrayList<>(this.CelestialBodyPool)) {
            if (celestialBody.getName().equals(name)) {
                return celestialBody;
            }
        }
        return null;
    }

    /** 按到 {@code pos} 的距离升序返回（导航/最近天体用）。 */
    public List<CelestialBody> getNearCelestialBody(Vector3d pos) {
        return new ArrayList<>(this.CelestialBodyPool).stream()
                .sorted(Comparator.comparingDouble(body -> body.getPos().distance(pos)))
                .collect(Collectors.toList());
    }

    public void putCelestialBody(CelestialBody celestialBody) {
        if (!this.CelestialBodyPool.contains(celestialBody)) {
            this.CelestialBodyPool.add(celestialBody);
        }
        celestialBody.spaceWorld = this;
    }

    public void removeCelestialBody(CelestialBody celestialBody) {
        this.CelestialBodyPool.remove(celestialBody);
        if (celestialBody.spaceWorld == this) {
            celestialBody.spaceWorld = null;
        }
    }

    // ==================== 坐标换算 ====================

    /** 基类不做缩放（MPS 的 space 维度自己覆写做 ZOOM）；这里保持"原样返回"。 */
    public Vector3d convertMinecraftSpaceVectorToSpaceVector(double x, double y, double z) {
        return new Vector3d(x, y, z);
    }

    public Vector3d convertMinecraftSpaceVectorToSpaceVector(Vector3d pos) {
        return this.convertMinecraftSpaceVectorToSpaceVector(pos.x(), pos.y(), pos.z());
    }

    public Vector3d convertMinecraftSpaceVectorToSpaceVector(Vec3 pos) {
        return this.convertMinecraftSpaceVectorToSpaceVector(pos.x(), pos.y(), pos.z());
    }

    public Vector3d convertSpaceVectorToMinecraftSpaceVector(double x, double y, double z) {
        return new Vector3d(x, y, z);
    }

    public Vector3d convertSpaceVectorToMinecraftSpaceVector(Vec3 pos) {
        return this.convertSpaceVectorToMinecraftSpaceVector(pos.x(), pos.y(), pos.z());
    }

    // ==================== 存档 ====================

    public JsonObject toJsonObject() {
        JsonObject mainJson = new JsonObject();
        for (CelestialBody celestialBody : new ArrayList<>(this.CelestialBodyPool)) {
            mainJson.add(celestialBody.getName(), celestialBody.toJsonObject());
        }
        return mainJson;
    }

    public void readDataFromJsonObject(JsonObject json) {
        for (String key : json.keySet()) {
            JsonObject jsonObject = json.get(key).getAsJsonObject();
            CelestialBody celestialBody = this.getCelestialBody(key);
            if (celestialBody != null) {
                celestialBody.ReadDataFromJsonObject(jsonObject);
            }
        }
    }

    // ==================== 步进 ====================

    /**
     * 推进一步（由物理线程按 {@code core_tick_time} 调用）。
     *
     * <p>顺序照 space：懒建 cosmos → 登记新天体（入队，由 {@link #up()} flush）→ 原生步进
     * → 逐体读回位姿/速度 → 自转（Java）→ {@code tick()}。</p>
     */
    public void step(double core_tick_time) {
        if (this.cosmos_world_memory_handle == -1L && PhysicsNatives.hasCosmos()) {
            // 调用点参数：dt、子步 4、网格 1/1/1、远场截断 1e6（见 NativePhysics 的说明）
            this.cosmos_world_memory_handle =
                    NativePhysics.cosmosWorldCreate(core_tick_time, 4, 1, 1, 1, 1000000.0);
            if (this.cosmos_world_memory_handle != -1L && this.cosmosReadBuffer == null) {
                this.cosmosReadBuffer = new double[3]; // 替代 kelvin 的 Unsafe.allocateMemory(24)
            }
        }

        if (this.cosmos_world_memory_handle != -1L) {
            for (CelestialBody celestialBody : new ArrayList<>(this.CelestialBodyPool)) {
                if (!this.cosmosRegistered.contains(celestialBody)) {
                    this.cosmosRegistered.add(celestialBody);
                    // isSun：space 用"名字里含 sun"判定固定不动的引力源
                    this.putOperation(new OperationBuffer(OperationBuffer.Type.REGISTER_COSMOS_BODY,
                            celestialBody, celestialBody.getName().toLowerCase().contains("sun")));
                }
            }

            this.up();
            NativePhysics.cosmosWorldStep(this.cosmos_world_memory_handle, core_tick_time);

            for (CelestialBody celestialBody : new ArrayList<>(this.CelestialBodyPool)) {
                Vector3d pos = this.getCosmosTranslation(celestialBody);
                if (pos != null && pos.isFinite()) {
                    celestialBody.moveToDirect(pos);
                }
                Vector3d vel = this.getCosmosLinvel(celestialBody);
                if (vel != null && vel.isFinite()) {
                    celestialBody.speed().set(vel);
                }
                // 自转永远在 Java 里做（原生只管平动）
                celestialBody.rotateToDirect(celestialBody.getRotate()
                        .mul(new Quaterniond().rotateY(celestialBody.getRotateSpeed() * core_tick_time))
                        .normalize());
                celestialBody.tick();
            }
        } else {
            // 纯 Java 兜底：引力 → 加速度 → 速度 → 位置（显式一步）
            for (CelestialBody celestialBody : new ArrayList<>(this.CelestialBodyPool)) {
                celestialBody.addForceDirect(this.getGravitationForce(celestialBody));
                celestialBody.speed().add(celestialBody.getAcceleration().mul(core_tick_time));
                celestialBody.forceTimeUpdata(core_tick_time);
                celestialBody.moveToDirect(celestialBody.getPos()
                        .add(new Vector3d(celestialBody.speed()).mul(core_tick_time)));
                celestialBody.rotateToDirect(celestialBody.getRotate()
                        .mul(new Quaterniond().rotateY(celestialBody.getRotateSpeed() * core_tick_time))
                        .normalize());
                celestialBody.tick();
            }
        }
    }

    /** 读回位置；原生返回 0 表示失败（kelvin 的约定是 {@code == 0 ? null : 值}）。 */
    private Vector3d getCosmosTranslation(CelestialBody body) {
        Long handle = this.cosmosBodyHandles.get(body);
        if (handle == null || this.cosmosReadBuffer == null) {
            return null;
        }
        if (NativePhysics.cosmosBodyTranslationOut(this.cosmos_world_memory_handle, handle, this.cosmosReadBuffer) == 0) {
            return null;
        }
        return new Vector3d(this.cosmosReadBuffer[0], this.cosmosReadBuffer[1], this.cosmosReadBuffer[2]);
    }

    /** 读回速度；返回约定同上。 */
    private Vector3d getCosmosLinvel(CelestialBody body) {
        Long handle = this.cosmosBodyHandles.get(body);
        if (handle == null || this.cosmosReadBuffer == null) {
            return null;
        }
        if (NativePhysics.cosmosBodyLinvelOut(this.cosmos_world_memory_handle, handle, this.cosmosReadBuffer) == 0) {
            return null;
        }
        return new Vector3d(this.cosmosReadBuffer[0], this.cosmosReadBuffer[1], this.cosmosReadBuffer[2]);
    }

    /** 销毁原生 cosmos 世界与登记表（本项目不需要释放读回缓冲）。 */
    public void freeCosmosWorld() {
        if (this.cosmos_world_memory_handle != -1L) {
            NativePhysics.cosmosWorldDestroy(this.cosmos_world_memory_handle);
            this.cosmos_world_memory_handle = -1L;
        }
        this.cosmosReadBuffer = null;
        this.cosmosBodyHandles.clear();
        this.cosmosRegistered.clear();
    }

    // ==================== 操作队列 ====================

    /** 入队；只接受"无主或属于本世界"的操作（防止跨世界误改）。 */
    public void putOperation(OperationBuffer operationBuffer) {
        if (operationBuffer.celestialBody == null
                || operationBuffer.celestialBody.spaceWorld == null
                || operationBuffer.celestialBody.spaceWorld.equals(this)) {
            this.operationBufferList.add(operationBuffer);
        }
    }

    /** 步进前 flush 队列。<b>一次最多 1024 条</b> —— 批上限，避免一次卡死物理线程。 */
    public void up() {
        List<OperationBuffer> snapshot = new ArrayList<>();
        OperationBuffer operationBuffer;
        for (int i = 0; i < 1024 && (operationBuffer = this.operationBufferList.poll()) != null; i++) {
            snapshot.add(operationBuffer);
        }
        for (OperationBuffer ob : snapshot) {
            this.doOperation(ob);
        }
    }

    private void doOperation(OperationBuffer ob) {
        switch (ob.type) {
            case MOVE_TO -> ob.celestialBody.moveToDirect((Vector3d) ob.data[0]);
            case ROTATE_TO -> ob.celestialBody.rotateToDirect((Quaterniond) ob.data[0]);
            case ADD_FORCE -> ob.celestialBody.addForceDirect((CelestialBodyForce) ob.data[0]);
            case SET_ROTATE_SPEED -> ob.celestialBody.setRotateSpeed((Double) ob.data[0]);
            case REGISTER_COSMOS_BODY -> this.registerCosmosBody(ob.celestialBody, (Boolean) ob.data[0]);
        }
    }

    /**
     * 把天体插进原生 cosmos 世界（登记为引力源）。
     *
     * <p>{@code fixed} 来自"名字含 sun" —— 恒星是固定不动的引力源，不参与积分。</p>
     */
    private void registerCosmosBody(CelestialBody body, boolean fixed) {
        if (this.cosmos_world_memory_handle == -1L) {
            return;
        }
        Vector3d pos = body.getPos();
        Vector3d vel = body.speed();
        long builder = fixed
                ? NativePhysics.cosmosFixedBodyBuilder(pos.x(), pos.y(), pos.z())
                : NativePhysics.cosmosSatelliteBuilder(body.getMass(),
                        pos.x(), pos.y(), pos.z(), vel.x(), vel.y(), vel.z(), body.getRadius());
        if (builder == 0L) {
            return;
        }
        long handle = NativePhysics.cosmosWorldInsertBodyAsGravitySource(
                this.cosmos_world_memory_handle, builder, body.getMass());
        if (handle != 0L) {
            this.cosmosBodyHandles.put(body, handle);
        }
    }

    // ==================== 引力（Java 侧，用于力下发与兜底积分） ====================

    /**
     * 求某天体受到的引力 —— <b>在 Java 里算</b>（原生 cosmos 只负责天体之间的积分）。
     *
     * <p>牛顿引力：{@code F = G·M·m / r²}，方向指向源；末尾乘自身质量，时长给
     * {@code POSITIVE_INFINITY}（永久力，见 {@link CelestialBodyForce}）。</p>
     */
    public CelestialBodyForce getGravitationForce(CelestialBody this_CelestialBody) {
        Vector3d gravitation = new Vector3d(0.0, 0.0, 0.0);
        for (CelestialBody other : new ArrayList<>(this.CelestialBodyPool)) {
            if (!other.getName().equals(this_CelestialBody.getName()) && other.isCompute()) {
                double distanceSq = this_CelestialBody.getPos().distanceSquared(other.getPos());
                if (distanceSq != 0.0) {
                    double factor = 6.6743E-11 * other.getMass() / distanceSq;
                    if (!Double.isNaN(factor)) {
                        gravitation.add(new Vector3d(other.getPos()
                                .sub(this_CelestialBody.getPos())
                                .normalize(factor)));
                    }
                }
            }
        }
        return new CelestialBodyForce("Gravitation",
                gravitation.mul(this_CelestialBody.getMass()), Double.POSITIVE_INFINITY);
    }

    /** 求某位置处（一个虚拟零质量天体）受到的引力。 */
    public CelestialBodyForce getGravitationForce(Vector3d pos) {
        return this.getGravitationForce(
                new CelestialBody(this.WorldID.toString(), "", pos, new Quaterniond(), 0.0));
    }

    // ==================== 操作缓冲 ====================

    /** 一条跨线程操作（见 {@link CelestialBody} 类注释）。 */
    public static class OperationBuffer {
        private final Type type;
        private final CelestialBody celestialBody;
        private final Object[] data;

        public OperationBuffer(Type type, CelestialBody celestialBody, Object... data) {
            this.type = type;
            this.celestialBody = celestialBody;
            this.data = data;
        }

        public enum Type {
            MOVE_TO,
            ROTATE_TO,
            ADD_FORCE,
            SET_ROTATE_SPEED,
            REGISTER_COSMOS_BODY
        }
    }
}
