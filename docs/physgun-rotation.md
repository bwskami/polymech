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

### 2.2.1 ★ 参考的**完整操作方式**（逐字取证；用户 2026-10-01 拍板"以机械动力航空学为准"）

先把参考是谁钉死：`C:\Users\34573\Desktop\Simulated-Project-main` =
**Create Aeronautics**（`aeronautics/gradle.properties`: `mod_name=Create Aeronautics`、
`mod_id=aeronautics`；`aeronautics-bundled`: `group=dev.simulated_team.simulated`）
—— 就是"机械动力：航空学"。用户说"以航空学为准"= 以这个参考为准。

| 输入 | 参考的行为 | 取证位置 | 我们 |
|---|---|---|---|
| **右键**（按下） | `START_DRAG`：开始拖；**已拖着时再按一次 ⇒ `stopDragging()`** ⇒ **是开关，不是长按** | `PhysicsStaffMouseHandler.onUse`（`action == GLFW_PRESS`）+ `onItemUsed` 里 `START_DRAG && state == DRAGGING` 分支 | ✅ 已对齐（见 §十） |
| **左键**（按下） | `onItemPunched()` ⇒ `LOCK`：服务端 `toggleLock(uuid)` 给体挂一个物理 handle **把它钉住**；再按一次解锁。**可持久化**（`saveLocks`/`loadLocks` 存 NBT） | `onItemPunched`（:110）、`PhysicsStaffServerHandler.toggleLock`（:151） | ✅ 已对齐（见 §十一） |
| **TAB**（按住） | `ROTATE_MODE` ⇒ 进入旋转模式：鼠标移动转物体、**相机冻结** | `SimKeys.ROTATE_MODE("rotate_mode", GLFW.GLFW_KEY_TAB, "Physics Staff Rotate Mode")`（`index/SimKeys.java:14`） | ✅ 已对齐（**默认 TAB**） |
| **鼠标移动**（旋转模式下） | `rotateLocalY(yaw×0.35)` + `premul(AxisAngle(−pitch×0.35, 世界侧向轴))` | `onMouseMove`（:551-573） | ✅ 逐字一致 |
| **滚轮** | 改持握距离：`+ deltaY × 0.6 × clamp(√(d/10),1,5) × (疾跑?4:1)`，钳 `[2.0, RANGE]` | `onScroll`（:576-591）、`clampDistance`（:113） | ✅ 逐字一致 |
| 抓取射程 | `PhysicsStaffItem.RANGE = 128.0f`（同时是距离钳位上限） | `PhysicsStaffItem.java:10` | ✅ 已对齐（见 §十二） |

**⚠️ TAB 与原版玩家列表冲突（参考同样存在，不是我们的 bug）**：
原版 `Options.keyPlayerList` 默认就是 TAB。参考用的是普通 `KeyMapping`（`new KeyMapping(...)`，
只有 `conflictSafe=true` 时才用 `ConflictSafeKeyMapping`，而 `ROTATE_MODE` 没开），
且它的 `KeyboardHandlerMixin` 只拦**鼠标**键（`InteractCallback.filterInteract` 只路由
attack/middle/use），**不拦键盘键** ⇒ 参考按住 TAB 时**也会**弹出玩家列表。
我们照抄这个默认值，玩家可在"按键绑定"里改任一个。

