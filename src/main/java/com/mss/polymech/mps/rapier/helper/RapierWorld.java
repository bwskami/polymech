package com.mss.polymech.mps.rapier.helper;

import com.mss.polymech.physics.NativePhysics;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 物理世界 —— <b>与 {@code org.polaris2023.mps.rapier.helper.RapierWorld} 同形的公开 API</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <p>后端是我们自己的 {@code polymech_physics}：一个世界 = 一个 {@link NativePhysics} 世界句柄。
 * 与 MPS 的差别只在"对象什么时候真正落到原生"：</p>
 * <ul>
 *   <li><b>刚体</b>：{@link #addRigidBody} 立即 {@code bodyCreate}（上层常在加入后马上读位姿）；</li>
 *   <li><b>碰撞体</b>：{@link #addColliderBody} 只入队，到 {@link #up()} 才创建 —— 这样
 *       MPS 的调用顺序「先 addColliderBody、再 setCollisionGroups」能生效
 *       （我们的原生分组只能在建体时给）。MPS 自己也是把 add 排进 operation buffer、
 *       在 {@code up()} 里统一 flush，时序一致。</li>
 * </ul>
 *
 * <p>无父刚体的碰撞体（MPS 的 {@code addColliderBody(cb)}）会挂到一个隐式固定体上 ——
 * 与 {@code PhysicsTerrain} 的做法相同。</p>
 */
public class RapierWorld {

    /** 世界句柄（{@link NativePhysics#worldCreate} 的返回值）。 */
    private final long rapierWorld;

    private final Set<RigidBody> rigidBodies = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<ColliderBody> colliderBodies = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final List<PendingCollider> pendingColliders = new ArrayList<>();
    /** 无父碰撞体用的隐式固定体（世界内 id）。 */
    private final List<Long> implicitHosts = new ArrayList<>();
    private final List<Runnable> tickListeners = new CopyOnWriteArrayList<>();

    private final Vector3d gravity = new Vector3d();
    public double tick_time = 0.01;
    public long tick;
    public double time;
    private double tickTimeBuffer = 0.01;

    public RapierWorld(double gx, double gy, double gz) {
        this.rapierWorld = NativePhysics.worldCreate(gx, gy, gz);
        this.gravity.set(gx, gy, gz);
        if (rapierWorld > 0) {
            NativePhysics.worldSetTimestep(rapierWorld, tick_time);
        }
    }

    public RapierWorld(Vector3d g) {
        this(g.x(), g.y(), g.z());
    }

    /** 世界句柄；创建失败为 0 或负。 */
    public long rapierWorldHandle() {
        return rapierWorld;
    }

    /** 是否可用。 */
    public boolean isAvailable() {
        return rapierWorld > 0;
    }

    // ==================== 步进 ====================

    /**
     * 步进一次 —— MPS 的 {@code step()}：先跑 tick listener，再走原生步进。
     *
     * <p>注意 MPS 的 {@code up()} 是"步进前的准备"，由调用方（物理线程）在 {@code step()} 之前调。</p>
     */
    public void step() {
        if (!isAvailable()) {
            return;
        }
        for (Runnable listener : tickListeners) {
            listener.run();
        }
        NativePhysics.worldStep(rapierWorld);
        tick++;
        time += tick_time;
    }

    /**
     * 步进前的准备 —— MPS 的 {@code up()}：
     * 结算各刚体的持续力、flush 待创建的碰撞体、对齐本步时长的原生设置。
     */
    public void up() {
        if (!isAvailable()) {
            return;
        }
        this.tick_time = tickTimeBuffer;
        NativePhysics.worldSetTimestep(rapierWorld, tick_time);
        for (RigidBody rb : rigidBodies) {
            rb.up(tick_time);
        }
        if (!pendingColliders.isEmpty()) {
            List<PendingCollider> snapshot = new ArrayList<>(pendingColliders);
            pendingColliders.clear();
            for (PendingCollider pc : snapshot) {
                long host = pc.parent != null ? pc.parent.handle : implicitHost();
                if (host <= 0) {
                    continue;
                }
                if (pc.collider.attach(this, host)) {
                    colliderBodies.add(pc.collider);
                }
            }
        }
    }

    public void addTickListener(Runnable r) {
        if (r != null) {
            tickListeners.add(r);
        }
    }

    public void removeTickListener(Runnable r) {
        tickListeners.remove(r);
    }

    public void clearTickListeners() {
        tickListeners.clear();
    }

    // ==================== 刚体 ====================

    public void addRigidBody(RigidBody rb) {
        if (rb == null || rigidBodies.contains(rb)) {
            return;
        }
        if (rb.attach(this, rapierWorld)) {
            rigidBodies.add(rb);
        }
    }

    public boolean removeRigidBody(RigidBody rb) {
        if (rb == null || !rigidBodies.remove(rb)) {
            return false;
        }
        NativePhysics.bodyDestroy(rapierWorld, rb.handle);
        rb.detach();
        return true;
    }

    public boolean hasRigidBody(RigidBody rb) {
        return rigidBodies.contains(rb);
    }

    public Set<RigidBody> listRigidBody() {
        return new HashSet<>(rigidBodies);
    }

    public RigidBody getRigidBody(long bodyHandle) {
        for (RigidBody rb : rigidBodies) {
            if (rb.handle == bodyHandle) {
                return rb;
            }
        }
        return null;
    }

    /**
     * 按原生句柄反查碰撞体 —— 对应 MPS 的 {@code getColliderBody(long)}。
     *
     * <p><b>为什么需要它</b>：原生射线查询只回一个 {@code colliderId}（句柄），
     * 而调用方要判断"这一击打中的是地形还是某个物理体" ——
     * 只有把句柄反查回 {@link ColliderBody}，才能再经
     * {@code PhysicalWorld.getPhysicalBody(collider)} 拿到体
     * （见 {@code PhysicalRaycast#castTerrain} 排除物理体的那一步）。
     * 少了这个反查，"站在船上挖地"就会打到自己脚下的船。</p>
     *
     * <p>线性扫描是 space 的做法：单维度里碰撞体是百量级，而每次射线只查一次，
     * 建句柄→对象的反查表反而要额外维护增删一致性。</p>
     */
    public ColliderBody getColliderBody(long handle) {
        if (!isAvailable()) {
            return null;
        }
        for (ColliderBody cb : colliderBodies) {
            if (cb.getHandle() == handle) {
                return cb;
            }
        }
        return null;
    }

    public int getRigidBodiesSize() {
        if (!isAvailable()) {
            return -1;
        }
        // ABI ≥ 6 有对齐 MPS 命名的 worldGetRigidBodySetSize；否则用我们原有的 worldBodyCount。
        return com.mss.polymech.physics.PhysicsNatives.hasLiveColliderEdits()
                ? NativePhysics.worldGetRigidBodySetSize(rapierWorld)
                : NativePhysics.worldBodyCount(rapierWorld);
    }

    // ==================== 分离对象（ABI 8）：跨维度搬运 ====================

    /**
     * 把刚体摘出世界（复制进分离竞技场并移除原体）—— 对应 MPS 的 {@code extractRigidBody}。
     *
     * <p>这是 `dimensionLeapPhysicalBody`（把船搬去另一个维度）的第一步：
     * 摘出来 → {@link #insertRigidBody} 插进目标世界。速度、角速度、质量属性、
     * 碰撞组、材质全部随对象一起过去，不需要调用方逐项补齐。</p>
     *
     * @return 分离句柄；失败返回 -1
     */
    public long extractRigidBody(RigidBody rb) {
        if (rb == null || !rigidBodies.remove(rb)) {
            return -1L;
        }
        long mem = NativePhysics.worldCopyRigidBody(rapierWorld, rb.handle);
        NativePhysics.worldRemoveRigidBody(rapierWorld, rb.handle, true);
        rb.markDetached(mem);
        return mem;
    }

    /** 把碰撞体摘出世界（复制 + 移除）—— 对应 MPS 的 {@code extractColliderBody}。 */
    public long extractColliderBody(ColliderBody cb) {
        if (cb == null) {
            return -1L;
        }
        pendingColliders.removeIf(pc -> pc.collider == cb);
        if (!colliderBodies.remove(cb)) {
            return -1L;
        }
        long mem = NativePhysics.worldCopyCollider(rapierWorld, cb.getHandle());
        NativePhysics.worldRemoveCollider(rapierWorld, cb.getHandle(), true);
        cb.markDetached(mem);
        return mem;
    }

    /** 把分离的刚体插进本世界（句柄被消费）。 */
    public boolean insertRigidBody(RigidBody rb) {
        if (rb == null) {
            return false;
        }
        long mem = rb.getMemoryHandleValue();
        if (mem <= 0) {
            return false;
        }
        long id = NativePhysics.worldInsertRigidBody(rapierWorld, mem);
        if (id <= 0) {
            return false;
        }
        rb.attachFromMemory(this, id);
        rigidBodies.add(rb);
        return true;
    }

    /** 把分离的碰撞体插进本世界（{@code parent} 为 null 时无父体）。 */
    public boolean insertColliderBody(ColliderBody cb, RigidBody parent) {
        if (cb == null) {
            return false;
        }
        long mem = cb.getMemoryHandleValue();
        if (mem <= 0) {
            return false;
        }
        long id = parent != null && parent.isAttached()
                ? NativePhysics.worldInsertColliderWithParent(rapierWorld, mem, parent.handle)
                : NativePhysics.worldInsertCollider(rapierWorld, mem);
        if (id <= 0) {
            return false;
        }
        cb.attachFromMemory(this, id);
        colliderBodies.add(cb);
        return true;
    }

    /** 丢弃一个没插回世界的分离对象（否则它会一直留在分离竞技场里）。 */
    public void freeDetached(long memoryHandle) {
        if (memoryHandle > 0L) {
            NativePhysics.RustMemoryFree(memoryHandle);
        }
    }

    // ==================== 碰撞体 ====================

    /** 无父碰撞体（挂在隐式固定体上）。 */
    public void addColliderBody(ColliderBody cb) {
        addColliderBody(cb, null);
    }

    public void addColliderBody(ColliderBody cb, RigidBody parent) {
        if (cb == null || cb.isAttached()) {
            return;
        }
        pendingColliders.add(new PendingCollider(cb, parent));
    }

    public boolean removeColliderBody(ColliderBody cb) {
        if (cb == null) {
            return false;
        }
        pendingColliders.removeIf(pc -> pc.collider == cb);
        if (!colliderBodies.remove(cb)) {
            return false;
        }
        // ABI ≥ 7 有 worldRemoveCollider，按句柄真删；老 dll 上只能摘记录
        // （碰撞体会留在原生世界里直到刚体被销毁）——降级路径会告警。
        if (cb.isAttached() && com.mss.polymech.physics.PhysicsNatives.hasWorldRemoval()) {
            NativePhysics.worldRemoveCollider(rapierWorld, cb.getHandle(), true);
        }
        cb.detach();
        return true;
    }

    public boolean hasColliderBody(ColliderBody cb) {
        return colliderBodies.contains(cb);
    }

    public Set<ColliderBody> listColliderBody() {
        return new HashSet<>(colliderBodies);
    }

    public int getColliderBodiesSize() {
        return colliderBodies.size();
    }

    /** 隐式固定体（惰性创建，全局共用一个）。 */
    private long implicitHost() {
        if (!implicitHosts.isEmpty()) {
            return implicitHosts.get(0);
        }
        long id = NativePhysics.bodyCreate(rapierWorld, NativePhysics.BODY_FIXED,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0);
        if (id > 0) {
            implicitHosts.add(id);
        }
        return id;
    }

    // ==================== 重力 / 时长 ====================

    public Vector3d getG() {
        // ABI ≥ 6 有 worldGetGravity，直接问原生（与 MPS 的 getG() 同源）；
        // 老 dll 上退回本地记录值 —— 它与 setG() 始终同步，不会失真。
        if (isAvailable() && com.mss.polymech.physics.PhysicsNatives.hasLiveColliderEdits()) {
            double[] out = new double[3];
            if (NativePhysics.worldGetGravity(rapierWorld, out)) {
                return new Vector3d(out[0], out[1], out[2]);
            }
        }
        return new Vector3d(gravity);
    }

    public void setG(Vector3d g) {
        gravity.set(g);
        if (isAvailable()) {
            NativePhysics.worldSetGravity(rapierWorld, g.x(), g.y(), g.z());
        }
    }

    public double getTickTime() {
        return tick_time;
    }

    public void setTickTime(double dt) {
        this.tickTimeBuffer = dt;
    }

    // ==================== 射线 ====================

    /**
     * 射线查询 —— MPS 的 {@code castRay(...)}。
     *
     * <p>原生 {@code worldCastRay} 的 out 布局为 {@code [toi, nx, ny, nz, colliderId]}。</p>
     *
     * @return 命中信息；未命中为 null
     */
    public RayHit castRay(Vector3dc origin, Vector3dc direction, double maxToi,
                          int memberships, int filter) {
        if (!isAvailable()) {
            return null;
        }
        double[] out = new double[5];
        boolean hit = NativePhysics.worldCastRay(rapierWorld,
                origin.x(), origin.y(), origin.z(),
                direction.x(), direction.y(), direction.z(),
                maxToi, memberships, filter, out);
        if (!hit) {
            return null;
        }
        return new RayHit((long) out[4], out[0], new Vector3d(out[1], out[2], out[3]), 0L);
    }

    // ==================== 释放 ====================

    /** 销毁世界（对应 MPS 的 {@code free()}）。 */
    public void free() {
        if (isAvailable()) {
            NativePhysics.worldDestroy(rapierWorld);
        }
        rigidBodies.clear();
        colliderBodies.clear();
        pendingColliders.clear();
        implicitHosts.clear();
        tickListeners.clear();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RapierWorld w && w.rapierWorld == rapierWorld;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(rapierWorld);
    }

    private record PendingCollider(ColliderBody collider, RigidBody parent) {
    }

    /** 射线命中（同 MPS 的 {@code RapierWorld.RayHit}）。 */
    public record RayHit(long colliderHandle, double toi, Vector3d normal, long feature) {
    }
}
