# space 0.3.0 对齐：执行计划与侦察结论

> 配套文档：`docs/space-030-audit.md`（差异审计）、`docs/space-decompile.md`（反编译参考库总览）。
> 基准：**space 0.3.0**（用户 2026-09-30 指令：之后参考都以最新版为准）。

## 一、四项任务与状态

| # | 任务 | 规模 | 状态 |
|---|---|---|---|
| 3 | `CelestialWorld` 映射矩阵改为正反严格互逆 | 小（+18/−4 行） | ✅ **完成**（`757cd1b`） |
| 4 | `Planet` 新增 `cloudBottom`/`cloudTop` | 小（+6 处） | ✅ **完成** |
| 2 | 体素粒度 64³→16³（物理/投影/渲染/网络四层） | 大（机械） | ✅ **完成** |
| 1 | 玩家物理切到 0.3.0 模型（原版驱动 + 扫掠 AABB） | 大（架构） | ⏸ **暂缓**（用户 2026-09-30 决定；依据见 §2.5） |

用户 2026-09-30：「可以，这四件事你都可以做」= 四项全部批准。
其中**第 1 项**在侦察出范围问题后由用户改为暂缓（「那先不做这一项吧」，依据见 §2.5）——
**3/4/2 三项已全部完成并提交**，第 1 项是唯一未做的，且**已侦察清楚、将来不必重做侦察**。

## 二、★ 第 1 项的关键侦察：0.3.0 的玩家到底怎么动

**结论：0.3.0 的玩家全程由原版移动，Rapier 只负责"碰撞"这一件事。**
不是"换了个物理引擎驱动玩家"，而是**取消玩家物理体**，把物理降级成碰撞查询。

### 2.1 组件清单（全部是 0.1.3 没有的）

| 文件 | 行数 | 职责 |
|---|---|---|
| `mps/collision/physical/physical_world/PhysicalEntityCollision.java` | 366 | 手写扫掠 AABB vs 体素解算器（SAT 15 轴：3 世界轴 + 3 体轴 + 9 叉积） |
| `.../PhysicalEntityCollider.java` | 115 | 实体碰撞盒 ↔ 物理世界的适配 |
| `space/client/SpaceViewRotation.java` | 158 | **自由视角**：朝向四元数 + 鼠标缩放 + 贴地面对齐 + 写回 yaw/pitch |
| `sunshine/mixin/game/MixinPlayerFlight.java` | 73 | `Player.travel` HEAD：相机相对的**飞行**推进 + 相机系阻尼 |
| `sunshine/mixin/game/MixinEntityMoveRelative.java` | 35 | `Entity.moveRelative` HEAD：输入向量按**相机朝向**旋转（6DOF 输入） |
| `sunshine/mixin/.../MixinEntity.java` | — | `Entity.collide` RETURN 改写返回值 + `Entity.turn` HEAD |

### 2.2 各自的精确行为（逐字核对过，照抄时按这个来）

**`SpaceViewRotation.isActive(player)`** —— 激活条件**只看维度**，不需要装备：
```java
ClientSpaceWorld spaceWorld = ClientSpaceWorld.getSpaceWorld();
boolean shouldBeActive = player != null && player.level() != null
    && spaceWorld != null && spaceWorld.WorldID.equals(player.level().dimension().location());
```
状态翻转时：激活 → `seedFromEntity(player)`（`rotationYXZ(π − yRot, −xRot, 0)`）+ `pendingSurfaceAlign = !alignToSurface(player)`；
失活 → `clampEntityPitch(player)`（pitch 夹到 ±90）+ 清 pending。

- `applyMouse(player, yRot, xRot)`：`TURN_SCALE = 0.15F`，`ORIENTATION.mul(rotateY(−yawΔ)).mul(rotateX(−pitchΔ)).normalize()`，然后 `syncEntity`。
- `orientation(partialTick, player)`：每 tick 更新 `PREVIOUS`，返回 `slerpSafe(PREVIOUS, ORIENTATION, partialTick)`。
- `alignToSurface`：`localSurfaceUp` 取"最近天体"（按 `distance(pos, body.getSmoothPos(1.0F)) − radius` 最小），
  要求 `altitude ≤ radius * 2.0`；成立则把朝向左乘 `fromTo((0,1,0), 表面法线)`。
- `syncEntity`：由 `ORIENTATION` 变换出 forward，反解 `yaw = atan2(−f.x, f.z)`、`pitch = −asin(clamp(f.y))`，
  写 `setYRot/setXRot/yHeadRot/yBodyRot`。

**`MixinEntityMoveRelative`**（6DOF 输入）：
```java
if (self instanceof LocalPlayer player && SpaceViewRotation.isActive(player)) {
    Vector3f local = new Vector3f((float)(-relative.x), (float)relative.y, (float)(-relative.z));
    if (local.lengthSquared() > 1.0F) local.normalize();
    local.mul(amount).rotate(SpaceViewRotation.orientation());
    self.setDeltaMovement(self.getDeltaMovement().add(local.x, local.y, local.z));
    ci.cancel();
}
```