**关于"左键 LOCK"与"右键开关"这两项未对齐**：它们是**功能/生命周期**层面的差异，不只是按键映射
（LOCK 要新增"钉住并持久化"的服务端语义；开关要改抓取的生命周期）。已向用户报告并等其决定，
没有擅自实现 —— 见 §九。

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
| `client/physics/PhysgunInputHandler.java`（新） | 按键 `ROTATE_MODE_KEY`（默认 **TAB**，照参考 `SimKeys.ROTATE_MODE`）+ `onMouseMove`（偏航绕体自身 Y / 俯仰绕世界侧向轴）+ `onScroll`（改持握距离）+ 每 tick 发拖拽包。灵敏度照参考：旋转 `0.35`、滚轮 `0.6`、距离钳 `[2, RANGE]`、滚轮灵敏度 `√(d/10)` 钳 `[1,5]` 且疾跑 ×4 |
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
| 1 | 右键抓住任意物理体，**按住 TAB**，鼠标**横向**移动 | 松手时 `[牵引枪·旋转判据] 松手残角 X°（阈值 5°）⇒ PASS/FAIL` | 物体绕**自身 Y** 转（偏航）；`PASS` |
| 2 | 同上，但鼠标**纵向**移动 | 同上 | 物体绕**世界侧向轴**翻转（俯仰）；`PASS` |
| 3 | 按住 TAB 的同时移动鼠标 | 观察**相机** | 相机**不动**（旋转模式下鼠标被物体吃掉）；松开 TAB 后视角恢复跟手 |
| 4 | 抓住物体，**滚轮上/下**滚 | 松手时的 `持握 X.XX 格` | 物体被推远/拉近；数值随滚动**单调**变化并钳在 `[2, 64]` |
| 5 | 不按 TAB，直接移动鼠标 | —— | 相机正常转动（不该被吃掉） |
| 6 | 抓住物体转到一个明显角度，**松手** | 判据行 | 物体**保持**该朝向，不再被拉回（伺服只在抓着时施力） |
| 7 | 松手后再抓同一个体、按住 TAB 移动鼠标 | —— | 从**当前**朝向继续转，**不跳**（`release` 清了旋转会话，重新按体当前朝向起步） |

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
2. `.\native\jni-smoketest\run-offline-checks.ps1` 必须 **17/17 步、exit 0**（本项加了第 16 步；
   第 17 步是体局部格映射，见 `docs/body-local-mapping.md`）。
3. 改了 `PhysgunRotation` 的公式 ⇒ 必须同步 `check-artifact.ps1` 的契约与探针的复刻。
4. `run-offline-checks.ps1` 需**双** BOM（`EF BB BF EF BB BF`）、
   `check-artifact.ps1` 需**单** BOM（`EF BB BF`）—— `edit`/`write` 工具会剥 BOM，改完要用
   `[System.IO.File]::ReadAllBytes`/`WriteAllBytes` 字节级补回。
5. 本 shell 是 **Windows PowerShell 5.1**（没有 `pwsh`）：`& 'native\jni-smoketest\run-offline-checks.ps1'`。
6. 任何用户可见的变化必须配**实机判据表**（绝对坐标，程序判定 PASS/FAIL，禁止相机相对）。
7. 助手不启动客户端；实机由用户验。
8. **操作方式的任何默认值都必须去参考里取证**，不许自行挑"更顺手"的键
   （本项栽过一次：`ROTATE_MODE` 我猜了左 Alt，参考其实是 TAB）。`check-artifact.ps1` 已有契约钉住 TAB。

---

## 九、待用户决定：两项未对齐的操作方式

用户 2026-10-01 拍板"牵引枪的操作方式以机械动力航空学为准"。审计后**按键映射层面已全部对齐**
（TAB + 鼠标转 + 滚轮调距，灵敏度逐字一致）；剩下两项是**功能/生命周期**层面的差异，
已报告、**未擅自实现**（避免在用户还没实机验证过当前版本时就动核心生命周期）：

| 项 | 参考行为 | 为什么不是"改一行" | 建议 |
|---|---|---|---|
| **右键 = 开关** | 按一次开始拖，**再按一次松手**（`onItemUsed` 的 `START_DRAG && DRAGGING ⇒ stopDragging`） | 我们整个服务端施力挂在 `onUseTick`（长按语义）上。改成开关要把施力从 `onUseTick` 挪到"由 `GRABS` 表驱动的服务端 tick"，是**抓取生命周期的重构** | 先让用户实机验证当前长按版；要改再改 |
| **左键 = LOCK** | 给体挂物理 handle 钉住，再按解锁，**可持久化**（存 NBT） | 这是**新功能**：需要服务端"钉住"语义 + 持久化 + 包 + 音效。我们已有的 `TrackedBody.frozen` 绑着 `shouldUnfreeze`（附近有玩家就自动解冻），**不能**直接复用 | 作为独立功能立项 |
| 抓取射程 `RANGE` | `128.0f` | 我们的 `64.0` 是**既有**常量，**同时**是射线射程与距离钳位上限 —— 改成 128 会让抓取距离翻倍（是玩法参数，不只是按键） | 等用户确认是否要 128 |

**没有一项被静默改掉**：上面每一条要么已对齐（TAB/鼠标/滚轮），要么在本节明确挂着等决定。

