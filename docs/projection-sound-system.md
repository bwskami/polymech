# 物理体互动补全：音效搬运 + 中键选取

> 需求（用户 2026-10-06）：**"增加中键选取物理体内方块的功能，增加物理体完整的音效系统（部分互动原本应该有的音效没用）"**
> 本轮实现只动**本项目自己的物理体栈**（`com.mss.polymech.physics` + `client/physics`）。
> `com.mss.polymech.mps`（space/MPS 克隆栈）是另一套并行实现，本轮**未改** —— 它本来就有
> `handlePickBlock`，但同样的"事件只在原版射线打中真方块时才发"的坑也埋在那里（见 §3）。

---

## 一、病根：两边同时缺位

物理体的方块**不在世界里**，它们活在隐藏的**投影维度**（`ProjectionManager`）。破坏 / 放置 /
使用（开箱、按钮、拉杆、门、熔炉…）全部在**那边**跑原版逻辑。于是两件事同时成立：

1. **声音发在投影维度** —— `ServerLevel.playSeededSound` 与 `ServerLevel.levelEvent` 都按
   `this.dimension()` 广播，而投影维度里**一个玩家都没有** ⇒ 声音与事件全发给空气。
2. **客户端没有原版那份"本地预测"** —— 我们把左键/右键都吞掉转发服务端了
   （`PhysicsBodyInteractionClient.onInteraction` → `event.setCanceled(true)`），
   原版"客户端先播一遍、服务端跳过发起者"的配对机制整条不存在。

表现正是用户说的"点了，但什么声都没有"。**与掉落物是同一个病**：隐藏维度里发生的一切
"表现"都必须挪到玩家看得见的世界里（见 `ProjectionDropRelocationMixin` / `ProjectionManager#relocateDrop`）。

---

## 二、音效系统：两条咽喉，一次拦全部

### 2.1 按坐标播的声音 —— `ProjectionSoundRelocation`

* 钩子：NeoForge 的 `PlayLevelSoundEvent.AtPosition` / `.AtEntity`
  （`ServerLevel.playSeededSound` 头上那句 `EventHooks.onPlaySoundAt*`）。
* **为什么用它而不是逐个交互补 `playSound`**：方块自己的交互音在原版方块代码里、
  机器/红石/流体的随机音在 tick 里、第三方模组的方块更没法枚举 —— 这是 `Level#playSound`
  的**唯一咽喉**，在这里拦一次 = 全部覆盖。属于"补机制，不要在上层逐点打补丁"。
* **归属与坐标**：`ProjectionManager.mapSoundPoint(x,y,z)`
  * 投影坐标 → `nearestBodyFor`（与掉落物搬运**同一套**判据，含"射出地皮边界"时就近归属）
  → `slotOf` / `startOf` 算**局部连续点** → `PhysicsBodyTracker.localPointToWorld`
  → `RayBox.toWorldPoint`（与"世界格⇄局部格"同一对映射）。
  * **点级，不是格心**：`playSound` 的坐标是连续值（格心 + 0.5 + 随机半格偏移）。
    只搬格心会偏最多 √3/2≈0.866 格 —— 离线实测 0.707 格、18045/48600 个采样落进**别的世界格**
    （`ProjectionSoundMappingProbe` 的 C/C2 组）。
* **`except` 传 null 是有意的**：原版 `level.playSound(player, …)` 会跳过发起者，
  因为发起者那一份由客户端本地预测补上；我们客户端碰不到投影维度，没有那份预测 ——
  保留 except 的话**交互者自己听不到**。
* 归属不到体 ⇒ 取消并计入"无归属静音"（留在原地本来也是 0 听众）。

### 2.2 levelEvent（破坏碎屑 2001、活塞/发射器/音符盒那一类）—— `ProjectionLevelEventMixin`

* 钩子：`ServerLevel#levelEvent(Player, int, BlockPos, int)` 的 HEAD + cancellable。
  `LevelAccessor#levelEvent(int, BlockPos, int)` 这个 3 参重载是 default 转发，
  所以所有路径都汇到这一个方法。
* 搬运后**取消投影里的那一次**，避免两边都发。
* **`except` 同样传 null**（见上）。
* 落点取 `BlockPos.containing(world)`：`levelEvent` 只吃整数格，这是入参形状决定的上限
  （转过的体上落点最多偏半格，离线探针把这条量化在 A2/C 组里）。

### 2.3 由此删掉的一处手发（重要）

