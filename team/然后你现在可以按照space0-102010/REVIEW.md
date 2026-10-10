# 评审（REVIEW）— spec-review 门禁（审查员 Sam）

> 审查对象 = SPEC / PLAN / TASKS / RESEARCH 四份**前置工件**（写代码前），不是已写好的业务代码。
> 引用约定：`<相对路径> · <锚点(函数/字段/唯一字符串)> @ <行号>`；finding 编号用跨轮稳定 `FIND-N` 形式。

## 结论

**verdict = `needs_revision`**（不是 pass，也不是 reject）

- 方向正确：0.5.0 目标结构（原版 `move+collide` + `collide` RETURN 注入 Java OBB 扫掠）对两个症状的根因判断成立；五份参考源码与 mps rapier helper 依赖**全部回源核实存在**（见 §5 反向推导）。
- 但存在 **3 项 HIGH**（C1/C2 无实现任务、onGround 维护口径造假、探针接线「先询问」与「直接做」冲突），且扫掠数据源选择与「照抄」纪律存在张力、驱动链残留文件未入 scope（会编译失败）。均为可修的口径/覆盖问题，不是方案性错误，故判 `needs_revision`。

---

## 1. 代码 vs 规格（改动点映射表是否覆盖 SPEC 全部验收项）

> 验收项 → 实现任务覆盖对照。映射表 = RESEARCH §4（12 项）+ TASKS.json inScope。

| SPEC 验收项 | 覆盖任务 | 覆盖否 |
|---|---|---|
| A1 高速防隧穿 | P1（`PhysicalEntityCollisionProbe`）| ✅ |
| A2 已穿入可推出 | P1 | ✅（夹具需满足 FIND-10 的位移方向约束）|
| A3 载速探针 | P3（`PhysicalEntityProbeTest`）| ✅ |
| B1 编译 + HEAD cancel 移除 | P2 | ✅ |
| **C1 不穿模（游戏内日志判据）** | **无任何任务产出诊断日志** | ❌ |
| **C2 不卡住（游戏内日志判据）** | **无任何任务产出诊断日志** | ❌ |
| N1 禁止自创近似 | 纪律，无独立验证任务 | ⚠️ |
| N2 禁止局部打补丁 | 纪律，无独立验证任务 | ⚠️ |
| N3 不保留双刚体 | P2（`grep new PlayerPhysicsBody` 零命中）| ✅ |
| N4 不再取消 move | P2（删 `EntityPhysicsDriveMixin`）| ✅ |
| N5 不动回滚点 | P4（git status 干净）| ✅ |
| N6 不动服务端三件套 | P4（服务端 git diff 零改动）| ✅ |
| N7 补原生不 Java 仿真 | 纪律，无独立验证任务 | ⚠️ |
| N8 探针不进 build/ | P1/P3 inScope 落点 `native/jni-smoketest/` | ✅ |

**结论**：C1/C2 是唯一「验收标准写了、但全链路没有任何任务去实现它」的缺口（见 FIND-1）。

---

## 2. 规格 vs 自身一致性（SPEC ↔ PLAN ↔ TASKS 口径对齐）

| # | 事实 | SPEC | PLAN | TASKS | 漂移 |
|---|---|---|---|---|---|
| D1 | 探针接线点（R4 歧义）| 安全边界「先询问用户拍板」@124 | R4「升级用户拍板，不自行猜」@160 | P3 直接承诺接线 @45 | ❌ 漂移（FIND-3）|
| D2 | onGround 维护归属 | 决策④@40 / R10@78「由 probe.lastGrounded 维护」| R6@162 同 | — | ❌ 造假（FIND-2）|
| D3 | mixin 数量 | 「新增 MixinEntity.collide RETURN 注入」@12（单数）| 拆 `EntityPhysicsCollideMixin`+`EntityPhysicsProbeMixin`@34-35 | 同 PLAN | ⚠️ 弱漂移（FIND-7）|
| D4 | ColliderAccess 接口形态 | 照抄参考（2 getter）| 多一个 `space$setPhysicalEntityProbe` setter@32 | 同 PLAN | ⚠️ 弱漂移（FIND-7）|
| D5 | overlap 分支「不返回 null」| R6@74 / A2@50 声称不返回 null | — | — | ❌ 过度声称（FIND-10）|
| D6 | collide 注入生效条件 | 决策②/R8 | 契约 `LocalPlayer && !isSpectator && !noPhysics`@97 | 同 | ✅ 一致 |
| D7 | 载速 `÷20` | R7@75 | `velocity()/20`@93 | `velocity()/20`@46 | ✅ 一致 |

