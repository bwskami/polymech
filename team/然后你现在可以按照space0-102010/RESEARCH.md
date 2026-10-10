# 调研报告（RESEARCH）— 玩家×物理体碰撞：0.1.3 → 0.5.0 结构升级

> 调研员：Leo（researcher）。本报告 = 现状调研 + lead 结论逐条验证 + 改动点映射表。
> 引用约定：`<相对路径> · <锚点(函数名/字段名)> @ <行号>`。

---

## 0. 结论摘要（先看这段）

lead 给的结构结论**全部验证通过**，无方向性错误。需要改正/澄清的只有 3 处**表述精度**问题（见 §5）：

1. **双刚体退场已坐实**：`decompiled-space/0.5.0` 全仓库**无任何 `new PhysicalEntity(` 调用**——旧的玩家双刚体类 `PhysicalEntity.java`（212 行）仍在源码里，但已是死类。0.5.0 玩家碰撞 = 原版 `move+collide` + Java 侧 OBB 扫掠（`PhysicalEntityCollision.resolve`）+ 独立探针刚体（`PhysicalEntityProbe`）。
2. **旧结构两症状根因成立**：`PlayerPhysicsBody` 双刚体 `CCD=false` + 100Hz 单子步 + 高速 → 隧穿穿模；穿进去后速度链 `main=sibling+own` 对消 → 卡住。两条链都在我方代码里逐行对齐到了 space 0.1.3 的口径。
3. **映射表 12 项**全部给出（删除/改造/新增），见 §4。

---

## 1. 0.1.3 现状清单（当前玩家碰撞链上到底有哪些东西）

客户端玩家碰撞的唯一权威 = `ClientPhysics` 维护的一个 Rapier 世界。

| 环节 | 文件 · 锚点 @ 行号 | 职责 |
|---|---|---|
| 世界句柄 | `src/main/java/com/mss/polymech/client/physics/ClientPhysics.java` · `world` @ 94 | `long` 型 Rapier 世界句柄，玩家+地形+镜像体共用 |
| 世界创建 | 同上 · `ensureWorld` @ 914 | `NativePhysics.worldCreate` @ 923；`PhysicsStepThread.addPostStep` 挂 `PlayerPhysicsBody.afterStep`（@ 931）与 `stepShipSync`（@ 934） |
| 移动入口 | `src/main/java/com/mss/polymech/mixin/EntityPhysicsDriveMixin.java` · `polymech$moveWithRapier` @ 66 | `@Inject(method="move", at=@At("HEAD"), cancellable=true)`；`PhysicsClientHooks.tryDrive` @ 78 为真则 `ci.cancel()` @ 79 |
| dist 桥 | `src/main/java/com/mss/polymech/physics/PhysicsClientHooks.java` · `movementDriver` @ 49 / `tryDrive` @ 57 | 默认 no-op；客户端装载时挂到 `ClientPhysics.drive`（见下） |
| 桥装载点 | `src/main/java/com/mss/polymech/client/physics/PhysicsBodyRenderer.java` · `movementDriver = …` @ 413 | `entity instanceof LocalPlayer && ClientPhysics.drive(player, delta)` |
| 位移权威 | `ClientPhysics.java` · `drive` @ 619 | `probeGround` @ 665 → `playerRig.move` @ 666 → `bodyReadTranslation` @ 669 → `setPos` @ 687（位置从刚体读回） |
| 玩家双刚体 | `src/main/java/com/mss/polymech/physics/PlayerPhysicsBody.java` · `class` @ 39 | 主 DYNAMIC + 兄弟 DYNAMIC，质量 `BODY_MASS=25.0` @ 44（×2）；`lockRotations` @ 175–176；`CCD=false` @ 178–179；`gravity_scale=0` @ 183–186；摩擦 `PLAYER_FRICTION=20.0`（`PhysicsMaterials.java` @ 50） |
| 速度链 | 同上 · `velocityChain` @ 352 | `main.linvel = sibling.linvel + own` @ 375–377；`sibling.linvel = own` @ 398；`sibling.pos = main.pos` @ 400–403；由 `afterStep` @ 342 每子步驱动 |
| 物理体镜像 | `ClientPhysics.java` · `syncShips` @ 1178 | `ClientPhysicsWorld.ClientBody` → `BODY_KINEMATIC_POSITION` ShipBody @ 1189；`stepShipSync` @ 288 每子步 lerp/slerp |
| 地形 | `ClientPhysics.java` · `updateTerrain` @ 968 / `buildTerrainChunk` @ 1043 | 体素 `BODY_FIXED` @ 1101 |
| 服务端体管 | `src/main/java/com/mss/polymech/physics/PhysicsBodyTracker.java` · `class` @ 42 | 物理体 DYNAMIC 刚体（`createFromRegion`→`drop` 生命周期） |
| 服务端世界 | `src/main/java/com/mss/polymech/physics/PhysicsWorldManager.java` · `class` @ 22 | 每维度一个 Rapier 世界（`WORLDS` map @ 28） |
| 投影 | `src/main/java/com/mss/polymech/physics/ProjectionManager.java` · `class` @ 41 | 隐藏 `projection_world` 维度让船上方块/机器照常 tick |
| 服务端移动取消 | `src/main/java/com/mss/polymech/mixin/EntityPhysicsMoveMixin.java` · `polymech$physicsMove` @ 22 | 服务端实体 move HEAD cancel（`PhysicsEntityManager.isAttached` 时） |
| 死代码 | `src/main/java/com/mss/polymech/physics/PhysicsAabbSolver.java` · `class` @ 14 | 79 行体素 AABB 推出求解器；**全仓库无调用者**（grep 仅命中其自身类名+构造器） |

