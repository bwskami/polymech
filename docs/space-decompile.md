# space 模组逆向源码（按版本分目录）

`decompiled-space/` **不进版本库**（见 `.gitignore` 的 `/decompiled-space/`），
只在本机作为对照参考。这份文档进版本库，用来固定"怎么分版本、怎么加新版本"的约定。

## 目录约定

```
decompiled-space/
  0.1.0/     ← space 0.1.0（minecraft [1.21.1]，neoforge [21.1.223,)）
  0.1.3/     ← space 0.1.3（minecraft [1.21.1]，neoforge [21.1.223,)）
```

每个版本目录下都是反编译后的完整 jar 内容：
`org/`（类）、`assets/`、`data/`、`META-INF/`、`natives/`，以及四个 mixin 配置
（`space.mixins.json`、`space.kelvin.mixins.json`、`space.mps.mixins.json`、`space.sunshine.mixins.json`）。

**为什么必须分版本**：我们的不少实现是"照 space 对照着做"的，而 space 各版本差异不小
（0.1.3 比 0.1.0 多 53 个类）。混在一个目录里就分不清"这条结论出自哪一版"。
**代码注释与文档里引用 space 时请写明版本**，例如
"space 0.1.3 的 `ProjectionManager`"，而不是笼统的"space 模组"。

## 新增一个版本

1. **先确认目标 MC 版本**：读 jar 里 `META-INF/neoforge.mods.toml` 的
   `minecraft` 依赖 `versionRange`。本项目对应 1.21.1，不匹配的版本不要放进对照库。
2. **反编译**（Vineflower；JDK 21+ 均可，本机用的是 JDK 25）：

```powershell
$vf = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.vineflower\vineflower" -Recurse -Filter 'vineflower-*.jar' | Select-Object -First 1).FullName
java -jar $vf -dgs=1 '<jar 路径>' 'decompiled-space\<版本号>'
```

   `-dgs=1` = 反编译泛型签名（MC 代码里泛型很重，不开会大量丢类型）。
3. **核对产物**：`decompiled-space/<版本号>/META-INF/neoforge.mods.toml` 里的
   `version=` 必须等于版本号，否则说明目录名写错了。

## 版本核对：space 0.1.3 的原生层与我们同代（Rapier 0.34 / parry 0.29）

`space-0.1.3.jar` 里的 `natives/mps_rigid_body.dll`（3 MB）**vendor 了 Rapier**
（源码路径形如 `rapier\crates\rapier3d-f64\..\..\src\...`），所以 dll 里**没有**
`rapier3d-f64-0.34.0` 这类注册表版本串，读不出直接版本号。改用三条间接证据：

| 证据 | 结果 |
|---|---|
| dll 里嵌的 12 条 Rapier/parry 源码路径 | **全部**存在于本机 `rapier3d-f64-0.34.0` / `parry3d-f64-0.29.0` |
| dll 里嵌的 6 条 panic 文案（如 `Cannot compute the center of less than 1 point.`） | **逐字**出现在上述版本的对应文件里（`center.rs` / `shape.rs` / `point_tetrahedron.rs` / `index_mut2.rs` …） |
| 依赖指纹 | 一致：`nalgebra 0.35.0`、`ena 0.14.4`、`jni 0.21.1`（`glam`/`hashbrown`/`smallvec` 不同，来自他们额外的 `glamx`/`rayon`/更新的 `indexmap`，与 Rapier 无关） |

**结论**：0.1.3 与我们**同一代 Rapier**，因此它的 JNI 级技术（碰撞组、关节、传感器…）
可以 1:1 映射到我们这边；也意味着 `InteractionGroups::new` 的三参签名（`InteractionTestMode`）对它同样适用。

### 另一件重要的事：他们的原生层不是"薄封装"

我们的 `polymech_physics` 是 JNI 薄封装；他们的是一整个仿真 crate `mps-core`：

```
crates\mps-core\src\rapier\{
    collider, voxel, terrain_gravity, interaction, batch, forces,
    fracture, bridge, cross_validate, shared_arena/*        }
```

从 panic 文案里还能看到 **DEM（离散元）参数**、**断裂力学**（S-N 曲线、Griffith 断裂能、
fracture fragments/joints）、以及 `rayon` 并行。也就是说：**继续对标 space，深度在原生层**，
远不止"刚体 + 体素碰撞"。

## 当前参考点

| 版本 | .java 类数 | 备注 |
|---|---|---|
| 0.1.0 | 263 | 早期对照版本（MPS 物理 / Kelvin 天体 / Sunshine 渲染 三套） |
| 0.1.3 | 316 | 当前最新；新增内容尚未系统对照 |

## 已知差异（边对照边记，避免重复结论）

