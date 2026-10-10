# 计划（PLAN）

> pm（Jason）骨架；「设计」段由 architect（Eva）填写。分阶段 P0–P4 + 阶段门控 + 风险。
> 代码引用一律 `相对路径 · 锚点(函数/字段/唯一字符串) @ 行号`。

## 目标

把「玩家↔物理体碰撞」从 space 0.1.3 的「Rapier 双刚体驱动玩家 + `Entity.move` HEAD cancel」升级为 space 0.5.0 的「原版 `Entity.move`+`collide` + `collide` RETURN 注入 Java OBB 扫掠解析」，根治「穿模 + 卡住」两症状。**只改玩家↔物理体，不动物理体之间碰撞与服务端三件套。**

## 里程碑

| 阶段 | 名称 | 交付物 | 阶段门控（不满足不进下一阶段） |
|---|---|---|---|
| **前置** | 备份已做 | 回滚点 `git commit 9981c51` | 确认 commit 存在；工作树干净（实现阶段前不提交） |
| **P0** | 镜像桥（主动栈→mps） | `MirrorPhysicalBodyBridge`：把主动栈 `ClientBody` 镜像成 mps `PhysicalBody` 进 mps `ClientPhysicalWorld` | 镜像后 mps `getAllPhysicalBody().size()` == `ClientPhysicsWorld.size()` |
| **P1** | 扫掠解析器移植 | `PhysicalEntityCollision` + `PhysicalEntityCollider` + `PhysicalEntityColliderAccess`；删 `PhysicsAabbSolver` | 离线探针 **A1 防隧穿 + A2 不嵌卡** PASS（纯 Java 夹具，不接主循环） |
| **P2** | 切换碰撞权威 | `MixinEntity.collide` RETURN 注入；撤销 HEAD cancel；玩家双刚体退场 | `./gradlew compileJava` BUILD SUCCESSFUL；`grep "new PlayerPhysicsBody"` 零命中 |
| **P3** | 载速探针 | `PhysicalEntityProbe` + `space$syncProbe`/`space$addProbeVelocity` 接线 | 离线探针 **A3 载速** PASS |
| **P4** | 收尾 | 全量编译 + 全套探针 + 游戏内日志判据 | 三件套（A/B/C）全 PASS；死代码复查干净 |

> **首个可运行里程碑（first-runnable，≤10 分钟）**：P1 的离线扫掠探针能编译并打印 `PASS/FAIL`——它是通路验证，不等任何阶段门，不计入验收项。

## 设计（architect 填写）

> 契约冻结口径：implement 角色**只认本节**。照抄基准 = `decompiled-space/0.5.0/org/polaris2023/mps/collision/`，包名落到我方 `com.mss.polymech`，方法体/常量逐字对齐（同形；与既有 mps 克隆层同一条 clean-room 约定）。所有引用 `相对路径 · 锚点 @ 行号`。

### 模块边界

