# 牵引枪「抓住时的旋转控制」—— 侦察、设计、进度

> 起点：`docs/mps-clone-plan.md` 第 3547–3552 行记录的四个已知缺口之③
> 「**抓住时的旋转控制**（GMod 用滚轮+按键转物体）：现在只能靠弹簧自然摆动」。
> 本文件是这一项的**唯一权威记录**：参考取证、设计决策、已落地/未落地、下一步接线计划。
>
> 状态：**核心已落地并提交（`a2e3805`，2026-10-01）；接线未做（见 §六）**。

---

## 一、参考是谁

牵引枪**不**对照 space（space 0.3.0 里没有任何牵引枪，只有
`org/deep_space_studio/space/sui/network/DragUpdatePacket.java`）。
参考是 `C:\Users\34573\Desktop\Simulated-Project-main` 的 **Creative Physics Staff**：

```
simulated/common/src/main/java/dev/simulated_team/simulated/content/physics_staff/
    PhysicsStaffAction.java          PhysicsStaffClientHandler.java
    PhysicsStaffItem.java            PhysicsStaffItemRenderer.java
    PhysicsStaffRenderHandler.java   PhysicsStaffServerHandler.java
    PhysicsStaffSubLevelObserver.java
simulated/common/.../network/packets/physics_staff/
    PhysicsStaffActionPacket.java    PhysicsStaffBeamPacket.java
    PhysicsStaffDragPacket.java      PhysicsStaffDragSessionsPacket.java
    PhysicsStaffLocksPacket.java
```

---

## 二、★ 参考的机制（逐字取证，这是设计的全部依据）

### 2.1 服务端：每物理 tick 给约束的 6 个轴设**马达**

`PhysicsStaffServerHandler.physicsTick`（第 298–337 行）与 `attachConstraint`（339–344）：

```java
private void physicsTick(final SubLevelPhysicsSystem physicsSystem) {
    if (this.constraint != null) { this.constraint.remove(); this.constraint = null; }
    this.attachConstraint(physicsSystem);

    if (player != null && this.constraint != null) {
        final float angularStiffness = config.physicsStaffAngularStiffness.getF();
        final float angularDamping   = config.physicsStaffAngularDamping.getF();
        final float linearStiffness  = config.physicsStaffLinearStiffness.getF();
        final float linearDamping    = config.physicsStaffLinearDamping.getF();

        // 角轴：目标角 0 + 刚度/阻尼  ⇒ 这是一个 PD 伺服
        for (final ConstraintJointAxis angularAxis : ConstraintJointAxis.ANGULAR)
            this.constraint.setMotor(angularAxis, 0.0, angularStiffness, angularDamping, false, 0.0);

        final double partialTick = physicsSystem.getPartialPhysicsTick();
        final double eyePosX = Mth.lerp(partialTick, player.xOld, player.getX());
        final double eyePosY = Mth.lerp(partialTick, player.yOld, player.getY()) + player.getEyeHeight();
        final double eyePosZ = Mth.lerp(partialTick, player.zOld, player.getZ());

        this.localGoal.set(this.playerRelativeGoal).add(eyePosX, eyePosY, eyePosZ);
        this.orientation.transformInverse(this.localGoal);          // ← 目标点表达在 drag orientation 的坐标系里

        this.constraint.setMotor(ConstraintJointAxis.LINEAR_X, this.localGoal.x(), linearStiffness, linearDamping, false, 0.0);
        this.constraint.setMotor(ConstraintJointAxis.LINEAR_Y, this.localGoal.y(), linearStiffness, linearDamping, false, 0.0);
        this.constraint.setMotor(ConstraintJointAxis.LINEAR_Z, this.localGoal.z(), linearStiffness, linearDamping, false, 0.0);
    }
}

private void attachConstraint(final SubLevelPhysicsSystem physicsSystem) {
    final FreeConstraintConfiguration config =
            new FreeConstraintConfiguration(JOMLConversion.ZERO, this.plotAnchor, this.orientation);
    this.constraint = pipeline.addConstraint(null, this.subLevel, config);
}
```

**结论（这一条决定我们怎么写）**：参考的**位置与朝向都走弹簧-阻尼伺服**，
由物理引擎的马达驱动刚体 —— **不是** `setPose` / `setRotation`。
所以"直接写朝向"不是参考的做法；我们线性那半（`PhysgunSpring` → `applyImpulse`）
已经是同一个机制类，缺的只是**角**那半。

### 2.2 客户端：**按键进入旋转模式 + 鼠标移动**转物体；滚轮是调距离

