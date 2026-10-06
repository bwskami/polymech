# 太空"输入 → 世界方向"的三条路（交接记录）

> **下一个会话先读这份文件。** 起因：用户 2026-10-03 报两个 bug ——
> ①"滚转 -180 时左右键方向是反的"（**已修**）、②"牵引枪玩家一动就抽"（抓点模型 + **10-04 消费方式修复**，见 `docs/render-interpolation.md` §七，**待实机复核**）、
> ③"创造飞行头朝下时按空格往屏幕下飞"（**已修**，见 §三 —— §3.4 那个悬着的事实已用反编译源码定死：
> 两个候选**都是死路**，真来源是 `LocalPlayer.aiStep` 沿**世界 +Y** 的那一句）。
>
> 这份文件存在的唯一目的：**别让下一个人（包括我自己）再推一遍已经推过的东西。**

## 一、核心认识：同一个概念有**三条路**，这是本项目反复栽的坑

"玩家按了键 → 该往世界的哪个方向走 / 屏幕朝哪" 这一个概念，在本项目里散成了三条实现路径：

| # | 路径 | 谁在用 | 现状 |
|---|---|---|---|
| 1 | `SpacePlayerData.viewFrame(...)`（四元数，局部轴 = 屏幕左/屏幕上/视线） | **水平移动**（`moveRelative` 注入） | ✅ 已接（`5a4bf61` 起的单来源；`a39653d` 删掉了"创造飞行走原版"那条分叉） |
| 2 | `SpacePlayerData.mcRotation(...)` → 原版 `Camera.setRotation(yaw,pitch,roll)` | **相机** | ✅ 已接（`3189f93` 从 mixin 搬进状态类；注入点必须是 `Camera.setup` 的 TAIL，见 `8464f4a`） |
| 3 | **创造飞行的整条速度链**（原版 `Player.travel` 飞行分支 + `LocalPlayer.aiStep` 的竖直） | **创造飞行的 Space/Shift** | ✅ 已接（`SpaceFlightMixin` + `SpaceFlight`；见 §三） |

**教训（本会话新增的硬规矩）**：

> **同一个概念如果存在"分叉"（飞行 vs 走路、客户端 vs 服务端、两种模式……），
> 这个分叉本身必须是状态、必须被报出来、必须参与判据。**
> 否则任何只验其中一条路的判据都是假的安全感。

这条规矩是被三次翻车换来的，见 §四。

> **（2026-10-03 第二轮新增）阻尼的基底必须与冲量的基底一致。**
> 只要"往哪个方向给输入"用基底 A、"每个 tick 怎么衰减"用基底 B，稳态速度方向就必然偏，
> **偏多少取决于姿态** —— 而姿态恰好在"水平"时让 A=B，于是这类 bug 在平时的姿态下
> 一个都测不出来（§三 就是它：冲量走视线基底、阻尼走世界轴，而 y 通道还被 `travel` 覆盖）。
> 推论：遇到"方向/手感随姿态漂"的症状，**先去数"冲量在哪个基、阻尼在哪个基"**，
> 别去调参数。

## 二、已验证的事实（**不要再重推**）

### 2.1 相机公式是对的 —— 两侧都用**真实现**验过

`native/jni-smoketest/RollInputBasisProbe.java`（离线套件第 21 步）：
输入侧调 `SpacePlayerData.viewFrame(...)`、相机侧调 `SpacePlayerData.mcRotation(...)` 再喂给
**真的 `net.minecraft.client.Camera`**（反射调它 protected 的 `setRotation(FFF)`，读它渲染用的
`getLeftVector/getUpVector/getLookVector`）。

```
姿态 350 个：PASS 350，FAIL 0     （含 roll ±180）
```

⇒ **不要再改 roll 的符号、不要再改 `mcRotation` 的公式。** 它们是对的。

### 2.2 运行时也有判据（`[姿态自检]`）

`SpaceCameraMixin` 在 `setRotation` 之后**立刻**读相机自己的 left/up，与输入方向点积，每 1 秒一行、
FAIL 时进聊天栏：

