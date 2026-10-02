# space 0.3.0 差异审计（我们已实现的部分）

> **触发**：2026-09-30 用户把参考基准从 space 0.1.3 切到 **0.3.0**（"之后的参考都已最新版为准"）。
> 我们整套实现是照 **0.1.3** 做的 clean-room 重实现，所以必须知道**哪些行为真的变了**。
> 本文只审"我们已经实现过的那部分"，不是 0.3.0 全量对照。
>
> **参考树**：`decompiled-space/0.3.0/`（不进版本库，见 `docs/space-decompile.md`）。
> 版本结构变化（包名重构）见同一份文档。

---

## 一、方法与口径（结论可复核）

**范围怎么定的**：取"我们的类简名 ∩ space 0.1.3 的类简名"= **68 个类**。
这比人工挑可靠 —— 我们的克隆是**同名**的（`ProjectionManager` ← 他们的 `ProjectionManager`）。

**怎么排除改名噪音**（关键，否则会得到 63 个假差异）：
1. 路径映射：`mps.**` → `mps.collision.**`；`cn_grass_block.kelvin.**` → `polaris2023.mps.orbit.**`；
   `sunshine.mixin.renderer` → `sunshine.mixin.render`。
2. **只归一化 0.1.3 那一侧**（把旧名替换成 0.3.0 的新名），0.3.0 侧保持原样。
   ⚠ 两侧都归一化会产生 `mps.collision.collision.*` 这种**假差异** —— 我第一遍就是这么错的。
3. 类改名也要归一：`RapierNative` → `Native`（注意新家在 `org.polaris2023.mps.Native`，
   **不是** `mps.collision.Native`）。
4. 判据用**逐行 diff 计数**，不用字符串整体相等 —— 后者在本例中会把
   `Meteoroid`/`CelestialBody`（逐行 diff = 0）误报成"有变化"。

**结果**：68 个类里 **26 个两版逐字无差异、42 个真有行为差异**。

### 逐字无差异的 26 个（物理底座 —— 好消息，不用重审）

```
BlackHole, CelestialBody, CelestialBodyForce, ClientCelestialWorld, ClientWorldCleanup,
ColliderBody, Meteoroid, MixinClientChunkCache, MixinClientPacketListener, MixinEntity(space 自己的),
MixinItemEntity, MixinServerPlayer, MixinWorldBorder, PhysicalBodyInteractionPacket,
PhysicalBodyWorldData, PhysicalChunk, PhysicalEntity, PhysicalRaycast, RapierWorld, RigidBody,
ServerCelestialWorld, ShipRaycast, Star, SyncCreateAck, SyncPhysicalBlockBreakProgress, UniformBuffer
```

**含义**：Rapier 封装层（`RapierWorld` / `RigidBody` / `ColliderBody`）、射线（`PhysicalRaycast` /
`ShipRaycast`）、体素容器（`PhysicalChunk`）、天体基类与引力（`CelestialBody` /
`CelestialBodyForce`）、旧玩家实体（`PhysicalEntity`）**一行都没改**。
我们的 MPS 克隆层不需要因为换基准而重做。

---

## 二、★ 头条：玩家物理架构被**整体替换**（影响最大）

这不是参数调整，是**换了一套模型**。

| | **0.1.3**（我们实现的） | **0.3.0** |
|---|---|---|
| 注入点 | `Entity.move` **HEAD** + `ci.cancel()` | `Entity.collide` **RETURN** + `cir.setReturnValue(...)` |
| 谁在移动玩家 | **Rapier 刚体**（`PhysicalEntity`） | **原版** `move()`/`setPos` 照常跑 |
| 碰撞响应 | Rapier 求解器 | **手写的扫掠 AABB vs 体素解算器** |
| 探地 | 5 点 `castRay(↓, toi=0.1, groups 2/5)` + `setOnGround` + `resetFallDistance` | **没有**（改由 probe，而 probe 是死代码） |
| 重力补偿 | `setDeltaMovement(x, y+0.08, z)`（我们用 `getGravity()`） | **没有** |
| 玩家的 Rapier 刚体 | `PhysicalEntity`，`move` 里驱动 | **不再创建**（`new PhysicalEntity(` 全库 0 处） |
| `MixinLivingEntity` | 把物理世界玩家的 `getDefaultGravity` **清零** | **空 mixin**（8 行，无注入） |
| 自由视角 | 无 | **新增** `SpaceViewRotation` + `Entity.turn` HEAD 注入（接管鼠标转向） |
| 旁观者 | `destroy()` 物理实体 | 仅跳过解算（不销毁） |
| 侧别 | 事实上客户端 | 显式客户端（`!isClientSide` 直接透传） |