**`MixinPlayerFlight`**（创造飞行时的相机相对飞行 + 阻尼）：
- 门：`self instanceof LocalPlayer && getAbilities().flying && !isPassenger() && SpaceViewRotation.isActive(player)`
- 基：`left = (−1,0,0)·q`、`up = (0,1,0)·q`、`forward = (0,0,−1)·q`
- `strafe = xxa`、`fwd = zza`，`len > 1` 时归一
- `ascend = (input.jumping ? 1 : 0) − (input.shiftKeyDown ? 1 : 0)`
- `horizontalSpeed = flyingSpeed * (isSprinting() ? 2 : 1)`
- 相机是自己时先 `delta.y -= ascend * flyingSpeed * 3`
- `delta += (left*strafe + forward*fwd) * horizontalSpeed + up * ascend * flyingSpeed * 3`
- `setDeltaMovement(delta)` → `move(MoverType.SELF, delta)` → **在相机系里阻尼**：
  `alongLeft = (moved·left) * 0.91`、`alongUp = (moved·up) * 0.6`、`alongForward = (moved·forward) * 0.91`，
  再 `left*alongLeft + up*alongUp + forward*alongForward` 写回
- `resetFallDistance()`、`ci.cancel()`

**⚠ `abilities.flying` 在 0.3.0 全库从未被设置**（grep `mayfly|setFlying|flying = true` 只命中这两处读取）。
⇒ `MixinPlayerFlight` 只在玩家**本来就会飞**（创造/旁观/别的模组给）时增强手感；
**生存模式的太空移动靠的是"零重力 + `MixinEntityMoveRelative` 的相机相对输入"**，不需要飞行能力。

### 2.3 我们要删/加的东西

**删（对齐 0.1.3 的旧模型，约 980 行）**：
`physics/PlayerPhysicsBody.java`(485)、`mps/physical/entity/PhysicalEntity.java`(211)、
`mixin/SpaceInputRotationMixin.java`(126)、`mixin/EntityPhysicsDriveMixin.java`(62)、
`physics/PhysicsDrivenPlayers.java`(42)、`client/physics/ClientPhysicsDriver.java`(32)、
`mixin/ServerPacketClampMixin.java`(22)

**加（0.3.0 的模型，约 856 行）**：上面 2.1 那六个文件。

### 2.4 这一项的行为影响（要写进实机判据表）

- 玩家不再由 Rapier 驱动 ⇒ **我们当初"接受"的接触穿透锯齿从设计上消失**（那不是 bug 修复，是模型换了）。
- 原版跳跃/贴地/摔落/游泳/爬梯语义**回归**（不再被 `move` HEAD cancel 掉）。
- 新增能力：太空自由视角（鼠标 ×0.15）+ 相机相对 6DOF 输入 + 靠近星球自动对齐地面。
- 风险点：`PhysicalEntityCollision` 的 15 轴 SAT 与我们的体素读取接口必须接对；
  以及"玩家在太空怎么获得推进"在生存下变成"零重力飘"——**这可能改变我们现有的太空玩法手感**，要重点实机验。

### 2.5 ⏸ 第 1 项**暂缓**（用户 2026-09-30 决定），以及做这个决定所依据的发现

侦察到 2.4 之后又发现一件**改变范围判断**的事，据此用户决定暂缓：

> **space 0.3.0 里没有任何"姿态/身体倾斜/手部角度/颈部锥"功能。**
> 对 `decompiled-space\0.3.0` 全树 grep：`superman|prone`、`BodyTilt|bodyQuat|tilt`、
> `handAngle`、`neck`、`rollAssist|LevelAssist`、`bodyCenterOffset`、`headLocalEuler`
> —— **全部 0 命中**。0.3.0 的 `org/deep_space_studio/space/client/` 下与朝向有关的
> 只有 `SpaceViewRotation.java` 一个文件。

对照我们的 `com.mss.polymech.space.SpacePlayerData`（~700 行）：它**是 `SpaceViewRotation` 的超集**，
额外提供

| 我们的能力 | 0.3.0 有吗 | 依赖它的文件 |
|---|---|---|
| 颈部锥模型（头先转、转不动推身体） | ❌ | `SpaceTurnMixin` |
| 超人俯卧姿态（`SUPERMAN_PRONE_PITCH_DEG`） | ❌ | `SpaceBodyTiltMixin`、`SupermanDimensionsMixin` |
| 身体倾斜渲染（`bodyQuat`） | ❌ | `SpaceBodyTiltMixin` |
| 第一人称手部角度（`advanceHandAngles`） | ❌ | `SpaceFirstPersonHandMixin` |
| 滚转回正（`LevelAssist`） | ❌ | `LevelAssist`、`SpaceTravelMixin` |
| 身体旋转支点（`bodyCenterOffset`） | ❌ | `SpaceCameraMixin`、`ClientPhysics` |
| 头部件命中箱（`HEAD_BOX_*`） | ❌ | `mixin\space\MixinEntity` |
| 朝向网络同步（`SpaceRotationPayload`） | 部分（0.3.0 走 vanilla yaw/pitch） | `SpaceRotationSync`、`Polymech` |

