# space 0.3.0 对齐：执行计划与侦察结论

> 配套文档：`docs/space-030-audit.md`（差异审计）、`docs/space-decompile.md`（反编译参考库总览）。
> 基准：**space 0.3.0**（用户 2026-09-30 指令：之后参考都以最新版为准）。

## 一、四项任务与状态

| # | 任务 | 规模 | 状态 |
|---|---|---|---|
| 3 | `CelestialWorld` 映射矩阵改为正反严格互逆 | 小（+18/−4 行） | ✅ **完成**（`757cd1b`） |
| 4 | `Planet` 新增 `cloudBottom`/`cloudTop` | 小（+6 处） | ✅ **完成** |
| 2 | 体素粒度 64³→16³（物理/投影/渲染/网络四层） | 大（机械） | ⬜ 待做 |
| 1 | 玩家物理切到 0.3.0 模型（原版驱动 + 扫掠 AABB） | 大（架构） | ⬜ 待做 |

用户 2026-09-30：「可以，这四件事你都可以做」= 四项全部批准。

## 二、★ 第 1 项的关键侦察：0.3.0 的玩家到底怎么动

**结论：0.3.0 的玩家全程由原版移动，Rapier 只负责"碰撞"这一件事。**
不是"换了个物理引擎驱动玩家"，而是**取消玩家物理体**，把物理降级成碰撞查询。

### 2.1 组件清单（全部是 0.1.3 没有的）

| 文件 | 行数 | 职责 |
|---|---|---|
| `mps/collision/physical/physical_world/PhysicalEntityCollision.java` | 366 | 手写扫掠 AABB vs 体素解算器（SAT 15 轴：3 世界轴 + 3 体轴 + 9 叉积） |
| `.../PhysicalEntityCollider.java` | 115 | 实体碰撞盒 ↔ 物理世界的适配 |
| `space/client/SpaceViewRotation.java` | 158 | **自由视角**：朝向四元数 + 鼠标缩放 + 贴地面对齐 + 写回 yaw/pitch |
| `sunshine/mixin/game/MixinPlayerFlight.java` | 73 | `Player.travel` HEAD：相机相对的**飞行**推进 + 相机系阻尼 |
| `sunshine/mixin/game/MixinEntityMoveRelative.java` | 35 | `Entity.moveRelative` HEAD：输入向量按**相机朝向**旋转（6DOF 输入） |
| `sunshine/mixin/.../MixinEntity.java` | — | `Entity.collide` RETURN 改写返回值 + `Entity.turn` HEAD |

### 2.2 各自的精确行为（逐字核对过，照抄时按这个来）

**`SpaceViewRotation.isActive(player)`** —— 激活条件**只看维度**，不需要装备：
```java
ClientSpaceWorld spaceWorld = ClientSpaceWorld.getSpaceWorld();
boolean shouldBeActive = player != null && player.level() != null
    && spaceWorld != null && spaceWorld.WorldID.equals(player.level().dimension().location());
```
状态翻转时：激活 → `seedFromEntity(player)`（`rotationYXZ(π − yRot, −xRot, 0)`）+ `pendingSurfaceAlign = !alignToSurface(player)`；
失活 → `clampEntityPitch(player)`（pitch 夹到 ±90）+ 清 pending。

- `applyMouse(player, yRot, xRot)`：`TURN_SCALE = 0.15F`，`ORIENTATION.mul(rotateY(−yawΔ)).mul(rotateX(−pitchΔ)).normalize()`，然后 `syncEntity`。
- `orientation(partialTick, player)`：每 tick 更新 `PREVIOUS`，返回 `slerpSafe(PREVIOUS, ORIENTATION, partialTick)`。
- `alignToSurface`：`localSurfaceUp` 取"最近天体"（按 `distance(pos, body.getSmoothPos(1.0F)) − radius` 最小），
  要求 `altitude ≤ radius * 2.0`；成立则把朝向左乘 `fromTo((0,1,0), 表面法线)`。