- **投影里产生的掉落物**：0.1.0 与 0.1.3 **都没有处理**（`org/polaris2023/mps` 下搜不到
  `ItemEntity` / `addFreshEntity` / `popResource`）。本项目自己实现了"每 tick 扫实体 + 搬到世界侧"，
  这一块是**我们领先**，别再回 space 里找答案。
  覆盖物品 + **经验球**（经验球走 `ExperienceOrb`，不经 `popResource`，所以单独扫一遍；
  搬运用 `Entity#teleportTo(ServerLevel,…)` 整球搬走 —— 跨维度会走 NBT 往返，`Value`/`Count` 都保得住，
  换成"读值→销毁→`award` 重发"会把合并过的球（`count > 1`）只搬走一份）。
- **破坏给经验**：NeoForge 1.21 把经验算在 `BlockDropsEvent` 里
  （`EnchantmentHelper.processBlockExperience(level, tool, state.getExpDrop(…))`），
  而那条链路挂在 `Block#dropResources` 上。物理体破坏为了搬掉落物绕过了它 ——
  所以原先"挖矿有掉落、却永远不给经验"。现在按同一公式补算，并在世界侧与掉落物同一处 `ExperienceOrb#award`。
- **破坏进度同步**：0.1.3 新增了 `SyncPhysicalBlockBreakProgress`（0.1.0 没有）——
  **已照它实现**。要点：原版的挖掘时间由 `Minecraft.continueAttack → continueDestroyBlock` 驱动，
  而那条路要求"原版射线命中真实方块"（物理体那里是空气），整条断掉；所以：
  客户端按住左键时**每 tick** 发一次破坏包（`ClientTickEvent.Post` + `keyAttack.isDown()`，
  照 space 的 `onClientTick`），服务端在投影维度累积 `state.getDestroyProgress(...)`，
  累满 1.0 才真破坏；停手 / 换目标 / 超过 2 tick 重置。进度广播给**全维度**
  （`PhysicsBodyBreakProgressPacket`，`-1` = 清除），客户端按格子存下来画裂纹
  （`SheetedDecalTextureGenerator` + `ModelBakery.DESTROY_TYPES`），
  破坏瞬间改用 `levelEvent(2001)` 出粒子 + 音效。
  **注意**：松手时没有包，所以服务端每 tick 扫一遍超时条目并广播清除 —— 少了这步裂纹会永久卡住。

## 0.1.0 → 0.1.3 系统对照结论（70 新增 / 17 移除）

MPS 物理核心新增 7 个类；下面按"对本项目的价值"排序。

### ⭐⭐⭐ 玩家物理：双刚体 + 碰撞组 + 从不旋转（`physical/entity/PhysicalEntity`，0.1.3 重写）

- **两个刚体**：主刚体 + "兄弟"刚体（`siblingBody`），各挂一个**同位置同尺寸**的 CUBOID。
- **碰撞箱从不旋转**：`move()` 里每次都 `setRotation(单位四元数)` + `setAngvel(0)` ——
  对比本项目"用角速度伺服让碰撞体跟着 6DOF 身体转"，这条路把旋转问题**整类消掉**了。
- **速度模型**：`sibling.linvel = ownVelocity`（玩家输入，每步重置）；`main.linvel = sibling.linvel + ownVelocity`
  —— 用"载速体 / 惯性体"分离，既保留惯性又能被船推动。
- **`collisionGroups(2,5)` / `(5,5)`**：已移植到本项目原生层（ABI 4，见下）。
  这是解锁"玩家不撞自己的刚体 / 传感器 / 单向碰撞"的钥匙。
- **摩擦 20.0**（`frictionCombineRule(0)`）+ 质量 25×2；本项目是**摩擦 0.6**、质量 50×1 —— 差距很大，值得单独核对手感。

### ⭐⭐⭐ 可靠创建握手（`network/ReliableCreateSender` + `SyncCreateAck` / `SyncCreateEnd`）

CREATE 带自增 id → 客户端 ACK → **40 tick 未 ACK 就重发**（普通 5 次；`sendCritical` 10 次、失败踢人）。
本项目现在是 `PhysicsBodyEvents` 里"换维度后延迟 2 秒补发"的权宜做法，
而 `ClientPhysicsWorld.acceptBlockEntities` 里已经踩到过"CREATE 还没到" —— 这就是正解。

> **已落地**：`PhysicsBodyTracker` 的 `PENDING_ACKS` + `network/SyncPhysicsBodyAckPacket`，
> 40 tick 重发 ×5，与 space 的普通包一致。

### ⭐⭐⭐ 批量移动同步（`network/packet/SyncPhysicalBodyMoveBatch`）

每 tick 把**所有**刚体的 pos + rot + linvel + angvel 打进**一个包**（列表）。
本项目现在每体每 tick 发一个 `PhysicsBodySyncPacket`。0.1.3 把旧的 `SyncPhysicalBodyMove` 删掉了，
天体同步也同样批量化（`kelvin` 的 `SyncCelestialBodyMoveBatch`）。

