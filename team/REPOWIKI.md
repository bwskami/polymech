# REPOWIKI — polymech 项目知识库

> 维护者：专家团（researcher/architect/实现者共用）。
> 用途：任何角色接手本仓库前先读这份，避免重复从源码/长文档里重新定位。
> 引用约定：代码引用一律写 `<相对路径> · <锚点(函数名/字段名/唯一字符串)> @ <行号>`。

---

## 1. 项目概览

- 项目：**polymech**，Minecraft **NeoForge 1.21.1** 模组。
- 仓库根：`C:/modIDEA/polymech/polymech-template-1.21.1`。
- 核心玩法：太空维度 + 物理体（飞船/建筑，从世界里"抠出来"变成 Rapier 刚体）+ 牵引枪 + 机器/流体等。
- 回滚点：git commit `9981c51`（玩家碰撞升级任务的回退基准，任务未完成前不动它、不额外提交）。

## 2. 技术栈

| 层 | 技术 | 说明 |
|---|---|---|
| 模组框架 | NeoForge 1.21.1 | Forge 的新分支，`net.neoforged` 命名空间 |
| 语言 | Java 21（Gradle build） | 主要业务代码 |
| 物理引擎 | **Rapier（Rust native）** | 通过项目自有 JNI 层 `NativePhysics`/`PhysicsNatives` 调用，**非 Java 侧模拟** |
| 原生 ABI | `native/polymech-physics/src/lib.rs` | Rapier 封装：世界/刚体/碰撞体/射线/体素/碰撞组/材质 |
| 数学库 | JOML（`org.joml`） | Vector3d/Quaterniond/AABBd，0.5.0 参考与项目共用 |
| 渲染 | GeckoLib + 自建 Geo 模型 | 机器方块实体渲染 |
| 参考基准 | `decompiled-space/`（0.1.0 / 0.1.3 / 0.5.0 反编译） | clean-room 照抄的**唯一权威** |

## 3. 目录结构（只列本任务相关主线）

```
src/main/java/com/mss/polymech/
  physics/                 ← 实际在跑的物理层（玩家双刚体/服务端体管/投影）
  client/physics/          ← 客户端物理（ClientPhysics 世界、镜像体、渲染读位姿）
  mixin/                   ← 通用 mixin（EntityPhysicsDriveMixin / EntityPhysicsMoveMixin 等）
  mixin/space/             ← 太空维度 mixin（ServerPlayer / ClientPacketListener / Entity …）
  mixin/mps/               ← mps 克隆层配套 mixin（无人填充的那套）
  mps/                     ← clean-room 克隆层（kelvin 天体 + MPS 物理体；见 §4）
  space/                   ← 太空维度（SpaceWorld / SpaceScaleMigration / PlanetDimensions）
  dimension/               ← PlanetDimensions（各维度重力倍率）
  network/                 ← 自有网络包（PhysicsBodySyncPacket / MoveBatch / PhysgunBeam …）
docs/                      ← 项目文档（mps-clone-plan.md 是核心工程台账）
decompiled-space/0.5.0/    ← 0.5.0 目标结构参考源码（照抄基准）
decompiled-space/0.1.3/    ← 0.1.3 旧结构参考（当前实现参照）
native/polymech-physics/   ← Rapier JNI 封装（Rust）
native/jni-smoketest/      ← 离线物理探针（PlayerWallProbeTest 等，不需要开游戏）
team/                      ← 专家团协作工件（本文件 + <runId>/ 下各阶段文档）
```

## 4. 物理系统「双套并存」（必读，别改错对象）

存在**两套**物理体系，名字高度相似，极易改错：

| 包 | 状态 | 用途 |
|---|---|---|
| `com.mss.polymech.physics` + `com.mss.polymech.client.physics` | **实际在跑** | 玩家双刚体（`PlayerPhysicsBody`）、服务端体管（`PhysicsBodyTracker`）、投影（`physics.ProjectionManager`）、每维度世界（`PhysicsWorldManager`）、客户端镜像体（`ClientPhysics`） |
| `com.mss.polymech.mps`（clean-room 克隆层） | **完整但无人填充**（见 docs §20） | MPS 物理体层：`mps.physical.entity.PhysicalEntity`、`mps.physical.physical_body.*`、`mps.physical.physical_world.*`、`mps.physical.manger.ProjectionManager`、`mps.rapier.*` |