---

## 十、右键从"长按"改成"开关"（2/3 已落地）

用户拍板"一切都按照他的来"，所以原先挂在 §九 的两项也照做。本节记第 2 项：**右键改开关**。

### 10.1 参考的架构（这是关键，不是"换个判断"）

参考的做法不是"把 onUseTick 改一改"，而是**整条输入链换掉**：

1. `MouseHandlerMixin` 注入 `MouseHandler.onPress`，拿枪时**把点击 cancel 掉**
   ⇒ 原版**从不**收到这次右键 ⇒ **从不进入**"使用中"状态；
2. 同时（`onPress`）注入 `turnPlayer`（旋转）与 `onScroll`（调距）；
3. 客户端把离散事件打包成 `PhysicsStaffActionPacket(action, subLevel, location)` 发服务端；
4. 服务端 `PhysicsStaffServerHandler` 维护 `draggingSessions`，**施力在 `physicsTick` 里按表推进**，
   与"原版使用状态"完全无关。

**所以"开关"是这套架构的必然结果，而不是一个可选的小改**：既然原版不进入使用状态，
就不存在"松开"这个事件，只能由"再按一次"来结束。

### 10.2 我们怎么落地的（逐项对照）

| 参考 | 我们 | 说明 |
|---|---|---|
| `MouseHandlerMixin` 注入 `onPress`（`getOverlay()` 那句，`ordinal=0`） | `@Inject(method="onPress", at=@At("HEAD"), cancellable=true)` | 行为等价，**不依赖指令序号**（那条 `getOverlay` 在 1.21.1 里确实存在，已 `javap` 核过，但它在方法中段，`ordinal=0` 会随上游改动**静默**挪位）。"开着界面/覆盖层时别抢点击"的意图改在 `PhysgunInputHandler.onMousePress` 里用 `mc.screen` / `mc.getOverlay()` 判断 |
| `PhysicsStaffActionPacket(action, subLevel, location)` | `PhysgunActionPacket(action, bodyId)` | 同样拆"离散事件"与"连续量"两类包；`bodyId` 只作参考，服务端**不信任**它 |
| `PhysicsStaffAction` 枚举（`START_DRAG`/`STOP_DRAG`/`LOCK`） | `PhysgunAction`（目前 `START_DRAG`/`STOP_DRAG`） | `LOCK` 随第 3 项（左键）一起加 —— 不提交半成品 |
| `PhysicsStaffServerHandler.drag/stopDragging` + `physicsTick` 施力 | `PhysgunItem.applyAction` + `serverTick`/`tickGrab` | 施力**离开** `onUseTick`，改由 `PhysicsTickEvent.Post` → `PhysgunItem.serverTick(server)` 按 `GRABS` 表推进 |
| `use()`/`onUseTick`/`releaseUsing` 一概不存在（物品只有 `canAttackBlock`） | **删掉** `use`/`onUseTick`/`releaseUsing`/`getUseDuration` | 用 `mustNot` 契约钉住不许回归（回来会导致**双份施力**） |
| 不再持枪 ⇒ 松手（`holdingStaff == false ⇒ state = PASSIVE`） | `onClientTick` 里同样处理 | 没有这一步，改成开关后切快捷栏会**永远拖着**（原版以前靠 `releaseUsing` 兜底） |

**两处有意偏离**（都已写进代码注释与契约）：

1. **`START_DRAG` 是显式事件，参考是隐式的**。参考的客户端自己选中子级、把目标 UUID 塞进拖拽包，
   服务端 `drag(...)` 首次收到就建会话（**它不自己射线**）。我们不能这样：本项目服务端射线才是权威
   （客户端/服务端体注册表曾经不一致），而且客户端镜像没同步上时本地求交必然落空
   —— 那条教训（**打不中也要让服务端去抓**）原来写在 `use()` 里，现在原样搬到 `onMousePress`。
2. **服务端校验失败只拒绝，不踢人**。参考的 `validateWorthyness` 会
   `disconnect("Invalid packet")`；我们只静默拒绝 —— 因为存在**合法竞态**
   （按下右键后立刻切走快捷栏，包到达时手里已经不是枪了），踢人会误伤。
   服务端本来就不信任包里的任何数值（抓谁由服务端射线决定），拒绝已经足够。

