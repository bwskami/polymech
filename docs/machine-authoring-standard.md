# 机器编写规范

> **状态：S1 ✅、S2 声明层 ✅、S3 ✅、S4（加载期校验）✅、S5（schema 3：组可嵌套）✅、S6（schema 4：cube 名字）✅。**
> 离线断言：`verifyMachineDefinitions` 35 → **80 条**，`verifyMachineEditorModel` 181 → **331 条**
> （其中 M19/M20 是**真行为**断言，不是读源码查字符串）。

## 加一台机器现在要做什么

1. `ModBlocks` 的 static 块里加**一行** `machine(...)`；
2. 加一份 `data/poly_mech/machine/<id>.json`（结构 + 舱室；可用 `/polymech machineeditor <id>` 画）。

**两处必须同时加**——少任何一处都不会编译报错，所以 `verifyMachineDefinitions` 的
M20 会读 `ModBlocks` 源码里的清单调用与 `machine/*.json` 文件名**双向比对**，
少一份/多一份都 FAIL（做过负向测试）。

## 整合包作者改坏了怎么办

作者手改 `config/poly_mech/machines/<id>.json` 时，**没有任何东西会拒绝他的输入**：
`MachineDefinition.parse` 忽略未知键，所以键名敲错、`behavior` 写成词表外的值，
机器都会**照常出现、只是悄悄少了一半数据**。

所以加载期会按 `MachineStandard` 校验**每一份** JSON（内置 + config 两条路径都校验），
问题记进 `errors()`，用 `/polymech machinereload` 打印出来。

## 已落地

| 项 | 内容 |
|---|---|
| 规范类 | [`MachineStandard`](../src/main/java/com/mss/polymech/machine/definition/MachineStandard.java)：键的**封闭词表**（未知键直接报错）+ 取值约束 + 行为**封闭词表** |
| schema 版本 | `schema_version` 已成**必填**，10 份 JSON 全部标注 `4`；**1/2/3/4 都能读**（向后兼容），只有**未来版本**才报错 |
| **组可嵌套（S5）** | 组对象里 `cubes` 与 `groups` **并存**（照 Blockbench 的 bone）；解析/展平/序列化/校验全部递归 |
| 行为词表 | `BEHAVIORS = {processing, turbine_generator, steam_boiler}`，**由真实 BE 继承关系推出**（`AbstractTurbineGeneratorBlockEntity` 是 `AbstractProcessingBlockEntity` 的子类，故发电机单列） |
| 行为标注 | 10 台机器全部标了 `behavior`（7 台 processing / 2 台 turbine_generator / 1 台 steam_boiler） |
| meta 往返 | `MachineDefinition.Meta` 真参与 `parse`/`toJson`/快照往返 —— 编辑器保存**不会**丢掉 `behavior`/`tier`/`family` |
| 交叉核对 | 断言会由机器 id 推出 BE 类名、读源码看 `extends` 谁，再反推应该是什么行为，与 JSON 声明比对；词表声称的基类也必须在源码里真实存在 |
| **单一注册清单（S3）** | `ModBlocks` 的 10 段手写 `MachineRegistrar.registerBlock(MachineConfig.builder(...))` 样板 → **一台机器一行 `machine(...)`** + `MACHINE_TABLE`；10 个公开字段（`STEAM_HAMMER` 等）改成从清单取，名字全部保留、注册调用与顺序不变 |
| **加载期校验（S4）** | `MachineDefinitionManager` 的两条读入路径（内置 + config）都按 `MachineStandard` 校验，问题进 `errors()` → `/polymech machinereload` 打印。这是作者手改 config 唯一的反馈渠道 |

**四道闸门**（都做过负向测试，证明能 FAIL）：
1. 把 `side_offsets` 敲成 `side_offset` → 报「不认识的键」+「缺少必填键」。
2. 断掉快照的 meta 传递 → 报「保存不会丢 behavior」失败，并把丢掉 `behavior`/`tier`/`family` 的 JSON 原样打出来。
3. 从清单里删掉一台机器 → 报「没注册: [steam_hammer]」「清单 9 / JSON 10」。
4. 只给内置加校验、漏掉 config 那条 → 报「config JSON 读入路径也会走规范校验」失败。