**新增的四个类**（0.1.3 全都没有）：

| 类 | 行数 | 作用 |
|---|---|---|
| `PhysicalEntityCollision` | 366 | 扫掠 AABB 解算器本体：位移切成 ≤2.0 的段（最多 128 段），每段 `sweepAndSlide`（≤4 次迭代：找最早命中 → 推进 → 沿面滑移） |
| `PhysicalEntityCollider` | 115 | 实体形状（`EntityShape` / `Bounds` / `at(entity, rotation)`） |
| `PhysicalEntityProbe` | 116 | DYNAMIC 刚体（mass=50、friction=100、每 5 tick 采样）—— **死代码** |
| `SpaceViewRotation` | 139 | 自由视角朝向 |

**解算器细节**（`PhysicalEntityCollision`）：
- 只用**分离轴定理**做"扫掠盒 vs 方块"：15 条轴 = 3 世界轴 + 3 体轴 + 9 个叉积（`:334-355`）。
- 命中判定 `hasCollision` 走**静态缓存** `COLLISION_STATE_CACHE`（`blockState → 碰撞形状是否为空`，
  `:32/:251-256`）。⚠ 这是**无上限的静态 Map**，长期运行会随见过的 blockState 数增长（数量有界，风险低）。
- 方块坐标 clamp 到 `[-64, 63]`（`:24-25`），与体中心 ±64 的体素体一致。
- 每段命中后推进 `min(1e-5, approachDistance)` 做**接触偏移**（`:135`）。

**"probe 是死代码"的证据**（我 grep 过，不是推断）：
`space$getPhysicalEntityProbe()` 只有"接口声明 + 实现 + 被调试渲染器 `PhysicalPlayerColliderRender` 读"；
`space$addProbeVelocity`（那个 `measured/20.0`）与 `space$syncProbe` **零调用点**；
`new PhysicalEntityProbe(...)` 只出现在没人调的 `space$syncProbe` 里。
⇒ **不要把 `/20.0` 的"速度回灌"当生效行为去实现。**

### 对我们的意义（这是本次审计最重要的结论）

1. 我们那套玩家物理（`PlayerPhysicsBody` 485 行 + `EntityPhysicsDriveMixin` +
   `PhysicalEntity` 211 行 + `PhysicsDrivenPlayers` + `ClientPhysicsDriver` +
   `SpaceInputRotationMixin` ≈ **1000 行**）对齐的是**一个 0.3.0 已经放弃的模型**。
2. **我们"接受"过的那个接触穿透锯齿，在 0.3.0 里从设计上就不存在了** ——
   因为玩家不再由 Rapier 驱动，"每子步把 own 顶进接触"这条链整条没了。
   当时我们判定它是"space 同款设计"（见 `docs/mps-clone-plan.md` 第 22/23 节），
   那个判断在 0.1.3 下成立，**在 0.3.0 下不再成立**。
3. 换成 0.3.0 的模型，是**删掉**驱动链、**加上**一个自洽的 Java 解算器；
   净代码量大致持平或更少，但语义差别很大（原版移动/跳跃/贴地全部回归原版语义）。
4. 代价：`SpaceViewRotation`（自由视角）与 `turn` 注入是**新机制**，我们没有对应物；
   而 `SpaceInputRotationMixin`（6DOF 输入）在 0.3.0 下是否还需要，需要单独判断。

> **这是一个需要用户拍板的架构决策，不是我可以自行改的。** 见文末"决策点"。

---

## 三、系统性变化一：体素粒度 **64³ → 16³**（对齐原版 section）

0.3.0 把物理体的体素单元从"每轴 2 块 × 64 格"改成"每轴 8 块 × 16 格"。
**总体积不变**（128³ = 2,097,152 格），但所有索引、循环、包数量都变了。

