package com.mss.polymech.physics;

/**
 * 物理原生层（Rust + Rapier f64）的 JNI 声明。
 *
 * <p>本类只做「Java ↔ Rust」的类型映射，不含任何业务逻辑，也不依赖 Minecraft 类，
 * 因此可以脱离游戏单独编译与测试。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>所有长度/质量均为 double（Rust 侧 f64），与 Rapier 的 f64 精度一致；</li>
 *   <li>world/body/collider 都是 {@code long} 句柄，0 或负值表示无效；</li>
 *   <li>Rust 侧所有入口都做非法值防护，不会 panic 跨 FFI 边界；</li>
 *   <li>调用前必须先由 {@link PhysicsNatives} 成功加载原生库并校验 ABI 版本。</li>
 * </ul>
 */
public final class NativePhysics {

    /** 刚体类型，与 Rust 侧约定一致。 */
    public static final int BODY_DYNAMIC = 0;
    public static final int BODY_FIXED = 1;
    public static final int BODY_KINEMATIC_POSITION = 2;
    public static final int BODY_KINEMATIC_VELOCITY = 3;

    private NativePhysics() {
    }

    /** 原生 ABI 版本；与 Java 侧期望值不一致时必须拒绝使用。 */
    public static native int abiVersion();

    /** 创建物理世界（重力，单位 m/s²）；失败返回 0。 */
    public static native long worldCreate(double gx, double gy, double gz);

    /** 销毁物理世界。 */
    public static native void worldDestroy(long world);

    /** 设置重力。 */
    public static native boolean worldSetGravity(long world, double gx, double gy, double gz);

    /** 设置固定步长（秒），有效范围 (0, 1]。 */
    public static native boolean worldSetTimestep(long world, double dt);

    /** 步进一次。 */
    public static native boolean worldStep(long world);

    /**
     * 移除挂在指定刚体上的所有碰撞体（方块被破坏/放置后重建体素碰撞体时用）。
     *
     * @return 被移除的碰撞体数量；-1 表示刚体不存在
     */
    public static native int bodyClearColliders(long world, long body);

    /** 世界内刚体数量（诊断）。 */
    public static native int worldBodyCount(long world);

    /**
     * 创建刚体。
     *
     * @param bodyType 见 {@link #BODY_DYNAMIC} 等常量
     * @param qx/qy/qz/qw 旋转四元数
     * @param mass 附加质量；&le;0 表示由碰撞体推导
     * @return 刚体 id；失败返回 -1
     */
    public static native long bodyCreate(long world, int bodyType,
                                        double x, double y, double z,
                                        double qx, double qy, double qz, double qw,
                                        double mass);

    /** 删除刚体（连同其碰撞体）。 */
    public static native boolean bodyDestroy(long world, long body);

    /** 读取位置到 out[0..2]。 */
    public static native boolean bodyReadTranslation(long world, long body, double[] out);

    /** 读取旋转四元数 (x,y,z,w) 到 out[0..3]。 */
    public static native boolean bodyReadRotation(long world, long body, double[] out);

    /** 直接设置位置。 */
    public static native boolean bodySetTranslation(long world, long body, double x, double y, double z);

    /**
     * 切换刚体类型（见 {@link #BODY_DYNAMIC} 等常量）。
     * 用于"恢复存档时先冻结为固定体，玩家靠近再切回动态"。
     */
    public static native boolean bodySetBodyType(long world, long body, int bodyType);

    /** 累加一个持续力（牛顿）；配合 {@link #bodyResetForces} 实现"速度伺服"。 */
    public static native boolean bodyAddForce(long world, long body, double fx, double fy, double fz);

    /** 清除累加的持续力（每个控制周期调用一次，避免力无限累积）。 */
    public static native boolean bodyResetForces(long world, long body);

    /** 锁定/解锁旋转（实体接管时锁定，避免高瘦碰撞箱翻倒）。 */
    public static native boolean bodyLockRotations(long world, long body, boolean locked);

