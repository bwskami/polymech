# Poly Mech 物理原生层（Rust + Rapier）

Java 侧 `com.mss.polymech.physics` 通过 JNI 调用本目录的 Rust crate 完成刚体仿真。

## 为什么用 Rust + Rapier

- **旋转刚体**：原版 Minecraft 只有网格对齐 AABB，无法表达飞船的任意旋转/翻滚/接舷；
- **f64 精度**：太空维度坐标可达 ±20 亿格，f32 在 1e11 处的分辨率约 1 万米，完全不可用；
- **零 GC 抖动**：物理跑独立 100Hz 定步长，且体素/建筑快照体量巨大，不适合进 Java 堆；
- **成熟引擎**：Java 生态没有等价的现代 3D 求解器（JBullet 停更且仅 f32、dyn4j 仅 2D）。

## 许可证（重要）

| 组件 | 协议 | 说明 |
|---|---|---|
| `rapier3d-f64` / `parry3d-f64` / `nalgebra` / `glam` / `jni` | Apache-2.0 / MIT | 开源依赖，可商用；分发时需保留版权与许可声明 |
| 本 crate（`polymech_physics`） | 本项目所有 | 由 Poly Mech 自行编写 |

> 本项目**不包含**任何第三方闭源产物。特别地，未使用、未反编译、未再分发 space 模组的 `mps_rigid_body.dll`
> 或 `org.polymech2023.mps` 相关代码（该模组为 All Rights Reserved）。

## 构建

### Windows（MinGW-w64，免管理员）

```bash
# 1) 安装 Rust（若未安装）
curl -LO https://static.rust-lang.org/rustup/dist/x86_64-pc-windows-msvc/rustup-init.exe
./rustup-init.exe -y --profile minimal --no-modify-path
rustup toolchain install stable-x86_64-pc-windows-gnu --profile minimal

# 2) 准备 MinGW-w64（示例：从 MSYS2 镜像解包到 ~/.polymech-toolchain/mingw64）
#    需要包：gcc, gcc-libs, binutils, crt, headers, libwinpthread,
#            windows-default-manifest, gmp, mpfr, mpc, isl, zlib, zstd, libiconv, gettext-runtime

# 3) 编译并复制到资源目录
./gradlew copyNativePhysics
```

可用环境变量覆盖工具链位置：

- `POLYMECH_MINGW`：MinGW 前缀目录（含 `bin/x86_64-w64-mingw32-gcc.exe`）
- `JAVA_HOME`：用于向链接器提供 `jvm.lib`（JNI 需要）

### Linux / macOS

```bash
rustup target add x86_64-unknown-linux-gnu      # 或 aarch64-apple-darwin
cd native/polymech-physics && cargo build --release
# 产物复制到 src/main/resources/natives/<linux_amd64|macos_amd64>/libpolymech_physics.so|dylib
```

## ABI 约定

**判"低于最低要求"，不判"严格相等"**：`PhysicsNatives.EXPECTED_ABI` 是 Java 侧要求的**最低**
Rust `ABI_VERSION`。

- dll 低于最低要求 → 拒绝启用物理层（日志明确写出，不崩游戏）；
- dll 高于最低要求 → 放行（原生层按约定**只做加法**，向后兼容）。

这样"Java 先加了新函数、dll 还没重编"这段窗口里，物理层仍能照常工作。
新增函数用独立的 **`MIN_ABI_*`** 常量逐个把关，调用前先查，例如：

```java
if (PhysicsNatives.hasCollisionGroups()) {   // 需要 ABI >= MIN_ABI_COLLISION_GROUPS(4)
    NativePhysics.colliderAttachCuboidGrouped(...);
}
if (PhysicsNatives.hasTier1()) {             // 需要 ABI >= MIN_ABI_TIER1(5)
    NativePhysics.worldSetFloor(...);
}
```

导出符号命名规则：`Java_com_mss_polymech_physics_NativePhysics_<方法名>`。

## 碰撞组（ABI 4，已可用）

Rust 侧与 Java 侧都已就位。用 `PhysicsNatives.hasCollisionGroups()` 判断是否可用。

新增的两个导出：

| 函数 | 说明 |
|---|---|
| `colliderAttachCuboidGrouped(world, body, hx, hy, hz, friction, restitution, membership, filter)` | 挂盒碰撞体并指定碰撞组 |
| `colliderAttachVoxelsGrouped(world, body, csx, csy, csz, cells, friction, restitution, membership, filter)` | 挂体素碰撞体并指定碰撞组 |

**Rapier 的交互判定是双向的**：A 与 B 交互 ⟺ `(A.membership & B.filter) != 0`
且 `(B.membership & A.filter) != 0` —— 只改一边不生效，两边都要设。

## Tier 1（ABI 5，已可用）

对标 space 0.1.3 补进来的一批能力。当前 dll 为 **ABI 5**，用
`PhysicsNatives.hasTier1()` / `MIN_ABI_TIER1` 判断是否可用。

| 函数 | 对标 space | 说明 |
|---|---|---|
| `worldSetFloor(world, y, enabled)` | `PhysicalWorld.setMinY` | 维度无限地面（法线朝上的半空间） |
| `colliderAttachBoxes(world, body, boxes, friction, restitution, membership, filter)` | `ColliderBody.Type.COMPLEX_VOXEL` | 任意盒复合碰撞体（台阶/楼梯/栅栏/墙…） |
| `colliderSetMaterial(world, collider, friction, restitution, skin, fricRule, restRule)` | `ColliderBody.setFrictionCombineRule` + `CONTACT_SKIN` | 材质与组合规则（contact skin 消抖） |
| `bodyAddTorque` / `bodyResetTorque` / `bodyApplyTorqueImpulse` | `rigidBodyAddTorque` 等 | 力矩与角冲量 |
| `bodyAddForceAtPoint(world, body, fx,fy,fz, px,py,pz)` | `rigidBodyAddForceAtPoint` | 偏心受力（推进器让船自转） |
| `bodySetDamping` / `bodySetGravityScale` / `bodySetAdditionalMassProperties` | `RigidBody` 构造器参数 | 阻尼 / 重力缩放 / 质心+质量+惯量 |
| `bodyEnableCcd` / `bodySetEnabledRotations` | `rigidBodyEnableCcd` / `SetEnabledRotations` | CCD / 逐轴旋转锁 |
| `bodySetNextKinematicTranslation` / `bodySetNextKinematicRotation` | `rigidBodySetNextKinematicPosition` | 运动学体目标位姿（电梯/移动平台） |
| `tier1Selftest()` | — | 原生自检，返回通过位掩码（bit0..bit7） |

验证方式（两选一）：

```bash
./gradlew copyNativePhysics
java -cp <classes> NativeSmokeTest <dll 绝对路径>   # 见 native/jni-smoketest/
```

游戏内：`/polymech physics tier1test`（逐项打印 ✔/✘）。

## 加载与降级

`PhysicsNatives` 会依次尝试：

1. `System.loadLibrary("polymech_physics")`（开发环境 / `-Djava.library.path`）；
2. 从 mod 资源 `/natives/<os>_<arch>/` 解压到临时目录并加载。

任何一步失败都只会让物理功能不可用（日志警告），**不会导致游戏崩溃**。

自检命令：`/polymech physics status`、`/polymech physics selftest`、`/polymech physics tier1test`