| 位置 | 0.1.3 | 0.3.0 |
|---|---|---|
| `PhysicalBody.CHUNK_WIDTH` | 64 | **16** |
| `PhysicalBody.CHUNK_COUNT` / `CHUNK_TOTAL` | 2 / 8（private） | **8 / 512（public）** |
| `chunkIndex(x,y,z)` | `x*4 + y*2 + z` | `x*64 + y*8 + z` |
| `PalettedChunk.WIDTH` / `SIZE` | 64 / 262144 | **16 / 4096** |
| `PalettedChunk` 索引 | `x + (z<<6) + (y<<12)` | `x + (z<<4) + (y<<8)` |
| `ProjectionManager` 脏块打包 | `slot<<8 \| (cx<<2) \| (cy<<1) \| cz`（2×2×2） | `slot<<9 \| (cx&7)<<6 \| (cy&7)<<3 \| (cz&7)`（8×8×8） |
| 上传/读取循环次数 | 8 | **512** |
| `uploadChunk` 的 `chunkOffset` | 1（centered = chunk−1） | **4（centered = chunk−4，范围 −4..3）** |
| `ClientPhysicalBody` 顶点缓冲 | 8 个（64³） | **512 个（16³）** |
| `PhysicalEntityCollision` 方块范围 | — | clamp 到 `[-64, 63]` |

**顺带新增的语义**：
- `PhysicalBody` 新增 `nonAirBlocks` 计数 + `hasAnyBlock()`（O(1) 判"体内是否有方块"），
  `ProjectionManager` 的 `isProjectionAllAir`（扫投影区块）**被删除**，改用 `hasAnyBlock()`。
  ⚠ 注意 `setBlockStateDeferred` **不维护** body 级计数 ⇒ 混用延迟写入与 `hasAnyBlock()` 会偏小。
- `PalettedChunk` 新增 **碰撞专用包围盒** `collisionAabb` + `nonAirBounds(out)` / `collisionBounds(out)`，
  只统计"非空气**且碰撞形状非空**"的方块（装饰块不再撑大宽相位盒）。
  ⚠ 去掉一个可碰撞方块可能触发 **4096 格全量重算**（`recomputeCollisionAABB`）。
- `ProjectionManager` 强制加载：**64 个区块 → 1 个中心区块**，且 `tick()` 开头
  **每 tick 取消该维度全部强制区块**（`getForcedChunks().forEach(...)`，遍历时修改集合，
  有 `ConcurrentModificationException` 风险 —— 这是他们的实现，我们若照抄要留意）。
- `ProjectionManager.init()`：投影维度缺失时**不再抛异常**，改为 warn + 降级（物理结构禁用）。
- `ProjectionManager.fillBlock`（0.1.3 叫 `writeProjectionBlocks`）：**只写填充盒**，
  不再"重写整个投影体积并把盒外清成空气"；缺 section 时**新建**而不是抛异常；
  改用 `rawFillSection` 直写 Palette/BitStorage + `recalcBlockCounts()`。
  ⇒ 调用方不能再依赖它顺带清空投影。
- `readChunk`：由"262144 次 `getBlockState`"改为 **Palette/BitStorage 直读快路径**
  （`decompressSection`）；字段写入顺序改为 `clearBlockEntities → putBlockEntity → setBlockStateChunk`。
- 区块获取一律**优先 `getChunkSource().getChunkNow()`**，取不到才 `getChunk()`（避免同步加载）。
- `ClientPhysicalBody`：绘制顺序由"按块交错（solid→cutout→…）"改为**按材质分四批**；
  **删除了方块实体渲染**与 `projectionAABB`/`worldAABB` 调试线框；
  新增 `chunkHasMesh`/`chunkVisible`/`chunkCullBounds`（复用 `AABBi`，不再每帧 new）。
- `PhysicalChunkManager`：**删除了 `old_physicalChunks` 缓存** ——
  0.1.3 那个 Map 从不清理，是**真实的内存泄漏**；0.3.0 重新加载区块时无条件重建 `PhysicalChunk`。

---

## 四、系统性变化二：同步层改为 `PhysicalBodySyncManager` + `aircraft` 路由位

| | 0.1.3 | 0.3.0 |
|---|---|---|
| 创建同步 | `ServerPhysicalBody` 的**构造器**里直接 `sendToAllPlayers`（且有一个构造器**重复广播 2 次**） | 构造器不发包；由 `PhysicalBodySyncManager.collectBodySends` 经 `ReliableCreateSender` **逐玩家可靠投递** |
| 新增时的块更新 | `ServerPhysicalWorld.addPhysicalBody` 里**即时**发 8 个包（2×2×2） | 入队 **512 条**（`halfChunks=4`，−4..3），由管理器决定合批与时机 |
| 删除同步 | **没有**（客户端只能靠其他机制隐式失效） | 新增 `removePhysicalBody` 覆写，入队 `PhysicalBodyRemove` |
| 路由位 | 无 | 所有 create/update/remove 载荷都带 `boolean aircraft = ServerSpaceWorld.isSpaceWorld(body.getLevel())` |
| 方块实体 | 随投影整体同步 | 新增 `ServerPhysicalBody.getBlockEntityEntries(cx,cy,cz)`，按 16³ 块序列化（`MAX_ENTRIES=4096`） |
| 非 `ServerPhysicalBody` 的体 | **完全跳过同步** | 也同步 |
| `getServerLevel()` | 每次发包前解析，未就绪则静默跳过 | 方法删除，改由管理器负责 |

