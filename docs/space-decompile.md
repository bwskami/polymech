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

### ⭐⭐⭐ 批量移动同步（`network/packet/SyncPhysicalBodyMoveBatch`）

每 tick 把**所有**刚体的 pos + rot + linvel + angvel 打进**一个包**（列表）。
本项目现在每体每 tick 发一个 `PhysicsBodySyncPacket`。0.1.3 把旧的 `SyncPhysicalBodyMove` 删掉了，
天体同步也同样批量化（`kelvin` 的 `SyncCelestialBodyMoveBatch`）。

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
- **不写存档**：只活在本次运行内，重启后回到默认。要持久化就得进 `PhysicsBodySavedData` 的 NBT。