```
[姿态自检] dot(按A方向, 相机左)=+1.000 dot(屏幕上, 相机上)=+1.000 移动基底=6DOF ⇒ PASS
```

`移动基底=` 那一项是 `SpacePlayerData.movementUsedViewFrame`：**"移动这一 tick 到底走了哪条路"**。
用户实测这一行 PASS ⇒ 路 1 与路 2 一致。

### 2.3 曾把滚转 bug 修错地方（两次），记下来

- 第一次猜"roll 手性与 MC 相反" —— **错**。真因是**注入点**：`Camera.setup` 里 `setRotation` 被调三次
  （`javap` 数出来的 offset 79/170/269），最后一次是 2 参版 = `setRotation(yRot, xRot, 0f)`，
  把我们的 roll 硬置 0。修法 = 注入到 `setup` 的 **TAIL**（提交 `8464f4a`）。
- 第二次猜"公式不对" —— 也**错**（§2.1 证明公式一致）。
- 真因是**第三条路**：`SpaceInputRotationMixin` 里那句
  `if (getAbilities().flying || isSpectator()) return;` 让创造飞行的移动走回原版 yaw-only 基底
  （不含 roll），而相机照滚 ⇒ 滚到 ±180 时按 A 往屏幕右。**已删**（提交 `a39653d`），
  并加了 mustNot 契约防止有人为了"建造顺手"再加回来。

## 三、创造飞行的**竖直**（2026-10-03 第二条会话：已修，记录在此）

### 3.1 症状

创造飞行时，头朝下（滚转 180° ⇒ 屏幕的"上" = 世界下）按空格，人往**屏幕下**飞。

### 3.2 根因（★ 与本节旧版写的**不一样** —— 旧版把根因判浅了）

旧版把根因判在 `Player.travel` 的 `d2 * 0.6` 上，并说"飞行的竖直实际由**别处**按世界 Y 驱动"。
方向对了一半，但"别处"没找到 —— 而**没找到就不能改**（§3.4 自己也这么说）。这一轮用
`neoforge-21.1.228-sources.jar`（NeoForge 的**反编译源码**，比 `javap` 好读且是权威）定死了：

```java
// LocalPlayer.aiStep  —— 竖直冲量的**唯一**来源
if (abilities.flying && this.isControlledCamera()) {
    int j = 0;
    if (this.input.shiftKeyDown) j--;
    if (this.input.jumping)      j++;
    if (j != 0) {
        this.setDeltaMovement(this.getDeltaMovement()
            .add(0.0, (double)((float)j * abilities.getFlyingSpeed() * 3.0F), 0.0));   // ← 沿**世界 +Y**
    }
}

// Player.travel  —— y 通道被整条覆盖
if (this.abilities.flying && !this.isPassenger()) {
    double d2 = this.getDeltaMovement().y;      // ← 读于 super.travel **之前**
    super.travel(travelVector);                 // ← 我们的 moveRelative 注入在这里面
    Vec3 vec31 = this.getDeltaMovement();
    this.setDeltaMovement(vec31.x, d2 * 0.6, vec31.z);   // ★ y 被覆盖 ⇒ moveRelative 那份竖直分量被丢
    this.resetFallDistance();
    this.setSharedFlag(7, false);
}
```

**于是有两个后果，而不是一个**（第二个是旧版没看到的）：

1. **竖直方向永远是世界 +Y** ⇒ 屏幕上下与世界上下不一致时按空格方向就错（用户报的那条）；
2. **水平输入里带世界竖直分量的那一部分也会被丢** —— 因为 `moveRelative` 加进去的 y 被 `★` 覆盖。
   滚到 ±90° 时"屏幕左"就是世界竖直 ⇒ **按 A 几乎没有持续位移**（只有一 tick 的冲量）。
   也就是说这是**同一个根因**的两个面，只修竖直会留一个对称的洞。

再往下一层，根因其实是**阻尼的基底与冲量的基底不一致**：原版把阻尼做在**世界轴**上
（`x,z × f3=0.91`、`y × 0.6`），而输入意图是**视线轴**的。两基不同 ⇒ 稳态速度方向必然偏；
偏多少取决于姿态，**水平姿态下两基恰好重合，所以平时完全看不出来**（这就是为什么它一直没被发现）。