**包格式不兼容**：`SyncPhysicalBodyBlockUpdate` 由 `record(uuid, pos, data)` 变为
`record(boolean aircraft, uuid, pos, data)`，且新增 `MAX_DIM=16` / `MAX_BLOCKS=4096` /
`MAX_RLE_ENTRIES=4097` 校验。**两版互发必然解析错位。**

---

## 五、系统性变化三：`CelestialWorld` 的坐标映射被**修正**（我们抄进了同一个缺陷）

| | 0.1.3 | **我们** | 0.3.0 |
|---|---|---|---|
| `getSpacePosFromWorldPos` | `x = -x` 再算 `dx` | **去掉了 `x=-x`**（有意偏离，见 §31.15） | 去掉 `x=-x` |
| 正向矩阵 | `x0 = dx·c + dz·s; z0 = dx·s + dz·c`（**对称阵，不是旋转**） | **同 0.1.3（对称阵）** | `x0 = dx·c − dz·s; z0 = dx·s + dz·c`（**真旋转**） |
| 反向矩阵 | `x = x0·c − z0·s; z = x0·s + z0·c` | **同 0.1.3** | `dx = x0·c + z0·s; dz = −x0·s + z0·c`（**严格逆**） |
| 世界轴↔经纬度 | 世界 X→纬度、世界 Z→经度 | **有意对调**（X→经度、Z→纬度，且取负）—— 为满足"天空与 MC 罗盘对应"的硬需求 | 仍同 0.1.3（X→纬度） |

**缺陷在哪**：对称阵 `M = [[c,s],[s,c]]` 的行列式是 `c²−s²`，其逆是 `1/(c²−s²)·[[c,−s],[−s,c]]`，
**不等于** `[[c,−s],[s,c]]`。所以"对称正向 + 旋转反向"**只在 `rotate = 0`（c=1, s=0）时互逆**。
0.3.0 把正向改成真旋转、反向改成严格逆，往返才精确闭合。

**这是潜伏缺陷还是已触发？—— 潜伏。** 实测：
- 我们的数据包 15 个世界 `pos_shadow_rotate` **全是 `0.0`**；space 0.3.0 自带数据包也是 `0`。
- `rotate = 0` 时两套矩阵都退化成单位阵 ⇒ **我们的映射在实际上完全可逆**
  （这也解释了 `SpaceMappingProbe` 为什么显示往返闭合到 8e-8）。
- 但 `pos_shadow_rotate` 是**真实存在、且会被读取**的数据包字段
  （`SpaceModDataPackManger:322-325` → `setPosShadowData` → 网络同步），一旦有人填非零就会坏。

**探针数字**（新增 `native/jni-smoketest/MappingRoundTripProbe.java`，**11/11 通过**）：
把 2D 矩阵对单独拎出来做往返（完整映射的 asin/atan2 与自转四元数按构造可逆，不必测）：

| `rotate` | 我们的往返误差 | 0.3.0 式的往返误差 |
|---|---|---|
| 0 | **0**（精确） | **0**（精确） |
| 1e-9 | 2.000e-09 | 0 |
| 0.1 | **1.987e-01** | 1.842e-16 |
| 0.5 | **8.415e-01** | 1.895e-16 |
| π/4 | **1.000e+00**（100%） | 9.474e-17 |
| 1.0 | **1.416e+00** | 1.842e-16 |
| π/2 | **2.000e+00**（200%） | 0 |
| 3.0 | **2.794e-01** | 1.895e-16 |

C 组还用解析解钉死了缩放因子：我们往返后的 `dx` 恰好被乘上 **`|cos²−sin²|`**
（rotate=0.1/0.5/π4/1.0 四组解析值与实测值逐位一致）—— 这就把"缺陷的数学形式"也钉住了，
而不是只知道"它不闭合"。

