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

    // ==================== 碰撞组（ABI 4 起） ====================

    /**
     * 挂盒碰撞体并指定<b>碰撞组</b>（membership / filter 位掩码）。
     *
     * <p><b>Rapier 的交互判定是双向的</b>：A 与 B 交互 ⟺
     * {@code (A.membership & B.filter) != 0} 且 {@code (B.membership & A.filter) != 0}。
     * 所以"让两边互相忽略"必须<b>两边都设</b>，只改一边不生效。</p>
     *
     * <p><b>space 0.1.3 的分组方案</b>（照 {@code docs/space-decompile.md}）：</p>
     * <ul>
     *   <li>地形 / 一类物理体：{@code (1, -1)} —— filter 全 1，与谁都交互；</li>
     *   <li>另一类物理体：{@code (4, -1)}；</li>
     *   <li>玩家主碰撞体：{@code (2, 5)}；玩家的"兄弟"碰撞体：{@code (5, 5)} ——
     *       两者同位置同尺寸，靠分组<b>互不作用</b>（否则求解器会把它们弹开）。</li>
     * </ul>
     *
     * <p>本项目的默认（不带 Grouped 的版本）等价于 {@code membership = 1, filter = -1}，
     * 也就是"与所有组交互" —— <b>保持旧行为</b>。</p>
     *
     * @return 碰撞体 id；失败返回 -1
     */
    public static native long colliderAttachCuboidGrouped(long world, long body,
                                                          double hx, double hy, double hz,
                                                          double friction, double restitution,
                                                          int membership, int filter);

    /** 挂体素碰撞体并指定碰撞组（语义同 {@link #colliderAttachCuboidGrouped}）。 */
    public static native long colliderAttachVoxelsGrouped(long world, long body,
                                                          double cellSizeX, double cellSizeY, double cellSizeZ,
                                                          long[] cells,
                                                          double friction, double restitution,
                                                          int membership, int filter);

    // ==================== Tier 1（ABI 5 起）：对标 space 0.1.3 ====================

    /** 摩擦/弹性组合规则序号，与 Rust 侧 {@code combine_rule} 对应。 */
    public static final int RULE_AVERAGE = 0;
    public static final int RULE_MIN = 1;
    public static final int RULE_MULTIPLY = 2;
    public static final int RULE_MAX = 3;
    public static final int RULE_CLAMPED_SUM = 4;

    /**
     * 设置（或清除）本维度的**无限地面**：法线朝上的半空间，平面在 {@code y} 处。
     *
     * <p>对应 space 0.1.3 的 {@code PhysicalWorld.setMinY}。非太空维度都该挂一个，
     * 否则物理体掉出世界就永远回不来了（我们此前只给玩家做了位置复位）。</p>
     */
    public static native boolean worldSetFloor(long world, double y, boolean enabled);

    /**
     * 运动学（位置型）刚体的**下一帧目标位置**。
     *
     * <p>用 {@link #bodySetTranslation} 推运动学体是瞬移，求解器读不到速度，
     * 站在上面的东西不会被带走 —— 电梯/移动平台/传送带必须用这个。</p>
     */
    public static native boolean bodySetNextKinematicTranslation(long world, long body,
                                                                 double x, double y, double z);

    /** 运动学（位置型）刚体的下一帧目标姿态。 */
    public static native boolean bodySetNextKinematicRotation(long world, long body,
                                                              double qx, double qy, double qz, double qw);

    /** 累加持续力矩（N·m）；配合 {@link #bodyResetTorque} 做转速伺服。 */
    public static native boolean bodyAddTorque(long world, long body, double x, double y, double z);

    /** 清除累加的持续力矩。 */
    public static native boolean bodyResetTorque(long world, long body);

    /** 施加角冲量（kg·m²/s）—— 反作用轮、螺旋桨启动。 */
    public static native boolean bodyApplyTorqueImpulse(long world, long body, double x, double y, double z);

    /**
     * 在**偏离质心的点**上施加力（世界系）。
     *
     * <p>偏心推进器会产生扭矩让船自转 —— "真实火箭"靠的就是它。</p>
     */
    public static native boolean bodyAddForceAtPoint(long world, long body,
                                                     double fx, double fy, double fz,
                                                     double px, double py, double pz);

    /** 线速度/角速度阻尼（1/s），负值按 0 处理；太空里的"航行阻尼"。 */
    public static native boolean bodySetDamping(long world, long body, double linear, double angular);

    /** 重力缩放（0 = 不受重力，1 = 正常）。局部反重力/悬停平台用。 */
    public static native boolean bodySetGravityScale(long world, long body, double scale);

    /**
     * 设置附加质量属性（质心 + 质量 + 主转动惯量），覆盖由碰撞体推导的那套。
     *
     * <p>对应 space 0.1.3 {@code RigidBody} 构造器的
     * {@code mass_center} / {@code mass} / {@code principal_inertia}。{@code mass} 必须 &gt; 0。</p>
     */
    public static native boolean bodySetAdditionalMassProperties(long world, long body,
                                                                 double cx, double cy, double cz,
                                                                 double mass,
                                                                 double ix, double iy, double iz);

    /** 开关连续碰撞检测（CCD）：高速物体不会穿过地形。 */
    public static native boolean bodyEnableCcd(long world, long body, boolean enabled);

    /** 逐轴开关旋转（比 {@link #bodyLockRotations} 的全锁/全放更细）。 */
    public static native boolean bodySetEnabledRotations(long world, long body,
                                                         boolean xEnabled, boolean yEnabled, boolean zEnabled);

    /**
     * 设置碰撞体材质：摩擦 / 弹性 / contact skin / 两个组合规则。
     *
     * <p>contact skin 是 Rapier 消接触抖动的手段（space 用 {@code 0.02}）；
     * 组合规则决定"玩家摩擦 20"与"地形摩擦 0.7"相遇时取哪个值（见 {@link #RULE_AVERAGE} 等）。</p>
     *
     * @param collider {@link #colliderAttachCuboid} 等返回的碰撞体 id
     */
    public static native boolean colliderSetMaterial(long world, long collider,
                                                     double friction, double restitution, double contactSkin,
                                                     int frictionRule, int restitutionRule);

    /**
     * 挂一个**任意盒复合碰撞体**（相对刚体局部空间）。
     *
     * <p>满碰撞形状走 {@link #colliderAttachVoxels} 的体素；非满形状（台阶/楼梯/栅栏/墙/锁链…）
     * 必须用这个，否则半砖会被当成整格（人浮在半空、楼梯走不上去）。
     * 对应 space 0.1.3 的 {@code ColliderBody.Type.COMPLEX_VOXEL}。</p>
     *
     * @param boxes 每 6 个 double 一个盒：{@code minX,minY,minZ,maxX,maxY,maxZ}
     * @return 碰撞体 id；失败返回 -1
     */
    public static native long colliderAttachBoxes(long world, long body, double[] boxes,
                                                  double friction, double restitution,
                                                  int membership, int filter);

    /**
     * Tier 1 原生自检：在独立临时世界里逐项验证 ABI 5 的新能力，返回通过的位掩码。
     *
     * <p>bit0 地面 / bit1 力矩 / bit2 运动学位移 / bit3 复合盒平台 / bit4 阻尼 /
     * bit5 材质与组合规则 / bit6 运动学旋转 / bit7 附加质量属性</p>
     */
    public static native int tier1Selftest();

    /**
     * 读取刚体质量（kg）；刚体不存在返回 -1。
     *
     * <p>注意 Rapier 的合成规则：<b>总质量 = 碰撞体推导质量 + 附加质量属性</b>。
     * 体素碰撞体默认密度 1.0，所以一格方块就是 1 kg —— 这是"玩家一碰物理体就把它撞飞"的根因
     * （50 kg 的玩家 vs 1 kg 的方块）。</p>
     */
    public static native double bodyGetMass(long world, long body);

    /**
     * 设置碰撞体**密度**（kg/m³）；体素碰撞体的总质量 = 体素数 × 密度。
     *
     * <p>Rapier 默认密度 `1.0`（一格方块 1 kg，比玩家还轻）—— 那正是"物理体太容易被推动"的
     * 物理原因。对应 space 0.1.3 的 {@code colliderBuilderSetDensity}。</p>
     *
     * <p>体素碰撞体只能有一个密度，所以要传<b>平均密度</b>（Σ 单块密度 / 块数）：
     * 总质量 = 块数 × 平均 = Σ 单块密度，正是我们要的。</p>
     */
    public static native boolean colliderSetDensity(long world, long collider, double density);

    /** 读取碰撞体密度；不存在返回 -1。 */
    public static native double colliderGetDensity(long world, long collider);

    // ==================== 碰撞体实时属性（ABI 6，对标 MPS 的 colliderSet*） ====================
    //
    // 为什么把这一组单独列出来：MPS 的原生把碰撞体属性做成**可随时修改**，
    // 所以它们的上层调用顺序是自由的（先挂载再设组、之后再改材质都行）。
    // 我们此前只能在建体时给，逼得 Java 侧发明"推迟创建"这种等价仿真 —— 殊途同归。
    // 补齐这组之后，上层就能照 MPS 的原样写。

    /** 实时改碰撞组；判定是双向的，两边都要放行（ABI 6）。 */
    public static native boolean colliderSetCollisionGroups(long world, long collider,
                                                            int membership, int filter);

    /** 实时改摩擦（ABI 6）。 */
    public static native boolean colliderSetFriction(long world, long collider, double friction);

    /** 实时改弹性（ABI 6）。 */
    public static native boolean colliderSetRestitution(long world, long collider, double restitution);

    /** 实时改摩擦组合规则（见 {@link #RULE_AVERAGE} 等，ABI 6）。 */
    public static native boolean colliderSetFrictionCombineRule(long world, long collider, int rule);

    /** 实时改弹性组合规则（ABI 6）。 */
    public static native boolean colliderSetRestitutionCombineRule(long world, long collider, int rule);

    /** 传感器开关：只报事件、不产生接触力（ABI 6）。 */
    public static native boolean colliderSetSensor(long world, long collider, boolean sensor);

    /** 碰撞事件开关（ActiveEvents 位掩码，ABI 6）。 */
    public static native boolean colliderSetActiveEvents(long world, long collider, int events);

    /** 接触力事件阈值（ABI 6）。 */
    public static native boolean colliderSetContactForceEventThreshold(long world, long collider,
                                                                       double threshold);

    /** 一次性设置位姿（位置 + 姿态，ABI 6）—— 对标 MPS 的 {@code rigidBodySetPose}。 */
    public static native boolean bodySetPose(long world, long body,
                                             double x, double y, double z,
                                             double qx, double qy, double qz, double qw,
                                             boolean wake);

    /** 读世界重力到 {@code out}（double[3]，ABI 6）。 */
    public static native boolean worldGetGravity(long world, double[] out);

    /** 世界内刚体数量（ABI 6；与 {@link #worldBodyCount} 同义，保留两者以对齐 MPS 命名）。 */
    public static native int worldGetRigidBodySetSize(long world);

    /** 世界内碰撞体数量（ABI 6）。 */
    public static native int worldGetColliderSetSize(long world);

    // ==================== 按句柄移除（ABI 7，对标 MPS 的 worldRemove*） ====================

    /**
     * 从世界移除**单个**碰撞体（ABI 7）—— 对应 MPS 的 {@code worldRemoveCollider}。
     *
     * <p>此前只有 {@link #bodyClearColliders}（按刚体整体清），所以"移除某一个碰撞体"
     * 只能做成 Java 侧摘记录 —— 结果对、方法错。这是补齐它之后的正确入口。</p>
     */
    public static native boolean worldRemoveCollider(long world, long collider, boolean wake);

    /** 从世界移除刚体（带唤醒开关，ABI 7）—— 对应 MPS 的 {@code worldRemoveRigidBody}。 */
    public static native boolean worldRemoveRigidBody(long world, long body, boolean wake);

    // ==================== cosmos（ABI 9，kelvin 的天体 N 体引力） ====================
    //
    // 契约**由 kelvin 的调用点反推**（不是猜）：SpaceWorld.java:134/153/273/275/282/188/203。
    // space 里另 5 个 cosmos 函数（AddNBody / InsertBody / BodyMass / DynamicBodyCount /
    // BuilderDestroy）零调用，故未实现。
    // 读回缓冲区改成 double[3] 出参，替代他们的 Unsafe.allocateMemory(24) 裸指针。

    /**
     * 建 cosmos 世界。
     *
     * @param dt        步长（秒）
     * @param substeps  每个外层步的子步数（调用点传 4）
     * @param gridX/Y/Z 粒子网格维度（调用点都是 1）；我们直接 O(n²) 积分，忽略
     * @param farField  远场截断（调用点 1e6）；直接积分用不到，仅记录
     */
    public static native long cosmosWorldCreate(double dt, int substeps,
                                                int gridX, int gridY, int gridZ, double farField);

    /** 销毁 cosmos 世界。 */
    public static native void cosmosWorldDestroy(long world);

    /** 推进一次；返回天体数，失败 -1。 */
    public static native int cosmosWorldStep(long world, double dt);

    /** 造"固定天体"builder（恒星/行星核；质量在插入时传）。 */
    public static native long cosmosFixedBodyBuilder(double x, double y, double z);

    /** 造"卫星"builder（质量 / 位置 / 速度 / 半径）。 */
    public static native long cosmosSatelliteBuilder(double mass, double x, double y, double z,
                                                     double vx, double vy, double vz, double radius);

    /** 把 builder 插进世界并登记为引力源；返回天体句柄。 */
    public static native long cosmosWorldInsertBodyAsGravitySource(long world, long builder, double mass);

    /** 读回天体位置到 {@code out[0..2]}；**非 0 = 成功，0 = 失败**（kelvin 判 {@code == 0 ? null : 值}）。 */
    public static native int cosmosBodyTranslationOut(long world, long body, double[] out);

    /** 读回天体速度到 {@code out[0..2]}；**非 0 = 成功，0 = 失败**。 */
    public static native int cosmosBodyLinvelOut(long world, long body, double[] out);

    // ==================== 分离对象（ABI 8，对标 MPS 的 memory handle 模型） ====================
    //
    // 为什么要有它：MPS 的跨维度搬运是 "copy out → insert into another world"。
    // 没有"不属于任何世界的对象"这个概念时，只能靠"读位姿 + 在新世界重建"冒充，
    // 而速度/角速度/质量属性/材质组合规则/碰撞组这些细节全靠调用方记得补齐 —— 漏一个就悄悄失真。
    // 这些句柄（memory handle）与世界内 id 分开编号，插入世界前不属于任何世界。

    /**
     * 把刚体从世界里**复制**出来（原体保留，由调用方决定是否移除）。
     *
     * @return 分离对象句柄；失败返回 -1
     */
    public static native long worldCopyRigidBody(long world, long body);

    /** 把碰撞体从世界里复制出来（原体保留）。 */
    public static native long worldCopyCollider(long world, long collider);

    /** 把分离的刚体插进世界（句柄被消费）。 */
    public static native long worldInsertRigidBody(long world, long memoryHandle);

    /** 把分离的碰撞体插进世界（无父体）。 */
    public static native long worldInsertCollider(long world, long memoryHandle);

    /** 把分离的碰撞体插进世界并挂到指定刚体上。 */
    public static native long worldInsertColliderWithParent(long world, long memoryHandle, long parentBody);

    /**
     * 释放一个分离对象 —— 对应 MPS 的 {@code RapierConnect.RustMemoryFree}。
     *
     * <p>MPS 靠 {@code Cleaner} + {@code Unsafe} 管这些内存句柄；我们这边句柄就是竞技场里的一条记录。
     * <b>没插回世界就丢弃的分离对象必须调它</b>，否则会一直留在竞技场里。</p>
     */
    public static native void RustMemoryFree(long memoryHandle);

    /**
     * 在世界里投一条射线，命中则把 {@code [toi, nx, ny, nz, colliderId]} 写进 {@code out}（长度须 ≥ 5）。
     *
     * <p>对应 space 0.1.3 的 {@code RapierWorld.castRay(origin, direction, maxToi, memberships, filter)}。
     * space 用它做玩家探地（从脚下五点向下打 0.1m）来设 {@code onGround}。</p>
     *
     * <p>{@code memberships}/{@code filter} 是<b>查询方</b>的碰撞组。玩家用 {@code (2, 5)}
     * 就能打到地形 {@code (1,-1)} 与物理体 {@code (4,-1)}，同时打不到自己的一对盒子。</p>
     */
    public static native boolean worldCastRay(long world,
                                              double ox, double oy, double oz,
                                              double dx, double dy, double dz,
                                              double maxToi,
                                              int memberships, int filter,
                                              double[] out);

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