---

## 3. 契约 vs 实现可行性（PLAN JSON Schema 能否被 implement 落地）

> 已逐项回源核实「方法/字段在代码里是否存在」。结论：**P3 探针的 mps rapier helper 依赖全部存在**（RESEARCH R8 的结论成立，两处我最初怀疑缺失的点被撤销，见 §5）；**真正的落地风险在「扫掠数据源 ClientBody 重写」与「SpaceViewRotation 缺等价物」两处**。

| 契约声明 | 回源结果 | 判定 |
|---|---|---|
| `PhysicalEntityProbe.bind` 依赖 `addRigidBody/addColliderBody/addTickListener/setCollisionGroups/setFrictionCombineRule/setRestitution/setCCD` | `mps/rapier/helper/RapierWorld.java`@133/291/117、`ColliderBody.java`@328/274/286、`RigidBody.java`@435 全部存在 | ✅ 可行 |
| `onPhysicsStep` 用 `world.tick%5` / `getTickTime` / `getG` / `getLinvel` / `setPos` / `setRotation` / `setAngvel` / `setLinvel` | `RapierWorld.java`@44 `public long tick` / @361 / @342；`RigidBody.java`@327/302/320/367/338 | ✅ 可行 |
| `RigidBody(Type.DYNAMIC, …, 50.0, …, gravityScale=1.0, lockRotations=true)` 12 参构造 | `RigidBody.java`@102 精确匹配 | ✅ 可行 |
| `ColliderBody(Type.CUBOID, …, 0.02, hw, hh, hw)` 变参构造 | `ColliderBody.java`@111 varargs | ✅ 可行 |
| 扫掠数据源 = `ClientPhysicsWorld.ClientBody`（主动栈）| 参考 `snapshotBodies` 迭代的是 `PhysicalWorld.getAllPhysicalBody()` → mps `PhysicalBody`；PLAN 契约@64-65 的 `BodySnapshot{position,rotation,worldBounds,blockCollision谓词}` 与参考 `BodySnapshot`（持 `PhysicalBody physicalBody` 字段 + 调 `getBlockStateInt`@245）**结构不符** | ❌ 需重写（FIND-4）|
| `blockCollision(x,y,z)` 谓词 | `ClientBody.blocks()` 是 `List<BlockEntry>`@36（线性结构），参考 `getBlockStateInt` 是 O(1)；契约未规定建索引 | ❌ 缺索引规格（FIND-5）|
| `ClientBody.worldAABB()`（P2 新增）| 现在**不存在**（P2 才加），已正确标注「新增」；但参考读的是 `PhysicalBody.worldAABB`（字段）+ `isValidAABB`@382，PLAN 未规定「AABB 无效→extent=64 兜底」在 ClientBody 侧的等价实现 | ⚠️ 欠规格（SIL-10）|
| mixin 的 `rotation` 来源 `SpaceViewRotation.isActive/orientation` | 全仓库 grep `SpaceViewRotation` **零命中**，无等价物点名 | ❌ 缺等价物（FIND-6）|
| `space$syncProbe`/`space$addProbeVelocity` 接线点 | 参考二者为 `@Unique private` 且**本文件内无调用**；TASKS P3 自创接线 | ❌ 无参考依据（FIND-3）|

---

## 4. 沉默清单（必产出，最重要）

> 规格未规定、但实现或交互上可选的行为。逐条给「建议裁定（允许/禁止）+ 依据 + 风险」，**交 PM/用户裁定**。沉默不得作为通过理由。