`SpacePlayerData` 共被 **17 个文件、74 处**引用，其中包含渲染栈与网络层。

**所以"完整对齐 0.3.0 玩家模型"≠"换个物理引擎"，而是"删掉我们已有的姿态/渲染特性"**：
0.3.0 的玩家只有一个自由视角四元数 + 鼠标缩放 + 贴地面对齐 + yaw/pitch 回写。
照抄它会回退我们已做的东西，并且要改写 17 个文件 —— 其中好几个正是前几轮刚稳定下来的渲染项。

**用户的决定：「那先不做这一项吧」。** 因此第 1 项**暂缓**，不是放弃：

- 已侦察清楚的部分全部保留在本节与 §二，**将来要做时不需要重新侦察**。
- 将来若要继续，建议先明确**范围**：
  - **A（窄）**：只换移动/碰撞层 —— 保留 `SpacePlayerData`（它是超集，且它的 `turn()`
    已经在做 `SpaceViewRotation.syncEntity` 的同一件事：把朝向写回 vanilla yaw/pitch），
    只移植 `PhysicalEntityCollision`/`PhysicalEntityCollider` + `Entity.collide` RETURN 钩子
    + `MixinEntityMoveRelative`/`MixinPlayerFlight`（把朝向来源从 `SpaceViewRotation.orientation()`
    换成我们的 `SpacePlayerData` 基向量），删掉 Rapier 玩家刚体链。**不丢任何特性。**
  - **B（全）**：连 `SpaceViewRotation` 一起换掉，等于删掉上表 7 项能力并改写 17 个文件。
- **不要**在没定范围前动手：`SpacePlayerData` 是渲染栈的支点，动它等于同时动相机/模型/命中箱。

**当前 Rapier 玩家物理仍然可用且已验证**（第 3/4/2 项都是在它之上做的），
所以暂缓**不影响**已完成的三项。

## 三、第 2 项：体素粒度 64³→16³ ✅ 完成

### 3.1 审计表（space 0.1.3 → 0.3.0）

| 文件 | 0.1.3 | 0.3.0 |
|---|---|---|
| `PhysicalBody` | `CHUNK_WIDTH=64`、`CHUNK_COUNT/TOTAL=2/8`、`chunkIndex=x*4+y*2+z` | `16`、`8/512`（转 public）、`x*64+y*8+z` |
| `PalettedChunk` | `WIDTH/SIZE=64/262144`、`index=x+(z<<6)+(y<<12)` | `16/4096`、`x+(z<<4)+(y<<8)` |
| `ProjectionManager` | 脏包 `slot<<8\|(cx<<2)\|(cy<<1)\|cz`、上传/读取循环 8 次 | `slot<<9\|(cx&7)<<6\|(cy&7)<<3\|(cz&7)`、512 次 |
| `uploadChunk` | `chunkOffset = 1` | `4`（居中 = chunk−4，范围 −4..3） |
| `ClientPhysicalBody` | 8 个顶点缓冲 | 512 个 |
| `ServerPhysicalBody` | — | 新增 `getBlockEntityEntries(cx,cy,cz)`，`MAX_ENTRIES=4096` |

### 3.2 ★ 审计表有两行**不适用于我们**（实测过，别照抄）

- **`ClientPhysicalBody`：我们根本没有顶点缓冲。** 该类只有 109 行，类注释明确把
  `render`/`buildRegionVertexBuffer`/`buildFrictionBuffer`/逐子块 `VertexBuffer`/视锥剔除
  全部推迟到"客户端渲染"那一项（审计文档 F7）。所以"8 → 512 个缓冲"在我们这里
  **无对应代码可改**，强行加反而是在没有渲染路径时先造 512 个空缓冲。
- **`ServerPhysicalBody.getBlockEntityEntries(cx,cy,cz)` 不是粒度改动，是新增 API。**
  它属于"同步层重构"（0.3.0 把方块实体查询收进 `ServerPhysicalBody`），
  与 16³ 无因果关系；本项不动它。

### 3.3 实际改动清单（全部用常量表达，避免下次再漏）