| 模块（新建/改造） | 包路径 | 职责 | 依赖方向（只依赖，不反向） |
|---|---|---|---|
| `PhysicalEntityCollision` | `com.mss.polymech.physics` | 分段 OBB 扫掠解析器：`resolve`（MC 入口）+ 纯 Java 核心 `sweepAndSlide`/`findEarliestHit`/`sweepBoxAgainstBlock`/`snapshotBodies` + `BodySnapshot` 内部类 | 依赖 `PhysicalEntityCollider`（`EntityShape`/`Bounds`）；读 mps 层数据源快照（`ClientPhysicalWorld.getAllPhysicalBody()` 的 mps `PhysicalBody`，照抄 0.5.0）；**不依赖** `ClientPhysics.drive`/`PlayerPhysicsBody`/P0 镜像桥 |
| `PhysicalEntityCollider` | `com.mss.polymech.physics` | 玩家碰撞形状工厂：`create`/`fits`/`at`（眼高 pivot + 盒底 offset + 旋转 + 默认直立）→ `EntityShape` record | 依赖 Rapier `ColliderBody`（`EntityShape` 持 `collider` 字段供探针 attach，扫掠数学不读它）；`EntityShape`/`Bounds` 供扫掠器复用 |
| `PhysicalEntityColliderAccess` | `com.mss.polymech.physics` | 访问接口：`space$getPhysicalEntityCollider`/`space$getPhysicalEntityProbe`/`space$setPhysicalEntityProbe`（比 0.5.0 多一个 setter，供 P3 探针接线**不写 P2 的 mixin**，见下契约） | 无 |
| `PhysicalEntityProbe` | `com.mss.polymech.physics` | 独立 DYNAMIC 探针刚体（质量 50）：`bind`/`sync`/`velocity`/`close`，测「被移动物理体带着走」的载速 | 依赖 `PhysicalEntityCollider` + Rapier 刚体/碰撞体 helper（见风险 R8） |
| `EntityPhysicsCollideMixin`（新增） | `com.mss.polymech.mixin` | `Entity.collide` RETURN 注入 `PhysicalEntityCollision.resolve`；实现 `PhysicalEntityColliderAccess`（collider 惰性建 + probe 字段存取）；`remove` HEAD 释放 | 依赖 `PhysicalEntityCollision`/`PhysicalEntityCollider`/`PhysicalEntityColliderAccess` |
| `EntityPhysicsProbeMixin`（新增） | `com.mss.polymech.mixin` | 探针接线：`space$syncProbe`（建/绑/同步）+ `space$addProbeVelocity`（载速 `/20` 叠加） | 依赖 `PhysicalEntityProbe` + accessor 接口 |
| `MirrorPhysicalBodyBridge`（新增，P0） | `com.mss.polymech.client.physics`（或 `com.mss.polymech.mps.client`） | 主动栈→mps 镜像桥：`ClientBody.blocks()`（`BlockEntry{dx,dy,dz,state}`）→ mps `PhysicalBody.fillBlockState`；负责坐标映射（主动栈最小角原点 → mps 中心原点 −64..63）；生命周期 CREATE/UPDATE/REMOVE 挂钩 | 依赖 `ClientPhysicsWorld` + mps `PhysicalBody`/`ClientPhysicalWorld` |
| `ClientPhysicsWorld.ClientBody`（改造） | `com.mss.polymech.client.physics` | 主动栈物理体快照（`blocks()`/`tickX/Y/Z`/`qx/y/z/w`）：**不再直接作扫掠数据源**，由 P0 镜像桥镜像成 mps `PhysicalBody`；不新增 `worldAABB()`（mps `PhysicalBody` 自带） | 无 |
| `ClientPhysics.java`（改造） | `com.mss.polymech.client.physics` | `drive`/`ensurePlayerBody`/`afterPlayerTick` 玩家刚体驱动链路**退场**；`syncShips`/`stepShipSync`/`updateTerrain` **保留** | — |

> **数据源张力裁决（FIND-4，已定「切 mps」）**：扫掠数据源 = mps `PhysicalBody`（照抄 0.5.0 `snapshotBodies`，迭代 `ClientPhysicalWorld.getAllPhysicalBody()` 返回的 mps `PhysicalBody`，**不再是 ClientBody 适配层**）。因 mps 客户端物理世界原为空（服务端从未 `new ServerPhysicalBody`，物理体数据全在主动栈 `ClientPhysicsWorld.ClientBody`），新增 P0 镜像桥把 `ClientBody` 镜像成 mps `PhysicalBody` 并 `addPhysicalBody` 进 mps `ClientPhysicalWorld`，扫掠+探针统一读 mps 层。**核心扫掠算法（`sweepAndSlide`/`findEarliestHit`/`sweepBoxAgainstBlock`/SAT 15 轴）仍逐字照抄**；`snapshotBodies`/`BodySnapshot` 数据获取层照抄 0.5.0（mps `PhysicalBody.getBlockStateInt`→`blockCollision`）。

### 接口契约（I/O 用 JSON Schema，精确到字段/类型/错误语义）

