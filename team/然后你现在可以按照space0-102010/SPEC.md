# 规格说明（SPEC / Ultra Spec）

> pm（Jason）填写。本 Spec 是唯一权威需求文档；实现者只按本文与 `PLAN.md` 契约落地，不另造近似实现。
> 代码引用一律 `相对路径 · 锚点(函数/字段/唯一字符串) @ 行号`，锚点是主证据、行号是阅读辅助。

**一句话目标**：把「玩家与物理体（飞船/建筑）的碰撞」从 space **0.1.3** 的「Rapier 双刚体驱动玩家 + HEAD cancel 原版移动」结构，升级为 space **0.5.0** 的「原版 `Entity.move`+`collide` 正常跑 + `collide` RETURN 注入 Java 侧 OBB 扫掠解析」结构，根治两个症状：**(1) 玩家穿进物理体（船）**；**(2) 穿进去后卡住出不来**（同一根因的两阶段）。

## 功能目标

### 目标（本任务唯一交付物）
- 玩家移动权威交回**原版**：撤销 `EntityPhysicsDriveMixin` 的 `Entity.move` HEAD cancel，原版 `Entity.move` + `collide` 完整运行（原版天然防穿模）。
- 新增 `MixinEntity.collide` **RETURN 注入**，在原版碰撞结算完成后追加 `PhysicalEntityCollision.resolve()`：仅对「**物理体方块**」（原版看不到、无对应世界方块的那些体素）做 Java 侧 OBB 扫掠解析，把原版已经放行的位移再夹一遍。
- 以 `PhysicalEntityCollider`（玩家碰撞形状 `EntityShape`）+ `PhysicalEntityProbe`（载速探针）取代旧 `PlayerPhysicsBody` 双刚体。

### 存量视角（影响哪些已有模块 / 复用哪些组件）
| 现有模块 | 处置 | 依据 |
|---|---|---|
| `ClientPhysics.java`（1392 行） | `drive()`/`ensurePlayerBody()`/`afterPlayerTick()` 的「玩家刚体驱动」链路退场；`syncShips()`/`stepShipSync()`/`updateTerrain()` 的「物理体镜像 + 地形」保留为扫掠解析的数据源 | 见下「结构对照」 |
| `PlayerPhysicsBody.java`（587 行）双刚体 | **全量退场**（0.5.0 全仓库无 `new PhysicalEntity` 调用） | 参考 `MixinEntity.java` 只建 `PhysicalEntityCollider`+`PhysicalEntityProbe` |
| `EntityPhysicsDriveMixin.java`（HEAD cancel） | **撤销** HEAD cancel | 0.5.0 不取消 `Entity.move` |
| `EntityPhysicsMoveMixin.java`（服务端 move cancel） | **保持不动**（服务端物理体实体仍由 `PhysicsEntityManager` 驱动，与本次「玩家↔物理体」无关） | 服务端是物理体一侧，非玩家一侧 |
| `PhysicsAabbSolver.java`（79 行，死代码，无调用者） | **删除** | 是 0.1.3 时代的自创近似实现，被 0.5.0 的 `PhysicalEntityCollision` 取代 |
| `ClientPhysicsWorld.java` 的 `ClientBody` | **保留**，作为扫掠解析读取的「客户端物理体快照」数据源 | 扫掠解析需要体素方块集 + 位置 + 旋转 |
| 服务端 `PhysicsBodyTracker` / `ProjectionManager` / `PhysicsWorldManager` | **保持不动**（物理体之间碰撞 / 投影均不在本次范围） | 决策⑤（范围收窄） |