| 文件 | 改动 |
|---|---|
| `PhysicalBody` | `CHUNK_WIDTH 64→16`、`CHUNK_SIZE 262144→4096`、`CHUNK_COUNT 2→8`、`CHUNK_TOTAL 8→512`（后两者转 **public**）；`voxelBounds` 位移 `&63/>>6/>>12` → `&15/>>4/>>8`；`invalidCacheSize()` 的尺寸串改为**由常量拼出** |
| `PalettedChunk` | `WIDTH 64→16`、`SIZE 262144→4096`、`index` `(z<<6)+(y<<12)` → `(z<<4)+(y<<8)` |
| `ProjectionManager` | 新增 `CHUNK_BITS`（由 `CHUNK_COUNT` 推出，=3）/`CHUNK_MASK`(=7)；`packDirtyKey` 改 3 位/坐标**并加掩码**；两处解包同步；`uploadAllChunks` 改 512 次扁平循环；`uploadChunk`/`readChunk` 的 `chunkOffset 1→4`；`readChunk` 的 `int[64][64][64]`/`*64`/`<64`/`+64-1` → 16；`onBlockUpload` 的 6 处 `/64` → `/16` |
| `SyncPhysicalBodyBlockEntity` | `int width = 64` → `PhysicalBody.CHUNK_WIDTH` |
| `PhysicalChunkManager` | 删掉 `old_physicalChunks`（0.1.3 那个 Map 只增不减 = **真实的无界内存泄漏**；0.3.0 已删）。`orderedChunks`（移除顺序/迟滞）**保留** —— 0.3.0 也保留 |

### 3.4 ★★ 一个必须记住的陷阱：`updateProjectionAABB` 的偏移不是 `CHUNK_WIDTH`

```java
// 0.1.3（旧）—— CHUNK_WIDTH 恰好 == MAX_WIDTH/2 == 64，所以看不出问题
int baseX = cx * CHUNK_WIDTH - CHUNK_WIDTH;
// 0.3.0（新）—— 偏移是 MAX_WIDTH/2 = 64，与子块边长无关
int baseX = cx * CHUNK_WIDTH - MAX_WIDTH / 2;
```

照抄旧写法而只把 `CHUNK_WIDTH` 改成 16 ⇒ 偏移变成 −16 ⇒ 512 个子块只铺到
**[−16, 112)**，整个体素静默平移 48 格。**编译完全正常。**
同理 `uploadChunk`/`readChunk` 的 `chunkOffset` 是 `CHUNK_COUNT/2 = 4`（不是 1）。
`upChunk` 里的 `int move = 64` 是**对的**（0.3.0 原文也是 64），不要"顺手统一"成 16。

### 3.5 判据（离线，已并入回归第 15 步）

新增 `native/jni-smoketest/SubChunkGranularityProbe.java`（**19/19 通过**）。
它不钉"某常量等于几"，而是钉三条**结构性不变量** —— 因为改这种全局耦合常量时
编译器帮不上忙（每个 64 都是合法 int，全改错也 BUILD SUCCESSFUL）：

| 组 | 不变量 | 为什么能抓住错误 |
|---|---|---|
| B | **覆盖性**：8×8×8 个子块恰好一次铺满 [−64, 64)³（实测 2097152/2097152 格，无重叠无空洞） | 偏移写成 −16 时只能覆盖 [−16, 112)，这条立刻 FAIL |
| C | **反例有效性**：误用 `-CHUNK_WIDTH` 时覆盖范围确实不足（实测 [−16, 111]） | 保证 B 组不是恒真判据 |
| D | **索引互逆**：`index` 的 `<<4/<<8` 与 `voxelBounds` 的 `&15/>>4&15/>>8` 互逆（全 4096 格） | 只改一半 ⇒ 碰撞盒与方块网格错位 |
| E | `chunkIndex` 在 512 个子块上单射，且 == `cx*64+cy*8+cz`（0.3.0 原文） | 索引碰撞会让两个子块互相覆盖 |
| F | **位布局单射**：0.3.0 的 3 位布局 512 键无碰撞；**旧 1 位布局只有 32 个不同键**（16 倍碰撞） | 证明这次位布局改动是**必需**的，不是可选优化 |

探针离线跑（不加载 MC 类），所以派生公式（`index`/`chunkIndex`/`CHUNK_BITS`/`packDirtyKey`/
投影偏移）在探针里**复刻**，再由 `check-artifact.ps1` 的 **11 条源码契约**保证
"复刻 == 实际实现"（含 1 条 `mustNot` 型：`old_physicalChunks` 不得回归）。
缺了这层契约，探针可能一直在验证一个已经不存在的公式。

### 3.6 实机判据表（第 2 项，需用户重启客户端后验证）

**为什么要实机验证**：离线只能证明"分区/索引/位布局自洽"，
证明不了"服务端读地皮→推给客户端→客户端显示"这条端到端链路。
粒度错了最典型的表现是**方块出现在错误位置**而不是崩溃。