`PhysicsBodyInteraction#breakBlock` 原来在世界侧**手发** `levelEvent(2001)`。
现在改成只靠 ③ 里原版 `playerWillDestroy` 在投影发的那一次（由 mixin 搬运）：

* 两处都发 = **双份**碎屑与双份破坏音；
* 只手发 = 活塞砸掉 / 床 / 双格植物 那些**自己**发 2001 的路径全都要逐个补（第一版就是这么漏的）。
* 安全性核对过：`Block#playerWillDestroy` → `spawnDestroyParticles` → 2001，
  10 个 override（床/门/双格植物/活塞头/潜影盒/TNT/绊线…）**全部调用 super** 或自己发 2001。

### 2.4 补上的一声：`state.attack`

原版 `ServerPlayerGameMode` 在算挖掘进度之前有一句 `blockstate.attack(level, pos, player)`
（音符盒被开挖"叮"一声、部分方块的攻击钩子挂在它上面）。克隆栈 MPS 有它、
本项目的 `PhysicsBodyInteraction` 漏了它 ⇒ 物理体上的音符盒永远不出声。
现在在**生存模式开挖换格/超时重启**处、以及**创造模式每次破格**处各补一次
（与原版节奏一致：创造是一点一破，方块马上就没了，不会连响）。

---

## 三、中键选取：为什么不能挂 `InteractionKeyMappingTriggered`

原版的中键入口是 `Minecraft#pickBlock()`，它**第一行**就是：

```java
if (this.hitResult != null && this.hitResult.getType() != HitResult.Type.MISS) {
    if (ClientHooks.onClickInput(2, keyPickItem, MAIN_HAND).isCanceled()) return;
    …
```

也就是说 NeoForge 那个事件**只在"原版射线打中了真方块"时才发**。瞄准悬空的物理体时背后是
空气 ⇒ 原版 `hitResult = MISS` ⇒ **事件根本不触发** —— 挂在它上面的选取（space/MPS 的做法）
只能"有时灵有时不灵"；而在背后有真方块时，不拦就还会把**挡在体后面那块**选进背包。

本轮挂点：`InputEvent.MouseButton.Pre`（`MouseHandler.onPress` 里、**KeyMapping 登记之前**）。
命中体 ⇒ 做选取并 `setCanceled(true)`（取消后 `KeyMapping.click` 不执行 ⇒ 原版 `pickBlock`
那一刻压根不会跑）；没命中 ⇒ 放行原版。

选物品逐行对齐原版 `Minecraft#pickBlock` 的方块分支 + MPS 的体局部坐标：

| 项 | 做法 |
| --- | --- |
| 方块状态 | 从**体的方块表**读（`stateOf`），不是世界 |
| `getCloneItemStack` | 照 MPS：`mc.level` + 局部格（见 §5 边界） |
| Ctrl 带 NBT | 从**体的方块实体表**（`PhysicsBodyBlockEntityPacket` 的 tag）`BlockEntity.loadStatic` 还原，方式与 `PhysicsBodyRenderer` 渲染时同一份 |
| 创造 | `setPickedItem` + `handleCreativeModeItemAdd`（照原版） |
| 生存 | 热键栏直接选中 / 背包外 `handlePickItem`；背包里没有则什么都不给（原版语义） |
| 守门 | 开界面/覆盖层时中键归界面（该事件在界面处理**之前**发，必须自己挡）；旁观者中键是旁观者菜单 |
| 日志 | `[PolyMech] [中键选取] 体 … 格 … <方块> -> <物品>（模式）` |

---

## 四、判据（怎么验证）

### 离线（不开游戏）

```powershell
.\gradlew.bat compileJava --console=plain          # 期望 stdout 出现 BUILD SUCCESSFUL
.\native\jni-smoketest\run-offline-checks.ps1      # 期望 24/24 步、exit 0
```

* **第 24 步 `ProjectionSoundMappingProbe`**（新增，直接调真实现，不是复刻）
  * A1 任意刚体姿态（含 3e5 量级大坐标）下 `toWorldPoint ⇄ toLocalPoint` 往返闭合
  * A2 搬运后落格稳定 —— 反查 floor 仍是原格（碎屑不落到隔壁格）
  * B `toWorldCenter` 必须**逐位**等于 `toWorldPoint(l+0.5)`（"格心"是"点"的特例，一份实现）
  * C/C2 反例：先 floor 成格再按格心搬 ⇒ 最大偏 0.707 格、18045/48600 采样落进别的世界格
