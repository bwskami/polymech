package com.mss.polymech.mps.rapier.helper;

import com.mss.polymech.physics.NativePhysics;
import com.mss.polymech.physics.PhysicsNatives;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 碰撞体 —— <b>与 {@code org.polaris2023.mps.rapier.helper.ColliderBody} 同形的公开 API</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <p><b>分离对象语义</b>：构造时只解析并保存形状参数（MPS 用的是 {@code Object... params}，
 * 这里保持一致），不碰原生；{@link RapierWorld#addColliderBody(ColliderBody)} /
 * {@link RapierWorld#addColliderBody(ColliderBody, RigidBody)} 时才创建原生碰撞体。</p>
 *
 * <h2>形状参数（与 MPS 的约定一致）</h2>
 * <ul>
 *   <li>{@code BALL}：{@code (Double radius)}</li>
 *   <li>{@code CUBOID}：{@code (Double hx, Double hy, Double hz)}（<b>半长</b>）</li>
 *   <li>{@code VOXEL}：{@code (boolean[] cells, Integer sx, Integer sy, Integer sz,
 *       Double csx, Double csy, Double csz)}，格索引 {@code x + z*sx + y*sx*sz} ——
 *       即 <b>x 最快、其次 z、最后 y</b>，与 MC 自己按段枚举方块、
 *       以及 {@link com.mss.polymech.mps.physical.helper.PalettedChunk#colliderArray()} 的顺序一致
 *       （索引序不一致会让体素整体错位，必须统一）</li>
 *   <li>{@code COMPLEX_VOXEL}：{@code (double[] boxes)}，每盒 6 个 double：minX,minY,minZ,maxX,maxY,maxZ</li>
 * </ul>
 *
 * <p>其余形状（{@code HALFSPACE}/{@code HEIGHTMAP}/{@code CAPSULE}/{@code CONVEX}/{@code TRIMESH}）
 * 我们的原生层还没有对应的每体形状：{@code HALFSPACE} 请用
 * {@link NativePhysics#worldSetFloor} 的维度地面，其它等 S5 补原生。调用即抛，不会静默失真。</p>
 *
 * @see RigidBody
 * @see RapierWorld
 */
public class ColliderBody {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("PolyMech/MPS/Collider");

    /** contact skin 默认厚度（格）—— 与 MPS 的 {@code CONTACT_SKIN} 同值。 */
    public static final double CONTACT_SKIN = 0.02;

    public enum Type {
        BALL,
        CUBOID,
        CAPSULE,
        CONVEX,
        TRIMESH,
        VOXEL,
        COMPLEX_VOXEL,
        HEIGHTMAP,
        HALFSPACE
    }

    // ==================== 分离期参数 ====================

    private final Type type;
    private final Vector3d pos = new Vector3d();
    private final Quaterniond rotation = new Quaterniond();
    private double contactSkin = CONTACT_SKIN;

    private double ballRadius;
    private double hx, hy, hz;
    private boolean[] voxelCells;
    private int voxelSx, voxelSy, voxelSz;
    private double cellX = 1.0, cellY = 1.0, cellZ = 1.0;
    private double[] boxes;

    private double friction = 0.5;
    private double restitution;
    private int frictionRule = NativePhysics.RULE_AVERAGE;
    private int restitutionRule = NativePhysics.RULE_AVERAGE;
    private double density = -1.0; // <0 = 不改，用 Rapier 默认
    private int membership = 1;
    private int filter = -1;
    private boolean groupsExplicit;
    private boolean sensor;
    private int activeEvents;
    private double contactForceEventThreshold = -1.0;

    // ==================== 插入世界后 ====================

    private RapierWorld rapierWorld;
    /** 原生碰撞体 id；未插入为 -1。 */
    private long handle = -1L;
    /** 分离对象句柄（MPS 的 memory handle）：被摘出世界后才有值，插回时消费掉。 */
    private long memoryHandle = -1L;

    /** 分离对象句柄；未分离为 -1。 */
    public long getMemoryHandleValue() {
        return memoryHandle;
    }

    /** 被摘出世界（原生已复制到分离竞技场）。 */
    void markDetached(long mem) {
        this.memoryHandle = mem;
        this.rapierWorld = null;
        this.handle = -1L;
    }

    /** 从分离竞技场插回某个世界。 */
    boolean attachFromMemory(RapierWorld world, long worldColliderId) {
        this.memoryHandle = -1L;
        this.rapierWorld = world;
        this.handle = worldColliderId;
        return true;
    }

    /**
     * @param params 形状参数，约定见类注释（与 MPS 相同）
     */
    public ColliderBody(Type type, Vector3d pos, Quaterniond rotation, double contactSkin, Object... params) {
        this.type = type;
        this.pos.set(pos);
        this.rotation.set(rotation);
        if (Double.isFinite(contactSkin)) {
            this.contactSkin = contactSkin;
        }
        switch (type) {
            case BALL -> ballRadius = (Double) params[0];
            case CUBOID -> {
                hx = (Double) params[0];
                hy = (Double) params[1];
                hz = (Double) params[2];
            }
            case VOXEL -> {
                voxelCells = (boolean[]) params[0];
                voxelSx = (Integer) params[1];
                voxelSy = (Integer) params[2];
                voxelSz = (Integer) params[3];
                cellX = (Double) params[4];
                cellY = (Double) params[5];
                cellZ = (Double) params[6];
            }
            case COMPLEX_VOXEL -> boxes = (double[]) params[0];
            default -> throw new UnsupportedOperationException(
                    "ColliderBody.Type." + type + " 暂无原生对应实现（见 docs/mps-clone-plan.md 的 S5）");
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 落到原生层（由 {@link RapierWorld#addColliderBody} 调用）。
     *
     * @param bodyId 所属刚体的世界内 id
     * @return 是否成功
     */
    boolean attach(RapierWorld world, long bodyId) {
        if (isAttached()) {
            return false;
        }
        long worldHandle = world.rapierWorldHandle();
        long id;
        boolean grouped = PhysicsNatives.hasCollisionGroups();
        switch (type) {
            case BALL -> id = NativePhysics.colliderAttachBall(worldHandle, bodyId,
                    ballRadius, friction, restitution);
            case CUBOID -> id = grouped
                    ? NativePhysics.colliderAttachCuboidGrouped(worldHandle, bodyId, hx, hy, hz,
                            friction, restitution, membership, filter)
                    : NativePhysics.colliderAttachCuboid(worldHandle, bodyId, hx, hy, hz,
                            friction, restitution);
            case VOXEL -> {
                long[] cells = packVoxels();
                id = grouped
                        ? NativePhysics.colliderAttachVoxelsGrouped(worldHandle, bodyId,
                                cellX, cellY, cellZ, cells, friction, restitution, membership, filter)
                        : NativePhysics.colliderAttachVoxels(worldHandle, bodyId,
                                cellX, cellY, cellZ, cells, friction, restitution);
            }
            case COMPLEX_VOXEL -> id = PhysicsNatives.hasTier1()
                    ? NativePhysics.colliderAttachBoxes(worldHandle, bodyId, boxes,
                            friction, restitution, membership, filter)
                    : -1L;
            default -> id = -1L;
        }
        if (id <= 0) {
            return false;
        }
        this.rapierWorld = world;
        this.handle = id;
        applyLiveProperties(worldHandle, id);
        return true;
    }

    /** 把"插入后仍可改"的属性补齐（材质组合规则 / contact skin / 密度）。 */
    private void applyLiveProperties(long worldHandle, long colliderId) {
        if (PhysicsNatives.hasTier1()) {
            NativePhysics.colliderSetMaterial(worldHandle, colliderId,
                    friction, restitution, contactSkin, frictionRule, restitutionRule);
        }
        if (density >= 0.0 && PhysicsNatives.hasTier1()) {
            NativePhysics.colliderSetDensity(worldHandle, colliderId, density);
        }
    }

    void detach() {
        this.rapierWorld = null;
        this.handle = -1L;
    }

    /** 是否已插入世界。 */
    public boolean isAttached() {
        return handle > 0L && rapierWorld != null;
    }

    long worldHandle() {
        RapierWorld w = rapierWorld;
        return w == null ? -1L : w.rapierWorldHandle();
    }

    public Type getType() {
        return type;
    }

    /** 原生碰撞体 id（未插入为 -1）。 */
    public long getHandle() {
        return handle;
    }

    /** 布尔格 → 打包的格坐标数组（索引 {@code (x*sy + y)*sz + z}）。 */
    private long[] packVoxels() {
        long[] out = new long[voxelCells.length];
        int n = 0;
        for (int x = 0; x < voxelSx; x++) {
            for (int y = 0; y < voxelSy; y++) {
                for (int z = 0; z < voxelSz; z++) {
                    int idx = x + z * voxelSx + y * voxelSx * voxelSz;
                    if (idx < voxelCells.length && voxelCells[idx]) {
                        out[n++] = NativePhysics.packCell(x, y, z);
                    }
                }
            }
        }
        if (n == out.length) {
            return out;
        }
        long[] trimmed = new long[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    // ==================== 属性 ====================

    /**
     * 碰撞体自身记录的挂载位姿。
     *
     * <p>挂到刚体之后，实际位姿由刚体驱动；我们的原生层没有导出"按碰撞体 id 读位姿"，
     * 所以这里返回构造时记录的值（上层要用真实位姿时请读所属刚体）。</p>
     */
    public Vector3d getPos() {
        return new Vector3d(pos);
    }

    public void setPos(Vector3d p) {
        pos.set(p);
        // 碰撞体一旦挂到刚体上，位姿由刚体驱动；这里只更新分离期参数（与 MPS 的 setPos 语义一致）。
    }

    public void setFriction(double f) {
        this.friction = f;
        if (!isAttached()) {
            return;
        }
        // 照 MPS：改一个属性就调对应的那个 setter（ABI ≥ 6）；
        // 老 dll 上退回"整份材质重套"（Tier 1），结果一致但方法不同。
        if (PhysicsNatives.hasLiveColliderEdits()) {
            NativePhysics.colliderSetFriction(worldHandle(), handle, f);
        } else if (PhysicsNatives.hasTier1()) {
            applyLiveProperties(worldHandle(), handle);
        }
    }

    public void setFrictionCombineRule(int rule) {
        this.frictionRule = rule;
        if (!isAttached()) {
            return;
        }
        if (PhysicsNatives.hasLiveColliderEdits()) {
            NativePhysics.colliderSetFrictionCombineRule(worldHandle(), handle, rule);
        } else if (PhysicsNatives.hasTier1()) {
            applyLiveProperties(worldHandle(), handle);
        }
    }

    public void setRestitution(double r) {
        this.restitution = r;
        if (!isAttached()) {
            return;
        }
        if (PhysicsNatives.hasLiveColliderEdits()) {
            NativePhysics.colliderSetRestitution(worldHandle(), handle, r);
        } else if (PhysicsNatives.hasTier1()) {
            applyLiveProperties(worldHandle(), handle);
        }
    }

    public void setRestitutionCombineRule(int rule) {
        this.restitutionRule = rule;
        if (!isAttached()) {
            return;
        }
        if (PhysicsNatives.hasLiveColliderEdits()) {
            NativePhysics.colliderSetRestitutionCombineRule(worldHandle(), handle, rule);
        } else if (PhysicsNatives.hasTier1()) {
            applyLiveProperties(worldHandle(), handle);
        }
    }

    public double getFriction() {
        return friction;
    }

    public double getRestitution() {
        return restitution;
    }

    public double getContactSkin() {
        return contactSkin;
    }

    /**
     * 设置碰撞组 —— 照 MPS 的语义：**任何时刻都能改**。
     *
     * <p>ABI ≥ 6 时走 {@link NativePhysics#colliderSetCollisionGroups} 实时生效；
     * 老 dll 上退化成"只在挂载时生效"（若此刻已挂载，需要上层重建碰撞体）。</p>
     */
    public void setCollisionGroups(int memberships, int filter) {
        this.membership = memberships;
        this.filter = filter;
        this.groupsExplicit = true;
        if (!isAttached()) {
            return;
        }
        if (PhysicsNatives.hasLiveColliderEdits()) {
            NativePhysics.colliderSetCollisionGroups(worldHandle(), handle, memberships, filter);
        } else {
            LOGGER.warn("[PolyMech/MPS] 原生 ABI < {} 不支持实时改碰撞组：本次只记录，"
                            + "需重建碰撞体才生效（world=0x{} collider={}）",
                    PhysicsNatives.MIN_ABI_LIVE_COLLIDER, Long.toHexString(worldHandle()), handle);
        }
    }

    public int getMembership() {
        return membership;
    }

    public int getFilter() {
        return filter;
    }

    public boolean hasExplicitGroups() {
        return groupsExplicit;
    }

    public void setDensity(double d) {
        this.density = d;
        if (isAttached() && PhysicsNatives.hasTier1()) {
            NativePhysics.colliderSetDensity(worldHandle(), handle, d);
        }
    }

    public double getDensity() {
        if (isAttached() && PhysicsNatives.hasTier1()) {
            return NativePhysics.colliderGetDensity(worldHandle(), handle);
        }
        return density;
    }

    public void setSensor(boolean s) {
        this.sensor = s;
        if (isAttached() && PhysicsNatives.hasLiveColliderEdits()) {
            NativePhysics.colliderSetSensor(worldHandle(), handle, s);
        }
    }

    public boolean isSensor() {
        return sensor;
    }

    public void setActiveEvents(int events) {
        this.activeEvents = events;
        if (isAttached() && PhysicsNatives.hasLiveColliderEdits()) {
            NativePhysics.colliderSetActiveEvents(worldHandle(), handle, events);
        }
    }

    public int getActiveEvents() {
        return activeEvents;
    }

    public void setContactForceEventThreshold(double threshold) {
        this.contactForceEventThreshold = threshold;
        if (isAttached() && PhysicsNatives.hasLiveColliderEdits()) {
            NativePhysics.colliderSetContactForceEventThreshold(worldHandle(), handle, threshold);
        }
    }

    public double getContactForceEventThreshold() {
        return contactForceEventThreshold;
    }
}