| 编号 | 沉默项 | 建议裁定 | 依据 | 风险 |
|---|---|---|---|---|
| SIL-1 | 探针接线点（`space$syncProbe`/`space$addProbeVelocity` 在哪调、调几次）| **禁止自行猜**，升级用户拍板 | 参考无调用点（`MixinEntity.java`@69/75 私有未调用）；SPEC「先询问」已列 | 接线错位 → 载速叠加进错误 movement / 重复叠加 / 被 collide 二次夹 |
| SIL-2 | 扫掠数据源 = 主动栈 `ClientBody` vs mps `PhysicalBody` | **默认 ClientBody（lead 已拍板），但需用户确认接受与 N1 照抄的张力**；未确认前禁止擅自切换 | 参考照抄路径是 mps `PhysicalBody`（`PhysicalBody.java`@550/184/188/97 全在），SPEC 假设@137 却选 ClientBody 映射 | 选错 → 重写 snapshotBodies/BodySnapshot；或 mps 客户端世界无船数据 → 扫掠空转 |
| SIL-3 | `blockCollision` 谓词的索引结构（List 线性扫 vs 快照建 HashSet/HashMap）| **允许**（实现细节），**禁止**每体素线性扫 List | 参考 `getBlockStateInt` O(1)；`blocks()` 是 List | 不建索引 → 128³ 体素 × N 方块 → 客户端卡顿 |
| SIL-4 | 单 mixin vs 双 mixin 拆分 | **允许**双 mixin（服务文件单写者），但 probe/collider 字段持有者必须唯一并登记进 AUTHORITY 文件单写者 | 参考单 `MixinEntity`@24-29 持两字段 | 两 mixin 各持 `@Unique` 字段 + 接口 setter → 字段归属冲突 |
| SIL-5 | `SpaceViewRotation` 等价物选取（我方 6DOF 旋转注入点）| **先询问用户**，禁止实现者自选等价物 | 参考依赖上游 `SpaceViewRotation`@62，我方无同名类；SPEC 假设@138 未点名 | rotation 来源错 → 6DOF 下玩家盒用错 OBB 轴 |
| SIL-6 | 载速 `÷20` 的 tick 语义假设 | **允许照抄 `÷20`**，但需确认探针世界 `tickTime=0.01`（`RapierWorld.java`@43）且「÷20=m/s → m/20Hz tick」折算成立 | PLAN@132「÷20 与 20Hz tick 对齐」 | 折算基准错 → 载速量级错 |
| SIL-7 | 载速叠加时机（原版 move 输入前 vs collide RETURN 后）| **禁止未定义处叠加**，需明确叠加点是「move 输入」而非「collide 后位移」 | 参考 `space$addProbeVelocity`@69 语义是「加到 movement 输入」 | 叠加进已夹位移 → 载速被二次夹/被扫掠吃掉 |
| SIL-8 | 已穿入 A2 夹具的位移方向（overlap 分支触发条件）| **允许但必须规定夹具位移含朝重叠法向的分量**，否则 FAIL | 参考@235-236 `movement.dot(overlapNormal) >= -1e-10` 时返回 null（FIND-10）| 夹具无该分量 → A2 误判 FAIL |
| SIL-9 | 探针碰撞组 `(2,5)` 与玩家组 `(2,4)` 的语义差 | **允许照抄 `(2,5)`**，但需登记「5=地形1\|物理体4」语义，禁止误用玩家组的 `(2,4)` | 参考 bind@50 `setCollisionGroups(2,5)`；mps `PhysicalWorld.java`@127 addPlayerCollider 用 `(2,4)` | 误用 `(2,4)` → 探针不碰地形 → 行星地面测不到载速 |
| SIL-10 | 「AABB 无效→extent=64 兜底」在 ClientBody 侧的等价判据 | **允许照抄 extent=64 兜底**，但需明确 ClientBody 侧 `isValidAABB` 等价实现 | 参考@277-287 + `PhysicalBody.isValidAABB`@382；SPEC 边界@117 | 漏兜底 → 旋转体 AABB 无效时漏检穿模 |