> **已落地**：`network/PhysicsBodyMoveBatchPacket`，`PhysicsBodyTracker.tick()` 按维度攒批，
> 包数从 N/tick 降到 1/tick/维度。

### ⭐⭐ 关节（`rapier/helper/JointBody`）

FIXED / REVOLUTE / PRISMATIC / ROPE / SPRING / SPHERICAL，含 `setLimits` / `setMotorVelocity` /
`setMotorPosition`（马达）/ `setContactsEnabled`。本项目完全没有关节能力（起落架、对接、铰链、绳索都要它）。
JNI 面：`jointBuilderCreate` / `jointBuilderSetLocalAnchor1|2` / `jointBuilderSetLimits` /
`jointBuilderSetMotorVelocity` / `jointBuilderSetMotorPosition` / `jointBuilderSetContactsEnabled`。

### ⭐⭐ 其它

- `mixin/MixinClientChunkCache`：客户端**区块事件驱动**重建物理地形（`onChunkReceived` / `onChunkDropped`），
  本项目是在 `ClientPhysics.updateTerrain` 里每 tick 扫区块 —— 事件驱动更省。
- `client/PhysicalPlayerColliderRender`：用 `RenderType.lines` 画出玩家物理碰撞箱（调试用）。
  **注意**：它画的两个盒子是"同一个盒子挂在两个刚体上"，**不是**头+身的复合碰撞箱，别误读。
- `mixin/MixinLivingEntity`：`getDefaultGravity` 直接返回 0（比本项目按维度缩放 `Entity#getGravity` 更直接）。
- `physical_world/PhysicalBodyWorldData`：物理体存档（尚未细读，待对照本项目的 `PhysicsBodySavedData`）。

## 本项目已落地：碰撞组（ABI 4）

原生层新增两个函数，与不带组的版本并存（旧 ABI 库仍可加载）：

- `colliderAttachCuboidGrouped(world, body, hx, hy, hz, friction, restitution, membership, filter)`
- `colliderAttachVoxelsGrouped(world, body, sx, sy, sz, cells, friction, restitution, membership, filter)`

实现用 `InteractionGroups::new(Group::from_bits_truncate(mem), Group::from_bits_truncate(filter), InteractionTestMode::And)`
（Rapier 0.34 是**三参**，少了 `InteractionTestMode` 会编译失败）。

**判定是双向的**：A 与 B 交互 ⟺ `(A.mem & B.filter) != 0 && (B.mem & A.filter) != 0`，两边都要放行。

space 0.1.3 的组方案（照抄即可）：

| 角色 | membership | filter |
| --- | --- | --- |
| 地形 / A 类刚体 | 1 | -1 |
| B 类刚体 | 4 | -1 |
| 玩家主刚体 | 2 | 5 |
| 玩家兄弟刚体 | 5 | 5 |

Java 侧：`PhysicsNatives.hasCollisionGroups()` 按 `MIN_ABI_COLLISION_GROUPS = 4` 单点把关，
`EXPECTED_ABI` 保持 3（**最低要求**语义：原生更新可以直接换库，不必同步改 Java）。

### 调试命令

```
/polymech physics collisiongroups <id>              # 查当前组
/polymech physics collisiongroups <id> <mem> <fil>  # 设置并立即重建碰撞体
```

- `1 -1` = 默认（与所有组交互，等价于没设过）；`1 0` = 幽灵（filter 空集，谁都碰不到，人可以直接穿过去）。
- 组存在 `PhysicsBodyTracker.COLLISION_GROUPS`，破坏/放置方块触发的**每次碰撞体重建都会带上它**；
  物理体销毁（`drop` / `dropWithoutRestoring`）时清除 —— 否则 id 回收（取最小空闲号）会串到下一个体上。
- **写存档**（Tier 1 起）：存进 `PhysicsBodySavedData` 的 `Mem` / `Fil` 字段，
  重启后由 `PhysicsBodyTracker.restore` 用 `colliderAttachVoxelsGrouped` 还原
  （`GROUP_UNSET = -1` 等于 Rapier 默认，与"没设过"同义，所以老存档天然兼容）。

---

## space 0.1.3 物理体对照：Tier 1 已落地（原生 ABI 5）

> 结论日期：本轮对照后。**判据**：`decompiled-space/0.1.3` 的
> `physical/`、`rapier/`、`network/packet/`、`thread/` 全量读一遍，对上本项目
> `physics/` + `client/physics/` + `native/polymech-physics/src/lib.rs`。

### 一、已追平（**不要再重复实现**）