关键事实（已核实，来自 `docs/mps-clone-plan.md` §20 `@ 1449`）：

- 克隆层的服务端体层/投影/网络/线程/桥**代码全到位**，但**没有任何 gameplay 路径会创建物理体**——全仓库只有 `ServerPhysicalBody.loadFromTag` 一处 `new ServerPhysicalBody(...)`，而存档里永远不会有东西。
- 项目**既有**的体管线（`PhysicsBodyTracker` + `physics` 包 + 项目自己的 `PhysicsBodyInteraction`/`ProjectionManager`）才是**当前真正在跑**的一套，它用自己的 id 空间，不经过克隆层。
- **注意**：`mps.kelvin.*`（天体/太空世界：`CelestialWorld`/`SpaceWorld`/`ServerCelestialWorld`）**不是空壳**——它已被 `PlanetDimensions`/`SpaceWorld`/`SpaceRenderer` 等活代码引用，正在跑。说"mps 空壳"只指 **MPS 物理体/碰撞**那一半。
- 两套 `ProjectionManager` 并存：`physics.ProjectionManager`（跑）、`mps.physical.manger.ProjectionManager`（死）。改投影逻辑认准 `com.mss.polymech.physics` 那个。

## 5. 关键文档

| 文档 | 内容 |
|---|---|
| `docs/mps-clone-plan.md` | **核心工程台账**（4426 行）。§20 克隆层状态、§21 被弹飞、§22 真根因（回灌+0.08）、§23 抽搐、§30 恒等坐标、§31 渲染平滑等历史定论 |
| `docs/repo-structure.md` | 仓库结构总览 |
| `docs/space-030-audit.md` / `docs/space-030-execution.md` | 0.3.x 结构审计与执行记录 |
| `docs/space-decompile.md` | 反编译参考说明 |
| `docs/render-interpolation.md` / `docs/body-local-mapping.md` | 渲染插值 / 物理体局部坐标约定 |
| `docs/mps-clone-plan.md` §13 `@ 308` | **回归怎么跑**（编译 + 离线探针 + 日志取证命令） |

## 6. 项目纪律（硬约束，写进所有工件）

1. **照抄 space 上游**：以 `decompiled-space/0.5.0/` 为权威，逐文件方法对齐，**不局部打补丁、不做自创近似实现**（用户明确反对近似重写）。
2. **方法对齐**：物理体/玩家碰撞结构必须与参考「方法对齐」——写明「职责 → 为什么必须这么做 → 实现步骤」三段。
3. **原生缺口补原生**：遇到 Rapier/JNI 缺口在 `native/polymech-physics` 补，**不在 Java 侧发明等价仿真**。
4. **clean-room 照抄**：克隆层语义照参考实现，但写成自有实现（`docs/mps-clone-plan.md` §一 许可红线）。
5. **傻瓜式自动判定 PASS/FAIL**：验证优先用离线探针（`native/jni-smoketest/`），程序判定，不让用户肉眼判断。
6. **约定类事实去自己那一侧的原生实现确认**（`lib.rs`），不能从对照实现反推（§21 的 `(0,halfHeight,0)` 教训）。
7. 落盘前若目标文件已存在，先 `read` 再 `write`/`edit`。

## 7. 构建 / 运行 / 回归速查

```bash
# 在仓库根目录
./gradlew compileJava           # 只编译（改了 Rust 才需要 copyNativePhysics）
./gradlew runServer             # 跑服务端
./gradlew runClient             # 跑客户端
```

回归与离线探针的完整命令见 `docs/mps-clone-plan.md` §13 `@ 308`。玩家物理离线探针（`PlayerWallProbeTest`）只依赖 `NativePhysics`，不需要开游戏，覆盖 A–K 场景（§21/22/23 的历史结论都由它钉死）。
