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

## 当前参考点

| 版本 | .java 类数 | 备注 |
|---|---|---|
| 0.1.0 | 263 | 早期对照版本（MPS 物理 / Kelvin 天体 / Sunshine 渲染 三套） |
| 0.1.3 | 316 | 当前最新；新增内容尚未系统对照 |

## 已知差异（边对照边记，避免重复结论）

- **投影里产生的掉落物**：0.1.0 与 0.1.3 **都没有处理**（`org/polaris2023/mps` 下搜不到
  `ItemEntity` / `addFreshEntity` / `popResource`）。本项目自己实现了"每 tick 扫实体 + 搬到世界侧"，
  这一块是**我们领先**，别再回 space 里找答案。
- **破坏进度同步**：0.1.3 新增了 `SyncPhysicalBlockBreakProgress`（0.1.0 没有），
  对应本项目"破坏是瞬破、没有挖掘进度"的缺口 —— 要补时优先照 0.1.3。

## 0.1.0 → 0.1.3 系统对照结论（70 新增 / 17 移除）

MPS 物理核心新增 7 个类；下面按"对本项目的价值"排序。

### ⭐⭐⭐ 玩家物理：双刚体 + 碰撞组 + 从不旋转（`physical/entity/PhysicalEntity`，0.1.3 重写）

- **两个刚体**：主刚体 + "兄弟"刚体（`siblingBody`），各挂一个**同位置同尺寸**的 CUBOID。
- **碰撞箱从不旋转**：`move()` 里每次都 `setRotation(单位四元数)` + `setAngvel(0)` ——
  对比本项目"用角速度伺服让碰撞体跟着 6DOF 身体转"，这条路把旋转问题**整类消掉**了。
- **速度模型**：`sibling.linvel = ownVelocity`（玩家输入，每步重置）；`main.linvel = sibling.linvel + ownVelocity`
  —— 用"载速体 / 惯性体"分离，既保留惯性又能被船推动。
- **`collisionGroups(2,5)` / `(5,5)`**：本项目原生层**没有这个能力**（现在只有 friction / restitution）。
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