```json
{
  "PhysicalEntityCollision.resolve（MC 入口，静态）": {
    "input": {
      "entity": "net.minecraft.world.entity.Entity（须 instanceof PhysicalEntityColliderAccess）",
      "requestedMovement": "net.minecraft.world.phys.Vec3",
      "rotation": "org.joml.Quaterniondc | null（null=直立，单位四元数）"
    },
    "output": {
      "resolved": "net.minecraft.world.phys.Vec3（分段扫掠+滑移后的夹后位移；!resolved.isFinite() 时返回 Vec3.ZERO）"
    },
    "errors": {
      "entity==null || requestedMovement==null": "原样返回 requestedMovement，不抛异常",
      "!entity.level().isClientSide": "原样返回 requestedMovement",
      "requestedMovement.lengthSqr() < 1e-10": "原样返回 requestedMovement",
      "数据源 null（维度不匹配/未就绪）": "原样返回 requestedMovement",
      "collider==null || !collider.fits(entity)": "原样返回 requestedMovement",
      "snapshotBodies 为空": "原样返回 requestedMovement"
    }
  },
  "PhysicalEntityCollision 核心扫掠（离线可单测，纯 Java，不依赖 Minecraft 运行时）": {
    "input": {
      "startShape": "PhysicalEntityCollider.EntityShape{center/axisX/axisY/axisZ/bounds/sweptBounds/move}",
      "requestedMovement": "org.joml.Vector3d",
      "bodies": "BodySnapshot[]（每体：position:Vector3d, rotation:Quaterniond, worldBounds:Bounds, blockCollision:(x,y,z)->boolean）"
    },
    "output": { "resolved": "org.joml.Vector3d" },
    "常量（照抄 0.5.0，逐字）": {
      "MIN_BLOCK": -64, "MAX_BLOCK": 63, "MAX_SWEEP_SEGMENTS": 128,
      "MAX_SLIDE_ITERATIONS": 4, "MAX_SEGMENT_LENGTH": 2.0,
      "AXIS_EPSILON": 1e-10, "TIME_EPSILON": 1e-9, "CONTACT_OFFSET": 1e-5
    },
    "碰撞判据（离线探针注入点）": "blockCollision 谓词是唯一 Minecraft 耦合点：MC 适配层实现为 !Block.stateById(state).getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()；离线探针注入合成谓词（如「非空气即碰」）"
  },
  "PhysicalEntityCollider.EntityShape": {
    "fields": {
      "collider": "Rapier ColliderBody（仅探针 attach 用，扫掠数学不读它）",
      "pivot": "Vector3d（= entity.getX(), entity.getY()+eyeHeight, entity.getZ()，眼高为枢轴）",
      "offset": "Vector3d（= 0, halfHeight-eyeHeight, 0，使盒底在脚底）",
      "halfExtents": "Vector3d（halfWidth, halfHeight, halfWidth）",
      "rotation": "Quaterniond（null 归一为默认直立）"
    },
    "方法": ["center()", "move(displacement)->EntityShape", "axisX()", "axisY()", "axisZ()", "bounds()->Bounds", "sweptBounds(displacement, margin)->Bounds"]
  },
  "PhysicalEntityProbe": {
    "常量（照抄）": { "PROBE_MASS": 50.0, "PROBE_FRICTION": 100.0, "STEPS_PER_WINDOW": 5 },
    "方法契约": {
      "bind(PhysicalWorld)": "attach 探针 DYNAMIC 刚体 + CUBOID 碰撞体，setCollisionGroups(2,5)、setFriction(100)、setFrictionCombineRule(0)、setRestitution(0)、setRestitutionCombineRule(1)、setCCD(false)、addTickListener(stepListener)",
      "sync(Entity)": "targetPosition = shape.center(entity)；lastGrounded = entity.onGround()",
      "onPhysicsStep（tick%5==0）": "velocity = linvel（未接地时减去 gravity×elapsed）；再 setPos(targetPosition)+setRotation(单位)+setAngvel(0)+setLinvel(0)",
      "velocity()": "返回 velocity 副本（Vector3d）"
    },
    "载速叠加点（契约冻结）": "space$addProbeVelocity(movement, probe) = new Vec3(movement.x + v.x/20, movement.y + v.y/20, movement.z + v.z/20)"
  },
  "collide RETURN 注入点（EntityPhysicsCollideMixin，契约冻结）": {
    "注入": "@Inject(method=\"collide\", at=@At(\"RETURN\"), cancellable=true)",
    "生效条件": "this instanceof LocalPlayer player && !player.isSpectator() && !player.noPhysics",
    "动作": "Vec3 resolved = cir.getReturnValue(); cir.setReturnValue(PhysicalEntityCollision.resolve(player, resolved, rotation))",
    "rotation 取值": "太空视角旋转激活时为对应 Quaterniond，否则 null（直立）"
  },
  "数据源映射（mps PhysicalBody → BodySnapshot，契约冻结，照抄 0.5.0）": {
    "getBlockStateInt(x,y,z)": "→ blockCollision(x,y,z) = 该局部坐标有方块且其 state 有碰撞形状（hasCollision）；mps 局部坐标 = 中心原点 −64..63（getBlockStateInt/fillBlockState 内部 +64）",
    "getPos()": "→ 权威位置（mps `PhysicalBody.getPos`@184）",
    "getRotation()": "→ 四元数（mps `PhysicalBody.getRotation`@188，归一化）",
    "worldAABB": "→ mps `PhysicalBody` 自带 `worldAABB`@97 + `getBlockStateInt`@550，无需客户端补",
    "镜像桥坐标映射（P0，契约冻结）": "主动栈 `ClientBody.blocks()` 局部坐标 =「方块 − 区域最小角」（刚体原点在最小角）→ mps `PhysicalBody` 局部坐标 =「中心在原点 −64..63」；镜像桥把 BlockEntry 的 dx/dy/dz 换算成 mps −64..63 坐标再 `fillBlockState`",
    "维度匹配": "mps `PhysicalBody` 维度由镜像桥按 `ClientPhysics` 当前维度分组；维度匹配由 resolve 入口用 entity.level() 与当前 mps 维度比对完成"
  }
}
```