> 玩家双刚体客户端数据源：`src/main/java/com/mss/polymech/client/physics/ClientPhysicsWorld.java` · `ClientBody` @ 34（424 行，含 `blocks()` @ 108、位姿插值环、`revision` 修订号）。

---

## 2. 0.5.0 目标清单（要新增 / 删除 / 改造）

照抄基准：`decompiled-space/0.5.0/org/polaris2023/mps/collision/`。

### 2.1 玩家移动交回原版（改造，核心翻转）

- **撤销** `EntityPhysicsDriveMixin` 的 `move` HEAD cancel。
- 原版 `Entity.move + collide` 正常跑（原版天然防穿模：`build/mcsrc` `Entity:639 collide()` → `:652 setPos(夹过的位移)` → `:677 setDeltaMovement(被挡轴清零)`）。
- 0.5.0 的 `MixinEntity.java` **没有** `move` 注入，只在 `collide` RETURN 追加、`turn`/`remove` HEAD 各一处（见 §5.7）。

### 2.2 新增 5 个类（照抄，逐文件对齐）

| 新类（我方要建） | 参考 · 锚点 @ 行号 | 职责 |
|---|---|---|
| `PhysicalEntityCollision` | `decompiled-space/0.5.0/.../physical/entity/PhysicalEntityCollision.java` · `resolve` @ 34 | 分段 OBB 扫掠解析：`segmentCount` @ 106（每段 ≤2 格、上限 128）→ 每段 `sweepAndSlide` @ 111（≤4 次滑动迭代）→ `findEarliestHit` @ 143（逐体素方块扫）→ `sweepBoxAgainstBlock` @ 184（SAT 15 分离轴） |
| `PhysicalEntityCollider` | 同上 · `PhysicalEntityCollider.java` · `at` @ 49 | 玩家碰撞形状 `EntityShape`：pivot 在眼高 @ 52、offset 使盒底在脚底 @ 53、可旋转、默认直立 |
| `PhysicalEntityProbe` | 同上 · `PhysicalEntityProbe.java` · `class` @ 10 | 独立 DYNAMIC 探针刚体（质量 50 @ 26–28），测"被移动的船带着走"的载速；`velocity()` @ 86 |
| `PhysicalEntityColliderAccess` | 同上 · `PhysicalEntityColliderAccess.java` | 接口：`space$getPhysicalEntityCollider` / `space$getPhysicalEntityProbe` |
| `MixinEntity`（我方对应 mixin） | `decompiled-space/0.5.0/.../mixin/MixinEntity.java` · `space$collideWithPhysicalBodies` @ 53 | `collide` RETURN 注入 `PhysicalEntityCollision.resolve`；`space$addProbeVelocity` @ 69（载速 `/20` 加到 movement） |

### 2.3 删除 / 退场

- `PlayerPhysicsBody` 双刚体在碰撞链退场（0.5.0 全仓库无 `new PhysicalEntity` 调用；`PhysicalEntity.java` @ 11 是残留死类）。
- `EntityPhysicsDriveMixin` 的 HEAD cancel 撤销；`PhysicsAabbSolver` 死代码可删。
- 服务端 `EntityPhysicsMoveMixin` 的语义需复核（见 §6 风险 R2）。