## schema 3：组可嵌套（Blockbench 的 bone 口径）

### 长什么样

一个组<strong>既能直接挂 cube，也能挂子组</strong>，两者并存而不互斥 —— 这正是 Blockbench 里
bone 的形状（bone 下面既能放 cube、也能放子 bone）：

```jsonc
{
  "schema_version": 3,
  "groups": [
    {
      "name": "主体",
      "cubes":  [{"from":[0,0,0],"to":[0,0,0]}],          // 这一层直接挂的 cube
      "groups": [                                          // 这一层的子组（可再往下）
        {"name": "外壳", "cubes": [{"from":[1,0,0],"to":[1,0,0]}]}
      ]
    }
  ]
}
```

### 为什么选"并存"而不是"二选一"

- **和 Blockbench 一致**：作者的心智模型就是"文件夹可以套文件夹，cube 放哪层都行"，
  不需要额外学一套规则；
- **旧文件零改动**：schema 2 的 10 份平铺 JSON 只有 `cubes`、没有 `groups`，
  走同一条递归路径读进来完全等价，所以升级只动了 `schema_version` 这个数字；
- **没有"空层"问题**：不引入"只装子组的中间层"这种必须靠约定维持的东西。

### 版本闸门：向后兼容，只挡未来

`MachineStandard.MIN_READABLE_SCHEMA = 1`、`CURRENT_SCHEMA = 3`。
schema 的演进是**加键**（1 → 2 加 `groups`，2 → 3 加组内 `groups`），
所以 1/2 的文件用现在的解析器读出来**完全等价**，不该报"需要迁移"——
那是纯噪声，而**有噪声的校验等于没有校验**（作者会开始无视所有报错）。
只有 `schema_version > 3`（未来版本）才是真的读不了，必须报出来。

### 递归是这里唯一的难点

组一旦能嵌套，"只处理一层"的代码就会**静默丢数据**：机器放出来缺一块，
却不报任何错。所以这几处全部递归，并且由断言逐条钉住：

| 位置 | 必须递归的东西 | 忘了会怎样 |
|---|---|---|
| `MachineDefinition.Group` | `volume()` / `allCubes()` | 统计少算、格子少展平 |
| `MachineDefinition` | `flatten` / `cubes()` / `parseGroups` / `toJson` | 嵌套 cube 直接不进结构 / 保存即丢 |
| `MachineStandard` | `validateGroups` / `validateCubes` | 子组里的坏 cube 检查不到 |
| `MachineStructureSnapshot` | 所有重建 `Group` 的地方都要带上 `g.groups()` | **改一次名就把子组删光**（最隐蔽） |
| `MachineEditorScreen` | 大纲按 `GroupPath` 展开、折叠按路径记 | 子组在界面上看不见 |

> 快照里的 `withCubes` / `withSubGroups` 两个小工具就是为这件事存在的：
> 重建 `Group` 只能走它们，避免有人手写 `new Group(name, cubes)` 把子组漏掉。

### 编辑器里的新东西