    /** 读取线速度到 out[0..2]（m/s）。 */
    public static native boolean bodyReadVelocity(long world, long body, double[] out);

    /** 设置线速度（m/s）。 */
    public static native boolean bodySetVelocity(long world, long body, double vx, double vy, double vz);

    /**
     * 一次性写入线速度 + 角速度，并显式控制是否唤醒。
     *
     * <p>对应 MPS 的 {@code rigidBodySetLinvel(..., wake)} / {@code rigidBodySetAngvel(..., wake)}：
     * {@code changed=false} 时不唤醒 —— 静置的刚体才能进入 Rapier 的休眠，
     * 不再每步被求解、也不会被同步噪声反复扰动（表现为姿态抖动/朝向漂移）。</p>
     */
    public static native boolean bodySetMotion(long world, long body,
                                               double vx, double vy, double vz,
                                               double ax, double ay, double az,
                                               boolean changed);

    /** 设置旋转姿态（四元数 x,y,z,w）。 */
    public static native boolean bodySetRotation(long world, long body,
                                                 double qx, double qy, double qz, double qw,
                                                 boolean wake);

    /** 刚体是否处于休眠。 */
    public static native boolean bodyGetIsSleeping(long world, long body);

    /** 主动唤醒。 */
    public static native boolean bodyWakeUp(long world, long body);

    /** 强制休眠（结构冻结时用）。 */
    public static native boolean bodySleep(long world, long body);

    /** 读取角速度到 out[0..2]（rad/s）。 */
    public static native boolean bodyReadAngvel(long world, long body, double[] out);

    /** 设置角速度（rad/s）。物理体自转同步用：客户端碰撞体靠它跟着转，而不是停在建体姿态。 */
    public static native boolean bodySetAngvel(long world, long body, double ax, double ay, double az);

    /** 施加冲量（单位 kg·m/s）。 */
    public static native boolean bodyApplyImpulse(long world, long body, double x, double y, double z);

    /** 挂盒碰撞体（参数为半长），返回碰撞体 id；失败返回 -1。 */
    public static native long colliderAttachCuboid(long world, long body,
                                                   double hx, double hy, double hz,
                                                   double friction, double restitution);

    /** 挂球碰撞体，返回碰撞体 id；失败返回 -1。 */
    public static native long colliderAttachBall(long world, long body,
                                                 double radius,
                                                 double friction, double restitution);

    /**
     * 挂<b>体素碰撞体</b>：把一块方块区域一次性变成 Rapier 的 {@code Voxels} 形状。
     *
     * <p>网格坐标 (x,y,z) 的体素在刚体局部空间中占据
     * {@code [(x,y,z), (x,y,z)+1] * cellSize}（即中心在整格中心），
     * 与 Minecraft 的方块坐标一一对应 —— 方块 {@code (bx,by,bz)} 直接填 {@code (bx,by,bz)} 即可。</p>
     *
     * @param cellSizeX/Y/Z 单体素尺寸（通常 1.0）
     * @param cells         打包后的网格坐标，见 {@link #packCell(int, int, int)}
     * @return 碰撞体 id；失败返回 -1
     */
    public static native long colliderAttachVoxels(long world, long body,
                                                   double cellSizeX, double cellSizeY, double cellSizeZ,
                                                   long[] cells,
                                                   double friction, double restitution);

    /**
     * 把网格坐标打包进一个 long（各占 21 位有符号，范围 ±1,048,575）。
     * 与 Rust 侧 {@code sign21} 解包逻辑对应。
     */
    public static long packCell(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (long) (z & 0x1FFFFF);
    }

    /**
     * 原生自检：内部构建「地面 + 10m 自由落体立方体」，步进后返回立方体 Y。
     * 期望值约 0.5（半边长）。
     */
    public static native double selftest(int steps);
}