| 项 | space 0.1.3 | 我们的实现 |
| --- | --- | --- |
| 批量移动同步 | `SyncPhysicalBodyMoveBatch` | `PhysicsBodyMoveBatchPacket` |
| 可靠创建握手 | `ReliableCreateSender` + ACK | `SyncPhysicsBodyAckPacket` + `PENDING_ACKS` |
| 碰撞组 | `(1,-1)/(4,-1)/(2,5)/(5,5)` | `colliderAttach*Grouped`（ABI 4） |
| 破坏进度同步 | `SyncPhysicalBlockBreakProgress` | `PhysicsBodyBreakProgressPacket` |
| 方块实体同步 | `SyncPhysicalBodyBlockEntity` | `PhysicsBodyBlockEntityPacket` |
| 投影 + 地皮 | `ProjectionManager`（in-world 投影） | 投影维度 + 地皮槽位（**我们更干净**） |
| 物理体存档 | `PhysicalBodyWorldData` | `PhysicsBodySavedData` |
| 地形体素快照 | `PhysicalChunkManager` + `PhysicalChunk` | `PhysicsTerrain` + `ClientPhysics` 地形 |
| 100Hz 独立步进线程 | `Server/ClientCollisionPhysicalThread` | `PhysicsStepThread` |
| 投影掉落物 / 经验球 | **两版都没做** | **我们领先**，别回 space 找答案 |

### 二、本轮补齐（Tier 1，原生 ABI 5）

| # | 缺口 | space 的做法 | 我们的实现 |
| --- | --- | --- | --- |
| A1 | 维度无限地面 | `PhysicalWorld.setMinY`（非太空维度挂 HALFSPACE） | `NativePhysics.worldSetFloor` + `PhysicsWorldManager`（重力 > 0 的维度才挂） |
| A2 | 力矩 / 偏心受力 | `rigidBodyAddTorque` / `ApplyTorqueImpulse` / `AddForceAtPoint` | 同名 JNI（能力已就位，暂无玩法调用方） |
| A3 | 刚体属性 | `RigidBody` 构造器的 damping / gravity_scale / mass+惯量 / CCD / `SetEnabledRotations` | `bodySetDamping` / `bodySetGravityScale` / `bodySetAdditionalMassProperties` / `bodyEnableCcd` / `bodySetEnabledRotations` |
| A4 | 材质组合规则 + contact skin | `CONTACT_SKIN = 0.02`；玩家 friction 20 + `combineRule(0)` | `colliderSetMaterial` + `PhysicsMaterials`（全部碰撞体统一套用） |
| A5 | 运动学目标位姿 | `rigidBodySetNextKinematicPosition` | `bodySetNextKinematicTranslation/Rotation` |
| B1 | 部分方块碰撞形状 | 满形状走体素、非满走 `COMPLEX_VOXEL`（compound boxes） | `colliderAttachBoxes` + `PhysicsShapes`，服务端 `PhysicsTerrain` 与客户端 `ClientPhysics` 地形各接一次 |
| D3 | 存档带速度与碰撞组 | `ServerPhysicalBody.saveToTag` 存 linvel/angvel | `PhysicsBodySavedData.Entry` 增 `vx..avz` / `membership` / `filter`（老存档向后兼容） |

**A4 的一个刻意决定**：玩家摩擦**仍保持 0.6**，没抄 space 的 20.0。space 的 20 是配
"双刚体 + 碰撞箱从不旋转"调的；我们的碰撞箱还跟着 6DOF 身体转（角速度伺服），
照抄 20 会把身体粘在地形上、伺服转不动。见 `PhysicsMaterials.PLAYER_FRICTION` 的注释。

**验证方式**：`/polymech physics tier1test`（游戏内，逐项 ✔/✘），
或 `native/jni-smoketest/NativeSmokeTest.java`（脱离游戏，只验 JNI 绑定）。
两条路径都走 `NativePhysics.tier1Selftest()` 的同一个位掩码。

### 三、还没做（按价值排序，供后续挑）

| # | 缺口 | space 0.1.3 的做法 | 影响 |
| --- | --- | --- | --- |
| C1 | **关节** | `JointBody`：FIXED/REVOLUTE/PRISMATIC/ROPE/SPRING/SPHERICAL + `setLimits` + 马达 | 起落架/铰链/对接锁/绳索/悬挂/螺旋桨**全都缺** |
| C2 | **碰撞事件 + 传感器** | `ActiveEvents` / `ActiveHooks` / `worldGetCollisionEvent(s)` / `colliderSetSensor` | 撞击触发、接触伤害、区域判定 |
| B3 | 物理查询 | `worldCastRay`（带组过滤）/ `worldCastShape` | 我们只有 Java 侧对自家方块逐格 slab，没有 shapecast |
| D4 | 跨维度搬运 | `dimensionLeapPhysicalBody(...)` 带 pos/rot/linvel/angvel | 星球 ↔ 太空切换时船过不去 |
| E1 | 玩家双刚体 | `PhysicalEntity`：主+兄弟刚体、**碰撞箱从不旋转**、速度拆分、friction 20 | 把"旋转碰撞箱"整类问题从根上消掉；**必须实测手感** |
| F1 | 选择棒 | `PHYSICAL_SELECTION_WAND` 两点选择 + `SyncPhysicalSelection` + 客户端选框 | 我们只有 `/polymech physics grab` 命令 |
| D1 | 体量上限 | 128³（8×64³ paletted chunk） | 我们 `MAX_REGION=32` / `MAX_BLOCKS=4096` |
| D2 | 方块同步粒度 | 按 64³ 子区块 RLE 增量同步 | 我们破坏一格就整包重发 |
| F4 | 地形区块管理 | 独立 50ms 线程 + 兴趣点 + 增删迟滞 | 我们主线程每 tick + 固定半径 2 |
| F5 | 物理体 scale | `PhysicalBody.scale`（体素随之缩放） | 无 |
| F6 | HEIGHTMAP 碰撞体 | 用噪声生成器直接算高度（未加载区块也有地面） | 无 |