### 数据流

```
P0 镜像桥（并行，先于扫掠/探针读）：ClientPhysicsWorld.accept 存 ClientBody
  └─ MirrorPhysicalBodyBridge：ClientBody CREATE → 新建 mps PhysicalBody + fillBlockState（坐标映射 最小角原点→中心原点 −64..63）+ addPhysicalBody
  └─ ClientBody UPDATE（applyUpdate 位姿/速度）→ mps setPos/setRotation/setLinSpeed/setAngSpeed
  └─ ClientBody REMOVE → 从 mps ClientPhysicalWorld remove
原版 Entity.move（HEAD cancel 已撤销，完整跑 collide）
  └─ 原版 collide 夹住原版方块（原版天然防穿模）
       └─ RETURN 注入 EntityPhysicsCollideMixin.space$collideWithPhysicalBodies
            └─ PhysicalEntityCollision.resolve(player, 原版已夹位移, rotation)
                 ├─ clientPhysicalWorld 判维度 → 数据源 = mps ClientPhysicalWorld.getAllPhysicalBody() 的 mps PhysicalBody 全量
                 ├─ snapshotBodies：mps PhysicalBody → BodySnapshot（照抄 0.5.0：getBlockStateInt→blockCollision / getPos→pos / getRotation→rotation / worldAABB 自带）
                 ├─ segmentCount = clamp(ceil(max(|x|,|y|,|z|)/2.0), 1, 128)  每段 ≤2 格
                 ├─ 每段 sweepAndSlide（≤4 次）：findEarliestHit（sweptBounds 粗筛 → 体素三重循环 → sweepBoxAgainstBlock SAT 15 轴）→ 沿法向推进 + 残余切面滑移；entryTime<0 走 overlap 推出
                 └─ setReturnValue(resolved)
载速（独立于位置求解，travel HEAD 输入前叠加）：EntityPhysicsProbeMixin
  └─ sync：targetPosition=shape.center / lastGrounded=onGround → probe.sync(player)
       └─ onPhysicsStep（世界 tick%5==0）：velocity = linvel（未接地减重力）→ 复位到 targetPosition
            └─ space$addProbeVelocity（travel HEAD，move 输入前）：movement += probe.velocity()/20
```

### 技术选型与理由