### 3.3 ★ 修法（已实施）

原则不变：**竖直也必须走 `viewFrame`，与水平同源**。但"在 `Player.travel` 的 TAIL 上重写 y"
**做不到** —— 要让方向对，阻尼必须与冲量同基，而"同基阻尼"在原版结构里表达不出来
（它把 `×0.91` 和 `×0.6` 分别绑死在 world x/z 与 world y 上）。所以只能把这一段整条拿过来自己做：

| 文件 | 作用 |
|---|---|
| `src/main/java/com/mss/polymech/space/SpaceFlight.java` | **纯数学**：视线基底冲量 + 逐轴阻尼。抽成纯类是为了让离线探针能调**真实现**（§四的死判据教训） |
| `src/main/java/com/mss/polymech/mixin/SpaceFlightMixin.java` | `@Mixin(Player.class)`，在 `travel` 的 **HEAD 取消**，对"太空 + `flying` + 本地玩家"接管整条飞行速度链 |

每 tick 做四件事（**照 space 0.3.0 的 `MixinPlayerFlight`**，它的 `@Inject(travel, HEAD, cancellable)` 就是这个）：

```
1) 撤掉 aiStep 已加进来的世界 +Y 冲量   delta.y -= ascend · flySpeed · 3
2) 加视线基底冲量                       delta += screenLeft·xxa·h + viewFwd·zza·h + screenUp·ascend·flySpeed·3
3) self.move(MoverType.SELF, delta)     原版 Entity.move（碰撞夹取照旧）
4) 沿视线基底逐轴阻尼：水平(屏幕左/视线) × 0.91，竖直(屏幕上) × 0.6
```

- 稳态速度方向 = 输入方向（阻尼在冲量同一个基下是对角的）；
- **水平姿态下与原版逐位等价**（水平基底的旋转不改变"水平面 vs 竖直"的分解）⇒ 手感没变；
- 量级照原版。**注意公式**：递推是 `v ← D(v + 冲量)`（冲量也吃一次阻尼），
  所以稳态 = **阻尼 × 冲量 / (1 − 阻尼)** ⇒ 竖直 `0.6×冲量/0.4 = 1.5×`、
  水平 `0.91×冲量/0.09 = 10.11×`。
  ⚠️ 别写成 `冲量/(1−阻尼)`（= 2.5× / 11.11×）—— 那是 `v ← D·v + 冲量` 的解，差一个阻尼因子。
  （本轮 `FlightVerticalProbe` 第一版就是这么算错的，把**正确**的实现报成 FAIL ——
  同类事故第 6 次：判据与真实现不一致时，先怀疑判据。）

**不是"第四条路"**：一取消，飞行这一 tick 就只有这一条速度来源；而且它与走路那条
（`SpaceInputRotationMixin` 的 `moveRelative`）读**同一个** `viewFrame`。分叉本身
（飞行 vs 走路）记进了 `SpacePlayerData.markMovementBasis`，会出现在 `[姿态自检]` 的
`移动基底=` 那一项里 —— 这正是 §一那条硬规矩要求的"分叉必须是状态、必须被报出来"。

**与物理层的分工**：飞行时 `ClientPhysics.drive()` 本来就返回 `false`
（`"创造飞行：位置交回原版"`，见 `ClientPhysics.java`），所以第 3 步走的是**原版**
`Entity.move`，刚体只是在 `afterPlayerTick` 里跟过来。**本改动不碰物理接管那条链。**

**原版副作用的保全**（取消 `Player.travel` 会跳过它们，逐条补回，免得"修好了方向、丢了别的"）：
`resetFallDistance()`、清鞘翅滑翔 flag7（原 `setSharedFlag(7, false)`；`setSharedFlag` 是 `Entity`
的 protected、而 `@Shadow` 只认目标类自身声明的成员，故走 public 的 `Player.stopFallFlying()`，
净效果同为 flag7=false —— **不能省**：`updateFallFlying()` 只在 `!level.isClientSide` 时才写 flag7，
客户端的 flag7 平时就靠飞行分支那一行清）、`calculateEntityAnimation(false)`
（原在 `LivingEntity.travel` 末尾，不补的话飞行时手脚不再摆动）。