### 10.3 一个必须记下的坑：开关判据不能用 `isGrabbed`

`PhysgunClientState.isGrabbed()` = `beam != null && !beam.finished()`，
而 `finished()` 要等**强度衰减到 0** 才为真 —— 也就是说**松手后的淡出期间 `isGrabbed` 仍为 true**。
拿它当"现在是不是拖着"的判据，会让"松手后立刻想再抓"被当成**又一次松手**（手感上表现为"点了没反应"）。
判据必须用 `released()`（`release()` 立刻置真，`ensure`/`applyServerEndpoints` 置假）。
已用契约钉住：`if (beam != null && !beam.released()) {`。

### 10.4 实机判据表（绝对坐标；程序判定 PASS/FAIL）

| # | 操作 | 判据（可观察、与相机无关） | 期望 |
|---|---|---|---|
| T1 | 拿枪，右键**点一下**（短促按下再松开），松开后不动 | 聊天栏出现「已抓住 物理体 #N」；光束**持续存在**（不会随松手消失） | PASS = 松手后 5 秒光束仍在，物体被拉到准星前方并停住 |
| T2 | 承 T1，**再点一下**右键 | 聊天栏出现「已松开」；光束在约 0.5 秒内淡出消失；物体停在原地不再被牵引 | PASS = 淡出后物体速度逐渐归零（不再有持续拉力） |
| T3 | 承 T2，**立刻**（淡出还没结束，<0.5 秒）再点一下右键 | 重新抓住同一个/准星所指的体，聊天栏再次出现「已抓住」 | PASS = 能立刻再抓（这正是 §10.3 那个坑的判据；判据错的话这一下会被吃掉） |
| T4 | 抓着时**把枪切走**（滚轮换到别的物品栏） | 光束淡出、物体松手；聊天栏出现「已松开」 | PASS = 不再持续牵引（没有这一步会永远拖着） |
| T5 | 拿着枪对**方块**点右键 | 方块**不被放置**、容器**不被打开**（点击被吃掉） | PASS = 什么都没发生（照参考；原版使用状态从不进入） |
| T6 | 拿着枪对**方块**长按左键 | 方块**不被破坏** | PASS = 方块完好（客户端那层吃掉 + 服务端 `canAttackBlock=false` 两道） |
| T7 | 抓着时**退出游戏再进** | 不会残留牵引（`GRABS` 是服务端内存表；`forget(uuid)` 在玩家离开时清理） | PASS = 重进后没有任何物体被凭空牵引 |
| T8 | 抓着时**下线/掉线**（多人） | 服务端 `serverTick` 解析不到玩家 ⇒ 自动放手 | PASS = 聊天栏/物理上都没有残留牵引 |

### 10.5 验证约定（本项）

1. `.\gradlew compileJava --console=plain` 必须 `BUILD SUCCESSFUL`。
2. `.\native\jni-smoketest\run-offline-checks.ps1` 必须 **17/17 步、exit 0**。
3. **删掉的原版钩子必须留在 `mustNot` 契约里**：`onUseTick(` / `releaseUsing(` / `getUseDuration(`
   —— 它们回来会造成双份施力（`serverTick` + `onUseTick` 各一次），且**不报错**。
4. ⚠️ 产物自检的 `marks` 不许写 `START_DRAG`/`STOP_DRAG`：`switch` 里的枚举常量名会被 `javac`
   放进合成的 `$SwitchMap` 类，`PhysgunItem.class` 里**没有**这两个字符串（写进去必假 MISS，
   我这次就栽了一下）。枚举值的钉住用**源码契约**（`case START_DRAG -> startDrag(sp);`）。

---

## 十一、左键 = 钉住/解锁（LOCK）（3/3 已落地）

第 3 项，也是三项里唯一**动了原生层**的一项。

### 11.1 参考的机制（逐字取证）

```java
private void toggleLock(final UUID uuid) {
    final ServerSubLevel subLevel = (ServerSubLevel) container.getSubLevel(uuid);
    if (subLevel == null) return;
    final Lock existingLock = this.locks.get(uuid);
    if (existingLock != null) { this.locks.remove(uuid); existingLock.remove(); this.setLocksDirty(); return; }
    this.locks.put(uuid, new Lock(uuid, addConstraint(container, subLevel)));
    this.setLocksDirty();
}
private static FixedConstraintHandle addConstraint(container, subLevel) {
    return container.physicsSystem().getPipeline().addConstraint(null, subLevel,
            new FixedConstraintConfiguration(subLevel.logicalPose().position(),
                                             subLevel.logicalPose().rotationPoint(),
                                             subLevel.logicalPose().orientation()));
}
```