- **Java 侧 OBB 扫掠**（分段 + sweep-and-slide + SAT 15 轴）照抄 0.5.0，不引新依赖；把 Minecraft 唯一耦合（`hasCollision` 的 `Block.getCollisionShape`）抽成可注入谓词，使核心离线可单测（SPEC A1/A2）。这不是「自创近似」，是「照抄方法 + 抽离测试缝」。
- **探针用 Rapier DYNAMIC 刚体**（照抄 `PhysicalEntityProbe`）：质量 50、CCD 关、碰撞组 `(2,5)`。它**不参与玩家位置求解**，只量载速（替代旧 sibling 测载速）。
- **载速 `÷20`**：`velocity()` 单位 m/s（刚体线速度），`÷20` 折算到每 tick 位移再叠加（与 20Hz tick 对齐）；接线注入点 = travel HEAD（move 输入前叠加，非 collide RETURN）。
- **数据源 = mps `PhysicalBody`（照抄，不建 ClientBody 适配层）**：P0 镜像桥把主动栈 `ClientPhysicsWorld.ClientBody` 镜像成 mps `PhysicalBody` 并 `addPhysicalBody` 进 mps `ClientPhysicalWorld`，扫掠 `snapshotBodies`/`BodySnapshot` 逐字照抄 0.5.0（迭代 `getAllPhysicalBody()` 的 mps `PhysicalBody`，`worldAABB`/`getBlockStateInt` 自带）；探针与扫掠统一读 mps 层。核心扫掠算法（`sweepAndSlide`/`findEarliestHit`/`sweepBoxAgainstBlock`/SAT 15 轴）逐字照抄。

### 术语与口径（AUTHORITY 权威定义）

**物理体**：由服务端 `PhysicsBodyTracker` 管理、客户端以 `ClientPhysicsWorld.ClientBody` 快照镜像，并经 P0 `MirrorPhysicalBodyBridge` 镜像成 mps `PhysicalBody` 的动态/运动学刚体 + 体素方块集合（飞船/建筑）；方块不存在于原版世界。

**物理体镜像桥**：P0 `MirrorPhysicalBodyBridge`，在客户端把主动栈 `ClientPhysicsWorld.ClientBody` 镜像成 mps `PhysicalBody` 并 `addPhysicalBody` 进 mps `ClientPhysicalWorld`，负责坐标映射（主动栈最小角原点 → mps 中心原点 −64..63），使扫掠与探针统一读 mps 层。

**扫掠解析**：`PhysicalEntityCollision.resolve` 的分段 OBB 扫掠碰撞解析（分段 + sweep-and-slide + SAT 15 分离轴），对「物理体方块」夹玩家位移。

**探针载速**：`PhysicalEntityProbe` 独立 DYNAMIC 探针刚体测得的「被移动物理体带着走」的线速度，`÷20` 叠加到 movement。

**碰撞链阶段**：P0 镜像桥 + P1 扫掠解析器（并行）→ P2 碰撞权威切换 → P3 载速探针 → P4 收尾 + 文档，按 `TASKS.json.dependsOn` 串行。

**任务状态机**：`pending→claimed→in_progress→completed|failed|cancelled`；依赖只认上游 `completed`。

**文件单写者**：每个要改的源码文件只归一个 implement 角色（`TASKS.json` 的 `inScope`+`owner` 是机器编码）。

**probe/collider 字段持有者**：probe/collider 字段的唯一持有者 = `EntityPhysicsCollideMixin`（collider 惰性建 + probe 字段存取），经 `PhysicalEntityColliderAccess.space$setPhysicalEntityProbe` 写入；`EntityPhysicsProbeMixin` 只读不持有字段。

### 风险与取舍

见下「风险」表（R1–R10，architect 补充取舍细节）。

## 风险