`PhysicsStaffClientHandler`：

```java
private boolean isRotating() {                       // 第 428 行
    return this.holdingStaff && this.dragSession != null && SimKeys.ROTATE_MODE.isPressed();
}

public Result onMouseMove(final double yaw, final double pitch) {   // 第 551 行
    if (handler.isRotating()) {
        final Vec3 axis = mc.player.calculateViewVector(0.0f, mc.player.getYRot() - 90.0f);  // 世界侧向轴
        final Quaterniond orientation = handler.dragSession.dragOrientation();
        final double rotationSensitivity = config.physicsStaffRotateSensitivity.get();

        final double yawChange = Math.toRadians(yaw) * rotationSensitivity;
        orientation.rotateLocalY(yawChange);          // 偏航绕**物体自身**的 Y
        orientation.premul(new Quaterniond(new AxisAngle4d(
                Math.toRadians(-pitch) * rotationSensitivity, axis.x, axis.y, axis.z)));  // 俯仰绕**世界侧向轴**
        return new Result(true);
    }
    return InteractCallback.super.onMouseMove(yaw, pitch);
}

public Result onScroll(final double deltaX, final double deltaY) {  // 第 576 行
    if (handler.holdingStaff && dragSession != null) {
        final double currentDistance = dragSession.distance;
        final boolean sprint = Minecraft.getInstance().options.keySprint.isDown();
        final double sensMultiplier = Mth.clamp(Math.pow(currentDistance / 10.0, 0.5), 1.0, 5) * (sprint ? 4 : 1);
        dragSession.setDistance(handler.clampDistance(currentDistance + deltaY * scrollSensitivity * sensMultiplier));
        return new Result(true);
    }
    return InteractCallback.super.onScroll(deltaX, deltaY);
}

private double clampDistance(final double distance) { return Math.clamp(distance, 2.0, PhysicsStaffItem.RANGE); }
```

注意：**滚轮不是旋转，是调持握距离**（钳在 `[2.0, RANGE]`，灵敏度随距离按 `√(d/10)` 放大、上限 ×5，疾跑 ×4）。
`mps-clone-plan.md` 里"GMod 用滚轮+按键转物体"的说法是**转述**，与参考实现不一致 —— 以参考为准。

### 2.3 网络：客户端把朝向随拖拽包发上去

```java
public record PhysicsStaffDragPacket(UUID subLevel, Vector3dc playerRelativeGoal,
                                     Vector3dc localAnchor, Quaterniondc orientation) implements CustomPacketPayload {
    public void handle(final ServerPacketContext context) {
        ...
        PhysicsStaffServerHandler.get((ServerLevel) level)
                .drag(player.getGameProfile().getId(), this.subLevel, this.playerRelativeGoal, this.localAnchor, this.orientation);
    }
}
```

服务端 `drag(...)` 里 `session.orientation.set(orientation)`（第 255 行）后，
下一个物理 tick 就用它建约束（§2.1）—— 即**客户端给目标朝向，服务端交给引擎的马达去实现**。

### 2.4 参考的默认参数（记录备查）

| 参数 | 默认值 | 出处 |
|---|---|---|
| `physicsStaffLinearStiffness` | `2650.0` | `SimPhysics.java:22` |
| `physicsStaffLinearDamping` | `125.0` | `SimPhysics.java:23` |
| `physicsStaffAngularStiffness` | `10000.0` | `SimPhysics.java:24` |
| `physicsStaffAngularDamping` | `850.0` | `SimPhysics.java:25` |
| `physicsStaffRotateSensitivity` | `0.35` | `SimItemConfigs.java:12` |
| `physicsStaffScrollSensitivity` | `0.6` | `SimItemConfigs.java:11` |

⚠️ **这些数不能直接照抄进我们这边**：它们是**约束马达空间**的刚度/阻尼，
量纲与我们 `PhysgunSpring` 的"目标加速度"（格/秒²）不同。见 §四。

---

## 三、我们原来缺什么（侦察结论）

| 项 | 现状 |
|---|---|
| 旋转控制 | **完全没有**。`PhysgunItem.Grab` 里只有 `bodyId / holdDistance / anchor`，没有任何朝向目标 |
| 滚轮 | **完全没有**。`holdDistance` 是**抓取那一刻定死**的，之后改不了（`PhysgunItem` 全文件没有 scroll 处理） |
| 旋转写入路径 | `PhysicsBodyTracker` 只暴露 `applyImpulse` / `push` / `lockRotations`，**没有**任何扭矩入口 |
| 底层能力 | `RigidBody` 与 `NativePhysics` **有** `applyTorqueImpulse` / `addTorque` / `setAngvel` / `setRotation`（但当时全项目无人调用） |