**沉默清单条数 = 10**。

---

## 5. 反向推导（逐条回源核实 + 撤销率）

> 每条 finding 均已打开被指文件核对存在性与行号。草稿期共自报 **15** 条（13 条确认 + 2 条撤销），撤销率 = **2/15 ≈ 13.3%**。

### 5.1 确认的 finding（13 条，均已回源）

- **FIND-1（HIGH）** C1/C2 无实现任务。`TASKS.json` P4 acceptance@62 宣称「三件套 A/B/C 全 PASS」，但 P1–P4 无任何任务产出「诊断日志记录玩家盒到物理体 AABB 最小带符号距离」；参考 `MixinEntity.java`@25 `PHYSICS_OUTPUT_ENABLED=false` 无任何日志调用。→ C 验收是「无生产者」的验收项。
- **FIND-2（HIGH）** onGround 维护口径造假。`SPEC.md` 决策④@40/R10@78、`PLAN.md` R6@162 称「onGround 由 probe.lastGrounded 维护」；但参考 `PhysicalEntityProbe.java`@63 `lastGrounded = entity.onGround()`（**采样**），@77 仅内部读，从无回写 entity.onGround/fallDistance。真正维护者是原版 collide（交回原版后天然有）。
- **FIND-3（HIGH）** 探针接线「先询问 vs 直接做」冲突。`SPEC.md`@124「先询问」、`PLAN.md` R4@160「升级用户拍板」；`TASKS.json` P3@45 已直接承诺接线，无「先询问」门。参考 `MixinEntity.java`@69/75 两方法 `@Unique private` 且文件内无调用。
- **FIND-4（HIGH）** 数据源 ClientBody 与「照抄」张力。参考 `PhysicalEntityCollision.java`@93-104 snapshotBodies 迭代 mps `PhysicalBody`，@255 BodySnapshot 持 `PhysicalBody` 字段调 `getBlockStateInt`@245；PLAN 契约@64-65 改写成 `{position,rotation,worldBounds,blockCollision谓词}`，与参考结构不符 → 无法逐字照抄，需重写。
- **FIND-5（MEDIUM）** blockCollision 无索引。`ClientPhysicsWorld.java`@36 `List<BlockEntry> blocks`（线性）；参考 `getBlockStateInt` O(1) 三重循环@160-176。契约@73 未规定建索引 → 性能风险。
- **FIND-6（MEDIUM）** SpaceViewRotation 无等价物。grep 全仓 `SpaceViewRotation` 零命中；参考 `MixinEntity.java`@62 依赖 `isActive/orientation`。
- **FIND-7（MEDIUM）** 双 mixin 拆分 + setter 新增偏离参考。参考单 `MixinEntity`@24-29 持两字段 + 接口 2 getter@3-7；PLAN 拆两 mixin 且接口多 `space$setPhysicalEntityProbe`。
- **FIND-8（MEDIUM）** 驱动链残留文件未入 scope。`ClientPhysicsDriver.java`@29 调 `ClientPhysics.afterPlayerTick()`（P2 将删除），但 `TASKS.json` P2 inScope@27-35 无此文件 → 编译失败；`PhysicsGroundProbe.java`@17 仅被 `ClientPhysics.java`@905 用（drive 链）；`PhysicsDrivenPlayers.java` 被 `PhysicsWorldManager.java`@100 引用（N6 说服务端不动 → 删它即触 N6）。
- **FIND-9（MEDIUM）** PhysicalEntityCollider 依赖 Rapier ColliderBody，PLAN 模块边界「仅 Entity+joml」不实。参考 `PhysicalEntityCollider.java`@18 `new ColliderBody(...)`、@85 `EntityShape` 持 `ColliderBody collider` 字段；PLAN@31 写「无（仅 Entity + joml）」，与 JSON@77「collider 仅探针 attach 用」自相矛盾。
- **FIND-10（MEDIUM）** SPEC R6/A2「overlap 不返回 null」过度声称。参考 `PhysicalEntityCollision.java`@235-236 在 `movement.dot(overlapNormal) >= -1e-10` 时返回 **null**。
- **FIND-11（LOW）** bind 契约漏 `setRestitutionCombineRule(1)`。参考 `PhysicalEntityProbe.java`@54 有，PLAN 契约@88 漏。
- **FIND-12（LOW）** ClientBody 无维度标识，维度匹配检查缺规格。参考 `PhysicalEntityCollision.java`@83-88 用 `getLevel().equals(dimension)`；`ClientPhysicsWorld.java`@19-31 无 level 字段。
- **FIND-13（LOW）** 参考死常量 `PROBE_MASS/PROBE_FRICTION` 未用于构造（@27 硬编码 50.0、@51 硬编码 100.0）。非阻塞。

