# 太空"输入 → 世界方向"的三条路（交接记录）

> **下一个会话先读这份文件。** 起因：用户 2026-10-03 报两个 bug ——
> ①"滚转 -180 时左右键方向是反的"（**已修**）、②"牵引枪玩家一动就抽"（**已改为抓点模型，待实机复核**）、
> ③"创造飞行头朝下时按空格往屏幕下飞"（**未修，见 §三**）。
>
> 这份文件存在的唯一目的：**别让下一个人（包括我自己）再推一遍已经推过的东西。**

## 一、核心认识：同一个概念有**三条路**，这是本项目反复栽的坑

"玩家按了键 → 该往世界的哪个方向走 / 屏幕朝哪" 这一个概念，在本项目里散成了三条实现路径：

| # | 路径 | 谁在用 | 现状 |
|---|---|---|---|
| 1 | `SpacePlayerData.viewFrame(...)`（四元数，局部轴 = 屏幕左/屏幕上/视线） | **水平移动**（`moveRelative` 注入） | ✅ 已接（`5a4bf61` 起的单来源；`a39653d` 删掉了"创造飞行走原版"那条分叉） |
| 2 | `SpacePlayerData.mcRotation(...)` → 原版 `Camera.setRotation(yaw,pitch,roll)` | **相机** | ✅ 已接（`3189f93` 从 mixin 搬进状态类；注入点必须是 `Camera.setup` 的 TAIL，见 `8464f4a`） |
| 3 | 原版飞行分支按**世界 Y** 施加的竖直分量 | **创造飞行的 Space/Shift** | ❌ **未接** —— 就是用户报的"头朝下按空格往屏幕下飞" |

**教训（本会话新增的硬规矩）**：

> **同一个概念如果存在"分叉"（飞行 vs 走路、客户端 vs 服务端、两种模式……），
> 这个分叉本身必须是状态、必须被报出来、必须参与判据。**
> 否则任何只验其中一条路的判据都是假的安全感。

这条规矩是被三次翻车换来的，见 §四。

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

## 三、★ 待办：创造飞行的**竖直**要接进 viewFrame

### 3.1 症状

创造飞行时，头朝下（俯仰/滚转导致屏幕上下与世界上下不一致）按空格，人往**屏幕下**飞。

### 3.2 根因（字节码证据，`javap -p -c` on `net.minecraft.world.entity.player.Player`）

```java
public void travel(Vec3 travelVector) {
    ...
    if (this.abilities.flying && !this.isPassenger()) {
        // offset 135-142
        double d2 = this.getDeltaMovement().y;        // ← 进入 travel **之前**的 y
        // offset 143-145
        super.travel(travelVector);                   // ← 我们的 moveRelative 注入在这个里面
        // offset 148-152
        Vec3 v = this.getDeltaMovement();
        // offset 154-170  ★
        this.setDeltaMovement(v.x, d2 * 0.6, v.z);    // y 被换回"旧 y × 0.6"
        this.resetFallDistance();
        ...
    }
    ...
}
```

**结论**：飞行时 y 通道被 `★` 那行整个覆盖 ⇒

1. 我们在 `moveRelative` 里沿 `screenUp` 算出的竖直分量**加进去就被丢掉** ——
   这就是为什么水平（路 1）修好了、竖直没跟着变；
2. 飞行的竖直实际由**别处按世界 Y** 驱动 ⇒ 屏幕上下与世界上下不一致时方向就错。

### 3.3 修法（未实施；**不要**在飞行分支上补特例，那会变成第四条路）

原则：**竖直也必须走 `viewFrame`**，与水平同源。落点建议：

- 需要一个新的 mixin 注入点：`Player.travel`（现有 `SpaceInputRotationMixin` 挂在 `Entity` 上，
  **注不进** `Player` 独有的方法）⇒ 要么新建一个 mixin 类，要么把现有 mixin 改成同时 `@Mixin(Player.class)`；
- 在 `Player.travel` 的 **TAIL** 上，对"太空 + `flying` + 本地玩家"：
  用竖直输入沿 `viewFrame` 的 **+Y（屏幕上）** 重写 y 通道（世界竖直分量不再单独存在）；
- 与 `polymech$equalizeZeroGravityY`（`Entity.baseTick` TAIL 里改 y 的那个）保持一致的写法风格。

### 3.4 ★ 还差的**一个事实**（下一轮第一件事，一次 `javap` 就能定）

**竖直输入的值从哪读？** 候选：

- `LivingEntity.yya`（原版 `xxa/yya/zza` 三件套里的竖直分量）——
  在 `LocalPlayer.aiStep` 里由输入填充；
- 或 `travelVector.y`（`Player.travel` 的入参）。

已经查过但**没找到**的地方（别重复查）：`LocalPlayer.aiStep` 前 220 条指令、
`LivingEntity.travel` 的开头、`Player.travel` 的飞行分支本身（它没读输入，只读旧 y）。

定位方法：`javap -p -c -cp build\moddev\artifacts\neoforge-21.1.228-merged.jar <类>`，
在 `LocalPlayer.aiStep` 里找 `Field net/minecraft/world/entity/LivingEntity.yya` 的 `putfield`。

**在确认这一点之前不要动输入链** —— 本会话已经有三次"先推后改"全部改错地方（§2.3）。

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

同类事故累计 5 次（`-q90` / 浮点取模 / `ListTag.getLong` / 漏抄前导负号 / 复刻对复刻）：
**判据与真实现不一致时，先怀疑判据。**

## 五、当前状态（提交 `a39653d`）

- `compileJava` BUILD SUCCESSFUL；`native\jni-smoketest\run-offline-checks.ps1` **21/21 步 exit 0**。
- 契约里与本线相关的 mustNot：
  - `SpaceCameraMixin` 的旧注入点（`ordinal=0` 的 `setRotation(FFF)` INVOKE）；
  - `SpaceInputRotationMixin` 里那句 `flying || isSpectator` 提前返回；
  - `PhysgunBeamPacket` 的 `double endX, double endY, double endZ,`（世界端点不得回归）。
- 实机待验：① 创造飞行 + 滚 -180 + A/D（用户已确认修好）；② 拖着物体在地面走时的
  `[牵引枪·丝滑判据]`（用户上次说没修好，但**不确定测的是哪次构建** —— 见 `docs/render-interpolation.md` §六）；
  ③ 本条 §三 的飞行竖直。