> **建议**：把正向改成真旋转、反向改成严格逆（0.3.0 的形式），**在 `rotate=0` 时行为零变化**
> （两套都退化成单位阵），所以是**免费**的健壮性修复。
> ⚠ 但**世界轴↔经纬度的对调与取负必须保留** —— 那是用户拍板的"天空必须与 MC 罗盘对应"的硬需求，
> 0.3.0 并没有改这一点，照抄 0.3.0 会把罗盘错位改回来。
> 即：**只改矩阵形式，不改轴约定**。


---

## 六、其它逐类差异（A–E 批）

| 类 | 结论 |
|---|---|
| `ProjectionManager` | 见 §三/§四。另外：`removePhysicalBody` 不再发包/清块/取消强制加载；破坏进度**完成复位包（−1.0F）被删除**；`continueDestroy` 签名改为带 `uuid` + `localPos`；`getSlot`/射线/`destroyBlock`/`useBlock`/交互距离/音效等**逐行未变** |
| `PhysicalBody` | 见 §三。`MAX_WIDTH=128`/`MAX_SIZE` 未变 |
| `PalettedChunk` | 见 §三 |
| `PhysicalChunkManager` | 删 `old_physicalChunks`（修内存泄漏）；其余逐行一致（兴趣点 16×16=256 区块、排序、`buildVoxelCollider` 参数等） |
| `PhysicalWorld` | 仅新增 `minecraftLevel` 字段 + `setMinecraftLevel`/getter + `getPhysicalBodiesSnapshot()` 别名；**碰撞分组常量与增删顺序逐行未变** |
| `ClientPhysicalWorld` | 新增静态 `spaceBodies`（`put/get/remove/ensureSpaceBody`）+ `init()` 清空；区块与玩家逻辑未变 |
| `ServerPhysicalWorld` | 见 §四。`dimensionLeapPhysicalBody` 两个重载逐行未变 |
| `ClientPhysicalBody` | 见 §三。`MOVE_STEP=0.2`、插值、摩擦 UV 公式**逐字未变** |
| `ServerPhysicalBody` | 构造器不再发包；`loadFromTag` 顺序改为 `readProjectionData` **先**、AABB 后（存档 NBT 覆盖投影读取）；新增 `getBlockEntityEntries`；NBT 键与类型未变（**两版存档可互读**） |
| `MixinEntity` | 见 §二（头条） |
| `ClientCollisionPhysicalThread` / `ServerCollisionPhysicalThread` | **只有耗时埋点**（`ThreadStepTimings.log*PhysicsTickNanos`）；步进参数、周期、暂停逻辑**逐字未变** |
| `CollisionPhysicalServerAction` | 新增 `setMinecraftLevel(serverLevel)` 绑定；判空被挪到绑定**之后**（`newPhysicalWorld` 返回 null 时会 NPE，0.1.3 不会）；其余未变 |
| `MixinLevelChunk` | 无行为差异（`instanceof ClientLevel` → `getLevel().isClientSide()`，等价） |
| `SpaceWorld` | 新增：创建 cosmos world 时 `cosmosWorldEnableRadio`；每个天体注册时 `cosmosWorldRadioAddReflector(handle, radius)`；新增 `getCosmosWorldMemoryHandle()`。**引力/操作队列参数逐行未变** |
| `ClientSpaceWorld` | `syncMoveData` 判空由"可能 NPE"改为双非空守卫；新增整套 aircraft 客户端网络入口与按名移除天体 |
| `ServerSpaceWorld` | radio 由 `radioNetwork.tick()` 改为 `preStep(handle)` + `Native.cosmosWorldRadioStep(handle)` + `postStep()` 三段式；新增 `freeCosmosWorld()` 与 `resolveSpaceWorld(level)` |
| `Planet` | 新增 `cloudBottom`/`cloudTop` 并**插入 encode/decode 中段** ⇒ **网络格式不兼容**；大气密度/气压公式与全部常量**逐字未变** |
| `Aircraft` | `UUID` 提升为主字段；`decode` 不再在物理体缺失时返回 null（改为用网络包字段构造）；新增 `initPhysicalBody`（**UUID 匹配才赋质量**）与 `nameOf(uuid)`；半径仍固定 1.0 |
| `CelestialWorld` | 见 §五。`Height=1024`、`MinY=0`、`G=1`、经度/纬度 ×π/2、`fromToQuaternion` 等**未变** |
| `SpaceModDataPackManger` | 详见补审（G 批） |
| 网络包（F 批）/ 渲染（G 批）/ 轨道同步（H 批） | 详见对应补审小节 |

---

## 七、决策点（需要用户拍板）