- **大纲用官方 `TreeList` 组件**（照 [LDLib2 agent 指南](https://low-drag-mc.github.io/LowDragMC-Doc/zh/ldlib2/ui/agent_guide.html)
  的组件速查表）：缩进、展开箭头、折叠、Ctrl/Shift 多选<strong>都是组件内建的</strong>，
  不用自己拍平再加缩进占位。**它自己不滚动**，所以必须嵌在一个 `ScrollerView` 里
  （源码注释与指南都是这个用法；漏了这层包装，超过面板高度的行会被静默裁掉）。
  节点实现 `ITreeNode`，`getDimension()` 就是层级
  （合成根 = 0，顶层组 = 1，组里的 cube/子组 = 2…），缩进由 `createNodeUI` 里
  `marginLeft(5 × dimension)` 画；
- **显示类文字走数据绑定**：`bindText(label, supplier)` → LDLib2 的 `SupplierDataSource`
  （每 tick 拉一次），不再塞进 `refreshText()` 那条手工刷新链 —— 那条链每加一个显示项
  就得记得多刷一处，漏了就是"数据变了界面不动"。体检这种**贵**的计算仍然只在
  `refreshValidation()` 里跑，Label 绑定的是它算完缓存下来的文本；
- **决策与渲染分离**：`MachineOutlineLogic`（纯函数）决定"有哪些行、第几层、「+ 组」落在哪层、
  哪些 cube 会换层"，渲染层只把这些行喂给 `TreeList`。这样断言能直接跑**真行为**；
- **路径寻址**：层级由 `GroupPath`（下标序列）表达，折叠状态也按路径记
  （顶层的第 1 组 与 顶层第 0 组的第 0 个子组 是两回事，一个 `int` 指不到）；
- **Ctrl / Shift 多选**：由 `setSupportMultipleSelection(true)` 打开（Shift 区间只在
  <strong>同级</strong>之间生效，这正是树的意义）；`+ 组` 把这一批 cube 一起移交；
- **缩进冲突弹窗**：新组建在**最浅那个选中 cube 所在组的同级**。若某个选中的 cube
  来自更深/更浅的层，移交会改变它的层级 —— 先把"哪些 cube 会换层"列出来问一次，
  确认才动手（不允许结构被悄悄拍平或加深）；
- **只选中文件夹**时 `+ 组` 在它**里面**建子组，这是造嵌套的入口。

## UI 自动化测试（已接上，卡在 LDLib2 自己的 Scene）

指南的 [UI 自动化测试](https://low-drag-mc.github.io/LowDragMC-Doc/zh/ldlib2/ui/testing.html)
是**唯一**能测真实交互的路子，本项目已接：

- `MachineEditorUiScenario`（`@LDLRegisterClient(registry = UIScenario.REGISTRY, environment = DEV_ONLY)`）
- 运行：`gradlew runClient -PldTest=polymech_machine_editor`
- 报告：`run/ldlib2-uitest/report.json`（**不是** `build/`）
- `build.gradle` 里把 `-PldTest` 映射成框架要的 `-Dldlib2.uitest.*`（框架读系统属性，`-P` 只是 LDLib2 自己仓库的约定）

### 依赖链（升 LDLib2 会连带动两处）

| 依赖 | 值 | 为什么 |
|---|---|---|
| `ldlib2_version` | **2.2.41** | `UIScenario` **Since 2.2.34**；2.2.34–2.41 传递依赖与本项目原版一致（taffy 1.1.4），2.2.42 会换 taffy 1.1.5 |
| `jei_version` | **19.51.0.417** | 2.2.41 硬性要求 `jei >= 19.51.0.417`（低于它 mod 加载直接 FATAL） |
| `neo_version` | **21.1.239** | JEI 19.51 要求 `neoforge >= 21.1.239`，原来 21.1.228 |

升级后**代码零改动**：`compileJava` 通过，离线断言 53 / 41 / 74 / 293 全绿。

### 当前的唯一卡点

跑起来后场景能被发现并开始执行（前两步 `openScreen` / `awaitScreen` PASS），
随后渲染线程死在 **LDLib2 自己的** 3D 场景里：

```
Scene.drawBackgroundAdditional(Scene.java:596)
 → WorldSceneRenderer.render(:379) → unProject(:1085/:1092)
   → GL11.glReadPixels   ← 同步 GPU 回读，在测试框架的渲染路径下永久阻塞
```

这是编辑器 3D 视口每帧做深度回读（鼠标拾取）导致的，**不是本项目的 bug**，
正常游玩不受影响（客户端能正常起、世界能建）。

### 决定：暂不启用（2026-10）

> **⚠️ 不要在本机跑 `-PldTest`。** 这个卡死会**把显示驱动拖到看门狗超时**：
> 实测触发 `C:\Windows\LiveKernelReports\WATCHDOG\WATCHDOG-20261017-2236.dmp`
> （显示驱动被判定挂死 → 重置，画面闪黑），随后 java.exe 在 `OpenAL.dll` 崩 `0xc0000409`。
> 本机本来就有较多 `WATCHDOG`/`AMD_WATCHDOG` 历史（含 2026-04/05/08 多份），
> 且挂着两个虚拟显示适配器（GameViewer / Virtual Desktop），疑似加重 `glReadPixels` 慢路径。

所以：**场景代码保留**（`MachineEditorUiScenario`，`DEV_ONLY` + 只在带 `-PldTest` 时才启动，
不影响正常构建与游玩），**等 LDLib2 把 `unProject` 的阻塞回读修好再启用**。
不做「测试时不挂 3D 场景」的开关 —— 那是让生产代码迁就测试。

## 替代手段（在没有可用 UI 测试时）

1. **纯逻辑行为断言**（M19/M20）：决策抽成 `MachineOutlineLogic`，离线直接跑**真行为**；
2. **源码字符串断言**（其余）：只能证明"代码还在"，证明不了"算得对"。

## schema 4：cube 也有名字

### 为什么加

cube 以前只有 `from`/`to`，**没有任何名字字段**。后果是大纲里所有 cube 只能显示
`cube`，作者分不清谁是谁，也没法给某一格起名 —— 「cube 改名」在旧结构下**无处可存**，
不是 UI 能修好的事。Blockbench 的 cube 本来就有名字，补上这个键是向它对齐。

### 长什么样

```jsonc
{"groups": [{"name": "主体", "cubes": [
  {"name": "齿轮", "from": [1,0,0], "to": [1,0,0]},   // 有名字
  {"from": [2,0,0], "to": [2,0,0]}                    // 没名字，照旧
]}]}
```

`name` 是**可选**的：

- **解析**：省略 → `null`，大纲退回按形状显示 `cube` / `cube (盒)`；
- **导出**：**没有名字就不写这个键** —— 所以 schema 1/2/3 的文件进编辑器再保存，
  形状与原来完全一致（由断言「导出时无名 cube 不写 name」钉死）；
- **校验**：`CUBE_KEYS = {name, from, to}` 是封闭词表，`name` 敲成 `nam` 会报「不认识的键」；
  空白名字也报错（要么别写，要么写有效值）。

### 连带的两个坑（都已在断言里）

`Cube` 是 record，`name` 参与 `equals` —— 改名等于**换了一个实例**：

| 位置 | 不修的症状 |
|---|---|
| `updateCube(...)` 改尺寸时构造新 cube | 忘带 name → **改个尺寸名字就没了** |
| 大纲的 `selectedCubes` | 旧引用匹配不上 → **刚改完名的 cube 选不中** |

## 项目历史（撤销 / 重做）

### 存什么

整份 `MachineDefinition` 快照，**不写"反向操作"**。原因很硬：区间切割是"一拆六"，
逆运算不唯一，为每种编辑各写一份逆运算必然写出对不上的 bug。撤销 = 整体换回一份定义，
正确性不依赖任何逆运算。

### 三个之前缺的接入点

| 之前 | 现在 |
|---|---|
| 只能在「结构」页签里点按钮，作者根本不知道有这个功能 | **Ctrl+Z** 撤销、**Ctrl+Shift+Z** / **Ctrl+Y** 重做（全局） |
| 空栈也点得动，点了才被告知"没有可撤销的操作" | 按钮按 `canUndo()/canRedo()` 变灰 |
| 只显示"撤销 N"，退到哪一步全靠记 | 「结构」页有**可点的历史列表**，点第 i 行 = 连退 i 步 |

### 两个实现细节

- **输入框聚焦时不抢键**：按 `getFocusedElement()` 判断是不是 `TextField`/`TextArea` ——
  在名字输入框里按 `Ctrl+Z` 应该撤销文字，不是撤销整个结构。
- **每一步都带标签**：`mutate("加格 " + 坐标, ...)`，24 个改动点全部标了。历史里显示
  「加格 (1,2,3)」而不是「改动」—— 只有数字的话，作者没法判断该退几步。

上限 64 步；任何新改动都会清空重做栈（标准编辑器语义）。

### 历史属于「项目」，不属于「这一次打开」

切项目其实是**新开一个 Screen 实例**（`EditorScreenOpener.open` → `new MachineEditorScreen`），
所以栈<strong>不能</strong>存成实例字段 —— 那样切走再回来历史就空了。历史按 `machineId` 存在
`EditorHistories`（和 `EditorDrafts` 同一套思路）：

| 行为 | 结果 |
|---|---|
| 同一个项目反复打开 | 永远拿到同一个栈 → **切回来历史还在** |
| 项目 A / B | 互不干扰，各自的标签与深度 |
| 关闭项目 | **才**丢掉它的历史（否则多开会一直涨） |

断言（M11）：同一个项目拿到同一个栈、A/B 互不影响、关闭只丢自己、全关后不残留。

### 架构：一个窗口 → 一条标签栏 → 每个标签是一份文档

```
EditorWorkspaceScreen        ← 整个编辑器唯一的 Screen
  ├── ws_tabs                唯一标签栏（已开标签 + ＋ + 关闭）
  └── ws_content             当前标签的 tabView()
```

| 类型 | 职责 |
|---|---|
| `EditorTab` | 一份文档：`tabId / tabTitle / tabView / onShown / onHidden / onClosed / requestClose / handleKey` |
| `MachineTab` | 一个机器项目（原 `MachineEditorScreen`，2600+ 行） |
| `PickerTab` | 主菜单 = 浏览器的**新标签页**（空白页）；**自己不画顶栏** |

### 标签是「槽位」，内容是「可替换」的

这才是浏览器的核心行为，也是之前两版都没做对的点：

| 操作 | 结果 |
|---|---|
| 点 **＋** | 开一个**新的空白标签**（内容是主菜单） |
| 在主菜单里点机器 | **当前标签自己导航过去**变成那个项目，主菜单**不留一份**（就像在搜索引擎点结果） |
| 打开一个已经开着的项目 | 切到那个标签，**不开第二份** |
| 关掉最后一个标签 | 自动补一个空白标签（窗口不会空） |

- **切标签 = 换 `ws_content` 挂的子元素**，不重建 Screen —— 3D 场景、相机、滚动位置都保留；
- **关标签（×）= 丢弃**：先 `requestClose` 问未保存，确认后窗口删掉草稿与历史；
- **关窗口 = 存草稿**（下次打开还在）。

> 之前是「每个界面各画一条标签栏」：主菜单是独立 Screen、没有标签栏，一点加号标签栏就跟着没了；
> 而给主菜单补一条标签栏只是把错误复制了第二份。现在标签栏只有一处，
> 以后加页面（资源查看器之类）只要实现 `EditorTab`，标签栏自动就有。

## 为什么没做「通用 BE 按 behavior 分派」

S4 最初设想是"通用 BE 按 behavior 分派，per-BE 类收敛成行为实现"。看了实际代码后
**判断不该做**，理由是证据而不是偷懒：

| BE 类 | 行数 | 说明 |
|---|---|---|
| `AbstractSteamBoilerBlockEntity` | 390 | 84 处 water/steam + 20 处 heat |
| `AbstractProcessingBlockEntity` | 224 | "processing" 行为本身 |
| `AbstractTurbineGeneratorBlockEntity` | 126 | "turbine_generator" 行为本身 |

**行为抽象其实已经存在了，就是这些抽象基类。** JSON 里的 `behavior` 命名的是一个
**已经用 Java 继承实现好的**东西，不是缺失的机制。把 390 + 224 + 126 行彼此不同的逻辑
塞进一个"通用 BE + 按 behavior 分派"里，只会得到一个巨大的条件分支——**严格更差**，
而且碰的是运行时，离线验证不了。所以 S4 只做**真正缺失的那一环**：让 `behavior`
与规范在**加载期真正生效**（校验 + 报错），而不是重写已经正确的继承结构。

> 说明：S2 的"行为注册表"目前以**词表 + 与源码交叉核对**的形式存在（`MachineStandard.BEHAVIORS`/`BEHAVIOR_BASE`）。
> 真正把 `behavior` id 映射到**运行时分派**属于 S4——在通用 BE 落地前，一个 id→实现的注册表没有消费方，
> 建了就是死代码，所以没有提前建。

## 一、参考实现的调研结论

### GregTech-Modern（`api/machine/MachineDefinition.java` + `registry/registrate/MachineBuilder.java`）

GT 的做法是**代码驱动的声明式**：一台机器 = 一条 fluent builder 链，把**所有**声明性事实
集中在一处，注册进**唯一一个注册表**（`GTRegistries.MACHINES`）：

```
MachineBuilder.builder()
    .name(...).tier(...)                      // 等级
    .recipeType(...) / .recipeTypes(...)      // 能跑哪些配方
    .ui(PanelFactory)                         // 界面怎么画
    .model(...) / .overlayTieredHullModel(...) // 模型
    .shape(VoxelShape) .rotationState(...)    // 形状/朝向
    .hasBER(...)                              // 要不要 BER
    .beforeWorking/.onWorking/.onWaiting/.afterWorking   // 行为回调
    .tooltipBuilder(...) .langValue(...)      // 提示与本地化
    .blockProp(...) .itemProp(...)
```

关键点（**这才是我们要抄的东西**）：
- **一台机器的全部事实只在一处声明**，没有"注册在 A、行为在 B、模型在 C"的散落。
- **注册表 + 通用 BE**：`MetaMachine` 承担共性，具体机器只是配置差异 → 加一台机器成本极低。
- **等级（tier）是一等公民**，一族机器靠 tier 参数化，而不是复制 10 份代码。
- 代价：**加机器要写 Java 并重新编译**，整合包作者改不了。

### Arknights: Endfield（`com.besson.endfield`）

**不是数据驱动**：`src/main/resources/data/arknights_endfield/` 下只有
`structure`/`worldgen`/`tags`，没有任何机器定义 JSON；`blockEntity/` 只有
`ModBlockEntities.java` + `ModCapabilities.java`。即"传统 per-block 代码"路线。

结论：**它的机器编写方式对我们没有可抄之处**，唯一可借鉴的是它也把能力（capability）
集中注册（`ModCapabilities`）而不是散在各处。

### 我们自己的现状（对比之下）

| 维度 | GT | Endfield | 我们（现状） |
|---|---|---|---|
| 结构/形状 | Java `shape()` | Java | **JSON + 3D 编辑器** ✅ 更优 |
| 舱室/IO | Java `PartAbility` | Java capability | **JSON，且按面配槽位** ✅ 更优 |
| 加一台机器 | 1 条 builder 链（要编译） | 若干个类 | **10 段 `ModBlocks` 样板 + 类** ❌ 最散 |
| 等级/族 | ✅ tier 一等公民 | ❌ | ❌ 没有 |
| 行为 | builder 回调集中声明 | per-block 类 | per-BE 类，无统一声明点 |
| 界面 | `.ui(PanelFactory)` | per-block | per-BE |
| schema 版本 | — | — | ❌ 没有 |
| 离线校验 | datagen | — | ✅ 181 条断言 |

**结论：我们最该补的不是"数据驱动"（结构/IO 已经领先），而是 GT 的两个优点 ——
① 一台机器的全部声明集中在一处；② 注册表 + 通用 BE，让"加一台机器"变成加一份数据。**

## 二、提议的规范：「一份 JSON 声明 + 一个行为 id」

### 核心思想

**JSON 是唯一声明源，Java 只提供"行为"。** 加一台机器 = 加一份 JSON + 一行清单
（+ 可选的一个行为类）；加一族机器 = 把 JSON 参数化。

### 机器 JSON 的目标 schema

```jsonc
{
  "schema_version": 1,            // 新增：规范版本，升级时可自动迁移
  "id": "horizontal_steam_boiler",

  // —— 结构（已有）——
  "side_offsets": [[0,3,0],[0,3,2],[0,4,2]],
  "fill_regions": [{"from":[-1,0,-2],"to":[1,2,2]}],

  // —— 舱室 / IO（已有，含按面槽位）——
  "item_proxies":  [{"at":[0,1,-2],"slots":[2],"io":"input","faces":["north"],
                     "face_slots":{"north":[2],"up":[]}, "note":"燃料输入"}],
  "fluid_proxies": [{"at":[0,0,1],"tanks":[0],"io":"input","faces":["south"]}],

  // —— 新增：声明性事实，全部可选，缺省即"通用机器"——
  "behavior": "steam_boiler",     // 行为 id（封闭词表，注册表里查得到才合法）
  "tier": 1,                      // 等级：一族机器靠它参数化
  "family": "steam",              // 族：用于批量生成/倍率
  "recipe_type": "poly_mech:boiler",   // 能跑哪些配方
  "ui": "default_3x3",            // 界面布局 id（对应一个 PanelFactory 注册项）
  "model": "builtin",             // 模型 id
  "lang": "蒸汽锅炉",              // 显示名（缺省用 lang 文件）
  "notes": {"0,3,0": "蒸汽输出口"}
}
```

### 配套的三件事

1. **单一注册循环**：`ModBlocks` 里 10 段手写 `MachineConfig.builder(...)` 换成读
   **机器清单**（`data/poly_mech/machines.json`，或扫描 `machine/*.json`），循环注册
   block/item/BE。新增机器不再需要改 `ModBlocks`。
2. **行为注册表**（`MachineBehaviorRegistry`）：`behavior` id → 行为实现（tick / 菜单 /
   能力）。通用 BE 按 id 分派，per-BE 类退化成"一个行为实现"。
   封闭词表的意义：JSON 里写错 id 会**当场报错**，而不是静默变成一台什么都不做的机器。
3. **schema 版本 + 校验**：`schema_version` 让以后改 schema 能自动迁移；
   `MachineStandard.validate(json, id)` 做封闭词表 + 必填键 + 未知键检查，
   接进现有离线校验（`verifyMachineDefinitions`），改错了直接 FAIL。

### 为什么这条路比 GT 更适合我们

- GT 的 builder 链是**编译期安全**的，但**整合包作者改不了**——这与你之前定的目标
  「让整合包作者们也能轻易使用」直接冲突。我们既然已经把结构/IO 做成 JSON + 3D 编辑器，
  再往"声明全进 JSON"走是一致的，而退回 Java builder 是倒退。
- GT 真正的优点（**声明集中 + 注册表 + 通用 BE**）与"用不用 JSON"无关，
  我们可以**同时拿到两者**：声明集中在 JSON，共性在通用 BE。
- 我们有 GT 没有的：游戏内 3D 结构编辑器 + 181 条离线断言。这两样让
  "JSON 驱动"不再等于"容易写错"。

### 迁移计划（分四步，每步独立可验证）

| 步 | 内容 | 状态 |
|---|---|---|
| S1 | 加 `schema_version` + `MachineStandard` 封闭词表校验，10 份 JSON 补上字段 | ✅ 已完成 |
| S2 | `behavior` 字段 + 行为词表；10 台机器标上行为 id | ✅ 声明层完成（运行时分派见 S4） |
| S3 | 单一注册清单替换 `ModBlocks` 的 10 段样板 | ✅ 已完成 |
| S4 | 加载期校验（让规范与 behavior 在运行时生效） | ✅ 已完成（通用 BE 分派经论证不做，见下） |
| S5 | schema 3：组可嵌套（数据层 + 快照路径寻址 + 大纲多选/缩进冲突） | ✅ 已完成（见上「schema 3」） |
| S6 | schema 4：cube 名字（可选键 + 大纲显示 + 改名） | ✅ 已完成（见上「schema 4」） |

## 三、附：本轮已顺手修掉的两个真 bug

写"按面槽位"界面时，被自己的往返断言抓出来的：

1. **`MachineStructureSnapshot` 的缓存失效**：`cellAt()` 读的是 `cellsByLocal` 缓存，
   而 `setItemFaceSlots`/`setFluidFaceTanks`/`setFaces`/`setRoleKind` 只改了
   `itemProxies`/`fluidProxies` 却没调 `rebuild()` → 界面读到旧值，改完看不到变化。
2. **按面配置存不下去**：序列化端按设计会写出 `"up": []`（=这一面什么都不暴露），
   但解析端 `intArray` 一律拒绝空数组 → **按面配置一存一读就抛异常**。
   已改成"方块级 slots/tanks 不许为空，按面 face_slots/face_tanks 允许为空"。

第 2 个尤其值得记：它证明"空数组"和"没配过"这两种状态必须有明确的、被断言钉住的区分，
否则功能看起来做完了、实际一保存就炸。