底层够用，缺的是"从客户端输入到服务端扭矩"这一整条链。

---

## 四、★ 设计决策

### 4.1 机制：角 PD 伺服（照参考的机制类），写入路径用力矩冲量

与线性那半完全对称：

```
PhysgunSpring  : a = clamp(Kp·err − Kd·v, |a| ≤ MAX_A)         → forceFor(mass, a)      → applyImpulse
PhysgunRotation: α = clamp(Kp·axis·angle − Kd·ω, |α| ≤ MAX_ANG_ACCEL) → torqueFor(I, α) → applyTorqueImpulse
```

每 MC tick 施加一次 `J = τ · FORCE_DURATION`（与线性共用 `FORCE_DURATION = 0.05`）。

**为什么不 `setRotation`**：参考用的是引擎马达（§2.1），不是写位姿；
而且本项目在**位置**上已经栽过"直接搬位置与求解器抢权威"的跟头（`PhysgunItem` 类注释 §22）。
朝向同理。`PhysicsBodyTracker.applyTorqueImpulse` 因此是旋转的**唯一**写入路径，
由 `check-artifact.ps1` 的源码契约钉住。

### 4.2 ★ 惯量补偿：**刻意偏离参考**，且是探针逼出来的结论

参考给的是**固定刚度的马达**（§2.4），由它的引擎按惯量缩放 ⇒ **大船转得慢**。
我们第一版照这个思路实现成"力矩空间 PD"（增益直接是 N·m/rad，不乘惯量），
`PhysgunRotationProbe` 的 D 组当场把它否掉：

```
10 倍惯量：阻尼比从 0.71 掉到 0.22，3 秒后残角 37.75°（参考体只剩 0.07°）
```

那会变成"**能拖动重船、却转不动它**"，与线性那半刻意做的
"手感与载荷质量无关"（`PhysgunSpring.forceFor` 的类注释）自相矛盾 —— 枪的两个自由度手感会分裂。
所以改成**把惯量乘进调用方**，增益回到"目标角加速度"（rad/s²）。

改完后 D 组：轻（0.1×）/参考/重（10×）三者的 **2% 整定时间完全一致**（都是 2 秒内 1.475°）。

### 4.3 惯量怎么来：用项目自己的公式估

原生层**没有**惯量读取接口，而且 `native/` 下**只有预编译库、没有 C++ 源**
（无法加 JNI 导出）。所以按项目自己的近似估：

```java
// PhysgunRotation.estimateInertia —— 与 PhysicsBodyMass.apply 里那句是同一套近似
double s = Math.cbrt(Math.max(1, blocks));
return mass * s * s / 6.0;
```

`PhysicsBodyMass.apply` 里对应的是 `inertia = extra * s * s / 6.0`（`s = ∛blocks`）。
**两边必须同形**，否则"补的质量惯量"与"伺服以为的惯量"对不上 ——
`check-artifact.ps1` 用两条契约分别钉住两边。

惯量只用于把增益归一化，是量级估计，不参与存档与碰撞解算：估偏一点只影响"转得快慢"。

### 4.4 增益取值

| 常量 | 值 | 理由 |
|---|---|---|
| `KP` | `8.0`（= `PhysgunSpring.KP`） | 与线性同一组手感：ω=√KP≈2.83 rad/s |
| `KD` | `4.0`（= `PhysgunSpring.KD`） | ζ=KD/(2ω)≈0.71，略欠阻尼 |
| `MAX_ANG_ACCEL` | `20.0` rad/s² | θ=π 时未限幅是 25.13；取 20 只削极限情况 |
| `MAX_ANGLE_ERROR` | `π` | 最短弧天然 ≤ π，显式夹一下防未归一化输入爆值 |

**注意**：没有照抄 §2.4 的 10000/850 —— 量纲不同（见 §2.4 的警告）。
手感对齐的是**我们线性那半**（已被实机接受的那组数），不是参考的绝对数值。

---

## 五、已落地

### 5.1 核心（`a2e3805`，2026-10-01）

