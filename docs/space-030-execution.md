# space 0.3.0 对齐：执行计划与侦察结论

> 配套文档：`docs/space-030-audit.md`（差异审计）、`docs/space-decompile.md`（反编译参考库总览）。
> 基准：**space 0.3.0**（用户 2026-09-30 指令：之后参考都以最新版为准）。

## 一、四项任务与状态

| # | 任务 | 规模 | 状态 |
|---|---|---|---|
| 3 | `CelestialWorld` 映射矩阵改为正反严格互逆 | 小（+18/−4 行） | ✅ **完成**（`757cd1b`） |
| 4 | `Planet` 新增 `cloudBottom`/`cloudTop` | 小（+6 处） | ✅ **完成** |
| 2 | 体素粒度 64³→16³（物理/投影/渲染/网络四层） | 大（机械） | ✅ **完成** |
| 1 | 玩家物理切到 0.3.0 模型（原版驱动 + 扫掠 AABB） | 大（架构） | ⬜ 待做 |

用户 2026-09-30：「可以，这四件事你都可以做」= 四项全部批准。

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
2. `.\native\jni-smoketest\run-offline-checks.ps1` 必须 **exit 0**（现 **15 步**）；
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