### 3.4 ★ 旧版悬着的"一个事实" —— 已定死，**两个候选都是死路**

旧版问："竖直输入的值从哪读？候选 `LivingEntity.yya` / `travelVector.y`。"

**答案：都不是。** 在 1.21.1 的 `LivingEntity` 里 `yya` **只被读、从没被写过**：

```
$ grep -rn "yya" <反编译源码>
LivingEntity.java:225:    public float yya;                                        ← 声明
LivingEntity.java:2810:   Vec3 vec31 = new Vec3(xxa, (double)this.yya, zza);  ← 唯一一次使用
```

⇒ `yya` 恒为 0，`travelVector.y` 也恒为 0。竖直真正的来源是
**`LocalPlayer.aiStep` 里那句沿世界 +Y 的 `delta.y += j·flySpeed·3`**（§3.2 第一段）。

**教训（新增）**：旧版 §3.4 列了两个候选就停了 —— 而"候选列表"本身也会骗人。
正确姿势是**先问"这个字段到底有没有人写"**（一次 `grep` 的事），再谈它是不是来源。

### 3.5 怎么验证（`FlightVerticalProbe`，离线套件第 22 步）

判据设计照 §四的反面（**必须能 FAIL**）：

| 组 | 判据 | 为什么需要它 |
|---|---|---|
| **A 中性** | 水平姿态（pitch=0,roll=0，任意 yaw）下新实现与原版一致到 **float 精度**（1e-6；基底是 float 四元数，实测最坏 1.4e-7） | 没有它，"改好了"可能只是把手感改了 |
| B/C 方向 | 任意姿态下"只按空格 ⇒ 速度沿**屏幕上**"、"只按 A ⇒ 沿**屏幕左**"（dot > 0.999） | 用户报的那条 |
| D/E 量级 | 稳态 = **阻尼×冲量/(1−阻尼)**，与原版同数（1.5× / 10.11×）；容差 1e-4（见下） | 防止"方向对了但速度被改" |
| **F 对照** | 原版结构（阻尼在世界轴 + y 被覆盖）在**多数姿态**下方向错 | ★ 判据必须有区分力，否则就是死判据 |
| G 报告姿态 | 滚转 180° 等 4 个姿态，打印两侧速度向量：原版 dot≈−1（往屏幕下飞）、新实现 dot≈+1 | 一眼能看懂 |

冲量/阻尼调的是**真实现**（`SpaceFlight.impulse/damp`），基底来自**真** `SpacePlayerData.viewFrame`，
飞行速度真值取自**真** `Abilities.getFlyingSpeed()`。唯一被复刻的是 `steadyVanilla`
—— 它是**被反驳的对象**（原版结构），不是判据的来源。

**★ 顺带钉死的两件事**（都查过源码，别再重推）：

1. **`yya` 是死路**（§3.4）：`LivingEntity` 里只被读、从没被写 ⇒ `travel` 入参的 y 恒为 0。
2. **`LocalPlayer.isEffectiveAi()` 返回 `true`** ⇒ `LivingEntity.aiStep` 里那句
   `else if (!isEffectiveAi()) setDeltaMovement(getDeltaMovement().scale(0.98))`（行 2734-2735）
   **不会**作用在本地玩家身上。所以客户端的竖直链就是干净的两步
   （`aiStep` 加冲量 → `travel` 乘 0.6），探针的递推 `v ← damp(v+冲量)` 与真机**逐字同形**，
   稳态确实就是 **1.5× / 10.11×**。
   （若它返回 false，就会多一层 0.98，稳态变成 1.4563× —— 这次核对**避免了一个错的注脚**。
   顺带：`LivingEntity.aiStep` 行 2743-2759 那个"|v|<0.003 归零"的 snap 也在这条链上，
   但飞行冲量是 0.15 > 0.003，不会被它吃掉。）