- `syncEntity`：由 `ORIENTATION` 变换出 forward，反解 `yaw = atan2(−f.x, f.z)`、`pitch = −asin(clamp(f.y))`，
  写 `setYRot/setXRot/yHeadRot/yBodyRot`。

**`MixinEntityMoveRelative`**（6DOF 输入）：
```java
if (self instanceof LocalPlayer player && SpaceViewRotation.isActive(player)) {
    Vector3f local = new Vector3f((float)(-relative.x), (float)relative.y, (float)(-relative.z));
    if (local.lengthSquared() > 1.0F) local.normalize();
    local.mul(amount).rotate(SpaceViewRotation.orientation());
    self.setDeltaMovement(self.getDeltaMovement().add(local.x, local.y, local.z));
    ci.cancel();
}
```

**`MixinPlayerFlight`**（创造飞行时的相机相对飞行 + 阻尼）：
- 门：`self instanceof LocalPlayer && getAbilities().flying && !isPassenger() && SpaceViewRotation.isActive(player)`
- 基：`left = (−1,0,0)·q`、`up = (0,1,0)·q`、`forward = (0,0,−1)·q`
- `strafe = xxa`、`fwd = zza`，`len > 1` 时归一
- `ascend = (input.jumping ? 1 : 0) − (input.shiftKeyDown ? 1 : 0)`
- `horizontalSpeed = flyingSpeed * (isSprinting() ? 2 : 1)`
- 相机是自己时先 `delta.y -= ascend * flyingSpeed * 3`
- `delta += (left*strafe + forward*fwd) * horizontalSpeed + up * ascend * flyingSpeed * 3`
- `setDeltaMovement(delta)` → `move(MoverType.SELF, delta)` → **在相机系里阻尼**：
  `alongLeft = (moved·left) * 0.91`、`alongUp = (moved·up) * 0.6`、`alongForward = (moved·forward) * 0.91`，
  再 `left*alongLeft + up*alongUp + forward*alongForward` 写回
- `resetFallDistance()`、`ci.cancel()`

**⚠ `abilities.flying` 在 0.3.0 全库从未被设置**（grep `mayfly|setFlying|flying = true` 只命中这两处读取）。
⇒ `MixinPlayerFlight` 只在玩家**本来就会飞**（创造/旁观/别的模组给）时增强手感；
**生存模式的太空移动靠的是"零重力 + `MixinEntityMoveRelative` 的相机相对输入"**，不需要飞行能力。

### 2.3 我们要删/加的东西

**删（对齐 0.1.3 的旧模型，约 980 行）**：
`physics/PlayerPhysicsBody.java`(485)、`mps/physical/entity/PhysicalEntity.java`(211)、
`mixin/SpaceInputRotationMixin.java`(126)、`mixin/EntityPhysicsDriveMixin.java`(62)、
`physics/PhysicsDrivenPlayers.java`(42)、`client/physics/ClientPhysicsDriver.java`(32)、
`mixin/ServerPacketClampMixin.java`(22)

**加（0.3.0 的模型，约 856 行）**：上面 2.1 那六个文件。

### 2.4 这一项的行为影响（要写进实机判据表）

- 玩家不再由 Rapier 驱动 ⇒ **我们当初"接受"的接触穿透锯齿从设计上消失**（那不是 bug 修复，是模型换了）。
- 原版跳跃/贴地/摔落/游泳/爬梯语义**回归**（不再被 `move` HEAD cancel 掉）。
- 新增能力：太空自由视角（鼠标 ×0.15）+ 相机相对 6DOF 输入 + 靠近星球自动对齐地面。
- 风险点：`PhysicalEntityCollision` 的 15 轴 SAT 与我们的体素读取接口必须接对；
  以及"玩家在太空怎么获得推进"在生存下变成"零重力飘"——**这可能改变我们现有的太空玩法手感**，要重点实机验。

## 三、第 2 项：体素粒度 64³→16³（机械但面广）