* **`check-artifact.ps1` 里本轮新增的 5 条契约**（源文本级，漏了编译器不报错）：
  1. `ProjectionSoundRelocation` 必须走 `ProjectionManager.mapSoundPoint(`（唯一实现）
  2. `ProjectionManager` 必须走 `PhysicsBodyTracker.localPointToWorld(bodyId,`（**点级**）
  3. `PhysicsBodyInteractionClient` 必须有 `InputEvent.MouseButton.Pre`（挂点不能回退）
  4. `poly_mech.mixins.json` 必须有 `ProjectionLevelEventMixin`（钩子漏注册是静默的）
  5. **不得** `PhysicsBodyInteraction` 再出现 `PARTICLES_DESTROY_BLOCK`（手发=双份）
  * 另外把"localToWorld 与 RayBox 共用同一对映射"那条契约的字面量从 `toWorldCenter(...)` 换成
    `toWorldPoint(...)` —— 实现改成"格心 = 点 + 0.5 的转发"之后契约跟着走，含义不变。

⚠️ 改 `check-artifact.ps1` / `run-offline-checks.ps1` 会**剥掉 BOM**，跑起来满屏乱码 +
"missing the terminator"。改完用字节级补回（前者单 BOM `EF BB BF`，后者双 BOM）：

```powershell
$p = 'native\jni-smoketest\check-artifact.ps1'
$t = [System.Text.UTF8Encoding]::new($false).GetString([System.IO.File]::ReadAllBytes($p)).TrimStart([char]0xFEFF)
[System.IO.File]::WriteAllBytes($p, [byte[]](0xEF,0xBB,0xBF) + [System.Text.UTF8Encoding]::new($false).GetBytes($t))
```

### 游戏内（判据是程序给的，不是靠耳朵猜）

```
/polymech physics projection            # 新增一行：音效搬运 声音 N / 事件 M / 无归属静音 K
/polymech physics soundtest <id> <dx> <dy> <dz>
```

`soundtest` 在**投影维度**里放一声按钮音 + 一次 2001，同一 tick 读搬运计数：

```
§aPASS 音效搬运　声音计数 3→4（+1）　事件计数 1→2（+1）
  投影 (320.5, -63.5, 0.5) → 世界 (100.50, 64.00, -200.50)　维度 minecraft:overworld
  程序判据到「已广播」为止；听觉部分自己确认：站到该体旁边，应听到一声按钮咔 + 一次破坏碎屑
```

**两条咽喉各判一次**（`proj.playSound` 走事件钩子、`proj.levelEvent` 走 mixin），
计数不在同一 tick +1 就是 FAIL。前置：该格在投影里必须有方块
（`/polymech physics placeblock <id> <dx> <dy> <dz> stone`）。

中键选取：看日志 `[PolyMech] [中键选取]`，或直接在游戏里试
（悬空体、背后有真方块、Ctrl+创造 三种场景各一次）。

---

## 五、已知边界（诚实记录）

1. **`ServerLevel.sendParticles` 不在本轮范围**：需求是音效。常见破坏/交互粒子走的是
   `levelEvent`（已搬），但方块代码直接 `sendParticles` 的那条仍只发投影维度。
2. **`PlayLevelSoundEvent.AtEntity` 只认"实体本身在投影里"**（事件只带 `entity.level()`）。
   方块代码几乎都用"按坐标播"的重载，所以方块交互已覆盖；"投影里播、锚点是世界里的玩家"
   这种组合没有钩子可辨，当前代码里没有这条调用路径。
3. **恰落在格边界上的点**（局部偏移恰为 0）经浮点旋转后反查可能落到邻格 —— 但本项目
   **从不对局部点取整**（搬运只做正向），唯一的取整是 levelEvent 那步取世界格，
   所以无影响；探针 A2 因此只采样格内部的点。
4. **`getCloneItemStack` 传的是客户端世界 + 局部格**（照 MPS）：只有极少数方块会去读
   `level.getBlockState/getBlockEntity(pos)`（潜影盒取颜色最典型），它们读到的是
   客户端世界在那个局部坐标上的东西，可能不对。真要修得给一个能解析体局部格的
   `LevelReader` 包装（`LevelReader` 接口面很大），本轮按"照抄优先"没有做。
5. **中键选取只覆盖本项目的物理体栈**；`mps` 栈的选择逻辑仍挂在
   `InteractionKeyMappingTriggered` 上，同样存在 §3 的 MISS 缺口。