| # | 操作（绝对坐标，勿用相对描述） | 期望 | 不符则说明 |
|---|---|---|---|
| 1 | 在太空放置一个物理体（任意方块），记录该方块的**世界坐标** | 方块出现在放置位置 | 整体平移 ⇒ `updateProjectionAABB` 偏移或 `chunkOffset` 错 |
| 2 | 在物理体上**连续放满一条 16 格长的线**（例如沿 X 从一端到另一端） | 16 格全部可见、位置连续 | 第 17 格起错位/消失 ⇒ 子块边界索引错 |
| 3 | 在**子块边界两侧**各放一格（例如局部 x=15 与 x=16） | 两格相邻、都在 | 有一格跑到别处 ⇒ `readChunk` 的 base 偏移错 |
| 4 | 在物理体上放一个**箱子**，放入物品，然后**拆掉箱子** | 放上能开、拆掉后客户端箱子消失 | 拆掉还留着 ⇒ `SyncPhysicalBodyBlockEntity` 清理宽度错 |
| 5 | 在物理体上放**红石/活塞**并触发一次 | 客户端看到状态变化 | 看不到变化 ⇒ 脏块位布局/掩码错（标脏标到别的子块） |
| 6 | 把物理体**开走一段距离**再回来 | 方块网格完整、无空洞 | 出现空洞/错层 ⇒ 子块数组索引或上传循环上界错 |
| 7 | 观察物理体**碰撞**（走上去、撞它） | 碰撞面与可见方块面**吻合** | 碰撞盒偏移但画面正常 ⇒ `voxelBounds` 位移没跟着改 |

第 7 条是唯一能把"渲染网格"与"碰撞网格"分开验的判据：两者用的是同一份
`int[]`，但读取位移是**两处独立代码**（`PalettedChunk.index` 写、`PhysicalBody.voxelBounds` 读）。

### 3.7 用户 2026-10-05 报告"玩家可以穿过物理体"：先分清哪条链，再看 `[镜子体体检]`

**用户的猜测（"是不是因为变成 16"）必须分链回答 —— 答案是"看哪条链"：**

| 链 | 用不用 `CHUNK_WIDTH`(16) | 16 能不能解释"穿过去" |
|---|---|---|
| **在跑的碰撞链**（服务端 `PhysicsBodyTracker` 的体 / 客户端 `ClientPhysics` 的镜子体 + 地形体素） | **不用** | ❌ 不能 |
| MPS 克隆（`PhysicalBody`/`PalettedChunk`/`ProjectionManager`） | 用（§3.4 那个陷阱） | ✅ 能，但这条链**现在没有消费者** |

- 在跑的碰撞链全程没有 `CHUNK_WIDTH`：`placeBlockAt`/`createFromRegion` 与 `ClientPhysics.syncShips`
  都是 `NativePhysics.packCell(dx,dy,dz)` —— **相对各自刚体原点的 21 位有符号格坐标**
  （±1,048,575，地形 localY≈1074 也安全）。客户端物理世界是 `ClientPhysics` 里自己
  `worldCreate` 的那个 `world`，**不是** MPS 克隆的世界。所以 64→16 改不到它。
- MPS 克隆那条链确实有 §3.4 的陷阱（偏移写成 `-CHUNK_WIDTH` ⇒ 静默平移 48 格），
  但它现在没有渲染/碰撞消费者（§3.2：`ClientPhysicalBody` 连顶点缓冲都没有）。
  真要担心它，判据是第 15 步 `SubChunkGranularityProbe` 的 B/C 组（覆盖性 + 反例有效性）。

**"能看见、没碰撞、牵引枪抓不到、也挖不掉"是四个症状，而只有一个原因能同时给出四个**：
那个体在**两侧都没有碰撞体**。渲染走方块列表（与碰撞体无关 ⇒ 照画），
而牵引枪/挖掘都是**对碰撞体的射线**（没有碰撞体 ⇒ 打不到 ⇒ 抓不到、也挖不掉）。

**已定位的真洞（客户端侧）**：原生层 `colliderAttachVoxels` 对**空单元数组**是 `return -1`
（`lib.rs` 的 `Ok(n) if n > 0 => n, _ => return -1`），**不是**建一个空碰撞体。
而 `ClientPhysics.syncShips` 原来**不看返回值** ⇒ 刚体建出来了、碰撞体一个没挂、`ShipBody` 照登记
⇒ 从那一 tick 起玩家就能穿过它，画面却完全正常。现在改成"销毁刚体 + 不登记 + 打一行 FAIL，
下一 tick 重试"。（`docs/mps-clone-plan.md` 的"拾取为什么在客户端自己算"早就记过
"客户端镜子体不保证有碰撞体" —— 这次是把它从**已知前提**变成**可判定的错误**。）

**新增程序判据（客户端，1s 一次，只查玩家 8 格内的体）：`[镜子体体检]`。**
它把上面第 7 条自动化：从**眼睛**朝**画面上最近那一格的可见中心**（渲染位姿 + 方块列表）
打一发玩家组 `(2,5)` 的射线，再打一发"全通过" `(-1,-1)`，四种结论互斥：

| 日志尾部 | 含义 |
|---|---|
| `PASS（朝可见方块中心打，命中它自己的碰撞体）` | 可见面 = 碰撞面，这一侧真的能撞上 |
| `FAIL：碰撞体在，但玩家的组 (2,5) 打不到它 ⇒ 组不匹配（幽灵）` | 碰撞组那一类（`filter=0`） |
| `FAIL：先撞到别的碰撞体 N ⇒ 碰撞体整体错位（可见面与碰撞面不吻合）` | §3.4 那一类（48 格平移） |
| `FAIL：朝可见方块中心打，两发都打不到 ⇒ 碰撞体不在世界 / 形状为空 / 整体错位` | 上面那个 `-1` 洞，或体素根本没建上 |