| 文件 | 内容 |
|---|---|
| `src/main/java/com/mss/polymech/physics/PhysgunRotation.java`（新，160 行） | 最短弧轴角 `errorAxisAngle` + 角加速度 PD `angularAccel` + 限幅 + `torqueFor` + `estimateInertia` + `torqueImpulse`。纯函数、零 MC 依赖 |
| `src/main/java/com/mss/polymech/physics/PhysicsBodyTracker.java` | `applyTorqueImpulse(id,tx,ty,tz)`（旋转的唯一写入路径，镜像 `applyImpulse`）+ `inertiaOf(id)`（质量 × 方块数 ⇒ 惯量） |
| `native/jni-smoketest/PhysgunRotationProbe.java`（新，249 行） | **31 条判据**：A 最短弧/双重覆盖、B PD 律与限幅、C 真收敛仿真、D ★ 惯量补偿、E 惯量公式同形 |
| `native/jni-smoketest/run-offline-checks.ps1` | 新增**第 16 步**；`N/15` → `N/16`；表头加第 12 块 |
| `native/jni-smoketest/check-artifact.ps1` | 新增 **6 条源码契约**（惯量公式两边同形 ×2、双重覆盖、限幅、扭矩写入路径、惯量来源） |

### 5.2 接线（本提交，2026-10-01）

| 文件 | 内容 |
|---|---|
| `client/physics/PhysgunInputHandler.java`（新） | 按键 `ROTATE_MODE_KEY`（默认**左 Alt**）+ `onMouseMove`（偏航绕体自身 Y / 俯仰绕世界侧向轴）+ `onScroll`（改持握距离）+ 每 tick 发拖拽包。灵敏度照参考：旋转 `0.35`、滚轮 `0.6`、距离钳 `[2, RANGE]`、滚轮灵敏度 `√(d/10)` 钳 `[1,5]` 且疾跑 ×4 |
| `mixin/MouseHandlerMixin.java`（新） | `@WrapWithCondition` 包住 `LocalPlayer.turn(DD)V` ⇒ 旋转模式下**相机冻结**（参考用 `ci.cancel()`，等价）。选它而不是参考的 `@Local(ordinal=…)`：1.21.1 的 `turnPlayer` 已经被 NeoForge 改过（开头多了 `ClientHooks.getTurnPlayerValues`），序号是脆的；`WrapWithCondition` 直接拿 `(player, yaw, pitch)` 参数，且 `pitch` 已含反转 Y 符号 |
| `network/PhysgunDragPacket.java`（新） | 客户端 → 服务端：`(bodyId, holdDistance, qx, qy, qz, qw)`。发**绝对朝向**（照参考，丢包自愈）；`playToServer` 注册在 `Polymech.registerPayloads` |
| `client/physics/PhysgunClientState.java` | `Beam` 增加目标朝向 + 距离 + 脏标记；`clientDistance` 让"客户端正在驱动的距离"不被服务端回声覆盖（否则滚轮会回弹）；`release` 清掉旋转会话（下次抓重新从体当前朝向起步） |
| `item/PhysgunItem.java` | `Grab` 增加 `orientation`（null = 还没转过 ⇒ 不做角伺服）；新增 `applyDrag`（**服务端校验**：确实抓着这个体 / 归一化 / 拒绝退化四元数 / 距离钳位）；`onUseTick` 在线性弹簧之后追加角伺服；新增松手时的**旋转判据**（程序判定 PASS/FAIL） |
| `PolymechClient` / `poly_mech.mixins.json` / 两个 lang provider | 按键注册 / mixin 注册（client 段）/ 按键名中英文 |

**验证**：`.\gradlew compileJava --console=plain` BUILD SUCCESSFUL；
`.\native\jni-smoketest\run-offline-checks.ps1` **16/16 步、exit 0**；角伺服 31/31；`check-artifact.ps1` 新增 **15 条接线契约**全 OK。

**★ 注入点用字节码验过**（不靠"读代码觉得对"）：`javap -p -c` 打
`build/moddev/artifacts/neoforge-21.1.228-merged.jar` 的 `net.minecraft.client.MouseHandler`，
`turnPlayer(double)` 里确实有
`invokevirtual net/minecraft/client/player/LocalPlayer.turn:(DD)V`；
`Options.keySprint`、`InputEvent.MouseScrollingEvent.getScrollDeltaY()` 也都在。
（mixin 配置是 `required: true`，所以万一注入点没了客户端会**启动失败**而不是静默失效 ——
上面那条 javap 检查就是为了在提交前拦住这种情况。）

### 5.3 ★ 探针抓到的一个"判据期望写错"（记下来避免重复）

