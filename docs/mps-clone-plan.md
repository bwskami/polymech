# MPS 高仿层（clean-room）实施方案

> 目标：让 space 的**上层**（`physical.physical_body` / `physical.entity` / `physical.helper` /
> `kelvin.physical`）能按原样接入一套与 `org.polaris2023.mps` **公开 API 同形**的实现，
> 行为对标 space 0.1.3，但代码由本项目自行编写。

## 一、许可红线（不可绕过）

`native/README.md` 已写明本项目的立场：

> 未使用、未反编译、未再分发 space 模组的 `mps_rigid_body.dll` 或 `org.polymech2023.mps`
> 相关代码（该模组为 **All Rights Reserved**）。

因此：

- **不逐字复制** `decompiled-space/0.1.3` 下的任何类（MPS、kelvin、sunshine、space 本体都算）；
  `decompiled-space/` 只作**行为对照**，不进版本库（`.gitignore` 已如此）。
- **不再分发** `mps_rigid_body.dll`。
- 高仿层的实现一律落在 `com.mss.polymech.mps`（本项目自有包），后端用我们自己的
  `polymech_physics` 原生库；只有在"我们确实没有的能力"上才新增原生函数。

## 二、现状数据（对照基准）

| 包 | 文件 | 行数 | native 声明 |
| --- | --- | --- | --- |
| `org.polaris2023.mps`（MPS 物理） | 51 | 7,300 | 127 |
| `org.cn_grass_block.kelvin` | 24 | 2,294 | 13（cosmos/N-body） |
| `org.cn_grass_block.sunshine` | 50 | 3,488 | 0 |
| `org.deep_space_studio.space` | 191 | 14,861 | 0 |
| **合计** | **316** | **~27,900** | **140** |
| 我们现有（`com.mss.polymech.physics` + `NativePhysics`） | 27 | 6,466 | 52 |

## 三、关键架构结论：分离对象模型可以在 Java 侧等价仿真

MPS 的原生层是"**分离 builder + 按 memory handle 插入世界**"：

```
rigidBodyBuilderCreate/Set*/Build  → 内存句柄（不属于任何世界）
worldInsertRigidBody(world, memHandle)   ← 真正进世界
worldCopyRigidBody / worldCopyCollider   ← 跨维度搬运
RustMemoryFree(handle)                    ← 显式释放（配 Unsafe/Cleaner）
```

我们的原生层是"**世界内 id**"（`bodyCreate(world,...)` → id），没有分离对象arena。

**改法（本项目做法，零新增原生）**：把"分离"放在 Java 对象里——

- `new RigidBody(...)` / `new ColliderBody(...)`：只把参数（类型、位姿、质量、形状、材质、碰撞组）
  存进字段，**不碰原生**；
- `RapierWorld.addRigidBody(rb)` / `addColliderBody(cb, rb)`：此刻才落到
  `NativePhysics.bodyCreate` / `colliderAttach*`，把返回的 id 记回对象；
- `worldCopyRigidBody/Collider` + 异地 insert = 读旧体的位姿/速度/材质参数，在新世界重建；
- `RustMemoryFree` ≈ Java 的 `destroy()`（对象被 GC 或显式销毁时调我们的 `bodyDestroy`）。

这样 MPS 那 127 个 native 里，**约 90 个直接消失**（builder 系列、内存管理、copy/bulk setter）。

## 四、127 个 native → 处理方式

| 分组 | 数量（约） | 处理 |
| --- | --- | --- |
| builder 系列（`rigidBodyBuilder*` 16、`colliderBuilder*` 25、`jointBuilder*` 7） | 48 | **Java 侧等价仿真**（存参数），不需要原生 |
| 内存管理（`RustMemoryFree`、`abiSupportsFfm/Jni`） | 3 | Java `destroy()` / 常量 |
| world 管理（create/destroy/step/set-get gravity/set size/snapshot/insert/copy/remove） | 16 | 多数用现有原生（`worldCreate/Step/SetGravity/SetTimestep` + `bodyCreate/Destroy`）；`worldGetGravity/SetSize` 补轻量原生 |
| rigid body 读写（`rigidBodyGet*/Set*/Add*/Apply*/EnableCcd/SetSleep`） | 33 | 现有原生已覆盖绝大部分（`bodyRead*/bodySet*/bodyAdd*/bodyApply*`），缺 `rigidBodySetStatus`(有 `bodySetBodyType`)、`SetPose`、`CanSleep`、`UserData` |
| collider 读写（`colliderGet*/Set*`） | 27 | 现有覆盖材质/密度/组；缺 `colliderSetPose/Sensor/SolverGroups/ActiveEvents/Hooks/ContactForceThreshold` |
| 形状（heightmap / obb / convex hull / skewed·discrete obb / edge bvh / medial spheres / double bv / point cloud / fused collapsing bounds / compound boxes） | 10 | `cuboid/sphere/voxels/halfspace/compound-boxes` 用现有；其余**冷门**，按需再补 |
| 查询（`queryCastRay` / `queryCastShape`） | 2 | 射线有（`worldCastRay`）；shapecast 需要时补 |
| 碰撞事件（`worldClearEvents` / `Count` / `Get*`） | 4 | 需要时补（space 的撞击/接触伤害要用） |
| 关节（`worldInsert/RemoveImpulseJoint`） | 2 | 需要时补（起落架/铰链） |

## 五、实施阶段

| 阶段 | 内容 | 文件（新建） | 验收 |
| --- | --- | --- | --- |
| **S1 契约层** | `rapier.helper`：`RigidBody` / `ColliderBody` / `RapierWorld`（含分离对象语义），后端 `NativePhysics` | `com/mss/polymech/mps/rapier/helper/*` | 编译通过 + 冒烟测试能建体/步进/读回 |
| **S2 世界与体** | `physical.physical_world`（`PhysicalWorld`/`Client`/`Server`）、`physical.physical_body`（`PhysicalBody`/`Client`/`Server`） | 6 类 | 客户端+服务端各能创建/同步一体 |
| **S3 玩家与地形** | `physical.entity.PhysicalEntity`（双刚体）→ 复用到现有 `PlayerPhysicsBody`；`physical.helper`（`PhysicalChunk`/`PalettedChunk`） | 4 类 | 玩家在 space 世界里由 S1/S2 驱动，手感与现网一致 |
| **S4 力与天体** | `kelvin.physical.CelestialBodyForce` + N-body 引力 + `PhysicalBodySpaceEvent` 语义 | 4~6 类 | 天体引力作用到物理体，`/polymech physics` 可观测 |
| **S5 其余能力** | 关节 / 传感器事件 / shapecast / 冷门形状；`ProjectionManager` / 选择棒 | 按需 | 各自单测 |
| **S6 拆旧** | `com.mss.polymech.physics` 退役，polymech 全部走 `com.mss.polymech.mps` | — | 全量回归 |

> S1–S3 是"把已有力学换壳到同形 API"，**不改变手感**；S4 起才引入我们目前没有的能力。

## 六、ABI 策略

新原生一律**追加**，不动已有 52 个的语义：

- `ABI_VERSION` 递增（6 …），Java 侧用 `MIN_ABI_*` 逐个把关，缺函数就降级；
- 保证"Java 已更新、dll 未重编"时物理层仍可用（`native/README.md` 的既有约定）。

构建（工具链已在本机，只是不在 PATH）：

```powershell
$env:PATH = "$env:USERPROFILE\.cargo\bin;" + $env:PATH
$env:POLYMECH_MINGW = "$env:USERPROFILE\.polymech-toolchain\mingw64"
./gradlew copyNativePhysics
```

---

## 七、进度

| 阶段 | 状态 | 落地文件 |
| --- | --- | --- |
| S1 契约层 | ✅ 编译通过 | `mps/rapier/helper/{RapierWorld,RigidBody,ColliderBody}.java` |
| S2 块容器 / 地形块 | ✅ 编译通过 | `mps/physical/helper/{PalettedChunk,PhysicalChunk,IntAABB,DoubleAABB,IntVec3}.java` |
| S2 世界与体（基类） | ✅ 编译通过 | `mps/physical/physical_world/PhysicalWorld.java`、`mps/physical/physical_body/PhysicalBody.java` |
| S2 世界（客户端） | ✅ 编译通过 | `mps/physical/physical_world/ClientPhysicalWorld.java`（区块事件驱动 + 每子步驱动玩家速度链） |
| S2 地形区块管理（服务端） | ✅ 编译通过 | `mps/physical/manger/PhysicalChunkManager.java`（两级区块表 + 关注点 ±8 + 距离迟滞 + 依赖未加载不建；用本项目太空维度判定替代 kelvin） |
| S2 世界（服务端） | ⏸ 被投影卡住 | `ServerPhysicalWorld` 需 `ServerPhysicalBody`，而后者每个构造函数都调 `ProjectionManager.createNewProjection` → **先做投影**（见第十节） |
| S2 体（客户端） | ✅ 核心编译通过（渲染待 S5） | `mps/physical/physical_body/ClientPhysicalBody.java`：运动学镜像 + `onMoveSync`/`tickMovePos` 0.2/子步插值 + listener 生命周期；<b>渲染部分</b>（每 64³ 子块一份 VertexBuffer、视锥剔除、需 space 的 `SpaceModVertexFormats`）留到渲染轮次 |
| S2 体（服务端） | ⏸ 被投影卡住 | `ServerPhysicalBody` 每个构造函数都调 `ProjectionManager.createNewProjection`（见第十节/十一节） |
| S3 玩家双刚体 | ✅ 编译通过 | `mps/physical/entity/PhysicalEntity.java`（两体 25kg、gravity_scale 0、锁旋转、组 (2,5)/(5,5)、friction 20、`afterStep` 速度硬写） |
| S2 网络（方块增量） | ✅ 编译通过 + 已注册 | `mps/network/packet/SyncPhysicalBodyBlockUpdate.java`（64³ 整块替换 + RLE + 尺寸哨兵；命名空间用本项目 `poly_mech`） |
| S2 网络（移除 / 运动批量） | ✅ 编译通过 + 已注册 | `mps/network/packet/SyncPhysicalBodyRemove.java`（带维度过滤，避免跨维度误删）、`SyncPhysicalBodyMoveBatch.java`（每 tick 一包，位姿+线速度+角速度；`onMoveSync` 是镜像体插值入口，不能改 setPos） |
| S2 网络（体创建 / 方块实体） | ⬜ 待做 | `SyncPhysicalBodyCreate` 依赖 `ClientPhysicalBody` + 可靠创建握手（本项目已有 `SyncPhysicsBodyAckPacket` + `PENDING_ACKS` 可对接）；`SyncPhysicalBodyBlockEntity` 对应现网 `PhysicsBodyBlockEntityPacket` |
| 克隆层离线回归 | ✅ CLONE SMOKE PASSED | `native/jni-smoketest/CloneSmokeTest.java`（分离语义 / up() flush 时序 / 碰撞组交互 / 摘出插回 / 重力） |
| ABI 6 / 7 原生 | ✅ 已重编 + 符号校验 | `colliderSet*` 实时属性、`bodySetPose`、`worldGetGravity/尺寸`、`worldRemoveCollider/RigidBody` |
| ABI 8 分离对象 | ✅ 已重编 + 回归通过 | `worldCopy/InsertRigidBody/Collider`、`RustMemoryFree`（跨维度搬运的前提） |
| **S4 cosmos（kelvin 天体引力）** | ✅ 已实现 + 行为回归通过 | `cosmosWorldCreate/Destroy/Step`、`Fixed/SatelliteBuilder`、`InsertBodyAsGravitySource`、`TranslationOut/LinvelOut`；契约由 kelvin 调用点反推；<b>半隐式欧拉</b>保证轨道能量有界；回归用"地球 + 圆轨道卫星绕一圈"钉住 |
| ⚠️ 契约修正（重要） | ✅ 已修 + 已重编验证 | `cosmosBody*Out` 的返回约定**曾写反**：kelvin 是 `== 0 ? null : 值`（**0 = 失败，非 0 = 成功**），我按"0 = 成功"实现，且回归断言也照错的契约写 —— 测试"通过"却钉错契约。**教训：断言必须由调用方代码推导，不能由自己的实现推导。** |
| S4 kelvin 力模型（Java 侧） | ✅ 编译 + 离线测试通过 | `mps/kelvin/physical/CelestialBodyForce.java`（具名持续力）；`KelvinForceTest` 钉住"同名即同一股力""`POSITIVE_INFINITY` 是永久力"、JSON 往返、`decomposeForce` 沿局部 +Z |
| S4 kelvin 其余（待做） | ⬜ | 体量已量：`CelestialBody` 280 / `SpaceWorld` 282 / `Planet` 346 / `Meteoroid` 96 / `Star` 59 / `Aircraft` 32 / `CelestialWorld` 119 / `ServerCelestialWorld` 53 / `ClientCelestialWorld` 31 / `ServerSpaceWorld` 49 / `ClientSpaceWorld` 46 / `BlackHole` 9 |
| **S4 kelvin 核心（天体 + 天体世界）** | ✅ 一起编译通过 | `mps/kelvin/physical/celestial_body/CelestialBody.java` + `space_world/SpaceWorld.java`；<b>两条积分路径</b>（原生 cosmos 优先 / 纯 Java 兜底）、自转恒在 Java、操作队列（1024/批）、读回缓冲改 `double[3]`（无 Unsafe）、引力在 Java 算 |
| S4 kelvin 子类与世界容器 | 🚧 部分完成 | ✅ `Star`（色温→黑体 RGB）/ `Aircraft`（包装物理体的适配器）/ `BlackHole`；⏸ `Meteoroid` **被卡住**：依赖 `Planet`（大气密度/高度/温度）、`ServerSpaceWorld.getSpaceWorld`、`OrbitPhysicalThread.core_tick_time`、以及客户端 `Minecraft...getDepthFar()` |
| S4 kelvin 正确剩余顺序（已量依赖） | ⬜ | `Planet` 346 → `Meteoroid` 96 → `CelestialWorld` 119（**行星重力 `G/122.5` 的落点**）→ `Server/ClientCelestialWorld` 53/31 → `Server/ClientSpaceWorld` 49/46 → `OrbitPhysicalThread` |
| S3 玩家与地形管理 | ⬜ | `physical/entity/PhysicalEntity`、`PhysicalChunkManager` |
| S4 力与天体 | ⬜ | `kelvin.physical.*`（引力 / N-body / 力下发 / 物理体↔天体绑定） |
| S5 其余能力 | ⬜ | 关节 / 传感器事件 / shapecast / HEIGHTMAP 与冷门形状 |
| S6 拆旧接线 | ⬜ | 退役 `com.mss.polymech.physics`，polymech 全量走 `com.mss.polymech.mps` |

### 已落地部分的已知偏差（有意为之，绝不静默失真）

| 项 | MPS | 我们 | 原因 |
| --- | --- | --- | --- |
| 整数包围盒 | `org.joml.primitives.AABBi` | `physical.helper.IntAABB` | MC 1.21.1 只随游戏带 joml **核心**，不含 `primitives` 包 |
| joml 可用类（**已实测**） | 直接用 joml | `Vector2i`/`Vector3i` 用 joml；只有 `primitives.*` 用自有替代 | `joml-1.10.5.jar` 共 123 条目：`Vector3d`/`Vector2i`/`Vector3i`/`Quaterniond`/`Matrix4f` **都在**，`primitives/AABBi`、`primitives/AABBd` **不在**。曾误判 `Vector2i`/`Vector3i` 也缺失而自写 `IntVec2`/`IntVec3` —— 已删除并换回 joml，顺带改回 MPS 的写法（`Vector2i.y` 存的是**世界的 z**） |
| 调色板打包 | 16 位内打包，超限退化成 `int[]` | 直接 `int[262144]`（1 MB/块） | 语义完全一致，内存与其 direct 分支同量级；实现简单、不易错 |
| ~~`setCollisionGroups` 时机~~ | 原生可随时改 | **已对齐**：ABI 6 的 `colliderSetCollisionGroups` 已补，Java 侧随时可改 | 消除了一处"迁就缺口"的等价仿真 |
| 体素索引序 | `x + z*sx + y*sx*sz`（x 最快） | 同左（`ColliderBody.VOXEL` 约定已统一） | 曾用 `(x*sy+y)*sz+z`，与 `PalettedChunk.colliderArray()` 不一致 → 体素会整体错位；已修，并删掉 `PhysicalChunk` 里多余的布局转换 |
| `getStatus()` / 存档 | `rigidBody.getStatus()` / `saveToTag·fromTag` | 暂未移植（不猜语义） | 与 S6 的 `PhysicsBodySavedData` 一起接 |
| HEIGHTMAP | `getHeightMap` + 高度图碰撞体 | 抛 `UnsupportedOperationException` | 原生没有 HEIGHTMAP 形状（S5） |
| 冷门形状 / 关节 / 碰撞事件 / shapecast | 有 | 占位或抛异常 | **见第九节**：这几项在 space 0.1.3 里自己也没启用，不实现 |

---

## 八、"实际使用面"核对（把 127 个 native 收敛成真正要做的）

判据不是"MPS 有哪些函数"，而是"space 0.1.3 的 MPS+kelvin 到底调了哪些"。2026-09 全量 grep 结论：

**`ColliderBody` 上真正被调用的只有 6 个方法**：

```
setCollisionGroups x5   setFriction x4   setRestitution x2
setRestitutionCombineRule x2   setFrictionCombineRule x2   setPos x1
```

→ 克隆层**已全部具备**。反面结论：`setSensor` / `setActiveEvents` /
`setContactForceEventThreshold` **一次都没被调用** —— 我 ABI 6 把这三个也补了，
那属于"能力"而非"对齐"，**S5 的优先级应当下调**（别为了没人用的能力先啃事件系统）。

**`RigidBody` 上真正被调用的**（去重）：`getPos / isAttached / getLinvel / getMass /
setLinvel / getRotation / setPos / setCCD / setRotation / setAngvel / setStatus / up /
getAngvel / setNextKinematicPositionDirect / setLinSpeed / applyImpulse / applyForce`。

→ 唯一缺口只是**命名**：我们叫 `enableCcd`，MPS 叫 `setCCD` —— 已改名对齐。
（名字也是对齐的一部分：否则"语义一样名字不同"又要多一层适配，那就是新的殊途同归。）

**`getStatus()` 不移植**，有证据：`RigidBody.getStatus()`（返回 `rigidBodyGetStatus(...) == 1`）
在 space 0.1.3 里的唯一调用者是 `PhysicalBody.getStatus()`，而**全树没有任何地方调用
`PhysicalBody.getStatus()`** —— 它在 space 自己那边就是死代码。其原生语义（`== 1` 到底是
Dynamic 还是 Fixed）无从确认，因此不猜、不实现、不添 API。

**下一步的真正重点**（按"上层真的会调"排序）：
`ServerPhysicalWorld` + `PhysicalChunkManager` → 跨维度搬运需要的 `worldCopyRigidBody/Collider`
→ `ClientPhysicalBody/ServerPhysicalBody`（渲染 + 增量同步）。

## 九、space 自身也没启用的 API（从优先级里划掉，别再啃）

判据同上：不看"MPS 声明了什么"，只看"space 0.1.3 有没有调用点"。以下六项**在 space 自己那边就是
未被使用的代码**，因此克隆层不需要实现（API 同名方法可保留，以便 space 的类按原样编译，
但实现优先级排到最后）：

| 项 | 证据（全量 grep） | 处理 |
| --- | --- | --- |
| 关节 `JointBody` | 40 行命中**全部在 `JointBody.java` 内部**（它声明并使用自己的原生），外面零调用 | 不实现；不补 `jointBuilder*` 原生 |
| HEIGHTMAP | `getHeightMap` / `buildHeightmapColliderBody` 只有**定义、无调用者**；`PhysicalChunkManager` 只调 `getHeightmapColliderBody()` 去**移除**（永远为 null） | 保持 `UnsupportedOperationException` 占位（永远不会被触发） |
| 碰撞事件 | 只有 `RapierWorld.getCollisionEvents()` 定义 + 其原生声明，**无外部调用者** | 不实现；不补事件原生 |
| 传感器 | `setSensor` 4 行 / 3 文件，全是声明与包装 | 不实现 |
| shapecast | `queryCastShape` 3 行 / 2 文件（声明层） | 不实现 |
| `PhysicalBody.scale` | 全树 `.scale.set(` 只出现在 `ScreenBlockEntity` 的**文本/贴图/窗口显示元素**缩放，**不是**物理体 | 恒为 1.0，克隆层默认 1.0 即等价 |

**反面（确实被大量使用、必须做）**：`PhysicalChunkManager`（86 行 / 11 文件涉及投影与区块管理）、
投影 `ProjectionManager`（86 行 / 11 文件）、选择棒 `PhysicalSelection`（56 行 / 11 文件）。

> 结论：S5 里真正要做的是**投影与选择棒**，不是关节/事件/传感器。这条结论是量出来的，
> 下一轮不要再去读 `JointBody` 或事件系统。

## 十、依赖顺序纠正：投影不是"S5 可选件"，而是服务端体层的前置

原计划把服务端世界/体层排在投影（`ProjectionManager`）之前。**实测后必须纠正**：

`ServerPhysicalBody`（117 行，已通读）**五个构造函数每一个都调用**
`ProjectionManager.createNewProjection(this[, slot])`，`loadFromTag` 还要
`ProjectionManager.readProjectionData(body)`；而 `ServerPhysicalWorld.addPhysicalBody`
里有 `instanceof ServerPhysicalBody` 分支去发方块增量包。也就是说：

```
ServerPhysicalWorld / ServerPhysicalBody
        ├── 硬依赖 ProjectionManager（1032 行）+ 投影相关包
        ├── 硬依赖 SyncPhysicalBodyCreate / SyncPhysicalBodyBlockUpdate
        └── 硬依赖 kelvin 的 ServerSpaceWorld.isSpaceWorld（loadFromTag 里判断是否锁体）
```

**所以正确的下一步是 `ProjectionManager`（含它依赖的包），不是 `ServerPhysicalWorld`。**
单类体量参考（已量）：

| 类 | 行数 | 说明 |
| --- | --- | --- |
| `ProjectionManager` | 1032 | 投影维度 + 地皮槽位 + 交互入口；服务端体层的前置 |
| `ClientPhysicalBody` | 493 | 客户端镜像体的渲染（按 64³ 子块各一份顶点缓冲） |
| `PhysicalChunkManager` | 274 | 服务端地形兴趣点 + 增删迟滞（**不**依赖投影，可先做） |
| `ServerPhysicalBody` | 117 | 依赖投影，暂不能单独落地 |
| `SyncPhysicalBodyBlockUpdate` | 107 | 方块增量同步包 |
| `PhysicalBodyWorldData` | 45 | 存档（依赖 `ServerPhysicalBody.loadFromTag`） |

**可立即做且不被投影卡住的两块**：
① `PhysicalChunkManager`（274 行，只依赖 `PhysicalChunk` + `PhysicalWorld`，已具备）；
② `ClientPhysicalBody` 的核心（运动学镜像 + `onMoveSync`，渲染部分可后置）。

> 教训同类：**先量依赖再定顺序**。这次若直接开 `ServerPhysicalWorld`，会在写完
> `addPhysicalBody` 的 `instanceof ServerPhysicalBody` 分支时才发现脚下是 1032 行的投影系统。

## 十一、`ProjectionManager` 架构分析（动手前的施工图）

已通读结构（常量/字段/内部类/全部方法签名）。它不是"一个管理器"，而是**四套职责**叠在一起，
每一套都有明确的存在理由 —— 先把这些理由说清，再逐块实现，否则抄出来的是形不是神。

### 为什么需要"投影世界"（整个系统的立足点）

物理体的方块**不能存在于真实世界**（否则双重碰撞、原版交互乱套），但**又必须让原版逻辑照常跑**：
箱子要能开、红石要能亮、破坏要走原版进度与掉落、放置要走原版 `canSurvive/canPlace`。
两条要求同时满足只有一条路：把这些方块**镜像进一个专用维度**（`mps:projection_world`），
在那边它们就是**真的方块 + 真的方块实体** —— 于是所有原版代码路径**一行都不用改**。

> 这正是我们 `PhysicsBodyInteraction` 注释里写的同一件事（"全部在投影维度里跑原版逻辑"）。
> 也就是说：**本项目已经有一套自己的投影系统**，克隆这套是"为了克隆自洽"，
> S6 切换时必须二选一 —— 不能两套同时跑。

### 四套职责（按依赖顺序）

| # | 职责 | 方法 | 为什么是这个形态 |
| --- | --- | --- | --- |
| 1 | **地皮槽位分配** | `init` / `createNewProjection(body[,slot])` / `getSlot` / `getPhysicalBody(slot\|pos)` / `getProjection(slot)` / `Projection` / `ProjectionSlotData` | 多个物理体同时存在，投影世界里必须各有**互不重叠的区域**。`Projection(start,end)` 就是那块地；`nextSlot` 是分配器；`ProjectionSlotData extends SavedData` 把分配器**存档**，否则重启后新体会覆盖旧体的地皮 |
| 2 | **真实世界 → 投影 的镜像** | `copyBlock`(136) / `writeProjectionBlocks` / `readProjectionData` / `sourceChunk` | 把方块与**方块实体**（箱子内容、机器状态）一起搬过去。`readProjectionData` 单独存在，是因为存档恢复时方块实体不在方块里、必须另外重建 |
| 3 | **投影 → 真实世界 的回收** | `removeBlock`(116) / `removePhysicalBody` / `removeEmptyPhysicalBody` / `isProjectionAllAir` | 拆体/破坏时把镜像撤掉并**结算掉落物、经验、音效**（原版 `dropResources` 那条链只在投影里能跑通） |
| 4 | **交互转发（服务端权威）** | `handleInteraction`(101) / `destroyBlock`(86) / `useBlockOnProjection` / `applyLocalRotation` / `getPlacementSoundCandidate` / `playBlockSound` / `attackThrottle` / `breakProgress` | 客户端碰不到投影维度，只能发"（体id，动作，手）"。服务端在投影里**重放原版** use/destroy，再用 `physicalBlockCenter` 把结果映回物理体所在世界（掉落物/粒子/音效要在玩家看得见的地方）。`breakProgress`/`attackThrottle` 是服务端自己算挖掘进度与节流（原版链路依赖"射线命中真方块"，物理体那里是空气） |
| 5 | **坐标与刷新工具** | `projectionOrigin` / `toLocal` / `containsProjectionPosition` / `physicalBlockCenter` / `levelOf` / `min` / `max` / `dirtyChunks` / `onBlockUpload` / `tick` / `uploadAllChunks` / `uploadChunk` / `packDirtyKey` | 两套坐标（世界 ↔ 地皮局部）必须集中转换，散开必错；方块写入**攒脏 + tick 批量刷新**，因为刷新区块很贵，红石/活塞一 tick 可能改上百格 |

### 关键常量（语义即设计）

- `PROJECTION_WORLD = mps:projection_world` —— 专用维度，真实世界保持干净；
- `INTERACTION_ATTACK=0 / ATTACK_STOP=1 / USE=2` —— 客户端→服务端的动作三态。
  **必须有 `ATTACK_STOP`**：松手时原版没有对应包，服务端得靠它主动清掉裂纹与挖掘进度；
- `CREATIVE_DESTROY_DELAY = 5` —— 与原版 `destroyDelay` 对齐（防止创造模式连点瞬拆）。

### 实现顺序（按上面 5 块，每块可独立验）

1. 槽位分配 + `Projection`/`ProjectionSlotData`（可单测：分配不重叠、存档往返）；
2. 坐标工具 + `dirtyChunks`/`tick` 刷新管线（可单测：世界↔局部往返一致）；
3. `copyBlock` / `writeProjectionBlocks` / `readProjectionData`（需要真 `ServerLevel`，只能进游戏验）；
4. `removeBlock` / 拆体（含掉落结算）；
5. `handleInteraction` 三态转发 + 挖掘进度/节流。

> 体积分布（实测方法行数）：`copyBlock` 136、`removeBlock` 116、`handleInteraction` 101、
> `destroyBlock` 86，其余多为 10–30 行的小工具。**所以这 1032 行的难点集中在前 4 个方法**，
> 不是均匀工作量——按上面顺序推进即可，不必被总行数吓住。

## 十二、"可靠创建握手"也不必重造（我方已有等价物）

space 的客户端体创建是一条**三件套协议**，不是单个包：

```
SyncPhysicalBodyCreate(id, level, pos, rot, uuid)   // 服务端发，带自增 id
        ↓ 客户端真正存下之后
SyncCreateAck(id)                                    // 客户端回执，按 id 配对
ReliableCreateSender                                 // 服务端：待确认表 + 40 tick 重发 ×5
```

**本项目已经有等价的一整套，而且在跑**：

| space | 本项目 | 说明 |
| --- | --- | --- |
| `SyncCreateAck(int id)` | `SyncPhysicsBodyAckPacket(long bodyId)` | 都是"客户端存下后回执" |
| `ReliableCreateSender`（40 tick ×5 重发） | `PhysicsBodyTracker` 的 `PENDING_ACKS` + `tick()` 重发 | 代码注释里明写"与 space 0.1.3 的 `ReliableCreateSender` 同参数" |
| 旧做法"换维度后延迟 2 秒补发" | 已由上面的握手取代 | 见 `PhysicsBodyEvents` |

**结论与投影、运动批量同类**：克隆层再实现一遍 `Create/Ack/ReliableSender` 只是**重复我们的既有能力**，
S6 切换时**二选一**即可。所以：

- 克隆层的 `SyncPhysicalBodyCreate` **暂不必写**（写了就是第二套握手，两边同时跑只会互相干扰）；
- 真正还缺的是**服务端**那一侧：`ServerPhysicalBody` / `ServerPhysicalWorld`，而它们卡在
  `ProjectionManager`（第十一节有施工图）。

> 一句话总结给下一轮：**别再往"同步/握手/投影"这些我方已有等价物的方向上找活干**。
> 克隆层的剩余价值集中在 **`ProjectionManager` + 服务端体/世界 + kelvin 力与天体** 这三块。

## 十三、回归怎么跑（交接用）

三套离线回归 + 一套诊断探针都在 `native/jni-smoketest/`，**不需要启动游戏**。共同注意：
**输出目录必须先清空** —— 里面残留旧的 `com/mss/polymech/physics/NativePhysics.class`
会形成 classpath 影子类，报 `NoSuchMethodError`，看着像"原生函数没导出"其实是假象。
控制台中文乱码用 `chcp 65001`；退出码非 0 常常只是 PowerShell 把 Java 的
`System.load/loadLibrary` 警告或 SLF4J NOP 提示当成了错误。

> ⚠️ **下面的命令是 PowerShell 语法，`%TEMP%` 是 cmd 的东西，在 PowerShell 里不展开** ——
> 直接把 `%TEMP%\pm-*` 原样粘进去就会"命令无效"。所以输出目录一律用**工作区相对路径**
> `build\pm-*`（顺带也免了"临时目录残留"那个坑）。要在 cmd 里跑，就把 `Remove-Item` 换成
> `rmdir /s /q`、`$dllDir` 换成 `src\main\resources\natives\windows_amd64`。

```powershell
# 在仓库根目录（C:\modIDEA\polymech\polymech-template-1.21.1）执行
$c = "$env:USERPROFILE\.gradle\caches"   # joml / slf4j-api / gson 从这里找
$dllDir = "src\main\resources\natives\windows_amd64"

# ① 编译（改了 Rust 才需要 copyNativePhysics）
./gradlew copyNativePhysics compileJava

# ② 原生 ABI 层（Tier1 + 玩家双刚体 + 实时碰撞组 + 分离对象 + cosmos 轨道）
$o = "build\pm-native"; Remove-Item -Recurse -Force $o -ErrorAction SilentlyContinue
javac -encoding UTF-8 -cp "build\classes\java\main" -d $o native\jni-smoketest\NativeSmokeTest.java
java -cp "$o;build\classes\java\main" NativeSmokeTest $dllDir\polymech_physics.dll

# ③ 克隆层契约（分离语义 / up() flush 时序 / 碰撞组 / 摘出插回）—— 需要 joml + slf4j
java "-Djava.library.path=$dllDir" -cp "build\pm-clone;build\classes\java\main;<joml>;<slf4j>" CloneSmokeTest

# ④ kelvin 力模型（同名替换 / 永久力 / JSON 往返）—— 需要 joml + gson
java -cp "build\pm-kelvin;build\classes\java\main;<joml>;<gson>" KelvinForceTest

# ⑤ 玩家物理离线探针（第 21/22/23 节；只依赖 NativePhysics，同 ② 的 classpath）
#    A–G 单变量场景 / H「vanilla 垂直记账」递推表（必须复现用户日志的 own 序列）
#    / I 太空甲板对照（修复前 vs 修复后）/ J 方向律（落甲板 vs 撞墙）
#    / K 顶住接触时的位置抖动幅度（"抽搐"的物理侧）。不是 pass/fail 回归，跑完打印全表即可
$o = "build\pm-probe"; Remove-Item -Recurse -Force $o -ErrorAction SilentlyContinue
javac -encoding UTF-8 -cp "build\classes\java\main" -d $o native\jni-smoketest\PlayerWallProbeTest.java
java "-Dstdout.encoding=UTF-8" -cp "$o;build\classes\java\main" PlayerWallProbeTest
```

### 看游戏内诊断（`[弹飞诊断]` **不是终端命令**，是日志行）

`玩家物理模式` / `玩家物理诊断` / `玩家双刚体已创建` 都是 `PlayerPhysicsBody` 与 `ClientPhysics`
**自动写进日志**的（前者边沿触发、后者只在 `|v|>15 m/s` 或单子步位移 `>0.2 格` 时打）。
终端里只做一件事：读日志。

```powershell
# 当前会话（run\logs\latest.log）
Select-String -Path run\logs\latest.log -Pattern "玩家物理模式|玩家双刚体|玩家物理诊断" |
  Select-Object -Last 20 | ForEach-Object { $_.Line }

# 归档会话（run\logs\*.log.gz，PowerShell 不能直接 Select-String）
$f = Get-ChildItem run\logs\*.log.gz | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$fs = [System.IO.File]::OpenRead($f.FullName)
$gz = New-Object System.IO.Compression.GZipStream($fs, [System.IO.Compression.CompressionMode]::Decompress)
$sr = New-Object System.IO.StreamReader($gz, [System.Text.Encoding]::UTF8)
($sr.ReadToEnd() -split "`r?`n") | Where-Object { $_ -match "玩家物理模式|玩家双刚体|玩家物理诊断" } | Select-Object -Last 20
$sr.Close(); $gz.Close(); $fs.Close()
```

四套的验收标志分别是 `SMOKE TEST PASSED` / `CLONE SMOKE PASSED` / `KELVIN FORCE TEST PASSED` /
`PLAYER WALL PROBE DONE`（⑤ 是诊断工具，只要跑完并打印出场景表即可）。

**新增原生函数时必须同时加断言**：本项目踩过的原生化坑（体素索引序、`h` 语义、
`Vector2i.y` 表示世界 z、碰撞组双向判定）全都是"编译通过但语义错"，只有行为断言能挡住。

## 十四、本轮结束时的状态（交接）

| 项 | 状态 |
| --- | --- |
| Gradle 编译 | ✅ BUILD SUCCESSFUL |
| 原生回归 | ✅ SMOKE TEST PASSED（含 cosmos 圆轨道绕一圈） |
| 克隆层回归 | ✅ CLONE SMOKE PASSED |
| kelvin 力模型回归 | ✅ KELVIN FORCE TEST PASSED |
| 原生 ABI | 9（实时属性 / 按句柄移除 / 分离对象 / cosmos） |

**已落地的 kelvin 单元**（全部 `compileJava` 通过）：

| 单元 | 行数 | 状态 |
| --- | --- | --- |
| `CelestialBodyForce` + `KelvinConstants` | — | ✅ 含离线回归 |
| `CelestialBody` | 386 | ✅ |
| `SpaceWorld` | 369 | ✅ cosmos 对接原生（唯一改形：`double[3]` 替 `Unsafe`） |
| `CelestialBody.variant.{Star,Planet,Aircraft,BlackHole}` | — | ✅ |
| `CelestialWorld` | 141 | ✅ 地表⇄太空坐标换算 |
| `ServerCelestialWorld` / `ClientCelestialWorld` | 66 / 37 | ✅ 服务端池 vs 客户端单例 |
| `ServerSpaceWorld` / `ClientSpaceWorld` | 61 / 57 | ✅ 含客户端双世界缓冲 |
| `OrbitPhysicalThread` | 75 | ✅ 100Hz 定时线程 + 重名保护；配置项落在 `Config.CORE_TICK_SPEED/TIME` |
| `Meteoroid` | 96 | ✅ 气动加热/辐射/对流 + 32 点环形尾迹 |
| `event/OrbitPhysicalServerAction` | 72 | ✅ 每维度 `orbit_data` SavedData 存读 + 线程启停 |
| `mps/space/util/manger/SpaceModDataPackManger` | 279 | ✅ `space_data/**` 读取器（命名空间改为本模组） |
| `mixin/datapack/{WorldLoader,RegistryDataLoader}*Mixin` | 2 个 | ✅ 运行时伪造 `PackResources` 注入维度 JSON |
| `datagen/ModSpaceDataProvider` | — | ✅ 从 `RealAstroData` 导出 `space_data/**`（20 天体 + 15 地表维度） |
| `RealAstroData` 新增 `massKg` | — | ✅ 公开天文常数（不是照抄 space 数据集） |
| `mps/space/network/ReliableCreateSender` + `SyncCreateAck` + `SyncCreateEnd` | 97+35+80 | ✅ 载荷无关的可靠创建传输层（见 §18） |
| `mps/kelvin/network/**`（4 包 + 2 事件 + 注册器） | 4+2+1 | ✅ 天体同步全线（见 §18） |

**唯一有意排除**：`ServerSpaceWorld` 在 space 里覆写 `step` 多推一个 `RadioNetwork`
（无线电玩法，16 个类，只被天线方块用，与力学零耦合）。我们**不覆写**，也不造假壳类。
`physical/SpaceNative`（17 行，kelvin 的 native 声明）同样跳过 —— 我们的原生面在
`PhysicsNatives`/`NativePhysics`，13 个 cosmos 函数已全部实现（space 未用的
`cosmosBuilderDestroy`/`DynamicBodyCount`/`InsertBody`/`AddNBody`/`BodyMass` 实测
**space 自身零调用**，故不做；builder 走 detached arena，`InsertBodyAsGravitySource`
会把它 `arena_take` 走，**不泄漏**）。

**kelvin 剩余（实测未落地）**：

| 单元 | 行数 | 卡在哪 |
| --- | --- | --- |
| `SpaceModDataPackManger`（在 space 侧） | 279 | ✅ **已落地**（见 §17）；只剩 `space_data/**` 内容未写 |
| `network/packet/Sync{SpaceWorldCreate,CelestialWorldCreate,CelestialBodyCreate,CelestialBodyMoveBatch}` | 381 | 依赖 `space_data` 内容产出天体之后才有意义 |
| `network/KelvinNetworkHandler` + `network/event/*` | 126 | 同上 |
| `event/PhysicalBodySpaceEvent` | 102 | space 侧物理体 ↔ 太空事件 |

**方向已定（用户选择 B）**：照抄 space 的 `space_data` 数据布局作为**唯一数据源**，
之后把 `PlanetDimensions` / 渲染 / 命令逐步迁到 `ServerSpaceWorld`+`ServerCelestialWorld` 上。

## 十七、`space_data` 数据通路（已落地机制，注入点已逐条验证）

### 三步链路

```
WorldLoader.load(...)
   ↓ offset 19 之前注入 ← WorldLoaderSpaceDataMixin：readSpaceData(resourceManager)
RegistryLayer.createRegistryAccess()      ← offset 19
   ↓
loadAndReplaceLayer(WORLDGEN)             ← offset 34  注册表读盘
RegistryDataLoader.load(DIMENSION_REGISTRIES) ← offset 56
   ↓ 其中 loadContentsFromManager 的 Map.entrySet()
   ↓ ← RegistryDataLoaderSpaceDataMixin：把合成 JSON 当作"真实文件"塞进清单
维度/维度类型/biome 全部就位
```

**注入点是卡死的**，不能挪：早一步资源管理器还没建（局部 6），晚一步注册表已定型。

### 注入点在 1.21.1 的实测依据（别再凭直觉猜）

| 事实 | 验证方式 |
| --- | --- |
| `WorldLoader.load` 在 offset 19 调 `RegistryLayer.createRegistryAccess()` | `javap -c` |
| 该处局部 6 **正是** `CloseableResourceManager` | `javap -c`：`Pair.getSecond()` → `checkcast CloseableResourceManager` → `astore 6` |
| `RegistryDataLoader.loadContentsFromManager` 里 `String s = Registries.elementsDirPath(registry.key())`（局部 5），唯一 String 局部 | `javap -c`；正是 `"dimension"/"dimension_type"/"worldgen/biome"` 三种取值 |
| `entrySet()` 唯一一处，作用于 `FileToIdConverter.listMatchingResources(...)` 的结果 | `javap -c` offset 43 |
| `PackResources` / `Resource(PackResources, IoSupplier)` / `PackLocationInfo(4 参)` 签名 | `javap -p` |

> `javap -l` 在 moddev 制品上**没有调试信息**，所以 `@Local` 靠 Mixin 的字节码分析而非
> LocalVariableTable。本项目已有 `SpaceFirstPersonHandMixin` / `MixinClientPacketListener`
> 在用 `@Local`，故该通路可行；`@ModifyExpressionValue` 亦已有 `SpaceFallingBlockMixin` 在用。

### **真实文件优先** —— 让迁移可以分步走

每处合成前都先 `datas.stream().noneMatch(...)` 判断"数据包里是否已有该文件"，**有则不合成**。
于是本机制与手写 JSON 安全共存：**手写的赢，合成的只补空缺**。
因此现在落地机制 + 手写 `data/poly_mech/dimension_type/space.json` 仍然生效，
不会因为合成而丢掉自定义 `effects`。

### 两处必要差异（已记录）

1. **命名空间用本模组 id**（不是字面量 `space`）：维度 id 必须与既有的
   `data/poly_mech/dimension/*.json` 和 `PlanetDimensions` 一致，否则会出现
   "合成出 `space:moon`、别人引用 `poly_mech:moon`"的两套 id。命名空间是名字，不是架构。
2. **`loadJson` 多吞 `RuntimeException`**：space 只 catch `IOException`，
   于是 JSON 语法错会一路抛到世界加载把游戏打崩。这里把坏文件判宽了一点，
   **不改任何正常路径行为**。

### 照抄不删的怪点

- `CelestialLevelNoiseSettings` 在 space 里**声明后从未写入、从未读取**（噪声设置实际复用维度 id）——
  保留只为公开面同形。
- `addNewCelestialLevelDimension` 的 biome/dimension_type 模板里，
  行星维度类型 `effects` 走 `minecraft:overworld`（不是自己的 id）。
- `SpaceLevelDimensionType` 的 `effects` 是**内联对象**且含 `clouds`/`weather`
  —— 这两个不是原版 `DimensionSpecialEffects` 字段，会被 codec 静默忽略；照抄不删。

### 加载顺序为什么是安全的（不是运气，已实测）

`readSpaceData` 只把 `type.json` 显式 `addFirst`，其余依赖 `listResources` 的顺序。
那条顺序**是确定的**：`MultiPackResourceManager.listResources` 返回 **TreeMap**
（`javap -c` 实测 `new java/util/TreeMap`），按 ResourceLocation 排序后天然是
`object/*` < `type.json` < `world/*`，再把 type 提到最前 →
**[type, object…, world…]**，正好满足"世界先建、天体后塞、地表参数最后套"。

> 这条顺序依赖**必须记住**：若哪天 `listResources` 换了容器，`world/*.json` 会先于
> `object/*.json` 被读到，`ServerCelestialWorld.getCelestialWorld()` 返回 null，
> 地表参数被<b>静默丢弃</b>（不报错）。

### 内容已落地：`ModSpaceDataProvider`（datagen 从 `RealAstroData` 导出）

```
data/poly_mech/space_data/space/type.json                     ← sky_texture
data/poly_mech/space_data/space/object/<天体 id>.json          ← type/scale/carmen_line_height/pos/rotate/texture/temperature/dimension/mass/speed/rotate_speed/compute
data/poly_mech/space_data/space/world/<维度 ns>/<维度名>.json   ← gravity/height/pos_shadow_*
```

四个关键决定（都有理由，别当成随手取的）：

| 决定 | 理由 |
| --- | --- |
| 一级目录叫 **`space`**（space 那边叫 `solar_system`） | 太空世界 id = `poly_mech:space`，与既有 `PlanetDimensions.SPACE` 完全一致；换名等于全库改 id |
| 天体名用 **id**（`sun`）不是中文名（`太阳`） | `SpaceWorld` 判恒星用的是**名字里含 sun** → 决定 `fixed`。用中文名太阳会被当普通卫星积分 |
| `mass` 取**公开天文常数**（NASA 量级） | space 的 `mass` 是它的数据集，受 ARR 保护不能照搬；差异 <1%，且是**数据来源**差异不是机制差异 |
| `speed` **算出来**：`\|v\|=sqrt(G·M/r)`，方向 `normalize(r×Y)`，卫星再叠加母星速度 | 同上不能抄它的向量；方向式子已用两份参考数据反推验证（地球、火星方向完全吻合），大小差约 4%（真实轨道有偏心率，圆轨道是近似） |

**`RealAstroData` 因此新增了 `massKg`**（第 7 个分量），并附带一条硬约束：
`BODIES` 里**母星必须排在卫星之前** —— 卫星速度要叠加母星速度，取不到就是错的。

三处**有意留白**（不是漏，是没自采数据；照抄 space 数值等于照抄其数据集）：

| 字段 | 后果 |
| --- | --- |
| `atmospheric` 整块不发 | `Planet.getAtmosphericHeight()` 保持 -1 → `Meteoroid` 的"是否进大气"恒假 → 流星不烧蚀 |
| `rotate` 发单位四元数、`rotate_speed` 发 0 | 天体不自转（纯观感，不影响轨道） |
| `texture` 发占位路径 `poly_mech:textures/celestial_body/planet/<id>/surface.png` | **必填**（读取端直接 `getAsString()`，缺了 NPE）；本项目行星由着色器程序化渲染，暂无消费者 |

### 运行时净效果（已核对，不是"应该没问题"）

因为**真实文件优先**，合成只补空缺。本项目现有静态文件齐备
（`dimension/*.json` 15 个、`dimension_type/*.json` 16 个**都已在手**），所以：

| 注册表 | 合成结果 |
| --- | --- |
| `dimension` | 15 个行星 + `space` **全部已有静态文件** → 一个都不合成 |
| `dimension_type` | 同上 → 一个都不合成（所以**既有维度的加载行为零变化**） |
| `worldgen/biome` | 行星的 biome id 是 `poly_mech:martian_wastelands` 这类**具名**的，而合成用的是与维度同名的 `poly_mech:mars` → **不存在 → 会合成 15+1 个笼统 biome**。它们没有任何引用者，是无害的额外注册 |

净效果 = kelvin 侧真正获得 1 个太空世界 + 20 个天体 + 15 个地表世界参数，
而原版/既有维度行为不变。若哪天要改用合成的维度类型/biome，
「删掉对应静态文件」即可自动接管 —— 这正是这条通路的设计意图。

`world/*.json` 的 `gravity` 照 space 语义发**绝对 m/s²**（地球 9.807、火星 3.72），
而 `PlanetDimensions.gravity(int)` 是**以地球为 1.0 的倍数**，换算 `abs = factor × 9.807`。
将来迁消费方到 `CelestialWorld.G` 时，**要么除回 9.807，要么把重力链整体改成绝对值** ——
两者混用就是"火星重力变成地球的 3.72 倍"这类静默错误。
`height` 发 320（= 合成维度类型的 `min_y(-64) + height(384)`），
`MinY` 在读取端硬编码 -64，两者必须配同。

## 十八、kelvin 网络层（已落地）

### 六个包的分工与"可靠/不可靠"的分界

| 包 | 方向 | 频率 | 可靠？ |
| --- | --- | --- | --- |
| `SyncSpaceWorldCreate` | S→C | 进维度一次 | ✅ `ReliableCreateSender.send` |
| `SyncCelestialBodyCreate` | S→C | 每体一次 | ✅ 同上 |
| `SyncCelestialWorldCreate` | S→C | 一次（或"没有"一次） | ✅ 同上 |
| `SyncCreateEnd` | S→C | 一次 | ✅ 同上（诊断摘要，不承载数据） |
| `SyncCelestialBodyMoveBatch` | S→C | **每 tick** | ❌ **刻意不可靠** |
| `SyncCreateAck` | C→S | 每个创建包一次 | — （回执本身） |

**分界线是"这份数据有没有下一次"**：创建类只在新玩家登录/换维度时发一次，
丢了就<b>永久缺状态</b>（天体少一颗、地表参数错一套），必须重发到回执；
位姿每 tick 都有下一份，可靠传输只会浪费带宽、放大延迟。

### 为什么 kelvin 需要自己的重发表（不是重复 §12 的 `PENDING_ACKS`）

| | `PhysicsBodyTracker.PENDING_ACKS`（既有） | `ReliableCreateSender`（本轮） |
| --- | --- | --- |
| 键 | `long` 体 id | 自增 `int` |
| 值 | 物理体快照 | `IntFunction<CustomPacketPayload>` |
| 重发内容 | 要走物理体创建逻辑 | 原始包，谁都能用 |

前者是**物理体专用**的，后者是**载荷无关**的传输层。§12 那条"二选一"针对的是
**物理体创建握手**（我们保留项目自己那套）；天体同步只有这一条通路，不存在两套并跑。

### 三个参数的设计意图（照 space）

- **每 40 tick 重发** —— 用 `tickCount/gameTime` 而不是真实时间，因为单机可暂停。
- **普通 5 次 / 关键 10 次 + `kickOnFail`** —— 同步不完整时宁可踢掉玩家，
  也不要把"看到的天体位置是错的、还会基于错状态算落点"的客户端留在世界里。
- **`IdentityHashMap<ServerPlayer, …>`** —— `ServerPlayer` 的 `equals` 不可靠（重连换实例），
  必须按身份分表。

### 两个非显然但关键的机制

**① `SyncCelestialBodyCreate` 的"解码两次"**：`buffer.copy()` 造一个共享底层数组、
读写指针独立的视图，两边各解出**独立对象**，分别放进显示世界与缓冲世界。
若两世界共享同一实例，`syncMoveData()` 就变成"自己拷自己"，世界永久静止。
类型判别字节（`1=Star 2=Planet 3=BlackHole 4=Meteoroid 5=Aircraft 0=基类`）也是必需的：
父类 `encode` 只写身份字段，子类再追加，解码方不知道类型就会<b>整段读错位</b>。
注意 `Meteoroid`/`BlackHole` **没有**自己的 `decode`（用继承来的基类 decode）—— 照抄，别补。

**② 发送顺序就是依赖顺序**：`SpaceWorldCreate` → `BodyCreate`×N → `CelestialWorldCreate`。
第三步在客户端要 `ClientSpaceWorld.getSpaceWorld().getCelestialBody(bodyName) instanceof Planet`
—— 天体没到就查不到，地表参数被**静默丢弃**。

### 换维度重同步：用 `EntityJoinLevelEvent` 而不是延迟补发

换维度（地表 ↔ 太空）也会触发它，于是"换维度后重新同步一遍"零额外机制。
这正是 space 用它取代"旧做法：换维度后延迟 2 秒补发"的原因 ——
延迟补发是在赌客户端什么时候准备好。

### 三处兜底（都不是防御性编程）

1. **地表维度回退到所属太空世界**（`ServerCelestialWorld.SpaceWorldID`）：
   玩家多数时候站在行星地表，不回退就"在表面看天空，行星全都不动"。
2. **太空世界为 null 也要发**（→ 客户端 `init()`）：走出太空后本地还留着旧宇宙。
3. **没有天体世界也要发空构造**（→ `ClientCelestialWorld.init()`）：
   少了它，走出地表后重力仍是上一颗行星的。

### 本轮抓到的一个真 bug（值得记成方法）

`SyncCreateEnd` 会被 `SpaceWorldUpdateSyncEvent` **真的发出去**，但我最初**漏了注册**它 ——
编译完全通过，运行时会抛"未注册载荷"。已修。
**教训：新增包时"发送路径"与"注册处"必须交叉核对**，编译期不覆盖这一点。
核对方式（本轮用的）：`grep 'sendToPlayer\(|new Sync'` 列出所有发送点，
再逐个对 `registrar.playTo*`。结果：5 个 S→C + 1 个 C→S 全部已注册。

### 唯一替换

`ServerPhysicalWorld.getPhysicalWorld(level) != null` → 项目既有的
`PhysicsWorldManager.terrainIfPresent(level) != null`。原因：克隆层服务端物理世界
按 §11/§12 结论暂缓（与项目既有投影/体层重复，S6 才二选一）。
该字段只参与客户端自检日志，语义一致。

## 十九、kelvin 线收尾状态与下一步的决策点

### 已全部落地

| 层 | 内容 |
| --- | --- |
| 力模型 | `CelestialBodyForce`、`KelvinConstants` |
| 天体 | `CelestialBody`、`Star/Planet/BlackHole/Meteoroid/Aircraft` |
| 世界 | `SpaceWorld`（cosmos 对接原生）、`Server/ClientSpaceWorld` |
| 地表 | `CelestialWorld`、`Server/ClientCelestialWorld` |
| 积分线程 | `OrbitPhysicalThread` + `event/OrbitPhysicalServerAction`（存档 + 启停） |
| 数据 | `SpaceModDataPackManger` + 两个 datapack mixin + `ModSpaceDataProvider`（20 天体/15 地表） |
| 网络 | 4 个同步包 + 2 个事件 + `ReliableCreateSender`/`SyncCreateAck`/`SyncCreateEnd` |

### ~~唯一未落地：`PhysicalBodySpaceEvent`~~ —— **【已过期，见下】**

> **2026-09-19 更正**：本节写于 §20 之前。**它已经落地了**：
> `mps/kelvin/event/PhysicalBodySpaceEvent.java` 现 **156 行**，并已在
> `Polymech.java:182` 注册到 `NeoForge.EVENT_BUS`。§20 的"增量 4/5/6"表里也把它列为已落地。
> **它现在不是"没做"，而是"没有输入"**：全仓只有 `ServerPhysicalBody.loadFromTag` 一处
> `new ServerPhysicalBody` ⇒ 没有任何 gameplay 路径创建物理体 ⇒
> `SyncPhysicalBodyCreate` 永远不会发出 ⇒ 镜像/受力镜像/越过 `Height` 抛进太空这三件事
> **从无机会触发**。要让它真正跑起来，必须先做 S6 第一步（把创建动作接到 `ServerPhysicalBody`）。

（以下为当时的判断，保留作背景）它三个入口全部依赖克隆层的**服务端物理体世界**：
`ServerPhysicalWorld.getPhysicalWorld(...)`、`getAllPhysicalBody()`、
`dimensionLeapPhysicalBody(...)`。而 `ServerPhysicalWorld` 当时卡在 `ProjectionManager`（1032 行），
后者按 §11/§12 判定为**与项目既有投影/体层重复的能力**，S6 才二选一。
所以它**不是"没做完"，是"依赖一个已决定暂缓的东西"**。

它的职能（值得记住，迁移时要找项目里的对应物）：
① 把太空世界里的每个物理体镜像成 `Aircraft` 天体并发给客户端（船在太空里要能被看到）；
② 把 `PhysicalBody` 的受力镜像成 `CelestialBodyForce`（`physical_body:<原名>`，跳过 `aircraft:` 前缀）；
③ 物理体升到 `celestialWorld.Height` 以上时，用 `getSpacePosFromWorldPos` 换算到太空坐标并
`dimensionLeapPhysicalBody` 抛进太空维度，同时登记为 `Aircraft`。
项目侧的对应物大概率是 `SpaceTransitionHandler`（玩家那侧）与船舶相关逻辑 —— 迁移前先核对。

### 当前系统的实际形态（重要，别误判"已经生效"）

> **2026-09 更正：下面这段已过期**（它写于"位置权威切换"之前）。现状是 **消费方已接通**：
> 唯一咽喉是 `SpaceWorld.gamePos`（渲染对象、光照/阴影投射、GUI 星图、HUD、传送、命令全走它），
> 由配置 `kelvinAuthoritative` 控制（**默认关**，游戏内 `/polymech kelvin authority true` 可运行时打开）。
> 2026-09 实机确认：打开后行星**真的在动**。前提是先修掉渲染路径漏调的
> `ClientSpaceWorld.syncMoveData()`（见第 25 节）——在那之前，服务端在积分、命令里数字在变，
> 但天空纹丝不动。

（以下为当时状态，保留作背景）kelvin 现在是**完整、自洽、但尚未取得权威**的子系统：

- 服务端确实在跑 N 体积分（`OrbitPhysicalThread` 100Hz）、确实在存档、确实在发包；
- 但**客户端渲染与传送目前仍读静态的 `RealAstroData`**（`SpaceWorld.gamePosMc`、
  `client/gui/widget/planet/*`），`ClientSpaceWorld` 收到的数据**暂无消费者**；
- 因此现在两边都用静态数据，**看不到任何不一致**，也不会"行星乱飞"。

### 下一步是一个**要你拍板**的迁移点（不是技术问题）

把消费方从静态 `RealAstroData` 迁到 kelvin 的权威状态，会**改变玩家看得见的行为**：

| 迁移项 | 可见后果 | 风险 |
| --- | --- | --- |
| 行星渲染读 `ClientSpaceWorld` | 行星随时间真的会动（累积轨道演化） | 需要渲染层每帧从 `ClientSpaceWorld` 取样 + 插值；现在的渲染假设位置是常量 |
| `PlanetDimensions.teleportToSpaceAbove` 读 kelvin 位置 | 传送落点跟随演化后的行星 | 静态落点与动态落点会不一致（若只迁一半） |
| `PlanetDimensions.gravity` 读 `ServerCelestialWorld.G` | 重力由数据包驱动 | **单位陷阱**：`G` 是绝对 m/s²(9.807)，而 `PlanetDimensions.gravity` 是倍数(1.0) —— 必须除回 9.807，否则"火星重力=地球 3.72 倍" |
| 地表⇄太空坐标换算改走 `CelestialWorld` | 上下行落点一致 | 需要 `MinY/Height` 与维度实际 `min_y/height` 严格配同 |

**顺序已按实测修正** —— 我原先提的"先传送、后渲染"是**错的**：

1. ✅ **已做：gravity**（数据已在 `world/*.json`，数值可证不变）。见下。
2. ⚠️ **"只迁传送"单独做会引入可见 bug**：渲染仍按静态位置画，传送却去了 kelvin 积分后的
   真实位置 —— 玩家会"传送到空处"。**传送与渲染必须一起迁**，不能分两步。
3. ✅ **已做：位置权威整体切换**（见"第 2 步"）。之所以能一次迁完，是因为实测发现
   渲染链已经收敛到**单一咽喉** `SpaceWorld.gamePos(RealAstroData)` ——
   渲染对象、光照/阴影投射、GUI 星图、HUD、传送、命令全部经它取值。
   改这一个方法 = 所有消费方同时切换，**不存在分脑**。

> 教训记下来：迁移前必须先找"有没有单一咽喉"。有 → 一次迁完；没有 → 先造咽喉，
> 否则"迁一半"产生的不一致比不迁更糟。

#### 第 1 步（gravity）已落地 —— 数值可证不变

`PlanetDimensions.gravity(ResourceKey<Level>)` 改为优先读
`ServerCelestialWorld.G / 9.807`，取不到才回退内置表。

两个必须记住的点：

- **单位**：`G` 是绝对 m/s²（地球 9.807、火星 3.72076），本方法返回**倍数**（地球 1.0）。
  除以 `STANDARD_GRAVITY(9.807)` 是硬要求；不换算就是"火星重力 = 地球 3.72 倍"。
- **缓存只在查到值时写入**：`gravity` 在 `MixinEntity#getGravity` 里是**每实体每 tick** 的热路径，
  不能每次都遍历静态池（`getAllCelestialWorld()` 还会新建 ArrayList）。
  但**绝不能无条件缓存** —— 世界加载早期 kelvin 还没登记，
  若把回退值缓存下来就会被永久钉住（重力再也回不到权威值）。
  所以"只在成功命中时 put"，既拿到热路径性能、又没有过期风险。

**离线验证（15/15 位级相同，不是"应该一样"）**：对 15 个可传送维度逐个比对
「旧：`分子 / 9.807f`（float 除法）」与「新：`生成的 json 值 / 9.807`（double 再转 float）」，
并同时验证 json 值确实等于 `旧值 × 9.807`：

```
mercury   json=3.7000001274347305   old=0.37728155  new=0.37728155   same=true
mars      json=3.7207601632475855   old=0.3793984   new=0.3793984    same=true
overworld json=9.807                old=1.0         new=1.0          same=true
…
GRAVITY UNITS OK (15/15)
```

> 方法学：迁移"数据源"时必须证明**数值不变**，而不是假设不变 ——
> 这类改动一旦改错数值，症状是"某颗星球重力不对"，会被误判成物理 bug，
> 极难回溯到"迁移"这一步。

#### 第 2 步（位置权威）已落地 —— 带开关，默认关闭

**改了什么**：

| 文件 | 改动 |
| --- | --- |
| `space/SpaceWorld` | `gamePos` 拆成"权威版 + `staticGamePos` 回退"；新增 `kelvinPos()`（客户端优先读显示世界，服务端读 `ServerSpaceWorld`）与 `setKelvinAuthority()` |
| `PlanetRenderObject` | `posX/Y/Z` 由 `final` 改为可变，新增 `updatePosition(...)` |
| `PlanetRenderObjectFactory` | 新增 `refreshPositions()`：每帧按 `gamePos` 刷新全部天体位置 |
| `SpaceRenderer` | 在**排序之前**调用 `refreshPositions()` |
| `Config` + `Polymech` | 新增 `kelvinAuthoritative`（默认 **false**），用 `ModConfigEvent` 喂给 `SpaceWorld` |

**两个关键设计点**：

- **Y 不能跟着迁**。kelvin 是完整三维积分，真实 Y 跨度可达 ±1.6×10⁶ 格，
  而太空维度只有 ±2×10³ 格高。所以权威版只取 kelvin 的 **X/Z**，
  Y 仍用静态规则（地球及其卫星→地球真实 Y，其余→0）。
- **必须刷新渲染对象**。`PlanetRenderObject.pos*` 原本是构造时快照；
  若只让 `gamePos` 变动态而不刷新它，就会出现最糟的分脑：
  **天体本体停在旧位置，而它的光照/阴影投射者、星图、HUD 按新位置算**
  —— 看起来像"影子从一颗星球投到空处"。所以刷新点放在**排序之前**（排序也用这些位置）。

**为什么默认关闭**：这是唯一会改变玩家**看得见**行为的开关。打开后行星会随时间真的移动
（服务端 100 Hz N 体积分 + 客户端镜像同步）。**而我无法在这里目视验证观感** ——
默认关闭意味着"不改代码也能立刻回到原状"，由你打开后判断。
`false` 时行为与迁移前**逐位相同**：`kelvinPos()` 在开关为假时第一行就返回 null，
`gamePos` 直接返回 `staticGamePos`（等同原实现）。

**开关打开后会发生什么（预期）**：`space_data` 的初速度是**圆轨道近似**，
而真实轨道有偏心率，所以行星位置会以每秒数百到数千格的速度偏离静态 J2000 位置
（1 MC 格 = 10000 米）。也就是说打开开关后行星会**明显移动**，这是预期行为，
不是 bug —— 但如果你看到的"移动"破坏了玩法（例如地表与太空对不上），
关掉开关即可。

#### 怎么在游戏里验（9/19 加的两个命令 + 为什么必须看数字）

```
/polymech kelvin                       # 状态：权威开关 / 时间倍率 / 实际积分速率 / 前 3 个天体的位置
/polymech kelvin authority [true|false]# 不给参数=切换（运行时生效，不写配置文件）
/polymech kelvin warp <1..20000>       # 时间倍率（子步，dt 不变 ⇒ 轨道精度不变）；1=实时
```

**为什么必须看数字而不是肉眼看**：开关打开后行星的偏离速率是"每秒数百~数千格"（1 格 = 10000 米），
**1× 就已经在动**；再乘 200 倍，行星是"瞬间飞出视野"而不是"慢慢挪"—— 肉眼反而更难判定。
所以命令会打印两样东西：

| 打印项 | 含义 |
| --- | --- |
| `实际积分速率 = N 步/秒（≈ M× 实时）` | 基准 100 步/秒 = 1×。**两次命令之间它是否按倍率涨**，证明加速真的进了积分循环 |
| `前 3 个天体 kelvin=(…) 米` + `gamePos(格)=(…) ← 渲染用的值` | 隔几秒跑两次命令，**数字变了 = 在动**；`gamePos` 是渲染唯一取值口，它变了而画面不动 ⇒ 问题在"刷新点"（目前只在 `SpaceRenderer:152` 每帧调 `refreshPositions()`） |

**倍率上限与预算**：`warp` 上限 20000，但每个 10ms 周期**最多花 4ms** 做积分
（`OrbitPhysicalThread.STEP_BUDGET_NANOS`）—— 否则"每周期积 N 步"会变成一次无限长的调用，
把天体线程和它持有的世界锁占死、渲染读位置就卡住。所以倍率是"想要的"，
实际跑多少由 CPU 决定，并**如实报出来**。想看"慢慢挪"用 `warp 5~50`；想压测用大值。

## 十五、`CelestialBody` + `SpaceWorld` 的施工图（已通读 `CelestialBody`）

### 一条贯穿全类的设计：所有"改动"都排队，不直接改

`CelestialBody` 的每个改动入口都长这样（`moveTo` / `rotateTo` / `setRotateSpeed` / `addForce`）：

```java
public void moveTo(Vector3d p) {
   if (this.spaceWorld == null) moveToDirect(p);                    // 没挂世界：直接改
   else spaceWorld.putOperation(new OperationBuffer(MOVE_TO, this, new Vector3d(p)));  // 挂了：入队
}
```

**为什么必须这样**：天体世界是**物理线程**每 10ms 推进的，而这些方法由**游戏线程**（方块、实体、
事件）调用。直接改 `pos` 会与积分器并发读写同一个 `Vector3d` —— 轻则一帧跳变，重则撕裂读。
入队由物理线程在步进前统一 flush，等于把"改动"交接给拥有它的线程。
**对应的 `*Direct` 变体就是给物理线程自己用的**（flush 时调用），别在游戏线程调 `*Direct`。

> 与 MPS 的 `RapierWorld` operation buffer、`PhysicalBody` 的 `blocks_collision_update`
> 是同一个套路：**跨线程的写一律排队**。这对理解整个 space 系很关键。

### 其余形态要点（照抄，别改）

| 成员 | 为什么 |
| --- | --- |
| `pos`/`old_pos`、`rotate`/`old_rotate` + `getSmoothPos/SmoothRotate(partialTick)` | 物理 20Hz、渲染 60fps：**渲染必须插值**（lerp / slerp），否则天体一顿一顿 |
| `forces` 的每个访问都 `synchronized` | 力池被两线程读写；`getForcesSnapshot` 返回**副本**而不是原表 |
| `addForceDirect` 用 `contains`/`indexOf` → `set` | **同名即替换**（与 `CelestialBodyForce.equals` 只比名字配套），否则力会叠加 |
| `init(compute, speed, rotate_speed, mass)` 与构造分离 | 构造只给"静态身份"（名字/位置/姿态/半径），可算性与力学量由数据包/存档再喂 |
| `encode`/`decode`、`toJsonObject`/`ReadDataFromJsonObject` | 网络与存档两条独立序列化路径，字段集合不同（前者只发身份，后者带力学状态） |
| `Tag` 用 `TagParser.parseTag` 且**吞异常** | 附加数据坏了不该让天体加载失败 |

**一处刻意保留的怪行为**：`forceTimeUpdata(time)` 遍历时把"超时的那个"记进 `rforce`（会被**覆盖**），
最后只 `removeForce(rforce)` 一次 —— 即**一次只清一个过期力**。这是 space 的原样行为，
多个力同时过期时会分几步才清完；**照抄**（它对结果无影响，但要理解成"惰性清理"），别"顺手改成 removeIf"。

### `SpaceWorld` 要写的东西（已读到的部分 + 待读）

已确认：字段（`CelestialBodyPool` / `WorldID` / `SkyBoxTexture` / `cosmos_world_memory_handle` /
`cosmosReadBuffer` / `cosmosBodyHandles` / `cosmosRegistered` / `operationBufferList`）、
`putCelestialBody`/`removeCelestialBody`/`getCelestialBody`/`getNearCelestialBody`/`getAllCelestialBody`、
`OperationBuffer` + `Type`（`MOVE_TO/ROTATE_TO/ADD_FORCE/SET_ROTATE_SPEED/REGISTER_COSMOS_BODY`）、
`getGravitationForce`（**纯 Java 算**：`6.6743e-11 * M / r²`，方向指向源，末尾 `mul(this.getMass())`，
时长 `POSITIVE_INFINITY`）。

待读（约 160 行，写之前必须读）：`putOperation` 与 flush 循环、`step(core_tick_time)`、
cosmos 世界创建（`cosmosWorldCreate(dt, 4,1,1,1, 1e6)`）与 `cosmosWorldStep`、
`cosmosBodyTranslationOut/LinvelOut` 的读回路径（**注意 kelvin 用 `Unsafe` 裸指针，
我们要换成 `double[3]` 出参**）、`convertMinecraftSpaceVectorToSpaceVector`、
`REGISTER_COSMOS_BODY` 之外的操作类型实现。

> 交接提醒：写 `SpaceWorld` 时**先补 `cosmosReadBuffer` 的替代**（`double[3]` 成员即可，
> 无需 Unsafe），这是与本项目原生唯一需要改形的地方。

## 十六、kelvin 世界层（`CelestialWorld` / `*CelestialWorld` / `*SpaceWorld`）施工图

### `CelestialWorld`：一张"方毯贴到球上"的双向映射

地表是平的世界（x/z 当经纬、y 当高度），行星在太空里是球，两者必须双向可换算，
否则飞船找不到你的地表位置、着陆点也算不出来。换算**只写在这一个类里**，别处一律调它。

三条约定（**别改**）：

1. **进太空时 `x = -x`** —— 地表与太空手性不同，不减这个负号行星会转反方向。
2. **等距经纬投影** —— `lat = x0/L·π/2`、`lon = z0/L·π/2`，拼成单位球向量再乘半径。
   等于把方毯贴到球面：能覆盖到极点，但高纬会被挤（space 的取舍，照抄）。
3. **高度沿卡门线归一** —— `r = R·(1 + h_ratio·(carmenLine/R))`，即地表 `y ∈ [MinY, Height]`
   线性映射到半径 `[R, R+carmenLine]`。所以 `Height`/`MinY` 必须和行星的 `carmenLineHeight`
   一起看，改一个就要重新想另一个。

`pos_shadow`（中心 / 旋转 / 经度长度）是数据包给的贴图参数，**收发都走副本**，
且 `longitude_length == 0` 兜底成 `1024`（防除零 —— 这个兜底在构造里做，不在使用处）。

`getRotateFromWorldPos` = 「局部 +Y 转到该点天顶方向」再**取逆** —— 让人的"上"恒指天顶，
站在行星背面才不会头朝下。姿态换算必须用四元数（`fromAxisAngleRad(a×b, acos(a·b))`）。

### 为什么 `Server*` 和 `Client*` 长得完全不同（这是设计，不是不一致）

| | 服务端 | 客户端 |
| --- | --- | --- |
| 要服务几个维度 | 全部 | 同一时刻**只有当前那一个** |
| `CelestialWorld` | `HashSet` + 按 id / `Level` / 天体三种反查 | **单例** + 自愈读取 |
| 失效处理 | 显式 `init()` 清池（换存档 / reload） | **读取时顺手清过期缓存** |

客户端 `getCelestialWorld()` 的两种失效成因（`level == null`、当前维度 ≠ 缓存维度）
都**不可能靠事件可靠捕捉**，所以做成"读取路径顺手把缓存置空再返回 null"——
调用方永远只需判 `== null`。把"状态可能过期"收进读取路径，是这一层的要点。

### `ClientSpaceWorld` 为什么是**两个**世界

网络只写 `bufferSpaceWorld`；每帧渲染前 `syncMoveData()`：先 `bufferSpaceWorld.up()`
flush 排队操作，再把每个天体的 `pos`/`rotate` 用 `*Direct` **整块**拷进显示世界。
突变只发生在同步点，同步点之间交给 `getSmoothPos/SmoothRotate` 插值 —— 否则天体一顿一顿。
拷贝必须走 `*Direct`：显示世界是纯客户端影子，没有物理线程，再入队只会白添一帧延迟。

`ServerSpaceWorld` 用 `Map`（按 id O(1) 查"这个维度是不是太空"）而 `ServerCelestialWorld`
用 `Set`（需要"按行星天体反查"的集合语义）—— 两者语义不同，不是随手选的容器。

## 二十、MPS 服务端物理体层（方案 A：字面照抄）—— 进行中

### 决定与实测成本

用户拍定 **方案 A**：字面照抄，**含第二个 `ProjectionManager`**，全场约 **2400 行**；
否掉了"复用项目既有投影系统"的 A′（约 470 行）。

必读的前提：**仓库内将并存两套投影系统**（MPS `ProjectionManager` 的克隆与项目既有的
`com.mss.polymech.physics.ProjectionManager`）。两者地皮几何**逐位相同**
（`slot*192 - 64`、边长 128、`min_y=-64`），所以 S6 必须二选一 —— 这是这个决定的已知代价。

> 我原先估"1300+ 行"是**低估**：只算了直系，没算传递闭包（真值 ≈2400）。

### 实测依赖图（读全文才拿到的，不是估的）

```
PhysicalBodySpaceEvent(102)   ← 目标：kelvin ↔ MPS 的桥
   ├── ServerPhysicalWorld(141) ── SyncPhysicalBodyBlockUpdate ✓已有 / PhysicalChunkManager ✓已有
   │      └── extractColliderBody / extractRigidBody / physicalBodies / physicalBodyByCollider
   │          ✓ 已在 RapierWorld / PhysicalWorld 克隆里
   ├── ServerPhysicalBody(117) ── SyncPhysicalBodyCreate(58) + ProjectionManager
   │      └── projectionAABB / worldAABB / setLevel / setPhysicalWorld … ✓ 已在 PhysicalBody 克隆里
   ├── PhysicalBodyWorldData(54)
   └── ProjectionManager(1163) ── 传递闭包比直系大得多：
          ├── SyncPhysicalBodyBlockEntity(82)  ✅ 已落地
          ├── SyncPhysicalBodyBlockUpdate(132) ✓ 已有
          ├── SyncPhysicalBodyRemove(50)       ✓ 已有
          ├── PhysicalRaycast(86)  ✅ 已落地 → ShipRaycast(155) ✅ 已落地
          └── SyncPhysicalBlockBreakProgress(38) → PhysicalBodyInteractionClient(346)
                 → PhysicalBodyInteractionPacket(41) / PhysicalSelectionActionPacket(38)
                 → PhysicalSelectionManager(134) / PhysicalSelectionClient(87) / SpaceModItems(魔杖)
```

### 已落地（每步都 compileJava + 三套离线回归）

| 增量 | 内容 | 行数 |
| --- | --- | --- |
| 1 | `mps/physical/helper/ShipRaycast`、`PhysicalRaycast`、`mps/network/packet/SyncPhysicalBodyBlockEntity` | 155+86+82 |
| 1 附带 | **补上克隆层真缺口**：`RapierWorld.getColliderBody(long)`（MPS 有、我方没有） | ~25 |
| 2 | `mps/network/packet/SyncPhysicalBodyCreate`、`SyncPhysicalWorldCreate` | 65+58 |

**`getColliderBody(long)` 缺口值得单记**：原生射线只回一个 `colliderId`，
必须反查回 `ColliderBody` → 再经 `getPhysicalBody(collider)` 判断"这一击是地形还是物理体"。
少了它 `PhysicalRaycast#castTerrain` 就<b>无法排除物理体</b>，
表现为"站在船上挖地会打到自己脚下的船"。
一处必要差异：MPS 直接读 `colliderBody.handle`（包内可见字段），
我方 `ColliderBody.handle` 是 `private`，改用已有的 `getHandle()`（语义相同）。

### 增量 3：**原子块已一次写完并编译通过**（约 2000 行）

| 类 | 行数 | 职责要点 |
| --- | --- | --- |
| `mps/physical/manger/ProjectionManager` | ~1160 | 投影维度 + 128³ 地皮槽位 + 方块搬运/破坏/放置 + 三态交互转发 + 脏块攒批 |
| `mps/physical/physical_world/ServerPhysicalWorld` | 141 | 按维度 id 的注册表、加体推方块增量、**跨维度搬迁六步** |
| `mps/physical/physical_body/ServerPhysicalBody` | 117 | 绑地皮 + 创建即广播 + NBT 存读（**沿用存档槽位号**） |
| `mps/physical/physical_world/PhysicalBodyWorldData` | 54 | 每维度体存档（`load` 里必须 `.copy()`；按 `level` 字段过滤） |
| `mps/client/PhysicalBodyInteractionClient` | ~330 | 射线命中→吞原版事件→转发；瞄准框 + 裂纹 + 选取方块 |
| `mps/network/packet/PhysicalBodyInteractionPacket` | 41 | 只发 uuid+动作+手，**不发坐标**（服务端自己重算射线） |
| `mps/network/packet/SyncPhysicalBlockBreakProgress` | 38 | 挖掘进度；`-1` 是终止信号 |

**为什么必须一次写完**：`ProjectionManager` 要 `ServerPhysicalWorld` 做反查，
而 `ServerPhysicalBody` 每个构造都调 `ProjectionManager.createNewProjection`
—— 任何一半都编译不过。

**照抄保留的两处 MPS 怪癖（已记录，S6 决定是否修）**：
1. `ServerPhysicalBody(Level, pos, rot)` 委托给 `(ResourceLocation, …)`（那里已发过一次创建包），
   委托返回后**又发一次** → 同体两份创建包。客户端 `addPhysicalBody` 按**实例**判重（不是 uuid），
   所以这条路径会在客户端建出**两个同 uuid 的镜像**。
2. `ProjectionManager` 的槽位**只增不减**（`nextSlot` 单调），
   所以长期运行会持续占用投影维度的 X 轴空间 —— space 就是这么设计的（避免撞上旧地皮）。

**克隆差异（唯一一处）**：`PhysicalBodyInteractionClient` 里 4 处
`SpaceModItems.PHYSICAL_SELECTION_WAND`（space 模组的选体魔杖玩法）**未移植**，
其余逐行同形；配套的 `PhysicalSelectionActionPacket` / `PhysicalSelectionManager` /
`PhysicalSelectionClient` 也因此未落地。理由见上一节末尾。

### 增量 4/5/6：网络同步 + 碰撞线程 + 目标桥（已全部落地）

| 类 | 行数 | 职责要点 |
| --- | --- | --- |
| `mps/thread/ServerCollisionPhysicalThread` | ~150 | **两个**线程：`-step`（100Hz 推物理）、`-chunk`（固定 50ms 地形兴趣点）。分线程的理由是"物理节拍的稳定性"——并进 step 会被偶发的区块扫描拖慢 |
| `mps/thread/ClientCollisionPhysicalThread` | ~105 | 客户端 100Hz 推本地物理世界（玩家碰撞体要在客户端也参与求解） |
| `mps/event/CollisionPhysicalServerAction` | ~95 | 开服：建世界（**重力三级推导**）→ init 投影 → 读档 → 起线程；停服：停线程再存档 |
| `mps/network/event/PhysicalWorldUpdateSyncEvent` | ~95 | 进维度全量下发（5 步顺序不能换） |
| `mps/network/MPSNetworkHandler` | ~80 | 9 个 S→C + 1 个 C→S 的注册 |
| `mps/space/network/packet/SyncPhysicalWorldEnd` | 64 | 物理世界摘要对账（与 kelvin 的 `SyncCreateEnd` 同源思路） |
| `mps/network/packet/SyncPhysicalThreadStart` | ~60 | **命令客户端起线程**（走 `sendCritical`：丢了客户端物理体永远停在原地） |
| `mps/kelvin/event/PhysicalBodySpaceEvent` | ~140 | **目标**：物理体↔天体镜像、受力镜像、越过 `Height` 抛进太空维度 |

**重力三级推导（最容易静默错的一环）**：
```
太空维度     → 0                        （天体引力由 kelvin 单独处理）
行星地表维度 → (0, −CelestialWorld.G, 0)  ← G 是绝对 m/s²（地球 9.807）
其它维度     → (0, −9.8, 0)
```
把 `G` 的倍数当绝对值用，症状只在行星地表显形、且像"物理 bug"——
与 §19 里 `PlanetDimensions.gravity` 的单位换算陷阱是**同一个陷阱的两端**。

**事件优先级是硬约束**：`CollisionPhysicalServerAction` 用 `EventPriority.LOW`
（kelvin 的 `OrbitPhysicalServerAction` 用 `HIGH`）—— 它要读
`ServerSpaceWorld`/`ServerCelestialWorld` 来推导重力，写反就全落进 `(0,−9.8,0)` 兜底分支。

**`PhysicalBodySpaceEvent` 的两个非对称设计（别"顺手对称化"）**：
① 姿态<b>单向</b>同步（天体跟随物理体；反向会把船的姿态锁死在天体积分上）；
② 受力镜像<b>跳过 `aircraft:` 前缀</b>（那些力是天体侧加的，镜像回去会自激、力越加越大）。

**验证**：`compileJava` BUILD SUCCESSFUL；**载荷注册交叉核对 40 个全部唯一**
（项目 `physics_body_*` 与克隆 `sync_physical_*` 资源路径不同、不冲突）；
三套离线回归全绿。已发现的重复注册风险（我在 `Polymech` 里重复注册了三个包）
当场修掉并写进注释。

### 方案 A 剩余未落地（都是**非关键路径**）

| 项 | 行数 | 说明 |
| --- | --- | --- |
| `PhysicalSelectionManager` / `PhysicalSelectionClient` / `PhysicalSelectionWandEvent` / 2 个包 / 魔杖物品 | ~500 | 选体魔杖玩法（space 物品，需模型/贴图/语言） |
| `ClientPhysicalBody` 渲染半边 | — | 需 space 的 `SpaceModVertexFormats` |
| `PhysicalBodyRender` / `PhysicalPlayerColliderRender` | ~100 | 客户端物理体渲染 |
| `ShipRaycast` 之外的 `MixinLevelChunk` / `MixinClientChunkCache` / `MixinLivingEntity` | ~80 | 分散的 mixin 补丁 |

**S6 必做的两件收尾**（方案 A 的既定代价）：
1. 两套投影系统**二选一**（MPS 克隆 vs 项目既有 `physics/ProjectionManager`，几何逐位相同）；
2. 两套物理体网络包**二选一**（`sync_physical_*` vs `physics_body_*`），
   以及两套创建握手（`ReliableCreateSender` 通道 vs `PhysicsBodyTracker.PENDING_ACKS`）。

### 首次真实运行验证（`./gradlew runServer`，9/18 19:18）

**已实测通过**：

| 验证项 | 证据 |
| --- | --- |
| 载荷注册无重复 | 服务端 `Done (0.850s)!`，无 `already registered` |
| mixin 全部注入 | 无 `InjectionError`；`space_data` 机制生效（见下） |
| **datapack 伪造 Resource 机制** | `[Kelvin] space_data 读取完成：太空世界 1 个，天体 20 个，地表世界 15 个` —— 与 datagen 产物**逐个对上**，且**在专用服务端**成立 |
| **重力三级推导** | `overworld → -9.807`（走的是 **celestial/Earth 分支**，不是 -9.8 兜底）、`mars → -3.72076`、`space → 0.0`、`the_nether/the_end → -9.8`。**这同时证明了事件优先级约束是对的** —— 若 LOW/HIGH 写反，所有维度都会显示 -9.8 |
| 四条线程 | kelvin 天体线程、MPS `-step`、MPS `-chunk` 各一次；客户端线程待 `runClient` 验 |

**踩到并修掉的一个真 bug（值得记成教训）**：
`Polymech` 早就注册过 `SyncPhysicalBody{BlockUpdate,MoveBatch,Remove}`，
我又在 `MPSNetworkHandler` 里注册一遍 → 启动即崩
`Cannot register payload poly_mech:sync_physical_body_remove as it is already registered.`

**而我上一轮的"重复检查脚本"漏判了它**，原因很具体：
`Polymech` 里写的是**全限定名**（`com.mss...SyncPhysicalBodyRemove`），
`MPSNetworkHandler` 里是**简单名**，字符串不等 → 判不出重复。
修正：**归一化到简单类名**再分组。
> 教训两层：① 注册点只能有一个，加包时全仓搜 `TYPE` 常量；
> ② **静态检查脚本本身也会骗人** —— 这次是靠"真的起一次服务端"戳穿的。

**两处需要纠正的既有认知**：
1. 我之前说"项目是 tick 驱动"**不完整** —— 项目早就有自己的 100Hz 物理步进线程
   （`[PolyMech] 物理步进线程已启动：10ms/步`）。克隆层与它是**两个并存线程**。
2. **同一维度现在有两个 Rapier 世界**：项目的（`重力 -9.8 m/s²`，句柄 1）与克隆层的
   （`重力 -9.807`）。克隆那套没有体、基本空转，但原生世界与句柄是**实打实占了两份**。
   这是方案 A 的既定代价，也让 S6 的二选一更有必要（否则地形体素化也会做两遍）。

## 二十一、玩家"被弹飞"（**已解决** —— 根因与修法见下节「真根因（第 22 节）」）

> **与 S5 克隆体层无关** —— 用户确认克隆层工作之前就有。别往那边归因。

### 现象（实测数据）

- 玩家以**慢速**靠近**船或普通方块**都会被弹开；稳定极限环，`|链前v|` 在 **8.8 ~ 24.8 m/s**（走路只有 4.4 m/s）。
- 弹飞瞬间速度向量**几乎全在 +Y**（`v前=(0,16.77,1.95)`）→ 被顶上天。
- 位置真的在飞（`跨步Δpos=0.17` ↔ `16.88×0.01` 完全吻合），且被读回原版 → 下一 tick 的 `own` 更大 → **正反馈**。

### 已排除（都有代码/日志证据，别再重复验）

| 假设 | 否掉它的依据 |
| --- | --- |
| `+0.08` 回灌单独致飘 | **火星实测不飘**（若重力被缩放就该净剩 +0.0496 格/tick）；且火星**能正常跳** → `onGround` 在该维度为真、回灌确实在执行 |
| 探地把**兄弟刚体**当地面 | `PhysicsGroundProbe` 查询组与 space 同为 `(2,5)`（space: `castRay(..., 0.1, 2, 5)`），双向判定下打不到自己 |
| 速度链抄错 | 与参考 `PhysicalEntity.afterStep` **逐行一致**（`sibling+own` / `eps 1e-8` / `sibling=own` / `sibling.pos=main.pos`） |
| `sibling` 组抄错 | 参考 `PhysicalEntity` 第 86/95 行确为 `(2,5)` / `(5,5)`，与我们相同 |
| 原生碰撞组语义/参数序 | `lib.rs:513` 注释 + `InteractionGroups::new(membership, filter)` 实现，双向 AND、参数序正确 |
| 重力双轨（原版×factor + 刚体 `gravity_scale=0`） | 火星能正常跳、不飘 → 不是本因 |
| **兄弟刚体"撞不到任何东西"** | **我自己算错过**：`5 = 0b101`，`1 & 5 = 1 ≠ 0` —— 兄弟与地形(1)/物理体(4) **本来就会**接触。据此做的 `SIBLING_FILTER = -1` 实验**反而导致自举升空**（见下），已回滚 |

### ⚠️ 实验留下的教训（别重犯）

把 `SIBLING_FILTER` 从 `5` 改成 `-1` 后，玩家**自己向上飞**：
```
原值 5： main(2,5) × sibling(5,5) → (2 & 5) = 0  ✗  两体不互撞 ✓
实验 -1： main(2,5) × sibling(5,-1) → 两体互撞 → 两个重合的 25kg 动态体互相推开
         而 ④ 又把它瞬移回重合位置 → 排斥速度被 ② 加进主刚体 → 自举升空
```
**`(5,5)` 这个"与任何东西都不互撞的自碰撞组"是 space 刻意的设计，不是笔误 —— 别再动这个值。**

### ❌ 已否：位置读回"减了两次中心偏移"（前提本身是错的）

上一轮把 `ClientPhysics:494` 的 `pos[1] − playerCenterOffset` 当成第一嫌疑，理由是
"我们的碰撞体建在局部 `(0, halfHeight, 0)`"。**这个前提是错的 —— 那是 space 的写法，不是我们的。**

| 事实 | 证据 |
| --- | --- |
| **我们没有**碰撞体局部偏移 | `native/polymech-physics/src/lib.rs:561` `ColliderBuilder::cuboid(hx,hy,hz)` 之后**没有** `.translation(...)`，也没有 `insert_with_parent` 之外的定位 → 碰撞体就在刚体原点 |
| **space 有** | `decompiled-space/0.1.3/.../PhysicalEntity.java:83` `new ColliderBody(CUBOID, new Vector3d(0.0, this.halfHeight, 0.0), ...)`，而刚体建在 `entity.getX/Y/Z`（**脚底**，第 27/37 行）→ 它是"原点在脚底 + 盒子抬半高" |
| 我们的刚体原点在**箱中心** | `ClientPhysics:719` 建体传 `player.getY() + centerOffset`；`SpacePlayerData.bodyCenterOffset` 普通姿态 = `getBbHeight()*0.5` = halfHeight |
| 所以读回必须减**恰好一次** | `ClientPhysics:494 / 583 / 617` 三处回写、`486` 安全网、`465 / 568` 飞行跟随，**同一约定、成对使用**；全仓库没有第二个消费者（`PlayerPhysicsBody.readPosition/centerOffset` 无人调用） |

**两种约定把盒子放在世界里的位置完全相同**（space：脚底 + (0,halfHeight,0)；我们：脚底+halfHeight + (0,0,0)），
是等价约定，不是"谁少减一次"。真按上一轮的计划改成 `setPos(pos[0], pos[1], pos[2])`，
实体原点会**沉到脚底以下 0.9 格**（相机进地面、`hasTerrainAt`/区块判定整体错位），
属于"只换症状"的补丁 —— **不做**。

> 这条错误的来源值得记成方法：把 space 源码里的 `(0, halfHeight, 0)` **读成了自己的代码**，
> 于是推导出一个不存在的前提。**约定类事实必须去自己那一侧的原生实现确认**（这次是 `lib.rs`），
> 不能从对照实现反推。

**次嫌疑（第 10 项）也已否**：`walkDist/moveDist`（`ClientPhysics:502-506`）全仓库只有本文件在写、
只有渲染（颠簸/摆臂）与音效在读，**不参与 `getDeltaMovement()`**，因此不可能进 `own`。

### 🎯 真正的机制（已用离线夹具复现并量化）

新增第 4 套离线回归 **`native/jni-smoketest/PlayerWallProbeTest.java`**（不需要开游戏），
把 `ClientPhysics` + `PlayerPhysicsBody` 逐条搬成原生调用后单变量扫描，结论：

| 场景（4 秒 / 400 子步） | 最大 \|v\| | 最大 vy | 垂直漂移 | 判定 |
| --- | --- | --- | --- | --- |
| A 慢速靠近墙 own=(1.0, 0) | 2.000 | 0.000 | +0.007 格 | **稳定**（0.007 就是 contactSkin 0.02 的沉降） |
| B 步行靠近墙 own=(4.317, 0) | **8.634** | 0.000 | +0.007 格 | 稳定贴墙，但速度 = **2×own** |
| C 慢速 + own.y=+1.6 | 3.774 | 3.200 | **+12.76 格** | 不弹，但被**持续抬升**（own.y 被当成常驻推力） |
| D 步行 + own.y=+1.6 | 9.208 | 3.200 | +2.28 格 | 同上 |
| E 原地不动 | 0.000 | — | +0.007 格 | 稳定（对照） |
| **F 起跳**（own.y: 8.4 → 每 tick 按原版衰减） | **16.800** | **16.800** | 跳 **2.511 格**（原版 1.25） | **×2.00** |
| G 起跳 + 水平走 | 9.444 | 8.400 | 0.257 格（贴墙时被墙吃掉） | ×1.00（贴墙后兄弟速度被接触清掉） |

两条硬结论：

1. **几何与接触无关**：`own.y = 0` 时，慢速/步行撞墙**完全不弹**（y 漂移 0.007 格，末态 vx = own）。
   所以"被弹飞"**不是**位置基准错开、也不是求解器给的接触反作用 —— 根因在 **`own` 的垂直分量**。
2. **链子把 `own` 当常驻速度，并且实测正好 ×2**：`main.linvel = sibling.linvel + own`，
   而兄弟刚体上一子步刚被写成 `own`、且它 `gravity_scale=0`（无正压力 ⇒ 摩擦为零）所以**不会被接触吃掉**，
   于是 `main = own + own`。F 场景是干净的证据：一次性冲量 `own.y = 0.42×20 = 8.4`
   被写成 **vy = 16.800 = 2 × 8.4**，腾空高度也正好 ×2。

**与线上日志的吻合**：`v前 = (0, 16.77, 1.95)` ⇒ 反推 `own = (0, 8.385, 0.975)`
⇒ `movement = (0, 0.419, 0.049)` —— **≈ 原版一次跳跃的 `delta.y = 0.42`**（`0.42 × 20 × 2 = 16.8`）。
这条线索一度把我引向"`autoJump` 触发了跳跃"，**方向是错的**（用户确认自动跳跃一直关着）。
真正的问题不是"这一发 0.42 从哪来"——**是 own.y 自己一 tick 一 tick 涨上去的**，见第 22 节。

### 🎯 真根因（**第 22 节**，已定论 + 已修）：回灌的 `+0.08` 与 `getGravity()` 不匹配

### 怎么定论的：翻用户自己的日志，看 `own=` 字段

`PlayerPhysicsBody` 的临时插桩每行都打了 `own=(...)`，而 §21 那一轮只引用了 `v前`。
把 `run/logs/2026-09-18-*.log.gz` 解开后，客户端维度是 **`poly_mech:space`（世界重力 -0.0）**，
`own.y` 的实测序列是：

```
1.57 → 7.53 → 14.34 → 20.50 → 26.06 → 31.09   （每 250ms 一行 = 每 5 tick）
```

**这与递推 `D ← 0.98·(D + 0.08 − g)`（g = 0）的第 1/5/10/15/20/25 tick 逐位相同**，
固定点 `D* = 49 × (0.08 − g) = 3.92 格/tick = 78.4 m/s`。同一批日志行还顺带证实了两件事：

- `载速=7.53 / own=7.53 / v后=15.07` ⇒ **`main = sibling + own` 精确 ×2**（离线也复现了 ×2.00）；
- `|链前v|=1.28 / 载速=1.28 / own=11.17 / v后=12.45` ⇒ 人已经在飞。

### 为什么会有这个递推（原版调用顺序，`build/mcsrc` 逐行核过）

```
LivingEntity.travel 地面分支
 :2326  vec35 = handleRelativeFrictionAndCalculateMovement(...)
                  └ :2384 moveRelative(...)
                    :2386 this.move(SELF, getDeltaMovement())   ← 我们的 HEAD cancel / 回灌在这里
                  └ :2387 vec3 = getDeltaMovement()              ← 读到的就是回灌值
 :2331  d2 -= d0                     （d0 = this.getGravity()，:2221）
 :2341  setDeltaMovement(vec35.x*f3, d2*0.98, vec35.z*f3)
```

也就是：**回灌发生在 vanilla 减重力之前**，所以回灌量必须等于 `getGravity()` 才叫"抵消"。
space 字面写 `+0.08`，那只在"重力恰好 0.08"时成立；而本项目
`MixinEntity.polymech$gravity` 是按维度给值的：

| 维度 | getGravity() | 每 tick 净增 | 稳态 own.y |
| --- | --- | --- | --- |
| 主世界 / 下界 / 末地 | 0.08 | 0 | 0（稳定，逐位不变） |
| 火星 | 0.0304 | +0.0496 | 48.6 m/s |
| **太空** | **0** | **+0.08** | **78.4 m/s ← 日志就是这一条** |

> `PlanetDimensions.gravity`：`SPACE → 0.0f`、行星取 kelvin 的 `G/9.807`、未知维度 → `1.0f`。
> 所以**只有主世界那一档是安全的**，太空与所有星球都会中招 —— 而船、站、甲板都在太空里。

### 修法（一处、一个变量）

`ClientPhysics.drive()` 的回灌由字面 `0.08` 改成 **`player.getGravity()`**：

```java
if (player.onGround()) {
    player.setDeltaMovement(movement.x, movement.y + player.getGravity(), movement.z);
}
```

这不是"殊途同归"式的另写一套：调用点、时机、意图（抵消 vanilla 减掉的那一份重力）
都与 space 一致，改的只是把那个**在太空自相矛盾的常数**换成它的定义式。
主世界上 `getGravity() == 0.08`，**逐位等价**；太空/行星上才第一次真正成立。
（space 0.1.3 这里确实有同样的隐患：它自己把太空的 `getGravity()` 设成 `0.0`
（`decompiled-space/0.1.3/.../deep_space_studio/space/mixin/common/entity/MixinEntity.java:51`），
却仍旧字面加 `0.08` —— 这是照抄字面值会踩的坑，不是我们发明的差异。）

### 回归（离线、不需要开游戏）

`PlayerWallProbeTest` 新增两节，钉住这条结论：

- **H 垂直记账**：打印 `D ← 0.98(D + 注入 − g)` 的递推表。验收判据是
  `注入=0.08, g=0` 那一行**必须**复现上面日志的 `1.57 / 7.53 / 14.34 / 20.50 / 26.06 / 31.09`，
  而 `注入=g` 的三行必须全为 `0.00`。
- **I 站在太空甲板上 6 秒**：own.y 由 H 的递推**涌现**给出。
  `注入=0.08` → 被抬升 7.13 格（飞了）；`注入=getGravity()=0` → 最高抬起 0.01 格（纹丝不动）。
- **J 方向律**（见下）：同一接近速度下"向下落甲板"与"水平撞墙"各跑一遍，两种回灌各一组。

### 方向律：用户实测"只有从世界正上方向下撞才会被弹飞" —— 已解释，且是同一根因

这条观察是**指向根因的强证据**，不是另一个 bug。理由：玩家身上唯一"与方向有关"的机制就是

> `PhysicsGroundProbe` 从脚下**朝世界 −Y** 打 5 条 0.1 格的射线 → `onGround` →
> `drive()` 里**唯一那处速度注入** `movement.y + 注入`。

水平撞墙**不会**让这 5 条向下射线命中，所以 `own.y` 拿不到正的注入，
链子再怎么放大也只是水平速度（表现为"被挡住"而不是"被弹飞"）；
从正上方落到甲板/方块才会让探地命中 —— 于是只有那个方向会飞。
**"从正上方撞" ≡ "脚下探到了东西"，两者是同一件事。**

`PlayerWallProbeTest` 的 **J** 节把这件事钉死了（世界取太空 g=0）：

| 名义接近速度 k | 旧 `注入=0.08`：向下落甲板 / 水平撞墙 | 修后 `注入=getGravity()=0`：两者 |
| --- | --- | --- |
| 1.0 | vy=1.6 抬 2.2 格 / vy=19.3 抬 34.0 格 | vy=0.0 抬 0.0 格 |
| 4.0 | vy=5.4 抬 10.3 格 / vy=38.6 抬 77.8 格 | vy=0.0 抬 0.0 格 |
| 12.0 | vy=3.8 抬 6.8 格 / vy=46.6 抬 94.4 格 | vy=0.0 抬 0.0 格 |

两条结论：

1. **接触本身完全不弹人**：把注入摘掉后，24 m/s 的正面撞击两个方向都 `反弹 0.00×`
   —— 所以"撞击反作用"这条线可以彻底排除。
2. **只要注入是 `getGravity()`，两个方向都不弹**；而旧的字面 `0.08` 在两个方向都能把人顶飞
   （夹具里水平那例之所以也飞，是因为链子把盒子一路顶进墙里、角上的向下射线从**实体内部**起射
   → `castRay` 命中 toi=0 → 探地误判为"着地"。真机上玩家靠在墙表面不会嵌进去，所以只有下落那一例会命中）。

> 另一条**独立**的、被这条观察顺带暴露出来的隐患：`castRay` 的起点若落在碰撞体**内部**，
> 一律返回命中（toi=0）。也就是说"玩家身体有一部分嵌进任何固体"⇒ 探地恒为真 ⇒ 恒在回灌。
> 这条不是本轮改的对象，但它能解释"卡进船体后一直在飞"，值得单独立项（需要的是"射线起点在内部时
> 是否算着地"的原生/上层判定，而不是继续加 Java 侧启发式）。

### 已知残留（**故意没动**）

回灌只抵消了 `getGravity()`。vanilla 还会在两种效果下再改这个量
（`LivingEntity` :2223 缓降 `d0 = min(d0, 0.01)`、:2328 漂浮 `d2 += (0.05·(amp+1) − vec35.y)·0.2`），
所以**缓降药水 / 漂浮效果下仍会净增**（缓降约 +0.07/tick）。这两条要用同一个思路处理
（照原版那两行精确镜像），但它们是独立的第二个变量，本轮不顺手改。

### 另外两件查证结果

1. **速度链 ×2 是 space 的原设计，不是抄错**：`main = sibling + own`，而 sibling 每子步刚被写成
   `own`，且玩家刚体 `gravity_scale=0`（无正压力 ⇒ 摩擦为零）所以那个 `own` 不会被接触吃掉。
   离线实测 ×2.00，日志实测 `载速=7.53 → v后=15.07` 也是 ×2 ⇒ **走路是 2×own、跳跃是 2.5 格**。
   这是 space 的行为，先照抄并记录，别当噪声删。
2. 克隆层 `ColliderBody.pos`（局部平移）**只被 get/set，从未落到原生**（见下节）。

### 抽搐（持续顶住物理体时）：**位置回写的架构对齐**（第 23 节，已修）

**症状**：玩家持续顶住物理体（一直按着方向键撞着船/方块）时抽搐。上一轮的"被弹飞"已经没了。

**它有两层，必须分开说**：

| 层 | 是什么 | 实测 | 归属 |
| --- | --- | --- | --- |
| ① 物理侧 | 两个刚体同位置、兄弟体每子步被瞬移回主刚体位置并写入 `own`，接触解算给出的法向速度与 `own` **不会逐位抵消** ⇒ 主刚体在法向轴上以**子步频率**来回 | `PlayerWallProbeTest` **K 节**：接触法向极差 = **14.9 mm（own=1.0）/ 48.8 mm（own=4.317）/ 120 mm（own=12）** ≈ `own × dt` | **space 也有**（链子逐行相同、求解器参数两边都是 Rapier 默认值 —— 见下），不是我们引入的 |
| ② 可见侧 | 我们**每帧**把刚体位置写进玩家，并把 `xo/yo/zo/xOld/yOld/zOld` 全部对齐到当前位置 ⇒ **关掉了原版的 tick 插值**，①那条 100Hz 抖动被原样送进相机 | 删掉后由原版在 20Hz 采样点之间插值 | **我们自己引入的** ⇒ 本轮修的就是它 |

#### space 是怎么做的（类 → 职责 → 为什么）

| 类 | 职责 | 为什么必须这样 |
| --- | --- | --- |
| `RapierWorld` | 世界的**唯一门面**：`operationBufferList`（`ConcurrentLinkedQueue<OperationBuffer>`）+ `up()` + `step()` + `tickListeners` | 世界是原生指针，**只能有一个写者**。所有改动排成命令，`up()` 在**步进开头**按 FIFO 一次性落盘 ⇒ 仿真永远不会看到"改了一半"的状态，顺序也确定 |
| `RigidBody` / `ColliderBody` | 每个**写**方法（`setPos/setLinvel/setRotation/setAngvel/setStatus/setCCD/applyImpulse/改材质/改分组`）都只 `rapierWorld.putOperation(...)`；每个**读**方法（`getPos/getLinvel/getRotation/getMass/getForce`）**直接调原生** | 写要排队（跨线程 + 定序），读不用（便宜、无副作用）。这就是"为什么他们偏偏弄一个队列"的全部理由 |
| `PhysicalWorld` / `ClientPhysicalWorld` / `ServerPhysicalWorld` | 每维度一个物理世界；碰撞组常量与三个挂载入口（地形 `(1,-1)`、物理体 `(4,-1)`、玩家 `(2,5)/(5,5)`）；`ClientPhysicalWorld.step()` 在 `super.step()` **之后**跑每个玩家的 `afterStep()` | 玩家速度链必须紧跟求解器之后、同一线程、同一子步 |
| `PhysicalEntity` | 玩家的一对刚体（主/兄弟）+ 速度继承链；`move()` 只记 `ownVelocity`，`afterStep()` 只读兄弟速度、写主速度、搬兄弟位置 | 见 §21：兄弟体存在的唯一目的是"量出被携带的速度" |
| `ClientPhysicalBody` | 客户端镜像体：`KINEMATIC_POSITION`；`onMoveSync(target)` 起插值，`tickMovePos()` 每子步 `+0.2` 调 `setNextKinematicPosition`；**渲染直接读刚体当前位姿** | 镜像体是**他们自己渲染的对象**，不走原版实体管线 ⇒ 可以直读 100Hz 状态 |
| `ClientCollisionPhysicalThread` | **唯一的步进线程**（100Hz）：`setTickTime → up() → step()` | 单写者 |
| `MixinEntity.space$moveWithRapier` | 在 `Entity.move` HEAD 探地 / 喂 `own` / **`entity.setPos(pos)`** / `+0.08` 回灌 / cancel | **全仓库对实体位置只有这一处写入 = 每 tick 一次**，而且**从不碰 `xo/yo/zo`** |

> **一句话的架构意图**：**实体是 100Hz 仿真的 20Hz 消费者，平滑交给引擎自己的 tick 插值；
> 只有"自己渲染的对象"（物理体）才直读刚体状态。**
> 我们早先把后半句**误推到了玩家实体上**（`frameWriteBack`，且注释里就写着"space/MPS 就是渲染时直接取刚体状态"）
> —— 那句话对**物理体**成立，对**实体**不成立。这和 §21 那两个错（`(0, halfHeight, 0)`、`+0.08`）是同一类错误：
> **把"他们那一侧成立的事实"套到我们这一侧的另一个对象上。**

#### 本轮改动（一处）

```java
// 删除 ClientPhysics.frameWriteBack()（每帧 setPos + 抹平 xo/yo/zo/xOld…）
// 删除 ClientPhysicsDriver.onRenderFramePre(...) 的注册
```
位置现在只在 `drive()`（= space 的 `Entity.move`）与 `afterPlayerTick()` 兜底里写，**每 tick 各一次**，
插值字段完全交回原版 —— 与 space 同构。`compileJava` 通过。

#### 结论（用户拍板 **9/19**）：**保持 space 同款设计，不动注入那一处**

抽搐分两层，只有一层是我们的错，另一层就是 space 的设计本身：

| 层 | 量级 | 归属 | 处置 |
| --- | --- | --- | --- |
| 零重力下 y 通道无阻尼累积（`D ← 0.98·D + 输入` ⇒ 50×输入 = 97.6 m/s 锯齿） | 速度极差 **97 m/s** | **我们的错**（6DOF 输入把竖直分量灌进了一个"原版指望重力抵消、所以不设摩擦"的通道） | ✅ 已修：竖直轴补上与水平轴同一个 `f3` 阻尼 |
| 接触里的穿透锯齿（每子步把 `own` 顶进接触 → 沉进 `own·dt` → 被推回） | 速度极差 **2–3 m/s**、位置偏离 **≈ own·dt**（7.6 / 26.5 / 72 mm @ own=1/4.3/12） | **space 同款**（同一条链、同款运动学镜像、同样的 Rapier 默认求解器） | ⏸️ **接受** |

修复后实测：`|v|>15 m/s` 由 **17 条 → 1 条**；`own` 回到走路速度（2.6 m/s，推导值 2.4 m/s）；速度极差 **97 → 2–3 m/s**。

**"调求解器"这条路已经试完并封闭**（三个旋钮全试、全回退，数据留在 `native/polymech-physics/src/lib.rs` 注释里）：

| 旋钮 | 改动 | K 节位置偏离（own=1 / 4.317 / 12） | 结论 |
| --- | --- | --- | --- |
| `normalized_prediction_distance` | 0.002 → 0.05 | 7.6 / 26.5 / 72.2 mm | **一个都没变**（它只决定"多远开始算接触"，而这里已经是接触中） |
| `contact_softness` 频率 | 30Hz → 120Hz | 20.9 / 31.2 / 77.4 mm | **更糟** |
| `num_solver_iterations` | 4 → 8 | 7.6 / 26.4 / 72.1 mm | 无变化 |

**根因不在求解器**：原版不靠调参，靠**同一步内把位置夹到表面**（`build/mcsrc` `Entity:639 collide()` →
`:652 setPos(夹过的位移)`、`:677 setDeltaMovement(被挡轴 → 0)`）—— **原版从不穿透**。
我们 HEAD `cancel()` 掉整个 `move`，这两件事一起没了，于是每子步沉进 `own·dt` 再被修正。
这一层由"每子步注入多少"决定，**不是"求解器多软"**。

> 教训要留住：**`own·dt` 是注入量，不是刚度**。以后遇到同类现象，先算"每子步往接触里注入了多少"，
> 别去翻 Rapier 的参数表。

#### ⚠️ 偏离实验（用户 9/19 拍板"偏离一下"）：**实测否决，已回退**

想法：原版 `Entity.move` 被挡时会**在被挡轴上把速度清零**（`build/mcsrc` Entity:677）。
我们 HEAD cancel 跳过了它，于是链子每子步把 `own` 原样顶进接触。于是试着在链子里
按"环境放行比例"缩放 `own`（`k = clamp(sibling/own, 0, 1)`，`main = sibling + own·k`）。

| | space 同款（基线） | 偏离（缩放 own） |
| --- | --- | --- |
| K 位置偏离均值 own=1 | 7.6 mm | **16.3 mm** |
| K 位置偏离均值 own=4.317（走路） | 26.5 mm | **54.5 mm** |
| K 位置偏离均值 own=12 | 72.2 mm | **158.4 mm** |
| 顶墙末态速度（场景 B） | `4.317`（= 1×own） | **`8.634`（= 2×own）** |

**翻倍，而且更糟。** 原因想通之后非常关键：

> **兄弟体不是"被挡检测器"，它是"对消伙伴"。**
> 顶着接触时它量到的是**被解算器顶回来的速度**（≈ −own），所以
> `main = sibling + own ≈ 0` 才是稳态 —— **稳态来自这一对的"对消"，不是来自"少注入"**。
> 把 `own` 缩放掉，等于把对消项拆了：`-own + own·k` 不再抵消 ⇒ 剩下的 2×own 直接砸进接触。

而且它同时解释了 **space 那条链"为什么偏偏原样相加"**：那句不是随意，是被对消结构定死的。

（教训与上面"调求解器"那条同源：**先算"这一子步往接触里注入了什么/抵消了什么"，再谈参数**。
另外注意：二值版会把"被移动平台带走"的载速（`sibling ≠ own` 而 `own ≈ 0`）一起误抹，
所以这条路连"换个判据再试"的价值都没有。）

真要按原版的机制（**同一步内把位置夹到表面**）压掉残余，等于要在 Rapier 之外自己实现
"步前 shape-cast + 夹取"那一层 —— 那是另一个量级的改动，属单独立项，不在本轮范围。

#### 仍然是我们与 space 唯一的结构差异（**有意不做**）

`RapierWorld` 的 `OperationBuffer` 命令队列 + `up()` 单点落盘（写方法全排队、读方法直连原生）。
我们的原生层是 `Arc<Mutex<PhysicsWorld>>`，**没有数据竞争**；缺的只是 space 那种"改动统一定序、
在步进开头一次性落盘"的确定性。它与本次症状无关（抽搐两层都已解释），按"一次只动一个变量"**不做**；
要做时照上表补 `putOperation/up()` 即可（Java 侧排队 + `PhysicsStepThread` 的 `worldStep` 前 flush，
语义与 `RapierWorld.up()` 一一对应）。

#### 有意保留的偏差（记录在案，不视为 bug）

- **创造飞行**我们交回原版驱动位置（刚体跟随）；space 不特判飞行，飞行也是物理驱动。
- **服务端玩家没有刚体**：两边一致（`PhysicalEntity` 只在 `LocalPlayer` 上建）。

### 顺带查出的一个真缺口（当前不在跑，但必须补）

`com.mss.polymech.mps.rapier.helper.ColliderBody` 的 `pos` 字段（构造参数里的局部平移，
space 就靠它把盒子抬半高）**只被 get/set，从未落到原生**（`ColliderBody.java:148-184` 的
`attach()` 直接调 `colliderAttachCuboid*`，原生这几个函数**没有平移参数**）。
即：克隆层一旦开始真正建体，**所有带局部偏移的碰撞体都会贴着刚体原点**，
与 space 的几何错开。修法只能是**补原生**（新增 collider 局部平移 setter，
ABI 6 已有 `bodySetPose` 的先例），不要在 Java 侧用"建体时先挪刚体"之类的等价仿真绕过去。

### 本轮已改的代码（都在工作区，已编译）

| 文件 | 改动 | 性质 |
| --- | --- | --- |
| `ClientPhysics` + `SpaceWorldUpdateSyncEvent` + `Polymech` | 红字修复（字段来源同一子系统 + 注册顺序 + 清掉重复注册） | ✅ 正式修好，已实测 |
| `PhysicsGroundProbe.OFFSETS` | 采样点原来只探 3 个共线点（**四角全丢**），已改成 space 的"中心+四角" | ✅ 正式修好（独立 bug） |
| `PlayerPhysicsBody.SIBLING_FILTER` | 实验改 `-1` → **已回滚为 `5`** | ↩️ 已还原 |
| `PlayerPhysicsBody` | **临时诊断插桩**：`velocityChain` 里的采集块 + `diagReport` + 5 个 `DIAG_*` 常量 + 计数字段 | 🗑️ **定论后必须整段删除**（源码里标了 `===== 临时诊断（整段可删）=====`） |
| `native/jni-smoketest/PlayerWallProbeTest.java` | **新增**：玩家链的离线单变量探针（A–G 场景 + H 垂直记账递推 + I 太空甲板 + J 方向律 + K 接触抖动幅度） | ✅ 新增诊断/回归工具（无副作用，不入生产路径） |
| `docs/mps-clone-plan.md` §21/§22/§23 | 位置读回嫌疑 → **已否**（前提错）；autoJump 猜想 → **已否**（第 22 节）；方向律 → 同一根因；抽搐 → 架构对齐（第 23 节） | 📄 文档 |
| **`ClientPhysics.java`（生产代码，真修复 1）** | 贴地回灌 `movement.y + 0.08` → **`movement.y + player.getGravity()`** | ✅ **正式修好**（第 22 节：日志逐位复现 + 离线 H/I 回归） |
| **`ClientPhysics` + `ClientPhysicsDriver`（生产代码，真修复 2）** | **删除** 每帧回写 `frameWriteBack()` 与其 `RenderFrameEvent.Pre` 注册（它同时抹平 `xo/yo/zo/xOld…`，等于关掉原版 tick 插值） | ✅ **正式修好**（第 23 节：space 全仓库对实体位置只写一次/每 tick） |
| `PlayerPhysicsBody`（诊断，**改为长期保留**） | 旧的 200 行"临时插桩"已被清掉 → 换成 **`diag()`**：只在 `|v|>15 m/s` 或单子步位移 `>0.2 格` 时打印一行（含 `v / 载速 / own` 三个向量），节流 500ms，正常时完全静默 | ✅ 长期工具（第 22 节那个根因就是靠这三行定出来的，不该删） |
| `ClientPhysics`（诊断，**新增**） | **玩家物理模式边沿日志** `noteMode(...)`：模式变化时打印一行，含"物理接管 / 原版驱动（原因）/ 关闭（原因）"与**主刚体句柄、兄弟句柄、碰撞箱尺寸、中心偏移** | ✅ 长期工具（见下节：为什么肉眼分不出"物理接管"还是"退回原版"） |

### 为什么"碰撞看起来是原版碰撞箱"这件事，肉眼分不出来

用户看到的现象："碰撞怎么是基于原版碰撞箱的？我的双刚体呢？" —— 这**不是 bug，是设计**，
而且逐条都能对上 space：

| 事实 | 代码 |
| --- | --- |
| 玩家的 Rapier 碰撞体**尺寸就是从原版碰撞箱取的** | 我们 `ClientPhysics.ensurePlayerBody`：`halfWidth = getBbWidth()*0.5`、`halfHeight = getBbHeight()*0.5`；space `PhysicalEntity:69-70` **逐字一致** |
| 两个刚体**同位置、互不相撞** | 组 `(2,5)` × `(5,5)`：`(2 & 5) == 0` ⇒ 互不作用；且每子步 ④ `sibling.pos = main.pos`。所以从外面看**永远是一个盒子** |
| 兄弟体不是"第二个碰撞箱"，它是**测速器** | 它的职责是量出"环境想带你去哪"（船面速度），供 `main = sibling + own` 把玩家带上移动平台 |
| 原版碰撞箱**仍然被维护**（`setPos` 一直在写），但**原版碰撞不跑** | `EntityPhysicsDriveMixin` 在 `Entity.move` HEAD `cancel()`；原版 AABB 还要给渲染/别的实体/射线/方块交互用 |
| 地形碰撞体**也是**用原版方块碰撞形状建的 | `buildTerrainChunk`：满方块 → 体素格；非满形状 → `state.getCollisionShape(...)` 的复合盒。space 的 `PhysicalChunk.buildVoxelCollider/buildCuboidCollider` 同构 |

**唯一一处物理盒 ≠ 原版碰撞箱**：超人姿态（太空疾跑）——`MixinEntity.polymech$spaceBoundingBox`
把 `makeBoundingBox` 改成中心 1.6 的 0.6³ 头盒，于是 `getBbHeight()`=0.6 ⇒ 半高 0.3、中心偏移 1.6。

**而且确实存在"退回原版碰撞"的路径**（以前是静默的，本轮起会打日志）：

| 退回原因 | 位置 |
| --- | --- |
| 非太空维度且 64 格内没有物理体 → 整个世界关掉 | `ClientPhysics.tick()` → `shouldSimulate` |
| 玩家所在区块的地形碰撞体尚未就绪 | `drive()` → `hasTerrainAt` |
| 旁观者 / 创造飞行（**我们有意保留的偏差**：space 连飞行也是物理驱动） | `drive()` |
| 原生库不可用 / 物理世界未就绪 | `drive()` → `available()` |

所以以后要判断"到底谁在裁定碰撞"，只看这一行：
`[PolyMech] 玩家物理模式 → 物理接管：主刚体=5 兄弟=6 碰撞箱=0.60×1.80 中心偏移=0.90 …`。

### 交接时的验证状态

`compileJava` ✅（两处改动后各跑一次，都是 `EXIT=0`）；三套离线回归全绿（SMOKE / CLONE / KELVIN FORCE 上一次跑）；
服务端实跑 `Done (0.850s)`、四条线程各一次、`space_data 读取完成：太空世界 1 / 天体 20 / 地表世界 15`、
逐维度重力与 datagen 精确一致。

本轮追加：`PlayerWallProbeTest` 跑通（ABI 9），A–G 场景 + H 递推表 + I 甲板对照 + J 方向律 + K 抖动幅度全部输出；
H 表里 `注入=0.08, g=0` 一行与用户日志**逐位相同**，`注入=g` 三行全 `0.00`；
I 节修复前被抬 7.13 格 / 修复后 0.01 格；J 节摘掉注入后两个方向都 `反弹 0.00×`；
K 节顶住接触时法向抖动 14.9 / 48.8 / 120 mm（≈ `own × dt`，**space 同样存在**）。

**下一步是实机验证**（两件事各自独立）：
1. 进太空站在船/甲板上走走，`[弹飞诊断]` 的 `own.y` 应恒为 `0.00`（而非 1.57→31→…）；主世界逐位不变。
2. 持续顶住船/方块：抽搐应当消失或大幅减轻。若仍**明显**抖动，那就是 K 节那 5cm 的
   100Hz 物理极限环在 20Hz 采样下的残余 —— 属于第 23 节的"物理侧"，**不能在 Java 侧加阻尼/死区**；
   目前 evidence 是求解器参数两边同为 Rapier 默认值，所以只能回到链子/接触语义上找
   （见第 23 节末的架构对齐清单）。

### 增量 7：两个脏标记 mixin（已落地）+ 一个**故意不移植**的 mixin

| 类 | 行数 | 职责 |
| --- | --- | --- |
| `mixin/mps/MixinLevelChunk` | ~70 | `setBlockState` RETURN 后按维度分派脏标记（投影→`onBlockUpload`；普通服务端→`chunkManager.markDirty`；客户端→`onChunkBlockChanged`） |
| `mixin/mps/MixinClientChunkCache` | ~65 | 区块载入/卸载 → 客户端地形碰撞体重建/移除 |

**`MixinLevelChunk` 为什么必须挂在这里**：项目自己的破坏路径会显式调 `onBlockUpload`，
但那只覆盖**玩家破坏**。地皮上的方块还会被红石/活塞/机器/别的模组直接 `setBlockState` ——
不标脏，客户端就永远看不到（"服务端的灯亮了，客户端还是黑的"）。

**故意不移植 `MixinLivingEntity`（重要）**：MPS 的版本是
```java
if (this instanceof Player p && ServerPhysicalWorld.getPhysicalWorld(p.level()) != null)
    cir.setReturnValue(0.0);   // 把 getDefaultGravity 清零
```
这在 space/MPS 里是对的（玩家由**刚体**承担全部重力，所以原版重力必须清零）。
但**本项目的玩家物理是另一套**（用户四条硬规则第 3 条）：
`PlayerPhysicsBody.gravity_scale = 0`，玩家下落靠**原版 `getGravity`**
按 `PlanetDimensions` 倍率缩放后作为 `own` 喂进物理体。
两者是**互斥的设计**：照抄这个 mixin 会把玩家的原版重力清零 → 且因为
`CollisionPhysicalServerAction` 给**每个非投影维度**都建了 `ServerPhysicalWorld`，
条件恒真 → **玩家在全图都不会下落**。

> 这是照抄撞上的**第二个**"本项目没有/不该有"的前置假设（第一个是选体魔杖物品）。
> 处理方式一致：**不硬抄**，记录冲突与理由，留给 S6 决定玩家物理走哪一套。

### 关键结论：克隆体层**完整但无人填充**（必须知道）
实测：全仓库只有 `ServerPhysicalBody.loadFromTag` 里那一处 `new ServerPhysicalBody(...)`。
也就是说：

- 克隆层的服务端体层、投影、网络、线程、桥**全都到位**；
- 但**没有任何 gameplay 路径会创建物理体** —— 只有"从 `physical_bodies` 存档恢复"，
  而存档里永远不会有东西（因为没人创建、也就没人保存）。

本项目**既有的**体管线（`PhysicsBodyTracker` + `physics_body_*` 包 + 项目自己的
`PhysicsBodyInteraction`/`ProjectionManager`）才是当前**真正在跑**的那一套，
它用自己的表示与 id 空间，不经过克隆层的 `PhysicalBody`。

**所以现在的状态与 kelvin 当初一样：完整、自洽、但尚未取得权威。**
要让"船进太空"真的可用，必须做 **S6 的第一次切换**：
把"组装一个结构 → 变成物理体"的创建动作接到 `ServerPhysicalBody` 上
（或者反过来，把克隆层删掉、只保留项目的管线）。

这一步**需要你拍板**，因为它是不可逆的方向选择：两条管线同时"创建"同一个物体会互相打架，
而项目的管线现在是能跑的、克隆层是新的。

### 一处需要单独立项的分支：选择魔杖

`PhysicalBodyInteractionClient` 有 4 处判断 `SpaceModItems.PHYSICAL_SELECTION_WAND`
—— 那是 **space 模组的玩法物品**（注册在 `SpaceModItems` / `SpaceModCreativeTab`，
带模型/贴图/语言项），不是 MPS 的物理机制，也**不在 `PhysicalBodySpaceEvent` 的关键路径上**
（关键路径只需要它的 4 行静态槽 `syncMiningProgress`）。

照抄到这里时这一分支有三种处理，**需要单独决定**：
① 连物品一起移植（物品注册 + 模型 + 贴图 + 语言）；
② 只移植"挖矿反馈"半边，魔杖分支记为差异；
③ 整个跳过 `PhysicalBodyInteractionClient`，把突破进度接项目既有的 `PhysicsBodyBreakProgressPacket`。

> 记录理由：这不是"缩范围"，而是**照抄撞上了一个本项目没有的外部内容**（space 的物品）。
> 硬造一个魔杖物品正是本项目反复踩过的"自作聪明"。

## 二十四、天体移动"肉眼可见"：space 的旋钮是 **dt**，不是步数（已对齐）

### 用户的问题
> "我记得 space 的测试视频里有肉眼可见的速度测试的，他们是怎么做星球移动的"

### 答案：一行赋值

`SpaceModCommand.orbitSpeed()`（`decompiled-space/0.1.3/org/deep_space_studio/space/command/SpaceModCommand.java:71-85`）：

| 命令 | 动作 |
| --- | --- |
| `/space physical orbit speed` | 打印 `core_tick_time / CORE_TICK_TIME.get()` = 当前倍率 |
| `/space physical orbit speed <倍率>` | `OrbitPhysicalThread.core_tick_time = CORE_TICK_TIME.get() * 倍率;` |
| `/space physical orbit pause <bool>` | `OrbitPhysicalThread.pause = state;` |

而线程循环（0.1.3 `OrbitPhysicalThread.java:52-57`）**每周期恰好一步**：

```java
for (SpaceWorld spaceWorld : ServerSpaceWorld.getAllSpaceWorld()) {
   spaceWorld.up();
   spaceWorld.step(OrbitPhysicalThread.core_tick_time);
}
```

于是 `模拟时间流速 = core_tick_time × core_tick_speed`：100Hz × 0.01s = 1×。
`/space ... speed 200` ⇒ dt = 2.0s ⇒ **每 10ms 推进 2 秒轨道时间** ⇒ 视频里那种看得见的公转。
配置注释也直说了：`CORE_TICK_TIME`"当目标速度为 1.0 时该值应为 (1 ÷ Core_TICK_SPEED)"
—— **dt 本身就是速度旋钮**。

### 为什么偏偏用这种方式（本项目的"思考题"）

1. dt 本来就是"一步走多久"。把倍率乘在 dt 上，每周期**仍然只有一次积分调用**
   ⇒ CPU 开销与倍率**无关**，几万倍也是常数开销。
2. 反过来，"每周期多跑 N 步"让 CPU 正比于 N。这正是本项目原实现失败的原因：
   实测 `/polymech kelvin warp 200` 之后线程被自己设的 CPU 预算截断 ——
   用户原话"开两百倍速好像没啥效果"，倍率名存实亡。
3. 代价 space 自己也认：dt 大于 `1/tick_speed` 就不是真实轨道了。
   所以它是**验收/演示旋钮**，不是正常游玩路径；看真实轨道必须设回 1×。

### 本项目改了什么（把偏离拉回方法一致）

| 项 | 改前（我写的子步方案） | 改后（照 space） |
| --- | --- | --- |
| 机制 | `timeScale` 整数 + 每周期循环 N 步 | 乘 `core_tick_time`（dt） |
| CPU | 正比于倍率，超出预算即被截断 | 恒定（每周期一步） |
| 失败模式 | 倍率名存实亡（"看不出效果"） | 立即可见；代价是精度 |
| 命令 | `/polymech kelvin warp <1..20000>` | `/polymech kelvin speed [<倍率>]` + `/polymech kelvin pause <bool>` |
| 诊断 | `steps` / `stepsPerSecond`（累计步数） | `tick`（应恒等于 100Hz）+ 打印 dt 与倍率 |

`OrbitPhysicalThread` 里 `timeScale` / `steps` / `stepsPerSecond` / `STEP_BUDGET_NANOS`
全部删除，循环回到 space 的形状。`ModCommands.kelvinWarp(int)` → `kelvinSpeed(double)`
（查询分支用 space 的原式 `base == 0.0 ? 1.0 : dt / base`）。

**两处有意的加固（行为不变，已写进类注释）**：`core_tick_time` 与 `pause` 在 space 里是普通
静态字段，却由**命令线程写、物理线程读** —— 我们加 `volatile`。这不是设计差异，
是补原版的可见性缺口（"日志不是架构"，数据竞争同理）。

**另一处必须先确认的细节（否则命令会静默失效）**：原生 cosmos 的 dt 是**逐步传入**的
（`cosmosWorldStep(handle, dt)`）；`cosmosWorldCreate` 收到的那个 dt 被忽略
（只留 `substeps` / `far_field`，`lib.rs:2226-2232`）。两条积分路径（原生 / 纯 Java 兜底）
都每步读当前 `core_tick_time` ⇒ 运行中改 dt **立刻生效**。

### 验收（游戏内两条）

```
/polymech kelvin speed 100   # dt = 1.0s ⇒ 模拟流速 100× 实时
/polymech kelvin             # 看「时间倍率=100.00× | dt=1.000 s」与「心跳=100 步/秒（实测 100）」
```

判断依据不变：`位置权威=开` 时，两次 `/polymech kelvin` 之间 `gamePos(格)` 的数值变化幅度
应当**正比于倍率**（不再是"被 CPU 截断"的假象）。若数值在变而天空不动 ⇒
**那是第 25 节的真 bug（渲染路径漏调 `syncMoveData`），已修**。

### 编译状态
`./gradlew compileJava` ✅（BUILD SUCCESSFUL）。

## 二十五、"星球不动"的真根因：实现体照抄了，**调用点漏了**（已修）

### 症状（极具误导性）

- `/polymech kelvin`：服务端积分在跑、`kelvin=(...)` 数字在变、`位置权威=开`、`时间倍率=N×`；
- 天上的星球**纹丝不动**；日志无异常栈、原生 ABI 9 正常、`space_data` 20 个天体齐、物理线程已启动。
- 于是判断"也许他压根没工作"。**其实一直在工作 —— 只是没人把结果搬给渲染。**

### 排查路径（可复用）

1. **线程真跑了吗** → 日志 `天体物理线程已启动`。注意 `TimerTask` 抛异常会**静默杀死整颗定时器**
   （只在 stderr 留栈），所以先查异常。
2. **积分器有东西可积吗** → 看生成的 `space_data/**/object/earth.json`：
   `mass=5.97e24`、`speed≈3e4 m/s`、`compute=true` ⇒ **只要跑一步就该动**（且量级一眼可见）。
3. **权威链的 id 契约** → `RealAstroData.id` = `sun/earth/mars…` 与 `space_data/**/object/<名>.json`
   的文件名**逐字一致**；`KELVIN_SPACE_ID = poly_mech:space` 与数据包目录名一致。
4. **grep 调用点** ← **真正的分水岭**。

### 根因

`ClientSpaceWorld.syncMoveData()`（把网络缓冲区的位姿搬进显示世界）**全仓库 0 个调用点**
（只有它自己的定义与四处注释在提它）。而 `SpaceWorld.kelvinPos()` **优先读客户端显示世界**：

```
网络每 tick 只写 bufferSpaceWorld（SyncCelestialBodyMoveBatch.handle，入队 moveTo）
显示世界只在 SyncCelestialBodyCreate 时被填过一次（= 创建时的快照）
                     ↓  缺 syncMoveData()
显示世界永远停在那个快照 ⇒ gamePos 的 X/Z 恒定 ⇒ 星球不动
```

### 为什么这是"照抄漏了一步"，不是设计问题

space 的调用点在 `org/cn_grass_block/sunshine/render/SpaceRenderer.java:106`：
`init(event)` 里"确认 `spaceWorld != null` 后、**消费任何位置之前**的第一件事"就是
`ClientSpaceWorld.syncMoveData()`。我们照抄了**实现体**（与 space 逐行相同），
却没有照抄**调用点** —— 整条链断在最后一米。

> 教训：克隆层最容易的静默失败不是"类没写"，而是"**类写全了、没人调**"。
> 症状是"某个功能压根不动"，此时第一步就该 `grep` 那个关键方法的调用点，
> 而不是继续往实现体里找 bug。（本项目已第二次撞上这一类：第一次是"克隆体层完整但无人填充"。）

### 修法（一处，照 space 的调用点）

`SpaceRenderer.renderSpaceBodies()` 中，`PlanetRenderObjectFactory.refreshPositions()` **之前**：

```java
if (ClientSpaceWorld.getSpaceWorld() != null) {   // 判空照 space 的调用点；syncMoveData 自身不判空
    ClientSpaceWorld.syncMoveData();
}
```

顺序不能换：`refreshPositions()` 会走 `gamePos → kelvinPos → 显示世界`，必须发生在搬运之后。

### 顺带补的诊断（下次不用再猜）

`SpaceWorld.kelvinPos()` 有**三个症状完全相同**的静默回退分支（未开权威 / 显示世界没有该天体 /
服务端世界没有该天体）。现在它记录来源，`/polymech kelvin` 会打印：

```
渲染取值的来源 = 客户端显示世界 | 服务端（积分） | 静态（未启用/未就绪）
```

有这一行，"天体不动"立刻能分成"积分没跑"还是"读了一份冻结的影子世界"。

### 验收

`位置权威=开` + `/polymech kelvin speed 100` ⇒ 星球应当一眼可见地动；
`/polymech kelvin` 里 `kelvin=` 与 `gamePos(格)=` **两个数字同时变**，且来源显示"客户端显示世界"。

### 编译状态
`./gradlew compileJava` ✅（BUILD SUCCESSFUL）。

## 二十六、"速度比和现实一样吗"—— 时间比**是**真的，轨道形状**是圆轨道近似**（已量化）

用户实测天体终于会动之后的第一个问题：**"实际速度比和现实一样吗？"**
答案要分成两层，两个结论都有硬证据。

### 第一层：时间比 —— 1× 就是 1 秒模拟 / 秒现实（已证死）

原生积分每次调用的**总推进量恰好等于 dt**（`native/.../lib.rs` CosmosWorld::step）：

```rust
let n = self.substeps.max(1);
let h = dt / n as f64;      // ← substeps 只把 dt 内部细分，不放大时间
```

所以调用点传的 `substeps = 4` 只是把 10ms 内部切成 4 个 2.5ms 子步（精度），**不是 4 倍时间**。
再叠加 `OrbitPhysicalThread` 的 100Hz ⇒ 每现实秒推进 `100 × dt` 模拟秒：

- `dt = 0.01`（配置基准）⇒ **1.0×（= 现实速度）**；
- `/polymech kelvin speed N` ⇒ `dt = 0.01N` ⇒ **N× 现实速度**。

**游戏内自证**：`/polymech kelvin` 那行 `心跳=100 步/秒（实测 N）` 里的 N 是**每秒真数出来的**，
`模拟时间流速 = dt × 心跳` 就是这个比值 —— 不需要先信任何假设。

### 第二层：轨道形状 —— 初始速度是**构造的圆轨道速度**，不是真实星历

`ModSpaceDataProvider.circularVelocity()` 生成数据包里的 `speed`：

```java
double speed = Math.sqrt(KelvinConstants.GRAVITATIONAL_CONSTANT * centralMassKg / distance);
// 方向 = normalize(r × Y)：黄道面内、vy 恒为 0
```

即"在该天体的**快照半径**上做一个圆轨道"。于是

```
周期 = 2π √(r_snapshot³ / μ)      ⇒ 与真实周期差 (r_snapshot / a_real)^1.5
```

**离线实测（vis-viva，由数据包自身的 pos/speed/质量反推；G = 6.6743e-11）**：

| 天体 | r(快照, 1e6 km) | v(km/s) | a(AU) | T_sim(d) | T_real(d) | 差 |
| --- | --- | --- | --- | --- | --- | --- |
| 水星 | 46.768 | 53.28 | 0.3126 | 63.84 | 87.97 | **−27.4%** |
| 金星 | 108.10 | 35.04 | 0.7226 | 224.34 | 224.70 | −0.16% |
| 地球 | 147.79 | 29.97 | 0.9879 | 358.62 | 365.26 | −1.8% |
| 火星 | 204.98 | 25.45 | 1.3702 | 585.74 | 686.98 | **−14.7%** |
| 木星 | 811.02 | 12.79 | 5.4213 | 4609.9 | 4332.6 | **+6.4%** |
| 土星 | 1368.0 | 9.85 | 9.1445 | 10098.8 | 10759.2 | **−6.1%** |
| 天王星 | 2881.2 | 6.79 | 19.259 | 30867 | 30688 | +0.58% |
| 海王星 | 4467.2 | 5.45 | 29.861 | 59593 | 60195 | −1.0% |
| 冥王星 | 5906.4 | 4.74 | 39.482 | 90601 | 90560 | **+0.05%** |
| 月球 | 0.302 | 1.149 | — | 19.11 | 27.32 | **−30.0%** |
| 木卫一/二/三/四 | — | — | — | 1.769/3.552/7.155/16.691 | 同 | **+0.01~0.03%** |
| 土卫六/二 | — | — | — | 15.948/1.371 | 同 | +0.02~0.04% |
| 火卫一/二 | — | — | — | 0.319/1.263 | 同 | ±0.04% |
| 冥卫一 | 0.020 | 0.211 | — | 6.762 | 6.387 | +5.9% |

**怎么读这张表**：

- **所有"快照半径 ≈ 真实半长轴"的天体误差都在 0.01~0.05%** —— 木卫、土卫、火卫、冥王星。
  这说明单位制（m/kg/s）、G、积分器、时间比**全都是对的**。
- 误差大的那几颗，误差值**精确等于 `(r/a)^1.5 − 1`**：水星/火星/月球的快照取在**近拱点**
  （水星 46.8e6 km vs a=57.9e6；火星 205e6 vs 228e6；月球 302,000 km vs 384,400 km），
  被当成了圆轨道 ⇒ 绕得快；木星取在远拱点（811e6 vs 778e6）⇒ 绕得慢。
  冥卫一 +5.9% 还多一层原因：μ 只用了冥王星质量，而真实周期由**冥王+冥卫合质量**决定。

**另外两处既定的近似（不是 bug）**：

1. **倾角丢失**：速度方向恒在黄道面（`vy = 0`），真实水星 7°、冥王星 17° 的轨道倾角没有体现。
2. **渲染侧 Y 本来就被压平**：`SpaceWorld.gamePos` 只替换 X/Z，Y 一律用静态规则
   （kelvin 的真实 Y 跨度 ±1.6e6 格，直接画会把天体扔出维度）—— 所以就算修了速度，
   上下起伏也不会出现。

这与第 19 节早就记录过的"静态 J2000 与圆轨道近似之间的偏差（每秒数百~数千格）"是**同一件事**，
不是新 bug：kelvin 现在积分的是"以 J2000 位置为起点、按圆轨道速度出发"的轨道。

**顺带的可玩性数字**：`speed 20000`（dt=200s/步）时，地球跑完一圈约 26 分钟、
水星（模拟周期 63.8 天，本来就偏快）约 4.6 分钟 —— 想肉眼看"轨道能不能闭合"，水星最快。

### 要"和现实一样"的话，是一个**数据源决策**（待拍板）

正解不是自己改轨道公式，而是把**初始状态换成真实星历**（J2000 的位置**和**速度），
并让 `RealAstroData` 带上速度字段（现在只有位置）：

- 现在 `RealAstroData.realPositionAt(secondsSinceJ2000)` **忽略参数**、直接返回定值 ——
  项目里根本没有星历传播，"真实"目前只是 J2000 快照。
- 速度必须取**公开星历**（JPL/NASA Horizons 之类的 J2000 状态向量）。
  **不能抄 space 数据包里的数字** —— space 是 ARR，其 JSON 属其数据，clean-room 规矩同样适用于数据。
- 代价：改的是"看得见的轨道"，会和现在的观感不同；且倾角修好后 Y 仍会被 `gamePos` 压平，
  所以收益主要在**相位与周期**（例如水星从"27% 偏快"变成准确）。

> 记录理由：这一条和第 19 节那张"迁移表"是同一类决定 —— **改变玩家可见行为**，需要你点头。
> 只做一半（比如只改速度不改星历位置）会比不做更糟：位置与速度不来自同一历元，轨道就闭不上。

## 二十七、地表天空 / 取证日志 / "走动卡死"（2026-09 实测回合）

### 27.1 「站在行星地表看天上的行星」= **功能没做**（不是坏了）

我把它列成待测项是**错的**：那不是"该动没动"，而是**根本没有渲染**。

- `SpaceRenderer.onRenderLevelStage` 第一行就是
  `if (!mc.level.dimension().equals(PlanetDimensions.SPACE)) return;`
  ⇒ 星球层（天空盒 + 天体）**只在太空维度绘制**，站在火星地表抬头是原版天空；
- `ClientCelestialWorld` 在整个仓库里**只被网络包引用**（`SyncCelestialWorldCreate` /
  `SyncCreateEnd`），没有任何渲染消费者 —— 这正是第 25 节那类"类写全了、没人调"。

**space 是有这个功能的**：它的 `SpaceRenderer.init()` 里有
`celestialWorld = ClientCelestialWorld.getCelestialWorld()` 分支，用
`celestialWorld.getSpacePosFromWorldPos / getRotateFromWorldPos` 把"地表维度里的相机"
换算到宇宙坐标系里，于是站在行星表面也能看到真实天体位置与朝向。
我们移植了 `CelestialWorld` 的数据层，**没移植这条渲染分支** ⇒ 记为待立项（不是 bug）。

> **2026-09-20 已实现**：`SpaceRenderer` 不再用 `dimension == SPACE` 一刀切，
> 而是"太空维度 → 原路径；地表维度 → 该维度有 `ClientCelestialWorld` 就画天体"。
> 相机帧按 space 的做法换成 `getSpacePosFromWorldPos` + `getRotateFromWorldPos`；
> 太空维度仍走线性 `ZOOM` + 无额外旋转，**已验收的太空渲染不受影响**；
> 大气/泛光后处理仍只在太空维度（它绑定太空的深度快照与投影约定）。
> **待肉眼确认**：地表那层朝向的合成顺序（`rotation(cameraRot) × rotation(spaceRotation)`）
> 是按 space 的"cameraPos + spaceRotation 一起交给渲染"反推的，角度若不对只影响地表的天空朝向。

### 27.2 取证日志（用户要求：人在游戏里、日志在外）

新增 `mps/kelvin/KelvinDiagnostics`，挂在**客户端 tick**（`ClientPhysicsDriver.onClientTickPost`）：

| 时机 | 内容 |
| --- | --- |
| 每 5 秒 | `维=… 玩家=… 模拟t=…s dt=…(=N×) 步/秒=… 暂停=… 来源=…` + `earth/mars/jupiter` 的 `gamePos(格)` |
| 换维度 / 进世界 | `★换维度/进世界 → 维度（玩家坐标）` + **离玩家最近的三颗天体与距离** |

- 为什么挂客户端：渲染取的是**客户端显示世界**，这里打的就是"眼睛看到的那个值"，并且打印来源
  （第 25 节那次"服务端在动、天上不动"就是靠这个区分开的）。
- 为什么带"最近天体+距离"：**传送落点**测试靠它自证 —— 落对了，最近的就是刚传过去那颗、
  距离在"半径 ~ 2.2×半径"量级；落空处，距离立刻不对。不需要再来回问。
- `OrbitPhysicalThread.simulatedSeconds`（新增，模拟秒单调时钟）与日志时间戳对比
  = 时间比的现场自证。

### 27.3 那次"走动卡死"：证据与已修的真 bug

**现场证据**（`run/logs/latest.log` + 进程快照）：

| 观测 | 值 |
| --- | --- |
| 进程 | PID 31028，工作集 **8.9 GB**，332 s CPU / ~248 s 墙钟（多核在烧） |
| 日志 | **无异常栈、无崩溃报告、无 jstack 机会**（进程已被关掉）；卡死前最后一条是 `Saving and pausing game...` |
| 唯一的 WARN | `物理步进被阻塞：间隔 70 ms / 本次耗时 0 ms` |

**① 我自己的探针报了假警报（已修）**：另一处 `间隔 112739 ms` 不是卡死 ——
112.7 秒正好等于"上个世界停止 → 新世界启动"之间玩家待在**主菜单**的时长。
`PhysicsStepThread.lastStartNanos` 是静态字段，线程 stop/start 时没归零。
**探针骗人比没有探针更糟**（会让人去追一个不存在的卡死），已在 `startIfNeeded()`/`stop()` 里归零。

**② 找到一个真的客户端线程长操作（已修）**：`ClientPhysics.updateTerrain` 原本写的是

```java
if (moved || terrainDirty.contains(key) || !terrainBodies.containsKey(key)) buildTerrainChunk(...);
```

`moved` = **水平跨区块** ⇒ 玩家**每走 16 格**就把视野内 9 个区块全部重新体素化一遍
（每块最多 16×16×128 格 = 32k 次取方块 + 原生体素单元插入），而地形根本没变。
这是纯浪费，且发生在**客户端线程**上 ⇒ "走着走着突然一顿"。
改成：只重建 `terrainDirty` 或**尚未建过**的区块；只有**竖直带**整体移动了才全量重裁
（`lastTerrainBandY` + `BAND_REBUILD_STEP = 16`）。
顺带修掉一个**漏洞**：原来竖直移动完全不触发重裁（带是"玩家当时的 Y ± 64"裁的），
飞上飞下会在旧带留空洞。

**③ 新增两条取证（下次卡死必有据可查）**：

- `[PolyMech] 地形体素化耗时 N ms（重建 k 个区块，带移动=…）` —— 单次 ≥ 50 ms 记 WARN；
- `[PolyMech] 物理规模 维=… 堆=u/m MB 原生 刚体=… 碰撞体=… 地形区块=…`
  每 10 秒一行，堆 > 85% 记 WARN。碰撞体数是 **Rapier 世界的真实计数**
  （`worldGetColliderSetSize`），不是我们的记账 —— 走路/传送过程中若单调上涨，
  **原生泄漏当场坐实**；不涨就排除它，去看 GC 或体素化。

> 8.9 GB 远超 Java 堆上限 ⇒ 大头在**原生**（Rapier 的体/碰撞体）。
> 这就是为什么第 ③ 条必须打**真实计数**，而不是只看 `-Xmx`。

**④ 尚未定论**：这次没有拿到栈（进程被关）。下次复现请**别急着关游戏**，
让它卡住，然后告诉我 —— 我可以对活着的进程取 `jstack`（本机
`C:\develop\JDK\bin\jstack.exe` 可用），那是最直接的一刀。

### 27.4 「每加载一个区块卡一下」= 克隆层**少了调色板快路径**（已修，用户判断正确）

用户复现后的描述非常准：*"不是彻底卡死的类型，只是每次加载区块卡一下"*，
并怀疑是"为了衔接无缝进入太空改的、而且改得不彻底"。**两条都对。**

对照 space 0.1.3 `PhysicalChunk.getFullBlockArrayInChunkQuickly`：

| | space | 我们（修前） |
| --- | --- | --- |
| 整段单一方块（`bits == 0`） | `Arrays.fill(fullBlocks, true)` —— **零次逐格** | 逐格 4096 次 |
| 非单一 | 先读一次调色板算 `paletteIsFull[]`，逐格只剩 `storage.get(j)` | 每格 `getBlockState` + `isCollisionShapeFullBlock` |

**一个区块 = 24 段 × 4096 = 98,304 次判定**，而且这段代码**同步跑在
`MixinClientChunkCache.replaceWithPacketData` 的 RETURN**（区块真的到手）里
⇒ 现象就是"每加载一个区块卡一下"。9800X3D 也不该卡 —— 这不是性能不够，是白干。

而且 `ClientPhysicalWorld` 按 §20 的记录**每个非投影维度都会建**，克隆层至今
**没有任何物理体**（"完整但无人填充"）⇒ 这份开销目前**完全无用**。

**修法**：移植等价快路径，但**用公开 API 而不是手搓 `FriendlyByteBuf` 里的调色板
二进制格式**（那是会随版本变的内部布局）：

```java
BlockState uniformState = section.getBlockState(0, 0, 0);
if (!section.getStates().maybeHas(s -> s != uniformState)) {   // 整段单一？
    if (全格) { Arrays.fill(fullBlocks, true); return; }        // 与 space 的 bits==0 分支等价
    ...
}
```

`maybeHas` 只遍历调色板项（单一项 O(1)）；`GlobalPalette` 会**保守**返回 true ⇒
自动回落逐格路径，不会误判成"单一"。

**同时加了拆分计时**（`[MPS] [Terrain]`，单区块 ≥5 ms 记 WARN）：
`Java 扫描 X ms / 原生建体 Y ms；满格 N 格，复合盒 M 个`。
这条是必须的 —— 如果原生那条（`ColliderBuilder::voxels` 一次塞进几万个立方）才是大头，
优化方向完全不同（space 里那个 `heightmapColliderBody` 就是为此存在的），不能靠猜。

**仍待决定（S6 范围，不擅自动）**：既然克隆层没有物理体，是否可以
**根本不建**这些地形碰撞体（或只在真有物理体的维度建）—— 能直接消掉整份开销，
但属于 §20 那"两套系统二选一"的决定。

### 27.5 实测数据 + **日志洪水才是"走走卡卡"的直接元凶**（已修）

装了计时后的一场真实会话（`poly_mech:venus`，50 秒）：

```
[MPS] [Terrain] 区块地形重建 8 ms（Java 扫描 8 ms / 原生建体 0 ms；
                满格 42616 格，复合盒 0 个，段数 24) @ [-14, -25]
```

**全场只有 1 次超过 5 ms，且原生建体是 0 ms** ⇒ 克隆层地形碰撞体<b>不是</b>当前的主要卡顿源
（成本全在 Java 扫描，而 8 ms 是"非单一整段"走慢路径的那一档 —— 快路径只覆盖整段单一）。
顺带确认 `MAX_VOXELS_PER_COLLIDER = 2_097_152`，满列（≤98k）不会触顶。

**但同一场日志里 2321 行，其中 1935 行是我们自己打的（≈39 行/秒）**：

| 行数 | logger | 原因 |
| --- | --- | --- |
| **1344** | `PolyMech/Physics/Client/` | `noteMode` 的边沿触发被击穿：**同一个状态有两个调用点、理由串不同**（`tick()` 说"非太空维度"，`drive()` 说"物理世界未就绪"），逐 tick 交替 ⇒ 每 tick 一行 |
| **591** | `PolyMech/Physics/DriveIn/` | 排查"弹飞"时加的**临时探针**，弹飞已在第 22 节修掉，它成了纯噪音 |

这是**纯自伤**：客户端线程上每秒几十次字符串格式化 + 同步日志 I/O，而且
`DriveIn` 只在**移动**时触发 —— 正好解释"走起来卡、停下来不卡"。

**修法**：`noteMode` 按**粗粒度**（括号前那段：关闭 / 原版驱动 / 物理接管）判变化，
同粗粒度下的理由变化最多 2 秒报一次；`DRIVE_PROBE` 默认 `false`（需要时一行打开，
不删 —— 诊断工具值得留）。预计日志量从 ~39 行/秒降到个位数。

### 27.6 地形碰撞体：space 怎么处理、我们到底需不需要

**space 的做法（`CollisionPhysicalServerAction:24-45` + `PhysicalWorldUpdateSyncEvent:27-63`）**：

- **服务端**：开服时给**每个非投影维度**建 `ServerPhysicalWorld`（`newPhysicalWorld`），
  不是只给太空维度 —— **这一点我们照抄了，没有偏差**；
- **客户端**：玩家进维度时按维度下发 `SyncPhysicalWorldCreate`；
  **该维度没有物理世界就下发 null** ⇒ 客户端 `ClientPhysicalWorld.init()` ⇒
  `MixinClientChunkCache` 里 `getPhysicalWorld()` 为 null ⇒ **一个区块碰撞体都不建**；
- **有物理世界的维度**：客户端**每收到一个区块就体素化整列**（`MixinClientChunkCache` 与
  space 逐行同形，连注释里的"16×384×16"都对得上）；
- 差别只在**扫描成本**：space 有调色板快路径（§27.4），我们修前没有、修后也只在
  "整段单一"时走快路径，非单一整段仍是逐格 —— 这就是那 8 ms。

**所以"我们真的需要吗"的答案是分层的**：

1. **现在（克隆层没有任何物理体）**：不需要。这份开销纯浪费 ——
   用户的原话"我们现在有物理体的维度就只有太空维度"是对的，
   而且**太空维度的地形是虚空（满格 0 格）** ⇒ 在太空里建地形几乎不花钱。
2. **将来（S6 把创建动作接到 `ServerPhysicalBody` 之后）**：需要，
   因为物理体出现在哪个维度、就在哪个维度要地形 —— 这正是 space 给**每个**维度都建的原因。
3. 所以"只在有物理体的维度建"**现在**能省，但它与 S6 的第一步绑定：
   接上创建之后，"有物理体的维度"会变成动态的，那时需要的是
   **兴趣点 + 迟滞**（space 的 `PhysicalChunkManager` 服务端就是这么做的，我们也照抄了），
   而不是一次性开关。**这一条要你拍板，我不擅自动**。

**另一个更安全的省法（不涉及 S6）**：既然 `ServerPhysicalWorld` 在太空维度也建、而太空是虚空，
真正的浪费集中在**地表维度**。若你只想先止血，可以让客户端在
"该维度既没有物理体、又不是太空维度"时**暂不体素化地形**，
并在第一个物理体创建时补建（这正好就是 `PhysicalChunkManager` 的兴趣点逻辑）。
要不要做，同样等你一句话。

### 27.7 日志顺手抓出的真 bug：**地表维度上 `gamePos` 根本不更新**（已修）

同一场 `poly_mech:venus` 会话里，位置日志显示来源是"客户端显示世界"、
`步/秒=100`、`暂停=false`，但 50 秒内地球的 `gamePos` **一位都没变**：

```
20:13:40  earth gamePos(格)=(1667868.2, 1087.6, -14684996.5)
20:14:23  earth gamePos(格)=(1667868.2, 1087.6, -14684996.5)   ← 1× 下 50 秒应走 ≈150 格
```

**原因**：`ClientSpaceWorld.syncMoveData()` 只挂在 `SpaceRenderer` 里，而那个渲染器
第一行就是 `if (!dimension.equals(SPACE)) return;` ⇒ **站在地表维度时，
"缓冲区 → 显示世界"这一步永远不发生**，显示世界停在 `SyncCelestialBodyCreate` 的快照。
而 `SpaceWorld.gamePos` **优先读显示世界** —— 于是地表上的渲染、星图、HUD、
以及**从地表发起的传送落点计算**全都拿到冻结的位置。

space 不会这样：它的 `SpaceRenderer.init()` 在**所有维度**都跑（靠 `CelestialWorld`
把地表相机换算到宇宙系），所以它的 `syncMoveData()` 到处生效 ——
这是第 25 节那个漏调用点问题的**同族第二例**：把"每帧同步"挂在一个**有维度守卫**的渲染路径上。

**修法**：在客户端 tick（`ClientPhysicsDriver.onClientTickPost`，维度无关）同步一次；
`SpaceRenderer` 里那次保留（渲染路径需要"消费位置之前"的同一帧同步，
`up()` 与 `*Direct` 拷贝都是幂等的，重复调用无副作用）。

> 又一次印证第 25 节的教训：**"某个值不动"先查那个同步点有没有调用者、
> 以及调用者是否被守卫挡住**。同一个坑，两处。

### 27.8 "未响应"的现场转储：不是死锁，是**克隆层空转吃掉半个核**（已掐）

2026-09-20 20:29 对**活着的**卡死进程取转储（`C:\develop\JDK\bin\jstack.exe -l`，
dump 为 UTF-16LE，需 `[System.IO.File]::ReadAllText(..., Unicode)` 转 UTF-8 再用 ripgrep）：

| 线程 | 状态 | 读法 |
| --- | --- | --- |
| `Render thread` | RUNNABLE @ `glfwSwapBuffers` → `Window.updateDisplay` | 卡在**提交帧/等 GPU**；Windows 由此判"未响应" |
| `ClientCollisionPhysicalThread-step` | park（空闲），但**累计 41.4 s CPU / 83 s 墙钟** | 克隆层客户端物理世界**一半时间是满载的** |
| `ServerCollisionPhysicalThread-step/-chunk` | park | 空闲 |
| **`PolyMech-Physics-Step`（项目自己那套）** | **不在线程表里** | venus 上 `shouldSimulate=false` ⇒ 按设计已停 |
| `at com.mss.polymech` 帧 | **0 个** | 没有卡在我们的 Java 代码里，也**没有死锁** |
| 内存 | 2.3 GB（上次是 8.9 G） | 内存不是本次原因 |

**结论**：项目自己那套物理在地表维度按设计关着，而**克隆层那套**在 100 Hz 扛着
"每个已载区块约 4 万格体素"的地形，而该世界 `getAllPhysicalBody()` **为空** ——
半个核纯粹为一份没人使用的地形付账。CPU 被吃掉 ⇒ 渲染线程排队 ⇒ 帧提交拖延 ⇒ 系统判"未响应"。

**所以"每维度都建地形碰撞体"的代价不在建（实测单区块 8 ms），而在之后每一步都要扛着它。**
space 也扛（它给每个非投影维度都建世界），但 space 里物理体**真的会出现**（船），
我们这里还没有 —— 这正是 S6 那条"克隆层完整但无人填充"的代价面。

**已改**（照用户的判断动手）：`ClientPhysicalWorld` 新增 `terrainNeeded()`
= `!getAllPhysicalBody().isEmpty()`：

- `onChunkReceived` 在**没有物理体**时**不体素化**（节流 5 秒记一行，免得又变成刷屏源）；
- 覆写 `addPhysicalBody`：**第一个物理体出现时**补建玩家周围 3×3 区块
  （与项目自己那套 `TERRAIN_RADIUS` 同量级），其余靠后续"到手/改块"事件补齐；
- 完整的"兴趣点 + 迟滞"仍是 space `PhysicalChunkManager` 那套，留给 S6 接创建动作时一并落地。

## 二十八、真实轨道：从"构造圆速度"改成"真实要素椭圆"（已落地并核验）

### 28.1 起因：两个用户一眼看出的现象

1. **地表天空里所有天体连成一条线** —— 两个独立原因：
   - `staticGamePos` 把日心天体的 **Y 强制为 0**（除地球；卫星继承母星），
     而地表相机是用 `CelestialWorld` 映射到宇宙系（**带真实 Y**）的 ⇒ 天体全在一个平面里；
   - 初速度是**构造**的圆轨道速度（`|v|=√(GM/r)`、`r × Y`、`vy=0`）。
2. **"太空维度下的也是真实轨道吧？"** —— 不是。位置本来就是真实星历，
   只有**速度**被换成了"快照半径上的圆速度"⇒ 轨道是**正圆**（水星 e=0.206 完全没体现）、
   **共面**（vy=0）、**周期按快照半径**（水星 −27%、火星 −15%，见 §26）。

### 28.2 对表（对出来的，不是推出来的）

怕自己推错，直接拿 space 数据包里**公开星历的真实状态向量**当参照（仅作行为验证）：

| 检验 | 我们 | 真实 | 结论 |
| --- | --- | --- | --- |
| 地球（i≈0，面内方向） | `r × ŷ` = (0.9946, 0, 0.1035) | (0.9953, 0.0001, 0.0973) | 点积 **1.0000** ⇒ 顺行符号正确 |
| 水星（i=7.005°，面外分量） | 法线取 `+sin i·cosΩ` ⇒ vy/\|v\|=0.0801 | 0.1027 | ✗ |
| 同上，法线取 **`−sin i·cosΩ`** | ⇒ 0.1003 | 0.1027 | ✓ |

> 教训：**面外分量的符号是靠两个独立样本对表钉死的**，不是推出来的。
> 一开始我还据此误报过"我们所有轨道逆行"——那是我自己的算术滑了一格，已作废。

### 28.3 实现（`ModSpaceDataProvider`）

```
n̂ = (sin i·sin Ω,  cos i,  −sin i·cos Ω)       // 公开 JPL 近似要素（J2000）
n̂ ← normalize(n̂ − (n̂·r̂) r̂)                    // 正交化：轨道面精确包含当前位置
t̂ = n̂ × r̂                                      // 面内顺行切向
p = a(1−e²),  h = √(μp),  cos ν = (p/r − 1)/e
v_r = (μ/h)·e·sin ν ,  v_t = h/r               // 径向 + 切向 ⇒ e 由要素精确给定
v = v_r·r̂ + v_t·t̂
```

**为什么要拆径向**：若强制 `v ⊥ r̂`，当前位置必然是拱点，涌现出的 e 只能是 `|r/a − 1|`
（实测天王星 0.004 vs 真实 0.047、冥王星 0.000 vs 0.249）。
**为什么要正交化**：位置与要素表不可能严格共面（水星离面 0.06°），不处理就会把那点残差
放大进 e（0.206 → 0.186）。

**范围**：九大行星 + 冥王星。**卫星与任何不在要素表里的天体走原路径**（`circularVelocity` 兜底），
所以行为不可能比改动前更差。**位置、质量、渲染一律未动。**

### 28.4 核验：读**真实产物**（不是复刻脚本）

`./gradlew runData` 重新生成后，直接读 `src/generated/.../object/*.json` 的 `speed` 反推：

| 天体 | \|v\| km/s | vy | e涌现 / e公开 | i涌现 / i公开 | T涌现 / T公开 (d) |
| --- | --- | --- | --- | --- | --- |
| 水星 | 58.18 | 6156.7 | **0.2056 / 0.2056** | 6.942 / 7.005 | **87.96 / 87.97** |
| 金星 | 35.06 | 2003.5 | **0.0068 / 0.0068** | 3.391 / 3.395 | **224.67 / 224.70** |
| 地球 | 30.15 | 0.0 | **0.0167 / 0.0167** | 0.004 / 0 | **365.20 / 365.26** |
| 火星 | 26.72 | 242.8 | 0.1024 / 0.0934 | 1.855 / 1.850 | **688.72 / 686.98** |
| 木星 | 12.52 | −142.6 | **0.0484 / 0.0484** | 1.307 / 1.304 | **4334.1 / 4332.6** |
| 土星 | 10.05 | 199.9 | **0.0539 / 0.0539** | 2.484 / 2.486 | **10755.5 / 10759.2** |
| 天王星 | 6.78 | 91.4 | **0.0473 / 0.0473** | 0.773 / 0.773 | **30698.6 / 30688.5** |
| 海王星 | 5.47 | −90.2 | **0.0086 / 0.0086** | 1.770 / 1.770 | **60218.9 / 60195** |
| 冥王星 | 4.74 | 1348.0 | **0.2488 / 0.2488** | 20.140 / 17.140 | **90601 / 90560** |

结论：**e 九颗里八颗精确复现；周期全部在 0.01~0.03% 内；vy 全部非 0**（与 space 的真实值同量级：
它水星 6059 / 火星 241）。

> **又一次撞上"断言必须由被验证的代码推导"**：我另写了一份 PowerShell 复刻脚本来算这张表，
> 它给出"水星 e=0.186"（错的），而真实产物是 0.2056。
> **复刻脚本自己也会骗人** —— 这已经是本项目第三次（`getCosmosTranslation` 契约、
> payload 重复检查脚本、这次）。要验就验**被验证物本身**。

### 28.5 本轮未做的三件后续（互相独立，都不影响已得成果）

1. **火星 e 残差**（0.1024 vs 0.0934）：我们的位置快照不在该要素的圆锥曲线上 ——
   位置来自某个快照历元，与 J2000 平均要素本身有残差。要消掉需要**同历元的位置+速度状态向量**
   （即 §26 那条"数据源决策"的完整版）。
2. **冥王星位置可疑**：黄纬正好 15.000°、`r` 正好等于 a（39.482 AU）⇒ 看着是**合成**的，
   所以它的 i 怎么算都差（20.14 vs 17.14）。修它要换真实位置（同 (1)）。
3. **太空维度 Y 压平**：那里 X/Z 现在已是真实积分，但 Y 仍按 `staticGamePos` 压平
   （可达性需要：真实 Y 动辄 ±64 万格）。"太空里也看到真实倾角"是**纵向映射**的设计问题，
   要么接受压平、要么给太空维度设计映射 —— 需你单独定。**

### 28.6 卫星为什么不动

它们的**构造圆速度**已能把周期做到 0.01~0.05%（§26 表），而真实倾角要把**母星极轴朝向**
引进来（木卫在木星赤道面、土卫在土星赤道面…），是另一个量级的工作。本轮**有意不动**。

## 二十九、失记的既有成果 + 本节的自查（2026-09 轮）

### 29.1 坐标上限：边界已开到 ±Double.MAX_VALUE（≈1.8e308），且深空守卫**只判 X/Z**

这一条**早就做完了**，但只在代码注释里活着，文档没有 —— 我因此在上一轮把
"MC 方块空间有限"当成前提，误判了"太空维度 Y 压平是可达性必需"，还顺手把 space 的
`PositionCompression` 重新推了一遍。**记在这里，避免再丢：**

| 机制 | 位置 | 作用 |
| --- | --- | --- |
| 边界全部返回 ±`Double.MAX_VALUE`、`isWithinBounds` 恒真 | `mixin/space/MixinWorldBorder.java` | 突破原版世界边界（≈1.8e308） |
| `DEEP_SPACE_LIMIT = 33_554_431`（原版 `BlockPos` 26 位极限） | `space/SpaceWorld.java` | 守卫线 |
| 守卫线外 `getBlockState/getFluidState` 直接返回真空、不进入区块系统 | `mixin/LevelSpaceAccessMixin.java` | 深空没有区块可编译也不会崩 |
| 深空无区块 ⇒ 直接报告"已就绪" | `mixin/LevelLoadStatusManagerMixin.java` | 避免加载卡死 |
| `/polymech space far <x> <z>`、`/polymech space probe` | `command/ModCommands.java` | 深空坐标压力测试 / 报告边界与守卫是否生效 |

**⚠️ 已知不对称**：`SpaceWorld.isDeepSpace(x, z)` **只比 x/z**，Y 没纳入。
所以"太空维度显示真实 Y"的正解**不是**新设计一套纵向映射（那是我上一轮凭空造的轮子），
而是**把 Y 纳入这套已有的深空方案**，与 X/Z 同构。

### 29.2 活代码里有两套同一个映射（并应合并）

| 实现 | 参数 | 使用者 |
| --- | --- | --- |
| `space/EarthSpaceMapping` | **地球硬编码**（CENTER 0,0 / 经度长度 100000 / HEIGHT 10000 / MinY −64） | `SpaceTransitionHandler`（地表⇄太空传送） |
| `mps/kelvin/.../CelestialWorld` | **按天体数据驱动**（`space_data/**/world/*.json` 的 `pos_shadow_*`/`Height`/`MinY`） | 地表天空渲染、头盔 HUD、`PhysicalBodySpaceEvent` |

**待办（合并，不盲改）**：让 `EarthSpaceMapping` 转发到 `ClientCelestialWorld`，取不到再回落常量；
`SpaceTransitionHandler` 随之不再有地球特例。
**但它属于传送路径** —— 改错会落到空处/掉出世界，必须实机核对"改造前后落点逐位一致"，不是顺手改的地方。

### 29.3 本轮"重复造轮子"的自查（可复用教训）

本轮我在**已有实现**上重写/重推了这些：

| 我新建的 | 仓库里早已存在 |
| --- | --- |
| `SpaceRenderer` 的地表相机帧分支 | **`.wipbak/space006/.../SpaceRenderMainline.java:100-103`** 逐行同构的 if/else |
| `LevelRendererCelestialSkyMixin`（屏蔽原版天空） | **`.wipbak/space006/.../MixinLevelRenderer.java:43-47`** 已注入 `renderSky` |
| 头盔 HUD 的地表映射 | `.wipbak/.../MixinDebugScreenOverlay.java:33-35` 同套写法 + 活代码 `EarthSpaceMapping` |
| "我们缺少距离压缩" | 我们早有等价物：`SpaceWorld.ZOOM` + `staticGamePos` 的"**保向压缩**" |

**根因**：① 我从压缩摘要接手，旧决定只能靠文档/仓库回收；② **动手前没先搜仓库（含 `.wipbak`）**；
③ 文档缺记录（§29.1 那条）。

> **立规矩**：动手实现任何机制前，先按"概念关键词"搜 `src/main/java` 与 `.wipbak`；
> 命中就**优先复用/合并**，不新写。`.wipbak/space006` 是既有移植的参考，不是垃圾。

### 29.4 本轮真正新增、且已核验的（作为对照，别一并否定）

真实轨道要素→椭圆（§28，e 八颗精确、周期 0.01~0.03%）、
`ClientPhysicalWorld.terrainNeeded()` 那道门（掐掉克隆层半核空转）、
行星地表维度挂自有维度特效（消除日落红染）、地表用**真实三维 Y**（消除"天体连成一条线"）、
以及那两张**读真实产物**的核验表。

## 三十、方案 B 施工图：太空维度坐标改恒等（**换地基**，进行中）

用户 2026-09 拍板走 space 的路：**太空维度坐标 = 宇宙坐标（1 格 = 1 米）**，
距离问题移到**渲染侧**，可达性改由**维度跃迁**承担。

### 30.1 为什么不能"只把渲染换成真实 Y"（上一轮已验证的结论）

`BlockPos.asLong`（本机 `build/mcsrc/.../BlockPos.java:120-123`）把 Y 打成**被掩码截断**的字段
（12 位，±2048）：

```java
i |= ((long)y & PACKED_Y_MASK) << 0;
```

⇒ 真实 Y（火星 −6.4e9 m）进了方块空间会**静默读错方块**（不是崩，比崩更糟）。
X/Z 的 26 位超限**能**被守卫发现（int 值仍是真的，只是打包截断），
**Y 不能**（`BlockPos.getY()` 返回的是从截断位段解回来的别名值，原始大 Y 已丢）。
所以没有"渲染侧单边解压平"这种中间态：要么全压平（自洽），要么把坐标换成恒等（B）。

### 30.2 影响面清单（= 咽喉在哪、谁依赖现口径）

| 消费方 | 用的是什么 | B 之后要改成 |
| --- | --- | --- |
| `SpaceWorld.gamePos` / `gamePosMc` | MC 方块口径（X/Z 来自 kelvin，**Y 压平**） | 拆成**两个显式口径**：`blockPos`（方块，仍压平给方块空间用）与 `renderPos`（宇宙系，真实 Y） |
| `staticGamePos` | Y 压平的**唯一**发生地 | 保留为 `blockPos` 的 Y 来源；`renderPos` 另走天体平滑位姿 |
| `SpaceWorld.toReal` / `toMc`（`ZOOM=10000`） | MC ⇄ 宇宙的线性缩放 | 变成**恒等**（1 格 = 1 米） |
| `SpaceRenderer:147` 相机帧 | `toReal(camPos)` | 恒等 + 渲染侧压缩 |
| `PlanetRenderObjectFactory` / `PlanetRenderObject`（光照/阴影投射者） | `gamePos` | 改读 `renderPos` |
| `PlanetDimensions.teleportToSpaceAbove`（落点 = 天体坐标 + 朝太阳方向×2.2R） | `gamePosMc` | 读 `blockPos`（可达性由跃迁保证），且必须做落点回归 |
| `SpaceTransitionHandler` / `EarthSpaceMapping` | **另一套**地球专用映射 | 并入咽喉（§29.2 的合并） |
| `SpaceHelmetHudOverlay` / `NavSolarSystemView` / `ModCommands` | `gamePos`/`gamePosMc` | 改读 `renderPos` |
| `LevelSpaceAccessMixin` + `DEEP_SPACE_LIMIT` | 深空方块访问真空 | 不变（管辖的是方块空间） |
| 物理地形（`ClientPhysics`/`PhysicsTerrain`/`ProjectionManager`） | MC 方块坐标 | 不变（与 `gamePos` 无耦合） |
| `LivingEntityBelowWorldMixin` | 太空取消虚空伤害 | 不变（B 之后更重要：玩家会真的飞很远） |

### 30.3 分步（每步可离线/实机验、可回退；`S4` 是分水岭）

| 步 | 内容 | 验证 | 回退 |
| --- | --- | --- | --- |
| **S1** | 把 MC⇄宇宙换算**全收敛到 `SpaceWorld` 一处**（含 `EarthSpaceMapping` 合并），三处口径只留一个入口 | 纯重构：对每颗天体断言 `toReal(toMc(x))==x`、`gamePos` 值**逐位不变** | 直接 revert |
| **S2** | 咽喉**保持现行为**（ZOOM + 压平），但把"方块口径 / 渲染口径"命名分开 | 同上 + 渲染截图对照 | revert |
| **S3** | 渲染侧接入 `PositionCompression(n=16384, f=262144)` + 等比缩半径 + `getDepthFar = far×2`；**先只在太空维度** | 视觉不变（位置仍是压平的），但深度范围变了 ⇒ 截图对照 | 一个开关切回旧投影 |
| **S4** | 咽喉换成**恒等**（1 格 = 1 米），渲染口径改用**真实 Y**；方块口径继续压平（**显式命名**，不是暗中分脑） | 离线：`renderPos` 反推 e/i/周期仍对；实机：地表/太空天空对照 | 开关切回 ZOOM+压平 |
| **S5** | 传送/落点改走新口径；可达性交维度跃迁 | **实机**：地球→太空→回地球，落点与改造前**逐位比对** | 保留旧落点公式做 A/B |
| **S6** | HUD/星图/命令统一读 `renderPos`；清掉所有"Y 已被压平"的隐含假设 | 三处 UI 目视 + 命令输出 | 逐个 revert |
| **S7** | 回归：卫星轨道 / 物理地形体素化 / 深空守卫 / 地表天空 | §28、§27 的既有验收路径 | —— |

### 30.4 S1 进度（进行中）：咽喉已立 + 守门断言已装

**已做（全部为纯增量，改前改后行为相同）：**

1. **咽喉方法**：`SpaceWorld.toSpace(...)` / `toGame(...)`（`SpaceWorld.java`）——
   全仓"方块 ⇄ 宇宙"的换算只经这两个入口，别处不许再出现 `ZOOM`。
   `ZOOM = 10000.0` 目前**只出现在 `SpaceWorld:42` 一处** ✔（`ModCommands:364` 那处是显示用换算）。
2. **守门断言**：`SpaceWorld.coordinateSelfCheck()`，每次客户端启动打一行：
   `[坐标自检] 往返最大相对误差=… | blockPos(格)校验和=… | 大气数=20`。
   - 往返用**相对判据**而非"逐位"——`×ZOOM` 再 `÷ZOOM` 在二进制浮点下本就不保证位精确；
   - **校验和是 S1–S3 的红线**：只想重构的步骤**不许**让它变；S4 换恒等后才允许变，
     届时改为盯 `renderPos` 口径。
3. **改造前快照**：`build/pm-diag/gamepos-snapshot-before-S1.txt`（20 天体，blockPos 口径）。
4. **数据侧金值**（与代码自检应一致，若不一致**以游戏内那行为准**，说明我这个复刻脚本又错了）：
   **`blockPos(格)校验和 = -5410992856234`**。

**核实过的消费者清单**（S4 必须**同时**改，漏一个就是混用两种尺度 = 分脑）：

| 消费者 | 位置 |
| --- | --- |
| 相机帧 | `SpaceRenderer:152-154`（`toReal`） |
| 星图 | `NavSolarSystemView:60-62`（`toReal`） |
| 头盔 HUD | `SpaceHelmetHudOverlay:78-80`（`toMc`） |
| 落点/传送 | `PlanetDimensions:165`（半径→格）、`SpaceTransitionHandler:75-77/88-90`、`TeleporterScreen:67` |
| 诊断/命令 | `KelvinDiagnostics:105-128`、`ModCommands:364` |
| 天体游戏坐标 | `SpaceWorld.gamePosMc` / `earthMcPos`（170/175） |

> ⚠️ **重要发现**：`SpaceTransitionHandler` 的传送路径**混用了两套** ——
> 先用 `EarthSpaceMapping`（**球面映射**，把地皮 x/z 贴到球面）得到宇宙坐标，
> 再用 `SpaceWorld.toMc`（**线性缩放**）换成太空维度的方块坐标。
> 所以 S4 换恒等时，**球面映射与缩放必须一起动**，只改一个会让落点系统性偏移。

**S1 剩余**：把上述调用点统一改走咽喉（机械替换，数值不变），并给 `EarthSpaceMapping`
加"仅供地球、待并入"的转发壳（**不改变它现在的数值**——它的 `HEIGHT=10000` 与数据里的
`height=320` 不一致，直接并入会移动传送落点，属于 S5 的实机验证范围）。

> **轮 2 更正**：上面这条"把调用点统一改走咽喉"经核对是**零收益的机械替换**
> （`toSpace`/`toGame` 与 `toReal`/`toMc` 本就是同一实现，咽喉只有 `ZOOM` 一处）。
> **故不做**，改为把精力放到真正卡着 S4 的东西上 —— 见 §30.5。

### 30.5 **隐藏尺度假设**清点（这才是卡 S4 的东西）

恒等约定（1 格 = 1 米）会让所有"以格为单位"的硬编码整体错 10⁴ 倍，故必须先把它们列全。

**✅ 好消息：渲染侧的近/远平面本来就是米**（`toReal` 已把方块换成米，
且投影用的是**相机相对坐标**，绝对量级无关）⇒ 恒等之后**自动继续有效**：

| 常量 | 值 | 单位 | S4 后 |
| --- | --- | --- | --- |
| `SpaceRenderer.SPACE_NEAR_PLANE` | 1000.0 | **米** | 不变 ✔ |
| `SpaceRenderer.SPACE_FAR_PLANE` | 1.0e13 | **米** | 不变 ✔（S3 另加 `getDepthFar = far×2`） |
| `SpaceRenderer` 天空盒投影 | 0.05 / 2000 | 立方体半径固定 | 不变 ✔ |
| `SpaceHelmetHudOverlay.VFOV_DEG` / `MARGIN` | 70 / 1.05 | 角度/比例 | 不变 ✔ |

**⚠️ 需要改的（都以"格"为单位，恒等后会失真或语义漂移）：**

| 位置 | 现状 | S4 要做 |
| --- | --- | --- |
| `SpaceHelmetHudOverlay:141-142` | `d >= 1e8 → 亿格`、`d >= 1e4 → 万格` | 距离口径与档位都按新单位重定（且 `d` 的来源要从 `gamePos` 改 `renderPos`） |
| `SpaceHelmetHudOverlay:110` | `rel.z() > -0.5f` 近裁剪（0.5 **格**） | 语义仍成立（"离相机极近"），但单位变了：确认 0.5 m 仍合适 |
| `ModCommands:364` | `dist * SpaceWorld.ZOOM` 显示"真实距离" | `ZOOM` 变 1 后这行成了 **no-op**，要么删、要么改成真正需要的换算 |
| `PlanetDimensions:165` 起的落点 | `toMc(radius) × 2.2` 得"格" | 恒等后 `radius` 格 = 米 ⇒ 落点数值**会变**（语义仍正确：地表上方 2.2R）；属 S5 实机比对范围 |
| `EarthSpaceMapping` 常量 | `HEIGHT=10000`，而数据里 `height=320` | 两者不一致：并入 `CelestialWorld` 会让落点移动 ⇒ **必须进 S5 的实机比对**，不能与 S3/S4 混做 |
| `NavSolarSystemView:60-62` | `toReal` + 星图自身尺度 | 星图是按"格"画的：口径要跟着 `renderPos`/新单位一起定 |

**结论**：S4 的真实工作量**不在"改一个常量"**，而在"把所有以格为单位的显示与阈值重新定标"，
且**只能一次性改完**（漏一个就是混用两种尺度）。

### 30.6 S2 完成：两个口径已显式命名（纯增量，行为不变）

`SpaceWorld` 现在有两个明确的入口，名字就写明给谁用：

| API | 口径 | 给谁 |
| --- | --- | --- |
| `blockPos(b)` | 方块口径（X/Z 来自 kelvin，**Y 压平**） | 传送落点、方块空间、任何要变 `BlockPos` 的东西 |
| `renderPos(b)` / `renderPos(b, partialTick)` | **渲染口径**（宇宙系真实三维，含真实 Y；插值版与相机帧同源） | 渲染、光照/阴影投射、HUD、星图 |
| `gamePos(b)` | = `blockPos`，**已标 `@Deprecated`** | 历史调用点（编译时会提示，S4/S6 逐个迁） |

同时把 `kelvinPos` 的查找抽出成 `kelvinBody(b)`（客户端显示世界优先 → 服务端兜底 → 来源标注），
让"取 `getPos`"与"取 `getSmoothPos`"**共用同一套查找与来源**；
`PlanetRenderObjectFactory.refreshPositionsFromCelestial` 随之改成一行
`SpaceWorld.renderPos(data, partialTick)` —— **删掉了它自建的那份重复查找**（§29.3 的教训：同一概念三份实现）。

**行为不变**（`blockPos` 就是原 `gamePos` 的逐字实现；`renderPos` 的查找与插值和原来那份一致，
只是多了"客户端缺该天体时回退服务端"与有限性检查）。守门校验和应仍是
**`-5410992856234`**（以游戏内 `[坐标自检]` 那行为准）。

**S3 起才动渲染**：接入 `PositionCompression(16384, 262144)` + 等比缩半径 + `getDepthFar = far×2`，
先只作用于太空维度，并留一个开关可切回旧投影。

### 30.7 S3（第一步）：压缩机制已落地并**离线验证**（尚未接进渲染，开关默认关）

新增 `client/space/RenderCompression.java`（纯数学，无 MC 依赖）：

```
compress(x) = x                       (x <= NEAR=16384)
            = FAR - (FAR-NEAR)·exp(-((x-NEAR)/4096)/(FAR-NEAR))     (x > NEAR)
zoom(dist, R) = compress(dist - R) / (dist - R)      // 位置与半径同乘，角直径不变
```

开关 `RenderCompression.enabled`（**默认 false**）—— 所以"把机制接进来"这一步本身零风险、可随时 A/B。

新增离线回归 `native/jni-smoketest/RenderCompressionTest.java`（跑法见 §13）：

```
javac -encoding UTF-8 -cp "build\classes\java\main" -d build\pm-probe native\jni-smoketest\RenderCompressionTest.java
java  "-Dstdout.encoding=UTF-8" -cp "build\pm-probe;build\classes\java\main" RenderCompressionTest
```

**结果（全部通过）**：`x<=NEAR` 原样 ✔、单调递增 ✔、压缩后 `<= FAR` ✔、
**角直径不变**：地球/太阳/月球/火卫一/极远(2e13 m) 五档的相对误差全部 **0.00e+00** ✔。

> **轮 4 更正 §30.5 的一条判断**：我当时说"我们的管线不需要 `far×2`"。**错了一半**：
> 不需要改 **MC 的** `getDepthFar()`（那会影响地形/实体，space 之所以改是因为它共用主深度缓冲），
> 但**必须**把**我们自己的 `spaceProj`** 的 far 设成 ≥ `FAR×2`。
> 理由由本轮测试逼出来：`exp(-巨大)` 会下溢到 0 ⇒ "无穷远"压缩后**恰好等于 FAR**（不是略小于），
> 于是 far 若正好 = FAR，最远的天体会**正好落在远平面上被裁掉**。这正是 space 取 `FAR×2` 的原因。

**S3 剩余**：把 compress+zoom 接进 `SpaceRenderer`（先只太空维度；`spaceProj` far 改 `FAR×2`），
保持 flag 可切；预期**画面位置与大小不变**、只有深度行为变好（截图为证）。

### 30.8 轮 4–5 的重排：**S3b 让位给 S4**（有依据的排序，不是偷懒）

读代码后发现两件事，改变了顺序：

1. **"等比缩半径"是侵入性改动**：`PlanetRenderObject.render` 的半径不是一处 scale ——
   fallback（`SolarSystemRenderer.renderBody`）+ `drawBaseLayerGpu` + 云 + 环 + 大气
   **共 5 条绘制路径**各自把半径写进几何，缩放要逐条改（文件 1160 行）。
2. **它并不阻塞 S4**：渲染器本来就工作在**米**尺度上（`toReal` 已把方块换成米），
   S4 把咽喉换成恒等之后，喂给渲染器的**量级完全一样** ⇒ 压缩是**独立的深度精度改进**，
   不是换约定的前置条件。

所以：**先给 S4 装闸门**（见下），S3b 排到 S4 之后（届时 flag 已在，可 A/B）。

### 30.9 S4 的闸门已装：`SpaceWorld.identityMode`（默认 false，行为不变）

```java
private static volatile boolean identityMode = false;   // false = 1格=ZOOM米（历史）；true = 1格=1米（space 恒等）
public static double toMc(double real)  { return identityMode ? real : real / ZOOM; }
public static double toReal(double mc)  { return identityMode ? mc  : mc * ZOOM; }
```

- **默认 false ⇒ 一行行为都没变**（`[坐标自检]` 仍应报金值 `-5410992856234`），
  且自检行现在会打印**当前约定**，A/B 时一眼可辨；
- 这正是目标里"每步可回退"要的东西：换约定会让所有以格为单位的数值整体变 10⁴ 倍，
  有开关才能"同一份代码、两种模式"逐项对照；
- **它只管"缩放"这一半**。恒等约定还要求方块口径不再压平 Y —— 那是 §30.1 的 12 位硬约束，
  必须单独一步，两件事不能塞进一个开关。

**S4 因此拆成两半**：`4a` = 缩放切恒等（`setIdentityMode(true)` + §30.5 一次性重定标 +
落点/方块空间回归）；`4b` = 方块口径的 Y 处理（**默认仍压平**，因为它是 BlockPos 的硬约束）。
真正"太空里看到真实倾角"靠的是**渲染口径走 `renderPos`（真实 Y）**，而不是让方块口径不压平。

### 30.10 轮 6：**S4a 的前置条件被查出来了**（先别切恒等）

查 space 侧三条事实，结论改变了 S4a 的规模：

1. **`position_zoom: 10000` 在 0.1.3 里没有任何 Java 消费者**
   （`data/space/space_data/alpha_system/type.json:3` 有它；对 `*.java` 全量检索 `zoom|Zoom|ZOOM`
   的 69 处命中里，没有一处读 `position_zoom`）。
   ⇒ **我们的 `ZOOM` 不是"照抄了 space 在用的机制"**，而是我们自己挑的一个（恰好等于一个死字段的值）。
   也就是说：**space 在"方块空间该用什么尺度"这件事上并没有给出答案**，因为它压根不在方块空间里放天体。

2. **space 的太空维度坐标就是宇宙坐标（恒等）**：`convertMinecraftSpaceVectorToSpaceVector` 恒等，
   而传送把人放到 `convertSpaceVectorToMinecraftSpaceVector(getSpacePosFromWorldPos(...))`，
   恒等 ⇒ **玩家在太空维度里的方块坐标 ≈ 1e11 格**（地球距离）。

3. **它凭什么不炸**：`BlockPos` 的 X/Z 只有 26 位（±3,355万），1e11 格必然**别名**到别的坐标 ——
   但 space 的太空维度是**纯虚空**（`SpaceLevelDimensionType`：`sky_color/fog_color = 0`、无地形生成），
   别名过去读到的**还是空气**，所以"读错位置"在观感与逻辑上都无害；区块系统在原点附近空转也无害。

**⇒ 我们照搬恒等会炸，因为我们的太空维度不是纯虚空**：
本项目的方块空间里还有**玩家的区块加载**、**`ClientPhysics.updateTerrain` 的逐区块地形碰撞体**、
方块放置/破坏等。它们都没被 `DEEP_SPACE_LIMIT` 守卫覆盖（那个守卫只拦
`Level.getBlockState/getFluidState`）。1e11 格上的区块查询会别名到原点附近 → **读到/建出错误的地形碰撞体**。

**所以 S4a 的正确前置不是"把开关切成 true"，而是先把深空守卫从"方块读取"扩展到
"整个方块空间子系统"**，三选一：

| 方案 | 内容 | 代价 |
| --- | --- | --- |
| **a. 扩展守卫（推荐）** | 让 `isDeepSpace` 判定覆盖**区块加载/地形碰撞体/放置**等全部方块空间入口（而不只是 `getBlockState`），线外一律"无方块、无区块、无地形" | 中等：要逐条入口接上（`ClientPhysics.updateTerrain`、`ChunkMap`/`ServerChunkCache` 相关、放置钩子） |
| **b. 学 space** | 让太空维度彻底虚空（不放方块、不做地形碰撞体） | 与项目现有玩法冲突（太空里要能建东西/开船），需要用户拍板 |
| **c. 混合** | 保留 ZOOM 作**方块空间专用**尺度（把天体映射进 ±1e7 格安全区），只有**渲染口径**用真实宇宙坐标 | 改动最小、最稳；但"坐标恒等"这一条目标要重新定义 |

**在用户就 a/b/c 拍板前，不动 `identityMode` 的默认值**（保持 false ⇒ 行为不变、校验和仍是金值）。
这正是目标里"每步先给影响面清单"的意义：这一条如果等切完再撞，代价将是"传送落点看着对、但地形碰撞体建在错误位置"这类**极难排查**的故障。

### 30.12 轮 8：**用户选 b，且"世界侧前提"本来就已满足**（剩余计划因此大幅缩小）

**用户决定：b —— 太空维度彻底虚空**（理由：太空里的方块之后由**物理体**接管，不需要原版方块空间）。

**核到的事实：这一条其实早就满足了** —— 太空维度本身就是纯虚空（`SpaceModDataPackManger:512-526`）：

```json
"generator": { "type": "minecraft:flat",
  "settings": { "biome": "poly_mech:space",
                "layers": [],                 // ← 一层方块都没有
                "structures": false, "lakes": false, "features": false } }
```

配套 `SpaceLevelDimensionType`：`has_skylight: false`、`effects.sky_color/fog_color = 0`、`natural: false`。

**⇒ §30.10 那条"1e11 格别名会建出错误地形碰撞体"的危害自动消失**：别名读到的仍是空气
（没有方块可读错、没有地形可建错）—— 这正是 space 自己赖以不炸的同一个理由。

| 项 | b 之下的结论 |
| --- | --- |
| §30.10 的"**a. 扩展守卫**"（`ChunkMap`/`ServerChunkCache`/`LevelChunk` 逐条接上） | **取消，不做**。那是为救"非虚空的太空"才需要的 |
| 轮 6 已加的两道守卫（`setBlock`→false、深空跳过地形体素化） | **保留**，且与 b 同向（就是在强制"太空里没有原版方块"），零成本、可回退 |
| `identityMode`（恒等开关） | 前提已具备，**可以动** |

**离 S4a 只剩三步：**

1. **⚠️ 用户启动一次游戏**（唯一仍未验证项）：轮 6 的 `setBlock` 注入是**运行时**校验
   （`defaultRequire: 1`），描述符不匹配 ⇒ **启动即 InjectionError**。签名按
   `build/mcsrc/.../Level.java:228/233` 核过，但只有启动能验收。
   **在确认前不再往核心路径叠新的运行时校验 mixin。**
2. **`identityMode = true` + §30.5 那张"隐藏尺度假设"表一次性重定标**（HUD 距离档位、
   `ModCommands:364` 的 `dist * ZOOM` 恒等后成 no-op、`PlanetDimensions:165` 起的落点、
   星图 `NavSolarSystemView` 口径）——**必须同一趟改完**，漏一个就是混尺度。
   ⚠️ 注意：恒等之后**玩家在太空维度里的方块坐标 ≈ 天体真实米数（1e11 量级）**，
   所以这一步**必须与"传送把玩家放到天体坐标"一起做**，否则"玩家在哪"与"天体在哪"
   会立刻脱钩（这正是"可达性改由维度跃迁承担"的字面含义）。
3. **落点逐位回归**：地球 → 太空 → 回地球，落点与改造前比对
   （`EarthSpaceMapping.HEIGHT=10000` 与数据里 `height=320` 不一致那条也在这一步暴露）。

**本轮不翻 `identityMode`** —— 半迁移（只切尺度、不改传送）正是文档反复警告的"迁一半比不迁更糟"。

### 30.13 轮 8（本会话独立核对）：**落点不变量可以离线证明** + 一条承重结构

> 先记一条**文档落后于代码**：上面 §30.12 写的是"本轮不翻 `identityMode`"，
> 但工作区里 `SpaceWorld:116` **已是 `identityMode = true`**（注释标"轮 10"）。
> 也就是说另一会话在 §30.12 之后又推进了若干轮、并把开关翻了过来（§30.12 的步骤 2/3 已执行）。
> **以代码为准**，下面的结论按"已翻恒等"的现状写。

**① 传送路径逐行审过：两侧都对称走咽喉，"宇宙系(米)"就是与约定无关的不变量。**

| 方向 | 代码 | 约定相关性 |
| --- | --- | --- |
| 地表 → 太空 | `player 方块` →(球面映射)→ **宇宙系(米)** →(`toMc`)→ 太空目标方块 | 只在**最后一步**乘/除约定因子；米是中间量 ✔ |
| 太空 → 地表 | `player 方块` →(`toReal`)→ 米 →(球面逆映射)→ **落点方块** | 落点是**球面反算的方块坐标**，与约定无关 ✔ |

⇒ **翻约定只会让"目标方块坐标"整体乘同一个因子；物理落点（球面上那个点）不变。**
这正是目标里"落点逐位比对"要的那条不变量，**现在有离线证明**（读代码即可），
实机日志（§30.12 已埋 `[坐标落点]` 两列）只作为**确认**，不再是唯一证据。

> 附带一条 26 位溢出的真隐患，§30.12 那边已补上守卫：
> 恒等之后太空目标会到 1e11 量级，而 `(int)` 上限 2.1e9 ⇒ 预加载会拿到别名坐标；
> 现在 `SpaceTransitionHandler:96-102` 用 `±3.0e7`（略小于深空守卫线）判"纯虚空就不预加载" ✔。

**② 新发现：`blockPos` 的 Y 压平是"卡门线捕获判定"的承重件（别去解压平）。**

`SpaceTransitionHandler.tickInSpace`（:111-128）拿
`pxReal/pyReal/pzReal = toReal(玩家方块坐标)` 与 `gamePos(body)` 比距离，判定是否进入某天体的卡门线。
`gamePos` 的 Y **被压平为 0**，而玩家在太空维度里的 Y 是一个**小量**（维度高度 ±256 格
⇒ ZOOM 下 ±2.56e6 米、恒等下 ±256 米）；捕获半径是 `半径 + 卡门线`（火星 ≈3.47e6 米）。
**两边都在同一个"压平后的小 Y"尺度上，判定才成立。**
若哪天"顺手"把方块口径的 Y 解压平（天体真实 Y 可达 −6.37e9 米），`dy` 会变成 1e9 量级，
**捕获判定立刻永远失败** —— 这是一条必须写下来的承重关系（§30.9 的"4b：方块口径继续压平"因此不是保守，而是必需）。

### 30.14 收尾（目标轮到 12/12）：实现全齐，只剩实机两项 + 一处**诚实缺口**

**已交付并各有证据**（详见上文各节）：

| 项 | 证据 |
| --- | --- |
| 恒等约定（1 格 = 1 米） | `SpaceWorld:116 identityMode = true`；启动日志 `约定=恒等(1格=1米)` |
| 单一咽喉 + 两个显式口径 | `toSpace/toGame`；`blockPos`（方块、Y 压平）/ `renderPos`（宇宙系真实 Y）；`ZOOM` 只在 `SpaceWorld:42` 一处 |
| 渲染侧距离压缩 | `RenderCompression`（near 16384 / far 262144）；`SpaceRenderer:168` far=`FAR×2`；`SolarSystemRenderer`/`PlanetRenderObject` 等比缩放；**角直径不变性离线回归 0.00e+00** |
| 深空守卫扩展 | `LevelSpaceAccessMixin` 2→4 入口 + `ClientPhysics:869` 守卫 |
| 守门断言与约定解耦 | `米/10` 写法；**权威金值 `-5410990681030`**（游戏内实测） |
| 存档迁移（玩家） | `SpaceScaleMigration`；判据用日志真实坐标离线验过：旧尺度 ⇒ 迁移 ×10000、新尺度 ⇒ 不碰 |
| 启动验收 | `2026-09-21-1.log.gz`：**无 InjectionError**、`往返最大相对误差=0.000e+00` |

**待实机确认的两项**（只有用户能做）：
1. 进太空时出现 `[坐标迁移] … ⇒ ×10000 搬到 (…)`；
2. 地球 → 太空 → 回地球：两行 `[坐标落点]` 的**宇宙系(米)必须逐位不变**、`目标` **恰好 ×10000**。

**⚠️ 一处未做的缺口（诚实记录，刻意不盲改）**：**物理体存档**（`PhysicsBodySavedData:161-190`）
里的坐标同样是旧尺度，与玩家一样会差 10⁴ 倍。没顺手改的原因：

- `load(tag, registries)` **拿不到所在维度**（SavedData 归属维度由更外层决定），
  要判"这是不是太空维度的存档"就得改 `SavedData.Factory` 的调用点 —— 那是接口改动，不是加一行；
- 该路径**没有离线回归覆盖**，改错的表现是"物理体下次载入时瞬移到 1/10000 处"——
  属于"宁可留着记录、也不要盲改"的那类；
- 触发面很窄：只有"翻约定**之前**就在太空维度里存过物理体"的存档才会遇到，
  而克隆层至今没有任何 gameplay 创建路径（§19/§20）⇒ 现状大概率没有这种存档。

---

## 31 恒等约定的第一个地板塌陷：原版阴影循环在大坐标下**永不退出**（已修，照 space 的锚点）

### 31.1 现象与**排除法**（不要再重推一遍）

实机：进太空后客户端**整个卡死**。日志给出第一手判据 ——
`[Kelvin] [位置日志]` 这一类 **`[Render thread/…]` 行在 `18:41:18.7` 之后全部停止**，
而 `[PolyMech-Physics-Step/…]` 仍在打印。**MC 的客户端 tick 与渲染同线程**，
所以这不是"渲染慢"，是那一帧根本没有结束。

对同一个进程（PID 29396）连抓 4 次 `jstack -l`，**每一次都是同一个叶子**：

```
Render thread  RUNNABLE  cpu=274562ms / elapsed=593s
  at BlockBehaviour$BlockStateBase.getRenderShape(BlockBehaviour.java:655)
  at EntityRenderDispatcher.renderBlockShadow(EntityRenderDispatcher.java:406)
  at EntityRenderDispatcher.renderShadow(EntityRenderDispatcher.java:386)
  at EntityRenderDispatcher.render(EntityRenderDispatcher.java:174)
  at LevelRenderer.renderEntity(LevelRenderer.java:1267)
```

6 秒窗口内该线程 CPU **+7468 ms（≈96% 单核满载）** ⇒ 紧循环空转，不是等锁、不是 GC、不是死锁。

> **排除法（都已做，勿重复）**：实体数量极少（`GC.class_histogram` 里最大的
> `net.minecraft.world.entity.*` 仅 2346 个 `AttributeInstance`），排除"实体太多"；
> 阴影方块循环理论上限 ≤33×33×33，且坐标饱和后 x/z 各只剩 1 次迭代 ——
> 所以**不能**用"迭代次数多"解释，必须看**循环边界本身**（见 31.2）。
> 另：上一轮把到达距离从 `R` 改到 `2.2R`（角直径 159.8°→54.1°）**是正确的、与本次无关**；
> 实机日志 `earth 距离=13980465` ≈2.19R 证明它生效了，卡死照旧。

### 31.2 根因：`Mth.floor` 的饱和转换 + `for (int …)` 回绕

反编译源码（`build/pm-diag/mcsrc/…/EntityRenderDispatcher.java:361-390`，取自
`build/moddev/artifacts/neoforge-21.1.228-sources.jar`）：

```java
int i  = Mth.floor(d0 - (double)size);   int j  = Mth.floor(d0 + (double)size);   // d0 = 实体世界 X
int i1 = Mth.floor(d2 - (double)size);   int j1 = Mth.floor(d2 + (double)size);   // d2 = 实体世界 Z
for (int k1 = i1; k1 <= j1; k1++)
    for (int l1 = i; l1 <= j; l1++) { … }
```

`Mth.floor(double)` 是**饱和**转换：`int i = (int)value; return value < (double)i ? i - 1 : i;`。
坐标越过 ±2^31 后上下界双双落到 **`Integer.MAX_VALUE`**（负向越界时 `MIN_VALUE - 1` 回绕成 `MAX_VALUE`），
于是 `i == j == MAX_VALUE`；而 `l1++` 从 MAX 回绕成 `MIN_VALUE`，`MIN_VALUE <= MAX_VALUE` **依然成立**
⇒ **循环永不退出**。太空按恒等约定玩家坐标 1e10~1e16，且玩家自己的阴影
（`distanceToSqr == 0`）每帧必画，于是必然踩中。

**离线复现（不靠推理）**：`build/pm-diag/ShadowLoopProbe.java`，按上述源码逐字复刻并加 43 亿次上限：

| 用例 | i / j | 结果 |
| --- | --- | --- |
| X=+1.675e10（太空真实坐标） | MAX / MAX | 43 亿次后**仍未退出** |
| Z=-1.468e11（太空真实坐标） | MAX / MAX | 43 亿次后**仍未退出** |
| X=+1.675e6（旧 ZOOM=10000 同一位置） | 1675258 / 1675259 | **2 次退出** |
| X=2^31-1（**仍在 int 内**） | 2147483646 / 2147483647 | 也是死循环（必须走到 j=MAX 再回绕） |

⇒ 这是**恒等约定（§30）的直接代价**，旧 ZOOM 约定下永远碰不到；也说明
"凡原版用 `int` 算方块坐标"的地方都是恒等约定的地板，不止这一处。

### 31.3 参考实现怎么做（实物证据，不是回忆）

从 `.wipbak/space010/space-0.1.0.jar` 取出并用 vineflower 反编译
`org/deep_space_studio/space/mixin/client/render/MixinEntityRenderDispatche.class`：

```java
@Mixin(EntityRenderDispatcher.class)                       // space.mixins.json 的 client 列表里
public class MixinEntityRenderDispatche {
    @Shadow private static void renderShadow(…) { … }

    @Redirect(method = "render",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderShadow(…F)V"))
    public void entityShadows(…) {
        if (!ClientSpaceWorld.isSpaceWorld()) { renderShadow(…); }   // 太空里不画实体阴影
    }
}
```

**职责**：太空世界的实体阴影一律不画。**为什么**：见 31.2（原版 int 边界在大坐标下不成立），
且太空里本来也没有真正的投影面。**实现步骤**：在 `render` 里那次 `renderShadow` 调用点做条件放行。
判据取"**是否在太空世界**"而非坐标阈值 —— 地表（行星维度）坐标仍小，阴影照常。

### 31.4 我们的落点（1:1 同锚点）

- 新增 `src/main/java/com/mss/polymech/mixin/space/MixinEntityRenderDispatcher.java`：
  与 space **同一个锚点**（`render` → `renderShadow` 调用点），判据用我们已有的
  `ClientSpaceWorld.isSpaceWorld()`（§客户端太空世界唯一判据）。
- 承载方式改用 `@WrapOperation`（本项目已标准化，理由见 `MixinItemEntity`：
  `@Shadow` 只认<i>目标类自身声明</i>的方法）；此处 `renderShadow` **确实**声明在
  `EntityRenderDispatcher` 自身，所以两种写法都合法，选前者只为一处约束更少。
- 注册：`src/main/resources/poly_mech.mixins.json` 的 `client` 列表。
- **锚点离线验证**（跑起来之前就确认能解析）：`javap -p -c` 运行时 merged jar 里的
  `EntityRenderDispatcher.class` ⇒ 全类 `invokestatic …renderShadow` **只有 1 处**（无需 `ordinal`），
  且 `private static void renderShadow(…MultiBufferSource…LevelReader…)` 签名逐字一致。
- **产物验证**：`gradlew classes` BUILD SUCCESSFUL；`build/classes/…/MixinEntityRenderDispatcher.class`
  时间戳刷新、`javap -v` 里 `INVOKE` 目标串完整；`build/resources/main/poly_mech.mixins.json`
  （运行时真正加载的那份）已含该项 —— 只跑 `compileJava` **不会**拷资源，必须 `classes`/`processResources`。
- 实机确认（唯一还差的一步）：进太空应出现一行
  `[太空阴影] 已按 space 锚点跳过实体阴影` 且客户端恢复 tick。

### 31.5 本次**刻意没做**的两件事（记下来，别当漏掉）

1. **没有**改成坐标阈值判据（例如 `|x|>2^31` 就不画）。space 的语义是"太空里不做阴影"，
   阈值判据会在"太空里坐标恰好还小"时画出阴影，行为与参考不一致。
2. **诚实的缺口**：**非太空维度**若坐标也越过 ±2^31（我们的 `space.MixinWorldBorder`
   已经把边界放到 ±`Double.MAX_VALUE`），同样会踩这个循环 —— space 的锚点**也**只挡太空世界，
   所以这是与参考实现同源的缺口，记录在此，不擅自扩大判据范围（要扩就先想清楚
   "世界边界 ±Double.MAX_VALUE"与"原版 int 假设"这一对矛盾的统一处理）。

### 31.6 订正：`ZOOM=10000` 来自 space **0.0.6**，当前参考（0.1.x）是恒等

**问题**："之前不就是 1 格 = 1 米吗？"——必须用物证回答，不能靠记忆。

| 物证 | 内容 |
| --- | --- |
| space 0.0.6 源码 | `.wipbak/space006/src/org/cn_grass_block/space/util/manger/SpaceModDataPackManger.java:77,84` 真的 `jsonObject.get("position_zoom").getAsDouble()` ⇒ **0.0.6 消费这个字段** |
| space 0.1.0 jar | 全 class 扫 `position_zoom` / `PositionZoom` ⇒ **命中 0 个类** ⇒ 0.1.x 已弃用该机制 |
| 本项目历史 | `ZOOM = 10000.0` 引进于提交 **`95f586d 宇宙1`**（`SpaceWorld:25`），是 0.0.6 时代的实现 |
| 改造前快照 | `build/pm-diag/gamepos-snapshot-before-S1.txt`：地球 blockPos = **1,530,000 格**（= 1.53e11 米 ÷ 10000）⇒ 世界侧当时**不是** 1:1 |

**结论**：0.1.x 的太空维度确实是 **1 格 = 1 米**（`convertMinecraftSpaceVectorToSpaceVector` 是恒等 +
`position_zoom` 无人消费），所以"应该是 1 格 = 1 米"这个判断**是对的**；
而我们的 `ZOOM=10000` 是把**上游 0.0.x 已删掉的轮子**多留了几周 —— 本轮翻恒等是**拆掉它**，不是造新轮子。

**两处订正**：
1. §31 开头把本次卡死说成"翻地基的必然代价"**说轻了**。更准确：
   我们一直按上游已弃用的 0.0.6 约定在跑，一翻，所有按旧约定写的地方同时暴露。
2. **口径分裂的由来**（这也是"记混"的来源）：**渲染/显示侧一直是米** ——
   `SpaceRenderer:146-148 SpaceWorld.toReal(camPos)`、`NavSolarSystemView:60-62 toReal(p.*)`、
   `ModCommands:234 dist * SpaceWorld.ZOOM`；**世界/存档侧才是 ÷10000**（HEAD `SpaceWorld:58/62`）。
   ⇒ 看着是米、存的是格。下次遇到"这个数到底什么口径"，先确认它在哪一侧。

**遗留脚手架的处理建议**（待定，不擅自删）：`identityMode` 闸门恒 true 后，它的分支属过渡重量；
`SpaceScaleMigration` 只对"`95f586d` 之后、本会话之前存过的太空存档"有意义。
建议保留闸门作为可回退开关，但把 `ZOOM` 的注释从"当前约定"改成
"**0.0.6 遗留常量，仅供旧存档迁移**"，否则下一个人（包括我）会再把它当承重约定。

### 31.7 用**目标版本 0.1.3** 的实物复核（并订正 §31.6 的证据来源）

**先说我的错**：§31.6 与上一条回复里我声称"工作区只有 0.0.6 / 0.1.0 两个参考 jar，没有 0.1.3"——
**这是错的**，是我把搜索范围自己划窄到 `.wipbak` 造成的。目标版本的实物一直在：

| 0.1.3 实物 | 位置 |
| --- | --- |
| 反编译源码树（316 个 java） | 工作区内 `decompiled-space/0.1.3/`（`docs/space-decompile.md:11` 早就写明了它在哪，我没先读这份索引） |
| 成品 jar | `C:\Users\34573\Downloads\space-0.1.3.jar`（桌面与 `versions/1.21.1gtm/mods/` 亦有副本） |

**0.1.3 的两条尺度物证**：

```java
// decompiled-space/0.1.3/org/cn_grass_block/kelvin/physical/space_world/SpaceWorld.java
88:  public Vector3d convertMinecraftSpaceVectorToSpaceVector(double x, double y, double z) {
89:     return new Vector3d(x, y, z);      // 恒等 ⇒ 1 格 = 1 米
100: public Vector3d convertSpaceVectorToMinecraftSpaceVector(double x, double y, double z) {
101:    return new Vector3d(x, y, z);      // 反向亦恒等
```

- **没有子类 override**：全树这两个名字共 13 处命中，其中**定义点 6 处全在 `SpaceWorld.java`（88/92/96/100/104/108）**，
  其余 3 个文件（`MixinDebugScreenOverlay`、`sunshine/render/SpaceRenderer`、`SpaceModCommand`）都只是调用点。
- **`position_zoom` 在 0.1.3 的 316 个 java 文件里 0 命中**（0.0.6 才有消费者）。

**并且 0.1.3 自己就带着那个阴影守卫**：`decompiled-space/0.1.3/org/deep_space_studio/space/mixin/client/render/MixinEntityRenderDispatche.java`
—— 与我们 §31.4 落地的 `MixinEntityRenderDispatcher` **同一锚点、同一判据**（`!ClientSpaceWorld.isSpaceWorld()` 才 `renderShadow`），
说明这条守卫在 0.1.x 一直存在（0.1.0 jar 里也有），**不是 0.1.3 之后才补的**。

**结论（取代 §31.6 的推断部分）**：

- space **0.1.3 = 1 格 = 1 米**（上表两条物证，非推断）；
- 我们（`identityMode = true`）**与目标版本一致**；
- 我们原先的 `ZOOM = 10000` 来自 **0.0.6**——一个上游 0.1.x 已经删掉的机制 ⇒ 翻恒等是**拆掉旧版遗留**，不是造新轮子。
- 方法论教训（比结论更重要）：**对齐类问题的证据必须取自目标版本本体**；只查到"某个版本的 jar"就先别下结论，
  而且动手前先读 `docs/space-decompile.md` 这类索引，别自己在 `.wipbak` 里瞎划范围。

### 31.8 第一次实机复核：**"没复现" ≠ "已验证"**（以及触发阴影路径的真正条件）

**实机日志（`run/logs/latest.log`，19:12 启动 / 19:13:59 进太空 / 19:14:35 退出）**：

| 观测 | 数值 |
| --- | --- |
| 客户端是否在太空继续 tick | 是：`[位置日志]` 每 5 秒一条（19:14:04/10/15/20/25/30），`物理规模` 每 10 秒一条，全程无空档 |
| 卡顿旁证 | `物理步进被阻拦` **0 次**（对比冻死那两次是另一种形态） |
| 太空物理 | `刚体=2 碰撞体=2 地形区块=0` ⇒ 物理世界在太空是活的 |
| 坐标 | `[坐标落点] overworld → 太空`：**宇宙系(米) 与 目标 逐位一致**；`[坐标自检]` 校验和 `-5410990681030` = 权威值、往返误差 `0.000e+00`；无 `InjectionError` |
| **守卫日志** | **`[太空阴影]` 在 latest.log 与 debug.log 各 0 命中** ⇒ 守卫这次**没被走到** |

**为什么没被走到（这次查清了）**：`LevelRenderer:1012-1018` —— 被渲染的前提里有一条

```java
&& (entity != camera.getEntity() || camera.isDetached() || …)   // 1014
```

**第一人称且相机未脱离时，本地玩家根本不进渲染列表**；太空维度里又只有玩家一个实体
⇒ `renderShadow` 一次都没被调用 ⇒ 既不卡，也不会有跳过日志。
`run/options.txt` 的 `entityShadows:true` 且该文件最后修改是 **09-19**（今天没动）⇒ 排除"阴影选项被关掉"。
**⇒ 触发冻死的条件是"太空里有被渲染的实体"**：第三人称，或相机脱离（正是本会话在改的"太空人称"）。

**所以这次只证明"没复现"，不能当成"修好且验证过"。** 为不再靠"没卡死"反推，补了两处自证：

1. 启动时 `[太空阴影] 锚点自检：已注入 / ★未注入` —— 反射查目标类上有没有本 mixin 的注入痕迹
   （`MixinEntityRenderDispatcher.anchorApplied()`）。Mixin 注入**成功是静默的**，这一步把成功也变成可观测。
2. `@WrapOperation` 处理里两行一次性日志：**首次拦到 `renderShadow`**（带"在太空世界=真/假"、实体、坐标）
   与**首次在太空跳过**（带实体、坐标）。

**教训（比结论更重要）**：修掉一个"卡死"之后，**必须让"拦到了"本身可观测**；
否则下一次"没卡死"极可能只是那条路径没被走到，而你会把它记成"已验证"。
判据要选**不依赖故障路径被触发**的那种（这里是启动期反射自检）。

**待实机**：重启 → 进太空 → **按 F5 切第三人称**（当初的触发路径）。预期：不卡死 +
出现 `[太空阴影] 已跳过太空世界的实体阴影（实体=LocalPlayer…）`。

**顺带记录（非本次问题）**：进太空时会打一条
`[MPS] [Physic] [step] 已存在同名线程，拒绝重复启动！`（ERROR），随后
`[PolyMech/Physics/Step/] 物理步进线程已启动：10ms/步` 正常出现、太空里 `刚体=2` 在跑
⇒ 这条 ERROR 目前看是**误报**（重复启动确实被挡住了，但不是故障），待单独确认。

**⚠️ 本轮踩的坑（Mixin 的三条硬规则，写下来免得再犯）**：

1. **mixin 类里不能有非 private 的 static 方法**。我为了做"锚点自检"在 mixin 里写了
   `public static boolean anchorApplied()`，启动直接崩：
   `MixinApplyError … InvalidMixinException: contains non-private static method anchorApplied()Z`。
   ⇒ 自检/辅助代码要放进<b>普通类</b>：现在是 `mixin/space/SpaceShadowAnchor.java`（反射按
   `polymech$` 前缀找注入痕迹），mixin 里只留 private 成员。
2. **mixin 里尽量不声明带初始化器的静态字段**（`private static final Logger LOGGER = …`）。
   它要求 Mixin 把本类的 `<clinit>` 合并进目标类，而"是否真的合并了"离线无法验证；
   万一没合并，字段就是 null，第一次拦截会在 `render` 的 try 里 NPE（比卡死更难查）。
   ⇒ 现在静态字段只有两个<b>无初始化器</b>的布尔（默认 false，不产生 `<clinit>`），
   日志器用到时再 `LoggerFactory.getLogger(...)` 取（每次会话至多两次）。
   审计方式：`javap -p` 该 mixin class，确认没有 `static {}`、没有 public/protected static 成员。
3. **`poly_mech.mixins.json` 里声明的 `package`（含子包）内的类禁止被直接引用**。
   我把自检类放进了 `com.mss.polymech.mixin.space`（当时想的是"离 mixin 近一点"），启动直接崩：
   `IllegalClassLoadError: … SpaceShadowAnchor is in a defined mixin package com.mss.polymech.mixin.*
   owned by poly_mech.mixins.json and cannot be referenced directly`。
   ⇒ 辅助/自检类必须放在 mixin 包<b>之外</b>（现为 `com.mss.polymech.client.space.SpaceShadowAnchor`）；
   该包内只放真正的 mixin，且这些类不要被包外代码 import/引用（本轮已全仓扫过一遍）。
   另外自检改到 `event.enqueueWork(...)` 里做：client setup 跑在并行 worker 上（日志里是
   `[Worker-Main-*]`），在 worker 里首次加载客户端类有并行类加载死锁的风险。

**从崩溃里白捡的一条证据**：崩溃栈能走到 `-> Apply Methods ->` 才失败，说明
**mixin 配置被加载、目标类与锚点都解析成功**，坏的只是我多加的那个方法；
由此也可反推 19:12 那次（没有该方法、且配置是 `required:true` + `defaultRequire:1`，
注入不匹配会直接报错）**守卫确实是注入成功的**，只是没被走到。

### 31.9 实机验证通过（本条闭环）

`run/logs/latest.log`（20:03 启动、20:04 进太空第三人称）：

```
20:03:45.692 [Render thread/INFO] [com.mss.polymech.Polymech/]:
  [太空阴影] 锚点自检：已注入 EntityRenderDispatcher（太空世界不画实体阴影；…）
20:04:20.172 [Render thread/INFO] [PolyMech/Space/Shadow/]:
  [太空阴影] 锚点已生效：首次拦到 renderShadow（在太空世界=true，实体=LocalPlayer，
  世界坐标=(1.6759371783969414E10, 1.0875911661388343E7, -1.4684237739771518E11)）
20:04:20.172 [Render thread/INFO] [PolyMech/Space/Shadow/]:
  [太空阴影] 已跳过太空世界的实体阴影（实体=LocalPlayer，世界坐标=同上）
```

| 行 | 它证明什么 |
| --- | --- |
| `锚点自检：已注入` | 注入痕迹确实在目标类上 ⇒ 以后不必再用"没卡死"反推 |
| `锚点已生效：首次拦到 renderShadow`（`在太空世界=true`，`实体=LocalPlayer`） | 运行时真的拦到了，**且正是当初冻死的那条路径**（太空里本地玩家被渲染 ⇒ 原版阴影循环） |
| `已跳过太空世界的实体阴影` | 跳过分支执行 ⇒ 原版 int 方块循环进不去 ⇒ 不会死循环 |

**持续性证据**：跳过发生在 20:04:20，`[位置日志]` 之后每 5 秒持续输出到 **20:06:08**
（≈110 秒，且读取时游戏仍在运行）；`物理步进被阻拦` **0** 次；
无 `InjectionError` / `MixinApplyError` / `CrashReport`；太空里 `刚体=2` 在跑。
对比修复前：进太空 5 秒内 `[Render thread]` 日志全停、该线程单核 96% 空转（§31.1）。

**仍待验证的一项**（本轮日志没有，因为本局是直接读档在太空、未走维度过渡）：
**地球 → 太空 → 回地球**两个方向的 `[坐标落点]` 是否逐位一致。
去程已验（19:13:59 那次：`宇宙系(米)` 与 `目标` 逐位相同、`约定=恒等`）。

**仍未处理的诚实缺口**（沿用 §30.14 / §31.5，刻意不擅自扩大范围）：

1. `PhysicsBodySavedData` 里存的物理体坐标仍是旧尺度；
2. **非太空维度**若坐标越过 ±2^31，同一个原版 int 循环仍会死循环（space 的锚点也只挡太空世界，属同源缺口）；
3. 进太空时那条 `[MPS] [Physic] [step] 已存在同名线程，拒绝重复启动！` ERROR
   目前看是误报（随后 `物理步进线程已启动` 正常、太空里 `刚体=2` 在跑），待单独确认。

### 31.10 收尾三件事：第四条 + 缺口1 + 缺口2（全部以 **space 0.1.3** 为准）

#### (一) 第四条：那条"拒绝重复启动"ERROR —— **是照抄来的预期行为，不是 bug**

证据链（`decompiled-space/0.1.3/`）：

- 启动包只有一个发送点：`org/polaris2023/mps/network/event/PhysicalWorldUpdateSyncEvent.java:27`，
  挂在 **`EntityJoinLevelEvent`** 上（`ReliableCreateSender.sendCritical`）——
  玩家**每次进入一个关卡**（含换维度）都会再发一次；
- 停止只有一个点：`org/deep_space_studio/space/client/ClientWorldCleanup.java:18`，
  挂 **`ClientPlayerNetworkEvent.LoggingOut`（退出世界）**——**换维度不停**；
- 于是第二次收到启动包必然撞名，space 那里同样打一条 ERROR
  （`ClientCollisionPhysicalThread.java:74`：`Can't create new thread! There is a thread with the same name in the thread pool!`）。

⇒ **不是我们的 bug**；守卫本身是对的（真起两个才会"每步推进两次"）。处置：

1. **保留完全相同的语义**（撞名 ⇒ 拒绝启动），只把**客户端**那条日志降到 WARN 并写清原因
   （诊断口径，不改行为；级别差异已在代码注释里标注来源）；
2. **补上我们真正缺的那块**：新增 `com.mss.polymech.client.ClientWorldCleanup`，
   照 space 挂 `LoggingOut` —— 退出世界时
   `ClientCollisionPhysicalThread.stopThread()` + `ClientPhysicalWorld.init()` +
   `ClientSpaceWorld.init()` + `ClientCelestialWorld.init()`。
   此前我们这三个 `init()` **只在收到 create 包时**才调用（`SyncPhysicalWorldCreate:76` /
   `SyncSpaceWorldCreate:98` / `SyncCelestialWorldCreate:150`），退出世界不复位
   —— 而 `ClientSpaceWorld` 自己的 javadoc 就写着"否则残留的影子世界会被下一局复用"。

**顺带观察（属 S6 待决，不在本轮范围）**：客户端当前有**两个**步进线程并存 ——
克隆层的 `ClientCollisionPhysicalThread`（步进克隆层 `ClientPhysicalWorld`，目前 `地形区块=0`、无刚体）
与我们自己的 `PhysicsStepThread`（步进旧 native 世界，玩家双刚体 2 个）。
两者名字不同、步进不同世界，所以不是"双倍推进"；但这就是 §30 里 S6 要二选一的那笔账。

#### (二) 缺口1：物理体存档的旧尺度坐标

- **参考物**：space 的 `PhysicalBodyWorldData` —— `get(ServerLevel)` 把数据挂在该关卡**自己的**
  `DataStorage` 上，且每条刚体的 tag 里带 `"level"`，`loadBodies(world)` 按维度过滤。
- **我们不需要搬家**：`PhysicsBodySavedData` 是"存在主世界存储、每条记录自带 `Dim`"，
  恢复路径 `PhysicsBodyTracker:838` 本来就 `server.getLevel(entry.dimension())` 取对应关卡
  ⇒ 与 space 的"按维度过滤"**职责等价**。改存储位置反而会把已有存档里的物理体变成孤儿（得不偿失）。
- **真正缺的只有"旧 ZOOM 尺度"**，已在 `PhysicsBodyTracker.restore` 补上：
  **判据与玩家迁移共用同一个函数** `SpaceScaleMigration.looksLikeLegacyScale(...)`
  （玩家那条也在本轮改成调用它，避免两处判据各自演化 —— "人搬了、船没搬"比不搬更糟）。
- **判据验算（直接调用生产代码，不是复制公式）**：`build/pm-diag/ScaleJudgeProbe.java`
  跑 `SpaceScaleMigration.looksLikeLegacyScale(boolean, boolean, double, double, double)`，
  **9/9 通过**，覆盖：旧尺度玩家位置（迁移 javadoc 引的日志值）、**太空原点附近的 ItemEntity**
  （太阳在原点 ⇒ 最近天体 1.41 ⇒ 不搬）、已迁移后的地球位置、非太空维度、旧约定，
  以及两条阈值边界（`3.0e7`、`1.0e8`）。
- **残留风险（诚实记录）**：判据是**启发式**——"小坐标 + 离所有天体 ≥1e8"无法与
  "某个确实远离所有天体、但坐标很小的正常位置"区分。玩家迁移从 §30 起就带着这个风险，
  物理体现在与它一致（**不新增第二套判据**是刻意的）。

#### (三) 缺口2：非太空维度坐标越过 ±2^31 时的同一个原版 int 循环

- **参考物**：space 0.1.3 的 `org/deep_space_studio/space/mixin/common/level/MixinWorldBorder`
  **对所有维度无条件放开边界**（注入点 getMaxX/getMaxZ/getMinX/getMinZ/isWithinBounds/
  getDistanceToBorder/getAbsoluteMaxSize/getDamageSafeZone/isInsideCloseToBorder，**没有任何维度判据**）
  ⇒ **space 自己也有这个洞**，它只在"太空实体阴影"那一条给了守卫。
- ⇒ **不动代码**，与参考保持一致；记录在此，避免下次有人"顺手加个维度判据"而偏离参考。

### 31.11 天体时间尺度：**天体的一天 = 原版的一天 = 20 分钟**（×71.8033）

- 原版一天 = `Level.TICKS_PER_DAY = 24000` tick @20 tps = **1200 秒 = 20 分钟**
  （从 `neoforge-21.1.228-sources.jar` 的 `Level.java` 读出，不是背常识）。
- 倍率 = 现实地球自转 86164 s ÷ 1200 s = **71.8033**，落地在
  `OrbitPhysicalThread.startThread()`：`core_tick_time = Config.CORE_TICK_TIME.get() * (86164.0 / 1200.0);`
- ⚠️ **绝不能改 `Config.CORE_TICK_TIME`** —— 它是 MPS 碰撞物理与 kelvin **共用**的基准 dt，
  改它会把碰撞步长一起改掉。
- 其它一切天体量（半长轴、偏心率、倾角、升交点、自转速率、自转轴倾角）保持**现实值**，
  **只缩放"时间"这一个量**。结果：地球自转 20.00 分钟、月球公转 9.13 小时、地球年 5.09 天。
- **中途两版被否，留档别重走**：① 曾取 ×60（"现实 1 小时 ↔ 游戏内 1 分钟"），用户指出要的
  是"一天 20 分钟" ⇒ 改 71.8；② 曾把地表自转绑到 `dayTime` 以对齐全天亮度（见 §31.12 末）。
- **已知副作用**：世界亮度仍走原版 20 分钟；两者**周期现在相同**（都 20 分钟）⇒
  只剩一个**固定相位差、不会再漂**。要彻底一致只有把光照改成由太阳高度角驱动。

### 31.12 自转轴：用真实黄赤交角自己构造，**space 的 `rotate` 数据不可照抄**

- 反推（`build/pm-diag/SpaceRotateProbe.java`，读 space 0.1.3 的 `object/*.json`）：
  其 `rotate` **第 4 分量恒为 0**；读作 (w,x,y,z) 时其中 7 颗的第 1 分量恰好是 `cos(ε/2)`
  （地球 0.979→23.4°、火星 0.9759→25.2°、木星 0.9996→3.2°、水星 1.0000→0°），
  但**轴的位置不对**（地球那条轴 ≈ 竖直，只把 Y 扭了 2.84°，不是倾斜）；
  土星/海王星/天王星三种读法都对不上现实交角，且土星/海王星那两条**不是单位四元数**
  （模长 0.9894 / 1.0189）。
- ⇒ **不用它的数值**，只用公开天文常数自己构造（与质量、自转周期同一条规矩）：
  - `RealAstroData.AXIAL_TILT_DEG`：10 个主天体的 IAU 黄赤交角（地球 23.439、火星 25.19、
    天王星 97.77、金星 177.36…）；10 颗卫星按**潮汐锁定**继承母星倾角。
  - `rotateQuaternion()`：约定按**我们自己的映射** —— `space = R_body⁻¹·v + bodyPos`
    ⇒ **`R_body·Y` 必须等于真实北极方向**；把 +Y 转到
    `n̂ = (sinε·cosλ, cosε, sinε·sinλ)` 的最小旋转，按读取端的 **(x, y, z, w)** 序列化。
  - **验收（读产物，不是读代码）**：`build/pm-diag/TiltProbe.java` 读**生成出来的 JSON**、
    按读取端约定算极点倾角 ⇒ **20/20 与交角一致，误差 < 0.01°**（专防写反一个分量）。
- **残留**：极点方位 λ 统一取 90°（与地球同向）⇒ 交角大小 100% 真实、**季节相位**不是逐体真实。
  精修要把 IAU 极点 (α₀, δ₀) 换算到黄道系、逐体填 λ 表。
- **`dayTime` 绑定那版**：代码留在 `ClientCelestialWorld`，由
  `BIND_SURFACE_SPIN_TO_DAYTIME = false` 关着（改 `true` 即回退）。它的用途是让天空与**原版亮度**
  严格同步；被否的原因是"天体时间 = 现实×71.8"与"按原版 20 分钟转"是两套时钟，只能二选一。
- **一处判据订正**：自检里"黄道相对地平线不应随时间变"**只在 ε=0 时成立**；有真实黄赤交角后
  该角全天在 **90°±ε** 之间摆动，**摆动是正确的**（真实天空亦然），别当 bug 追。

### 31.13 待办：卫星轨道面要搬到**母星赤道面**（改一半会更糟，注意初始化顺序）

- 现状：卫星**位置**由 `RealAstroData.ofSatellite` 构造在**黄道面（XZ）**上
  （`parent + r·(cosφ, 0, sinφ)`），**速度**由 `ModSpaceDataProvider.circularVelocity`（黄道面内顺行）；
  而它们的 `rotate` 已按 §31.12 继承母星倾角 ⇒ **自转轴与轨道面不一致**，
  这是"天体连成一条线、且那条带子会扭"的根因。
- 正确做法（**位置与速度必须同时改**，只改一半会得到不自洽的轨道）：
  1. 母星极轴 `p = (sinε·cosλ, cosε, sinε·sinλ)`；正交基 `u = normalize(X × p)`、`v = p × u`；
  2. 位置 = `parentPos + r·(cosφ·u + sinφ·v)`（φ 仍取任意相位 —— 数据表本就没有 J2000 卫星相位）；
  3. 速度 = 绕 p 顺行：`v⃗ = √(GM/r)·(−sinφ·u + cosφ·v)`，角动量 `r×v ∝ +p`；
  4. 可再叠各自的真实轨道倾角（对母星赤道）：火卫 ≈1°、木卫 0.04°~0.5°、土卫 0.3°~1.5°；
     **月球是例外**（轨道接近黄道面，5.14°）。
- ⚠️ **初始化顺序坑（必须同时处理）**：`ofSatellite` 是在静态字段初始化时被调用的（行 ~74），
  而 `AXIAL_TILT_DEG` 现在声明在文件**末尾** ⇒ 若直接在 `ofSatellite` 里读它，
  会拿到**尚未初始化**的表（倾角全 0 或 NPE，整类初始化失败 = mod 起不来）。
  **必须先把 `AXIAL_TILT_DEG` 整块移到天体条目之前**，再动 `ofSatellite`。
- 另注：`axialTiltDeg()` 对卫星会回落到 `PARENT_BY_ID`，而该表也声明在条目之后 ⇒
  初始化期只能依赖"母星在 `AXIAL_TILT_DEG` 里直接命中"这条路径（行星作母星时满足）。

### 31.14 卫星赤道面改造完成 —— **验收当场抓出"卫星一直逆行"**（只验周期的教训）

**已落地**（位置与速度**同时**改，这是硬要求：只改一半会得到"位置在赤道面、速度在黄道面"的不自洽轨道）：

- `RealAstroData.ofSatellite`：相对位置 = `r·(sinφ, −sinε·cosφ, cosε·cosφ)`，与母星极轴
  `p = (0, cosε, sinε)` **精确正交**（`·p ≡ 0`）。ε 取母星倾角；
  **月球是例外** —— 它的位置是**真实星历**（不是构造的）、轨道近黄道面（5.14°）而非地球赤道面，
  所以 ε 取 0。`AXIAL_TILT_DEG` 已按 §31.13 移到天体条目**之前**（行 52 < SUN 行 65），
  静态初始化期可读。
- `ModSpaceDataProvider`：把相对位置旋进**母星赤道系**（那里极轴 = +Y）→ 用既有
  `circularVelocity` 取圆速度 → 旋回宇宙系。顺行交给既有函数，不自己抄引力常数。

**验收判据（两条，读产物 JSON，不读代码）**：

1. `相对位置·极轴 ≈ 0` —— 实测 io / titan / phobos / europa **全为 `0.0000 m`** ✔
2. `轨道法向·极轴 = +1`（顺行）—— 实测 **`+1.00000000`** ✔

**验收当场抓到的旧账（真 bug）**：第一次复验四条全是 **`−1.00000000`** ⇒ `circularVelocity`
给的方向是**逆行**的 ⇒ **卫星此前一直绕着母星倒着转**。过去只验过"周期误差 0.01~0.05%"，
而**周期对逆行不敏感** ⇒ 一直没暴露。已取负纠正。

> **教训（比本次修复更值钱）：验"周期对"≠验"方向对"。凡是绕转类构造，必须同时验三件：
> ① 轨道**面**（法向对不对）② 绕转**方向**（顺行/逆行）③ 周期/速率。**
> 本项目已经因为"只验一半"翻车两次（另一次是 §31.8 的"没卡死 ≠ 已验证"）。

**顺带确认**：行星走的是另一条路径 `velocityFromElements`（法向 `n̂` 的符号是按 space 数据校准的、
`v̂ = n̂ × r̂` ⇒ `L ∝ +n̂`）⇒ **构造上就是顺行**；有此问题的只有卫星那条 `circularVelocity` 路径。

**待办：极点方位 λ 精修**。现在统一取 λ = 90°（与地球同向）⇒ **交角大小已真实、季节相位未逐体真实**。
做法：表里改存 IAU 极点 `(α₀, δ₀)`，生成时按

```
x = cosδ·cosα,  y = cosδ·sinα,  z = sinδ                       (赤道直角坐标)
y' = y·cosε_ecl + z·sinε_ecl,  z' = −y·sinε_ecl + z·cosε_ecl   (转到黄道系, ε_ecl = 23.4392911°)
λ = atan2(y', x')
```

再按 §31.12 的 `n̂ = (sinε·cosλ, cosε, sinε·sinλ)` 构造四元数。**下面这些是凭记忆列的近似值，
入表前必须逐个复核**（这份清单只是"从哪儿找"的索引，不是可信数据）：
水星 (281.01, 61.45)、金星 (272.76, 67.16)、地球 (0, 90)、火星 (317.68, 52.89)、
木星 (268.06, 64.50)、土星 (40.59, 83.54)、天王星 (257.31, −15.18)、海王星 (299.36, 43.46)、
冥王星 (132.99, −6.16)、太阳 (286.13, 63.87)、月球 (269.99, 66.54)。

### 31.15 天空"东西南北对不上"的两步归因（90° → 180°），以及**判据自己写错**的那一次

用户诉求（原话）："你需要按照 mc 的东西南北来，我在星球上就是需要和 mc 的东西南北对应的天空"。
实机症状一路变：**朝南时太阳在右手边** → 上午的太阳偏 90° → 最后"上午的太阳在南边"。

**归因必须分开做，因为这是两个独立的错，而它们的画面症状长得一样**：

| 步骤 | 病灶层 | 判据 | 结果 |
|---|---|---|---|
| ① 90° | **映射**（`getSpacePosFromWorldPos` 的经纬度取哪根轴） | `MC罗盘: 世界东·物理东 / 世界南·物理南` | 两根轴都 `0.0000` ⇒ 确认差 90° |
| ② 180° | **映射的手性**（经度增大的方向是东还是西） | 同上 | 两根都 `−1.0000` ⇒ 差 180° |
| ③ 任意角滚转 | **姿态帧**（`getRotateFromWorldPos` 只钉了"上"） | `姿态帧检查`（§31.16 新增） | 见 §31.16 |

**①→②的中间还有一次"改对了但判据说错"**：为了让"世界 +X = MC 东"成立，把经度符号取反；
随后判据报 `世界南·物理南 = −1`。当时差点又去改映射 —— 实际是**判据自己把南算成了北**：
在 lon=0 处 `east = 极轴×up = −Z`，`east×up = (−Z)×X = −Y`，而 `−Y` 就是**南**，
判据里却多写了一个 `negate()`。**判据修好后立刻 PASS**。

> **教训：判据报 FAIL 时，先确认判据自己没写错，再去改被测量的东西。**
> 一个"看起来在验别人的"表达式，它自己也可能把标签贴反（这里就是把北叫成了南）。
> 做法：判据里每个向量都写上"它按定义是什么"（`east = p×up`、`south = east×up`），并给一个
> 已知位形的手算值当锚（例如 lon=0 处 `east` 必须指向 −Z）。

**最终采用的映射**（**有意偏离** space 0.1.3，理由就是上面那张表）：

```
longitude = −x0 / L · (π/2)        // 世界 +X（MC 东）⇒ 经度**减小**方向；§31.15 实测标定
latitude  = −z0 / L · (π/2)        // 世界 +Z（MC 南）⇒ 纬度减小
```

`EarthSpaceMapping` 的过渡映射与 `getWorldPosFromSpacePos`（逆映射）同步改，三处符号必须一致 ——
只改一处会得到"进去和出来不是同一个点"，那比不匹配更难查。

### 31.16 姿态帧：**只把 +Y 转到径向是不够的**（另一个自由度的坑）

`getRotateFromWorldPos` 原来是 space 的写法：`fromToQuaternion((0,1,0), up).invert()` ——
一个把 `+Y` 转到径向的**最小旋转**。它保证"上"对，但**完全不管世界 +X / +Z 落到哪里**：
绕"上"轴那一个自由度是空着、由插值约定任意选定的。

**这就是"映射判据全 PASS，画面里上午的太阳却在南边"的原因**：映射和数据都对，
错的是"站姿"这层 —— 天空整体绕天顶滚了一个任意角度。

**把症状算出来对一下（这一步让"归因"变成"可验算的预言"）**：旧写法的最小旋转轴是
`(0,1,0) × up`，也就是**绕世界 Z 轴转 90°**（赤道情形）。把它当 `q_old = R⁻¹` 算两个点：

| 观察者位置 | 物理东 = 极轴×up | `q_old` 把物理东映到 | 画面上"日出方向"出现在 |
|---|---|---|---|
| 赤道 lon=0（世界 x0=0 一侧） | `(0,0,−1)` | `(0,0,−1)` = 世界 **−Z** | **北** |
| 赤道 lon=180°（世界 x0 反号一侧） | `(0,0,+1)` | `(0,0,+1)` = 世界 **+Z** | **南** ← 用户截图 |

**用户的基地正好在 lon≈180° 那一侧 ⇒ 日志里 `罗盘对齐=PASS`，画面上"上午的太阳在南边"。**
90° 的滚转在球面不同位置会表现为"东跑到北"或"东跑到南" —— 这正是同一条病、
同一个自由度的两种长相，**不要因为"症状换了个方向"就以为是新的病**。

**修法**：三根轴一起钉死，用 ((X,Y,Z) → (东,上,南)) 这个**完整旋转**：

```
up    = 径向（该点在宇宙系的方向）
pole  = getSurfacePole(partialTick)     // = spin⁻¹·Y，**不能写死 (0,1,0)**，见下
east  = pole × up
south = east × up
Matrix3f 的**三根列** = (east, up, south)   → q = setFromNormalized(m).invert()
```

`(东,上,南)` 与 `(X,Y,Z)` 都是右手系（已验 `东×上=南`，行列式 +1）⇒ 是合法旋转、**无反射**，
且方位自由度被钉死 ⇒ 天空与 MC 的东西南北一一对应。

**"极轴不能写死 +Y"**：映射是相对**天体本地 +Y** 量纬度的，宇宙系里 `up = spin⁻¹·v̂`，
于是 `北极 = spin⁻¹·Y`。地表客户端把 `surfaceSpinRotate` 换成了**纯 Y 的时钟角**，
此时 `spin⁻¹·Y = Y` 恰好相等 —— 写死**看不出错**；但一旦切回物理自转
（`rotate = tilt·rotateY(ωt)`，有真实黄赤交角），`spin⁻¹·Y ≠ Y`，
写死就会让"东"和纬度整体算歪。所以新增 `CelestialWorld.getSurfacePole()` 把
**映射 / 姿态帧 / 诊断判据**三处统一到同一个来源。

**⚠️ 本条的修法第一版是错的，而且是被离线探针当场抓到的**（详见 §31.17）：
JOML 的 9 参 `Matrix3f` 构造是**列主序**，按"行"填进去会得到目标的**转置**，
而旋转矩阵的转置 = 逆 ⇒ 姿态帧整个反过来。症状与 §31.16 开头那条**一模一样**
（赤道 lon=0 处"物理东"被映到世界 `+Y` 天顶 = 偏 90°）。
**注意游戏内的 `罗盘对齐` / `方位检查` 当时全是 PASS —— 只看游戏内判据会漏掉这一类错。**

### 31.17 离线探针 `FrameProbe`：把"读代码觉得对"变成"真的验过"

一块纯数学 + 一个第三方库的**存储约定**（JOML 是列主序还是行主序？
`setFromNormalized` 把矩阵当 local→world 还是 world→local？）**光读代码是定不下来的**，
而猜错的代价是"天空整体转 90°"这种要重启客户端才看得见的症状。

`native/jni-smoketest/FrameProbe.java`：用**与模组同一个 joml 版本**（gradle 缓存里的 1.10.5），
把源码那个构造方式逐字搬过来，对 4 个观察点（赤道 lon=0 / 赤道 lon=90° / 45°N / 30°S·120°）
各验 9 条：

- `Matrix3f` 的三根列 == 传入的 (东,上,南)（**这一条直接钉死"列主序还是行主序"**）
- `物理东→世界 (1,0,0)`、`物理上→世界 (0,1,0)`、`物理南→世界 (0,0,1)`
- `东×上=南`（右手系、无镜像）
- 相机约定自检：`yaw=180°` 时前向量 = `−Z`（北）、左手边 = `−X`（西）——
  这两条是拿 MC 自己的 `Camera.setRotation`（`rotationYXZ(π − yaw·π/180, −pitch·π/180, −roll·π/180)`，
  见 `build/mcsrc/net/minecraft/client/Camera.java` 第 121 行）当**独立真值**，防止探针自己也把朝向搞反
- **面朝北看上午的太阳 ⇒ 在屏幕右侧**（端到端：东 → 世界轴 → 相机视空间）

**跑法（一条命令，两项一起跑）**：

```powershell
.\native\jni-smoketest\run-offline-checks.ps1
```

它同时跑 §31.14 的 `OrbitAcceptanceTest` 和 `FrameProbe`，退出码 0 = 全通过。

> **两条元教训**：
> ① **能在离线定的东西不要留到实机定**。这个探针在用户重启之前就抓出了我自己刚写下的 bug，
>    省掉一轮"看图→重启→还是不对"的循环。
> ② **游戏内判据可以全绿而画面仍是错的** —— 因为判据只覆盖它自己那一段链路
>    （`罗盘对齐`/`方位检查` 只用到映射与向量，压根没碰 `getRotateFromWorldPos`）。
>    加判据时必须说清"这条判据覆盖到哪一段、哪一段它管不着"。
>
> **配套**：脚本 `run-offline-checks.ps1` 必须带 **UTF-8 BOM** 保存。Windows PowerShell 读无 BOM
> 的脚本会按 GBK 解，中文字符串的尾字节会把后面的引号吃掉，直接报
> `The string is missing the terminator`（第一版就是这么挂的）。

### 31.18 地表天空的四条判据（一次重启就能拿到全部结论）

`SpaceRenderer.surfaceSkyDiag` 每秒打一行 `[Kelvin] [地表天空]`，现在含四条**互补**判据 ——
每条覆盖链路的不同一段，"哪条 FAIL"直接指出病灶层，不用看图猜：

| 判据 | 覆盖的链路 | 用到的量 | FAIL 意味着 |
|---|---|---|---|
| `MC罗盘: 世界东·物理东 / 世界南·物理南` | 映射（世界↔宇宙） | 只用映射 + 向量 | 映射的轴取错（90°）或手性错（180°） |
| `姿态帧检查` | **渲染站姿四元数** | 真的把 `getRotateFromWorldPos` 作用到东/上/南 | 天空绕天顶滚了 / 被镜像 |
| `方位检查` | 太阳的**几何**方位 | 时角 + 物理东（不含任何映射约定） | 太阳的地平方位与时钟不符 |
| `画面对齐` | **投影合成**（`view = cameraRot·spaceRotation`） | 帧预测的屏幕侧 vs `vp` 投影出的 ndc 符号 | 合成顺序/逆方向写错 |

外加 `屏幕ndc=(x, y) w=…` —— 与用户截图上的位置可直接对照；`w<=0` = 太阳在相机背后。
**四条全 PASS ⇒ 上午的太阳必然在屏幕的东侧**（映射对 + 帧对 + 方位对 + 画面对）。
整段自检被 try/catch 包着（诊断代码绝不允许崩客户端，§31.8 的 `%d` 事故）。

### 31.19 "跟抽帧一样"的根因：**每帧都在把插值的两端推平**

用户 2026-09-25 原话："不管是在哪，我看到的星球的移动都是很低帧率的…跟抽帧了一样，一点都不丝滑"。

**根因（三段链条，缺一不可）**：

1. `CelestialBody.getSmoothPos(partialTick)` = `lerp(old_pos, pos, partialTick)` —— 插值靠**两端**；
2. `moveToDirect(v)` 的第一件事是 **`old_pos.set(pos)`**（"物理线程用：立即改，old_pos 留给渲染插值"）；
3. `ClientSpaceWorld.syncMoveData()` 被**每帧**调用（space 在 `SpaceRenderer.init` 每帧一次；
   我们另有 `ClientPhysicsDriver` 每 tick 一次），而网络包只有**每 tick**一份。

于是同一个包值被反复搬进去：

```
第 1 帧（新包刚到）：old_pos = P(t−1), pos = P(t)   ← 插值还有救
第 2 帧起（同一个包）: old_pos = P(t),   pos = P(t)   ← 被推平
```

⇒ `lerp` 恒等于 `pos` ⇒ **插值完全失效**，天体只能在包到达那一瞬跳一格。
注意 `ClientSpaceWorld` 自己的 javadoc 写的就是"突变只发生在同步点，同步点之间交给 getSmoothPos"——
**实现把自己的前提拆掉了**。

**这是"偏离参考"还是"修正抄错"？** 如实记录：`decompiled-space/0.1.3/.../ClientSpaceWorld.java:24-36`
与本项目**逐字相同**，`SpaceRenderer.java:106` 也是**每帧**调用 ⇒ **space 0.1.3 同样会跳**。
所以这是偏离，依据只有它自己写下的意图。若要回到逐字同形：把
`ClientSpaceWorld.syncMoveData()` 里那个 `if (!pos.equals(...) || !rot.equals(...))` 删掉即可，其余一字不用改。
影响面仅限**渲染采样**（显示世界是纯客户端影子，不参与任何权威状态）。

**判据（实测，不是推理）**：`|getSmoothPos(1) − getSmoothPos(0)|` = `|pos − old_pos|`
（不需要任何新 API）。**= 0 就是插值已死**。

### 31.20 "一天到底多少分钟""是 71.8 还是 72"——用实测回答

用户三问：速度是不是 20 分钟一天？该取 71.8 还是 72？为什么看着比原版快？

**① 71.8033 是"定义出来"的数，不是四舍五入出来的**：

```
timeScale = 86164 / 1200 = 71.80333…        (OrbitPhysicalThread.timeScale)
             ↑ 地球自转一周的真实秒数（恒星日）
                        ↑ 原版一天 = 24000 tick ÷ 20 tps = 1200 秒
```

即"**地球自转一圈 = 原版一天 = 20 分钟**"这条设计本身。取 72 会让物理自转比时钟快 0.27%
（20 分钟里差 3.3 秒）—— 在"地表天空由 `dayTime` 驱动"的今天看不出来，
但**物理自转与时钟会慢慢错开**，属于白白引入一个漂移。**结论：71.8033，不要取整。**

**② 地表天空按原版时钟走，所以速度必然等于原版**：`ClientCelestialWorld.surfaceSpinRotate`
用 `clock = 2π(dayTime−6000)/24000`，`dayTime` 是原版计时 ⇒ 天空转速 = 360°/20 分钟 = **0.300 °/秒**，
与主世界一字不差；升落节奏同样是 t=0 日出、6000 正午、12000 日落、18000 午夜。

**③ 那"看着快"是什么**：是**角直径**，不是角速度。原版太阳是一个**半宽 30 的方片画在距离 100 处**
（`LevelRenderer` 里 `f12 = 30.0F`，顶点 `(±f12, 100, ±f12)`；字节码已核：`ldc float 30.0f` → `fstore 18`，
且 `renderSky` 里**没有任何 `PoseStack.scale`**）⇒ 视角 **2·atan(30/100) ≈ 33°**（月亮是半宽 20 ⇒ ≈ 22.6°）；
真实太阳只有 **0.53°** ⇒ **原版大 60 倍以上**。同一个 0.300°/秒下，小圆盘"穿过自身直径"的速度快 60 倍，
再加上当时天空没有云/星空做参照物 ⇒ 主观上就是"快得多"。用户自己的猜测
（"原版太阳大，所以显着很慢"）**是对的**，这里把它量化了。

> ⚠️ 这个数**第一次写错过**（写成 17°）：把"半宽 30"当成了"全宽 30"，于是算成 `2·atan(15/100)`。
> 数是从字节码里重新取出来的（`ldc float 30.0f` → `fstore 18`，顶点 y 常量 `100.0f`），
> **凡是要写进日志/文档的"原版常数"，都按这个办法从实产物取，不要凭记忆推。**


**新增的实测判据**（都在 `[Kelvin] [地表天空]` 那一行里）：

| 判据 | 期望 | 不合格意味着 |
|---|---|---|
| `时钟: X tick/秒` | 20.0（⇒ 一天 = 20.00 分钟） | 世界时钟被别的东西改过 |
| `天空转速: X°/秒` | 0.300（且应 ≈ 时钟 × 360/24000） | 时钟项与转速不自洽 |
| `顺滑: 插值跨度` | **> 0** | `= 0` ⇒ 插值已死 = 抽帧（§31.19） |
| `顺滑: 位姿搬运 X 次/秒` | ≈ 20 × 天体数 | ≈ 帧率 × 天体数 ⇒ 每帧都在推平 `old_pos` |
| `太阳角直径` | 0.53°（原版约 17°） | 用来解释主观速度差 |

**同时修掉的一处不连续**：`clock` 原来只用整数 `getDayTime()`（每 tick 才 +1）⇒
整片天空一秒只动 20 次、每次 0.015°，而相机与其它按 `partialTick` 插值的量是连续的 ⇒ 两种节奏混在一起。
改成 `(dayTime % 24000) + partialTick` 后**转速一点不变**，只是不再以 tick 为粒度跳。

### 31.21 原版天空：从"整片掐掉"收窄成"只掐日月"（用户拍板）

**旧行为**：`LevelRendererCelestialSkyMixin` 在 `renderSky` 的 HEAD 直接 `ci.cancel()` —— 地表维度
**整片原版天空都不画**（渐变、日落红染、星空、雾全没）。这是照 space 0.1.3 的
`org.cn_grass_block.sunshine.mixin.renderer.MixinLevelRenderer` 抄的判据，属于**临时排障手段**
（旧注释写明"在我们补上自己的地表天空之前"，目的是"先清干净再判读我们的天体画在哪"）。
代价：天空只剩一个平坦雾色 —— 用户评价"**天空太干净了**"，并主张"把原版日月关掉就可以了"。

**新行为**：`SpaceDimensionEffects` 的 `SkyType` 由 `NONE` 改 `NORMAL`（NONE 会让原版 `renderSky`
既不进 END 也不进 NORMAL 分支 ⇒ 上面那些全没有）；mixin 改成**只把原版日月那两次"换贴图"调用
换成一张全透明贴图**。

**为什么用"换贴图"而不是"跳过绘制"**：原版日月与星空**共用同一段姿势栈**
（`pushPose` → 太阳 → 月亮 → 星空 → `popPose`），跳绘制容易让 `pushPose/popPose` 与
`RenderSystem` 状态（blendFunc、setShaderColor）失配，那类错会污染之后所有渲染。
换贴图**不可能改变任何状态**（顶点照画、矩阵照用），像素 alpha = 0 ⇒ 在 `(SRC_ALPHA, ONE)` 下贡献恰好 0。
失败模式也安全：判据没命中的最坏结果是"原版日月又露出来"，而不是花屏或崩溃。

**注入点全部按实产物字节码核对（不靠读源码猜）**：

```powershell
javap -c -p -cp build\moddev\artifacts\neoforge-21.1.228.jar net.minecraft.client.renderer.LevelRenderer
```

- `renderSky` 里 `setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V` **恰好 2 处**
  （字节码偏移 647 / 772），紧邻的 `getstatic` 分别是 `SUN_LOCATION` / `MOON_LOCATION`；
  全类共 6 处，其余 4 处在别的方法（雨/雪/末地天空/力场）✓
- `LevelRenderer.<clinit>` 里 `SUN_LOCATION = textures/environment/sun.png`、
  `MOON_LOCATION = textures/environment/moon_phases.png` —— mixin 就按这两个路径串比对 ✓
- **NeoForge 的钩子在 `renderSky` 的最开头**：`level.effects().renderSky(...)` → `ifeq 31` → `return`
  ⇒ 太空维度（我们的 `renderSky` 返回 true）**根本走不到**那两次换贴图，不会被误伤 ✓

**两个必须避开的 mixin 坑（本轮各踩到一次）**：
① 被包装的 `RenderSystem.setShaderTexture` 是**静态**方法 ⇒ 处理器也必须是 `static`；
② 全透明贴图**不能**写成带初始化器的 `static final` 字段（那要求合并 `<clinit>`，离线无法验证；
   万一没合并字段就是 null，会把 null 贴图交给 `setShaderTexture`）⇒ 现用现建。

**资源**：`assets/poly_mech/textures/environment/blank.png`（16×16 全透明，创建后回读校验 `max alpha = 0`）。

### 31.22 教训：**探针与证据不要放在 `build/` 下**

本轮为了强制全量重编跑了 `gradlew clean`，它删掉了整个 `build/pm-diag/`（历次离线探针
`ShadowLoopProbe` / `ScaleJudgeProbe` / `SpaceRotateProbe` / `TiltProbe`、jstack 存证、
以及 `build/mcsrc` 里的 MC 源码副本）。它们的**结论**都已写进本文档，所以知识没丢，
但**可复跑的探针本体丢了**（`build/` 本来就在 `.gitignore` 里，等于从来没进过版本库）。

> **规矩**：凡是"以后还要再跑一遍"的探针，一律放 `native/jni-smoketest/`
> （`FrameProbe.java`、`OrbitAcceptanceTest.java`、`run-offline-checks.ps1` 都在那儿，本轮已就位）；
> `build/pm-diag/` 只能放**一次性**排查中间产物。
> 另：`clean` 在本项目会因 `build/moddev/artifacts/*.jar` 被占用而**中途失败**，删一半留一半 ——
> 要强制重编请用 `gradlew classes --rerun-tasks`，**不要用 clean**。
>
> ⚠️ **本文档下文（以及上文 §31.2、§27.x 等处）凡引用 `build/pm-diag/*.java` 的探针，都已经不存在了**
> （本轮 `clean` 删掉，共 9 处引用）。要复用请按所在节的描述**重建**，别以为只是找不到文件。

### 31.23 **死判据**：查表恒为 null 的判据比没有判据更糟

本轮加"距角自检"时写了一句 `RealAstroData.byName(o.planetName())`，编译通过、逻辑看着对，
但**永远不会执行**：`PlanetRenderObject.planetName()` 返回的是 **id**（`"sun"`/`"mercury"`，见该方法
javadoc"与 {@code RealAstroData#byId(String)} 一致"），而 `byName` 查的是**中文名**表 ⇒ 恒 null。

**顺带查出同一循环里一条早就存在的死判据**：`RealAstroData.SUN.name().equals(o.planetName())`
比的是中文"太阳" vs id `"sun"` ⇒ **恒 false** ⇒ 太阳永远被判为"不在渲染表里"
（日志里那句 `太阳(不在渲染表里)` 就是这么来的），每次都走兜底分支。
兜底分支本身写对了，所以**没有产生错数** —— 但那条比较是死的，谁也不知道。

> **教训**：判断"判据没报 FAIL"时，必须先确认**判据真的跑了**。
> 做法：凡是新增判据，先在日志里给它一个**必然出现的正输出**（本项目的做法是打印实测值本身，
> 例如 `距角=xx.x°(上限yy.y°）`），而不是只在 FAIL 时才打印。
> "没有输出" 与 "输出正常" 在日志里长得一模一样，这是死判据能长期潜伏的原因。

### 31.24 太空维度：**位置压根没插值**（与地表是两条不同的路）

用户 2026-09-25："我咋看着太空维度里的星球移动还是不够流畅呢？我传送到了地球上空的太空里看的。"

§31.19 修的是**显示世界**的 `old_pos` 被每帧推平，受益的是**地表**那条路（它读 `getSmoothPos`）。
**太空那条路根本不用这对值**：它走 `PlanetRenderObjectFactory.refreshPositions()`
→ `SpaceWorld.gamePos` → `blockPos` → `kelvinPos` → `body.getPos()` —— **物理步进后的原始值**。
所以"不够流畅"当然还在：这就是"还是"两个字的由来（上一轮的修复对这条路完全无效）。

**修法**：新增 `SpaceWorld.blockPos(RealAstroData, float partialTick)`
（X/Z 取 `getSmoothPos` 的插值值，**Y 仍按方块口径压平** —— 理由见该重载的注释），
`PlanetRenderObjectFactory.refreshPositions(float)` 改用它，渲染路径传同一帧的 `partialTick`。

**同时必须改投射者**：`PlanetRenderObject` 里三处阴影/反照率投射者原来也读 `gamePos(caster)`。
本体插值、投射者不插值 ⇒ 两者每 tick 相对跳一次，表现是"影子自己在天体表面一跳一跳"。
三处都改成 `blockPos(caster, params.partialTick())`，与本体同口径。

**数值有多大（这决定了插值是不是肉眼可见）**：天体走的是真实轨道速度 × 71.8 倍时间：

| 天体 | 真实轨道速度 | **每 tick 位移**（×0.05s×71.8033） |
|---|---|---|
| 地球 | 30,151 m/s | **108.2 km** |
| 月球 | 30,304 m/s | 108.8 km |
| 火星 | 26,719 m/s | 95.9 km |
| 木星 | 12,523 m/s | 45.0 km |

`/polymech` 的"传送到某天体上空"把玩家放在 `max(2.2R, R+150)` 处（地球 ≈ 2.2 个半径）。
在这个距离上，地球每 tick 扫过 `atan(108.2km / 1.4e7m) ≈ 0.443°` ⇒ **20Hz 硬跳时每步约 7 像素**，
是肉眼一眼可见的台阶 —— 插值后变成连续的 ≈8.9°/秒。

**新增探针 `[Kelvin] [太空视运动]`**（1 秒一行，只在太空维度），把"不流畅"拆成三种互斥成因：

| 打印项 | 含义 |
|---|---|
| `视运动=…°/秒 每帧=… px` | 每帧 <1 像素 ⇒ 真顺；≥1 像素 ⇒ 还会看着跳 |
| `插值跨度=… m` | **=0 ⇒ 位置没插值**（就是本条的病；修好后应 ≈ 每 tick 位移，地球≈1.08e5） |
| `帧率=… fps` | 客户端本身卡不卡 |

**顺带确认（不必再查）**：行星**自转**一直是连续的 —— `PlanetRenderObject` 用的是
`rotationSpeed * params.simTime()`，而 `simTime = gameTime/20 + partialTick/20` 带 partialTick
（`PlanetRenderObject:919`、`:990`）。所以太空里"唯一"按 tick 跳的量就是位置。

**遗留的设计问题（不是 bug，需用户拍板）**：即使插值后，
"停在天体上空看它"在这个时间尺度下**本来就不成立** —— 地球的等效速度是 `108.2km/0.05s ≈ 2164 km/s`，
而传送过去的玩家速度是 `Vec3.ZERO`（`PlanetDimensions.teleportToSpaceAbove`），
所以地球会在约 8 秒内扫出视野。真要"停住"，只能走**天体参考系**（渲染时减去参考天体的位置），
而不是给玩家设速度 —— 2164 km/s 这种量级 vanilla 的移动/区块管线根本承载不了。

### 31.25 "还是不丝滑"的真正根因：**两拍 lerp 要求"交换发生在 tick 边界"**

用户 2026-09-25 第二次报："等一下，天体移动还是不丝滑啊……不管做哪个方案，你都得先把天体运动做丝滑了。"

**这次不是"没插值"，而是插值本身有个隐藏前提。** space 的两拍 lerp：

```
moveToDirect(v): old_pos ← pos;  pos ← v          ← 这次"交换"
getSmoothPos(φ): lerp(old_pos, pos, φ)
```

**它默认交换发生在 φ = 0（tick 边界）**，只有那样两段折线才首尾相接。而
`ClientSpaceWorld.syncMoveData()` 是**每帧**调的、包是**每 tick**到的，
于是交换落在一个**任意相位 φ₀**：

```
交换前：P(t−1) + φ₀·Δ
交换后：P(t)   + φ₀·Δ   = 交换前 + **Δ**
```

⇒ **每个 tick 凭空跳一整个采样间隔 Δ**（跳变幅度 = Δ，与 φ₀ 无关 ⇒ 20 次/秒）。
太空里 Δ 有多大：地球每 tick **108.2 km**；站在 2.2 倍地球半径（1.4e7 m）处看，
Δ ≈ **6.9 像素**，而顺滑运动本身每帧才 2.3 像素 ⇒ 每秒 20 次、幅度约 3 帧的跳变。

**离线对拍（`native/jni-smoketest/InterpProbe.java`，同一串带相位漂移的样本跑两种实现）**：

| 实现 | 每帧最大位移 | 抖动(max/中位) | **与真值最大偏差** |
|---|---|---|---|
| 旧：两拍 lerp（按 partialTick） | 9.06 px | 4.15 | **6.80 px** ← 就是那个 Δ |
| 新：时间戳插值（延迟 100 ms） | 2.25 px | 1.09 | **0.024 px** |

**修法**：不再假设交换发生在哪个相位 —— 给每拍打**时间戳**，渲染时取
`渲染时刻 = 现在 − 100ms`，在**包住该时刻的相邻两拍**之间插值（`CelestialBody` 里加 64 条快照环）：

- 交换落在哪个相位都无所谓（只是往时间轴末尾追加一拍）；
- 网络/线程抖动只造成"速率短暂变化"，**不会造成位置跳变**；
- 代价是渲染延迟 100 ms（肉眼不可见；天体**自转**走的是 `simTime`，不受影响）。

**这是对 space 的有意偏离**（它只有两拍 lerp）。回退点很干净：把 `getSmoothPos`/`getSmoothRotate`
换回 `lerp(old_pos, pos, partialTick)` 即可，字段与 `pushSample` 都不用删。
快照不足时（样本 < 2 条）**自动退回** space 的原始两拍 lerp。

**顺带修掉一个会假报警的旧判据**：`|getSmoothPos(1) − getSmoothPos(0)|` 在时间戳口径下
取的是**同一时刻**，差值恒为 0 ⇒ 会被读成"插值已死"。改用新增的
`CelestialBody.sampleSpan()`（= `pos.distance(old_pos)`）与 `sampleIntervalSeconds()`。

**新增的"丝滑度"客观判据**（`[Kelvin] [太空视运动]`，**每帧累计、每秒一行**）：

```
每帧位移(px) 均=… 最大=… 最小=… 抖动(max/中位)=… 卡帧=n/总 ⇒ 顺滑=PASS(抖动<1.30)
样本间隔(ms) 均/最大/最小    ← 拍子抖动（跳变的来源）
```

- 为什么用 **max/中位**而不是 max/均值：天体掠过时角速度**本身在变**（越近越快），
  均值会被加速段抬高、把真 bug 掩盖掉；中位数对真实的加速免疫、只对跳变敏感。
  阈值 1.30 是上面那张对拍表标定出来的（旧 4.15 / 新 1.09）。
- **只看"每秒多少度"判断不了丝滑**：匀速扫过和"扫过+跳一下"的平均速率是一样的。

### 31.26 教训：用编辑工具改带 BOM 的脚本，**BOM 会掉**

`run-offline-checks.ps1` 必须带 UTF-8 BOM（§31.17）。本轮用编辑工具改了它一次，
BOM 被丢掉，Windows PowerShell 立刻按 GBK 解、中文字符串吃掉后面的引号，
报 `The string is missing the terminator`。修法：改完复查前三字节是否 `EF BB BF`：

```powershell
$b = [System.IO.File]::ReadAllBytes($p); ($b[0..2] | % { $_.ToString('X2') }) -join ' '
```

不是 `EF BB BF` 就 `[System.IO.File]::WriteAllBytes($p, ([byte[]](0xEF,0xBB,0xBF) + $b))`。

### 31.27 参考 space 0.1.3 的"平滑方案"到底是什么 —— **它对这个病没有解**

用户问："你看一下 space 用的是什么方案"（只查不写）。把 0.1.3 的整条链路读完，结论是
**参考原生就是 20 Hz 硬跳**，我们修之前与它逐字相同。以下是带行号的证据，**以后不必再读一遍反编译**。

#### 完整链路

| 环节 | 参考的做法 | 证据（`decompiled-space/0.1.3/`） |
|---|---|---|
| 时间倍率 | `0.72` × `100 Hz` = **72×** ⇒ 一天 19.95 分钟 | `kelvin/OrbitPhysicalThread.java:18,67` |
| 坐标尺度 | **恒等**（1 格 = 1 米），无 ZOOM；星际移动靠**传送器** | 全库无 `toReal`/`ZOOM` |
| 发包 | 每 tick 全量位姿（20 Hz），写**缓冲区世界** | `kelvin/network/event/CelestialBodyMoveSyncEvent.java:22`（`LevelTickEvent.Pre`）、`:34`（`getPos()` 原始值）；`SyncCelestialBodyMoveBatch.java:63`（`body.moveTo(...)` 入队） |
| 显示世界更新 | `init`（`AFTER_SKY`，**每帧**）→ `syncMoveData()`：`up()` 后**无条件**整块拷贝 | `sunshine/render/SpaceRenderer.java:87-106`；`kelvin/.../ClientSpaceWorld.java:24-36` |
| 插值 | **只有** `lerp(old_pos,pos,partialTick)` / `slerp`，全渲染路径都用它 | `CelestialBody.java:47,55`；`SpaceRenderer.java:475`；`ClientCelestialBody.java:30`；`ClientPlanet.java:41,44`；`ClientStar.java:36,43`；`CelestialBodyDataUBO.java:162` |
| 其它平滑 | **无**：没有时间戳、客户端预测/外推、滤波 | 全库 grep `lerp/smooth/predict/interpolat` 只命中上面那些 |

#### 为什么它的插值**是失效的**

`moveToDirect` 第一步 = `old_pos ← pos`（`CelestialBody.java:67`），而 `syncMoveData()` **每帧**无条件拷一次：

```
包到达那一帧：old_pos = P(t−1), pos = P(t)   ← 插值只在这一帧有效
之后每一帧  ：old_pos = P(t),   pos = P(t)   ← 被推平 ⇒ lerp 恒等于 pos
```

⇒ 参考画面里的天体位置 = **每 tick 硬跳一次（20 Hz）**。
**这是它的原生行为，不是我们抄错**；我们那两个类与它逐字相同（§31.19 里的"有意偏离"就是从这里来的）。

#### 它的 `getRenderZoom` **不是**为了丝滑（别把它当答案）

`ClientCelestialBody.java:29-35`：`zoom = PositionCompression(|p−c|) / |p−c|`，把远处压进 `[16384, 262144]`。
但把整条偏移向量乘一个标量**既不改变方向、也不改变角直径**：

```
投影 = (o·z).xy / (o·z).z  ≡  o.xy / o.z        // 分子分母同时乘 z，约掉
```

⇒ 它与"跳不跳"无关，真实目的是**深度精度/视锥**（否则 `near=0.05、far=1e13` 下远处天体 z-fighting、
或正好落在远平面上被裁掉；配套 `sunshine/mixin/renderer/MixinGameRenderer.java:55` 把 far 收紧到
`farCompressionDistance × 2`）。

> **我们这儿的现状**：`RenderCompression` 已移植但 `enabled` **从未打开**（全库只有读没有写），
> 所以"远处天体渲染稳定性"这一项我们有缺口 —— 但它**与丝滑无关**，属另一件事，别混。

#### 两边对照（只列真正起作用的差别）

| 环节 | 参考 0.1.3 | 我们（§31.19/24/25 修完） |
|---|---|---|
| 时间倍率 | 72× → 一天 19.95 分钟 | 71.8033× → 正好 20.000 分钟 |
| 坐标尺度 | 恒等 1 格 = 1 米 | 同 |
| 发包 | 每 tick 全量包 20 Hz | 同 |
| 显示世界更新 | **每帧无条件整块拷贝** | 只在**值真的变了**时拷贝 |
| 渲染取位姿 | **绘制时**直接读 `getSmoothPos(partialTick)`，不缓存 | 缓存到 `PlanetRenderObject`（曾走 `gamePos` 取原始值 ⇒ §31.24 那个锅是**我们自己的**，参考没这毛病） |
| 插值算法 | 两拍 lerp（因上面那条而失效 ⇒ 20 Hz 硬跳） | **时间戳快照环 + 100 ms 延迟插值** |
| 距离压缩 | 有，默认开（深度精度用途） | 已移植但关着 |

#### 结论（一句话）

> 参考的方案 = "20 Hz 全量包 + 每帧整块拷贝 + 两拍 lerp"，这个组合让它自己的插值失效；
> 它对"天体一顿一顿"**没有解**，因为它的设计里玩家不会贴着行星看（星际靠传送器，
> 而 72× 时间 × 恒等尺度下"近距离停留"本来就不成立）。
> 我们那两处改动（**只在变化时拷贝**、**时间戳插值**）是对参考的**真偏离**，
> 也正是用户感到"真的流畅了"的直接原因 —— 回退点在 §31.19 / §31.25 各写了一次。

### 31.28 进出太空的过渡（地表 ⇄ 太空）：**验过什么 / 没验什么 / 根本没做什么**

用户 2026-09-26 提醒："从太空降落到星球和从星球升到太空之类我记得有些还没测"。查证结果：
**这一整类里只有一项被验过**，而且里面混着三类性质完全不同的东西 —— 分开记，别互相冒充。

#### 四条实际路径（`SpaceTransitionHandler.java` / `PlanetDimensions.java`）

| 路径 | 触发 | 落点/行为 | 代码 |
|---|---|---|---|
| **A. 地表 → 太空（无缝上升）** | **仅主世界**：`dim == Level.OVERWORLD` 且 `player.getY() >= 10000` | `EarthSpaceMapping.worldToSpace` → `SpaceWorld.toMc` →（\|坐标\| ≤ 3e7 才）预加载 → 发 `SpaceTransitionSyncPacket` → **2 tick 后** `changeDimension(space, pos, deltaMovement, yRot, xRot, DO_NOTHING)` + `SpacePlayerData.initFromVanilla` | `:59,64-107,165-189` |
| **B. 太空 → 地球（卡门线捕获下降）** | 与**地球** `gamePos` 距离 < `半径 + 卡门线 − 1` | `EarthSpaceMapping.spaceToWorld` → 方块坐标 → `surfaceY` → 预加载 → 发同步包 → 2 tick 后 `teleportToPlanetSurface(idx=3, x, z)` | `:109-154` |
| **C. 太空 → 其它可着陆天体** | 同一套卡门线捕获 | **落到该影子维度的世界出生点**（`PlanetDimensions.teleport(idx)`）；**不发同步包**；注释自承"精确落点捕获**留待 M4**" | `:155-160,171-176` |
| **D. 命令/传送器** | `/polymech` 或 `TeleporterScreen` | `teleportToSpaceAbove`：放在 `max(2.2R, R+150)` 处、**速度 `Vec3.ZERO`**、朝向天体中心；或 `teleportToPlanetSurface` 表面出生点 | `PlanetDimensions.java:119-183` |

**可着陆集合** = `PlanetDimensions.isTeleportable(idx)` = 是否注册了影子维度：水星/金星/地球/月球/火星/火卫一二/木卫一~四/土卫六/土卫二/冥王星/卡戎。
**太阳与四颗气态巨行星不可着陆**（只当景观）—— 设计选择。

#### ✅ 已验证（唯一一项）

- **地表 → 太空（去程）的落点**：§31.8，19:13:59 实机日志 `[坐标落点] overworld → 太空`：
  **`宇宙系(米)` 与 `目标` 逐位一致、`约定=恒等`**，无 `InjectionError`。
- 同一区域但**不是过渡本身**：太空维度里不卡死 + `[太空阴影] 锚点已在运行时生效并跳过`（§31.9）。

#### ❌ 没测（功能在，但没人走过）

1. **太空 → 地球 的下降（路径 B）—— 从来没走过**。§31.9 末尾明写"**仍待验证**：本局是直接读档在太空、未走维度过渡"。
2. **往返一致性**：去程验了、回程没验 ⇒ `地球 → 太空 → 回地球`两个方向的 `[坐标落点]` 是否逐位一致**未知**。
3. **下降那一刻的表现**：2 tick 延迟 + 同步包这条无缝路径有没有黑屏/错位/一帧跳变；速度与朝向是否保住
   （去程传了 `deltaMovement`，回程 `teleportToPlanetSurface` **没有速度参数**）。
4. **`teleportToSpaceAbove`（路径 D）的落点回归**：§30.13/§31.13 写着"**必须做落点回归**"，没有已验记录；
   且它用 `gamePosMc`（Y 压平）而渲染/物理用真实 Y ⇒ 落点与"看到的那颗星"是否重合没验。
5. **卡门线边界反复穿越**：`COOLDOWN_TICKS = 80`、`DELAY_TICKS = 2`、`PENDING` 去重 —— 边界上下抖动会不会重复触发/卡住/漏触发。
6. **深空落点**：`|坐标| > 3.0e7` 时**跳过区块预加载**（`:96-102`）⇒ 落进纯虚空会不会掉出世界、有没有落脚面。
7. **落到气态巨行星/太阳上会怎样**：捕获循环里 `isTeleportable` 直接 `continue` ⇒ 会**穿过去**，没人试过。

#### ⛔ 根本没做（结构性缺口，别当成"没测"）

1. **无缝上升只在主世界**：入口是 `else if (dim == Level.OVERWORLD)` ⇒ **在火星/金星/月球等地表升到 10000 格不会进太空**，
   只能靠命令/传送器。要通用化得先把 `EarthSpaceMapping` 并到 kelvin 的通用映射（§29.2 那条待办）。
2. **太空 → 非地球天体是"非无缝 + 出生点落点"**（路径 C）：注释自己写着"精确落点捕获留待 M4"，且不发同步包。
3. **两套映射并行**：`EarthSpaceMapping` 是地球硬编码（CENTER (0,0) / 经度长度 100000 / HEIGHT 10000 / MinY −64）
   与 `CelestialWorld` 的通用映射**同时存在**（§29.2）。
4. **`SURFACE_TO_SPACE_SCALE = 0.01` 是死常数**：全库只有声明、无人引用（`SpaceTransitionHandler.java:27`）。
   它看着像"地表↔太空换算比例"，实际是 ZOOM 时代遗留 ⇒ **读这段代码时不要以为它生效**。
5. ⚠️ **承重关系（§30.13 已记，这里再钉一次）**：`tickInSpace` 的卡门线判定靠 `gamePos`（**Y 压平**）
   与玩家那个"小 Y"在同一尺度上；**一旦把方块口径的 Y 解压平，捕获判定会永远失败**。做任何坐标/参考系改动前先读这条。

#### 过渡要去验时的读数

- 两条路径都打 `[坐标落点]`：`… → 太空 | 玩家地表=… | 宇宙系(米)=… | 目标=… | 约定=…` 与
  `太空 → <天体> | 太空输入(米)=… | 落点=… | 约定=…`。
- 判据（§31.8 / §30.12）：**同一段路"宇宙系(米)"必须逐位不变**（映射没动）；**回程落点是方块坐标、与约定无关**。
- 另需 `[坐标自检]` 的校验和与往返误差（历史上记过 `-5410990681030` / `0.000e+00`）。
- **③ 与"太空维度方案"直接相关**：参考系跟随会改变卡门线捕获与落地所用的坐标口径 ⇒ 做那个方案前先把上面三张清单过一遍。

### 31.30 玩家侧物理玩法：**太空放方块 = 造物理体** + **牵引枪**（2026-09-27）

用户需求（两轮，第二轮覆盖第一轮）：
1. "先实现一个玩家放置物理体的功能，并且有个类似 gmod 或者机械动力航空学那种的拖拽物理体的工具"；
2. **"不需要装配器，就是如果在太空对着空处放置方块会变成物理体，但是对着物理体不会再旁边再新建一个，还有一系列的保护措施
   ……因为原版方块到了 30m 之后就放不出来了"**。

⇒ 第一轮的**两点选区装配器已按第 2 条撤销**（类/注册/物品栏/lang/模型全删，`datagen.ps1` 还加了"不该存在"的反向判据）；
最终形态就是**放置驱动的造体** + **牵引枪拖拽**。

#### 为什么必须这样（用户给的理由，也是硬约束）

**原版方块到了 ~3×10⁷ 格（30m）之后就放不出来了**：`BlockPos` 的 X/Z 各只有 26 位（±33,554,431），
超出会静默别名到原点附近；我们另有 `LevelSpaceAccessMixin:59-76` 在 `isDeepSpace` 时把 `setBlock` 直接返回 false。
缩放约定下地球在 1.53e6 格（放得下）、**木星及以外 7.8e7 格（放不下）** —— 所以"在外行星那边盖东西"
只能靠**物理体**（Rapier 刚体 + 投影维度 `poly_mech:projection_world` 里的真实方块），它在原版方块体系之外。

#### 参考依据（行号可查；只借机制，不借代码）

| 环节 | 参考 | 我们的对应件 |
|---|---|---|
| **造体顺序** | `RocketAssemblyService.java:258-279 materialize(...)`：建体 → `getProjection()` → `getStart().offset(64,64,64)`（地皮 = `slot*192 ± 64` = **129³**）→ `copyBlock` → `uploadAllChunks` → `addPhysicalBody` | `SpaceBlockPlacement.createBody`：同序；因为方块还没进世界，把 `copyBlock` 换成"直接写地皮中心那一格 + `onBlockUpload` 标脏" |
| **往体上放东西** | `handleInteraction` / `INTERACTION_USE` | 我们已有 `PhysicsBodyInteraction.useOrPlace`（在投影维度里跑原版 `useItemOn`/`BlockPlaceContext`） |
| **推进器怎么施力** | `ChemicalThrusterBlockEntity.java:29-43`：`ProjectionManager.getPhysicalBody(worldPosition)` 找"自己所属的刚体"→ 用刚体旋转把方块朝向转世界方向 → `body.addForce(Force(dir·1000, 0.05))`；化学版**不烧燃料** | `ProjectionManager.java:226 getPhysicalBody(BlockPos)` 已有 ✔ |
| **拾取** | —（参考没有玩家工具） | `PhysicalRaycast.cast(...)` → `Hit(PhysicalBody, localBlockPos, localFace, worldLocation, distance)` 已有 ✔ |

#### 关键取证：为什么整件事可以**纯服务端**做（这是"不会在旁边再新建一个"的结构性保证）

物理体的方块**不在客户端的 level 里**（活在投影维度）⇒ 客户端射线必然 miss
⇒ 客户端发的是**原版"用物品"包**，服务端在 `ServerPlayerGameMode` 里触发
`PlayerInteractEvent.RightClickItem`。取证方式是从**打过补丁的产物**里扫常量池，不靠记忆：

```
全 jar 扫描 neoforge-21.1.228{,-merged}.jar：调用 CommonHooks.onItemRightClick 的类 =
   net/minecraft/server/level/ServerPlayerGameMode        ← 服务端"用物品"路径（我们的钩子）
   net/minecraft/client/multiplayer/MultiPlayerGameMode   ← 客户端（我们不用）
（在 1.21.1 里 "RightClickItem" 这个类名只出现在 NeoForge 自己的常量池里，
  补丁过的 MC 类引用的是方法名 onItemRightClick —— 所以只搜事件类名会漏。）
```

于是**一个服务端处理器**同时覆盖两种情况，不存在"客户端包 + 服务端逻辑各触发一次"：

| 准星 | 服务端行为 |
|---|---|
| 打在**物理体**上（`PhysicalRaycast` 命中） | **并进那个体**（写它地皮里对应的一格 + 标脏），**绝不新建** |
| 前方是**空的**（附近也没有体） | **新建一个单方块物理体**，落在准星前方 3 格 |

#### 保护措施（12 条；每条都写了"为什么"）

| # | 措施 | 为什么 |
|---|---|---|
| 1 | 只在**太空维度**生效 | 行星地表有真实方块空间，那里就该放普通方块 |
| 2 | 不在**投影维度**生效 | 否则会在地皮里套娃建体 |
| 3 | 必须有物理世界，否则什么都不做 | 不能把方块扔进虚空 |
| 4 | 只处理**方块物品** | 食物/工具照原样走原版 |
| 5 | 每玩家 **4 tick 冷却** | 防连点刷体 |
| 6 | 目标格与**玩家包围盒相交 ⇒ 拒绝** | 把方块塞进自己身体会被卡住/弹开 |
| 7 | 每维度 **≤ 256 个体**（只挡"新建"，并体不受限） | 防把物理世界塞爆 |
| 8 | **目标格不得与玩家身体相交**（AABB 相交即拒） | 别把自己的腿封进方块里。**注意**：曾经这里还有一条"离玩家 ≤2.5 格"，第二轮实机反馈"**离玩家的保护就别这么远了，不然不好放啊**"后**已撤** —— 目标格只可能是"贴体相邻格"或"准星前方 3 格"，那条门防的是一个不存在的风险（"放得很远"），代价是把正面放置全挡了 |
| 9 | 目标格必须是**空气** | 这是"放置"不是"替换"（`placeBlockAt` 也会再判一次） |
| 10 | 创造模式**不消耗**，生存消耗 1 个 | 与原版一致 |
| 11 | 单个体方块数上限 | 由原语自己的 `MAX_BLOCKS` 把关（`placeBlock` 内） |
| 12 | 拒绝时也**吃掉这次右键** | 否则原版会去走它自己的放置（要么放不出、要么写出一个孤儿方块） |

**命中面的世界化**（结构性，不是补丁）：体被牵引枪转过之后，命中面是**体局部**的
（`PhysicalRaycast.Hit.localFace()`），直接用会把方块放到**体的另一侧**。
`SpaceBuildRules.rotateFace(局部面, 刚体旋转)` 把它转成世界方向（离线判据里专有"体绕 Y 转 90°"一组）。

#### 实现落点（**第二版：只有一个实现入口**）

- `physics/SpaceBlockPlacement`：`RightClickItem` 处理器（服务端）。算**世界坐标**的目标格 ⇒ 过前置判据
  ⇒ 调 **`PhysicsBodyTracker.placeBlockAt(level, pos, stateId)`**（项目已有原语）⇒ 消耗物品 + actionbar/日志。
- `physics/SpaceBuildRules`：**零 MC 依赖**的纯函数（判据表 + 拒绝文案 + `rotateFace`）⇒ 离线可直接判。
- `physics/PhysicsBodyTracker`：把 `placeBlockAt` 里那段"找面相邻体"的扫描抽成
  `findAdjacentBody` + 公开 `hasAdjacentBody`（**同一次扫描，不做第二份实现** —— §29.3 的教训）。
- `item/PhysgunItem` + `physics/PhysgunSpring`：牵引枪（射线拾取 → `startUsingItem` → 每 MC tick 施一次
  `F = m·a` 的弹簧力，`a = clamp(Kp·err − Kd·v)`，Kp=8/Kd=4/aMax=80/误差上限 32/力时长 0.05 s）；
  **只施力、不写位置**。注册：`ModItems.physgun` + 工具物品栏 + datagen 模型（v1 复用原版 `item/blaze_rod`）+ 中英 lang。

#### ⚠️ 第一版的根因（用户实机报"放出来不显示、也无法对这个物理体再放方块"）

第一版（2026-09-27 当天早些时候）**没找到 `placeBlockAt`**，自己拼了一条路：
`new ServerPhysicalBody(...)` + `ProjectionManager.setBlock(地皮中心)` + `uploadAllChunks` + `addPhysicalBody`。
它看起来"照抄了参考的六行"，但**绕过了 `PhysicsBodyTracker`**，而项目里真正的两条链是：

| 链 | 依赖 | 我漏掉的后果 |
|---|---|---|
| **客户端渲染** | `PhysicsBodyTracker` 的方块快照（`PhysicsBodySyncPacket` / `refresh`） | 客户端收到的是**空体** ⇒ **不显示** |
| **碰撞体** | `PhysicsBodyTracker.rebuildCollider`（由体素方块网格推导） | 体**没有碰撞体** ⇒ 射线打不到它 ⇒ 判定为"附近没有体" ⇒ **每次都在旁边新建**，永远并不到一起 |

⇒ 现象与你报的两条**逐字吻合**。修法不是打补丁，而是**改走唯一原语**：
`PhysicsBodyTracker.placeBlockAt` 一次做完"并进面相邻的体 / 由这一块新建体 + 建碰撞体 + 写存档 + 广播客户端 + 落地皮"，
注释原文就是"太空维度里玩家摆出来的方块应当是物理体……并入面相邻的现有物理体，找不到就由这一块新建一个物理体"。
**教训（本项目第 N 次同类）：动手前先把"这个功能是不是已经存在"查干净 —— 我查了 `ProjectionManager`，
没查 `PhysicsBodyTracker` 的公开 API，于是重造了一套绕过账本的实现。**

#### 离线验收（都已并入回归）

| 判据 | 结果 |
|---|---|
| `PhysgunDragProbe`（第 9 项）：真 MPS 刚体 + 真弹簧 | 误差 1/10/32 格 ⇒ 收敛 1.19 / 2.45 / 2.94 秒，稳态误差 **0.0000**，超调 ~4%，限幅生效，80 kg 与 50 t 差 **0.0%** ✔ |
| `SpaceBuildRulesProbe`（第 10 项）：判据表 8 例 + 4 条拒绝文案 + `rotateFace` 4 例 | **16 项全 PASS** ✔ |
| `check-artifact`（第 7 项） | 13 个类的常量池里都有本轮标记（含 `placeBlockAt`/`hasAdjacentBody`/`rotateFace`）✔ |

跑法：`pwsh native\jni-smoketest\run-offline-checks.ps1` → **10/10，退出码 0**。

#### 实机判据（下一次会话）

1. 太空里手持方块，**对着前方右键** ⇒ actionbar "**新建物理体** …：(x,y,z) 方块名"，
   方块出现在准星前方 3 格，**立刻可见、可被牵引枪打到**（这两点是第一版缺的）；
2. 对着它旁边右键 ⇒ actionbar "**并入物理体** …" ⇒ **体变大，旁边不多出新体**；
3. 把准星压到脚下/贴着自己身体放 ⇒ 被拒："太贴着你了，把准星往前挪一点"；连点 ⇒ 被 4 tick 冷却吃掉；
   （**"离玩家太远 ⇒ 拒绝"那条已经撤掉了** —— 见上表第 8 行，用户反馈"不好放"）
4. 日志搜 `[太空建造]`，每行含 `新建物理体` / `并入物理体` 字样。

**已知不完美（照抄原语的既有假设，先记着别急着改）**：`PhysicsBodyTracker` 算方块世界坐标用的是
`tracked.origin + 局部坐标`，**不含刚体旋转** ⇒ 体被牵引枪转过之后，相邻判定/落点会按"未旋转"算。
要不要把它做成旋转感知，需要动体素账本模型（也可能与参考同构），**先攒证据再动**。

#### 牵引枪的**视觉**（2026-09-27 第三轮：用户"完全不像是 GMod/机械动力那样啊，牵引枪的线呢？"）

第一版只有"施力"、**零客户端视觉**（我自己在 v1 里列为"已知简化"），所以手感上完全不像 —— 补上四件：

| # | 画什么 | 颜色 | 实现要点 |
|---|---|---|---|
| 1 | **准星目标高亮**：命中体的局部 AABB 线框（12 条边、跟着刚体旋转） | 青 | `PhysgunBeamRenderer.drawBodyOutline` |
| 2 | **枪口射线**：近似枪口 → 命中点 | 青 | 枪口 = 眼 + 视线×0.35 + 右×0.18 − 上×0.12（正上/正下看时右向量退化，有兜底） |
| 3 | **抓住后的光束**：枪口 → 目标点（= 眼 + 视线 × holdDistance，**与服务端施力同一个公式**） | 橙 | `PhysgunClientState.holdDistance` |
| 4 | **落点环**：目标点的小线框盒 | 橙 | 让人看得出"拉力正把它拽向哪里" |

- 渲染通道照本项目已验证的写法：`RenderLevelStageEvent` 的 `AFTER_TRANSLUCENT_BLOCKS` +
  `event.getPoseStack()` + `event.getCamera().getPosition()`（裸 `PoseStack` 会缺相机旋转，§ 已记录）；
  线用 `RenderType.lines()`（POSITION_COLOR_NORMAL ⇒ 每个顶点都要 `setNormal`）。
- **拾取为什么在客户端自己算**：客户端镜子体不保证有碰撞体（Rapier 射线可能打不到），
  而客户端手里已有每个体的方块列表 + 位姿 ⇒ 用 `RayBox`（**纯 joml，离线可判**）在体局部空间做 slab 求交更稳。
  服务端仍是权威（真正抓谁由服务端射线决定），客户端这份只服务视觉与"抓住瞬间的距离"。
- **一个必须踩过才知道的点**：客户端要画光束就必须 `player.isUsingItem()`，
  而原版只有在 `use()` 返回 `consumesAction()`（`consume`）时才 `startUsingItem` ——
  v1 客户端返回的是 `success` ⇒ 按住右键也不会 `isUsingItem()` ⇒ 光束永远不出现。现在客户端分支返回 `consume`。

**离线判据**：`SpaceBuildRulesProbe` 第 4 节专测 `RayBox`（正打 t=1.5 / 平行擦过 miss / 盒在背后 miss /
起点在盒内 t=0 / 3 格长体的局部 AABB / **体转 90° 后同一根射线命中而未旋转则 miss**）。
其中"体转 90°"那组**我写错过两次期望**（第一次方向写反、第二次忘了算射线朝向），
两次都是**判据的期望错了、代码是对的** —— 与本项目"断言必须由调用方推导"那条教训同源，记在这里。

**已知缺口（下一步，按优先级）**：
1. **线太细**：`RenderType.lines()` 是 1px 线；GMod/机械动力的光束是**有厚度的发光条**（用四边形带 billboard 或 `lightning` 类通道）；
2. **别人的牵引枪看不见**：现在只画本地玩家 ⇒ 需要服务端把"谁抓着哪个体"广播（`PhysgunGrabPacket`），
   顺带能消掉"客户端镜子体滞后导致光束指向另一个体"的极端情况；
3. **抓住时的旋转控制**（GMod 用滚轮+按键转物体）：现在只能靠弹簧自然摆动；
4. **手感**：现在 Kp=8/Kd=4（1–2 秒收敛、略软）；想更"焊住"就调 Kp/Kd（离线探针可直接扫参，先看收敛与超调）。

1. **误删 datagen 产物**：我用 `Remove-Item src\generated\resources\data\poly_mech\* -Recurse` 想删"装配器相关产物"，
   结果删掉整个 `data/poly_mech/**`（4201 个被跟踪文件，含 `space_data/space/object/*.json`），
   第 1 项轨道验收立刻报 `NoSuchFileException`。恢复方式：`git checkout -- src/generated/resources/data`（4201 个文件全回），
   然后让 datagen 重跑。**规矩：`src/generated` 下永远不要用通配删除**，要"让 datagen 自己清"，或直接 `git checkout` 回滚。
2. **`.ps1` 的 BOM 又被编辑工具弄掉**（`check-artifact.ps1` 整份被按 GBK 解析、满屏语法错）。
   这次已有 `datagen.ps1` 里的补 BOM 手法兜底：改完 `.ps1` **必须**复查前三字节 `EF BB BF`。


#### 牵引枪视觉**第四轮**：照参考重做（用户"太廉价"；同时挖出"线呢？"的真正根因）（2026-09-28）

用户原话：**"牵引枪不对，完全不像是机械动力航空学和 gmod 的那种啊，牵引枪的线呢？"**、
**"你这显示的完全不对啊，不像，太廉价了好吗"**，并给了参考仓 `C:\Users\34573\Desktop\Simulated-Project-main`。
上一轮我自己把"线太细 / 别人看不见"写进了"已知缺口"（见上），这一轮就是去关掉它们 ——
但**不是调参**，而是先取证：参考那套东西到底由哪几样构成。

**取证（只读参考仓，行号可查；只借机制不借代码）**

| 参考位置 | 事实 | 我们上一轮 | 结论 |
|---|---|---|---|
| `physics_staff/PhysicsStaffClientHandler.java:439-527` | `PhysicsBeam`：节点链，`TARGET_SPACING=1.5`、`MIN_POINTS=8`、`targetNodeRadius=0.2`；`BeamNode.update()` = `(position + offsetRandom(random,3)) * 0.5` | 一条直线 | 光束是**会呼吸的电浆绳**：节点半径 `0.2·√(scaled/count)`≈0.18 格，均值回复（不漂走） |
| 同上 `:468` | `line.getParams().colored(0xffffff).disableLineNormals().lineWidth(0.6f/16f)` | `RenderType.lines()`（1px） | 参考**自绘四边形**当线用 —— 因为核心 profile 把 `glLineWidth` 钳到 1，光靠 GL 线宽做不出厚度 |
| 同上 `:493-500` | `extension = lerp(0.5, extension, 1)`；松手 `intensity *= .6f`，`< .4f` 移除 | 松手硬切 | 有**收束淡出**（1.0 → 0.6 → 消失） |
| 同上 `:439`、`:357-384` | `Map<UUID, PhysicsBeam> beams`；每帧 `beam.render(focusPos, interpolatedBeamEnd, …)`，端点 `previousStart/End.lerp(pt)` | 只画本地玩家 | 别人的光束也看得见；20Hz 端点按帧插值补平 |
| `network/packets/physics_staff/PhysicsStaffBeamPacket.java` | `(uuid, start, end)` 服务端 → 客户端 | 客户端自己求交决定"抓没抓到" | **权威必须在服务端** —— 这条同时就是"线呢？"的根因（见下） |
| `physics_staff/PhysicsStaffRenderHandler.java` | `Outliner.showCluster(...).colored(...).lineWidth(1/32f).withFaceTexture(AllSpecialTextures.CHECKERED)` | 单色 1px 线框盒 | 悬停是**棋盘面 + 有宽度的框**，不是一根细单色线 |

**"牵引枪的线呢？"的根因（本轮最重要的发现，不是观感问题而是判据分裂）**

上一轮客户端在 `use()` 里自己用 `PhysgunTarget`（解析求交，打客户端镜子体）决定抓谁，
**打不中就 `return success`** ⇒ 不 `startUsingItem` ⇒ 渲染器的判据 `player.isUsingItem()` 为假 ⇒
**一根线都不画**。而服务端在同一次点击里走 Rapier 射线（`PhysicalRaycast`）**命中了**，
于是聊天栏老老实实显示"已抓住"。
两边对"抓没抓到"用的根本不是同一套判据 —— 客户端镜子体没同步上时，这个分叉是常态而非极端情况。
修法与参考同构：① 客户端**无论如何**都 `startUsingItem` + 返回 `consume`（打中就顺手记一条本地预测，光束立刻出现）；
② 服务端把抓取结果（谁、两端在世界哪里、松没松手）用 `PhysgunBeamPacket` 广播给附近的人（`sendToPlayersNear`，含自己）。

**本轮落地（5 件，每件都有独立回退点）**

| # | 文件 | 做了什么 | 为什么（判据） |
|---|---|---|---|
| 1 | `physics/PhysgunBeamShape.java`（新，纯数学） | 节点数/半径公式、随机游走、线段四边形、正对相机的方块、棋盘奇偶、强度曲线 | 抽出来才能**离线判**（照 `PhysgunSpring` 的同一招）：公式错、退化除零、NaN 都在这里被钉住 |
| 2 | `client/physics/PhysgunRenderTypes.java`（新） | 两个 `RenderType.create` 通道：`BEAM`（加色混合 `ADDITIVE_TRANSPARENCY` + `COLOR_WRITE` 不写深度 + `NO_CULL`，`POSITION_COLOR` 自发光）、`OVERLAY`（普通透明，画棋盘面与厚线框） | 本项目**第一次**用 `RenderType.create`：已从反编译源核实 `create(String,VertexFormat,Mode,int,boolean,boolean,CompositeState)` 是 public、`RenderStateShard` 的常量全是 public、且 `name` 无命名空间校验 |
| 3 | `client/physics/PhysgunClientState.java`（重写） | `Map<UUID, Beam>`：每玩家一条；节点链 + 端点 `prev→cur` 插值；`intensity` 淡出；**本地预测 vs 服务端权威**（`serverDriven` 一旦为真，预测不再写端点） | 照参考的"按玩家一张表"；预测保证开火瞬间就有光束，权威到达后接管 |
| 4 | `client/physics/PhysgunBeamRenderer.java`（重写） | ① 抖动能量绳（每段画**十字双四边形**：核心 0.030 格 + 外晕 0.080 格，任何视角都有厚度）；② 抓点光斑（两层正对相机的方块）；③ 落点标记；④ 体的高亮框改成厚线（0.026 格）+ 命中的那一格画**几何生成的棋盘面**（每面 3×3 填一半，替代参考的 `CHECKERED` 贴图）；⑤ 光束起点对本地玩家用枪口近似 | 十字截面解决"视线与光束平行时细成一条线"；棋盘面不引入新资产 |
| 5 | `network/PhysgunBeamPacket.java`（新）+ `PhysgunItem` + `PhysicsClientHooks` + `Polymech` 注册 | S2C 广播：`(playerId, start, end, holdDistance, released)`；服务端抓取瞬间 + 每 2 tick（`BROADCAST_INTERVAL_TICKS`）重算端点（抓点存**体局部**，用刚体姿态变换 ⇒ 船转光束跟着转）；松手/体消失/抓取被拒都发 `released` | 走 dist-safe 桥（`PhysicsClientHooks.physgunBeamConsumer`，默认 no-op）⇒ 服务端不会加载客户端类；**端点直接发世界坐标**，避免跨 UUID ↔ ProjectionManager 槽位 ↔ tracker long id 三重 id 映射 |

顺带：抓取/松手接上原版 `CONDUIT_ACTIVATE/DEACTIVATE`（参考有 ignite/idle/lock/extinguish 一整套自造 ogg，
自造音频资产是另一件事，先用原版顶上，至少有听觉反馈）。

**离线验收（全部并入回归，`run-offline-checks.ps1` 现为 11 步）**

- 新增第 11 步 `PhysgunBeamShapeProbe`（**55 项**，纯数学、连 joml 都不需要）：
  数值锚点对参考逐个核对（1.5 / 8 / 0.2 / 0.5 / 0.6 / 0.4）；
  节点数公式与 4096 保护；半径随节点数单调减小；随机游走两万步**有界且均值回复**（平均 |pos|≈0.83 ⇒ 20 格光束振幅 ≈0.15 格）；
  线段四边形四个角点到轴线距离都等于半宽、面积 = 长×宽；
  **相机落在光束轴上、两端点重合等退化情形不出 NaN**（实机表现就是"光束突然消失"）；
  正对相机的方块在"相机在正上方/正中心"时不退化；棋盘 8×8 正好一半；强度曲线收敛到 1 且松手后 `<0.4` 才移除。
- 产物自检扩到 **21 个类**（含新类、以及 `PhysgunClientState$Beam` 这个**内部类**独立 .class —— 教训：
  marker 必须写它真正所在的那个 class，字段属于内部类就搜不到外层类里）。

**实机判据（请用户验；每条的 FAIL 表现与首查点都写好）**

| # | 操作 | PASS | FAIL 时首查 |
|---|---|---|---|
| 1 | 太空里手持牵引枪，准星指着物理体（**不按右键**） | 体上出现**青色厚线框** + 指着的那一格有**棋盘面** | `PhysgunTarget.find` 是否命中（`aimedBodyId()`）；客户端 `ClientPhysicsWorld` 里有没有这个体 |
| 2 | 按住右键抓一个体 | **立刻**出现一条**会轻微抖动**的蓝白光束（不是直线、不是 1px），末端有个亮斑 | 先看聊天栏："已抓住"⇒ 包没到或渲染器没跑；"没指到物理体"⇒ 服务端射线问题 |
| 3 | 抓着时拖动鼠标/走动 | 光束跟着枪口与抓点走，**没有一抽一抽**（端点 20Hz + 逐帧插值） | 掉帧说明 `nodeCount` 过大（看日志/`nodeTotal`） |
| 4 | 松开右键 | 光束**淡出收束**（约 0.1 秒），不是硬切 | `PhysgunClientState.tick()` 是否在跑（`ClientTickEvent.Post`） |
| 5 | 抓着一艘船**转它**（撞击/推进后） | 光束端点**粘在船身的同一处**跟着转 | 端点由服务端每 2 tick 用体局部抓点重算 ⇒ 看包是否在发 |
| 6 | 客户端有第二个玩家（或看别人） | 别人的牵引枪光束**你也看得见** | `PacketDistributor.sendToPlayersNear` 半径 128 与 `physgunBeamConsumer` 装配点（`PhysicsBodyRenderer.Setup`） |

**已知未做（诚实记录）**：抓住时的**旋转控制**（GMod 用滚轮+按键转物体，现在只能靠弹簧自然摆动，见上一轮缺口 3）；
自造音效资产；`PhysicsBodyTracker` 用"origin + 局部坐标"算世界坐标时**不含刚体旋转**（§ 已记，
表现为旋转过的体上放方块/邻接判定会偏 —— 需要先取证再动）。

#### 牵引枪第五轮：**右键即崩** + 半格坐标约定（2026-09-29，两个独立 bug 一起收）

用户一句话：**"牵引枪右键物理体直接崩溃了"**。查 `run/crash-reports/crash-2026-09-29_19.48.59-client.txt`，
崩的不是服务端而是我的渲染通道：

```
java.lang.IllegalStateException: Not building!
  at BufferBuilder.ensureBuilding
  at PhysgunBeamRenderer.emit(:400) ← segment(:241) ← drawBeam(:221) ← onRenderLevelStage(:156)
```

**根因（读 1.21.1 反编译源 `MultiBufferSource.BufferSource.getBuffer` 得到，不是猜的）**：

```java
if (bb != null) return bb;
else {
    ByteBufferBuilder fixed = this.fixedBuffers.get(renderType);
    if (fixed != null) bb = new BufferBuilder(fixed, ...);
    else {
        if (this.lastSharedType != null) this.endBatch(this.lastSharedType);   // ←★
        bb = new BufferBuilder(this.sharedBuffer, ...);
        this.lastSharedType = renderType;
    }
}
```

**自定义（非 fixed）通道共用一条 `sharedBuffer`，取第二个通道会把第一个提前 `endBatch` 掉**。
我上一轮在同一帧里先 `getBuffer(BEAM)` 再 `getBuffer(OVERLAY)`，随后回头往 BEAM 写第一个顶点 ⇒ 那个
builder 已经 `build()` 过（不再 building）⇒ "Not building!"。
旧版只用一个 `RenderType.lines()`，所以这个坑一直没露头；**一旦有第二个自定义通道就必炸**。

- 为什么"瞄准高亮"当时不崩：它往**第二个**通道（OVERLAY）写，那个还开着；只有抓住后往 BEAM 写才炸
  —— 于是症状是"一右键就崩"，完全不像渲染通道的锅。
- 修法：**一个通道一趟**（`getBuffer → 写完 → endBatch`，绝不跨趟持有消费者），光束一趟、高亮一趟。
- 加固：新增 `client/renderer/RenderPassGuard`（纯状态机、零 MC 依赖）+ `PhysgunRenderTypes.beginPass/endPass`。
  重入时立刻抛一条点名两个通道、并说明"共享 BufferSource"的异常，而不是让顶点写到一半才炸不好读的
  "Not building!"；抛异常时先清状态（fail-open，不让一帧的异常连坐后面每一帧）。
  判据：`PhysgunBeamShapeProbe` 第 10 节（重入必抛、异常信息含通道名与 BufferSource、关错/关空必抛、fail-open、
  正确用法不抛）。

**顺带挖出的第二个（更贴合用户实际体验的）bug：方块局部坐标差了半格**

日志显示两次右键都是 `[Server] [牵引枪] 准星没指到物理体（64 格内）` —— 服务端 `ShipRaycast` 两发全空，
而客户端解析求交**命中**了（所以是本地预测画出的光束崩的）。两条判据口径不同不是偶然：
`RayBox.blockBounds` 把整数当**块中心**（`dx ± 0.5`），而真实约定是**块占 `[x, x+1)`**。
三处**互相独立**的证据：

| 证据 | 事实 |
|---|---|
| `PhysicsBodyTracker.localToWorld:1133` | `new Vector3d(dx + 0.5, dy + 0.5, dz + 0.5)` ⇒ 块心在 `dx+0.5`（服务端物理世界坐标） |
| `PhysicsBodyRenderer` | `pose.translate(entry.dx(), dy(), dz())` 后画占 `[0,1]` 的原版方块模型 ⇒ 块占 `[dx, dx+1)` |
| `PhysicsBodyInteractionClient:310` | `tmp.set(hit.dx + 0.5F, ...)` 求块心 |

后果不是"差一点"：客户端的拾取盒**平移半格且比真实体大一圈**（`[min−0.65, max+0.65]` 而非
`[min−0.15, max+1.15]`）⇒ ①**悬停框画在错位置**，玩家照着错的框去瞄；②服务端用真实方块形状 ⇒
"客户端命中、服务端全空"；③光束抓点落在体旁边。

- 修：`RayBox.blockBounds` 改成 `min = min(dx) − inflate`、`max = max(dx) + 1 + inflate`；
  悬停格由 `Math.round(localHit)` 改为 `floor`（块的另一半），注释同步改写。
- **教训（已写进 `RayBox.blockBounds` 的注释）**：`SpaceBuildRulesProbe` 第 4 节当年把期望写成
  `x∈[-0.5, 2.5]`、`t=4.5` —— 那是**从我自己刚写的实现推出来的**，于是"判据通过"只证明了实现自洽、
  证明不了约定正确。本轮按独立证据改成 `x∈[0,3]`、`t=5.0`，并新增一条"单格 AABB = [0,1]³（块心 0.5）"
  的约定判据。**期望必须来自独立证据，不能来自被测实现自身。**
- 另加服务端诊断：没打中时聊天栏补一句"最近 X 格（共 N 个体）"，
  让一句话区分开三种原因 —— ①这个维度没有物理体；②体在 64 格外；③体就在眼前却没打中（真 bug）。
  这一轮若早有它，第一眼就能看出是③。

**本轮判据**：`run-offline-checks.ps1` **11/11 全过**（含第 10 节通道守卫与改正后的约定判据）；
`check-artifact.ps1` 21 个类全命中。

**实机判据（请用户验）**

| # | 操作 | PASS | FAIL 时首查 |
|---|---|---|---|
| 1 | 太空里对物理体右键 | **不崩** | 崩溃报告栈顶：若是 `Not building!` ⇒ 又跨趟持有通道了（查 `RenderPassGuard` 的异常） |
| 2 | 手持牵引枪对准物理体（不按键） | 高亮框与体**真实轮廓重合**（不再偏半格） | `RayBox.blockBounds` 的约定 |
| 3 | 对物理体右键 | 聊天栏出现"已抓住 …"，且**立刻**有抖动光束 | 若仍"准星没指到物理体"⇒ **看新增的"最近 X 格（共 N 个体）"**：X 很大 = 没瞄准/太远；X 很小却打不中 = 求交本身的问题（下一步取证） |
| 4 | 松开右键 | 光束淡出收束（约 0.1 秒） | `PhysgunClientState.tick()`（`ClientTickEvent.Post`） |
| 5 | 有第二个玩家 | 别人的光束你也看得见 | 包注册 + `physgunBeamConsumer` 装配点 |

#### 牵引枪第六轮：**画面呈现照参考重做**（用户："你看看航空学是怎么设计画面呈现的效果的"，2026-09-29 同日）

用户发了张截图 + 一句"???何意味"：屏幕上一个大青框套住褐色平台，右端还飘着几个蓝色方块。
问清后两条回答很关键：**"你看看航空学是怎么设计画面呈现的效果的"** + **"好像是你给物理体的框框"**
—— 那堆蓝色方块**是我画的**（悬停格的棋盘面），而且它飘到框外去了。

**先把参考的呈现方式逐行读清楚**（`physics_staff/PhysicsStaffRenderHandler.java`）：

| 参考 | 我上一轮做的 | 结论 |
|---|---|---|
| `:78` `Outliner.showCluster("physicsStaffSelection", List.of(hoverBlockPos))` —— **只高亮一个方块** | 框住**整个物理体**（用户的 15 格大青框） | 参考是"指着哪格亮哪格"；整框是我自己发明的，且遮视线 |
| `:76` `new Color(191/255f, 191/255f, 191/255f, 1f)` = **0xBFBFBF 浅灰** | 亮青色 `{0.42,0.85,1.0}` | 参考在这一点上很克制：高亮用中性灰，彩色留给光束 |
| `:81` `lineWidth(1/32f)` = 0.03125 | 0.026（接近，但颜色错、范围错） | 线宽照抄 |
| `:82` `withFaceTexture(AllSpecialTextures.CHECKERED)` | 用几何拼 3×3 棋盘 | 参考用的是**贴图**；贴图每面只要 1 个四边形（几何拼法要 27 个），格纹也更细 |
| `:95-100` 抓住时 `hoverBlockPos = BlockPos.containing(dragSession.dragLocalAnchor())` —— 框**移到抓点那一格** | 整框 + 一个落点标记 | 抓住时高亮跟着抓点走 |
| `:125-151` `renderAllLocks`：对每个被锁定的 sub-level，在它的渲染位置上、`rotate(cameraOrientation())` 后画一个 **1×1**（−0.5..+0.5）的 `SimRenderTypes.lock()` 四边形，`0xffffffff` + `LightTexture.FULL_BRIGHT` | 无 | "锁定"标记 = **正对相机的 1×1 方块** |
| `:52` `if (Minecraft.getInstance().options.hideGui) return;` | 无判 | F1 时不画 |

**本轮改动（对齐上表）**

1. **只高亮一格**：`drawBodyBox`（整框）删掉；改成 `drawCellFaces`（棋盘贴图面）+ `drawCellEdges`
   （12 条细灰棱，`CELL_LINE_WIDTH = 1/32`）。颜色 `HOVER_RGB = 0xBFBFBF`。
2. **抓住时**：高亮格跟到**抓点那一格**（用服务端广播的光束终点 `renderEnd()` → 体局部 → `floor`），
   并在抓点画 **正对相机的 1×1 lock**（`drawLockFaces`/`drawLockEdges`，`FULL_BRIGHT`、白色）
   —— 就是参考 `renderAllLocks` 的形状。
3. **棋盘改成贴图**（对齐 `withFaceTexture`）：新增 `assets/poly_mech/textures/misc/physgun_checker.png`
   （16×16、4px 格、白 + 全透明交替；生成器 `native/jni-smoketest/MakeCheckerTexture.java`，幂等可重跑）
   + 新通道 `PhysgunRenderTypes.CHECKER`（`POSITION_COLOR_TEX_LIGHTMAP` + 公开的
   `POSITION_COLOR_TEX_LIGHTMAP_SHADER` + 公开的 `TextureStateShard`，顶点给 `FULL_BRIGHT`）。
4. `hideGui` 时不画（照 `:52`）。
5. **通道纪律升级成三条**（BEAM / CHECKER / OVERLAY），每趟严格 `beginPass → 写完 → endPass`
   ——`RenderPassGuard` 会在重入时立刻抛异常。

**顺带修掉的真 bug：悬停格的"面"仍在用中心约定**

`drawHoverCell` 里那半格错误**没被我上一轮修干净**：格的**索引**已经改成 `floor`（对），但**面的位置**
还是手写的"格心 ± 0.495" ⇒ 棋盘整体平移半格、飘到体轮廓之外。这正是用户截图里"框外面那几个蓝方块"。
现在面的几何抽成纯函数 `PhysgunBeamShape.cellFace(axis, sign, cell, inset, xyz12, uv8)`
（格占 `[x, x+1)`；只在**法线**方向内缩 `inset`，**切向**铺满整格），一次同时给出角点与同序 UV
—— 免得两个数组分开写、顺序对不上。

**判据**：`PhysgunBeamShapeProbe` 第 8 节整段重写（原来是"棋盘奇偶"这种自证式判据）：
+X/−X/+Y 三个面各自落在正确的平面上、面在切向铺满整格（面积 1.0）、**相邻角点间距都 = 1.0**
（真能分辨"绕圈"与"蝴蝶结"顺序 —— 面积判据分辨不出来，我一开始把它写成"顺便证明顺序"是错的）、
UV 与角点同序、6 个面的并集 = 整格 `[c, c+1]³`。

**又一次"期望写错、代码是对的"**（本轮两次）：面只在法线方向内缩（切向仍铺满），所以面积是 1.0 而不是
`(1−2·inset)²`；并集要算上其它面的切向铺展，所以是整格而不是三轴都缩。两次都是**我先入为主地假设
"整体缩小一圈"**。与 §31.30 记的"期望必须有独立依据"同源，记在这里当第三次。

**产物自检加了资源检查**：`check-artifact.ps1` 现在除了 22 个 `.class`，还检查
`src/main/resources/assets/poly_mech/textures/misc/physgun_checker.png` 是否存在 ——
贴图缺了**不会编译报错**，游戏里只会变"紫黑格"，属于典型静默坏。

**实机判据（请用户验）**

| # | 操作 | PASS | FAIL 时首查 |
|---|---|---|---|
| 1 | 手持牵引枪对准物理体（不按键） | **只有准星指着的那一格**亮起：浅灰细框 + 棋盘格面（不再是整个体的大青框） | `PhysgunTarget.find` 的命中格；`cellFace` 的约定 |
| 2 | 换着瞄准体的不同格子 | 高亮格**跟着准星走**，且**贴在该格上**（不再飘半格） | `drawCellFaces`/`drawCellEdges` 的 `inset`/`grow` |
| 3 | 右键抓住一个体 | 抓点处出现**正对相机的 1×1 lock**（白 + 棋盘），高亮格跟到抓点那一格 | 光束是否收到（`PhysgunClientState.beam(self)`） |
| 4 | 按 F1 隐藏界面 | 高亮与光束**都不画** | `hideGui` 判在渲染器最前 |
| 5 | 看那张棋盘贴图有没有变成紫黑格 | 正常是白色棋盘 | 资源检查（`check-artifact.ps1` 的"资源文件"一节） |


#### 牵引枪第八轮：**"无法长按"的根因 = 查错了注册表**（2026-09-29 晚间，用户实机）

用户原话：**"点一下右键才能有一瞬间的射线，根本就无法长按，更无法得知是否可以工作了"**
（同一张截图里还有一句"HUD 怎么一堆 0"）。两件事都查清了，第二件是前一件的症状链。

**决定性证据来自上一轮自己加的那句诊断**（`run/logs/latest.log`）：

```
[牵引枪] Dev ：准星没指到物理体（64 格内），最近 1.7976931348623157E308 格（共 2 个体）
```

`1.7976931348623157E308` = `Double.MAX_VALUE`：说明**每个体算出来的距离都是 NaN**
（NaN 与任何数比较都是 false ⇒ `best` 一直没被更新）。而服务端走的是
`PhysicalRaycast` → `ShipRaycast`，它的 `pose()` 里正好有 `position.isFinite()` 检查
⇒ **它把每一个体都 `continue` 掉了** ⇒ 永远"没指到"。整条症状链于是闭合：

```
服务端求交全空 → use() 走失败分支 → broadcastReleased(sp)
  → 客户端收到 released → 本地预测那条光束被强度曲线收掉（约 0.1 秒）→ "只有一瞬间的射线"
  → GRABS 里没有记录 → onUseTick 直接 return → "无法长按"
  → 聊天栏只剩一句"准星没指到物理体" → "无法得知是否可以工作"
```

**架构层面的根因：两套注册表**（这才是要修的东西）

| | 客户端拾取 / 渲染 / 太空放方块 | 牵引枪（旧） |
|---|---|---|
| 数据源 | `PhysicsBodyTracker`（long id）→ `PhysicsBodySyncPacket` → `ClientPhysicsWorld` | MPS 层 `ServerPhysicalWorld`（`PhysicalBody` + UUID） |
| 位姿 | 有效（所以你看得见、客户端拾取也命中了那块平台） | **NaN**（该维度 2 个体全是） |
| 结果 | 命中 ✔ | 全空 ✖ |

`SpaceBlockPlacement`（太空放方块造体）用的正是 tracker ⇒ 玩家**造出来的东西和牵引枪查的东西
根本不是一张表**。上一轮"客户端命中、服务端全空"的不对称根就在这里 —— 当时只当成"两边口径不同"，
这次有了 NaN 证据才看清是两张表。

**修法：服务端与客户端"同一张表 + 同一套数学"**

1. 新增纯函数 `physics/BodyRaycast`（只依赖 joml，可离线判）：把射线变到体局部、用 `RayBox`
   做 slab 求交 —— **与客户端 `PhysgunTarget` 完全相同的算法**；位姿 NaN / 零四元数 / 无方块的体
   **逐个跳过**（而不是让一个坏体把整次求交污染成 NaN）。
2. `PhysicsBodyTracker.rotationOf(long)`：原来只暴露 `positionOf`，拿不到姿态就转不了抓点；补上。
3. `PhysgunItem` 改到 tracker 上：抓取走 `BodyRaycast`；施力用 `applyImpulse(J)`，其中
   `J = PhysgunSpring.forceFor(mass, acc) × FORCE_DURATION` —— **与原来 MPS 那条
   `addForce(Force(f, 0.05))` 等效**（弹簧数学仍只有 `PhysgunSpring` 一份，`PhysgunDragProbe` 继续有效）。
   端点广播改用 tracker 位姿算抓点世界坐标。
4. 没打中时的诊断也换成 tracker 的表 ⇒ 报出来的数字才是真的（"最近体原点 X 格（共 N 个体）"）。

**顺带修掉 HUD 的"一堆 0"**（把截图放大逐字读出来的）：`SpaceAttitudeOverlay` 把俯仰梯整组按滚转
倾斜（`mulPose(tiltDeg)`），**数字跟着一起转** —— 侧翻 180° 时 `60` 显示成 `09`、`30` 显示成 `0E`，
再叠上 `滚转 -180.0` 与天体标签 `+ 月球` 挤在同一行，看上去就是"满屏 0 和乱数字"。
修法：每个刻度数字先 `translate(0, y)` + `mulPose(-tiltDeg)` 转回来再画 ——
**真实姿态仪也是这样：地平线/梯级随姿态转，刻度数字始终正立**。

**离线判据**：新增第 12 步 `BodyRaycastProbe`（19 项）：单体正打（t=4.85 = 外扩面）、
多体取最近（不是拿第一个）、超距 null、**体转 90° 命中而未旋转打不到**（真能分辨旋转）、
**位姿 NaN 的体被跳过且坏体不废掉整次求交**（就是这次的故障）、零四元数/空方块/空表/零方向/
非正 maxDistance 全部安全、擦边（1.10 命中 / 1.20 未命中）、背后不命中。
`run-offline-checks.ps1` 现为 **12/12 全绿**；`check-artifact.ps1` **23 个类 + 1 张贴图**全命中。

**又一次"marker 写错地方"**：`BodyRaycast.Hit` 是内部 record ⇒ `localX` 在 `BodyRaycast$Hit.class`
里，不在外层类。这已是本项目第三次（前两次 `PhysgunClientState$Beam`、`PhysgunBeamShape`）——
规矩：**marker 必须写它真正所在的那个 class**。

**实机判据（请用户验）**

| # | 操作 | PASS | FAIL 时首查 |
|---|---|---|---|
| 1 | 太空里对准物理体**按住**右键 | 光束一直在，物体被弹簧拖向准星前方；松手才淡出 | 聊天栏：若仍"准星没指到"，看后面那句"最近体原点 X 格"（现在是真数字；X 很小却打不中 = 求交还有问题） |
| 2 | 抓着时走动/转视角 | 物体跟着走，光束端点粘在物体同一处 | `worldAnchor` 用的 `rotationOf` |
| 3 | 松手 | 光束淡出（约 0.1 秒）+ 音效 | `releaseUsing` 的客户端分支 |
| 4 | 侧翻 180° 看姿态仪 | 俯仰梯数字**正立**（60 就是 60，不再像 09） | `SpaceAttitudeOverlay` 的 `-tiltDeg` 反旋 |
| 5 | 别人也拿一把枪 | 他的光束你也看得见 | 包注册 + 广播半径 |


#### 牵引枪第九轮：**把"闪电"换成"钓鱼竿曲线"**（2026-09-29，用户定调）

用户原话：**"航空学的那种酷似闪电的连线也太有辨识度了，我们要防止撞车，就一般曲线就好了，
要是曲线，并且会根据你移动画面而更改，就跟钓鱼竿受力弯曲一样"**。

这句其实是把**第二~六轮一直照抄的那件东西**（参考 {@code PhysicsBeam} 的<b>节点链 + 随机游走抖动</b>）
明确否掉了 —— 它正是航空学辨识度的来源。所以这一轮是"**从照抄转向避让**"：
形状机制不能再跟参考同构，要换成我们自己的曲线。

**新形状：贝塞尔 + 软弹簧"虚拟竿尖"**

```
虚拟竿尖 tip ──软弹簧(K=160, D=15, 4 子步)──▶ 真实抓点 end     // 每 tick 推进一步
受力向量 bend = end − tip（按模长限幅 3 格）                  // 你甩视角时它最大
控制点     c  = (start+end)/2 + bend×1.5 + 垂直基础弓形(0.02/格,≤0.2)
曲线       B(t)= (1−t)²·start + 2(1−t)t·c + t²·end            // 两端始终咬住
线宽       w(t)= w₀·(1 − 0.55t)                              // 根粗尖细 = 竿形
取样       0.75 格一段（6..48 段）
```

三个设计要点（都是为了满足用户那句话）：

1. **两端永远咬住**枪口与物体，弯的只有中间 ⇒ 甩视角时<b>不会</b>出现"光束脱开物体"
   （如果让"末端落后"直接体现在端点上就会脱开 —— 这里把落后量只用在<b>控制点</b>上）。
2. **弯曲方向 = 受力方向**：`bend` 指向"物体正在拉竿的方向"，所以你朝哪边甩，竿身就朝哪边鼓；
   停下后弹簧归位，曲线慢慢回直 —— 这就是钓鱼竿的手感。
3. **基础弓形与相机无关**（沿"光束方向 × 参考上方向"的垂直轴），否则你只是转头、曲线就自己晃。
   静止时是温和的曲线（"一般曲线就好了"），不是死直线、也不再是锯齿闪电。

**改动的文件**：`PhysgunBeamShape`（删掉 nodeCount/nodeRadius/walk/point → 换成
segments/bezier/control/lagStep/taper）、`PhysgunClientState.Beam`（节点数组 → 虚拟竿尖 + 速度 + 软弹簧）、
`PhysgunBeamRenderer.drawBeam`（折线 → 贝塞尔采样 + 收细）。

**离线判据**：`PhysgunBeamShapeProbe` 第 0~4b 节整段重写（节点链那套判据随机制一起删掉）：
段数下限/上限与单调、收细单调且 t 越界被夹、贝塞尔两端<b>正好</b>落在端点上且 t 单调推进、
控制点在"无受力/落后 1 格/落后 60 格（限幅）"三种情况下的偏移量精确对表、
基础弓形只走横向（控制点的沿轴分量必须正好是中点）、软弹簧一步内不乱跑 / 0.25 秒跑过一半 /
1.25 秒收敛到 1% 内 / 瞬移时落后量可见但有限 / 不出 NaN。套件仍 **12/12 全绿**。

**实机判据（请用户验）**

| # | 操作 | PASS | FAIL 时首查 |
|---|---|---|---|
| 1 | 抓住后不动 | 一条**平滑曲线**（微微弓形），不是直线、不是锯齿闪电 | `control` 的基础弓形项（0.02/格） |
| 2 | **快速左右甩视角** | 竿身朝甩动方向**明显弯**，停下后 0.3 秒左右回直 | `LAG_K/LAG_D`（ω≈12.6、ζ≈0.6）；`lagStep` 的子步数 |
| 3 | 长时间快速甩 | 弯曲有上限（不会把曲线拉成麻花） | `LAG_MAX=3` 的模长限幅 |
| 4 | 近距离抓小物体 | 短光束近乎直线（弓形按长度算，短的就小） | `BOW_PER_BLOCK` |
| 5 | 看粗细 | 从枪口到抓点**逐渐变细**（竿形） | `taper` / `TAPER=0.55` |


#### 星球遮挡第十轮：**"远处的星球把近处的物理体挡住"**（2026-09-29，用户实测）

用户原话：**"发现一个bug，远处的星球反而还把近处的物理体给挡住了"**。

**取证路径**（都写在注释里了，此处归档）：

1. 先排除"星球本体画在了世界之后"：`SpaceRenderer.renderSpaceBodies` 跑在 **`AFTER_SKY`**，
   即 MC 地形/实体之前 —— 顺序本来是对的，而且它在 `AFTER_SKY` 清深度也是安全的
   （那时深度缓冲里只有天空盒）。所以**不是本体**。
2. 于是看 `AFTER_PARTICLES` 的屏幕空间后处理。对照两条链路：

| | `DepthSampler` | `SpaceDepthSampler` / `SkyDepthSampler` | `useMinecraftDepth` |
|---|---|---|---|
| 恒星泛光（正常） | AFTER_PARTICLES 主深度 | **AFTER_SKY 太空底** ✔ | — |
| 行星大气（有问题） | AFTER_PARTICLES 主深度 | **同一张主深度** ✗ | **0** ✗ |

3. 再看 shader：`planet_atmosphere.fsh:131` 的 `ScreenToWorld()` 用
   `max((1 - DepthSampler.r) * useMinecraftDepth, SpaceDepthSampler.r)` 重建视图位置 ——
   `useMinecraftDepth=0` 把**唯一会看世界几何的那一项**乘成了 0；而 `SpaceDepthSampler`
   又被绑成"世界画完之后"的主深度 ⇒ 重建出来的位置是跨投影的假距离。
4. 最后 `blitToMain` 是**关掉深度测试的全屏 blit** ⇒ 大气（视觉上就是星球本体/边缘）
   无条件盖到物理体上。

**两道"保护世界几何"的机制同时失效** —— 这就是那个 bug。

**修法（照泛光已验证的契约把大气对齐）**

1. `SpaceAtmosphereRenderer.render(...)` 增加 `skyDepthTextureId` 参数；
   `SpaceDepthSampler` 改绑 **AFTER_SKY 的太空底**（不再与 `DepthSampler` 同一张）。
2. `planet_atmosphere.fsh` 的 `main()` 开头加**世界优先**判据：
   ```glsl
   if (texture(DepthSampler, texCoord).r < texture(SpaceDepthSampler, texCoord).r - 1.0e-7) {
       fragColor = vec4(0.0);   // 这一像素上世界几何比星球更近 ⇒ 大气不许画
       return;
   }
   ```
   判据就是 `SpaceRenderer` 注释里那条（"depthNow < skyDepth 当且仅当该像素有 MC 几何体
   真正画了上去"），也是泛光 pass 用的同一条。两者都是同一相机、标准深度（越小越近），
   而星球恒在最远端（压缩后 ≈ FAR）⇒ 任何世界几何都比它近。
3. `SpaceRenderer` 里太空底改成**无条件采集**（原来只在"本帧有恒星"时采，注释写着泛光是
   唯一消费者 —— 大气现在也消费它，那条假设作废）。

**离线回归判据（源文本级，加进 `check-artifact.ps1`）**：这类 bug 编译期毫无提示、只有画面能看出来，
所以把契约钉在文本上 —— ① 大气 shader 必须含那条世界优先判据；② `SpaceDepthSampler` 必须绑
`skyDepthTextureId`（不能与 `DepthSampler` 同一张）；③ 太空底必须无条件采集（源码里不许再出现三元判断）。

**风险与兜底**：着色器改动若编译失败，MC 会记一条 shader 错误，`SpaceAtmosphereRenderer` 的
`catch (RuntimeException)` 会把大气整体禁用（**不会崩**）—— 所以这一条请务必看一眼实机：
如果大气整体消失，说明 shader 没编过，把那行报错发我。

**实机判据（请用户验）**

| # | 操作 | PASS | FAIL 时首查 |
|---|---|---|---|
| 1 | 太空里把物理体放在星球**前面** | 物理体完整可见，星球在它**后面**（边缘/大气不再盖住它） | shader 是否编过（大气消失=没编过）；`SpaceDepthSampler` 绑定 |
| 2 | 慢慢移动视角让星球从物体后面经过 | 物体轮廓处星球/大气被**干净地切开** | 判据里的 `1e-7` 容差（浮点相等的情况） |
| 3 | 地表维度看别的天体 | 地形（山、建筑）同样不会被天体大气盖住 | 地表走的是同一套（`celestialWorld != null`）分支 |
| 4 | 恒星泛光 | 与原来一致（它本来就是对的，别回归） | `SpaceStarBloomRenderer` 的 sampler |


#### 星球遮挡第十一轮：**根因是"两套投影共用一张深度缓冲"，不是纹理绑错**（2026-09-30）

用户第二次报：**"星球遮挡错误啊，远处的星球的图层显示在近处的物理体上面"**
—— 说明第十轮那个修法（改判据 + 绑对太空底）**没修对**。

**先排除"没生效"**：`build/classes` 里 `SpaceRenderer.class` / `SpaceAtmosphereRenderer.class`
时间戳（09-29 21:39）晚于源码（21:38）；今天的 `run/logs/latest.log`（09-30 10:56）里
`Planet atmosphere passes created` 正常、无 shader 报错 ⇒ 改动**进了产物也跑起来了**，
所以错的是**判据本身**。

**真根因**：主深度缓冲里混了**两套投影**的深度值 —— 星球在 AFTER_SKY 用 spaceProj
（near=1000m / far=524288m）写入，世界几何体随后用 MC 主投影（near=0.05 / far=768）写入。
两套投影的深度值**不可比**，于是两件事同时坏掉：

| # | 后果 | 机制 |
|---|---|---|
| 1 | 物理体**画不出来** | 星球先写下的深度更小（= 判定为"更近"）⇒ 后画的物理体 LEQUAL 失败 |
| 2 | 大气/泛光**盖到物理体上** | 遮挡判据 `mainDepth < skyDepth` 恒为假 ⇒ 掩码永远是 0 |

**离线量化**（新增 `native/jni-smoketest/DepthOcclusionProbe.java`；取用户 09-30 会话：
玩家离地球 15849 格）：

- 地球表面压缩后 **50853 m** → spaceProj 深度 **0.98221**；
- 5 格处的方块用 MC 主投影 → **0.99006** ⇒ 方块反而"更远"；
- **临界距离只有 2.80 格** —— 比这远的方块全都输给星球。

**并且推翻了"这是距离压缩引入的回归"这个假设**：把 spaceProj 的 far 换回压缩前的 `1e13`，
临界距离是 **2.53 格** —— 旧判据**从来**只对贴脸的东西成立。它一直没露头，
只是因为 09-27 起太空里**才第一次有物理体**需要被它遮挡。

> **教训：这次是探针纠正了我的归因。** 我先按"压缩把星球深度从 0.9999 拉到 0.973"
> 把注释和结论都写好了，探针一跑才发现两处算错（`compress()` 忘了加 NEAR；
> 把 far=1e13 时的临界距离想当然）。**数字必须由探针给，不能由推理给** ——
> 与 §31.23「死判据」同一条纪律的另一面。

**修法（结构性的，不是再调判据）**：把星球层的深度**在离开 AFTER_SKY 之前抹掉**，
让主深度只属于 MC。

- `SpaceRenderer.renderSpaceBodies`：`captureSkyDepth(...)` 之后加
  `RenderSystem.depthMask(true); clearDepth(1.0f); clear(0x100, false);`
  ⇒ 世界几何体照常画在星球**上面**；星球层自己的互相遮挡不受影响（在本行之前已画完）。
- `planet_atmosphere.fsh`：判据改成**投影无关**的
  `texture(DepthSampler, texCoord).r < 1.0 - 1.0e-7`（"这一像素世界画过没有"）。
  `SpaceDepthSampler` 保留，仍只用于 `ScreenToWorld` 重建星球表面位置。
- `star_bloom.fsh`：`MinecraftOccluder` 同样改成 `mainDepth < 1.0 - DEPTH_EPS`；
  连带**删掉**它已经用不到的 `SkyDepthSampler`（`.fsh` + `.json` + `SpaceStarBloomRenderer` 的形参），
  避免"声明了却被 GLSL 优化掉、`setSampler` 找不到名字"这一类隐患。

**新判据成立的前提（已显式钉住）**：会画出来的世界几何体（≤ 渲染距离 32 区块 = 512 格）
在渲染帧里一定比"超出 NEAR 的天体"近（后者渲染距离 ≥ 16384 m）。唯一例外是天体表面近到
NEAR 以内（< 1.64 格）—— 那时相机已经贴在天体上，可接受；`DepthOcclusionProbe` 的 C 组
把这条例外钉出来了。

**有意偏离 space 的地方（记录下来，供以后判断）**：space 的做法是让两套投影**共用同一对
near/far**（`MixinGameRenderer` 把 `getDepthFar` 改成 `farCompressionDistance * 2`，
太空投影取 `setPerspective(fov, aspect, getDepthFar(), 0.05F)`，即反向 Z），
并且天体画进**独立的 render target**、主深度从头到尾没被污染。我们没照抄这两点，因为
① 改 `getDepthFar` 是**全局** MC 投影改动（地表维度的地形/实体一起吃）；
② 把星球画进独立 target 要再加一个 target 与一次合成。
现在的"画完就清深度"能达到**同样的结构效果**（主深度里没有天体深度），
代价只是星球层必须自己留一份太空底。**若以后要彻底对齐 space，这里是入口。**

**离线回归**：新增第 13 步 `DepthOcclusionProbe`（21 项：旧判据在 1/2/5/20/100/300/512 格处的
真假、临界距离、压缩前后临界距离对比、新判据与天体距离无关、空像素不被判成遮挡、
渲染距离上限 < 天体渲染下界、`compress` 单调）。`run-offline-checks.ps1` 现为 **13/13**；
`check-artifact.ps1` 的着色器契约也按新判据重写（旧契约文本
`DepthSampler < SpaceDepthSampler` 已作废）。

**为什么这个 bug 直到 09-27 才可能出现（用户提问的追查，2026-09-30）**

用户问："之前好像没有这个问题，也许是在你增加了凭空放置物理体之后，我说物理体无法显示出来的时候有的 bug？"
—— **时间线对，但那次报告的是另一个 bug。**

1. **这个 bug 只能在"太空维度里已经有物理体"之后才可能被看见**，因为天体只有**在太空维度**才会
   写下一个 0.98 级的深度：
   - 太空维度：压缩生效，天体深度 0.9~0.98 ⇒ 挡住世界几何体；
   - **地表维度**：`RenderCompression.active = false` ⇒ spaceProj 的 far 仍是 `1e13`，
     最近的天体（月球 3.8e8 m）深度是 0.9999974，反解出的临界距离约 **740 格**，
     而渲染距离上限是 512 格 ⇒ **挡不住任何地形**。而且"当前所在天体"在
     `SpaceRenderer` 第 252-255 行就被 `bodies.removeIf(o -> selfId.equalsIgnoreCase(...))`
     排除了，所以地表也不会画自己脚下那颗星球。
   - 而物理体在 09-27「凭空放置物理体」之前**只可能存在于地表维度** ⇒ 那时这个 bug 无从显形。
2. **但用户记得的那次"物理体无法显示"是另一个根因**（见本节上方「⚠️ 第一版的根因」）：
   第一版太空建造绕过了 `PhysicsBodyTracker`，客户端收到的是**空体**（没有方块快照）
   ⇒ 没东西可画；同时**没有碰撞体** ⇒ 射线打不到 ⇒ 判定"附近没有体"而不断新建。
   **指纹是"也无法对这个物理体再放方块"** —— 纯渲染/深度问题**不可能**导致拾取失败，
   所以那次一定是数据链的 bug，与深度无关。
3. **两个 bug 的症状会重叠，这就是记忆模糊的原因**：放置距离正好 `SpaceBuildRules.PLACE_DISTANCE = 3.0`
   格，而当时的深度临界是 **2.80 格** —— 所以"数据修好之后"，只要那个方向背后正好有星球，
   **体仍然看不见**。也就是说那次修完之后症状并没有被完全消除，只是概率降低了。

⇒ 结论：**这个 bug 不是 09-27 引入的，而是 09-27 第一次"有机会"出现**
（探针也证明它不是距离压缩引入的：换回 far=1e13 时临界距离 2.53 格）。
它与"物理体无法显示"是**两个** bug，只是症状部分重叠。

**实机判据（请用户验）**

| # | 操作 | PASS | FAIL 时首查 |
|---|---|---|---|
| 1 | 太空里把物理体放在星球**前面** | 物理体完整可见，星球在它**后面**（大气边缘不再盖住它） | 大气是否还在（`Planet atmosphere passes created`）；`mainDepth < 1.0` 判据 |
| 2 | 慢慢移动视角让星球从物体后面经过 | 物体轮廓处星球/大气被**干净地切开** | 主深度是否真被清回 1.0（`SpaceRenderer` 里那句 `clearDepth`） |
| 3 | 把物理体放到**几格以外**（越过旧的 2.80 格临界） | 同样挡得住（这正是旧判据做不到的） | 同上 |
| 4 | 地表维度看别的天体 | 远处地形（山、建筑）不会被天体大气盖住 | 星球层深度清掉后，地形应当总能画上去 |
| 5 | 恒星泛光 | 与原来一致（别回归）；被方块挡住的部分仍剪出轮廓 | `star_bloom` 的 `MinecraftOccluder` |

> 兜底：若进游戏发现**大气整体消失**，说明 shader 没编过（`SpaceAtmosphereRenderer` 的
> `catch (RuntimeException)` 会把大气整体禁用，**不会崩**）—— 把那行报错发我。


#### 构建/运行坑：`could not open ...\dataRunVmArgs.txt`（2026-09-27 实际踩到）

- **现象**：`runData`（datagen）直接以
  `Error: could not open '...\build\moddev\dataRunVmArgs.txt'` 失败。
- **根因**：`gradle.properties` 里 `org.gradle.configuration-cache=true`，而 moddev 的 run 参数文件
  （`dataRunVmArgs.txt` / `clientRunVmArgs.txt` …）是**任务产出**（`prepareDataRun` 等）。
  配置缓存命中时会按缓存里的旧状态跳过这些任务 ⇒ 一旦磁盘上的 argfile 被删
  （`clean` / 手动删 / 换机器），JavaExec 就会去开一个不存在的 `@argfile`。
  实机取证：`build\moddev\` 里只有 09-26 的 client 那几个 argfile、**没有 data 的**，
  而 `build\moddev\artifacts\*.jar` 还在（所以不是 `clean` 删的）。
- **修法**：加 `--no-configuration-cache` 强制重新配置 + 重跑参数任务：
  `.\gradlew runData --no-configuration-cache`（`runClient` / `runServer` 同理）。
- **固化**：`native/jni-smoketest/datagen.ps1` —— 一条命令跑 datagen 并**自检产物**
  （`dataRunVmArgs.txt` + 两件工具模型 + 中英 lang 各 2 个键），全绿才退出 0。
  判据不看 gradle 退出码（本项目常见"退出码 1 但 BUILD SUCCESSFUL"），只看产物。

### 31.29 太空维度"可玩"方案：**拍板 ①（缩放 1 格 = 10⁴ 米）**，判定规则 = "哪个最像 space"

用户 2026-09-27 给的规则原话：**"哪个最像 space 我们就选哪个"**。下面是按这条规则逐个方案的取证结果
（全部带行号；"像不像 space"只看两版参考里**有没有这个机制**，不看它是否好用）。

| 方案 | 0.0.6 | 0.1.3（对齐目标） | 判定 |
|---|---|---|---|
| **① 缩放坐标（1 格 = N 米）** | **是它的原生机制**：`SpaceWorld.PositionZoom`（`.wipbak/space006/src/org/cn_grass_block/space/util/classes/SpaceWorld.java:27,40-44`），换算 `:78-80`（×Zoom）/`:90-92`（÷Zoom），数据 `space_data/*/type.json` 的 `position_zoom: 10000`，读取点 `util/manger/SpaceModDataPackManger.java:77,84`，咽喉调用点 8 处（`event/PlayerTeleportManger.java:43,84,102`、`client/render/SpaceRenderMainline.java:100`、`mixin/.../MixinEntity.java:57,74,97` 等） | **删掉了**：`kelvin/physical/space_world/SpaceWorld.java:46-49` 构造只剩两个参数；`:88-90`、`:100-102` 两个换算方法体是 `new Vector3d(x,y,z)`（恒等）；数据侧 `SpaceModDataPackManger.java:80-82` 只读 `sky_texture`；`solar_system/type.json` 已无该字段（`alpha_system/type.json:3` 留着 `10000` 是**死字段**） | **像 0.0.6 的 space**（且是唯一一个"space 为太空维度自己设计的坐标机制"）⇒ **选它** |
| **② 时间倍率旋钮** | `config/SpaceModCommonConfig.java:17` `tick_time = 0.72` ⇒ 100 Hz × 0.72 = **72×** | `config/SpaceModCommonConfig.java:26` `tick_time = 0.01` ⇒ **1×**（`kelvin/OrbitPhysicalThread.java:34-35` 从配置读、`:67` 10 ms；`:18` 的 0.72 只是 `startThread` 之前的字段占位，`:80-81` stop 时复位 0.01） | 两版都有这个旋钮 ⇒ "像"，但**单独不解决问题**（1× 下地球仍 30,151 格/秒），保留为旋钮 |
| **③ 天体参考系跟随** | **没有**：`ValkyrienSkies_SYNC_MODE`（"物理体跟随天体"）与 `ACCELERATION_SCALING` 只在 `config/SpaceModCommonConfig.java:22-27` **声明**，全库**没有任何一处读它们**（死配置）；`compat/valkyrienskies/ShipManger.java` 只做三件事 —— 维度重力、`y ≥ Height` 时把船传进太空（`:64` 用 ÷Zoom）、把船注册成 `Aircraft` 天体吃引力（`:112-131`） | **没有**：全库 grep 无 `valkyrienskies`/`ValkyrienSkies`；星际靠**火箭**（`game/rocket/RocketAssemblyService.java:258 materialize(...)`、`block/.../LaunchPadBaseBlockEntity.java`、`propulsion/{Chemical,Hall}ThrusterBlockEntity.java`），地表→太空仍是自动传送（`util/event/SpacePlayerTeleport.java:22-34`） | **两版都没有 ⇒ 选它就不是"像 space"，是自创 ⇒ 否决** |
| **④ 距离压缩** | `util/classes/CelestialBody.java:269-276`（`getRenderZoom`/`PositionCompression`） | `sunshine/render/celestial_body/ClientCelestialBody.java:29-35`，调用点 `SpaceRenderer.java:476`、`CelestialBodyDataUBO.java:161,245`、`ClientPlanet.java:43`、`ClientStar.java:38` | **两版都开** ⇒ 最"像"；**本轮已接通并启用**（见下文"④"小节），但 UBO 的 `RealPos` **有意保持真实**（量化依据：全压 4.34° vs 半压 0.003°） |

**结论与代价（据实记）**：① 是**坐标重标定**，物理关系按 N 等比例改善 ——
地球等效速度 2.165e6 格/秒 → **216 格/秒**；地月 3.84e8 → **38,400 格**；地球方块坐标 1.53e10 → **1.53e6 格**
（回到 BlockPos 26 位上限 3.36e7 之内 ⇒ **太空维度重新拥有区块**，这正是今天每次升空都打
`目标在深空，跳过区块预加载` 的原因）；地球半径 → **637 格**。但"贴脸停住"仍需要与行星同速（216 格/秒），
这条 ① 给不了 —— 要么接受"掠过"，要么将来做火箭/推进（0.1.3 的原生答案）。
地表维度本身**不是等比的**（离线实测：水平 **1 格 = 200.2 m**、竖直 **1 格 = 9.9 m**，差 20.1 倍），缩放只作用于太空侧。

#### 本轮已落地（增量 S0 / S1，都可回退）

- **S0 判据修复**（纯诊断，零行为改动；回退 = `git checkout` 这两个文件）：
  - `SpaceRenderer`：兜底分支的门从"太阳**不在**渲染表里"放开成"本维度有太空世界" ⇒ 修掉 §31.23 的**连带损伤**：
    归档日志证据（09-26 20:11 会话）`时钟: 20.00 tick/秒 ✔` 但 `天空转速=n/a`、`插值跨度=NaN`、
    `太阳角直径` **一次都没打印** —— 因为这三项只在兜底分支里赋值，而太阳改成 `byId` 之后主循环就能找到它。
    （`方位检查` 同理；09-26 19:11 之后地表天空探针再没跑过，当前 build 19:34 无读数。）
  - `顺滑=PASS` 判据补两条下限：每帧最大位移 **< 0.5 px ⇒ PASS**；样本 **< 30 帧 ⇒ 不判**。
    依据：归档 48 条 `★FAIL` 全是假报警（fps=1501 那行 均=0.01px、最大=0.03px，抖动却算出 3.80）。
  - `时钟` 补暂停守卫：世界暂停时 `dayTime` 不推进，旧写法会打 `时钟: 0.00 ★（≠20）`（归档里连续 60 秒都是这种假 FAIL）。
  - `[坐标落点]` 两条都补 `地球参考=(米)(…) 半径比=…`；回程再补 `与去程方向夹角=…° ⇒ PASS/★FAIL`
    —— 旧判据"两行坐标逐位一致"是**同义反复**（回程打印的是玩家输入坐标，玩家没动过 ⇒ 必然一致）。
- **S1 迁移双向化**（① 的前置，否则一翻约定存档里人和天体差 10⁴ 倍，即 §30.14 的"迁一半更糟"）：
  `SpaceScaleMigration` 现在按 `identityMode` 决定方向（恒等 ⇒ ×ZOOM，缩放 ⇒ ÷ZOOM），
  判据改成**单位对口径**（旧版拿"米"的天体距离去比"格"的玩家坐标）+ **三维距离**（水平距离会把
  "站在星球正上方"算成 0 ⇒ 误判成钻进天体里），并带"换算后钻进天体内部 ⇒ 不动"的防假阳性守卫；
  `PhysicsBodyTracker` 与玩家共用同一个纯函数。离线场景表（6 条）见
  `native/jni-smoketest/SpaceMappingProbe.java` 第 5 节，全部 PASS。
- **S1b 判据自动化 + 静默判据清理**（2026-09-27 同日）：
  - 新增 `native/jni-smoketest/check-probe-log.ps1` —— **一条命令出判决**：
    `pwsh native\jni-smoketest\check-probe-log.ps1`（默认读 `run\logs\latest.log`，可 `-Log <.log|.log.gz>`）。
    它把三种结论**分开报**：`PASS/★FAIL`（判据跑了）／`未出现`（判据根本没跑，比 FAIL 更该查）／
    `不可判`（读了但 n/a/NaN ⇒ 判据自身坏了）—— 正是 §31.23 那条教训的工程化。
    自带 `-SelfTest`：样例 A 必须全 PASS、样例 B 必须报出 FAIL（**证明它不是死判据**）。
  - `[坐标自检]` 从"只打印"改成**真比较**：新增 `SpaceWorld.STATIC_CHECKSUM_GATE`，
    并查明它与历史值 `-5410990681030` 的差 **Δ=-713,039,320** 的来源 ——
    提交 `a23ac6e`（"天体太空"）改了**卫星轨道参数化**（相位/轴向互换 + 加入轨道倾角分量）⇒
    多颗卫星位置同时变（离线证据：这个 Δ 既不是单颗贡献、也不是两颗之差，见探针第 7 节）。
    **按"有意改动"处理：2026-09-27 重新定基线**；以后一变就该带日期+提交号重定，不许继续静默漂。
  - `run-offline-checks.ps1` 从 3 项扩到 **5 项**（+ 映射/迁移探针 + 判读器自测），一次跑完退出码 0。
    两个踩到的坑记在这里：① 该总入口的 `$ErrorActionPreference='Stop'` 会把 Java 的 SLF4J
    **stderr 当终止错误**，导致 4/5 之后的步骤一个都跑不到（这一段临时改 Continue，只看退出码）；
    ② 编辑工具改这两个 `.ps1` 会**掉 BOM**（§31.26），每次改完必须复查前三字节 `EF BB BF`。

#### S2（**本轮已落地**）与判据

1. ✅ 已翻 `SpaceWorld.identityMode` 初值 → `false`（回退 = 改回 `true` 重编，或运行时 `setIdentityMode`）。
2. ✅ 三处口径混算已按 0.0.6 的"边界换算"改掉（`mps/kelvin/event/PhysicalBodySpaceEvent.java`）：
   - `:101-107` 同步弹簧：**先把 aircraft 的位置换算到方块口径**再相减（`SpaceWorld.toGame`），
     阈值按 `toMc(1)` 重标（增益不动 —— 冲量 ∝ Δv，换算已含在"距离−阈值"里）；
   - `:116-118` 力镜像：MPS（格·kg/s²）⇒ kelvin（牛顿）**乘回** ZOOM（`SpaceWorld.toSpace`），
     方向与 `ShipManger.java:122-124` 一致；
   - `:152-159` `dimensionLeap` 目标点：`getSpacePosFromWorldPos`（米）**过 `toMc`** 之后才用作太空维度落点。
   - 线速度/角速度仍原样带过去（地表↔太空映射各向异性，不存在正确的速度换算；0.0.6 是**清零**）——
     改成清零属**玩法决定**，单列待拍板。
3. ✅ 离线把两种约定都验了一遍（`native/jni-smoketest/SpaceMappingProbe.java`，15 项判据全 PASS）。两个新发现：
   - **访问器契约必须钉死**：`blockPos`/`renderPos` 返回的是 **米**（天文坐标系，与方块约定无关），
     `gamePosMc` 才是**格**（随 `identityMode` 变）。恒等约定下两者同值，所以 §31.6 以来一直分不清；
     翻转后这一条是"渲染/传送会不会错 10⁴ 倍"的分水岭 —— 探针第 6 节现在把三条契约都写成了断言
     （防止将来有人"顺手"把 `blockPos` 改成 `toMc`，那会把渲染和捕获一起改坏）。
   - 翻转后的世界数：地球 **1.53e6 格**（< BlockPos 26 位上限 ⇒ **"目标在深空，跳过区块预加载"将消失**）、
     半径 **637.1 格**、地月 **38,400 格**、地球每 tick 位移 **10.8 格**（对照：恒等时是 1.08e5）。
   - 太空维度的生成器是 `minecraft:flat`（`mps/space/util/manger/SpaceModDataPackManger.java:515-516`）⇒
     翻转后**有区块、无地形**：空空间（"太空里要不要地面"是另一个决定），但**方块是真的能放了** —— 三条数：
     ① `data/poly_mech/dimension_type/space.json`：`height=2048 / min_y=-64` ⇒ 合法 Y ∈ [−64, 1983]，
        而翻转后玩家/地球在太空的 Y = 1087.6（= 1.0876e7 ÷ 10⁴）✔ 在建造高度内
        （**恒等时是 1.0876e7，远超上限 ⇒ 连放置合法性都不成立**）；
     ② `LevelSpaceAccessMixin.java:30-76`：只有 `isDeepSpace`（|x| 或 |z| > 3.355e7）才把
        `getBlockState` 顶成 VOID_AIR、并把**两个 `setBlock` 重载直接返回 false**（§30.10 那个别名坑）。
        地球在恒等时 |z| = 1.47e11 **命中**（整片近地空间放不进任何方块），翻转后 1.47e7 **不命中** ✔；
     ③ 该守卫的边界 = 3.36e7 格 = 3.36e11 m ≈ 2.25 AU ⇒ 覆盖水星/金星/地球/月球/火星，
        **木星及以外仍是深空桩**（无方块可放）—— 这正是"守卫以内是真实区块，供地球/火星近旁活动"的原设计。
     `SpacePreloader.RADIUS_CHUNKS = 3`（7×7=49 区块）⇒ 预加载本身很便宜。
4. 傻瓜判据（一次重启的会话里 grep）：
   > **首选：不用手抄** —— `pwsh native\jni-smoketest\check-probe-log.ps1` 直接读 `run\logs\latest.log`
   > 打出上表全部判据的 PASS/★FAIL/未出现/不可判（退出码 0=全过、1=有 FAIL、2=有判据没跑）。
   > 底下这几条是它对应的原始行，手抄时按这个对照：
   - `[坐标落点] … → 太空` **不再**跟着 `目标在深空，跳过区块预加载`，且 `约定=缩放(1格=10000米)`、`半径比≈2.20` ⇒ **PASS**；
   - 回程行 `半径比≈1.02` + `往返: 与去程方向夹角=x° ⇒ PASS(与去程同一条径线)`，且 `落点(x,z)` 与去程
     `玩家地表(x,z)` 差 ≤2 格 ⇒ **PASS**（**历史 09-21 那一局这里就是 ★FAIL：Δz=11.5 格**，
     判读器已能自动指出，这正是 S0 加那两个字段的意义）；
   - 位置从恒等存档迁过来时应有**一次** `[坐标迁移] … ÷10000 搬到 …`；不该重复出现（重复 = 判据不幂等）；
   - `[Kelvin] [地表天空]` 必须出现 `天空转速=0.3xx°/秒`、`插值跨度=<非 NaN> m`、`太阳角直径=0.5xx°`（S0 修的四项）；
   - `[Kelvin] [太空视运动]` 首行应为 `n/a(样本不足)`，之后为 `PASS(每帧<0.5px)` 或带真实 `最大>0.5px` 的 `★FAIL`；
   - `[坐标自检]` 现在会打 `静态blockPos校验和=…（闸门=… ⇒ PASS）`—— **没有那个 `⇒` 就是判据被改回"只打印"了**。

#### ④ 距离压缩：**本轮接通并启用**（照抄两版默认开），但**有意不照抄"全压"**

- 接线：`CelestialBodyDataBuffer` 里 **Pos / 半径 / 大气壳厚度** 乘 zoom（网格帧），
  **`RealPos` 与真实大气高度保持米**（物理量）。依据是 shader 的**消费方**：
  `planet_atmosphere.fsh:104,152,161,162` 用 `planet.Pos`/`R`/`AtmosphericHeight` 做球面求交、法线、
  壳厚积分（必须与网格同帧），`:168` 只用 `starlist[].RealPos` 算**光照方向与遮挡**（必须真实）；
  shader 自己那行 `AtmosphericHeight / RealAtmosphericHeight` 就是它要的压缩因子。
- **量化判据**（`native/jni-smoketest/RenderCompressionTest.java` 第 ⑤ 节，离线）：
  相机在地球 2.2R 处、样点取球面四个方位：
  **全压（= space 的做法）光照方向误差最大 4.3445°**，而
  **半压（我们：Pos 压 / RealPos 真）只有 0.0026°** ⇒ 相差约 1600 倍。
  **这就是"照抄思路、不照抄这一处"的依据** —— 不是口味问题，是 4.3° 的实测量。
  同节还确认：地球表面被压 **419 倍**（7.645e6 → 1.824e4 m）、太阳压缩后贴到 **FAR=262144**，
  而启用时 `spaceProj` 的 far 自动取 `FAR×2=524288` ⇒ 不会被远平面裁掉。
- **前提（写进代码注释了）**：压缩只作用于天体，地形/实体/粒子仍用 MC 自己的投影画真实距离。
  压缩是**单调**的所以近处遮挡顺序不变；但超过 `NEAR=16384 m` 后两套投影的深度值不再可比。
  现在太空维度是 `minecraft:flat` **空世界**（无地形），放置的方块/船都在几百米内 ⇒ 安全；
  **将来若给太空维度加地形或远距离结构，必须先解决这一步**（space 的做法是让两套投影共用 near/far）。
- **回退**：`RenderCompression.enabled = false` 一行；此时 `active` 恒假 ⇒ 渲染路径逐位回到启用前
  （UBO 里的 `zoomOf` 也直接返回 1.0，不做任何乘法）。
- 离线回归现在 **7/7**（第 5 项 = 距离压缩，**第 7 项 = 新增 `check-artifact.ps1` 产物自检**），退出码 0。
  `check-artifact.ps1` 专治本项目两次真实教训：§31.9 的"mixin 注入成功是静默的"以及
  "源码改了但没重编 ⇒ 客户端跑旧 class ⇒ 日志里还是旧格式"。做法是在 `build\classes` 的 .class
  常量池里搜本次改动的字符串/方法名（8 个类、20+ marker），任一缺失就报 MISS。
  顺带记一个新的编码坑：**补 BOM 只许用字节级 `ReadAllBytes`/`WriteAllBytes`** ——
  这轮有人用 `Get-Content -Raw` + `WriteAllText` 想"规范化编码"，结果整份中文脚本被按 ANSI 解成乱码、
  直接解析失败（比 §31.26 的"掉 BOM"更狠）。

#### S2 翻转审计台账（2026-09-27：逐条读过代码，别再重审一遍）

| 环节 | 结论 | 证据 |
|---|---|---|
| 坐标咽喉 | ✔ 唯一入口 | `SpaceWorld.toMc/toReal`；全仓 44 处 `gamePos/blockPos/gamePosMc` 消费点已逐个过 |
| 渲染相机 | ✔ 同帧 | `SpaceRenderer.java:160-162` 相机 `toReal`（米）；行星位姿走 `blockPos/renderPos`（米）⇒ 两侧都是米 |
| 进/出太空 | ✔ 同帧 | `SpaceTransitionHandler`：`pxReal=toReal`（米）与 `gamePos`（米）比较；落点/预加载走 `toMc`（格） |
| 传送命令 | ✔ | `PlanetDimensions.java:165,178` 用 `gamePosMc` + `toMc(radius)` |
| 存档迁移 | ✔ 双向 + 单位对口径 | `SpaceScaleMigration`（本轮改），`PhysicsBodyTracker` 共用同一纯函数 |
| 物理 ↔ 天体桥 | ✔ 三处边界换算 | `PhysicalBodySpaceEvent.java:101,116,152`（弹簧 / 力 / 跃迁落点） |
| 客户端过渡 | ✔ | `ClientSpaceTransition`（只转发服务端坐标）、`SpaceTransitionSyncPacket`；`SpaceTravelMixin.java:175` 的"单 tick >1000 格 = 传送"阈值在两种约定下都成立（缩放后一次传送 ≥1e6 格，而正常飞行 ≤11 格/tick） |
| 头盔 HUD | ✔ 同口径 | `SpaceHelmetHudOverlay.java:72-101`：太空支路相机取方块坐标、天体 `renderPos→toMc`；地表支路两侧都 `toMc` ⇒ 两条路一致 |
| UBO（大气/泛光） | ✔ 已接线 | `CelestialBodyDataBuffer`：Pos/半径/壳厚走压缩帧，`RealPos`/真实高度保持米 |
| 访问器契约 | ✔ 已钉死 | `blockPos/renderPos` = **米**；`gamePosMc` = **格**；探针第 6 节有断言（防"顺手改成 toMc"） |
| 姿态死代码 | 记录 | `PlanetRenderObject.renderZoom` 无调用点；真正的接线点是同文件的 `beginBodyModelView → compressionZoom`（勿被名字误导） |

**顺带结案**：轨道验收"检查 19 个天体" vs `[坐标自检]` "大气数=20" —— 差的是**太阳**：
`OrbitAcceptanceTest.java:104-105` 有意跳过（`sun` 无母天体 ⇒ 没有轨道六要素可验）。不是漏验。

#### 下一段主线：推进 —— "能飞、能停"的最后一块（离线标定已完成，2026-09-27）

**参考的答案（两版一致）**：space 不给玩家任何推进 —— 全库无 jetpack / 作用于玩家的 thrust。
它的推进是**方块**：`ChemicalThrusterBlockEntity:17,40` 与 `HallThrusterBlockEntity:22,66`，
两者都是 `magnitude = 1000.0`，每 MC tick 追加一个**持续 0.05 s** 的力（世界 tickTime = 0.01 ⇒ 稳态恰好一份）；
霍尔版代价 32 FE/tick、缓冲 2048。

**离线标定**（`native/jni-smoketest/ThrustCalibrationProbe.java`，跑真实 MPS 克隆层，已并入回归第 8 项）：

| 载荷 | 追上地球 216.5 格/秒（= **能停**） | 128 格/秒（≈5 分钟走完地月的巡航速度） |
|---|---|---|
| **80 kg（人+服）** | **17.4 秒** | 10.3 秒 |
| 500 kg（小艇） | 1.8 分 | 64 秒 |
| 5 t（船） | 18.0 分 | 10.7 分 |
| 50 t（大船） | >2 小时未达到 | 1.78 时 |

- **引擎契约 PASS**：实测加速度 = `F/m`，误差 0.4%（例：5000 kg + 1000 ⇒ 0.1992 vs 0.2000 格/秒²）。
  这一条顺带证明了 MPS 克隆层的单位就是 **kg + 方块帧力** ⇒ 天体引力桥那三处"边界换算"的量纲方向正确。
- **选型含义**：同一推力下 80 kg 与 5 t 差 62.5 倍 —— **质量是唯一杠杆**。
  「能停」的最短路径是**套装级**（80 kg，17 秒）；「能飞星际」是**船级**（5 t，10.7 分钟到月球速度）。
- **"停"的两种语义**（别混）：① 与行星同速悬停（上面这张表）；
  ② space 原本的"停" = **落到行星影子维度**（我们已经有了，路径 A/B/C）。② 不需要推进，① 需要。

**三个增量**（I1 已完成）：
1. ✅ **I1 标定**：上面这张表 + 引擎契约（离线）。
2. **I2 推进器本体**：方块 + BlockEntity + `serverTick → applyForce(Force(dir, 0.05))`，
   照 0.1.3 的结构与参数；我们的管道已就位（MPS `RigidBody.Force` ✔、力镜像桥 `PhysicalBodySpaceEvent:116` ✔、
   `CelestialBodyForce` 换算 ✔、机器方块/能源的现成模式 ✔）。判据：离线可验"力进得去、a=F/m"，
   实机验"船真的动、燃料/电真的扣"。
3. **I3 实机试飞**：用户重启后按上表对时间。

**另一条路（明确标记为偏离）**：给玩家 6DOF 驱动加一个"太空档位"（速度上限 ×N）。
一行代码就能让人在太空里"飞起来、追得上"，但**两个参考版本都没有这个机制** ⇒ 属自创；
按"最像 space"的规则不首选，只作为"先要手感"的临时开关备选。

#### 顺带更正的三处旧记录

1. §31.27 表格"0.1.3 = 0.72 × 100 Hz = 72×"是 **0.0.6 的数**；0.1.3 出厂默认 `tick_time = 0.01` = **1×**（行号见上表 ②）。
2. §31.28"太空 → 地球的回程**从来没走过**"应更正为：**走过一次**（09-21 18:10:53，升空后 4 秒），
   但那一对日志的"逐位一致"是同义反复（见 S0 第 4 条），且**落点 z 差 11.5 格**至今无解释
   —— 离线已证映射角向闭合到 8e-8 格（`SpaceMappingProbe` 第 1 节），所以那 11 格只能来自
   "两侧用的地球参考不同/玩家不在同一径线"，要靠 S0 新加的字段在下次会话里判。
3. 归档文档第二节"本轮所有新增判据的读数一次都没有人看过"应更正为：**读数在 `run/logs/*.log.gz` 里**
   （去重后 `[地表天空]` 4095 行、`[太空视运动]` 38 行、`[坐标落点]` 11 行、`[坐标自检]` 45 行），
   而且其中 4 项（天空转速/插值跨度(sun)/太阳角直径/方位检查）**读不出来**（原因见 S0）。
   另：`[坐标自检]` 的 `静态blockPos校验和=-5411703720350` 与代码里写死的闸门 `-5410990681030` **不一致**，
   而这条判据只打印不比较 ⇒ 要么补上比较、要么更新闸门值，别让它继续静默。





