---

## 3. 旧结构两症状的根因（验证后的精确表述）

- **穿模**：`PlayerPhysicsBody` 双刚体 `CCD=false`（@ 178–179）+ 100Hz 定步 + 高速单子步位移 > 碰撞体厚度 → 隧穿。原版靠"同一步内把位置夹到表面"不穿透，HEAD cancel 把这一层整个跳过了（§23 已记录 `own·dt 是注入量不是刚度` 这条）。
- **卡住**：穿进去后求解器把主刚体顶在接触面，速度链 `main=sibling+own` 里 `sibling≈−own` 对消 → 玩家速度归零、出不来。这与 §23「兄弟体是"对消伙伴"」是同一个结构，只是从"贴墙稳态"恶化成"嵌进体内被锁死"。

---

## 4. 改动点映射表（★ 核心交付）

> 类型：**删除**（拆掉旧链）/ **改造**（同文件换结构）/ **新增**（照抄新建）。

| # | 我方文件 · 锚点 @ 行号 | 对应 0.5.0 参考 · 锚点 | 类型 | 说明 |
|---|---|---|---|---|
| 1 | `mixin/EntityPhysicsDriveMixin.java` · `move HEAD cancel` @ 66 | `0.5.0/.../mixin/MixinEntity.java`（**无 move 注入**） | **删除** | 撤销 HEAD cancel，玩家移动交回原版 `move+collide` |
| 2 | `client/physics/ClientPhysics.java` · `drive` @ 619 | `0.5.0/.../physical/entity/PhysicalEntityCollision.java` · `resolve` @ 34 | **改造** | `drive` 的"喂位移给双刚体→读回"整段退场，改由 `collide` RETURN 走 Java 扫掠 |
| 3 | `physics/PlayerPhysicsBody.java` · `class` @ 39 | `0.5.0/.../physical/entity/PhysicalEntity.java`（死类，无调用） | **删除**（退场） | 双刚体 + `velocityChain` @ 352 整链退场 |
| 4 | `client/physics/ClientPhysics.java` · `syncShips` @ 1178 / `stepShipSync` @ 288 | `0.5.0/.../physical/physical_body/ClientPhysicalBody.java`（镜像体） | **改造（保留）** | 镜像体 KINEMATIC 推进保留——扫掠的 `BodySnapshot` 读它的位姿/AABB |
| 5 | `client/physics/ClientPhysics.java` · `updateTerrain` @ 968 | 0.5.0 无对应（原版地形在原版世界，不镜像） | **复核** | 原版 move 恢复后，地形碰撞体与 Rapier 世界的关系需重审（见 R3） |
| 6 | —（无对应文件） | `0.5.0/.../physical/entity/PhysicalEntityCollision.java`（359 行） | **新增** | 照抄扫掠解析器（分段 + sweep-and-slide + SAT 15 轴） |
| 7 | —（无对应文件） | `0.5.0/.../physical/entity/PhysicalEntityCollider.java`（129 行） | **新增** | 照抄 `EntityShape`（眼高 pivot / 盒底 offset / 旋转） |
| 8 | —（无对应文件） | `0.5.0/.../physical/entity/PhysicalEntityProbe.java`（111 行） | **新增** | 照抄探针刚体（替代旧 sibling 测载速） |
| 9 | —（无对应文件） | `0.5.0/.../physical/entity/PhysicalEntityColliderAccess.java`（7 行） | **新增** | 照抄访问接口 |
| 10 | `mixin/` 需新增玩家碰撞 mixin | `0.5.0/.../mixin/MixinEntity.java`（128 行） | **新增** | `collide` RETURN 注入 + 探针同步 + `turn`/`remove` HEAD |
| 11 | `physics/PhysicsAabbSolver.java` @ 14 | 无对应（0.5.0 是 `PhysicalEntityCollision`） | **删除** | 79 行死代码，扫掠器已取代其定位 |
| 12 | `mixin/EntityPhysicsMoveMixin.java` @ 22 | 0.5.0 服务端无玩家刚体（`PhysicalEntity` 只在 `LocalPlayer` 建） | **复核** | 服务端 move cancel 语义要跟 0.5.0 对齐（见 R2） |