第一版 A 组写的是"目标取 `-q90` ⇒ 残角应为 0"。**这是错的**：
`-q90` 与 `q90` 是**同一个旋转**，它离单位元本来就是 90°。
真正该断言的是这两条（现已改成）：

```
error(单位元, -q90) == error(单位元, q90)     // 双重覆盖：同一个角
error(q90, -q90) == 0                        // 双重覆盖：残角 0，不是 2π
```

即：**代码是对的，判据的期望错了**。这与 `docs/mps-clone-plan.md` 第 4376 行起记录的
"两次都是判据的期望错了、代码是对的"是同一类问题 —— 判据必须由调用方自己推导。

---

## 六、实机判据表（用户验收用）

前提：进游戏，拿牵引枪（`item.poly_mech.physgun`），对着一个**已知的**物理体（太空里放几个方块造一个最容易看清）。

| # | 操作（绝对、可复现） | 程序判定（服务端打聊天栏） | 期望 |
|---|---|---|---|
| 1 | 右键抓住任意物理体，**按住左 Alt**，鼠标**横向**移动 | 松手时 `[牵引枪·旋转判据] 松手残角 X°（阈值 5°）⇒ PASS/FAIL` | 物体绕**自身 Y** 转（偏航）；`PASS` |
| 2 | 同上，但鼠标**纵向**移动 | 同上 | 物体绕**世界侧向轴**翻转（俯仰）；`PASS` |
| 3 | 按住左 Alt 的同时移动鼠标 | 观察**相机** | 相机**不动**（旋转模式下鼠标被物体吃掉）；松开 Alt 后视角恢复跟手 |
| 4 | 抓住物体，**滚轮上/下**滚 | 松手时的 `持握 X.XX 格` | 物体被推远/拉近；数值随滚动**单调**变化并钳在 `[2, 64]` |
| 5 | 不按 Alt，直接移动鼠标 | —— | 相机正常转动（不该被吃掉） |
| 6 | 抓住物体转到一个明显角度，**松手** | 判据行 | 物体**保持**该朝向，不再被拉回（伺服只在抓着时施力） |
| 7 | 松手后再抓同一个体、按住 Alt 移动鼠标 | —— | 从**当前**朝向继续转，**不跳**（`release` 清了旋转会话，重新按体当前朝向起步） |

判据 1/2/6/7 都是**程序判定**（服务端算"目标朝向 vs 实际朝向的最短弧残角"，绝对量，
与相机朝向无关）。阈值 `RESIDUAL_PASS_DEG = 5°`：离线 C 组实测 2% 整定 2 秒内到 1.475°，
所以 5° 既容得下"刚转完就松手"，又远小于"完全没转"（那是几十度）。

**已知会在实机上看到的非缺陷现象**：
- 抓着**很轻**的体（方块数少）快速甩鼠标时可能过冲一点点再回来 —— 那是 PD 的 2% 整定过程（ζ≈0.71 略欠阻尼）。
- 旋转判据只在**松手**时打一行；抓着不放时不打（否则每 tick 刷屏）。

---

## 七、之后仍开放的牵引枪缺口

（来自 `mps-clone-plan.md` 3547–3552，与本项独立）

- 光束是 1px `RenderType.lines()`（GMod 是粗四边形）；
- 别的玩家看不见你的光束（需要服务端广播"谁抓住了什么"）；
- 手感调参（Kp=8 / Kd=4）；
- 自造音效资产（现用原版导管音顶替）。

---

## 八、验证约定（本项专用）

1. `.\gradlew compileJava --console=plain` 必须打印 `BUILD SUCCESSFUL`。
2. `.\native\jni-smoketest\run-offline-checks.ps1` 必须 **16/16 步、exit 0**（本项加了第 16 步）。
3. 改了 `PhysgunRotation` 的公式 ⇒ 必须同步 `check-artifact.ps1` 的契约与探针的复刻。
4. `run-offline-checks.ps1` 需**双** BOM（`EF BB BF EF BB BF`）、
   `check-artifact.ps1` 需**单** BOM（`EF BB BF`）—— `edit`/`write` 工具会剥 BOM，改完要用
   `[System.IO.File]::ReadAllBytes`/`WriteAllBytes` 字节级补回。
5. 本 shell 是 **Windows PowerShell 5.1**（没有 `pwsh`）：`& 'native\jni-smoketest\run-offline-checks.ps1'`。
6. 任何用户可见的变化必须配**实机判据表**（绝对坐标，程序判定 PASS/FAIL，禁止相机相对）。
7. 助手不启动客户端；实机由用户验。