1. **玩家物理要不要切到 0.3.0 的模型？**
   - 切：删掉 Rapier 驱动链（≈1000 行），改成"原版移动 + Java 扫掠解算"（≈500 行），
     接触穿透锯齿从设计上消失，原版跳跃/贴地语义回归；代价是重写 + 新引入自由视角机制。
   - 不切：继续用 0.1.3 的模型，但要接受"它已被上游放弃"，且我们记录的
     "space 同款设计"论据失效。
2. **体素粒度 64³ → 16³ 要不要跟？** 跟的话投影/物理/渲染/网络四层都要动（改动面大但机械）。
3. **`CelestialWorld` 映射修正要不要跟？** 这条是**修正了一个真缺陷**（0.1.3 正反不互逆），
   建议优先核对我们的实现是否复现了该缺陷。
4. **`Planet` 新增 `cloudBottom`/`cloudTop`** 是纯增量（云层厚度），跟的话要注意网络格式不兼容。

---

## 八、补审：F（网络包）/ G（渲染与交互）/ H（轨道同步与线程）

> 详细报告：`.wipbak/audit/F-network.md`、`.wipbak/audit/H-orbit.md`（未进版本库）。
> G（渲染与交互）仍在补审。

### F —— 网络包

**贯穿性变化：六个包新增 `boolean aircraft`，线格式全面不兼容。**

| 类 | 变化 |
|---|---|
| `SyncPhysicalBodyBlockUpdate` | `record` 首字段新增 `aircraft`；新增 `MAX_DIM=16` / `MAX_BLOCKS=4096` / `MAX_RLE_ENTRIES=4097`，`decompress` 做**全量校验**（维度、run 对长度≥2、count>0、idx 溢出）；`handle` 新增 aircraft 分支 + **补上 `getPhysicalWorld()` 判空**（0.1.3 会 NPE） |
| `SyncPhysicalBodyCreate` | 新增 `aircraft`；`handle` 重构：aircraft 走 `getSpaceBody`/`putSpaceBody` + `writeAircraftCreate` + `bindAircraft`；非 aircraft 分支**"已存在就只 `bindAircraft`，不 new / 不 add / 不重置位姿"**；★ **`SyncCreateAck` 由无条件发送改为仅 level 匹配时发送** |
| `SyncPhysicalBodyRemove` | 新增 `int id` + `aircraft`；新增 ack（无条件）与飞行器移除路径。⚠ 由 `(level,uuid)`/`(PhysicalBody)` 构造的包 `id` 恒为 0，而 `nextId` 从 1 起 ⇒ **这类包的 ack 匹配不上 pending**（他们的实现如此） |
| `SyncPhysicalBodyMoveBatch` | `Entry` 新增 `aircraft`；`MAX_ENTRIES=65536` + 解码校验；aircraft 条目**只 `writeAircraftMove`，丢弃 linvel/angvel** |
| `SyncPhysicalBodyBlockEntity` | 新增 `aircraft` + `MAX_ENTRIES=4096`；★ **清理宽度常量 `width` 由 64 改为 16**（清理区域 64³→16³） |
| `SyncPhysicalWorldCreate` | 线格式不变；`handle` 下沉到 `PhysicalBodySyncManager`；★ **ack 由"先发"改为"世界创建后发"**；新建 `ClientPhysicalWorld` 时新增 `setMinecraftLevel(...)` |
| `SyncPhysicalThreadStart` | 线格式不变；★ **ack 与 `startThread()` 顺序反转**（现在先起线程再 ack） |
| `ReliableCreateSender` | ★ **`MAX_ATTEMPTS` 5 → 30**（按 40 tick 间隔 ≈200 → 1200 tick）；新增**迟到 ack 补偿**：`pending.attempts()>1` 且载荷是 `SyncPhysicalBodyCreate` 时调 `onCreateAppliedLate` **补发完整方块数据**；放弃前新增 `LOGGER.error`。`sendCritical` 的 10 次、`kickOnFail`、并发模型不变 |
| `SyncCreateEnd` / `SyncPhysicalWorldEnd` | **无行为变化**（逐条校验/顺序/文案一致；分支写法改写但判定等价） |

### G —— 渲染与交互（4 类 / 4 变）

**★ 头条：第十二轮那套渲染结构在新基准下逐条不变。**