**★ 容差必须按"稳态增益"定，不能拍脑袋**（本轮第二次"先怀疑判据"）：

| 量 | 稳态增益 `d/(1−d)` | 对基底误差的敏感度 | float 基底（偏 ~1.2e-7）⇒ 稳态相对误差 |
|---|---|---|---|
| 竖直（屏幕上） | 1.5 | 约 2.6 倍 | ~4e-7（1e-6 容差够） |
| 水平（屏幕左） | 10.11 | **约 11.7 倍** | **~4e-6（1e-6 容差不够，实测最坏 4.019e-06）** |

第一版 D/E 都取 1e-6 ⇒ 竖直过、水平 **140/490 姿态被判 FAIL**，而实现是对的。
诊断行（探针会打印）一次就定位：`|屏幕左|=1.000000119 左·上=-2.980e-08 |q|=0.999999970`
—— 基底是 float，`1/(1−0.91)=11.11` 的增益把它放大 11.7 倍。
**结论：分母里出现 `1−阻尼`（阻尼接近 1）时，容差要按 `增益×float epsilon` 估，别照抄竖直那档。**
真正的行为改动是 O(1) 量级，1e-4 一样抓得住。

**实机待验**：创造飞行 + 滚转 180°（或俯仰 ±90°）按空格/Shift 应沿屏幕上/下走；
滚到 ±90° 按 A/D 应有持续位移（旧版这里几乎不动）。

## 四、本会话最贵的教训：**死判据**

`RollInputBasisProbe` 第一版是这样写的（它自己的注释里写着）：

> 它没法 `new SpacePlayerData`（那个类当时带静态 `DeferredRegister`，类初始化要整套 FML 环境），
> 所以这里**按它的约定解析构造**那组基底。

⇒ **两边都是复刻** ⇒ 复刻对复刻必然自洽 ⇒ **永远 3/3**。
于是"用户说还是反的，探针却全过"。

由此产生两个结构性改动（都要保留）：

1. **`SpacePlayerData` 拆成纯状态类**：DataAttachment 的注册管道搬到 `SpaceAttachments`
   ⇒ 离线探针可以 `new` 真类。**别再往 `SpacePlayerData` 里加静态注册字段。**
2. **相机那侧的数学搬进 `SpacePlayerData.mcRotation(...)`**：原来在 mixin 里现算，
   探针调不到、只能复刻。**别再把它搬回 mixin。**

同类事故累计 **6** 次（`-q90` / 浮点取模 / `ListTag.getLong` / 漏抄前导负号 / 复刻对复刻 /
**本轮：`FlightVerticalProbe` 把稳态公式写成 `冲量/(1−阻尼)`，而真实现是 `D(v+冲量)` ⇒
应为 `阻尼×冲量/(1−阻尼)`，于是 D/E 两组把正确的实现报成 FAIL**）：
**判据与真实现不一致时，先怀疑判据。**

## 五、当前状态

上一节（§一～§二、§四）的基线是提交 `a39653d`：`compileJava` BUILD SUCCESSFUL，
`native\jni-smoketest\run-offline-checks.ps1` **21/21 步 exit 0**。

本轮（§三 飞行竖直）之后：

- `compileJava` BUILD SUCCESSFUL；离线套件扩到 **22 步**（新增 `FlightVerticalProbe`）。
- 新增文件：`com/mss/polymech/space/SpaceFlight.java`（纯数学）、
  `com/mss/polymech/mixin/SpaceFlightMixin.java`（`@Mixin(Player.class)`，已注册进
  `poly_mech.mixins.json` 的 **client** 段）。
- 契约新增（`check-artifact.ps1`）：
  - **must**：`SpaceFlightMixin` 的注入点（`travel` 的 **HEAD 取消**）、`data.viewFrame(`、
    `SpaceFlight.cancelVanillaVertical(`、`SpaceFlight.damp(`、`stopFallFlying()`、
    `calculateEntityAnimation(false)`、`markMovementBasis(true)`；
    `SpaceFlight` 的两个阻尼常量与"分解到基底再合成"那一行；
    `poly_mech.mixins.json` 里的 `"SpaceFlightMixin"`（**注册漏了就是静默失效**）；
  - **mustNot**：`SpaceFlightMixin` 里出现 `travelVector.y`（那个字段恒为 0，
    是 §3.4 的错误前提）；
  - **mustNot（2026-10-03 追加）**：`SpaceInputRotationMixin` 里出现 `shiftKeyDown`
    —— 钉死"别在这里补 Space/Shift → 上下"这条地雷（见下）。