> 注：`SpaceViewRotation`（0.5.0 `MixinEntity` 里用于 6DOF 旋转的依赖）是我方 `space` 层的等价物，映射时需确认我方旋转基底的注入点，不在本表 1–12 之外另起炉灶。

---

## 5. lead 结论逐条验证（含行号）

### 5.1 「EntityPhysicsDriveMixin 在 Entity.move HEAD cancel 转 ClientPhysics.drive」 — ✅ 通过（表述需澄清）

- `EntityPhysicsDriveMixin.java` @ 66：`@Inject(method="move", at=@At("HEAD"), cancellable=true)`，@ 78–79 `if (PhysicsClientHooks.tryDrive(...)) ci.cancel();`。
- **澄清**：不是**直接**调 `ClientPhysics.drive`，而是经 `PhysicsClientHooks.movementDriver`（@ 49）间接转发，装载点 `PhysicsBodyRenderer.java` @ 413–415。这是 dist-safe 桥，结论方向不变，只是调用链多一跳。

### 5.2 「drive() 把位移交给玩家双刚体，位置从刚体读回」 — ✅ 通过

- `ClientPhysics.java` · `drive` @ 619：`playerRig.move(movement.x,y,z)` @ 666 → `bodyReadTranslation` @ 669 → `player.setPos(pos[0], pos[1]-playerCenterOffset, pos[2])` @ 687。

### 5.3 「PlayerPhysicsBody 主 DYNAMIC+兄弟 DYNAMIC，质量 25×2，锁旋转，CCD 关，gravity_scale=0，摩擦 20」 — ✅ 通过

- `BODY_MASS=25.0` @ 44；`BODY_DYNAMIC` 建两个 @ 169–170；`lockRotations(true)` @ 175–176；`bodyEnableCcd(false)` @ 178–179；`bodySetGravityScale(0.0)` @ 183–186；`PLAYER_FRICTION=20.0`（`PhysicsMaterials.java` @ 50，`attachColliders` @ 199/202 套用）。

### 5.4 「速度链 afterStep：main=sibling+own；sibling=own；sibling.pos=main.pos」 — ✅ 通过

- `velocityChain` @ 352：`main=sibling+own` @ 375–377；`sibling=own` @ 398；`sibling.pos=main.pos` @ 400–403。与 space 0.1.3 `PhysicalEntity.afterStep` 逐行一致（§21 @ 1057 已交叉验证过）。

### 5.5 「物理体镜像 syncShips 建 KINEMATIC_POSITION ShipBody，stepShipSync 每子步 lerp/slerp」 — ✅ 通过

- `syncShips` @ 1178：`BODY_KINEMATIC_POSITION` @ 1189；`stepShipSync` @ 288：位置 lerp @ 300–303 + 姿态 slerp（含 dot<0 翻半球）@ 306–315。

### 5.6 「地形 updateTerrain 体素 FIXED」 — ✅ 通过

- `updateTerrain` @ 968 → `buildTerrainChunk` @ 1043 → `BODY_FIXED` @ 1101；深空守卫 @ 976。

### 5.7 「0.5.0 MixinEntity.collide 在 RETURN 注入 PhysicalEntityCollision.resolve；PhysicalEntityProbe 测载速加到 movement；0.5.0 全仓库无 new PhysicalEntity 调用」 — ✅ 通过

- `0.5.0 MixinEntity.java` · `space$collideWithPhysicalBodies` @ 53（`@Inject collide RETURN, cancellable`）→ @ 63 `PhysicalEntityCollision.resolve(player, resolved, rotation)`；`space$addProbeVelocity` @ 69–72：`movement.x + measured.x/20.0`（三轴）。
- `grep 'new PhysicalEntity' decompiled-space/0.5.0`：**零命中**（仅 `new PhysicalEntityCollision.SweepHit/BodySnapshot`、`new PhysicalEntityCollider.Bounds/EntityShape`、`new PhysicalEntityProbe`——这些是内部 record/类，不是 `PhysicalEntity`）。
- `PhysicalEntity.java`（0.5.0，212 行）仍在：mass 25 @ 40/56、friction 20 @ 87/96、`setCCD(false)` @ 50/66——即旧双刚体的**尸体**，无人实例化。

### 5.8 「服务端 PhysicsBodyTracker + ProjectionManager + PhysicsWorldManager」 — ✅ 通过