### 结构对照（为什么 0.5.0 能根治）
| 维度 | 0.1.3（现状，要改掉） | 0.5.0（目标，照抄） | 为什么根治 |
|---|---|---|---|
| 玩家移动权威 | Rapier 双刚体（CCD 关）单子步推进，位置从刚体读回 | 原版 `Entity.move`+`collide`，位置由原版结算 | 原版 `collide()` 在同一步内把位置夹到方块表面、被挡轴清零，**从不穿透** |
| 防穿模手段 | Rapier 离散碰撞 + CCD 关 → 高速单子步隧穿 | 分段扫掠（`MAX_SEGMENT_LENGTH=2.0`，最多 `MAX_SWEEP_SEGMENTS=128` 段）+ sweep-and-slide + SAT 15 分离轴逐体素方块扫掠 | 分段把大位移切成 ≤2 格的小段，逐段扫掠，消除隧穿 |
| 卡住成因 | 穿入后被求解器顶住，速度链 `main=sibling+own` 对消 → 卡死 | 无刚体约束；`sweepBoxAgainstBlock` 的 overlap 分支（`entryTime<0` → `SweepHit(0, overlapNormal)`）主动把已穿入盒沿最浅穿透轴推出，滑移分量继续沿切面走 | 穿入是可恢复的（推出 + 滑移），不再对消卡死 |
| 载速测量 | 兄弟刚体 `sibling.linvel` | 独立 DYNAMIC 探针刚体 `PhysicalEntityProbe.velocity()`，`÷20` 加到 movement | 保留「被移动的船带着走」，但不参与玩家位置求解 |

### 七项技术决策（用户未指定 → 按建议默认值走，**留痕可覆盖**）
| # | 决策点 | 建议默认值 | 依据（0.5.0 参考） |
|---|---|---|---|
| ① | 玩家碰撞箱形状 | **直立**（`rotation=null` → 单位四元数），不跟 6DOF 身体转 | `PhysicalEntityCollider.at` @49-54：`rotation == null ? 单位四元数`；`MixinEntity.collide` @62 只在 `SpaceViewRotation.isActive` 时传旋转 |
| ② | 创造/旁观飞行 | 创造飞行交回原版（已有 `SpaceFlightMixin`）；旁观者穿透（不参与碰撞） | `MixinEntity.collide` @59-60：`isSpectator()`/`noPhysics` 跳过 |
| ③ | 行星 vs 太空 | 行星有原版方块走原版 `collide`；太空虚空全凭扫掠解析；两者同一套扫掠兜底 | `resolve` @37：只在客户端、有物理体时夹，原版碰撞先行 |
| ④ | onGround/fallDistance | 由**原版 collide 维护**（交回原版后原版天然维护）；`PhysicalEntityProbe` 只**采样** `entity.onGround()` 到 `lastGrounded`（测载速时决定是否减重力分量），**从不回写** `entity.onGround`/`fallDistance` | `PhysicalEntityProbe.sync` @61-64：`lastGrounded = entity.onGround()` |
| ⑤ | 物理体之间碰撞 | **不动**（范围收窄，本次只改「玩家↔物理体」） | 决策范围 |
| ⑥ | 双刚体 `PlayerPhysicsBody` | **退场**，被 `PhysicalEntityCollider`+`PhysicalEntityProbe` 取代 | 0.5.0 全仓库无 `new PhysicalEntity` 调用 |
| ⑦ | 探针接线位置（`space$syncProbe`/`space$addProbeVelocity` 在哪调、调几次） | **进确认门让用户拍板**；建议默认值=载速在 move 输入前叠加（travel HEAD 注入） | 反编译未体现接线 → 确认门裁决点（见 PLAN 风险 R4） |

## 验收标准

> A/B 两件套机器判定 `PASS`/`FAIL`，C 为端到端取证（辅助，不阻塞交付）。

### A. 离线探针（无游戏，纯 Java + native）
- [ ] A1 **高速防隧穿**：给定玩家盒（`halfWidth=0.3, halfHeight=0.9, eyeHeight=1.62`）+ 旋转物理体方块集 + 大位移（单 tick 位移 `0.5 ~ 2.0 格`），断言 `PhysicalEntityCollision.resolve()` 返回的 `resolved` 位置**不进入**任一物理体方块的 OBB 内部（穿透深度 `< 1e-3 格`）。判定：打印 `PASS/FAIL`，非零退出码。
- [ ] A2 **已穿入可推出（不嵌卡）**：给定玩家盒初始与物理体方块**重叠**（模拟已穿入），且夹具位移**含朝重叠法向的分量**（`movement.dot(overlapNormal) < -1e-10`），断言扫掠的 overlap 分支返回 `SweepHit(0, overlapNormal)` 且 `resolved` 把盒推到方块表面之外（重叠深度归零），且沿切面的滑移分量 `> 0`（可自由滑动脱离）。若位移不含朝法向分量则返回 `null`，属**正常行为而非 FAIL**。判定：`PASS/FAIL` + 非零退出码。
- [ ] A3 **载速探针**：`PhysicalEntityProbe` 在静止地面 `velocity() == (0,0,0)`；在匀速移动平台（如 `+v` 沿 X）上 `velocity() ≈ (v,0,0)`（误差 `< 1e-3`）。判定：`PASS/FAIL` + 非零退出码。
- 探针落点建议：`native/jni-smoketest/`（沿用 `PlayerWallProbeTest` 的同款离线夹具范式），或纯 Java `com.mss.polymech` 下新类；**禁止把探针放进 `build/`**（历史教训 §31.22）。