### ★ 注释也钉在了四个"会误导人"的地方（本轮追加）

契约只能钉"字符串在不在"，钉不住"读者会不会理解错"。所以以下四处补了注释，
**它们都是"照旧说法做就会改错"的位置**：

| 位置 | 旧说法的毛病 | 现在写的 |
|---|---|---|
| `SpaceInputRotationMixin`（`moveRelative` 注入内） | 读者会以为移动都要在这里处理 | 创造飞行**走不到这里**（`travel` HEAD 已取消，`moveRelative` 不执行）⇒ 别在这补飞行分支、尤其别补 Space/Shift 竖直（① 历史坑：零重力无阻尼下无限累积；② 现在补就是两份来源叠加） |
| `ClientPhysics.drive()` 的 `playerFlies` 分支 | "位置交回原版"容易被读成"创造飞行 = 完全原版" | 交回的只是**位置积分**；**速度**由 `SpaceFlightMixin` 算 ⇒ 别把 `SpaceFlightMixin` 当冗余删掉 |
| `SpacePlayerData.viewFrame()` | 只提了相机 + 输入两个消费方 | 改成 **三个消费方**表（相机 / 走路游泳 / 创造飞行），并写明"任何移动路径都不得自算基底、不得只改其中一条" |
| `SpaceTravelMixin` 类注释 | "走多少/怎么停由原版 `travel` 负责" —— **对飞行已不成立** | 改成三分工表 + 明确指出那句旧话对飞行错在哪（世界 +Y 竖直 + y 通道被覆盖） |
- 契约里与本线相关的 mustNot（沿用）：
  - `SpaceCameraMixin` 的旧注入点（`ordinal=0` 的 `setRotation(FFF)` INVOKE）；
  - `SpaceInputRotationMixin` 里那句 `flying || isSpectator` 提前返回；
  - `PhysgunBeamPacket` 的 `double endX, double endY, double endZ,`（世界端点不得回归）。

### ⚠️ 本轮踩到的工具坑（会再犯，记死）

**`edit` 工具会剥掉 UTF-8 BOM。** 本轮用 `edit` 改 `run-offline-checks.ps1` /
`check-artifact.ps1` 之后，两个脚本都变成"无 BOM 的 UTF-8"，而 Windows PowerShell 5.1
按 **GBK** 解 ⇒ 满屏乱码 + `The string is missing the terminator` 解析失败
（正是这两个文件自己头部警告的那件事）。
**改这两个（以及任何带 BOM 的）脚本：要么别用 `edit`，要么改完立刻用字节级补回 BOM。**
校验/补 BOM 一律走 `[System.IO.File]::ReadAllBytes/WriteAllBytes`，
**不要**用 `Get-Content -Raw` + `WriteAllText`（会把整份中文脚本按 ANSI 解成乱码）。

- 实机待验：① 创造飞行 + 滚 -180 + A/D（用户已确认修好）；
  ② 拖着物体在地面走时的 `[牵引枪·丝滑判据]` —— 10-03 抓点模型上线后仍 FAIL，
  **10-04 已归因并修复**（根因不是数据模型而是**消费方式**：直读 100Hz 步进阶梯，
  见 `docs/render-interpolation.md` §七；本轮实机复验待做）；
  ③ **创造飞行 + 滚转 180°（或俯仰 ±90°）按空格/Shift** ⇒ ✅ 用户 2026-10-04 确认
  **不同滚转角度的上升/下降全部通过**；
  ④ **创造飞行 + 滚到 ±90° 按 A/D** ⇒ 应有持续位移（旧版这里几乎不动，见 §3.2 后果 2）——**仍待验**。