**目标点必须是"可见的那一格"，不能是刚体原点**：原点就是碰撞体自己的坐标系，
碰撞体整体平移时原点跟着一起平移 —— 打原点照样 PASS，只有打可见面才抓得到 §3.4 那类错位。

**顺带修掉的一个崩溃（`SpaceBlockPlacement`，`2ec3443` 引入）**：
成功提示里 `Long.toHexString(bodyId).substring(0, 8)` —— 原生层给的 id 是 3、4 这种小数，
`Long.toHexString(3) == "3"` ⇒ `substring(0,8)` 抛 `StringIndexOutOfBoundsException`。
它在**建体成功之后**炸，于是 ① 提示不出现（用户以为没放上，反复右键）② 冷却不记（可无限连点）
③ `event.setCanceled(true)` 被跳过 ⇒ 原版放置路径继续跑。
取证：`run/logs` 里 18:44 / 18:45 / 19:10 三次单块放置，每次 `物理体 N 由单块创建` 后 4ms 都是这条异常。
修法两半：**把取消提前到副作用之前**（装饰不许带走契约）+ id 改 `%08x`（定宽，不再需要 substring）。

**实机判据（绝对坐标，用户执行）**：
1. 造一个物理体，站在离它 3~5 格处看日志：应出现一行 `[镜子体体检] … PASS`；
   若是 FAIL，那一行已经把原因写在 `⇒` 后面（组 / 错位 / 不在），**不需要靠眼睛判断**。
2. 走向它并贴上去：日志从 PASS 变 FAIL（或 FAIL 里出现"先撞到别的碰撞体"）⇒
   可见面与碰撞面不重合，属于 §3.4 那一类，**不是** Rapier 参数问题（别再去调求解器）。


## 四、第 4 项：`Planet` 云层字段 ✅ 完成

0.3.0 的 `Planet` 在 encode/decode **中间**插入 `cloudBottom`/`cloudTop` ⇒ **线格式不兼容**。
数据包 cloud 段新增可选 `bottom`/`top`（缺省回退 `cloudHeight`），
由 `SpaceModDataPackManger` 调 `setCloudBottom`/`setCloudTop`。

**已做**：`Planet` 两个字段 + encode/decode（位置与 0.3.0 相同）+ 4 个 getter/setter +
`getCloudThickness()`（`max(top−bottom, 0)`，与 0.3.0 的 `CelestialBodyDataUBO` 同式，放在模型层免得每个消费者各 clamp 一遍）；
`SpaceModDataPackManger` 解析可选 `bottom`/`top`。

**⚠ 目前只到数据层，字段暂时没人读** —— 这不是遗漏，是如实记录：
本项目的 `cloudHeight` 本身就是"只存不读"（全库只有 encode/decode/getter/setter，
**没有任何渲染消费**）。0.3.0 里这两个字段由 `CelestialBodyDataUBO` 写进一个 vec4
（`cloudBottom, cloudTop, max(top−bottom,0), 0`）供**体积云阶段**使用 ——
而体积云在 0.3.0 是"从死代码变成完整阶段"的大工程（`PLANET_CLOUD` 开关、
`TransmittanceTarget`、`cloudMaskSampler`、`renderPlanetCloud`）。
**本项不含渲染**；要不要把体积云一起做，需要用户再定。

**新增契约**（`check-artifact.ps1`）：
- `.class` 契约：`Planet.class` 必须含 `setCloudBottom/getCloudBottom/setCloudTop/getCloudTop/getCloudThickness`。
- **顺序契约**（新机制）：4 条 `IndexOf` 链式递增断言，钉住 encode/decode 的字段顺序与 setter 齐全。
  为什么需要新机制：字段顺序在 `.class` 常量池里**看不见**（顺序不是字符串），
  而顺序错 = 跨版本整段错位/解出来的环数据全乱。用 `IndexOf` 而不是"多行字面子串"是为了对 CRLF/LF 差异免疫。

## 五、验证约定（每项都要给）

1. `.\gradlew compileJava` 必须 `BUILD SUCCESSFUL`。
2. `.\native\jni-smoketest\run-offline-checks.ps1` 必须 **exit 0**（现 **23 步**：第 23 步是物理体射线几何内核 BodyVoxelRaycastProbe）；
   每加一条判据就并入该脚本（注意它有**双 BOM**：改完要用
   `[System.IO.File]::ReadAllBytes/WriteAllBytes` 补 `EF BB BF EF BB BF`，`edit` 工具会吃掉 BOM；
   `check-artifact.ps1` 是**单 BOM**）。