**D2 的一个现状**：`PhysicsBodyTracker.breakBlock/placeBlock` 之后走 `refresh()`，
把**整包方块列表**重发给全维度（照旧）。space 只发改动过的 64³ 子区块，是后续优化点。

### 四、关于"物理体自身的方块形状"

space 的**物理体**碰撞体是 `PalettedChunk.colliderArray()` → `boolean[]` → `Voxel`，
即**满格体素**（`COLLISION_CACHE` 只判 `getCollisionShape(...).isEmpty()`）；
**只有地形**（`PhysicalChunk`）才拆成"满块体素 + 非满块 compound boxes"。
所以 B1 只需改地形，物理体方块保持满格 —— 我们与它一致，别多改。

### 五、本轮修掉的两个"没法测试"的 bug

#### 5.1 「玩家一碰物理体，物理体就被撞飞」

**根因是质量标定**。Rapier 的体素碰撞体密度默认 `1.0`，于是"**一格方块 = 1 kg**"。
用 `bodyGetMass`（本轮新增，对标 space 的 `rigidBodyGetMass`）实测：

| 碰撞体 | 质量 |
| --- | --- |
| 1 格体素 | 1.0 kg |
| 1000 格体素 | 1000.0 kg |
| 玩家盒 0.6×1.8×0.6 | 0.648 kg（+ 附加 50 = **50.65 kg**） |

**50 kg 的玩家比几格方块的船重几十倍**，撞上去必然飞。实测量化（`NativePhysics` 直接跑）：

| 场景 | 修复前 | 修复后 |
| --- | --- | --- |
| 50 kg 玩家 5 m/s 撞 8 格（2×2×2）体 | 体 **4.59 m/s**，玩家 4.28 m/s | 体 **0.42 m/s**，玩家被挡停 |
| 单个推力包（600 kg 体） | 固定 24 N·s | 步进 0.8 m/s（按住约 1 秒到走路速度） |
| 连推 3 秒 | — | 稳定在 **4.0 m/s**（速度上限，不会无限加速） |

两处修复：

- `PhysicsBodyMass`：给物理体一个 **600 kg 质量下限**（Rapier 总质量 = 碰撞体质量 + 附加质量，
  缺的用附加质量补；**质心必须传结构包围盒中心**，传原点会让合成质心落到区域最小角 →
  船绕着角转）；
- `PhysicsBodyPushPacket`：从"固定 24 N·s"改成**速度目标型**（单包最多补 0.8 m/s、
  上限 4 m/s、再叠 3000 N·s 冲量天花板）。固定冲量的问题在于太空没有摩擦、
  速度只累加不衰减，客户端每 4 tick 一包 → 推两秒几十 m/s。

客户端镜像刚体（`ClientPhysics.syncShips`）必须用**同一套公式**，否则本地被推得更远、
然后周期性被位置修正拉回来（抖动）。

#### 5.2 「飞行中间隔几秒顿一下，类似抽帧」

已修 + 已装探针（还没最终定案）：

- **修**：原生层从"整个世界表一把全局 `Mutex`"改成 **per-world 锁**。
  单机下客户端与服务端各有一个物理世界，原先任何一方的长操作（建区块体素碰撞体、
  重建物理体碰撞体、一帧几十次读刚体）都会把另一方的 100Hz 步进线程卡住 ——
  模拟整段停住，画面上就是"顿一下"。
- **探针**：`PhysicsStepThread` 现在统计"步进间隔/耗时 ≥ 40ms"的次数与最长值，
  并限流告警（`/polymech physics status` 也能看到）；`PhysicsBodyTracker.rebuildCollider`
  耗时 ≥ 8ms 会告警（带方块数与当时速度）。
