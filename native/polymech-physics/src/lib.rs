//! Poly Mech 物理原生层：Rapier (f64) 的 JNI 桥接。
//!
//! 设计原则：
//! - 所有坐标/质量为 f64，与 Java 侧 double 一一对应（太空坐标可达 1e9 格，f32 不可用）；
//! - Java 侧只持有 `jlong` 句柄（world/body/collider id），仿真数据全部在 Rust 侧堆外，
//!   避免大体量数据进入 Java 堆引发 GC 抖动；
//! - 世界用 `Mutex<HashMap>` 管理，所有导出函数都做越界/非法值防护，绝不 panic 跨 FFI 边界
//!   （panic 跨 JNI 边界是未定义行为）。
//!
//! ABI 版本：Java 侧加载后校验 [`ABI_VERSION`]，不匹配则拒绝使用，避免版本错配崩溃。

use jni::objects::{JClass, JDoubleArray, JLongArray};
use jni::sys::{jboolean, jdouble, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use rapier3d_f64::prelude::{Group, InteractionGroups, InteractionTestMode};
use rapier3d_f64::prelude::*;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

/// ABI 版本：任何导出函数签名/语义变更都必须 +1。
///
/// 4：新增 `colliderAttachCuboidGrouped` / `colliderAttachVoxelsGrouped`（带碰撞组）。
/// 5：Tier 1 对标 space 0.1.3 的能力补全 ——
///    `worldSetFloor`（维度地面）、力矩/偏心受力、刚体属性（阻尼/重力缩放/附加质量属性/CCD/每轴旋转）、
///    `colliderSetMaterial`（摩擦弹性组合规则 + contact skin）、运动学目标位姿、
///    `colliderAttachBoxes`（任意盒复合碰撞体）、`tier1Selftest`（原生自检）。
/// 6：碰撞体**实时属性**（对标 MPS 的 `colliderSet*`）——
///    `colliderSetCollisionGroups` / `SetFriction` / `SetRestitution` / 两个组合规则 /
///    `SetSensor` / `SetActiveEvents` / `SetContactForceEventThreshold`、
///    `bodySetPose`、`worldGetGravity` / `worldGetRigidBodySetSize` / `worldGetColliderSetSize`。
///    补它们的理由是**方法对齐**：此前 Java 侧为了绕开缺口发明了"推迟创建碰撞体"这类
///    等价仿真，属于殊途同归；有了这些函数就能照 MPS 的原样写。
/// 7：单个碰撞体 / 刚体的**按句柄移除**（对标 MPS 的 `worldRemoveCollider` /
///    `worldRemoveRigidBody`）。此前只能按刚体整体清碰撞体，逼得 `removeColliderBody`
///    只能"Java 侧摘记录、原生还留着"—— 又一处理论上对、方法上错的补丁。
/// 8：**分离对象**（对标 MPS 的 memory handle 模型）——
///    `worldCopyRigidBody` / `worldCopyCollider`（复制出世界）、
///    `worldInsertRigidBody` / `worldInsertCollider` / `worldInsertColliderWithParent`（插回世界）、
///    `RustMemoryFree`（释放）。这是跨维度搬运（`dimensionLeapPhysicalBody`）的前提：
///    有了它才不必用"读位姿 + 在新世界重建"去冒充（那会丢速度/质量属性/材质细节）。
/// 9：**cosmos**（kelvin 的天体 N 体引力）——`cosmosWorldCreate/Destroy/Step`、
///    `cosmosFixedBodyBuilder` / `cosmosSatelliteBuilder` /
///    `cosmosWorldInsertBodyAsGravitySource`、`cosmosBodyTranslationOut` / `cosmosBodyLinvelOut`。
///    契约全部由 kelvin 的调用点反推；space 里另 5 个 cosmos 函数零调用，未实现。
///    读回改用 `double[3]` 出参，替代他们的 `Unsafe.allocateMemory(24)` 裸指针。
///
/// Java 侧用 `PhysicsNatives.MIN_ABI_TIER1` 逐项把关；`EXPECTED_ABI` 仍是**最低要求**，
/// 所以"Java 已更新、dll 还没重编"时物理层照常跑，只是 Tier 1 功能降级。
pub const ABI_VERSION: jint = 9;

/// 世界内刚体数量上限（防御非法输入）。
const MAX_BODIES_PER_WORLD: usize = 65_536;

/// 单个体素碰撞体的最大体素数（对应 space 模组的 128^3 = 2,097,152 上限）
const MAX_VOXELS_PER_COLLIDER: usize = 2_097_152;

/// 单个复合盒碰撞体的最大盒数（防御非法输入；每盒 = 6 个 double）。
const MAX_BOXES_PER_COLLIDER: usize = 65_536;

// ==================== 世界 ====================

struct PhysicsWorld {
    gravity: Vector,
    params: IntegrationParameters,
    pipeline: PhysicsPipeline,
    islands: IslandManager,
    broad: BroadPhaseBvh,
    narrow: NarrowPhase,
    bodies: RigidBodySet,
    colliders: ColliderSet,
    impulse_joints: ImpulseJointSet,
    multibody_joints: MultibodyJointSet,
    ccd: CCDSolver,
    /// Java 侧 id → Rapier 句柄
    body_map: HashMap<i64, RigidBodyHandle>,
    /// Java 侧碰撞体 id -> (所属刚体 id, Rapier 句柄)，用于按刚体清理
    collider_map: HashMap<i64, (i64, ColliderHandle)>,
    /// 维度地面（半空间）的句柄；对应 space 0.1.3 的 `PhysicalWorld.setMinY`
    floor: Option<ColliderHandle>,
    next_id: i64,
}

impl PhysicsWorld {
    fn new(gx: f64, gy: f64, gz: f64) -> Self {
        let mut params = IntegrationParameters::default();
        params.dt = 1.0 / 100.0;
        // ── 2026-09-19 实测：接触锯齿**不是求解器旋钮能解决的**（三个都试过，全部回退）──
        //
        // 现象（`PlayerWallProbeTest` K 节，与游戏内 `[玩家物理抽搐]` 是同一件事）：
        // 速度链每子步把 `main.linvel` 写成 `sibling + own`，被挡住时 sibling≈0
        // ⇒ main 每子步都以 `own` 顶进表面；Rapier 的（软）接触让它先沉进去、再推回来。
        // 实测位置偏离均值 = 7.6mm（own=1）/ 26.5mm（own=4.317 走路）/ 72mm（own=12）
        // —— 与 `own × dt` 同量级，速度在 0 ↔ own 之间每 2 子步换向。
        //
        // 试过并**全部回退**（因为都没有改善，或更糟）：
        //   1. `normalized_prediction_distance` 0.002 → 0.05：K 节数字**一个都没变**
        //      （它只决定"多远开始算接触"，而这里是**已经在接触中**）；
        //   2. `contact_softness` 30Hz → 120Hz（只改频率）：**更糟**
        //      （7.6→20.9 / 26.5→31.2 / 72→77 mm）；
        //   3. `num_solver_iterations` 4 → 8：**没有变化**（7.6 / 26.4 / 72.1）。
        //
        // 为什么旋钮都没用：**原版不靠调参解决这件事，而是靠"同一步内把位置夹到表面"** ——
        // `Entity.move` 先 `collide()` 拿到夹过的位移、再用它 `setPos`（mcsrc Entity:639/652），
        // 并把被挡轴的速度**清零**（:677）。**原版从不穿透**。
        // 我们在 HEAD `cancel()` 掉了整个 `move`，于是既没有那一次夹取、也没有那次清零，
        // 身体每子步被 `own` 顶进去 `own×dt`，靠求解器事后修正 —— 这就是残余的那 2–3 m/s。
        //
        // 结论：**不要在这里加数字**。要压掉它只能从"每子步是否该把整个 own 顶进接触"
        // 这个结构上去谈（那是 space 同款设计，需用户拍板），不是调 Rapier。
        Self {
            gravity: Vector::new(gx, gy, gz),
            params,
            pipeline: PhysicsPipeline::new(),
            islands: IslandManager::new(),
            broad: BroadPhaseBvh::new(),
            narrow: NarrowPhase::new(),
            bodies: RigidBodySet::new(),
            colliders: ColliderSet::new(),
            impulse_joints: ImpulseJointSet::new(),
            multibody_joints: MultibodyJointSet::new(),
            ccd: CCDSolver::new(),
            body_map: HashMap::new(),
            collider_map: HashMap::new(),
            floor: None,
            next_id: 1,
        }
    }

    fn step(&mut self) {
        self.pipeline.step(
            self.gravity,
            &self.params,
            &mut self.islands,
            &mut self.broad,
            &mut self.narrow,
            &mut self.bodies,
            &mut self.colliders,
            &mut self.impulse_joints,
            &mut self.multibody_joints,
            &mut self.ccd,
            &(),
            &(),
        );
    }

    fn alloc_id(&mut self) -> i64 {
        let id = self.next_id;
        self.next_id += 1;
        id
    }
}

/// 每个世界一把**独立**的锁。
///
/// 早先是"整个世界表共用一把全局 `Mutex`"：单机下客户端与服务端各有一个物理世界，
/// 于是任何一方的长操作（建一个几万体素的区块碰撞体、一帧里几十次读刚体、
/// 玩家刚体重建…）都会把<b>另一方</b>的 100Hz 步进线程一起卡住 ——
/// 表现就是"正在飞的物理体每隔几秒顿一下"（模拟整段时间停住，然后继续）。
///
/// 换成 per-world 锁后跨世界不再互相阻塞；同一世界内仍然串行，这是必须的
/// （步进与读写在同一个 Rapier 世界上不能并发）。
///
/// 注意加锁顺序：**先取 map 锁、克隆出 Arc、立刻放掉 map 锁**，再锁世界。
/// 绝不可以在持有 map 锁时去锁世界，否则会与 `worldDestroy` 形成死锁。
type WorldRef = std::sync::Arc<Mutex<PhysicsWorld>>;

static WORLDS: OnceLock<Mutex<HashMap<i64, WorldRef>>> = OnceLock::new();
static NEXT_WORLD_ID: OnceLock<Mutex<i64>> = OnceLock::new();

fn worlds() -> &'static Mutex<HashMap<i64, WorldRef>> {
    WORLDS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 取某世界的引用（不锁世界本身）。
fn world_ref(handle: jlong) -> Option<WorldRef> {
    if handle <= 0 {
        return None;
    }
    let map = worlds().lock().ok()?;
    map.get(&(handle as i64)).cloned()
}

fn with_world<R>(handle: jlong, f: impl FnOnce(&mut PhysicsWorld) -> R) -> Option<R> {
    let world = world_ref(handle)?;
    let mut guard = world.lock().ok()?;
    Some(f(&mut guard))
}

// ==================== 工具 ====================

fn finite(v: f64) -> bool {
    v.is_finite()
}

fn ok(written: bool) -> jboolean {
    if written {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

// ==================== 导出函数 ====================

/// ABI 版本校验。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_abiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    ABI_VERSION
}

/// 创建物理世界（重力 m/s²），返回句柄；失败返回 0。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldCreate(
    _env: JNIEnv,
    _class: JClass,
    gx: jdouble,
    gy: jdouble,
    gz: jdouble,
) -> jlong {
    if !finite(gx) || !finite(gy) || !finite(gz) {
        return 0;
    }
    let id = match NEXT_WORLD_ID.get_or_init(|| Mutex::new(1)).lock() {
        Ok(mut counter) => {
            let id = *counter;
            *counter += 1;
            id
        }
        Err(_) => return 0,
    };
    match worlds().lock() {
        Ok(mut map) => {
            map.insert(id, std::sync::Arc::new(Mutex::new(PhysicsWorld::new(gx, gy, gz))));
            id as jlong
        }
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle > 0 {
        if let Ok(mut map) = worlds().lock() {
            map.remove(&(handle as i64));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldSetGravity(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    gx: jdouble,
    gy: jdouble,
    gz: jdouble,
) -> jboolean {
    if !finite(gx) || !finite(gy) || !finite(gz) {
        return JNI_FALSE;
    }
    ok(with_world(handle, |w| w.gravity = Vector::new(gx, gy, gz)).is_some())
}

/// 设定固定步长（秒），必须在 (0, 1] 内。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldSetTimestep(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    dt: jdouble,
) -> jboolean {
    if !finite(dt) || dt <= 0.0 || dt > 1.0 {
        return JNI_FALSE;
    }
    ok(with_world(handle, |w| w.params.dt = dt).is_some())
}

/// 步进一次固定步长。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldStep(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    ok(with_world(handle, |w| w.step()).is_some())
}

/// 创建刚体。
///
/// `bodyType`: 0=动态 1=固定 2=运动学(位置) 3=运动学(速度)
/// 旋转为四元数 (qx, qy, qz, qw)；`mass` <= 0 表示由碰撞体推导质量。
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyCreate(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_type: jint,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
    mass: jdouble,
) -> jlong {
    if !finite(x) || !finite(y) || !finite(z) {
        return -1;
    }
    if !finite(qx) || !finite(qy) || !finite(qz) || !finite(qw) {
        return -1;
    }
    let mut builder = match body_type {
        0 => RigidBodyBuilder::dynamic(),
        1 => RigidBodyBuilder::fixed(),
        2 => RigidBodyBuilder::kinematic_position_based(),
        3 => RigidBodyBuilder::kinematic_velocity_based(),
        _ => return -1,
    };
    builder = builder.translation(Vector::new(x, y, z));
    if finite(mass) && mass > 0.0 {
        builder = builder.additional_mass(mass);
    }
    let body = builder.build();
    let rotation = Rotation::from_xyzw(qx, qy, qz, qw).normalize();

    match with_world(handle, |w| {
        if w.body_map.len() >= MAX_BODIES_PER_WORLD {
            return -1;
        }
        let body_handle = w.bodies.insert(body);
        if let Some(stored) = w.bodies.get_mut(body_handle) {
            stored.set_rotation(rotation, false);
        }
        let id = w.alloc_id();
        w.body_map.insert(id, body_handle);
        id
    }) {
        Some(id) => id as jlong,
        None => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
) -> jboolean {
    let removed = with_world(handle, |w| match w.body_map.remove(&(body_id as i64)) {
        Some(body_handle) => {
            w.bodies.remove(
                body_handle,
                &mut w.islands,
                &mut w.colliders,
                &mut w.impulse_joints,
                &mut w.multibody_joints,
                true,
            );
            // 必须同步清掉 Java 侧 id 映射：留着的话 id 会指向已被回收的 Rapier 句柄，
            // 而 Rapier 会复用句柄槽位 —— 后续按 id 改材质就改到了别人的碰撞体上。
            w.collider_map.retain(|_, (owner, _)| *owner != body_id as i64);
            true
        }
        None => false,
    });
    ok(removed.unwrap_or(false))
}

/// 读取位置到 `out`（double[3]）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyReadTranslation(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    out: JDoubleArray,
) -> jboolean {
    let pos = with_world(handle, |w| {
        w.body_map
            .get(&(body_id as i64))
            .and_then(|h| w.bodies.get(*h))
            .map(|b| {
                let t = b.translation();
                (t.x, t.y, t.z)
            })
    })
    .flatten();
    match pos {
        Some((x, y, z)) => {
            let written = env.set_double_array_region(&out, 0, &[x, y, z]).is_ok();
            ok(written)
        }
        None => JNI_FALSE,
    }
}

/// 读取旋转四元数到 `out`（double[4]，顺序 x,y,z,w）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyReadRotation(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    out: JDoubleArray,
) -> jboolean {
    let rot = with_world(handle, |w| {
        w.body_map
            .get(&(body_id as i64))
            .and_then(|h| w.bodies.get(*h))
            .map(|b| {
                let q = b.rotation();
                (q.x, q.y, q.z, q.w)
            })
    })
    .flatten();
    match rot {
        Some((x, y, z, w)) => {
            let written = env.set_double_array_region(&out, 0, &[x, y, z, w]).is_ok();
            ok(written)
        }
        None => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodySetTranslation(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) -> jboolean {
    if !finite(x) || !finite(y) || !finite(z) {
        return JNI_FALSE;
    }
    let done = with_world(handle, |w| {
        let body_handle = w.body_map.get(&(body_id as i64)).copied();
        match body_handle.and_then(|h| w.bodies.get_mut(h)) {
            Some(b) => {
                b.set_translation(Vector::new(x, y, z), true);
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyApplyImpulse(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) -> jboolean {
    if !finite(x) || !finite(y) || !finite(z) {
        return JNI_FALSE;
    }
    let done = with_world(handle, |w| {
        let body_handle = w.body_map.get(&(body_id as i64)).copied();
        match body_handle.and_then(|h| w.bodies.get_mut(h)) {
            Some(b) => {
                b.apply_impulse(Vector::new(x, y, z), true);
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

/// 给刚体挂盒碰撞体（参数为半长）。
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderAttachCuboid(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    hx: jdouble,
    hy: jdouble,
    hz: jdouble,
    friction: jdouble,
    restitution: jdouble,
) -> jlong {
    if !finite(hx) || !finite(hy) || !finite(hz) || hx <= 0.0 || hy <= 0.0 || hz <= 0.0 {
        return -1;
    }
    let friction = if finite(friction) {
        friction.max(0.0)
    } else {
        0.5
    };
    let restitution = if finite(restitution) {
        restitution.clamp(0.0, 1.0)
    } else {
        0.0
    };
    match with_world(handle, |w| {
        let Some(body_handle) = w.body_map.get(&(body_id as i64)).copied() else {
            return -1;
        };
        let collider = ColliderBuilder::cuboid(hx, hy, hz)
            .friction(friction)
            .restitution(restitution)
            .build();
        let collider_handle = w
            .colliders
            .insert_with_parent(collider, body_handle, &mut w.bodies);
        let id = w.alloc_id();
        w.collider_map.insert(id, (body_id as i64, collider_handle));
        id
    }) {
        Some(id) => id as jlong,
        None => -1,
    }
}

/// 给刚体挂盒碰撞体，并指定**碰撞组**（membership / filter 位掩码）。
///
/// Rapier 的交互判定是**双向**的：A 与 B 交互 ⟺ `(A.membership & B.filter) != 0`
/// 且 `(B.membership & A.filter) != 0`。所以只改一边的 filter 不生效，两边都要设。
///
/// 参考 space 0.1.3 的分组（见 `docs/space-decompile.md`）：
/// 地形/一类体 `(1, -1)`、另一类体 `(4, -1)`、玩家主碰撞体 `(2, 5)`、
/// 玩家"兄弟"碰撞体 `(5, 5)` —— 同位置的两个盒子靠分组**互不作用**，否则求解器会把它们弹开。
///
/// 默认（`colliderAttachCuboid`）等价于 `membership = 1, filter = -1`（与所有组交互），
/// 也就是**保持旧行为**；要改行为的调用方才用这个带组版本。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderAttachCuboidGrouped(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    hx: jdouble,
    hy: jdouble,
    hz: jdouble,
    friction: jdouble,
    restitution: jdouble,
    membership: jint,
    filter: jint,
) -> jlong {
    if !finite(hx) || !finite(hy) || !finite(hz) || hx <= 0.0 || hy <= 0.0 || hz <= 0.0 {
        return -1;
    }
    let friction = if finite(friction) {
        friction.max(0.0)
    } else {
        0.5
    };
    let restitution = if finite(restitution) {
        restitution.clamp(0.0, 1.0)
    } else {
        0.0
    };
    // Rapier 0.34 的第三个参数是交互测试模式：And = 双向都要成立
    //（(A.mem & B.filter) != 0 且 (B.mem & A.filter) != 0），这是常规语义；
    // Or 只在两边都声明 Or 时才生效（见 interaction_groups.rs 的文档）。
    let groups = InteractionGroups::new(
        Group::from_bits_truncate(membership as u32),
        Group::from_bits_truncate(filter as u32),
        InteractionTestMode::And,
    );
    match with_world(handle, |w| {
        let Some(body_handle) = w.body_map.get(&(body_id as i64)).copied() else {
            return -1;
        };
        let collider = ColliderBuilder::cuboid(hx, hy, hz)
            .friction(friction)
            .restitution(restitution)
            .collision_groups(groups)
            .build();
        let collider_handle = w
            .colliders
            .insert_with_parent(collider, body_handle, &mut w.bodies);
        let id = w.alloc_id();
        w.collider_map.insert(id, (body_id as i64, collider_handle));
        id
    }) {
        Some(id) => id as jlong,
        None => -1,
    }
}

/// 给刚体挂球碰撞体。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderAttachBall(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    radius: jdouble,
    friction: jdouble,
    restitution: jdouble,
) -> jlong {
    if !finite(radius) || radius <= 0.0 {
        return -1;
    }
    let friction = if finite(friction) {
        friction.max(0.0)
    } else {
        0.5
    };
    let restitution = if finite(restitution) {
        restitution.clamp(0.0, 1.0)
    } else {
        0.0
    };
    match with_world(handle, |w| {
        let Some(body_handle) = w.body_map.get(&(body_id as i64)).copied() else {
            return -1;
        };
        let collider = ColliderBuilder::ball(radius)
            .friction(friction)
            .restitution(restitution)
            .build();
        let collider_handle = w
            .colliders
            .insert_with_parent(collider, body_handle, &mut w.bodies);
        let id = w.alloc_id();
        w.collider_map.insert(id, (body_id as i64, collider_handle));
        id
    }) {
        Some(id) => id as jlong,
        None => -1,
    }
}

/// 世界内刚体数量（诊断）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldBodyCount(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_world(handle, |w| w.body_map.len() as jint).unwrap_or(-1)
}

/// 自检：自建世界（重力 -9.8 m/s²），地面 + 从 10m 高落下的立方体，
/// 步进 `steps` 次后返回立方体 Y 坐标（期望落在 ~0.5，即半高）。
///
/// 该函数不依赖 Java 侧任何状态，用于验证「原生库可加载 + Rapier 可仿真」。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_selftest(
    _env: JNIEnv,
    _class: JClass,
    steps: jint,
) -> jdouble {
    let mut world = PhysicsWorld::new(0.0, -9.8, 0.0);
    world.params.dt = 1.0 / 60.0;

    // 地面：固定刚体，半长 10 x 0.5 x 10，顶面在 y = 0
    let ground = RigidBodyBuilder::fixed()
        .translation(Vector::new(0.0, -0.5, 0.0))
        .build();
    let ground_handle = world.bodies.insert(ground);
    let ground_collider = ColliderBuilder::cuboid(10.0, 0.5, 10.0)
        .friction(0.8)
        .build();
    world
        .colliders
        .insert_with_parent(ground_collider, ground_handle, &mut world.bodies);

    // 落体：动态刚体，半边长 0.5 的立方体，从 y = 10 开始
    let cube = RigidBodyBuilder::dynamic()
        .translation(Vector::new(0.0, 10.0, 0.0))
        .build();
    let cube_handle = world.bodies.insert(cube);
    let cube_collider = ColliderBuilder::cuboid(0.5, 0.5, 0.5).build();
    world
        .colliders
        .insert_with_parent(cube_collider, cube_handle, &mut world.bodies);

    let steps = if steps <= 0 { 240 } else { steps.min(100_000) };
    for _ in 0..steps {
        world.step();
    }

    world
        .bodies
        .get(cube_handle)
        .map(|b| b.translation().y)
        .unwrap_or(f64::NAN)
}

/// 21 位有符号还原（体素网格坐标打包用）。
fn sign21(v: i64) -> i64 {
    let m = v & 0x1F_FFFF;
    if m >= 0x10_0000 {
        m - 0x20_0000
    } else {
        m
    }
}

/// 体素碰撞体：把一个方块区域一次性地变成 Rapier 的 `Voxels` 形状。
///
/// `cells` 为打包后的网格坐标数组，每个 long 编码 (x, y, z)，各占 21 位有符号：
/// `((x & 0x1FFFFF) << 42) | ((y & 0x1FFFFF) << 21) | (z & 0x1FFFFF)`
///
/// 网格 (x,y,z) 的体素在局部空间中占据 `[(x,y,z), (x,y,z)+1] * cellSize`（中心在整格中心）。
///
/// @return 碰撞体 id；失败返回 -1
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderAttachVoxels(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    cell_size_x: jdouble,
    cell_size_y: jdouble,
    cell_size_z: jdouble,
    cells: JLongArray,
    friction: jdouble,
    restitution: jdouble,
) -> jlong {
    if !finite(cell_size_x) || !finite(cell_size_y) || !finite(cell_size_z) {
        return -1;
    }
    if cell_size_x <= 0.0 || cell_size_y <= 0.0 || cell_size_z <= 0.0 {
        return -1;
    }
    let len = match env.get_array_length(&cells) {
        Ok(n) if n > 0 => n as usize,
        _ => return -1,
    };
    if len > MAX_VOXELS_PER_COLLIDER {
        return -1;
    }
    let mut raw = vec![0i64; len];
    if env.get_long_array_region(&cells, 0, &mut raw).is_err() {
        return -1;
    }

    let mut coords: Vec<IVector> = Vec::with_capacity(len);
    for v in raw {
        coords.push(IVector::new(
            sign21(v >> 42),
            sign21(v >> 21),
            sign21(v),
        ));
    }

    let friction = if finite(friction) {
        friction.max(0.0)
    } else {
        0.5
    };
    let restitution = if finite(restitution) {
        restitution.clamp(0.0, 1.0)
    } else {
        0.0
    };

    match with_world(handle, |w| {
        let Some(body_handle) = w.body_map.get(&(body_id as i64)).copied() else {
            return -1;
        };
        let collider = ColliderBuilder::voxels(
            Vector::new(cell_size_x, cell_size_y, cell_size_z),
            &coords,
        )
        .friction(friction)
        .restitution(restitution)
        .build();
        let collider_handle = w
            .colliders
            .insert_with_parent(collider, body_handle, &mut w.bodies);
        let id = w.alloc_id();
        w.collider_map.insert(id, (body_id as i64, collider_handle));
        id
    }) {
        Some(id) => id as jlong,
        None => -1,
    }
}

/// 给刚体挂**体素**碰撞体，并指定碰撞组（语义同 `colliderAttachCuboidGrouped`）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderAttachVoxelsGrouped(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    cell_size_x: jdouble,
    cell_size_y: jdouble,
    cell_size_z: jdouble,
    cells: JLongArray,
    friction: jdouble,
    restitution: jdouble,
    membership: jint,
    filter: jint,
) -> jlong {
    if !finite(cell_size_x) || !finite(cell_size_y) || !finite(cell_size_z) {
        return -1;
    }
    if cell_size_x <= 0.0 || cell_size_y <= 0.0 || cell_size_z <= 0.0 {
        return -1;
    }
    let len = match env.get_array_length(&cells) {
        Ok(n) if n > 0 => n as usize,
        _ => return -1,
    };
    if len > MAX_VOXELS_PER_COLLIDER {
        return -1;
    }
    let mut raw = vec![0i64; len];
    if env.get_long_array_region(&cells, 0, &mut raw).is_err() {
        return -1;
    }

    let mut coords: Vec<IVector> = Vec::with_capacity(len);
    for v in raw {
        coords.push(IVector::new(
            sign21(v >> 42),
            sign21(v >> 21),
            sign21(v),
        ));
    }

    let friction = if finite(friction) {
        friction.max(0.0)
    } else {
        0.5
    };
    let restitution = if finite(restitution) {
        restitution.clamp(0.0, 1.0)
    } else {
        0.0
    };
    // Rapier 0.34 的第三个参数是交互测试模式：And = 双向都要成立
    //（(A.mem & B.filter) != 0 且 (B.mem & A.filter) != 0），这是常规语义；
    // Or 只在两边都声明 Or 时才生效（见 interaction_groups.rs 的文档）。
    let groups = InteractionGroups::new(
        Group::from_bits_truncate(membership as u32),
        Group::from_bits_truncate(filter as u32),
        InteractionTestMode::And,
    );

    match with_world(handle, |w| {
        let Some(body_handle) = w.body_map.get(&(body_id as i64)).copied() else {
            return -1;
        };
        let collider = ColliderBuilder::voxels(
            Vector::new(cell_size_x, cell_size_y, cell_size_z),
            &coords,
        )
        .friction(friction)
        .restitution(restitution)
        .collision_groups(groups)
        .build();
        let collider_handle = w
            .colliders
            .insert_with_parent(collider, body_handle, &mut w.bodies);
        let id = w.alloc_id();
        w.collider_map.insert(id, (body_id as i64, collider_handle));
        id
    }) {
        Some(id) => id as jlong,
        None => -1,
    }
}

/// 读取线速度到 `out`（double[3]）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyReadVelocity(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    out: JDoubleArray,
) -> jboolean {
    let vel = with_world(handle, |w| {
        w.body_map
            .get(&(body_id as i64))
            .and_then(|h| w.bodies.get(*h))
            .map(|b| {
                let v = b.linvel();
                (v.x, v.y, v.z)
            })
    })
    .flatten();
    match vel {
        Some((x, y, z)) => {
            let written = env.set_double_array_region(&out, 0, &[x, y, z]).is_ok();
            ok(written)
        }
        None => JNI_FALSE,
    }
}

/// 设置线速度（用于运动学/传送同步）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodySetVelocity(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) -> jboolean {
    if !finite(x) || !finite(y) || !finite(z) {
        return JNI_FALSE;
    }
    let done = with_world(handle, |w| {
        let body_handle = w.body_map.get(&(body_id as i64)).copied();
        match body_handle.and_then(|h| w.bodies.get_mut(h)) {
            Some(b) => {
                b.set_linvel(Vector::new(x, y, z), true);
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

/// 读取角速度到 `out`（double[3]）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyReadAngvel(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    out: JDoubleArray,
) -> jboolean {
    let ang = with_world(handle, |w| {
        w.body_map
            .get(&(body_id as i64))
            .and_then(|h| w.bodies.get(*h))
            .map(|b| {
                let a = b.angvel();
                (a.x, a.y, a.z)
            })
    })
    .flatten();
    match ang {
        Some((x, y, z)) => {
            let written = env.set_double_array_region(&out, 0, &[x, y, z]).is_ok();
            ok(written)
        }
        None => JNI_FALSE,
    }
}

/// 设置角速度（物理体自转同步：客户端碰撞体靠它跟着转，而不是永久停在建体姿态）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodySetAngvel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) -> jboolean {
    if !finite(x) || !finite(y) || !finite(z) {
        return JNI_FALSE;
    }
    let done = with_world(handle, |w| {
        let body_handle = w.body_map.get(&(body_id as i64)).copied();
        match body_handle.and_then(|h| w.bodies.get_mut(h)) {
            Some(b) => {
                b.set_angvel(Vector::new(x, y, z), true);
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

/// 一次性写入线速度 + 角速度，**显式控制是否唤醒**。
///
/// 对应 MPS 的 `rigidBodySetLinvel(..., wake)` / `rigidBodySetAngvel(..., wake)`：
/// 数值没变就不要唤醒 —— Rapier 的休眠机制靠它才能让静止的刚体真正"睡下去"。
/// 我们此前一律 wake=true，静置的船永不休眠，求解器每步都在解它，
/// 接触噪声又被 20Hz 同步不断喂回去 → 姿态抖动 / 朝向漂移，还白烧 CPU。
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodySetMotion(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    vx: jdouble,
    vy: jdouble,
    vz: jdouble,
    ax: jdouble,
    ay: jdouble,
    az: jdouble,
    changed: jboolean,
) -> jboolean {
    if !finite(vx) || !finite(vy) || !finite(vz) || !finite(ax) || !finite(ay) || !finite(az) {
        return JNI_FALSE;
    }
    let wake = changed == JNI_TRUE;
    let done = with_world(handle, |w| {
        let body_handle = w.body_map.get(&(body_id as i64)).copied();
        match body_handle.and_then(|h| w.bodies.get_mut(h)) {
            Some(b) => {
                b.set_linvel(Vector::new(vx, vy, vz), wake);
                b.set_angvel(Vector::new(ax, ay, az), wake);
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

/// 设置旋转姿态（四元数 x,y,z,w）；`wake` 控制是否唤醒。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodySetRotation(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
    wake: jboolean,
) -> jboolean {
    if !finite(qx) || !finite(qy) || !finite(qz) || !finite(qw) {
        return JNI_FALSE;
    }
    let rotation = Rotation::from_xyzw(qx, qy, qz, qw).normalize();
    let done = with_world(handle, |w| {
        let body_handle = w.body_map.get(&(body_id as i64)).copied();
        match body_handle.and_then(|h| w.bodies.get_mut(h)) {
            Some(b) => {
                b.set_rotation(rotation, wake == JNI_TRUE);
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

/// 是否处于休眠（Rapier sleeping）。对应 MPS 的 `rigidBodyGetIsSleeping`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyGetIsSleeping(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
) -> jboolean {
    let sleeping = with_world(handle, |w| {
        w.body_map
            .get(&(body_id as i64))
            .and_then(|h| w.bodies.get(*h))
            .map(|b| b.is_sleeping())
    })
    .flatten();
    ok(sleeping.unwrap_or(false))
}

/// 主动唤醒。对应 MPS 的 `rigidBodySetWakeUp`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyWakeUp(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
) -> jboolean {
    let done = with_world(handle, |w| {
        let body_handle = w.body_map.get(&(body_id as i64)).copied();
        match body_handle.and_then(|h| w.bodies.get_mut(h)) {
            Some(b) => {
                b.wake_up(true);
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

/// 强制休眠（例如结构被冻结时）。对应 MPS 的 `rigidBodySetSleep`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodySleep(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
) -> jboolean {
    let done = with_world(handle, |w| {
        let body_handle = w.body_map.get(&(body_id as i64)).copied();
        match body_handle.and_then(|h| w.bodies.get_mut(h)) {
            Some(b) => {
                b.sleep();
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

/// 锁定/解锁旋转（实体接管需要：避免高瘦盒子无意义地翻倒）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyLockRotations(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    locked: jboolean,
) -> jboolean {
    let done = with_world(handle, |w| {
        let body_handle = w.body_map.get(&(body_id as i64)).copied();
        match body_handle.and_then(|h| w.bodies.get_mut(h)) {
            Some(b) => {
                b.lock_rotations(locked == JNI_TRUE, true);
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

/// 切换刚体类型（0=动态 1=固定 2=运动学位置 3=运动学速度）。
/// 用途：恢复存档时先把物理体冻结为固定体（避免无人时自由落体丢失），
/// 玩家靠近、地形碰撞体就绪后再切回动态。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodySetBodyType(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    body_type: jint,
) -> jboolean {
    let target = match body_type {
        0 => RigidBodyType::Dynamic,
        1 => RigidBodyType::Fixed,
        2 => RigidBodyType::KinematicPositionBased,
        3 => RigidBodyType::KinematicVelocityBased,
        _ => return JNI_FALSE,
    };
    let done = with_world(handle, |w| {
        let body_handle = w.body_map.get(&(body_id as i64)).copied();
        match body_handle.and_then(|h| w.bodies.get_mut(h)) {
            Some(b) => {
                b.set_body_type(target, true);
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

/// 移除挂在某刚体上的所有碰撞体（用于"方块被破坏/放置后重建体素碰撞体"）。
/// 返回被移除的碰撞体数量。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyClearColliders(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
) -> jint {
    let removed = with_world(handle, |w| {
        let Some(body_handle) = w.body_map.get(&(body_id as i64)).copied() else {
            return -1;
        };
        // 先克隆句柄列表，避免同时借用 bodies 与 colliders
        let handles: Vec<ColliderHandle> = match w.bodies.get(body_handle) {
            Some(b) => b.colliders().to_vec(),
            None => return -1,
        };
        let mut count = 0;
        for collider_handle in handles {
            if w.colliders
                .remove(collider_handle, &mut w.islands, &mut w.bodies, true)
                .is_some()
            {
                count += 1;
            }
        }
        // 同步清掉 Java 侧 id 映射里属于该刚体的条目
        w.collider_map.retain(|_, (owner, _)| *owner != body_id as i64);
        count
    });
    removed.unwrap_or(-1)
}

/// 累加一个持续力（Rapier 会一直施加，直到 reset 或下次 reset_forces）。
/// 语义：每步 Δv = F * dt / mass，因此"速度伺服"用 F = m(v_des - v)/τ。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyAddForce(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) -> jboolean {
    if !finite(x) || !finite(y) || !finite(z) {
        return JNI_FALSE;
    }
    let done = with_world(handle, |w| {
        let body_handle = w.body_map.get(&(body_id as i64)).copied();
        match body_handle.and_then(|h| w.bodies.get_mut(h)) {
            Some(b) => {
                b.add_force(Vector::new(x, y, z), true);
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

/// 清除之前累加的持续力（每个控制周期重置一次，避免力无限累积）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyResetForces(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
) -> jboolean {
    let done = with_world(handle, |w| {
        let body_handle = w.body_map.get(&(body_id as i64)).copied();
        match body_handle.and_then(|h| w.bodies.get_mut(h)) {
            Some(b) => {
                b.reset_forces(true);
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

// =====================================================================================
// ABI 5：Tier 1 能力（对标 space 0.1.3）
//
// 这一整段都是**只做加法**：老函数签名与语义一字未改，Java 侧用
// `PhysicsNatives.MIN_ABI_TIER1` 逐项把关，dll 未更新时自动降级。
// =====================================================================================

/// 对指定刚体执行一次可变操作；命中返回 true。
///
/// 抽出来是因为 ABI 5 新增的刚体操作有十几个，逐个写 `with_world` + `body_map` +
/// `bodies.get_mut` 三层样板会把真正的语义淹没。
fn body_mut(handle: jlong, body_id: jlong, f: impl FnOnce(&mut RigidBody)) -> jboolean {
    let done = with_world(handle, |w| {
        let Some(body_handle) = w.body_map.get(&(body_id as i64)).copied() else {
            return false;
        };
        match w.bodies.get_mut(body_handle) {
            Some(b) => {
                f(b);
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

/// Java 传入的组合规则序号 → Rapier 枚举（越界一律 Average，与 Rapier 默认一致）。
fn combine_rule(rule: jint) -> CoefficientCombineRule {
    match rule {
        1 => CoefficientCombineRule::Min,
        2 => CoefficientCombineRule::Multiply,
        3 => CoefficientCombineRule::Max,
        4 => CoefficientCombineRule::ClampedSum,
        _ => CoefficientCombineRule::Average,
    }
}

fn bounded_friction(v: jdouble) -> f64 {
    if finite(v) {
        v.max(0.0)
    } else {
        0.5
    }
}

fn bounded_restitution(v: jdouble) -> f64 {
    if finite(v) {
        v.clamp(0.0, 1.0)
    } else {
        0.0
    }
}

/// A1：设置（或清除）本维度的**无限地面**。
///
/// 对应 space 0.1.3 的 `PhysicalWorld.setMinY`：非太空维度在 `minBuildHeight`
/// 挂一个法线朝上的半空间，物理体掉出世界时有东西接住 —— 我们此前只有玩家有
/// `SAFE` 位置复位，船没有任何兜底。`enabled = false` 时清除。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldSetFloor(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    y: jdouble,
    enabled: jboolean,
) -> jboolean {
    if enabled == JNI_TRUE && !finite(y) {
        return JNI_FALSE;
    }
    let done = with_world(handle, |w| {
        if let Some(old) = w.floor.take() {
            w.colliders.remove(old, &mut w.islands, &mut w.bodies, true);
        }
        if enabled == JNI_TRUE {
            let collider = ColliderBuilder::new(SharedShape::halfspace(Vector::new(0.0, 1.0, 0.0)))
                .translation(Vector::new(0.0, y, 0.0))
                .friction(0.7)
                .restitution(0.0)
                .build();
            w.floor = Some(w.colliders.insert(collider));
        }
        true
    });
    ok(done.unwrap_or(false))
}

/// A5：设置运动学（位置型）刚体的**下一帧目标位置**。
///
/// 必须用它推进运动学体：`bodySetTranslation` 对运动学体是"瞬移"，
/// 求解器读不到速度，站在上面的东西不会被带着走（电梯/移动平台/传送带推不动）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodySetNextKinematicTranslation(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) -> jboolean {
    if !finite(x) || !finite(y) || !finite(z) {
        return JNI_FALSE;
    }
    body_mut(handle, body_id, |b| {
        b.set_next_kinematic_translation(Vector::new(x, y, z))
    })
}

/// A5：设置运动学（位置型）刚体的下一帧目标姿态（四元数 x,y,z,w）。
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodySetNextKinematicRotation(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
) -> jboolean {
    if !finite(qx) || !finite(qy) || !finite(qz) || !finite(qw) {
        return JNI_FALSE;
    }
    let rotation = Rotation::from_xyzw(qx, qy, qz, qw).normalize();
    body_mut(handle, body_id, |b| b.set_next_kinematic_rotation(rotation))
}

/// A2：累加一个持续**力矩**（N·m），配合 `bodyResetTorque` 做转速伺服。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyAddTorque(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) -> jboolean {
    if !finite(x) || !finite(y) || !finite(z) {
        return JNI_FALSE;
    }
    body_mut(handle, body_id, |b| {
        b.add_torque(Vector::new(x, y, z), true)
    })
}

/// A2：清除之前累加的持续力矩。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyResetTorque(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
) -> jboolean {
    body_mut(handle, body_id, |b| b.reset_torques(true))
}

/// A2：施加一个**角冲量**（kg·m²/s）—— 反作用轮、螺旋桨启动的瞬时加速。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyApplyTorqueImpulse(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) -> jboolean {
    if !finite(x) || !finite(y) || !finite(z) {
        return JNI_FALSE;
    }
    body_mut(handle, body_id, |b| {
        b.apply_torque_impulse(Vector::new(x, y, z), true)
    })
}

/// A2：在**偏离质心的点**上施加力 —— 偏心推进器会让船自转，这是"真实火箭"的来源。
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyAddForceAtPoint(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    fx: jdouble,
    fy: jdouble,
    fz: jdouble,
    px: jdouble,
    py: jdouble,
    pz: jdouble,
) -> jboolean {
    if !finite(fx) || !finite(fy) || !finite(fz) || !finite(px) || !finite(py) || !finite(pz) {
        return JNI_FALSE;
    }
    body_mut(handle, body_id, |b| {
        b.add_force_at_point(Vector::new(fx, fy, fz), Vector::new(px, py, pz), true)
    })
}

/// A3：设置线速度/角速度阻尼（1/s），负值按 0 处理。
///
/// 太空里"航行阻尼"就靠它：space 的 `RigidBody` 构造器一直带着这两个参数，我们此前没有。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodySetDamping(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    linear: jdouble,
    angular: jdouble,
) -> jboolean {
    if !finite(linear) || !finite(angular) {
        return JNI_FALSE;
    }
    body_mut(handle, body_id, |b| {
        b.set_linear_damping(linear.max(0.0));
        b.set_angular_damping(angular.max(0.0));
    })
}

/// A3：重力缩放（0 = 不受重力，1 = 正常）。做"局部反重力/悬停平台"用。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodySetGravityScale(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    scale: jdouble,
) -> jboolean {
    if !finite(scale) {
        return JNI_FALSE;
    }
    body_mut(handle, body_id, |b| b.set_gravity_scale(scale, true))
}

/// A3：设置**附加质量属性**（质心 + 质量 + 主转动惯量），覆盖由碰撞体推导的那套。
///
/// 对应 space 0.1.3 `RigidBody` 构造器的 `mass_center` / `mass` / `principal_inertia`。
/// `mass` 必须 &gt; 0；惯量给 0 表示"由 Rapier 按形状补"，需要精确控制转动惯量时再给值。
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodySetAdditionalMassProperties(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    cx: jdouble,
    cy: jdouble,
    cz: jdouble,
    mass: jdouble,
    ix: jdouble,
    iy: jdouble,
    iz: jdouble,
) -> jboolean {
    if !finite(cx)
        || !finite(cy)
        || !finite(cz)
        || !finite(mass)
        || !finite(ix)
        || !finite(iy)
        || !finite(iz)
        || mass <= 0.0
    {
        return JNI_FALSE;
    }
    let props = MassProperties::new(
        Vector::new(cx, cy, cz),
        mass,
        Vector::new(ix.max(0.0), iy.max(0.0), iz.max(0.0)),
    );
    body_mut(handle, body_id, |b| {
        b.set_additional_mass_properties(props, true)
    })
}

/// A3：开关连续碰撞检测（CCD）—— 高速物体（炮弹、坠落的船）不会穿过地形。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyEnableCcd(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    enabled: jboolean,
) -> jboolean {
    body_mut(handle, body_id, |b| b.enable_ccd(enabled == JNI_TRUE))
}

/// A3：逐轴开关旋转（比 `bodyLockRotations` 的全锁/全放更细）。
///
/// 例：只锁 X/Z（不让船侧翻）而放开 Y（允许偏航）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodySetEnabledRotations(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    x_enabled: jboolean,
    y_enabled: jboolean,
    z_enabled: jboolean,
) -> jboolean {
    body_mut(handle, body_id, |b| {
        b.set_enabled_rotations(
            x_enabled == JNI_TRUE,
            y_enabled == JNI_TRUE,
            z_enabled == JNI_TRUE,
            true,
        )
    })
}

/// A4：设置碰撞体的**材质**（摩擦 / 弹性 / contact skin / 组合规则）。
///
/// contact skin 是 Rapier 消接触抖动的手段，space 0.1.3 全用它（`CONTACT_SKIN = 0.02`）；
/// 组合规则决定"玩家摩擦 20"和"地形摩擦 0.7"相遇时取哪个值（Average/Min/Multiply/Max）。
/// 我们此前完全没设过这两项，全是 Rapier 默认。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderSetMaterial(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    collider_id: jlong,
    friction: jdouble,
    restitution: jdouble,
    contact_skin: jdouble,
    friction_rule: jint,
    restitution_rule: jint,
) -> jboolean {
    if !finite(friction) || !finite(restitution) || !finite(contact_skin) {
        return JNI_FALSE;
    }
    let done = with_world(handle, |w| {
        let Some((_, collider_handle)) = w.collider_map.get(&(collider_id as i64)).copied() else {
            return false;
        };
        match w.colliders.get_mut(collider_handle) {
            Some(c) => {
                c.set_friction(friction.max(0.0));
                c.set_restitution(restitution.clamp(0.0, 1.0));
                // contact skin 大于半格没有物理意义，夹住防止调用方传错把体素撑开
                c.set_contact_skin(contact_skin.clamp(0.0, 0.5));
                c.set_friction_combine_rule(combine_rule(friction_rule));
                c.set_restitution_combine_rule(combine_rule(restitution_rule));
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

// ==================== ABI 6：让 Java 侧能照 MPS 的原样写，而不是迁就缺口 ====================
//
// 动机（记录一次教训）：我们此前为了绕开"原生只能在建体时给分组/材质"这个缺口，
// 在 Java 侧发明了等价仿真 —— 例如把 `addColliderBody` 推迟到 `up()` 才创建，
// 好让"先挂载、再 setCollisionGroups"这种 MPS 调用顺序能生效。
//
// 那是"殊途同归"：结果凑对了，方法却和对方不一样，于是任何一处时序变动都会重新炸。
// MPS 的原生本来就把这些属性做成**可随时修改**的（`colliderSetCollisionGroups` /
// `colliderSetFriction` / `colliderSetRestitution` / 组合规则 / 传感器 / 事件阈值），
// 所以它们的调用顺序是自由的。正确做法是把缺的函数补齐，而不是让 Java 去迁就。

/// 供各 `colliderSet*` 复用的碰撞体定位（Java 侧 id → Rapier 句柄 → 可变引用）。
fn collider_mut(
    handle: jlong,
    collider_id: jlong,
    f: impl FnOnce(&mut Collider) -> bool,
) -> jboolean {
    let done = with_world(handle, |w| {
        let Some((_, collider_handle)) = w.collider_map.get(&(collider_id as i64)).copied() else {
            return false;
        };
        match w.colliders.get_mut(collider_handle) {
            Some(c) => f(c),
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

/// 实时改碰撞组 —— 对应 MPS 的 `colliderSetCollisionGroups`。
///
/// 补上它之后，`ColliderBody.setCollisionGroups` 就能照 MPS 那样随时调用，
/// 不再需要"必须在挂载前设置"的人为限制。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderSetCollisionGroups(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    collider_id: jlong,
    membership: jint,
    filter: jint,
) -> jboolean {
    let groups = InteractionGroups::new(
        Group::from_bits_truncate(membership as u32),
        Group::from_bits_truncate(filter as u32),
        InteractionTestMode::And,
    );
    collider_mut(handle, collider_id, |c| {
        c.set_collision_groups(groups);
        true
    })
}

/// 实时改摩擦 —— 对应 MPS 的 `colliderSetFriction`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderSetFriction(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    collider_id: jlong,
    friction: jdouble,
) -> jboolean {
    if !finite(friction) {
        return JNI_FALSE;
    }
    collider_mut(handle, collider_id, |c| {
        c.set_friction(friction.max(0.0));
        true
    })
}

/// 实时改弹性 —— 对应 MPS 的 `colliderSetRestitution`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderSetRestitution(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    collider_id: jlong,
    restitution: jdouble,
) -> jboolean {
    if !finite(restitution) {
        return JNI_FALSE;
    }
    collider_mut(handle, collider_id, |c| {
        c.set_restitution(restitution.clamp(0.0, 1.0));
        true
    })
}

/// 实时改摩擦组合规则 —— 对应 MPS 的 `colliderSetFrictionCombineRule`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderSetFrictionCombineRule(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    collider_id: jlong,
    rule: jint,
) -> jboolean {
    collider_mut(handle, collider_id, |c| {
        c.set_friction_combine_rule(combine_rule(rule));
        true
    })
}

/// 实时改弹性组合规则 —— 对应 MPS 的 `colliderSetRestitutionCombineRule`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderSetRestitutionCombineRule(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    collider_id: jlong,
    rule: jint,
) -> jboolean {
    collider_mut(handle, collider_id, |c| {
        c.set_restitution_combine_rule(combine_rule(rule));
        true
    })
}

/// 传感器开关 —— 对应 MPS 的 `colliderSetSensor`（只报事件、不产生接触力）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderSetSensor(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    collider_id: jlong,
    sensor: jboolean,
) -> jboolean {
    collider_mut(handle, collider_id, |c| {
        c.set_sensor(sensor == JNI_TRUE);
        true
    })
}

/// 碰撞事件开关（`ActiveEvents` 位掩码）—— 对应 MPS 的 `colliderSetActiveEvents`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderSetActiveEvents(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    collider_id: jlong,
    events: jint,
) -> jboolean {
    let flags = ActiveEvents::from_bits_truncate(events as u32);
    collider_mut(handle, collider_id, |c| {
        c.set_active_events(flags);
        true
    })
}

/// 接触力事件阈值 —— 对应 MPS 的 `colliderSetContactForceEventThreshold`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderSetContactForceEventThreshold(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    collider_id: jlong,
    threshold: jdouble,
) -> jboolean {
    if !finite(threshold) {
        return JNI_FALSE;
    }
    collider_mut(handle, collider_id, |c| {
        c.set_contact_force_event_threshold(threshold);
        true
    })
}

/// 一次性设置位姿（位置 + 姿态）—— 对应 MPS 的 `rigidBodySetPose`。
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodySetPose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
    wake: jboolean,
) -> jboolean {
    if !finite(x) || !finite(y) || !finite(z) || !finite(qx) || !finite(qy) || !finite(qz) || !finite(qw) {
        return JNI_FALSE;
    }
    let rotation = Rotation::from_xyzw(qx, qy, qz, qw).normalize();
    let wake = wake == JNI_TRUE;
    // 注意：Rapier 0.34 的 set_position 收 Pose（整体位姿）；这里按位姿的两个分量分别写，
    // 与 bodySetTranslation / bodySetRotation 的既有语义保持一致。
    body_mut(handle, body_id, |b| {
        b.set_translation(Vector::new(x, y, z), wake);
        b.set_rotation(rotation, wake);
    })
}

/// 读世界重力到 `out`（double[3]）—— 对应 MPS 的 `worldGetGravity`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldGetGravity(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    out: JDoubleArray,
) -> jboolean {
    let g = with_world(handle, |w| (w.gravity.x, w.gravity.y, w.gravity.z));
    match g {
        Some((x, y, z)) => ok(env.set_double_array_region(&out, 0, &[x, y, z]).is_ok()),
        None => JNI_FALSE,
    }
}

/// 世界内刚体数量 —— 对应 MPS 的 `worldGetRigidBodySetSize`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldGetRigidBodySetSize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_world(handle, |w| w.bodies.len() as jint).unwrap_or(-1)
}

/// 世界内碰撞体数量 —— 对应 MPS 的 `worldGetColliderSetSize`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldGetColliderSetSize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_world(handle, |w| w.colliders.len() as jint).unwrap_or(-1)
}

/// 从世界移除**单个**碰撞体 —— 对应 MPS 的 `worldRemoveCollider`。
///
/// 补它的理由：此前只能 `bodyClearColliders`（按刚体整体清），
/// 于是 `RapierWorld.removeColliderBody` 只能做成"Java 侧摘记录、原生还留着"——
/// 结果凑对了、方法错了。Rapier 本来就支持按句柄移除。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldRemoveCollider(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    collider_id: jlong,
    wake: jboolean,
) -> jboolean {
    let done = with_world(handle, |w| {
        let Some((_, collider_handle)) = w.collider_map.remove(&(collider_id as i64)) else {
            return false;
        };
        w.colliders
            .remove(collider_handle, &mut w.islands, &mut w.bodies, wake == JNI_TRUE);
        true
    });
    ok(done.unwrap_or(false))
}

/// 从世界移除刚体（带唤醒开关）—— 对应 MPS 的 `worldRemoveRigidBody`。
///
/// 与既有的 `bodyDestroy` 同类，区别只是把 `wake` 交给调用方
///（MPS 的移除操作带这个参数，摘体时通常不希望把周围唤醒）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldRemoveRigidBody(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    wake: jboolean,
) -> jboolean {
    let done = with_world(handle, |w| match w.body_map.remove(&(body_id as i64)) {
        Some(body_handle) => {
            w.bodies.remove(
                body_handle,
                &mut w.islands,
                &mut w.colliders,
                &mut w.impulse_joints,
                &mut w.multibody_joints,
                wake == JNI_TRUE,
            );
            // 同步清掉 Java 侧 id 映射：Rapier 会复用句柄槽位，留着就会改到别人的体上
            w.collider_map.retain(|_, (owner, _)| *owner != body_id as i64);
            true
        }
        None => false,
    });
    ok(done.unwrap_or(false))
}

// ==================== ABI 8：分离对象（不属于任何世界的刚体 / 碰撞体） ====================
//
// 动机：MPS 的跨维度搬运是 "copy out → insert into another world"
// （`ServerPhysicalWorld.dimensionLeapPhysicalBody` 正是这样把船搬过维度的）。
// 我们此前没有"不属于任何世界的对象"这个概念，那条路就只能靠
// "读位姿 + 在新世界重建"来冒充 —— 又一处殊途同归：速度、角速度、质量属性、
// 材质组合规则、碰撞组这些细节在"重建"时全靠调用方记得补齐，漏一个就悄悄失真。

/// 分离对象：从世界复制出来、尚未插回任何世界的物体。
enum Detached {
    Body(RigidBody),
    Collider(Collider),
    /// cosmos 的 builder（还没插进任何 cosmos 世界的天体）—— ABI 9；
    /// 与刚体/碰撞体共用同一个竞技场，是为了只有**一套**分离对象生命周期（少一套泄漏面）。
    Cosmos(CosmosBuilder),
}

#[derive(Default)]
struct DetachedArena {
    objects: HashMap<i64, Detached>,
    next: i64,
}

/// 分离对象竞技场（全局）。
///
/// 与"世界内 id"分开编号：这里的句柄（MPS 的 memory handle）在插入世界前不属于任何世界，
/// 因此不能放在某个世界的 `body_map` / `collider_map` 里。
fn detached_arena() -> &'static Mutex<DetachedArena> {
    static ARENA: OnceLock<Mutex<DetachedArena>> = OnceLock::new();
    ARENA.get_or_init(|| Mutex::new(DetachedArena::default()))
}

fn arena_put(obj: Detached) -> i64 {
    match detached_arena().lock() {
        Ok(mut a) => {
            a.next += 1;
            let id = a.next;
            a.objects.insert(id, obj);
            id
        }
        Err(_) => -1,
    }
}

fn arena_take(mem: i64) -> Option<Detached> {
    detached_arena().lock().ok().and_then(|mut a| a.objects.remove(&mem))
}

/// 把刚体从世界里**复制**出来（原体保留，由调用方决定是否移除）——
/// 对应 MPS 的 `worldCopyRigidBody`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldCopyRigidBody(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
) -> jlong {
    let cloned = with_world(handle, |w| {
        w.body_map
            .get(&(body_id as i64))
            .and_then(|h| w.bodies.get(*h))
            .cloned()
    })
    .flatten();
    match cloned {
        Some(body) => arena_put(Detached::Body(body)),
        None => -1,
    }
}

/// 把碰撞体从世界里**复制**出来 —— 对应 MPS 的 `worldCopyCollider`（同样保留原体）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldCopyCollider(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    collider_id: jlong,
) -> jlong {
    let cloned = with_world(handle, |w| {
        w.collider_map
            .get(&(collider_id as i64))
            .and_then(|(_, h)| w.colliders.get(*h))
            .cloned()
    })
    .flatten();
    match cloned {
        Some(collider) => arena_put(Detached::Collider(collider)),
        None => -1,
    }
}

/// 把分离的刚体插进（另一个）世界 —— 对应 MPS 的 `worldInsertRigidBody`。
///
/// 句柄被**消费**：插入后请改用返回的世界内 id。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldInsertRigidBody(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    mem: jlong,
) -> jlong {
    match arena_take(mem) {
        Some(Detached::Body(body)) => with_world(handle, |w| {
            if w.body_map.len() >= MAX_BODIES_PER_WORLD {
                return -1;
            }
            let body_handle = w.bodies.insert(body);
            let id = w.alloc_id();
            w.body_map.insert(id, body_handle);
            id
        })
        .unwrap_or(-1),
        _ => -1,
    }
}

/// 把分离的碰撞体插进世界（无父体）—— 对应 MPS 的 `worldInsertCollider`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldInsertCollider(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    mem: jlong,
) -> jlong {
    match arena_take(mem) {
        Some(Detached::Collider(collider)) => with_world(handle, |w| {
            let collider_handle = w.colliders.insert(collider);
            let id = w.alloc_id();
            w.collider_map.insert(id, (-1, collider_handle));
            id
        })
        .unwrap_or(-1),
        _ => -1,
    }
}

/// 把分离的碰撞体插进世界并挂到指定刚体上 —— 对应 MPS 的 `worldInsertColliderWithParent`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldInsertColliderWithParent(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    mem: jlong,
    parent_body_id: jlong,
) -> jlong {
    match arena_take(mem) {
        Some(Detached::Collider(collider)) => with_world(handle, |w| {
            let Some(body_handle) = w.body_map.get(&(parent_body_id as i64)).copied() else {
                return -1;
            };
            let collider_handle =
                w.colliders
                    .insert_with_parent(collider, body_handle, &mut w.bodies);
            let id = w.alloc_id();
            w.collider_map.insert(id, (parent_body_id as i64, collider_handle));
            id
        })
        .unwrap_or(-1),
        _ => -1,
    }
}

/// 释放一个分离对象 —— 对应 MPS 的 `RapierConnect.RustMemoryFree`。
///
/// MPS 靠 `Cleaner` + `Unsafe` 管理这些内存句柄；我们这边句柄就是竞技场里的一条记录，
/// 释放 = 移除。**没插回世界就丢弃的分离对象必须调它**，否则会一直留在竞技场里。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_RustMemoryFree(
    _env: JNIEnv,
    _class: JClass,
    mem: jlong,
) {
    let _ = arena_take(mem);
}

// ==================== ABI 9：cosmos（kelvin 的天体 N 体引力）====================
//
// 契约**来自调用点反推，不是猜**（decompiled-space 0.1.3）：
//   SpaceWorld.java:134  cosmosWorldCreate(core_tick_time, 4, 1, 1, 1, 1000000.0)
//   SpaceWorld.java:153  cosmosWorldStep(handle, core_tick_time)
//   SpaceWorld.java:273  cosmosFixedBodyBuilder(px, py, pz)
//   SpaceWorld.java:275  cosmosSatelliteBuilder(mass, px,py,pz, vx,vy,vz, radius)
//   SpaceWorld.java:282  cosmosWorldInsertBodyAsGravitySource(world, builder, mass)
//   SpaceWorld.java:188  cosmosBodyTranslationOut(world, handle, buf24) -> 0 表示成功
//   SpaceWorld.java:203  cosmosBodyLinvelOut(world, handle, buf24)      -> 同
// 其余 5 个（cosmosWorldAddNBody / cosmosWorldInsertBody / cosmosBodyMass /
// cosmosWorldDynamicBodyCount / cosmosBuilderDestroy）在 space 里**零调用**，不实现。
//
// 读回缓冲区：kelvin 用 `RapierConnect.unsafe.allocateMemory(24)` 的裸指针 + unsafe.getDouble。
// 我们改成 JNI 的 `double[3]` 出参（同语义、无 Unsafe），调用方换成读数组即可。
//
// 单位：与 kelvin 的 Java 侧 `getGravitationForce` 保持一致 —— 引力常数 6.6743e-11，
// 质量与距离都按调用方给的原值，不做单位换算（换算属于上层的事）。

/// 与 kelvin 的 Java 侧重力和常数同值（那边也是 6.6743E-11）。
const COSMOS_GRAVITY_CONSTANT: f64 = 6.6743e-11;

/// 一个天体积分体。
struct CosmosBody {
    mass: f64,
    pos: [f64; 3],
    vel: [f64; 3],
    #[allow(dead_code)]
    radius: f64,
    /// 固定体（恒星/行星核）：**不参与积分**，位置永远不变。
    fixed: bool,
    /// 引力源：对其它体产生引力。
    source: bool,
}

/// builder（还没插进世界的天体）—— 复用 ABI 8 的分离竞技场存放。
struct CosmosBuilder {
    mass: f64,
    pos: [f64; 3],
    vel: [f64; 3],
    radius: f64,
    fixed: bool,
}

struct CosmosWorld {
    bodies: Vec<CosmosBody>,
    /// 与 bodies 一一对应的句柄（Java 侧用它读回位姿/速度）。
    handles: Vec<i64>,
    next_handle: i64,
    /// 每个外层步内部的子步数（调用点传 4）。
    substeps: u32,
    /// 远场截断距离（调用点传 1e6）。直接 O(n²) 积分用不到，仅记录以备将来做粒子网格近似。
    #[allow(dead_code)]
    far_field: f64,
}

impl CosmosWorld {
    /// 半隐式欧拉 + 子步：先按当前加速度更新速度，再用**新速度**推进位置。
    ///
    /// 为什么用半隐式（而不是显式欧拉）：显式欧拉在轨道问题里能量单调增长、
    /// 轨道会越绕越大（几圈就飞出去）；半隐式欧拉能量有界、轨道长期稳定 —— 对"行星绕恒星"
    /// 这种长期积分是必须的，代价只是位置用新速度（一行之差）。
    fn step(&mut self, dt: f64) {
        let n = self.substeps.max(1);
        let h = dt / n as f64;
        for _ in 0..n {
            let len = self.bodies.len();
            let mut acc = vec![[0.0f64; 3]; len];
            for i in 0..len {
                if self.bodies[i].fixed {
                    continue;
                }
                let mut a = [0.0f64; 3];
                for j in 0..len {
                    if i == j || !self.bodies[j].source {
                        continue;
                    }
                    let d = [
                        self.bodies[j].pos[0] - self.bodies[i].pos[0],
                        self.bodies[j].pos[1] - self.bodies[i].pos[1],
                        self.bodies[j].pos[2] - self.bodies[i].pos[2],
                    ];
                    let r2 = d[0] * d[0] + d[1] * d[1] + d[2] * d[2];
                    if !(r2 > 0.0) || !r2.is_finite() {
                        continue; // 重合/非法：跳过（不能让一次除零毁掉整个积分）
                    }
                    let r = r2.sqrt();
                    // a = G·M / r²，方向沿 d/r
                    let f = COSMOS_GRAVITY_CONSTANT * self.bodies[j].mass / r2;
                    if !f.is_finite() {
                        continue;
                    }
                    a[0] += d[0] / r * f;
                    a[1] += d[1] / r * f;
                    a[2] += d[2] / r * f;
                }
                acc[i] = a;
            }
            for i in 0..len {
                if self.bodies[i].fixed {
                    continue;
                }
                for k in 0..3 {
                    self.bodies[i].vel[k] += acc[i][k] * h;
                    self.bodies[i].pos[k] += self.bodies[i].vel[k] * h;
                }
            }
        }
    }
}

type CosmosRef = std::sync::Arc<Mutex<CosmosWorld>>;

static COSMOS_WORLDS: OnceLock<Mutex<HashMap<i64, CosmosRef>>> = OnceLock::new();
static NEXT_COSMOS_ID: OnceLock<Mutex<i64>> = OnceLock::new();

fn cosmos_worlds() -> &'static Mutex<HashMap<i64, CosmosRef>> {
    COSMOS_WORLDS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 与 `with_world` 同一套加锁规矩：先取 map 锁、克隆 Arc、立刻放掉，再锁世界。
fn cosmos_ref(handle: jlong) -> Option<CosmosRef> {
    if handle <= 0 {
        return None;
    }
    let map = cosmos_worlds().lock().ok()?;
    map.get(&(handle as i64)).cloned()
}

fn cosmos_with<R>(handle: jlong, f: impl FnOnce(&mut CosmosWorld) -> R) -> Option<R> {
    let world = cosmos_ref(handle)?;
    let mut guard = world.lock().ok()?;
    Some(f(&mut guard))
}

/// 建一个 cosmos 世界 —— 对应 kelvin 的 `SpaceNative.cosmosWorldCreate`。
///
/// 参数按调用点解释：`dt`（本步时长）、`substeps`（调用点传 4）、
/// 中间三个 int（调用点都是 1，属粒子网格维度；我们用直接 O(n²) 积分，故忽略）、
/// `far_field`（调用点 1e6，远场截断；直接积分用不到，仅记录）。
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_cosmosWorldCreate(
    _env: JNIEnv,
    _class: JClass,
    dt: jdouble,
    substeps: jint,
    _grid_x: jint,
    _grid_y: jint,
    _grid_z: jint,
    far_field: jdouble,
) -> jlong {
    if !finite(dt) || !finite(far_field) {
        return -1;
    }
    let world = CosmosWorld {
        bodies: Vec::new(),
        handles: Vec::new(),
        next_handle: 0,
        substeps: if substeps > 0 { substeps as u32 } else { 1 },
        far_field,
    };
    let id = match NEXT_COSMOS_ID.get_or_init(|| Mutex::new(0)).lock() {
        Ok(mut n) => {
            *n += 1;
            *n
        }
        Err(_) => return -1,
    };
    let mut map = match cosmos_worlds().lock() {
        Ok(m) => m,
        Err(_) => return -1,
    };
    map.insert(id, std::sync::Arc::new(Mutex::new(world)));
    id as jlong
}

/// 销毁 cosmos 世界 —— 对应 `cosmosWorldDestroy`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_cosmosWorldDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Ok(mut map) = cosmos_worlds().lock() {
        map.remove(&(handle as i64));
    }
}

/// 推进一次 —— 对应 `cosmosWorldStep`；返回天体数（失败 -1）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_cosmosWorldStep(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    dt: jdouble,
) -> jint {
    if !finite(dt) {
        return -1;
    }
    match cosmos_with(handle, |w| {
        w.step(dt);
        w.bodies.len() as jint
    }) {
        Some(n) => n,
        None => -1,
    }
}

/// 造一个"固定天体"builder（恒星/行星核）—— 对应 `cosmosFixedBodyBuilder`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_cosmosFixedBodyBuilder(
    _env: JNIEnv,
    _class: JClass,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) -> jlong {
    if !finite(x) || !finite(y) || !finite(z) {
        return -1;
    }
    arena_put(Detached::Cosmos(CosmosBuilder {
        mass: 0.0, // 质量由 insertBodyAsGravitySource 传入（调用点就是这么做的）
        pos: [x, y, z],
        vel: [0.0; 3],
        radius: 0.0,
        fixed: true,
    }))
}

/// 造一个"卫星"builder（会绕源运动）—— 对应 `cosmosSatelliteBuilder`。
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_cosmosSatelliteBuilder(
    _env: JNIEnv,
    _class: JClass,
    mass: jdouble,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    vx: jdouble,
    vy: jdouble,
    vz: jdouble,
    radius: jdouble,
) -> jlong {
    if !finite(mass) || !finite(x) || !finite(y) || !finite(z)
        || !finite(vx) || !finite(vy) || !finite(vz) || !finite(radius)
    {
        return -1;
    }
    arena_put(Detached::Cosmos(CosmosBuilder {
        mass,
        pos: [x, y, z],
        vel: [vx, vy, vz],
        radius,
        fixed: false,
    }))
}

/// 把 builder 插进世界并登记为**引力源**（同时本身也是被积分的体）——
/// 对应 `cosmosWorldInsertBodyAsGravitySource`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_cosmosWorldInsertBodyAsGravitySource(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    builder: jlong,
    mass: jdouble,
) -> jlong {
    if !finite(mass) {
        return -1;
    }
    let Some(Detached::Cosmos(b)) = arena_take(builder) else {
        return -1;
    };
    cosmos_with(handle, |w| {
        w.next_handle += 1;
        let body_handle = w.next_handle;
        w.bodies.push(CosmosBody {
            mass,
            pos: b.pos,
            vel: b.vel,
            radius: b.radius,
            fixed: b.fixed,
            source: true, // 调用点全部用它插入，因此一律是引力源
        });
        w.handles.push(body_handle);
        body_handle
    })
    .unwrap_or(-1)
}

/// 读回某天体的位置到 `out[0..3]` —— 对应 `cosmosBodyTranslationOut`。
///
/// **返回约定照 kelvin 的调用方**：`SpaceWorld.getCosmosTranslation` 写的是
/// `... == 0 ? null : 值` —— 即 **0 = 失败/无数据，非 0 = 成功**。
/// （此前我按"0 = 成功"实现，正好反了；这类"约定方向"必须从调用点读，不能凭直觉。）
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_cosmosBodyTranslationOut(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_handle: jlong,
    out: JDoubleArray,
) -> jint {
    let pos = cosmos_with(handle, |w| {
        w.handles
            .iter()
            .position(|h| *h == body_handle)
            .map(|i| w.bodies[i].pos)
    })
    .flatten();
    match pos {
        Some(p) => {
            if env.set_double_array_region(&out, 0, &p).is_ok() { 1 } else { 0 }
        }
        None => 0,
    }
}

/// 读回某天体的速度到 `out[0..3]` —— 对应 `cosmosBodyLinvelOut`；同样 **0 = 失败，非 0 = 成功**。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_cosmosBodyLinvelOut(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_handle: jlong,
    out: JDoubleArray,
) -> jint {
    let vel = cosmos_with(handle, |w| {
        w.handles
            .iter()
            .position(|h| *h == body_handle)
            .map(|i| w.bodies[i].vel)
    })
    .flatten();
    match vel {
        Some(v) => {
            if env.set_double_array_region(&out, 0, &v).is_ok() { 1 } else { 0 }
        }
        None => 0,
    }
}

/// B1：挂一个**任意盒复合碰撞体**（每盒 6 个 double：minX,minY,minZ,maxX,maxY,maxZ，刚体局部空间）。
///
/// 这是我们缺的那块地形精度：满碰撞形状走体素（`colliderAttachVoxels`），
/// 非满形状（台阶/楼梯/栅栏/墙/锁链…）必须用一堆盒子表达，否则半砖会被当成整格。
/// 对应 space 0.1.3 的 `ColliderBody.Type.COMPLEX_VOXEL`（`colliderBuilderCreateCompoundBoxes`）。
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderAttachBoxes(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
    boxes: JDoubleArray,
    friction: jdouble,
    restitution: jdouble,
    membership: jint,
    filter: jint,
) -> jlong {
    let len = match env.get_array_length(&boxes) {
        Ok(n) if n as usize >= 6 => n as usize,
        _ => return -1,
    };
    if len % 6 != 0 || len / 6 > MAX_BOXES_PER_COLLIDER {
        return -1;
    }
    let mut raw = vec![0f64; len];
    if env.get_double_array_region(&boxes, 0, &mut raw).is_err() {
        return -1;
    }

    let mut shapes: Vec<(Pose, SharedShape)> = Vec::with_capacity(len / 6);
    for c in raw.chunks_exact(6) {
        let (min_x, min_y, min_z, max_x, max_y, max_z) = (c[0], c[1], c[2], c[3], c[4], c[5]);
        if !finite(min_x)
            || !finite(min_y)
            || !finite(min_z)
            || !finite(max_x)
            || !finite(max_y)
            || !finite(max_z)
        {
            return -1;
        }
        let hx = (max_x - min_x).abs() * 0.5;
        let hy = (max_y - min_y).abs() * 0.5;
        let hz = (max_z - min_z).abs() * 0.5;
        if hx <= 0.0 || hy <= 0.0 || hz <= 0.0 {
            continue; // 退化盒（零厚度）直接跳过，避免造出无碰撞体
        }
        shapes.push((
            Pose::translation(
                (min_x + max_x) * 0.5,
                (min_y + max_y) * 0.5,
                (min_z + max_z) * 0.5,
            ),
            SharedShape::cuboid(hx, hy, hz),
        ));
    }
    if shapes.is_empty() {
        return -1;
    }

    let friction = bounded_friction(friction);
    let restitution = bounded_restitution(restitution);
    let groups = InteractionGroups::new(
        Group::from_bits_truncate(membership as u32),
        Group::from_bits_truncate(filter as u32),
        InteractionTestMode::And,
    );

    match with_world(handle, |w| {
        let Some(body_handle) = w.body_map.get(&(body_id as i64)).copied() else {
            return -1;
        };
        let collider = ColliderBuilder::new(SharedShape::compound(shapes))
            .friction(friction)
            .restitution(restitution)
            .collision_groups(groups)
            .build();
        let collider_handle = w
            .colliders
            .insert_with_parent(collider, body_handle, &mut w.bodies);
        let id = w.alloc_id();
        w.collider_map.insert(id, (body_id as i64, collider_handle));
        id
    }) {
        Some(id) => id as jlong,
        None => -1,
    }
}

/// 在世界里投一条射线，返回最近命中：`out = [toi, nx, ny, nz, colliderId]`。
///
/// 对应 space 0.1.3 的 `RapierWorld.castRay(origin, direction, maxToi, memberships, filter)`。
/// space 用它做**玩家探地**（`MixinEntity` 里从脚下五点向下打 0.1m），
/// 从而在物理驱动下正确设置 `onGround` —— 我们此前**完全没设过**，
/// 所以"站在船上"时原版认为你在空中：不能跳、摔落距离一直累加、冲刺会被打断。
///
/// `memberships`/`filter` 是**查询方**的碰撞组：查询与碰撞体交互 ⟺
/// `(查询.memberships & 碰撞体.filter) != 0` 且 `(碰撞体.memberships & 查询.filter) != 0`。
/// 玩家用 `(2, 5)` 就能打到地形 `(1,-1)` 与物理体 `(4,-1)`，同时**打不到自己的一对盒子**
/// （自己的 mem 是 2 和 5，与 filter 5 相与为 0）。
///
/// @return 是否命中；命中时把 toi / 法线 / 碰撞体 id 写进 `out`（长度须 ≥ 5）
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_worldCastRay(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    ox: jdouble,
    oy: jdouble,
    oz: jdouble,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
    max_toi: jdouble,
    memberships: jint,
    filter: jint,
    out: JDoubleArray,
) -> jboolean {
    if !finite(ox)
        || !finite(oy)
        || !finite(oz)
        || !finite(dx)
        || !finite(dy)
        || !finite(dz)
        || !finite(max_toi)
        || max_toi <= 0.0
    {
        return JNI_FALSE;
    }
    let groups = InteractionGroups::new(
        Group::from_bits_truncate(memberships as u32),
        Group::from_bits_truncate(filter as u32),
        InteractionTestMode::And,
    );
    let hit = with_world(handle, |w| {
        let ray = Ray::new(Vector::new(ox, oy, oz), Vector::new(dx, dy, dz));
        let query_filter = QueryFilter::new().groups(groups).exclude_sensors();
        let pipeline = w.broad.as_query_pipeline(
            w.narrow.query_dispatcher(),
            &w.bodies,
            &w.colliders,
            query_filter,
        );
        pipeline
            .cast_ray_and_get_normal(&ray, max_toi, true)
            .map(|(collider_handle, inter)| {
                // 反查 Java 侧碰撞体 id（诊断/上层区分地形与物理体用；查不到就当 -1）
                let java_id = w
                    .collider_map
                    .iter()
                    .find(|(_, (_, h))| *h == collider_handle)
                    .map(|(id, _)| *id)
                    .unwrap_or(-1);
                let n = inter.normal;
                (inter.time_of_impact, n.x, n.y, n.z, java_id as f64)
            })
    })
    .flatten();

    match hit {
        Some((toi, nx, ny, nz, id)) => {
            let data = [toi, nx, ny, nz, id];
            ok(env.set_double_array_region(&out, 0, &data).is_ok())
        }
        None => JNI_FALSE,
    }
}

/// 设置碰撞体**密度**（kg/m³）。
///
/// 为什么必须有：Rapier 的默认密度是 `1.0`，也就是"**一格方块 = 1 kg**" —— 石头、铁块、羊毛
/// 一样重，而且比 50 kg 的玩家还轻。于是任何物理体都"一碰就飞"。
/// 设成方块材质对应的真实密度后，总质量 = 体素数 × 密度：
/// 一格石头 ≈ 2500 kg、一格钢 ≈ 7800 kg，人推不动；羊毛 200 kg 还推得动。
///
/// 体素碰撞体只能有一个密度，所以上层传的是**平均密度**（Σ 单块密度 / 块数），
/// 总质量正好等于 Σ 单块密度。
///
/// 对应 space 0.1.3 的 `colliderBuilderSetDensity` / `colliderGetDensity`。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderSetDensity(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    collider_id: jlong,
    density: jdouble,
) -> jboolean {
    if !finite(density) || density < 0.0 {
        return JNI_FALSE;
    }
    let done = with_world(handle, |w| {
        let Some((_, collider_handle)) = w.collider_map.get(&(collider_id as i64)).copied() else {
            return false;
        };
        match w.colliders.get_mut(collider_handle) {
            Some(c) => {
                c.set_density(density);
                true
            }
            None => false,
        }
    });
    ok(done.unwrap_or(false))
}

/// 读取碰撞体密度（kg/m³）；不存在返回 -1。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_colliderGetDensity(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    collider_id: jlong,
) -> jdouble {
    with_world(handle, |w| {
        w.collider_map
            .get(&(collider_id as i64))
            .and_then(|(_, h)| w.colliders.get(*h))
            .map(|c| c.density())
    })
    .flatten()
    .unwrap_or(-1.0)
}

/// 读取刚体质量（kg）。
///
/// 对应 space 0.1.3 的 {@code rigidBodyGetMass}。诊断"为什么一碰就飞"必须先能看到质量：
/// 我们的体素碰撞体密度是 Rapier 默认的 1.0，于是<b>一格方块 = 1 kg</b> ——
/// 比 50 kg 的玩家还轻 50 倍，被人一撞当然飞。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_bodyGetMass(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    body_id: jlong,
) -> jdouble {
    with_world(handle, |w| {
        w.body_map
            .get(&(body_id as i64))
            .and_then(|h| w.bodies.get(*h))
            .map(|b| b.mass())
    })
    .flatten()
    .unwrap_or(-1.0)
}

/// Tier 1 原生自检：在**独立临时世界**里逐项验证 ABI 5 的新能力，返回通过的位掩码。
///
/// bit0 半空间地面 / bit1 力矩 / bit2 运动学位移 / bit3 复合盒平台 / bit4 阻尼 /
/// bit5 材质与组合规则 / bit6 运动学旋转 / bit7 附加质量属性
///
/// 刻意不碰 `WORLDS`：自检不该污染游戏里的世界（也避免自检失败把玩家世界搞坏）。
#[no_mangle]
pub extern "system" fn Java_com_mss_polymech_physics_NativePhysics_tier1Selftest(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let mut bits: jint = 0;

    // ── bit0：地面。在远离平台处落下，应停在 y ≈ 0.5（半高 0.5 贴地面 y=0）。
    {
        let mut w = PhysicsWorld::new(0.0, -9.8, 0.0);
        let floor = ColliderBuilder::new(SharedShape::halfspace(Vector::new(0.0, 1.0, 0.0)))
            .translation(Vector::new(0.0, 0.0, 0.0))
            .build();
        w.colliders.insert(floor);
        let h = w
            .bodies
            .insert(RigidBodyBuilder::dynamic().translation(Vector::new(50.0, 6.0, 0.0)).build());
        let c = ColliderBuilder::cuboid(0.5, 0.5, 0.5).build();
        w.colliders.insert_with_parent(c, h, &mut w.bodies);
        for _ in 0..400 {
            w.step();
        }
        let y = w.bodies.get(h).map(|b| b.translation().y).unwrap_or(f64::NAN);
        if y > 0.4 && y < 0.6 {
            bits |= 1;
        }
    }

    // ── bit3：复合盒平台。板顶面 y = 0.5，落下后中心应停在 1.0。
    //（若复合盒没生效，它会穿过平台落到地面 y=0.5，这个断言就把两者区分开了）
    {
        let mut w = PhysicsWorld::new(0.0, -9.8, 0.0);
        let platform = ColliderBuilder::new(SharedShape::compound(vec![(
            Pose::translation(0.0, 0.25, 0.0),
            SharedShape::cuboid(4.0, 0.25, 4.0),
        )]))
        .build();
        w.colliders.insert(platform);
        let h = w
            .bodies
            .insert(RigidBodyBuilder::dynamic().translation(Vector::new(0.0, 6.0, 0.0)).build());
        let c = ColliderBuilder::cuboid(0.5, 0.5, 0.5).build();
        w.colliders.insert_with_parent(c, h, &mut w.bodies);
        for _ in 0..400 {
            w.step();
        }
        let y = w.bodies.get(h).map(|b| b.translation().y).unwrap_or(f64::NAN);
        if y > 0.9 && y < 1.15 {
            bits |= 8;
        }
    }

    // ── bit1：角冲量。绕 Y 的角冲量应立刻给上角速度。
    {
        let mut w = PhysicsWorld::new(0.0, 0.0, 0.0);
        let h = w
            .bodies
            .insert(RigidBodyBuilder::dynamic().translation(Vector::new(0.0, 0.0, 0.0)).build());
        let c = ColliderBuilder::cuboid(0.5, 0.5, 0.5).build();
        w.colliders.insert_with_parent(c, h, &mut w.bodies);
        if let Some(b) = w.bodies.get_mut(h) {
            b.apply_torque_impulse(Vector::new(0.0, 8.0, 0.0), true);
        }
        w.step();
        let av = w.bodies.get(h).map(|b| b.angvel().y).unwrap_or(0.0);
        if av.abs() > 0.1 {
            bits |= 2;
        }
    }

    // ── bit2 / bit6：运动学（位置型）体的目标位姿在下一次步进生效。
    {
        let mut w = PhysicsWorld::new(0.0, 0.0, 0.0);
        let h = w.bodies.insert(
            RigidBodyBuilder::kinematic_position_based()
                .translation(Vector::new(0.0, 0.0, 0.0))
                .build(),
        );
        let q = Rotation::from_xyzw(
            0.0,
            std::f64::consts::FRAC_1_SQRT_2,
            0.0,
            std::f64::consts::FRAC_1_SQRT_2,
        )
        .normalize();
        if let Some(b) = w.bodies.get_mut(h) {
            b.set_next_kinematic_translation(Vector::new(3.0, 0.0, 0.0));
            b.set_next_kinematic_rotation(q);
        }
        w.step();
        if let Some(b) = w.bodies.get(h) {
            if (b.translation().x - 3.0).abs() < 0.01 {
                bits |= 4;
            }
            let r = *b.rotation();
            if (r.y - q.y).abs() < 1e-6 && (r.w - q.w).abs() < 1e-6 {
                bits |= 64;
            }
        }
    }

    // ── bit4：线阻尼。初速 10、阻尼 2/s，0.5 秒后必须明显掉下来。
    {
        let mut w = PhysicsWorld::new(0.0, 0.0, 0.0);
        let h = w.bodies.insert(
            RigidBodyBuilder::dynamic()
                .translation(Vector::new(0.0, 0.0, 0.0))
                .linvel(Vector::new(10.0, 0.0, 0.0))
                .build(),
        );
        let c = ColliderBuilder::cuboid(0.5, 0.5, 0.5).build();
        w.colliders.insert_with_parent(c, h, &mut w.bodies);
        if let Some(b) = w.bodies.get_mut(h) {
            b.set_linear_damping(2.0);
        }
        for _ in 0..50 {
            w.step();
        }
        let vx = w.bodies.get(h).map(|b| b.linvel().x).unwrap_or(10.0);
        if vx < 6.0 {
            bits |= 16;
        }
    }

    // ── bit5：材质与组合规则。写进去再读回来必须一致。
    {
        let mut w = PhysicsWorld::new(0.0, 0.0, 0.0);
        let h = w
            .bodies
            .insert(RigidBodyBuilder::fixed().translation(Vector::new(0.0, 0.0, 0.0)).build());
        let c = ColliderBuilder::cuboid(0.5, 0.5, 0.5).build();
        let ch = w.colliders.insert_with_parent(c, h, &mut w.bodies);
        if let Some(collider) = w.colliders.get_mut(ch) {
            collider.set_friction(20.0);
            collider.set_restitution(0.5);
            collider.set_contact_skin(0.02);
            collider.set_friction_combine_rule(combine_rule(1));
            collider.set_restitution_combine_rule(combine_rule(3));
        }
        if let Some(collider) = w.colliders.get(ch) {
            if (collider.friction() - 20.0).abs() < 1e-9
                && (collider.restitution() - 0.5).abs() < 1e-9
                && (collider.contact_skin() - 0.02).abs() < 1e-9
                && collider.friction_combine_rule() == CoefficientCombineRule::Min
                && collider.restitution_combine_rule() == CoefficientCombineRule::Max
            {
                bits |= 32;
            }
        }
    }

    // ── bit7：附加质量属性。无碰撞体的刚体设了 7kg 就该是 7kg。
    {
        let mut w = PhysicsWorld::new(0.0, 0.0, 0.0);
        let h = w
            .bodies
            .insert(RigidBodyBuilder::dynamic().translation(Vector::new(0.0, 0.0, 0.0)).build());
        if let Some(b) = w.bodies.get_mut(h) {
            b.set_additional_mass_properties(
                MassProperties::new(
                    Vector::new(0.0, 1.0, 0.0),
                    7.0,
                    Vector::new(2.0, 2.0, 2.0),
                ),
                true,
            );
        }
        // 质量是在 pipeline 步进时由"碰撞体贡献 + 附加质量属性"合成的，
        // 设完立刻读还是旧值（这个体没有碰撞体 → 读出来是 0），必须先走一步。
        w.step();
        let m = w.bodies.get(h).map(|b| b.mass()).unwrap_or(0.0);
        if (m - 7.0).abs() < 1e-6 {
            bits |= 128;
        }
    }

    bits
}