3. 每个用户可见的行为改动都要给**实机判据表**（PASS/FAIL 由绝对坐标判定，不用相机相对量）。
4. 助手**不启动客户端**；实机验证由用户做。
5. 破坏性重构前先提交一个可回滚点（用户明确偏好）。
6. **本 shell 是 Windows PowerShell 5.1，没有 `pwsh`** —— 调脚本用 `& 'path\to.ps1'`，
   别写 `pwsh -File`。`java` 传 `-Dstdout.encoding=UTF-8` 时**必须加引号**，否则被拆成两个参数。
7. 给离线探针加"复刻公式"时，**必须同时加 `check-artifact.ps1` 源码契约**钉住"复刻 == 实现"，
   否则探针可能在验证一个已经不存在的公式（第 2 项的 11 条契约就是为此）。
8. 改动**删除**类行为（如删掉无界缓存）时用 `mustNot` 型契约，防止回归。

---

## 3.8 物理体互动：两栈并存 + 射线差异（2026-10-06 调查结论）

### 结论一：仓库里有两套物理栈，同时活着

| | STACK A（本项目自己） | STACK B（space 0.3.0 洁净室克隆） |
|---|---|---|
| 包 | `com.mss.polymech.physics` / `client.physics` | `com.mss.polymech.mps` |
| 状态 | **全部玩家可见功能都走这里** | **全链路已接线但永久为空** |

B 的"永久为空"是**可证明的**，不是猜测：全仓库 `new ServerPhysicalBody` 只有一处
（`mps/physical/physical_body/ServerPhysicalBody.java:101`，`loadFromTag`），
只被 B 自己的存档加载路径调用（`PhysicalBodyWorldData.loadBodies` ← `CollisionPhysicalServerAction:99`）；
而 `saveBodies` 只写 B 世界里已有的体 ⇒ 空世界跨会话永远是空的。
没有任何代码把 A 的体拷进 B（反之亦然）。两份存档文件也是分开的
（A：`polymech_physics_bodies`；B：`physical_bodies`）。
B 还在**无条件付代价**：客户端/服务端各一个 100Hz 步进线程 + 服务端 20Hz 区块线程，跑的都是空 Rapier 世界。

### 结论二：B 即使有体也互动不了（四处断链，每处都独立致命）

1. **没有造体者**：B 只有 `loadFromTag`；space 的 `PhysicalSelectionManager`（法杖选取建体，
   也是 `uploadAllChunks`/`copyBlock` 的唯一调用者）在本仓库**整类缺失**，
   `PhysicalSelectionActionPacket`/`SyncPhysicalSelection`/`PhysicalSelectionWandEvent` 同样缺失。
2. **没有位姿流**：`SyncPhysicalBodyMoveBatch` 注册了但**全仓库没有构造点**（只有它自己的 `decode`）。
   space 在 `ProjectionTickHandler` 里每 tick 给每个体入队一条 move ⇒ 客户端镜体停在创建/登录时的位姿。
3. **方块数据只发 8/512 个子块**：登录/建体只发 `x,y,z ∈ [-1,0]`（`PhysicalWorldUpdateSyncEvent:82-90`、
   `ServerPhysicalWorld:100-108`），space 是 `halfChunks=4` 即全部 512
   ⇒ 客户端镜体在中心 32³ 之外全是空气 ⇒ `ShipRaycast` 找不到方块 ⇒ `findHit()==null`。
4. **子块偏移差 3**：`PhysicalBody.setBlockStateChunk/getBlockStateChunkInt` 写 `chunk_x++`（偏移 1），
   space 是 `chunk_x += 4`（偏移 4）；而 `ProjectionManager` 传的是 `chunkX - CHUNK_COUNT/2`（偏移 4）
   ⇒ 负局部坐标被静默拒绝、正的错 3 个子块（48 格）。叠加 `readProjectionData` 还是 `chunkCount = 2`（2×2×2 时代）
   ⇒ **读档后 B 的方块表整片为空**。

### 结论三：A 的挖掘射线不是 space 的（已修）

A 的客户端挖掘一直用自己写的 `PhysicsRaycast`：把方块表拍成 `int[]`，**逐块当单位立方体**做 slab 求交。
两个偏差：

1. **方块形状被当满格**：台阶/半砖/栅栏/玻璃板/草/火把的真实形状都不是整格 ⇒ 对着看得见的空处也报命中。
   space 用 `state.getShape(...)` 的真实形状（空形状才退回 `Shapes.block()`）。
2. **slab 少夹了一次 far**：某轴提供进入点时只更新 `tmin` 而**没有** `tmax = min(tmax, tFar)`
   ⇒ 三轴区间不相交（射线从方块旁擦过）也报命中。解析反例（可复算）：
   方块 (0,0,0)，起点 (0.5, −1.788854, −2.236068)，方向 (0, 0.894427, 0.447214)，reach=10；
   X 平行 ⇒ 区间无限宽，Y ≈ [2, 3.118]，Z ≈ [5, 7.236]，交集为空（真值未命中），旧实现报"命中 t=5"。

**后果**：擦边瞄准时客户端报的格 ≠ 服务端投影里的格（甚至报空气）⇒
"瞄半天挖不掉 / 第一格破了后面不动 / 互动错格"，且**随角度时好时坏**。