| 项 | 0.1.3 vs 0.3.0 |
|---|---|
| **反向 Z 投影** | **两版逐字节等价**：`MixinGameRenderer.space$getProjectionMatrixZ` → `setPerspective(fov, aspect, this.getDepthFar(), 0.05F)`（`:154-159`）。注意反向 Z 的 near/far 在**这里**，不在 `SpaceRenderer` 内 |
| `spaceRenderTarget` / `deepSpaceRenderTarget` | 建立条件（仅尺寸变化时重建 + 每帧 resize、space 目标 `enableStencil`、deep 不带）一致 |
| 清深度与 depthFunc | `_clearDepth(0.0)` + `_clear(256)`、`depthFunc 518 → 513` 一致 |
| near/deep 分类阈值 | `4.2949673E9F` 一致 |
| 合成链 | `BufferSwitch → SpaceDepthBlit → 主目标` 一致 |
| 阶段 | 未增减（`AFTER_SKY` 画、`AFTER_PARTICLES` 后处理）；`renderBehindEffects` **两版都是无调用点的死代码** |

> **一处要更正的说法**：补审报告里写"polymech 现用 `near = FAR*2`，而 space 用 `near = getDepthFar()`，
> 这是既有偏差"。**这是误读** —— space 的 `getDepthFar()` 被它**自己的 mixin**
> （`MixinGameRenderer.modifyDepthFar:55`）改成 `farCompressionDistance * 2.0 = 524288`，
> **和我们的 `RenderCompression.FAR * 2.0` 是同一个值**。我逐字核对过两处原文，没有偏差。
> （唯一出现别的数字的是 `FastSpaceRenderer` 里硬编码的 `528688.0F`，那是另一条"快"路径，见 §六备注。）

**真正的变化：体积云从"死代码"变成完整阶段**（本轮渲染侧最大改动）：
- 0.1.3：`PlanetCloud` 只配置 effect，**从不 process/blit** ⇒ 云**实际不渲染**。
- 0.3.0：新增 `PLANET_CLOUD` 开关与 `renderPlanetCloud`（`pickCloudPlanet` 选星球、
  `CloudPlanetIndex`/`CloudPlanetRotate`/`CloudSampler`、`PLANET_CLOUD_STEP_COUNT`、
  `PlanetCloud.process → PlanetCloudBlur → blit`），且**在大气之前**执行。
- 新增 `TransmittanceTarget`（颜色+掩码 MRT，RGBA16F / `GL_COLOR_ATTACHMENT1`）与 `cloudMaskSampler()`，
  后者被绑到 `StarBloom`/`StarGhost`/`StarScatter`/`PlanetBloom`/`PlanetAtmosphere`。
- 其它契约变化：`StarGhost` 删掉 `ColorSampler`/`StarburstSampler`、新增 `tModelViewMat`+`OutSize`；
  `GaussianBlur` 两处新增 `ScreenSize`；大气新增 `CloudDepthSampler`/`CloudMaskSampler`/
  `CloudDepthValid`/`CloudPlanetIndex`；`renderSkyBox` 显式 `activeTexture(0)`+`bindTexture` 后再设过滤；
  `renderSpace` 结尾把 0..3 纹理单元过滤强制设为 `NEAREST`；
  目标尺寸来源由 `window.getScreenWidth/Height` 改为 `mainRenderTarget.width/height`（`max(1,·)`，判零条件消失）。
- 新增配置键 `PLANET_CLOUD`、`PLANET_CLOUD_STEP_COUNT`；新增 UBO API `pickCloudPlanet`/`getLastPlanets`。

**`PhysicalBodyInteractionClient`：法杖选点包在 0.3.0 客户端被删了（疑似回归，别照抄）**
- 全仓检索确认 `PhysicalSelectionActionPacket` 在 0.3.0 只剩"类定义 + 注册 + 服务端 handler"，
  **没有任何发送点**（`PhysicalSelectionClient` 两版逐字节相同且从不发包）
  ⇒ **0.3.0 的法杖选点对服务器是空操作**，属重构未完成。
  **我们若要有可用行为就保留发包，不要跟这一条。**
- 其它：攻击包改为固定 `MAIN_HAND`（原 `event.getHand()`）；非攻击只在 `isUseItem()` 时发包；
  新增 `stopMining()` 但 `resetMiningFeedback()` 去掉 `notifyServer` 参数 ⇒
  "按住攻击键但 `screen != null` 或 level/player 为空"时，0.1.3 会发停止包、0.3.0 **只清本地状态不同步服务器**；
  `stopMining` 还去掉了 `getConnection() != null` 判空。

**`SpaceModDataPackManger`**
- 0.3.0 对 star/planet/black_hole 三种天体在 `putCelestialBody` 后追加
  `CelestialBodySyncManager.addCreate(new Create(WorldID, body))` —— **新增同步契约**，
  不照做的话客户端收不到"数据包定义的天体"。