### B. 编译
- [ ] B1 `./gradlew compileJava` 输出 `BUILD SUCCESSFUL`，且 `EntityPhysicsDriveMixin` 的 HEAD cancel 已移除、`MixinEntity.collide` RETURN 注入已生效（以编译产物 + 源码 grep 双判）。

### C. 端到端实机取证（辅助 · 不阻塞交付 · 不计入机器判定 PASS/FAIL）
- 核心防穿模/不嵌卡判据以 **A1/A2 离线探针为准**；本节仅**复用项目既有诊断日志**（如 `[玩家物理诊断]`）做端到端取证，**不新增诊断日志生产者**（参考 0.5.0 `PHYSICS_OUTPUT_ENABLED=false` 无日志）。
- 取证方式：用户重启游戏后人工确认「走/冲/被弹向船不穿模」「持续顶住船 3 秒后松开可自由脱离」；不阻塞交付。

> 判定方式总体口径：**离线探针（A）+ 编译（B）**机器判定 `PASS/FAIL`；**端到端实机取证（C）为辅助，不阻塞交付**。

## 业务规则

> 每条对应具体「输入 → 输出」；把存量系统的隐性规则显式写出，不靠 AI 猜。

| 规则 | 输入 | 输出/行为 |
|---|---|---|
| R1 权威唯一 | 客户端玩家在太空维度、有物理体存在 | 玩家位置**只**由原版 `Entity.move`+`collide` + `collide` RETURN 注入的扫掠解析裁定；不再有第二个位置权威（删双刚体读回） |
| R2 只挡物理体方块 | 扫掠解析的体素方块集 | **只**对 `hasCollision`（方块有碰撞形状）的体素做 SAT；原版看得见的方块已由原版 `collide` 处理，扫掠不重复夹 |
| R3 分段 | 单 tick 位移 `requested` | `segmentCount = clamp(ceil(max(|x|,|y|,|z|)/2.0), 1, 128)`；每段 ≤2 格 |
| R4 扫掠+滑移 | 每段位移 | `sweepAndSlide`：最多 4 次迭代，找最早命中 `findEarliestHit`，沿法向推进、残余沿切面滑移（`remaining -= (remaining·n)·n`） |
| R5 分离轴 | 玩家 OBB × 物理体方块 OBB | 15 轴 SAT（3 世界轴 + 3 体轴 + 9 叉积轴）；分离即无碰撞 |
| R6 已穿入恢复 | `entryTime < 0`（盒已与方块重叠） | 返回 `SweepHit(0, overlapNormal)`，沿最浅穿透轴推出；但当 `movement.dot(overlapNormal) >= -1e-10`（玩家没有朝重叠法向移动）时返回 **null**，属正常行为而非卡死 |
| R7 载速 | 玩家站在移动物理体上 | `PhysicalEntityProbe.velocity() ÷ 20` 加到 movement（替代旧 `sibling.linvel`） |
| R8 旁观/创造 | 玩家 `isSpectator()` 或 `noPhysics` | **不参与**扫掠解析，原样返回原版位移（穿透物理体）；创造飞行交回原版（已有 `SpaceFlightMixin`） |
| R9 行星 vs 太空 | 玩家所在维度 | 行星（有原版方块）走原版 `collide`；太空虚空全凭扫掠解析；**两者同一套扫掠兜底**（决策③） |
| R10 onGround/fallDistance | 原版 collide 步进结果 | 由**原版 collide 维护**（交回原版后原版天然维护）；`PhysicalEntityProbe` 只**采样** `entity.onGround()` 到 `lastGrounded`（测载速时决定是否减重力分量），**从不回写** `entity.onGround`/`fallDistance` |