**修法（已落地）**：新增 `physics/BodyVoxelRaycast.java` —— 逐字照 space 的 `ShipRaycast`：
原版 `BlockGetter.traverseBlocks` DDA + 方块真实形状 + `AABB.clip` 粗筛 + `AABB_ENTRY_EPSILON` 推进；
`client/physics/PhysicsBodyInteractionClient.castAgainstBodies` 改用它，删除 `physics/PhysicsRaycast.java`。
有意保留的唯一偏离：**位姿用渲染口径**（`renderX/Y/Z/Rotation(1.0f)`），因为玩家瞄的是画面上那一格，
而我们的渲染走网络位姿环（space 渲染与拾取都直读体位姿，它没有这个问题）。

### 结论四：放置射线读 B、写 A（**已修 2026-10-06**）

> **已修**：`SpaceBlockPlacement` 的射线改成 `PhysicsBodyTracker.raycastBody(...)` —— 对 **A 自己的体**
> 做 space 口径三步求交（AABB 粗筛 → 体局部 DDA + 方块真实形状 → 世界化），返回体局部格/面 +
> **世界**面 + 世界命中点；世界面用 `SpaceBuildRules.rotateFace` 按刚体旋转转过。
> 同时删掉 B 世界门禁（`ServerPhysicalWorld.getPhysicalWorld`）与已成死代码的
> `worldFace(PhysicalRaycast.Hit)` ⇒ "对着体某一面放置"分支**从死代码变回活代码**。
> 共用几何提到 `BodyVoxelRaycast`（新增 `worldAabb` / `toLocal` / `toWorld`），服务端不再有第二套实现。
>
> 判据：`BodyVoxelRaycastProbe` 新增 **F1–F7**（旋转体 + 已知面 ⇒ 目标格 = 面相邻格；期望值全部解析推导），
> 探针 **18/18**、离线自检 **exit 0**、`compileJava` **BUILD SUCCESSFUL**。
> 副产物：F4 抓出原版 `VoxelShape.clip` 会把起点沿射线推进 0.1%（`vec3.scale(0.001)`）的语义 ——
> 实现是对的，判据的期望值据此修正（F7 仍 PASS ⇒ 放置决策对该 0.005 偏移不敏感）。
>
> 下面保留当时的诊断原文。


`physics/SpaceBlockPlacement`：`:95` 取 B 的 `ServerPhysicalWorld` 当门禁、`:108` 用 B 的 `PhysicalRaycast.cast`，
而 B 永远为空 ⇒ 永远返回 `null` ⇒ **"对着体某一面放置"那条分支是死代码**，
每次放置都退化成 `eye + look * 3.0`（固定 3 格）。
后果：无法把方块放到"正在看的那一面上"，常常凭空在别处新建一个体（`hasAdjacentBody` 判定失败）。
修法：把这条射线改成对 A 的 `PhysicsBodyTracker` 体做（复用 `BodyVoxelRaycast.traverse` 几何内核）。

### 结论五：穿模的候选机制（仍是假设，未证实）

最自洽的解释是**渲染位姿环 vs 镜体碰撞位姿的错位**：玩家碰撞用镜体/原生的实时位姿，
而画面上的体被网络位姿环延迟约 0.1s。体静止时两者重合 ⇒ 不穿；体在动时两者分离 ⇒ 看着穿过去了。
（space 0.3.0 的玩家碰撞模型 `PhysicalEntityCollision` 是另一条路，本项目当时按用户决定"暂缓"，
所以现在仍是 Rapier 双体那套 0.1.3 模型。）

### 两栈并存的潜在雷（未爆发，但要知道）

- **两个投影槽分配器管同一个维度**（`poly_mech:projection_world`，几何都是 128³）：A 取最小空槽、B 取持久化递增 `nextSlot`；
  B 一旦有体就会拿到槽 0 —— 正是 A 第一个体用的槽，A 的 `wipeSlot` 会把对方的方块删掉。
- **两个客户端互动处理器订阅同一对事件**（`client/physics/PhysicsBodyInteractionClient` 与 `mps/client/PhysicalBodyInteractionClient`），
  今天无害只因为 B 找不到命中；B 一旦有体，一次点击会发两个包。
- **B 的 `ProjectionManager.tick()` 无调用者**（`PhysicsServerTick:27` 解析到 A 的同名类）⇒ B 的脏块回写是死代码。
- **kelvin 的"船当天体"桥读 B**（`PhysicalBodySpaceEvent:70-77`）⇒ 真实船永远不会生成 `Aircraft`。

### 待用户决策

B 是"照抄 space"的最终形态，但它现在**既不通电也没数据**。三条路：
(a) 删掉 B，只留 A（A 已修好射线，最快止血）；
(b) 把 A 的体喂给 B（迁移到 space 形态，工作量大，但方向与"照抄优先"一致）；
(c) 维持现状（继续付空转代价 + 两个槽分配器的雷）。
