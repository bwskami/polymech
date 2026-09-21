package com.mss.polymech.mps.rapier.helper;

import com.mss.polymech.physics.NativePhysics;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 刚体 —— <b>与 {@code org.polaris2023.mps.rapier.helper.RigidBody} 同形的公开 API</b>
 * 的自有实现（clean-room，不复制 space 的任何代码；见 {@code docs/mps-clone-plan.md}）。
 *
 * <p><b>分离对象语义（MPS 的模型，本项目在 Java 侧等价仿真）</b>：构造时只把参数记在字段里，
 * <b>不碰原生层</b>；直到 {@link RapierWorld#addRigidBody(RigidBody)} 才落到
 * {@link NativePhysics#bodyCreate}，把返回的世界内 id 记进 {@link #handle}。
 * 这样就不再需要 MPS 的 builder 系列原生函数与 memory handle/Unsafe 释放。</p>
 *
 * <p><b>力的排队（对应 MPS 的 {@code forces} + {@code up(tick_time)}）</b>：
 * {@link #applyForce(Force)} 把"持续力"排进队列，每个物理子步前由
 * {@link RapierWorld#up()} 统一结算：按 {@code min(剩余时长/步长, 1)} 缩放后累加，
 * 先 {@code bodyResetForces} 再 {@code bodyAddForce}。剩余时长扣完的力自动出队。</p>
 *
 * @see ColliderBody
 * @see RapierWorld
 */
public class RigidBody {

    /** 刚体类型，与原生层 {@code BODY_*} 常量一一对应。 */
    public enum Type {
        DYNAMIC,
        FIXED,
        KINEMATIC_POSITION,
        KINEMATIC_VELOCITY
    }

    // ==================== 分离期的参数（插入世界前唯一存在的东西） ====================

    private final Type type;
    private final Vector3d pos = new Vector3d();
    private final Quaterniond rotation = new Quaterniond();
    private final Vector3d massCenter = new Vector3d();
    private double mass;
    private final Vector3d principalInertia = new Vector3d();
    private final Vector3d linvel = new Vector3d();
    private final Vector3d angvel = new Vector3d();
    private double linearDamping;
    private double angularDamping;
    private double gravityScale = 1.0;
    private boolean lockRotations;
    private boolean canSleep = true;
    private long userData1;
    private long userData2;

    // ==================== 插入世界后 ====================

    /** 所属世界；未插入为 null。 */
    RapierWorld rapierWorld;
    /** 世界内 id（{@code NativePhysics.bodyCreate} 的返回值）；未插入为 -1。 */
    long handle = -1L;
    /**
     * 分离对象句柄（MPS 的 memory handle）：被 {@code extractRigidBody} 摘出世界后才有值；
     * 插回世界（{@code insertRigidBody}）时被消费并清回 -1。
     */
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
    boolean attachFromMemory(RapierWorld world, long worldBodyId) {
        this.memoryHandle = -1L;
        this.rapierWorld = world;
        this.handle = worldBodyId;
        return true;
    }

    /** 持续力队列（MPS 的 {@code forces}）。 */
    private final Queue<Force> forces = new ConcurrentLinkedQueue<>();

    // ==================== 构造（照 MPS 的四个重载） ====================

    public RigidBody(Type type, Vector3d pos, Quaterniond rotation, Vector3d massCenter,
                     double mass, Vector3d principalInertia, Vector3d linvel, Vector3d angvel,
                     double linearDamping, double angularDamping, double gravityScale) {
        this(type, pos, rotation, massCenter, mass, principalInertia, linvel, angvel,
                linearDamping, angularDamping, gravityScale, false);
    }

    public RigidBody(Type type, Vector3d pos, Quaterniond rotation, Vector3d massCenter,
                     double mass, Vector3d principalInertia, Vector3d linvel, Vector3d angvel,
                     double linearDamping, double angularDamping, double gravityScale,
                     boolean lockRotations) {
        this.type = type;
        this.pos.set(pos);
        this.rotation.set(rotation);
        this.massCenter.set(massCenter);
        this.mass = mass;
        this.principalInertia.set(principalInertia);
        this.linvel.set(linvel);
        this.angvel.set(angvel);
        this.linearDamping = linearDamping;
        this.angularDamping = angularDamping;
        this.gravityScale = gravityScale;
        this.lockRotations = lockRotations;
    }

    public RigidBody(Type type, Vector3d pos, Quaterniond rotation, double mass) {
        this(type, pos, rotation, new Vector3d(), mass, new Vector3d(), new Vector3d(), new Vector3d(),
                0.0, 0.0, 1.0, false);
    }

    public RigidBody(Type type, Vector3d pos, Quaterniond rotation) {
        this(type, pos, rotation, 0.0);
    }

    // ==================== 生命周期 ====================

    /**
     * 落到原生层（由 {@link RapierWorld#addRigidBody} 调用）。
     *
     * @return 是否成功；失败时本对象保持"未插入"状态
     */
    boolean attach(RapierWorld world, long worldHandle) {
        if (isAttached()) {
            return false;
        }
        int bodyType = switch (type) {
            case DYNAMIC -> NativePhysics.BODY_DYNAMIC;
            case FIXED -> NativePhysics.BODY_FIXED;
            case KINEMATIC_POSITION -> NativePhysics.BODY_KINEMATIC_POSITION;
            case KINEMATIC_VELOCITY -> NativePhysics.BODY_KINEMATIC_VELOCITY;
        };
        long id = NativePhysics.bodyCreate(worldHandle, bodyType, pos.x, pos.y, pos.z,
                rotation.x, rotation.y, rotation.z, rotation.w, mass);
        if (id <= 0) {
            return false;
        }
        this.rapierWorld = world;
        this.handle = id;
        // 分离期记下的属性在插入时一次补齐（原生没有 builder，只能建体后设）
        if (lockRotations) {
            NativePhysics.bodyLockRotations(worldHandle, id, true);
        }
        if (linearDamping != 0.0 || angularDamping != 0.0) {
            NativePhysics.bodySetDamping(worldHandle, id, linearDamping, angularDamping);
        }
        if (gravityScale != 1.0) {
            NativePhysics.bodySetGravityScale(worldHandle, id, gravityScale);
        }
        if (!canSleep) {
            NativePhysics.bodyWakeUp(worldHandle, id);
        }
        if (massCenter.lengthSquared() > 0.0 && mass > 0.0) {
            NativePhysics.bodySetAdditionalMassProperties(worldHandle, id,
                    massCenter.x, massCenter.y, massCenter.z, mass,
                    principalInertia.x, principalInertia.y, principalInertia.z);
        }
        if (linvel.lengthSquared() > 0.0 || angvel.lengthSquared() > 0.0) {
            NativePhysics.bodySetMotion(worldHandle, id,
                    linvel.x, linvel.y, linvel.z, angvel.x, angvel.y, angvel.z, true);
        }
        return true;
    }

    /** 从世界摘除（对应 MPS 的 {@code world.removeRigidBody}）。 */
    void detach() {
        this.rapierWorld = null;
        this.handle = -1L;
    }

    /** 是否已插入世界（MPS: {@code handle != -1}）。 */
    public boolean isAttached() {
        return handle >= 0L && rapierWorld != null;
    }

    /** 供工具类判断（同包）。 */
    long worldHandle() {
        RapierWorld w = rapierWorld;
        return w == null ? -1L : w.rapierWorldHandle();
    }

    public Type getType() {
        return type;
    }

    /** MPS 里叫 memory_handle；我们只有世界内 id，两者合并成一个概念。 */
    public long getMemoryHandle() {
        return handle;
    }

    public long getHandle() {
        return handle;
    }

    // ==================== 每子步：力队列结算 ====================

    /**
     * 结算本子步的持续力 —— 对应 MPS 的 {@code RigidBody.up(tick_time)}。
     *
     * <p>在<b>物理步进线程</b>上、{@code worldStep} 之前调用。</p>
     */
    void up(double tickTime) {
        if (!isAttached() || tickTime <= 0.0) {
            return;
        }
        if (type != Type.DYNAMIC) {
            return; // 非动态体不接持续力（与 Rapier 语义一致）
        }
        long world = worldHandle();
        Vector3d out = new Vector3d();
        int count = forces.size();
        for (int i = 0; i < count; i++) {
            Force force = forces.poll();
            if (force == null) {
                break;
            }
            double scale = Math.min(force.remainingTime / tickTime, 1.0);
            out.fma(scale, force.force);
            force.remainingTime -= tickTime;
            if (force.remainingTime > 0.0) {
                forces.add(force);
            }
        }
        NativePhysics.bodyResetForces(world, handle);
        if (out.lengthSquared() > 0.0) {
            NativePhysics.bodyAddForce(world, handle, out.x, out.y, out.z);
        }
    }

    // ==================== 力 / 冲量 ====================

    /** 排入一个"持续 {@code duration} 秒"的力（MPS 的 {@code applyForce(Force)}）。 */
    public void applyForce(Force force) {
        if (force != null) {
            forces.add(force);
        }
    }

    /** 力的快照（MPS: {@code getForcesSnapshot()}，供天体/飞机的力同步）。 */
    public List<Force> getForcesSnapshot() {
        return new ArrayList<>(forces);
    }

    /** 一次性冲量（kg·m/s）。未插入世界时与 MPS 一样是 no-op（MPS 只在有 world 时入队）。 */
    public void applyImpulse(Vector3dc impulse) {
        if (isAttached()) {
            NativePhysics.bodyApplyImpulse(worldHandle(), handle, impulse.x(), impulse.y(), impulse.z());
        }
    }

    public void applyTorqueImpulse(Vector3dc impulse) {
        if (isAttached()) {
            NativePhysics.bodyApplyTorqueImpulse(worldHandle(), handle, impulse.x(), impulse.y(), impulse.z());
        }
    }

    public void addForce(Vector3dc f) {
        if (isAttached()) {
            NativePhysics.bodyAddForce(worldHandle(), handle, f.x(), f.y(), f.z());
        }
    }

    public void addForceAtPoint(Vector3dc f, Vector3dc point) {
        if (isAttached()) {
            NativePhysics.bodyAddForceAtPoint(worldHandle(), handle,
                    f.x(), f.y(), f.z(), point.x(), point.y(), point.z());
        }
    }

    public void addTorque(Vector3dc t) {
        if (isAttached()) {
            NativePhysics.bodyAddTorque(worldHandle(), handle, t.x(), t.y(), t.z());
        }
    }

    // ==================== 位姿 / 速度 ====================

    public Vector3d getPos() {
        if (!isAttached()) {
            return new Vector3d(pos);
        }
        double[] out = new double[3];
        if (!NativePhysics.bodyReadTranslation(worldHandle(), handle, out)) {
            return null;
        }
        return new Vector3d(out[0], out[1], out[2]);
    }

    public void setPos(Vector3dc p) {
        pos.set(p);
        if (isAttached()) {
            NativePhysics.bodySetTranslation(worldHandle(), handle, p.x(), p.y(), p.z());
        }
    }

    public Quaterniond getRotation() {
        if (!isAttached()) {
            return new Quaterniond(rotation);
        }
        double[] out = new double[4];
        if (!NativePhysics.bodyReadRotation(worldHandle(), handle, out)) {
            return null;
        }
        return new Quaterniond(out[0], out[1], out[2], out[3]);
    }

    public void setRotation(Quaterniond q) {
        rotation.set(q);
        if (isAttached()) {
            NativePhysics.bodySetRotation(worldHandle(), handle, q.x, q.y, q.z, q.w, true);
        }
    }

    public Vector3d getLinvel() {
        if (!isAttached()) {
            return new Vector3d(linvel);
        }
        double[] out = new double[3];
        if (!NativePhysics.bodyReadVelocity(worldHandle(), handle, out)) {
            return null;
        }
        return new Vector3d(out[0], out[1], out[2]);
    }

    public void setLinvel(Vector3dc v) {
        setLinvel(v, true);
    }

    public void setLinvel(Vector3dc v, boolean wake) {
        linvel.set(v);
        if (isAttached()) {
            // 原生 bodySetMotion 会校验全部 6 个分量必须有限（NaN 会被拒），
            // 所以想"只改线速度"就必须先把当前角速度读出来一起写回。
            double[] av = new double[3];
            if (!NativePhysics.bodyReadAngvel(worldHandle(), handle, av)) {
                av[0] = av[1] = av[2] = 0.0;
            }
            NativePhysics.bodySetMotion(worldHandle(), handle,
                    v.x(), v.y(), v.z(), av[0], av[1], av[2], wake);
        }
    }

    public Vector3d getAngvel() {
        if (!isAttached()) {
            return new Vector3d(angvel);
        }
        double[] out = new double[3];
        if (!NativePhysics.bodyReadAngvel(worldHandle(), handle, out)) {
            return null;
        }
        return new Vector3d(out[0], out[1], out[2]);
    }

    public void setAngvel(Vector3dc v) {
        angvel.set(v);
        if (isAttached()) {
            NativePhysics.bodySetAngvel(worldHandle(), handle, v.x(), v.y(), v.z());
        }
    }

    /** 运动学（位置型）体的下一帧目标位置。 */
    public void setNextKinematicPositionDirect(Vector3dc p) {
        if (isAttached()) {
            NativePhysics.bodySetNextKinematicTranslation(worldHandle(), handle, p.x(), p.y(), p.z());
        }
    }

    public void setNextKinematicRotation(Quaterniond q) {
        if (isAttached()) {
            NativePhysics.bodySetNextKinematicRotation(worldHandle(), handle, q.x, q.y, q.z, q.w);
        }
    }

    // ==================== 状态 ====================

    /** 切换刚体类型（对应 MPS 的 {@code setStatus}）。 */
    public void setStatus(Type newType, boolean wakeUp) {
        if (isAttached()) {
            int bodyType = switch (newType) {
                case DYNAMIC -> NativePhysics.BODY_DYNAMIC;
                case FIXED -> NativePhysics.BODY_FIXED;
                case KINEMATIC_POSITION -> NativePhysics.BODY_KINEMATIC_POSITION;
                case KINEMATIC_VELOCITY -> NativePhysics.BODY_KINEMATIC_VELOCITY;
            };
            NativePhysics.bodySetBodyType(worldHandle(), handle, bodyType);
            if (wakeUp) {
                NativePhysics.bodyWakeUp(worldHandle(), handle);
            }
        }
    }

    public boolean getIsSleeping() {
        return isAttached() && NativePhysics.bodyGetIsSleeping(worldHandle(), handle);
    }

    public void setWakeUp() {
        if (isAttached()) {
            NativePhysics.bodyWakeUp(worldHandle(), handle);
        }
    }

    public void setSleep() {
        if (isAttached()) {
            NativePhysics.bodySleep(worldHandle(), handle);
        }
    }

    public double getMass() {
        return isAttached() ? NativePhysics.bodyGetMass(worldHandle(), handle) : mass;
    }

    public void setMass(double m) {
        this.mass = m; // 与 MPS 一致：建体后不再改质量（Rapier 需要重建附加质量属性）
    }

    /**
     * 开关 CCD —— 方法名与 MPS 的 {@code RigidBody.setCCD(boolean)} 对齐。
     *
     * <p>名字对齐是有意义的：space 的调用点要能 1:1 映射过来，
     * 才不会因为"语义一样但名字不同"再写一层适配（那就是新的殊途同归）。</p>
     */
    public void setCCD(boolean enabled) {
        if (isAttached()) {
            NativePhysics.bodyEnableCcd(worldHandle(), handle, enabled);
        }
    }

    public void setDamping(double linear, double angular) {
        this.linearDamping = linear;
        this.angularDamping = angular;
        if (isAttached()) {
            NativePhysics.bodySetDamping(worldHandle(), handle, linear, angular);
        }
    }

    public void setGravityScale(double scale) {
        this.gravityScale = scale;
        if (isAttached()) {
            NativePhysics.bodySetGravityScale(worldHandle(), handle, scale);
        }
    }

    public void lockRotations(boolean locked) {
        this.lockRotations = locked;
        if (isAttached()) {
            NativePhysics.bodyLockRotations(worldHandle(), handle, locked);
        }
    }

    public long getUserData1() {
        return userData1;
    }

    public long getUserData2() {
        return userData2;
    }

    public void setUserData(long a, long b) {
        this.userData1 = a;
        this.userData2 = b;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RigidBody rb && isAttached() && rb.isAttached()
                && rb.handle == handle && rb.rapierWorld == rapierWorld;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(handle);
    }

    // ==================== 持续力 ====================

    /**
     * 一个"持续一定时长"的力 —— 同名、同语义照 MPS 的 {@code RigidBody.Force}。
     *
     * <p>结算规则见 {@link RigidBody#up(double)}：每子步按剩余时长缩放累加，
     * 相当于把这股力摊到 {@code duration} 秒里。</p>
     */
    public static class Force {
        private final String name;
        private final Vector3d force;
        private double remainingTime;

        public Force(Vector3dc force, double duration) {
            this(null, force, duration);
        }

        public Force(String name, Vector3dc force, double duration) {
            this.name = name;
            this.force = new Vector3d(force);
            this.remainingTime = duration;
        }

        public String getName() {
            return name;
        }

        public Vector3d getForce() {
            return new Vector3d(force);
        }

        public double getRemainingTime() {
            return remainingTime;
        }
    }
}