## 边界与禁止项（强制 · 沉默 ≠ 允许）

> **规格没写的行为，默认视为「允许」并会被实现出来** —— 这是本包头号返工源。每条边界写成「**禁止什么 → 期望拒绝/行为 → 如何验收**」；**没有拒绝码的边界视为未定义**。
> 本项目是**游戏 mod 碰撞重构、非 CRUD**，故「边界十问」逐条适配如下（显式标注）。

### 边界十问适配表（非 CRUD 域逐条标注）
| 边界族 | 规则（禁止什么） | 期望拒绝/行为 | 验收方式 |
|---|---|---|---|
| 自反关系 | **不适用（非 CRUD 域）**——无「自己回复/关注自己」概念 | — | — |
| 归属 / 跨父级 | **不适用（非 CRUD 域）**——无父子资源归属 | — | — |
| 终态不可变 | **不适用（非 CRUD 域）** | — | — |
| **越权（适配）** | **服务端权威与客户端扫掠解析的并发边界**：客户端扫掠解析**不得**写服务端物理体状态；玩家位置唯一权威=客户端原版移动+扫掠，服务端位置以客户端上报为准（`ServerPacketClampMixin` 恒等夹持），客户端扫掠**不得**反向越权改动服务端刚体 | 客户端只读物理体快照，不写服务端；服务端仍以 `PhysicsBodyTracker` 为准 | 代码审查：`PhysicalEntityCollision` 只 `read` 快照；`grep` 确认无客户端写服务端世界 |
| 幂等 | **不适用（非 CRUD 域）**——无重复提交/计数 | — | — |
| 基数上限 | **不适用（非 CRUD 域）** | — | — |
| 级联与计数 | **不适用（非 CRUD 域）** | — | — |
| **并发同键（适配）** | **物理步进线程 vs 主线程的 Rapier 世界访问必须串行**：步进线程（100Hz）与主线程（渲染/移动）对同一 Rapier 世界的访问必须经现有 `with_world` 互斥；扫掠解析**读的是客户端物理体快照**，不得在步进中途读半改状态 | 世界单写者；扫掠读快照（`snapshotBodies` 一次性拷贝 position+rotation+体素），与服务端 20Hz 同步包的关系 = **只消费已落盘的 `ClientBody` 快照，不锁步进线程** | 代码审查：`resolve` 不直接调原生读；`snapshotBodies` 拷贝语义；`with_world` 互斥存在 |
| 权限升降级 | **不适用（非 CRUD 域）** | — | — |
| 可见性 | **不适用（非 CRUD 域）** | — | — |

### 禁止项（强制 · 违反即 FAIL）
| # | 禁止什么 | 期望行为 | 验收方式 |
|---|---|---|---|
| N1 | **禁止自创近似实现**（如沿用/复活 `PhysicsAabbSolver` 的「沿最浅穿透轴推出」旧逻辑） | 一律照抄 `decompiled-space/0.5.0` 参考 | 逐文件 diff 对照 + 审查 |
| N2 | **禁止局部打补丁**（如只给双刚体开 CCD、只调 `contact_softness`/`num_solver_iterations`） | 结构升级，不调参糊弄（第 23 节已证调参无效） | 审查：无求解器旋钮改动 |
| N3 | **禁止保留双刚体** `PlayerPhysicsBody` 及其 `drive()`/`afterStep()` 链路 | 双刚体**全退场** | `grep "new PlayerPhysicsBody"` 零命中；`afterStep` 无调用 |
| N4 | **禁止再取消 `Entity.move`**（HEAD cancel 必须移除） | 原版 `move`+`collide` 完整跑 | `EntityPhysicsDriveMixin` 无 `cancellable=true` cancel 逻辑 |
| N5 | **禁止改动回滚点** `git commit 9981c51`，且实现阶段前**禁止额外提交** | 仅实现完成后统一提交 | 实现阶段 git status 干净（不含意外提交） |
| N6 | **禁止改动物理体之间碰撞**、服务端 `PhysicsBodyTracker`/`ProjectionManager`/`PhysicsWorldManager` 主体 | 本次只改「玩家↔物理体」（决策⑤） | 审查：服务端文件零改动 |
| N7 | **禁止在 Java 侧发明等价仿真替代原生缺口** | 遇原生缺口**补原生**（ABI 先例 `bodySetPose`），不在 Java 绕 | 审查 |
| N8 | **禁止把探针/证据放进 `build/`** | 探针进 `native/jni-smoketest/` 或 `com.mss.polymech` 源码树 | 审查 |