四条**必须一起对齐**的行为（少一条就不算对齐）：

| 参考 | 含义 | 我们 |
|---|---|---|
| `toggleLock` | 再按一次解锁 | `PhysgunLocks.toggle(bodyId)` |
| `drag(...)` 开头 `removeLock(subLevel)` | **拖着的体自动解锁**（约束与弹簧力不能同时作用，否则互相顶、物体抖） | `PhysgunItem.tickGrab` 开头 `removeLock` |
| `save`/`loadLocks` | 持久化；读回来 `handle = null` | `PhysgunLocks.save/load`，读回记 `PENDING` |
| `applyLockIfNeeded` | 惰性重建（句柄是运行时句柄，重启必然失效） | `PhysgunLocks.applyPending()`，每服务端 tick 调 |

### 11.2 ★ 原生缺口：Rapier 没有那个 `null`

参考的 `addConstraint(null, ...)` 里第一个参数是 **`null` = 锚在世界**。Rapier 0.34 **没有**这个隐式地面：

- `dynamics/solver/joint_constraint/joint_constraint_builder.rs:46` 是 `let rb1 = &bodies[joint.body1];`
  —— **直接索引**刚体集合；
- 而 `Coarena::get` 返回 `Option`、**没有**默认值兜底 ⇒ 传 `RigidBodyHandle::invalid()` 会 **panic**；
- `ImpulseJointSet::insert` 的文档里也**没有**任何关于 invalid 句柄的说明（0.34 的签名只接受两个真句柄）。

所以按项目规矩**补原生**（而不是在 Java 侧找个"效果一样"的写法）：
`worldAddFixedConstraint` 把"世界"这个锚**显式建出来** ——

```
在体当前位姿建一个 FIXED 锚体  →  用 FixedJoint::new()（锁全部 6 轴、两个局部帧都是单位帧）连它和体
```

锚体建在**体当前位姿**上，是让"两个局部帧都用单位帧"成立的关键 —— 这正是参考那三个
`logicalPose()` 参数在做的事。锚体**不登记**进 `body_map`，所以 `worldBodyCount()`
（只数 Java 可见的体）不会虚增。

**为什么不用 `bodySetBodyType(FIXED)`**：那是**殊途同归** —— 效果像（体不动了），但它改的是体的
*类型*（质量无穷、退出正常求解路径），解锁时还得记住原来的类型。参考用的是**约束**，我们也用约束。

### 11.3 离线判据（第 18 步 `ConstraintProbe`，20/20）

这是本项目里少见的"依赖引擎内部语义"的改动，所以判据刻意做重：

| 组 | 判据 | 实测 |
|---|---|---|
| **A 对照组** | **不加**约束时必须自由落体 1 秒 ≈ 4.9 格 | Δy=4.9122 ✓（★ 没有它，"钉住了"可能只是"物理没在跑"） |
| B | 加约束后 100 步**一动不动**；刚体集 +1（锚体真的建出来了）；Java 可见体数不变 | 总位移 2.487e-13 ✓；1→2 ✓；1→1 ✓ |
| C | 解锁后恢复自由落体 0.5 秒 ≈ 1.225 格；刚体集回到原值（锚体真的删了） | Δy=1.2311 ✓；2→1 ✓ |
| D | 重复加返回 **-2** 且**不建第二个锚体** | 返回 -2 ✓；2→2 ✓ |
| E | 删掉被钉住的体 ⇒ 刚体集 **-2**（体 + 锚体都回收） | 2→0（裸世界 1）✓ |
| F | 退化输入（世界 0 / 不存在的体 / 不存在的约束）只返回失败，不崩 | 4/4 ✓ |

★ B/C/E 用 `worldGetRigidBodySetSize()`（Rapier 原始刚体集，**含**锚体）而不是
`worldBodyCount()` —— **锚体泄漏是"不报错、不可见"的那类错**，只有这个计数抓得住。