### 5.2 撤销的 finding（2 条，计入撤销率）

- **撤销-1**：草稿「`PhysicalWorld` 缺 `tick` 字段 → probe `currentWorld.tick%5` 无法编译」。回源 `RapierWorld.java`@44 `public long tick;` **存在** → 撤销。
- **撤销-2**：草稿「`PhysicalWorld` 缺 `addTickListener/removeTickListener/getTickTime/getG/hasColliderBody/removeColliderBody/hasRigidBody/removeRigidBody` → probe bind/release 无法编译」。回源 `RapierWorld.java`@117/123/361/342/315/298/151/142 **全存在** → 撤销。

---

## 6. 交付三件套

- **改了哪些文件（全路径）**：仅本评审文件
  `C:/modIDEA/polymech/polymech-template-1.21.1/team/然后你现在可以按照space0-102010/REVIEW.md`（由占位模板覆盖为完整评审）。
- **关键行号**：结论在 §结论；四道验证 §1–§4；沉默清单 §4（SIL-1..SIL-10）；反向推导 + 撤销率 §5；finding 索引 FIND-1..FIND-13 + 撤销-1/2。
- **原始输出（运行）**：无（本任务为 spec-review，无编译/探针运行）。证据来源为 `grep`/`read` 对以下文件的回源读取：
  `decompiled-space/0.5.0/.../PhysicalEntityCollision.java`、`PhysicalEntityCollider.java`、`PhysicalEntityProbe.java`、`PhysicalEntityColliderAccess.java`、`mixin/MixinEntity.java`；
  `src/main/java/com/mss/polymech/{client/physics/{ClientPhysicsWorld,ClientPhysics,ClientPhysicsDriver,PhysicsBodyRenderer}.java, physics/{PlayerPhysicsBody,PhysicsClientHooks,PhysicsAabbSolver,PhysicsGroundProbe,PhysicsDrivenPlayers}.java, mixin/{EntityPhysicsDriveMixin,EntityPhysicsMoveMixin,SpaceFlightMixin}.java, mps/rapier/helper/{RigidBody,ColliderBody,RapierWorld}.java, mps/physical/physical_world/{PhysicalWorld,ClientPhysicalWorld}.java, mps/physical/physical_body/PhysicalBody.java}`；
  `src/main/resources/poly_mech.mixins.json`。

---

## 7. 给 PM/用户的裁定请求（汇总）

1. **C1/C2 怎么办**（FIND-1）：要么补一个「诊断日志」实现任务（但违背 N1 照抄、且参考无此日志），要么把 C1/C2 从「验收项」降级/改为「A 探针等价覆盖」。二选一，需拍板。
2. **onGround 口径**（FIND-2）：确认「onGround 由原版 collide 维护，探针只采样」，删除 SPEC 决策④/R10、PLAN R6 的「probe 维护」表述。
3. **探针接线点**（FIND-3 / SIL-1 / SIL-7）：确认是否「先询问接线位置」，还是接受 TASKS P3 的直接接线方案。
4. **扫掠数据源**（FIND-4 / SIL-2）：确认接受 ClientBody（重写 snapshotBodies，弃逐字照抄），或切 mps `PhysicalBody`（照抄更贴、但需确认 mps 客户端世界有船数据）。
5. **SpaceViewRotation 等价物**（FIND-6 / SIL-5）：点名我方 6DOF 旋转的注入点/等价类。