## 边界 Case

- **空/非法输入**：`requestedMovement == null` 或 `lengthSqr < 1e-10` → 原样返回（`PhysicalEntityCollision.resolve` @34-40）；`entity == null` → 原样返回；`!level.isClientSide`（服务端实体）→ 原样返回。
- **非客户端维度**：`clientPhysicalWorld()` 返回 null（维度不匹配/未就绪）→ 原样返回，不夹。
- **碰撞箱尺寸变化**：`collider.fits(entity)` 为 false（如超人姿态 `getBbHeight()` 变化）→ 重建 collider 并 `close()` 旧 probe。
- **物理体旋转**：`BodySnapshot.create` 处理旋转体（`rotation` 归一 + 15 轴含体轴/叉积轴）；`rotation.lengthSqr < 1e-10` 的退化四元数 → 快照判 null，跳过该体。
- **物理体 AABB 无效**：`PhysicalBody.isValidAABB` false → 用 `extent=64` 兜底 bounds，不漏检。
- **并发/竞态**：步进线程 vs 主线程——见「边界十问」的「并发同键（适配）」；扫掠只读快照，不锁步进。
- **历史脏数据/向前兼容**：旧存档物理体（0.1.3 尺度）在扫掠解析下仍按当前 `ClientBody` 快照消费，不迁移存档（本次不碰存档格式）。

## 安全边界（三级权限）

- **必须做**：照抄 `decompiled-space/0.5.0` 逐文件对齐；撤销 HEAD cancel；`collide` RETURN 注入；离线探针 PASS；编译 BUILD SUCCESSFUL。
- **先询问**（遇到再抛用户拍板）：七项决策若与建议默认值冲突；「探针接线位置（`space$syncProbe`/`space$addProbeVelocity` 在哪调、调几次）」这类**反编译未体现接线**的歧义 → **进确认门让用户拍板**（决策⑦，建议默认值=载速在 move 输入前叠加/travel HEAD 注入），非模糊「先询问」（见 PLAN 风险 R4）。
- **严禁做**：动回滚点 `9981c51` 前的实现阶段提交；动物理体间碰撞/服务端三件套；自创近似实现；局部打补丁；把探针放 `build/`。

## 测试计划

- **离线探针（主）**：见「验收标准」A1–A3。新增夹具，复用 `PlayerWallProbeTest` 范式；PASS/FAIL + 非零退出码。
- **编译（次）**：`./gradlew compileJava` → `BUILD SUCCESSFUL`。
- **对存量功能的回归**：`syncShips`/`stepShipSync`/`updateTerrain` 保留路径**行为不变**（物理体镜像/地形渲染不受扫掠解析影响）；服务端 `EntityPhysicsMoveMixin` 路径不变；`SpaceFlightMixin` 创造飞行路径不变。
- **端到端实机取证（辅助，不阻塞）**：见「验收标准」C 节，复用既有诊断日志人工确认，不计入机器判定 PASS/FAIL。
- **首个可运行里程碑（first-runnable，不计入验收项）**：**≤10 分钟内**产出离线扫掠探针（`PhysicalEntityCollision.resolve` 的最小可运行夹具）能 `./gradlew compileJava` 通过并打印 `PASS/FAIL`。这是通路验证，不是功能完成承诺。

## 假设（不阻塞的小决策，标注即可）

- 反编译 0.5.0 的 `PhysicalEntityCollision` 依赖的 `PhysicalBody.getBlockStateInt`/`worldAABB`/`getPos`/`getRotation` 等接口，在移植时按我方 `ClientBody`（`ClientPhysicsWorld.java`）既有字段映射，不新造体素数据模型。
- 玩家碰撞箱直立（决策①）⇒ collide RETURN 注入的 rotation 参数恒为 null，无需 `SpaceViewRotation` 等价物；若未来要 6DOF 旋转盒，另行立项补旋转基底。