- **待查（下一个嫌疑，尚未修）**：`PhysicsBodyRenderer` 的烘焙缓存指纹是
  `ClientBody.revision()`，而**任何**方块改动（含方块实体包、`refresh()` 重发 CREATE）
  都会 `touch()` → **整条船几万个方块全部重新烘焙四边形**（模型查找/邻面剔除/染色）。
  若投影里机器/红石周期性改方块，就是"每隔几秒掉一次帧"。space 对应做法是
  `ClientPhysicalBody` **按 64³ 子区块各建一份顶点缓冲 + 视锥剔除**，只重烘改动的那块。
  另外 `refresh()` 目前是**整包方块列表重发**（space 是 `SyncPhysicalBodyBlockUpdate`
  按子区块 RLE 增量），这条同属 D2。

### 六、本轮修掉"站在船上的玩家把船推着走 / 诡异碰撞"（照 space 的玩家方案）

**症状**：站在物理体上的玩家会被"莫名其妙"推着走（方向就是玩家按键方向）；
其它玩家↔物理体的碰撞表现诡异。

**根因 1（我们自己的发明在误伤）**：客户端 `ClientPhysics.reportPushIfBlocked` 的判据是
"我想走、但实际水平速度不到期望的一半 ⇒ 我一定在推东西 ⇒ 把推力发给最近的物理体"。
玩家**站在正在移动的船上时相对速度本来就接近 0**，于是每按一次键就把推力发给脚下那条船、
方向就是按键方向 —— 船当然"往前走"。**space 0.1.3 里完全没有这个启发式**。
→ 已删除（`PhysicsBodyPushPacket` 保留，仅供将来做**显式**推拉键用）。

**根因 2（碰撞箱在转）**：我们的玩家碰撞盒跟着 6DOF 身体转（角速度伺服）。
旋转盒子的"角"会扎进地形/船体，解算器为了把角推出来的冲量方向很诡异 ——
这就是"贴着船/墙会突然被弹开、翻倒、莫名其妙位移"的来源。

**space 的玩家方案（0.1.3，`MixinEntity` + `PhysicalEntity`）**，三条关键设计：

| # | space 的做法 | 我们这轮 |
| --- | --- | --- |
| 1 | `move()` 每步 `setRotation(单位四元数)` + `setAngvel(0)` —— **碰撞箱从不旋转** | 直接 `bodyLockRotations(true)`（等效且更省），客户端 + 服务端都锁 |
| 2 | `Entity.move` 的 HEAD 里做**五点向下探地**（脚下中心 + 四角，0.1m，查询组 `(2,5)`）→ `setOnGround` / `verticalCollisionBelow` / `resetFallDistance` | 新增原生 `worldCastRay` + `PhysicsGroundProbe`，按同参数探地；**此前我们一次都没设过 onGround**（所以站着不能跳、摔落距离一直累加） |
| 3 | **双刚体**：主刚体 + 兄弟刚体（同位置同尺寸 CUBOID，组 `(2,5)`/`(5,5)` 互不作用）；`afterStep()` 里 `main.linvel = sibling.linvel + ownVelocity`、`sibling.linvel = ownVelocity`、`sibling.pos = main.pos`，**在 100Hz 步进循环里**（`RapierWorld` 的 tick listener）每步重设速度 | **尚未移植**（见下） |

**为什么"渲染姿态"不受影响**：`SpacePlayerData.bodyQuatForRender` 的注释早就写明
它走的是**运动学**姿态（"物理刚体的真实姿态只在步进线程 100Hz 更新，会暴露采样率"），
所以把碰撞箱锁成轴对齐**不会**动到可见的身体朝向。

**已移植（本轮）**：space 的**双刚体 + 速度继承**。
实现放在 `PlayerPhysicsBody`（客户端/服务端共用），速度继承链由 `PhysicsStepThread`
的"步进后回调"（对应 space 的 `RapierWorld` tick listener）**每个 100Hz 子步**驱动。
参数照抄：每体 25 kg、组 `(2,5)`/`(5,5)`、锁旋转、CCD 关、摩擦 20、
contactSkin 0.02、弹性组合规则 Min。

**与 space 的对照（逐条照抄，不再自行发挥）**：