| 文件 | 0.1.3 | 0.3.0 |
|---|---|---|
| `PhysicalBody` | `CHUNK_WIDTH=64`、`CHUNK_COUNT/TOTAL=2/8`、`chunkIndex=x*4+y*2+z` | `16`、`8/512`（转 public）、`x*64+y*8+z` |
| `PalettedChunk` | `WIDTH/SIZE=64/262144`、`index=x+(z<<6)+(y<<12)` | `16/4096`、`x+(z<<4)+(y<<8)` |
| `ProjectionManager` | 脏包 `slot<<8\|(cx<<2)\|(cy<<1)\|cz`、上传/读取循环 8 次 | `slot<<9\|(cx&7)<<6\|(cy&7)<<3\|(cz&7)`、512 次 |
| `uploadChunk` | `chunkOffset = 1` | `4`（居中 = chunk−4，范围 −4..3） |
| `ClientPhysicalBody` | 8 个顶点缓冲 | 512 个 |
| `ServerPhysicalBody` | — | 新增 `getBlockEntityEntries(cx,cy,cz)`，`MAX_ENTRIES=4096` |

顺带（同批值得一起做）：`PhysicalChunkManager` 删掉 `old_physicalChunks`
（0.1.3 那个 Map 从不清理，**是真实的内存泄漏**）。

## 四、第 4 项：`Planet` 云层字段 ✅ 完成

0.3.0 的 `Planet` 在 encode/decode **中间**插入 `cloudBottom`/`cloudTop` ⇒ **线格式不兼容**。
数据包 cloud 段新增可选 `bottom`/`top`（缺省回退 `cloudHeight`），
由 `SpaceModDataPackManger` 调 `setCloudBottom`/`setCloudTop`。

**已做**：`Planet` 两个字段 + encode/decode（位置与 0.3.0 相同）+ 4 个 getter/setter +
`getCloudThickness()`（`max(top−bottom, 0)`，与 0.3.0 的 `CelestialBodyDataUBO` 同式，放在模型层免得每个消费者各 clamp 一遍）；
`SpaceModDataPackManger` 解析可选 `bottom`/`top`。

**⚠ 目前只到数据层，字段暂时没人读** —— 这不是遗漏，是如实记录：
本项目的 `cloudHeight` 本身就是"只存不读"（全库只有 encode/decode/getter/setter，
**没有任何渲染消费**）。0.3.0 里这两个字段由 `CelestialBodyDataUBO` 写进一个 vec4
（`cloudBottom, cloudTop, max(top−bottom,0), 0`）供**体积云阶段**使用 ——
而体积云在 0.3.0 是"从死代码变成完整阶段"的大工程（`PLANET_CLOUD` 开关、
`TransmittanceTarget`、`cloudMaskSampler`、`renderPlanetCloud`）。
**本项不含渲染**；要不要把体积云一起做，需要用户再定。

**新增契约**（`check-artifact.ps1`）：
- `.class` 契约：`Planet.class` 必须含 `setCloudBottom/getCloudBottom/setCloudTop/getCloudTop/getCloudThickness`。
- **顺序契约**（新机制）：4 条 `IndexOf` 链式递增断言，钉住 encode/decode 的字段顺序与 setter 齐全。
  为什么需要新机制：字段顺序在 `.class` 常量池里**看不见**（顺序不是字符串），
  而顺序错 = 跨版本整段错位/解出来的环数据全乱。用 `IndexOf` 而不是"多行字面子串"是为了对 CRLF/LF 差异免疫。

## 五、验证约定（每项都要给）

1. `.\gradlew compileJava` 必须 `BUILD SUCCESSFUL`。
2. `.\native\jni-smoketest\run-offline-checks.ps1` 必须 **exit 0**（现 14 步）；
   每加一条判据就并入该脚本（注意它有**双 BOM**：改完要用
   `[System.IO.File]::ReadAllBytes/WriteAllBytes` 补 `EF BB BF EF BB BF`，`edit` 工具会吃掉 BOM）。
3. 每个用户可见的行为改动都要给**实机判据表**（PASS/FAIL 由绝对坐标判定，不用相机相对量）。
4. 助手**不启动客户端**；实机验证由用户做。
5. 破坏性重构前先提交一个可回滚点（用户明确偏好）。
