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
/// Java 侧 `PhysicsNatives.EXPECTED_ABI` 必须同步改成 4，否则加载器会拒绝启用物理层。
pub const ABI_VERSION: jint = 4;

/// 世界内刚体数量上限（防御非法输入）。
const MAX_BODIES_PER_WORLD: usize = 65_536;

/// 单个体素碰撞体的最大体素数（对应 space 模组的 128^3 = 2,097,152 上限）
const MAX_VOXELS_PER_COLLIDER: usize = 2_097_152;

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
    next_id: i64,
}

impl PhysicsWorld {
    fn new(gx: f64, gy: f64, gz: f64) -> Self {
        let mut params = IntegrationParameters::default();
        params.dt = 1.0 / 100.0;
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

static WORLDS: OnceLock<Mutex<HashMap<i64, PhysicsWorld>>> = OnceLock::new();
static NEXT_WORLD_ID: OnceLock<Mutex<i64>> = OnceLock::new();

fn worlds() -> &'static Mutex<HashMap<i64, PhysicsWorld>> {
    WORLDS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn with_world<R>(handle: jlong, f: impl FnOnce(&mut PhysicsWorld) -> R) -> Option<R> {
    if handle <= 0 {
        return None;
    }
    let mut guard = worlds().lock().ok()?;
    guard.get_mut(&(handle as i64)).map(f)
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
            map.insert(id, PhysicsWorld::new(gx, gy, gz));
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