- `PhysicsBodyTracker.java` @ 42（1813 行，DYNAMIC 体管）；`PhysicsWorldManager.java` @ 22（每维度世界，`WORLDS` @ 28，重力按 `PlanetDimensions` @ 44）；`physics/ProjectionManager.java` @ 41（948 行，`projection_world` @ 44）。

### 5.9 「PhysicsAabbSolver 死代码 79 行无调用者」 — ✅ 通过

- `grep 'PhysicsAabbSolver' src/main/java`：仅命中其自身 `class` @ 14 与私有构造器 @ 16。无调用者。

---

## 6. 更正与未知点（必须让 architect/实现者看到）

### 6.1 更正（lead 结论里的表述精度问题）

- **C1**：任务简报写「docs/mps-clone-plan.md 第 21/22/23 节 = 玩家被弹飞/穿模/抽搐」。实测 §21=**被弹飞** @ 1041、§22=**真根因（回灌 `+0.08` vs `getGravity()` 不匹配）** @ 1126、§23=**抽搐** @ 1245。「穿模」**不是**历史 §21–23 的独立章节，历史三节定论的根因分别是回灌 bug 和帧写回，**与本次"CCD 关→隧穿穿模"是不同根因**。本次升级要治的"穿进船 + 卡住"是**新**根因，不能沿用 §21/22/23 的修法。
- **C2**：`mps` 克隆层"空壳"只对 **MPS 物理体/碰撞**那一半成立；`mps.kelvin.*`（天体/太空世界）是**在跑的活代码**（被 `PlanetDimensions`/`SpaceWorld`/`SpaceRenderer` 引用）。改代码别把 kelvin 当死代码删。
- **C3**：`drive` 的调用链是 `mixin → PhysicsClientHooks.movementDriver → ClientPhysics.drive` 的**间接桥**，不是直接调用（见 §5.1）。

### 6.2 未知点 / 风险（需 architect 拍板）

- **R1（依赖面）**：`PhysicalEntityCollision` 依赖 0.5.0 的 `PhysicalWorld`/`ClientPhysicalWorld.getAllPhysicalBody()` 与 `PhysicalBody`（含 `worldAABB`、`getBlockStateInt`、`getPos`/`getRotation`）。我方体数据源是 `ClientPhysicsWorld.ClientBody`（`blocks()` 是 `List<BlockEntry>`，无直接 `getBlockStateInt`/旋转 AABB）。**扫掠器的 `BodySnapshot` 需要适配我方数据源**——这是"照抄"与"我方既存数据结构"之间最硬的一处接缝，实现前必须定清楚。
- **R2（服务端 move cancel）**：0.5.0 服务端玩家**没有刚体**（`PhysicalEntity` 只在 `LocalPlayer` 建）。我方 `EntityPhysicsMoveMixin` @ 22 目前对 `ServerLevel` + `PhysicsEntityManager.isAttached` 的实体取消 move——升级后这段语义是否还成立需复核。
- **R3（地形世界）**：0.5.0 里原版地形在原版世界天然存在，**没有**"把地形体素镜像进 Rapier"这回事。我方 `updateTerrain` @ 968 是为"双刚体权威"服务的；交回原版 move 后，Rapier 世界只剩**镜像体（船）+ 探针刚体**两种东西，`terrainBodies` 与 `BODY_FIXED` 地形是否还需要保留/如何裁掉是设计点。
- **R4（旋转基底）**：0.5.0 `MixinEntity` @ 62 依赖 `SpaceViewRotation.isActive/orientation` 决定 `rotation` 参数。我方旋转基底是 `space` 层自己的东西，映射时需确认等价注入点。
- **R5（原生层能力）**：`PhysicalEntityProbe` 用到的碰撞组 `(2,5)`、`setCCD`、`setFrictionCombineRule` 等我方 `NativePhysics` 是否都有对应接口，需对照 `native/polymech-physics/src/lib.rs` 逐项核，缺则补原生（纪律 3）。

---

## 7. 验证方式偏好（交付给 QA 的约束）

- 离线探针优先：`native/jni-smoketest/PlayerWallProbeTest`（只依赖 `NativePhysics`，不开游戏）是 §21/22/23 历史结论的判据载体。本次升级的"扫掠解析器"（`PhysicalEntityCollision`）应**同样**做离线探针：喂入"玩家盒 + 旋转体体素方块 + 位移"，断言输出 = 期望的滑动后位移，自动 PASS/FAIL。
- 不要求用户肉眼判断"能不能穿进去"。