| space 的步骤 | 我们的位置 |
| --- | --- |
| 两个 25 kg 动态刚体 + `lockRotations` + CCD 关 + **`gravity_scale = 0`** | `PlayerPhysicsBody.create()`（`bodySetGravityScale(0)`，**本轮补上**） |
| 主 (2,5) / 兄弟 (5,5) 两个同位置同尺寸 CUBOID | `attachColliders()` |
| friction 20 / contactSkin 0.02 / restitution rule Min | `PhysicsMaterials.applyPlayer()` |
| `move()`：`ownVelocity = delta × 20`（+ 每步 `setRotation(单位四元数)`、`setAngvel(0)`） | `move()`；旋转已在建体时 `lockRotations(true)`，那两句是等价冗余 |
| `afterStep()`：`main = sibling + own`（**原样相加**）；写 main 前判 `delta² > 1e-8`；**`sibling = own`（每子步清零）**；`sibling.pos = main.pos` | `velocityChain()`，逐字一致（**本轮修正**：删掉我们自加的"接触增益"与 `MAX_SPEED` 夹持，补上 `delta²` 写入判据 —— 见下） |
| 在 100Hz 步进循环里跑 `afterStep` | `PhysicsStepThread` 的**步进后回调**（= space 的 `RapierWorld` tick listener） |
| `MixinEntity`：五点向下 0.1m 探地 → `setOnGround` / `verticalCollisionBelow` / `resetFallDistance` | `PhysicsGroundProbe` + `worldCastRay` |
| `MixinEntity`：着地时 `setDeltaMovement(movement.x, movement.y + 0.08, movement.z)` 回灌 vanilla | `ClientPhysics.drive()` 末尾回灌，但垂直分量给 `min(0, delta.y)`（两边拿到的 delta 不同，见该类注释） |
| `MixinEntity.getGravity`：太空 0 / 天体 `G/122.5` | `MixinEntity.polymech$gravity` 按维度倍率缩放；**物理玩家也走这条**（本轮删掉了"驱动中清零"） |

**本轮修正（"撞到普通方块被弹开 / 反重力"）**：`velocityChain()` 此前多了两样 space 没有的东西 ——

1. **"接触增益"**：被挡住时 `own` 被整个抹掉（`gain → 0`），于是 `main = sibling + 0`，
   解算器给兄弟刚体的**反弹速度原封不动**落到玩家身上。space 的 `main = sibling + own`
   会让输入速度去**顶掉**那个反弹分量（撞墙时 `own` 与兄弟的反弹同轴反号），这才是它不弹人的原因。
2. **`MAX_SPEED = 8` 夹持**：同样是本地发明，对"恒定的反作用偏置"无效（夹持只削幅值）。

同时补上 space 的写入判据 `delta.lengthSquared() > 1.0E-8` —— 此前每子步无条件 `wake=true`，
静置刚体永不休眠，求解器每子步都解它，接触噪声被反复喂回速度链。

重力也改成**单来源**（先前的组合两头都不对）：玩家刚体 `gravity_scale = 0`（Rapier 不施加），
原版 `getGravity()` 不再对物理玩家清零，按维度倍率缩放后经 `own = 位移 × 20` 进入物理。
清零那一个会让行星上的物理玩家浮起来（Rapier 的重力又会被速度链每子步抹掉，等于没有重力）。

**原生层是否需要扩展？结论：双刚体本身不需要**（两个刚体 + 分组碰撞体 + 锁旋转 +
速度读写 + 重力缩放，ABI 4/5 早就有了）。当初真正缺、也确实补了的只有
`worldCastRay`（探地用的 Rapier 射线查询）与 `PhysicsDrivenPlayers`（"谁被物理接管"的共享登记，
现在只作诊断/状态用，不再驱动重力清零）。
唯一与 space 不同的小地方是**碰撞体局部偏移**（space 的 `ColliderBody` 带 translation，
把刚体原点放在脚底；我们把刚体放在碰撞箱中心）—— 两者代数等价，无需改原生。

**已知取舍（实测，不是猜测）**：`sibling = own` 每子步清零，使"载速"被限制在单个子步的
摩擦预算 μ·g·dt 内 —— 5 m/s 的传送带上，玩家只能到约 1.0–1.7 m/s，会被落下。
**但这一句不能删**：兄弟刚体每子步都被搬回主刚体位置，搬动会产生穿透冲量；
不清零的话这些噪声会经 `main = sibling + own` 加到玩家身上（实测：不清零虽然能被带到
5 m/s，但站着不动时速度会漂 —— 也就是"移动发飘/莫名加速"）。
要做"完全同步且无噪声的载速"，得另想办法（例如另存一份载速、不动兄弟刚体的速度），
**不要**靠删掉这句。

**回归测试**（`native/jni-smoketest/NativeSmokeTest.java` 的 `smokePlayerRig()`，脱离游戏）：

```
ok: 玩家站在静止船上不推船（船速 0.0000 m/s）
ok: 站着不动不被污染（速度 0.0000/0.0000/0.0000）
ok: 站着不动不下沉（y=2.900 ≈ 2.9）
```

**顺带删掉的**：原"位移 → 冲量"的 MPS 力模型（速度只加不减，零重力下按 W 不放就能
把自己变成炮弹）。现在玩家速度由原版移动学决定，Rapier 只负责碰撞与"被带走"。

#### 6.1 二次修正：「还是被撞飞」——三处权威打架，这次是结构性重写

第一轮只删掉 `velocityChain()` 里那两处本地发明（接触增益 / `MAX_SPEED`），结果**更糟**
（一碰就飞）。说明弹飞的根源不在速度链本身，而在"**谁有权改玩家位置 / 谁在积分**"这一层。
逐条对照后重写了三处：

