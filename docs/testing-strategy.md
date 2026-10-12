# Poly Mech 测试策略

> 结论先写：**本项目当前唯一能在 CI 稳定运行的回归手段是「离线类探针 + 编译」**。
> NeoForge GameTest（`runGameTestServer`）在本模组下**起不来**，原因见第 3 节。

## 1. 可用的验证手段

| 手段 | 命令 | 说明 |
|------|------|------|
| 编译 | `./gradlew compileJava` | 最快的一道门；改动后必跑 |
| 完整构建 | `./gradlew build` | 含 `processResources` / `generateModMetadata` / 打包 |
| **离线类探针** | `./gradlew verifyClasses` | SMOKE / PHYSICS / SPACE / CLONE / KELVIN 五组关键类存在性，秒级 PASS/FAIL，**CI 用这个** |
| 聚合物结构式 | `./gradlew verifyPolymers` | 走真实 CDK 管线验证 `polymer_formulas.json` |
| 机器渲染包围盒 | `./gradlew verifyMachineRenderBox` | 整机覆盖 / 回归守卫 / 朝向不变 / 旋转互逆 |
| 专用服务器冒烟 | `./gradlew runServer` | **服务端维度/物理世界只有这里能真验**（见第 3 节） |
| 客户端实机 | `./gradlew runClient` | 渲染/输入类改动只能由人在客户端复测 |

### 退出码约定（重要）

`./gradlew compileJava` / `build` **经常以退出码 1 收尾但实际是 `BUILD SUCCESSFUL`**
（NeoForge/ModDevGradle 往 stderr 写弃用警告导致 PowerShell 把结果标成失败）。
判定成功请以 stdout 里的 `BUILD SUCCESSFUL` 为准，不要只看退出码。

## 2. 离线类探针（`verifyClasses`）

`src/main/java/com/mss/polymech/verify/ClassProbe.java`。

核心是 `Class.forName(name, false, loader)` —— 第二个参数 `false` 表示**不初始化静态块**，
所以：

- 不会加载 `polymech_physics.dll`、不会起物理/轨道线程；
- 不需要启动 Minecraft、不需要资源包；
- 但**能真正证明**"这个类还在编译产物里"，能抓住"重构误删 / 包路径写错 / 模块没编进去"。

输出形如：

```
=== Poly Mech 离线类探针 ===
PASS  SMOKE(base)    3/3
PASS  PHYSICS        6/6
PASS  SPACE          5/5
PASS  CLONE          20/20
PASS  KELVIN         19/19
-----------------------------------------
RESULT: PASS  (53 classes loadable)
```

任一缺失 → `RESULT: FAIL` + 列出缺失类名，退出码 1。

**加新探针**：往 `ClassProbe` 里对应组的字符串数组加类名即可。

**它证不了什么**：注册表内容（方块/物品/流体到底注册上没有）、
世界生成、维度加载、物理数值正确性。这些要真跑游戏。

## 3. 为什么不用 GameTest（`runGameTestServer`）

**不要**把 `runGameTestServer` 加进 CI —— 本模组在该环境下 100% 启动崩溃。

复现（2026-10-11 实测）：

```
[Server thread/ERROR] [ne.ne.bu.EventBus/EVENTBUS]:
  Exception caught during firing event: Missing dimension poly_mech:projection_world;
  registered dimensions = [minecraft:overworld, minecraft:the_end, minecraft:the_nether]
    at com.mss.polymech.mps.physical.manger.ProjectionManager.init(ProjectionManager.java:159)
    at com.mss.polymech.mps.event.CollisionPhysicalServerAction.onServerStart(...:91)
```

关键事实：

1. **只有 3 个原版维度进了注册表**，模组自己的 16 个维度
   （`projection_world` + `space` + 15 个行星地表）**一个都没有** ——
   尽管 `build/libs/*.jar` 里 `data/poly_mech/dimension/*.json` 与
   `dimension_type/*.json` 齐全，且崩溃报告里 `mod/poly_mech` 明确列为
   `Active Data Packs`。
2. 原版 `GameTestServer.create` 用的是
   `new DataPackConfig(new ArrayList<>(packRepository.getAvailableIds()), List.of())`，
   即**启用全部可用数据包**（含模组），所以不是"包被排除"。
3. `[Kelvin] space_data 读取完成：太空世界 1 个，天体 20 个，地表世界 15 个` 正常打印，
   说明 `WorldLoaderSpaceDataMixin` 注入成功、合成维度表也填好了；
   但 `RegistryDataLoaderSpaceDataMixin` 合成的维度同样没进注册表。
4. **同一份构建，`runServer` 完全正常**：
   ```
   [PolyMech] 投影维度就绪：poly_mech:projection_world（地皮 128³，间距 192）
   [MPS] 物理世界已创建：poly_mech:mars / moon / venus / ... （15 个全在）
   Done (0.939s)! For help, type "help"
   ```
   客户端（`runClient`）历史上也一直正常（2026-10-04 ~ 10-10 日志每次都有
   `投影维度就绪`）。

**结论**：这是 GameTestServer 环境特有的数据包/注册表加载差异，
不是模组 bug，也不是数据包内容问题。根因未最终定位。

**放大伤害的一点**：`mps.ProjectionManager.init` 在维度缺失时是**直接抛异常**
（照抄 space 的"不降级"口径，见该类注释），所以任何拿不到投影维度的环境
都会**启动即崩**，而不是降级继续跑。反观本项目自有的
`physics.ProjectionManager.init` 是"记日志 + 返回 false"的降级写法。
若将来要让 GameTest 可用，需要先解决"这个环境为什么拿不到模组维度"，
而不是去改那处抛异常（那会偏离 clean-room 口径）。

## 4. CI

`.github/workflows/build.yml`：

```yaml
- run: ./gradlew build
- run: ./gradlew verifyClasses
```

## 5. 已移除的方案（留档，避免重复踩）

曾按"给 CI 加 GameTest 回归"的思路在 `src/main/java/com/mss/polymech/gametest/`
写过 `SmokeTest` / `CloneTest` / `KelvinTest`，并配了
`data/poly_mech/structure/smoke_test.nbt` 结构模板。**已全部删除**，原因：

- GameTest 类必须放 `src/main/java`（NeoForge 扫的是 mod 自身类；
  `src/test` 在 ModDevGradle 下拿不到 Minecraft 依赖，直接编译不过）；
- 结构模板只认 `data/<modid>/structure/`（**单数**）下的**二进制 `.nbt`**
  （`StructureTemplateManager` 用 `FileToIdConverter("structure", ".nbt")`；
  `.snbt` 只对本地 `gameteststructures/` 目录生效）；
- 但即使这些都做对，服务器也**起不来**（第 3 节），探针根本没机会跑。

如果将来 GameTest 能跑起来，第 3 节末尾那段"已删除方案的要点"就是完整的重建清单。