- cloud 段新增可选 `bottom`/`top`（缺省回退 `cloudHeight`）并调用 `setCloudBottom`/`setCloudTop`（对应 §六 `Planet` 的两个新字段）。
- 其余（含 `pos_list.size()==4` 判 rotate、`size()==3` 判 speed 这两个**两版都有**的既有 bug）一致。

**`ClientEvents`**：仅资源重载 `apply` 由空实现改为调 `ScreenBlockEntityRenderer.clearTargets()`；
`onComputeCamera` 的 `0.025F` 阈值与 atan2 公式两版完全一致。


### H —— 轨道同步与线程（8 类 / 7 变）

| 类 | 变化 |
|---|---|
| `SpaceWorldUpdateSyncEvent` | 登录同步由"`EntityJoinLevelEvent` 里立即逐个发包"改为**入队 + `ServerTickEvent.Post` 每 tick 最多 flush 64 个**（新增 `enqueueLoginSync`）；`SyncCreateEnd` 改走 `PacketDistributor.sendToPlayer`（**失去可靠通道/ack**）；`bodyCount` 在玩家自身处于太空世界时**排除 Aircraft**；新增 `handleSyncCreateEnd` 四项校验 + 热栏报错 |
| `CelestialBodyMoveSyncEvent` | 钩子 `LevelTickEvent.Pre` → **`ServerTickEvent.Post`**；范围由"单个 level 解析的太空世界（含 SpaceWorldID 回退）"→ **遍历 `getAllSpaceWorld()` 全部太空世界**；Move 载荷新增 `spaceWorldId`（但 `SyncCelestialBodyMoveBatch` 线格式未变）；**每 tick 每队列最多 drain 512 条，超出静默丢弃**（>512 个天体就丢同步） |
| `PhysicalBodySpaceEvent` | Aircraft 识别由"每个物理体重扫天体列表 + `getPhysicalBody()` 无判空"改为**预建 `Set<UUID>` 并跳过空物理体**（消除 NPE，O(n·m)→O(n+m)）；Aircraft 创建包由**全服广播改为入队定向**，且玩家处于太空世界时**不发** Aircraft 创建包。阈值 `10.0`/增益 `100.0`、纠偏公式、跃迁参数**全部未变** |
| `SyncCelestialBodyCreate` | 线格式**逐字节一致**；`handle` 改为"仅当 `body==null \|\| spaceWorld!=null` 才处理" + 判空 + Aircraft 额外 `bindAircraft`；**ack 只在条件分支内发**（0.1.3 恒发；0.1.3 在无太空世界时 NPE） |
| `SyncCelestialBodyMoveBatch` | 新增 `MAX_ENTRIES=65536` 校验；编码格式与 `Entry` 结构未变 |
| `SyncCelestialWorldCreate` | 字段/编码**完全未变**；条件由 `bodyName != null && body instanceof Planet` 改为 **`bodyName == null \|\| body instanceof Planet`**，ack 只在条件成立时发。★ 关键差异：`bodyName` 非空但客户端查不到天体时，0.1.3 会 `init()` 并回 ack，**0.3.0 两者都不做** |
| `SyncSpaceWorldCreate` | **无行为差异**（仅 handle 搬迁，ack 由先发变后发） |
| `OrbitPhysicalThread` | **仅插桩**：`ThreadStepTimings.logCelestialTickNanos`（240 槽环形缓冲 + synchronized）。`core_tick_speed=100`、**`core_tick_time=0.72`**、`Tick_RunTime` 计算、`scheduleAtFixedRate`、单机暂停判定、`stopThread` 重置为 0.01 —— **全部一致** |

> ⚠ 注意 `OrbitPhysicalThread` 的 `core_tick_time = 0.72` 是**天体线程**的值；
> 碰撞线程是 `0.01`。两者别混（`docs/mps-clone-plan.md` 里已有一处旧记录被这个数误导过）。

**F+H 的共同结论**：0.3.0 把"同步"整体收拢到 `PhysicalBodySyncManager` /
`CelestialBodySyncManager`，并**普遍加上了队列、上限、迟到补偿与 ack 条件化**。
对我们的含义：如果我们跟 0.3.0 的网络契约，就要**重写同步层**（不是改几个字段）；
但**线格式本身不兼容**这一点是确定的（`aircraft` 位 + 多个新上限），跨版本互发必然解析错位。