### 11.4 实机判据表（绝对坐标；程序判定 PASS/FAIL）

| # | 操作 | 判据 | 期望 |
|---|---|---|---|
| L1 | 抓一个物体（右键），按**左键** | 聊天栏「已钉住 物理体 #N」 | PASS = 松手后物体**停在原地不掉不飘**（把它拖到半空再松手最能看出来） |
| L2 | 承 L1，再按**左键** | 聊天栏「已解锁 物理体 #N」 | PASS = 物体立刻恢复自由落体 |
| L3 | 承 L1（已钉住），**右键去拖它** | 自动解锁（约束与弹簧力不能同时作用） | PASS = 能正常拖动、**不抖**；聊天栏的锁状态随之失效 |
| L4 | 钉住后**退出游戏再进** | 读档后锁仍在 | PASS = 物体仍然停在原地（`applyLockIfNeeded` 的实机判据） |
| L5 | 钉住后**打掉/拆掉**那个物体 | 锚体必须一起回收 | PASS = 不崩、无残留（原生 E 组离线已证；实机只看不崩） |
| L6 | 按住左键连续点 | 每次都是 toggle | PASS = 钉住/解锁交替，不重复叠加约束 |

### 11.5 遗留（已如实记录，未做）

参考还会广播 `PhysicsStaffLocksPacket` 让**客户端**知道谁被钉住了。查过它的**唯一**用途：
`PhysicsStaffClientHandler` 用它**选音效**（已锁 ⇒ `STAFF_UNLOCK`，否则 `STAFF_LOCK`/`STAFF_IGNITE`），
**没有**任何视觉指示。我们的音效目前还是原版 conduit 占位音，所以这一步先不做这个包 ——
它换的只是两个占位音之间的选择。真正该做的是**自造音效资产**（已在遗留清单里）。

### 11.6 验证约定（本项）

1. `.\gradlew compileJava --console=plain` 必须 `BUILD SUCCESSFUL`。
2. 改了原生层 ⇒ 必须 `.\gradlew copyNativePhysics` 重编，且 **DLL 必须一起提交**
   （`src/main/resources/natives/windows_amd64/polymech_physics.dll`）。
3. `.\native\jni-smoketest\run-offline-checks.ps1` 必须 **18/18 步、exit 0**。
4. ⚠️ **第 18 步必须"先 javac 编译、再用 -cp 跑"，不能用源码模式**（`java Xxx.java`）：
   源码模式用的是另一套类加载器，`System.load` 与 `-cp` 上的 `NativePhysics` 不在同一个加载器里
   ⇒ JNI 符号绑不上，报 `UnsatisfiedLinkError: 'int ...NativePhysics.abiVersion()'`。
   我在这里踩过一次（连 `NativeSmokeTest` 也一样，它的注释本来也要求"先编译"）。
5. 走原生 DLL 的步骤要临时把 `$ErrorActionPreference` 改成 `Continue`：JVM 的
   Native-access 警告走 stderr，`Stop` 会把它当终止错误。

---

## 十二、射程与左键语义（1/3 已落地）

第 1 项，最简单、无架构改动的那一条，但两处都**照参考逐字**：

1. **`RANGE` 64.0 → 128.0**，照 `PhysicsStaffItem.RANGE = 128.0f`
   （`simulated/common/.../content/physics_staff/PhysicsStaffItem.java:10`）。
   这个常量客户端与服务端**共用**（`PhysgunTarget.find` / `PhysgunBeamRenderer` /
   `BodyRaycast.nearest` / 滚轮距离上限 / `applyDrag` 钳位），所以改一处两端一致 ——
   不存在"客户端 128、服务端 64"这种半吊子状态。
   副作用（有意）：能抓能推的距离翻倍；actionbar 的「（N 格内）」文案自动跟着变。

2. **`canAttackBlock(...) { return false; }`**，照 `PhysicsStaffItem.canAttackBlock`。
   理由：左键在参考里是 **LOCK**（见 §十一），不拦的话每次锁一下都会顺手把准星前的方块挖掉。
   这是**服务端**那道；客户端还有一道（`MouseHandlerMixin` 把左键吃掉，见 §十）。

契约：`check-artifact.ps1` 里两条（`RANGE = 128.0` 的值、`canAttackBlock` 的签名）。