| # | 风险 | 影响 | 缓解 / 处置 |
|---|---|---|---|
| R1 | **已解决（切 mps）**：原「参考 `PhysicalBody` 接口映射到 `ClientBody` 有缺口」 | — | 切 mps 后照抄 0.5.0，接口天然对齐 mps `PhysicalBody`（`getBlockStateInt`@550/`worldAABB`@97/`getPos`@184/`getRotation`@188 全存在），无需映射 |
| R2 | 分段扫掠（每 tick 最多 128 段 × 多体素 × 15 轴 SAT）性能 | 客户端卡顿 | 快照 + `sweptBounds` 粗筛（`BodySnapshot.worldBounds` 相交才进体素三重循环）；照抄 0.5.0 的 `clampBlock` 范围限制 |
| R3 | 撤销 HEAD cancel 后，原版 `collide`（已夹原版方块）与扫掠（只夹物理体方块）的叠加行为 | 二次夹取 / 抖动 | 扫掠**只**对 `hasCollision` 的物理体体素；原版方块不重复夹（SPEC R2） |
| R4 | 0.5.0 反编译里 `space$syncProbe`/`space$addProbeVelocity` 定义了但调用点未体现（`MixinEntity.collide` 只调 `resolve`） | 载速探针「接线」缺失 → 被船带着走失效 | **已拍板**：接线点 = travel HEAD（move 输入前叠加），照抄 `space$addProbeVelocity` 语义（`velocity()÷20` 加到 movement） |
| R5 | 客户端扫掠读快照与服务端 20Hz 同步包的时序一致性 | 快照滞后一帧 | 只消费已落盘 `ClientBody` 快照；世界访问经 `with_world` 互斥串行（SPEC「并发同键·适配」） |
| R6 | 双刚体退场后 `onGround` 判定回归（旧 `PhysicsGroundProbe` 依赖刚体地面） | 落地/跳跃异常 | `onGround` 由**原版 collide 维护**（交回原版后天然有）；`PhysicalEntityProbe` 只采样 `lastGrounded` 测载速，不回写（SPEC 决策④） |
| R7 | 双刚体 `drive()`/`afterStep()` 链路有隐藏调用点未清干净 | 编译期/运行期残留 | P4 全仓 `grep` 复查：`new PlayerPhysicsBody`/`afterStep`/`drive(` 零命中 |
| R8 | `com.mss.polymech.mps` 克隆层已含 0.5.0 同形依赖——`mps.physical.physical_body.PhysicalBody`（`getBlockStateInt`@550/`fillBlockState`@508/`getPos`@184/`getRotation`@188/`worldAABB`@97/`updateWorldAABB`@353/`isValidAABB`@382）、`mps.rapier.helper.RigidBody`（`setCCD`@435）/`ColliderBody`（`setCollisionGroups`@328/`setFrictionCombineRule`@274）、`mps.physical.physical_world.PhysicalWorld`（`getAllPhysicalBody`@229 + `addRigidBody`/`addTickListener`/`getTickTime`/`getG`）与单例 `mps.ClientPhysicalWorld.getPhysicalWorld`@264（`addPhysicalBody`@152） | 扫掠/探针「照抄 vs 适配」接缝 | **已定「切 mps」**：扫掠数据源 = mps `PhysicalBody`（照抄 0.5.0），探针世界 = mps `ClientPhysicalWorld`，二者统一读 mps 层；由 P0 镜像桥补 mps 客户端物理体数据。`snapshotBodies`/`BodySnapshot` 逐字照抄 0.5.0，不再做 `ClientBody` 适配层 |
| R9 | **已解决（切 mps）**：原「主动栈 vs mps 层两套并行 Rapier 世界」——扫掠读主动栈、探针读 mps 层，两世界位姿/时序不一致 | 两世界不一致 | **P0 镜像桥把主动栈 `ClientBody` 镜像成 mps `PhysicalBody` 并 `addPhysicalBody` 进 mps `ClientPhysicalWorld`，扫掠+探针统一读 mps 层**，消除双数据源。残余风险：镜像桥时序（ClientBody 快照 → mps 镜像滞后一帧）与坐标映射正确性（见 R10） |
| R10 | **P0 镜像桥坐标约定映射（P0 最大风险）**：主动栈 `ClientBody.blocks()` 局部坐标 =「方块 − 区域最小角」（刚体原点在最小角），mps `PhysicalBody` 局部坐标 =「中心在原点 −64..63」（`getBlockStateInt`/`fillBlockState` 内部 +64） | 镜像体素错位 → 扫掠命中错误方块 | 镜像桥显式做坐标换算（BlockEntry dx/dy/dz → mps −64..63 再 `fillBlockState`）；verify：镜像后 mps `getAllPhysicalBody().size()` == `ClientPhysicsWorld.size()` 且 `getPos()` 与 ClientBody tickX/Y/Z 一致 |