| # | 我们原来的结构 | space 0.1.3 的结构 | 为什么原来的会弹飞 |
| --- | --- | --- | --- |
| 1 | `EntityPhysicsDriveMixin` 重定向 `Entity.move` 里的 `setPos`（ordinal 1）：原版 `collide()` 仍照跑，喂给物理的是**碰撞后**的 `vec3` | `MixinEntity.space$moveWithRapier`：在 `Entity.move` **HEAD 直接 `cancel`**，喂进去的是**碰撞前**的位移 | 原版碰撞与 Rapier 都在算同一次移动；`own` 又是被原版夹过的 `vec3`，输入意图已丢。回灌的 `+0.08` 也因此没东西可抵消（当时拿 `min(0, delta.y)` 打补丁 —— 属于"配套的两个半句一起改坏"） |
| 2 | `ServerPlayerPhysics` + `ServerMoveTrustMixin`：**服务端也跑一套双刚体**，`writeBackAll()` 把服务端刚体写回玩家、`snapTo` 又对齐到上报位置 | 服务端玩家**完全没有刚体**。唯一的服务端改动是 `MixinServerGamePacketListenerImpl` 把 `clampHorizontal/Vertical` 改成恒等（我们的 `ServerPacketClampMixin` 早已一致） | 两端各积分一份再互相拽 —— 一个**持续回弹**的闭环 |
| 3 | `ClientPhysics.syncShips` 把客户端镜像体建成 **`BODY_DYNAMIC`**（注释写着"照 MPS"——**误读**），配 600 kg 质量下限 +"偏差 > 1 格才硬拉"的位置修正 | `ClientPhysicalBody` 构造是 `super(level, pos, rotation, uuid, **kinematic = true**)` → `KINEMATIC_POSITION`，位姿经 `setNextKinematicPosition(lerp 0.2/子步)` 推进 | 动态镜像体有有限质量，玩家一顶就在本地被推动，随后被同步/位置修正拽回 —— **那股"拽回"就是把玩家弹飞的冲量**。`MAX_SPEED` 只是在掩盖它 |

重写后：

- `EntityPhysicsDriveMixin`：`Entity.move` HEAD → `PhysicsClientHooks.tryDrive` → `cancel`。
  原版碰撞**不再参与玩家位置**，世界里只剩 Rapier 一套权威。
- `ClientPhysics.drive()`：探地用**刚体**位置（碰撞箱底面）→ `rig.move(碰撞前位移)` →
  从刚体读回位置 → 贴地时 `setDeltaMovement(movement.x, movement.y + 0.08, movement.z)`。顺序与公式全部照 space。
- **删除** `ServerPlayerPhysics` / `ServerMoveTrustMixin`（连同 `writeBackAll` 调用、`mixins.json` 条目、
  `PhysicsBodyEvents` 里的 forget 调用）。服务端回到"原版移动 + 恒等夹持"，与 space 一致。
- 客户端镜像体改为 `KINEMATIC_POSITION` + 每子步 `setNextKinematicTranslation(lerp 0.2)`
  （`stepShipSync`，挂在 `PhysicsStepThread` 的步后回调）+ 碰撞组 `(4,-1)`。
  删除 `POSITION_CORRECTION` / `MOTION_DEADBAND` / `sameMotion` / `motionOf` / `localCenter`
  与客户端质量标定 —— 运动学体没有质量，那一整类补丁随之作废。
- `PlayerPhysicsBody.move()` 补上 space 的 `setRotation(单位四元数)` + `setAngvel(0)`（锁旋转下冗余，照抄）。

**一处刻意保留的补偿（space 没有）**：HEAD 取消会一并跳过 vanilla 在 `Entity.move` 末尾累加的
`walkDist` / `moveDist`（前者驱动走路颠簸与手臂摆动，后者是脚步声节拍）。`drive()` 里按
"物理真正应用的位移"用同一公式（距离 × 0.6）补上。这是**动画/音效账，不是物理量**；不补就会"画面不再起伏"。

**仍未与 space 一致的已知点**：玩家刚体原点仍取碰撞箱中心（space 取脚底、`ColliderBody` 带
`(0, halfHeight, 0)` 局部偏移）。对**轴对齐盒**两者代数等价（且旋转已锁），故保留不动。
另外写回仍保留"每 tick / 每帧"两道（space 只在 `move` 里写一次）：它们写的是同一个刚体位置，
并用 `droveThisTick` 门控（本 tick 没真接管就不碰位置），因此不改变受力，只为渲染平滑。

### 七、还没做（原第三节，补两条）

| # | 缺口 | 说明 |
| --- | --- | --- |
| F7 | 渲染按子区块烘焙 + 增量方块同步 | 见 5.2 的"待查"：整包重发 + 整体重烘 = 周期性掉帧 |
| F8 | 物理体速度上限 | 目前只有玩家推力有上限；入射碰撞仍可给任意速度（质量下限已把量级压住） |

