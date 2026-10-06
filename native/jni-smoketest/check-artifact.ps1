# 产物自检 —— 证明"改动真的进了 build 产物"，而不是只改了源码
#
#   pwsh native\jni-smoketest\check-artifact.ps1
#
# 为什么需要它（本项目两次真实教训）：
#   1) §31.9：Mixin 注入**成功是静默的**，没有报错 ≠ 注入成功。当时靠 javap 核对 class 里
#      有没有注入痕迹才发现问题；只改源码、忘了重编，表现和"守卫生效"一模一样（都"没卡死"）。
#   2) 本轮：判据/日志文本改了一堆，如果客户端跑的是旧 class，日志里就还是旧格式 ——
#      于是"抄一行发现是 n/a"会被误读成"判据坏了"，其实是**产物没更新**。
#
# 做法：直接在 `build\classes\java\main` 的 .class 里搜**常量池里的字符串/方法名**
# （class 文件的常量池用 UTF-8 存字面量，按 UTF-8 读成文本就能搜）。
# 每个 marker 都对应一处**本轮或近期**的改动；任一缺失 = 该改动没进产物。
#
# 退出码 0 = 全部命中；1 = 有缺失。

$ErrorActionPreference = 'Stop'
chcp 65001 > $null
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$classes = Join-Path $root 'build\classes\java\main'

if (-not (Test-Path $classes)) {
    Write-Host "找不到 $classes —— 先跑 gradlew compileJava" -ForegroundColor Red
    exit 1
}

# 类名 → 必须出现的 marker（字符串常量或方法名）与它的含义
$checks = @(
    @{ cls = 'com\mss\polymech\space\SpaceWorld.class';
       marks = @('缩放(1格=', 'STATIC_CHECKSUM_GATE', '（闸门=', '静态位置表被改过');
       why = '坐标约定标签 + 自检闸门真比较' },
    @{ cls = 'com\mss\polymech\space\SpaceTransitionHandler.class';
       marks = @('与去程方向夹角', '半径比=', '期望≈2.20', '期望≈1.02');
       why = '往返诊断字段（S0）' },
    @{ cls = 'com\mss\polymech\space\SpaceScaleMigration.class';
       marks = @('staleScaleDirection', 'nearestBodyDistance3D', '跨约定');
       why = '双向迁移 + 三维距离判据（S1）' },
    @{ cls = 'com\mss\polymech\client\space\SpaceRenderer.class';
       marks = @('n/a(样本不足)', '世界暂停/未推进', 'PASS(每帧<0.5px)', '太阳角直径=');
       why = '四条判据修复（S0）' },
    @{ cls = 'com\mss\polymech\client\space\RenderCompression.class';
       marks = @('compress', 'zoomFor');
       why = '距离压缩本体' },
    @{ cls = 'com\mss\polymech\client\space\CelestialBodyDataBuffer.class';
       marks = @('zoomOf');
       why = 'UBO 压缩接线（④）' },
    @{ cls = 'com\mss\polymech\mps\kelvin\physical\celestial_body\variant\Planet.class';
       marks = @('setCloudBottom', 'getCloudBottom', 'setCloudTop', 'getCloudTop', 'getCloudThickness');
       why = '云层底面/顶面字段（space 0.3.0 第 4 项）确实编进了产物；顺序由下面的顺序契约钉' },
    @{ cls = 'com\mss\polymech\mps\kelvin\event\PhysicalBodySpaceEvent.class';
       marks = @('toGame', 'toSpace', 'toMc');
       why = '物理↔天体桥的三处边界换算（S2）' },
    @{ cls = 'com\mss\polymech\mixin\LevelRendererCelestialSkyMixin.class';
       marks = @('原版日月已隐藏');
       why = '只掐原版日月的 mixin（且证明 mixin 类确实被编译进产物）' },
    @{ cls = 'com\mss\polymech\item\PhysgunItem.class';
       marks = @('已抓住', 'serverTick', 'tickGrab', 'physicsStep', 'applyAction', 'startDrag', 'stopDrag',
                 'PhysgunActionPacket', 'PhysgunSpring', 'PhysgunBeamPacket', 'BodyRaycast',
                 'PhysicsBodyTracker', 'nearestBodyInfo');
       why = '牵引枪（抓取 + 弹簧拖拽 + 光束广播 + 音效）—— 2026-09-29 起在 PhysicsBodyTracker 上求交；2026-10-01 起右键是开关（serverTick 驱动事务，2026-10-04 起施力在 physicsStep、每 100Hz 子步一次）。⚠️ 别把 START_DRAG/STOP_DRAG 写进 marks：switch 里的枚举常量名会被 javac 放进合成的 $SwitchMap 类，本类里没有这两个字符串（写进去必假 MISS）' },
    @{ cls = 'com\mss\polymech\physics\BodyRaycast.class';
       marks = @('INFLATE', 'nearest', 'toLocalPoint');
       why = '服务端体求交（与客户端同一套 RayBox 数学；离线判据第 12 步）' },
    @{ cls = 'com\mss\polymech\physics\BodyRaycast$Hit.class';
       marks = @('localX', 'localY', 'distance');
       why = '求交结果内部 record（局部命中点 / 距离）—— 内部类是独立 .class，marker 别写错地方' },
    @{ cls = 'com\mss\polymech\physics\PhysicsBodyTracker.class';
       marks = @('hasAdjacentBody', 'findAdjacentBody', 'placeBlockAt', 'rotationOf');
       why = '建体/并体的唯一原语 + 邻接查询 + 旋转取值（牵引枪要用）' },
    @{ cls = 'com\mss\polymech\client\physics\PhysgunBeamRenderer.class';
       marks = @('AFTER_TRANSLUCENT_BLOCKS', 'PhysgunRenderTypes', 'drawCellFaces', 'drawCellEdges',
                 'drawLockFaces', 'CELL_LINE_WIDTH', 'HOVER_RGB');
       why = '视觉：平滑曲线光束（贝塞尔 + 受力弯曲）+ 端点光斑 + **单格**棋盘悬停框 + 抓取 lock' },
    @{ cls = 'com\mss\polymech\client\physics\PhysgunTarget.class';
       marks = @('liveTransform', 'boundsOf', 'INFLATE');
       why = '客户端解析拾取（不依赖客户端碰撞体）' },
    @{ cls = 'com\mss\polymech\client\physics\PhysgunClientState.class';
       marks = @('grabbedBodyId', 'holdDistance', 'applyServer', 'setPredictedSource');
       why = '按玩家的光束注册表（外层：预测 / 权威 / 松手 三个入口）' },
    @{ cls = 'com\mss\polymech\client\physics\PhysgunClientState$Beam.class';
       marks = @('serverDriven', 'applyServerSource', 'refreshEnd', 'nodeCount', 'tick');
       why = '单条光束内部类（端点插值 + 虚拟竿尖软弹簧 + 服务端权威接管；内部类是独立 .class）' },
    @{ cls = 'com\mss\polymech\client\physics\PhysgunRenderTypes.class';
       marks = @('poly_mech_physgun_beam', 'poly_mech_physgun_overlay', 'poly_mech_physgun_checker',
                 'ADDITIVE_TRANSPARENCY', 'physgun_checker.png');
       why = '自定义渲染通道（光束加色 / 覆盖层 / 棋盘贴图面）——本项目第一次用 RenderType.create' },
    @{ cls = 'com\mss\polymech\physics\PhysgunBeamShape.class';
       marks = @('segments', 'bezier', 'lagStep', 'taper', 'cellFace', 'RELEASE_DECAY');
       why = '光束曲线 + 受力弯曲弹簧 + 悬停格面几何（离线判据 PhysgunBeamShapeProbe 调的就是这一类）' },
    @{ cls = 'com\mss\polymech\client\renderer\RenderPassGuard.class';
       marks = @('共享 BufferSource', 'Not building', 'open', 'close');
       why = '渲染通道守卫（共享 BufferSource 一次只允许一个非 fixed 通道；2026-09-29 崩溃换来的）' },
    @{ cls = 'com\mss\polymech\network\PhysgunBeamPacket.class';
       marks = @('physgun_beam', 'BROADCAST_INTERVAL_TICKS', 'PhysgunBeamPacket');
       why = '光束广播包（服务端权威 ⇒ 修「抓住了却没有线」+ 别人也看得见）' },
    @{ cls = 'com\mss\polymech\physics\RayBox.class';
       marks = @('toLocalPoint', 'toLocalDirection', 'intersect', 'blockBounds');
       why = '射线/盒纯数学（离线判据第 4 节调的就是这一类）' },
    @{ cls = 'com\mss\polymech\physics\SpaceBlockPlacement.class';
       marks = @('太空建造', 'onRightClickItem', 'placeBlockAt', 'hasAdjacentBody');
       why = '太空放方块 ⇒ 走 tracker 原语造/并物理体（第二版）' },
    @{ cls = 'com\mss\polymech\physics\SpaceBuildRules.class';
       marks = @('MAX_BODIES_PER_DIMENSION', 'PLACE_DISTANCE', 'targetKind', 'rotateFace');
       why = '放置判据 + 创建/扩增分流 + 命中面世界化（离线判据调的就是这一类）' },
    @{ cls = 'com\mss\polymech\physics\PhysicsBodyTracker.class';
       marks = @('hasAdjacentBody', 'findAdjacentBody', 'placeBlockAt');
       why = '建体/并体的唯一原语 + 抽出的邻接查询（同一次扫描，无第二份实现）' },
    @{ cls = 'com\mss\polymech\physics\PhysgunSpring.class';
       marks = @('MAX_ERROR', 'MAX_A', 'accel');
       why = '弹簧数学（离线探针调的就是这一类）' }
)

$miss = 0
Write-Host "===== 产物自检（$classes）=====" -ForegroundColor Cyan
foreach ($c in $checks) {
    $path = Join-Path $classes $c.cls
    $name = Split-Path $c.cls -Leaf
    if (-not (Test-Path $path)) {
        Write-Host ("[MISS] {0,-42} 类不存在（没编？）  —— {1}" -f $name, $c.why) -ForegroundColor Red
        $miss++
        continue
    }
    $text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    $bad = @()
    foreach ($m in $c.marks) { if (-not $text.Contains($m)) { $bad += $m } }
    if ($bad.Count -eq 0) {
        Write-Host ("[ OK ] {0,-42} {1}" -f $name, $c.why) -ForegroundColor Green
    } else {
        Write-Host ("[MISS] {0,-42} 缺: {1}  —— {2}" -f $name, ($bad -join ', '), $c.why) -ForegroundColor Red
        $miss++
    }
}

# 资源文件自检：渲染通道引用的贴图必须真的在 src\main\resources 里。
# 少了它**不会编译报错**，游戏里只会变成"紫黑格" —— 典型的静默坏，所以要在这里拦住。
$resFiles = @(
    @{ file = 'src\main\resources\assets\poly_mech\textures\misc\physgun_checker.png';
       why = '牵引枪悬停框的棋盘贴图（生成器：native\jni-smoketest\MakeCheckerTexture.java）' }
)

Write-Host ""
Write-Host '--- 资源文件 ---' -ForegroundColor Cyan
foreach ($r in $resFiles) {
    $p = Join-Path $root $r.file
    if (Test-Path $p) {
        Write-Host ("[ OK ] {0,-44} {1}" -f (Split-Path $r.file -Leaf), $r.why) -ForegroundColor Green
    } else {
        Write-Host ("[MISS] {0,-44} 文件不存在 —— {1}" -f (Split-Path $r.file -Leaf), $r.why) -ForegroundColor Red
        $miss++
    }
}

# --- 着色器/接口契约（源文本级；这是 2026-09-29 / 09-30 / 09-30 三次「星球挡住物理体」bug 的回归判据）---
# 为什么必须查文本：那几次都是「深度纹理绑错」/「遮挡判据写错」/「两套投影的深度混进同一张缓冲」，
# 编译期毫无提示、只有实机画面看得出来 —— 契约只能钉在文本上。
#
# 2026-09-30 第十二轮订正：判据从「主深度 < 1.0 + 画完把主深度清一遍」（第十一轮的权宜之计）
# 换成 **space 的结构**：天体画进独立的 spaceRenderTarget（两套 Z 约定不再混进同一张深度缓冲），
# SpaceDepthFarMixin 让两套投影共用同一对 near/far（只是对调顺序）⇒ 1 - mainDepth 与 spaceDepth
# 成为精确镜像。下列契约钉的就是这套结构里**少一条就退化**的环节。
# 离线量化见 native\jni-smoketest\DepthOcclusionProbe.java。
$contracts = @(
    @{ file = 'src\main\resources\assets\poly_mech\shaders\program\planet\planet_atmosphere.fsh';
       must = 'texture(DepthSampler, texCoord).r < 1.0 - 1.0e-7';
       why = '大气必须"世界优先"：这一像素有世界几何体 ⇒ 输出 0（否则星球/大气盖住物理体）' },
    @{ file = 'src\main\resources\assets\poly_mech\shaders\program\star\star_bloom.fsh';
       must = 'texture(DepthSampler, screenPos).r < 1.0 - DEPTH_EPS';
       why = '泛光的 MC 几何体掩码同理（跨投影比大小的旧判据是假的）' },
    @{ file = 'src\main\java\com\mss\polymech\client\space\SpaceRenderer.java';
       must = 'reversedZ ? (float) (RenderCompression.FAR * 2.0) : SPACE_NEAR_PLANE';
       why = '天体投影必须是反向 Z（near=FAR×2, far=0.05）：正常 Z 下 16384~262144m 只剩 ~48 个 float 值 ⇒ 行星 z-fighting' },
    @{ file = 'src\main\java\com\mss\polymech\client\space\SpaceRenderer.java';
       must = 'GlStateManager._depthFunc(GL11.GL_GEQUAL)';
       why = '反向 Z 必须配 GEQUAL + 清 0.0（配 LEQUAL 会整层天体不显示）' },
    @{ file = 'src\main\java\com\mss\polymech\client\space\SpaceRenderer.java';
       must = 'spaceSkyDepthTexture = spaceRenderTarget.getDepthTextureId();';
       why = '后处理的 SpaceDepthSampler 必须来自**天体层独立缓冲**的深度（大气靠它重建星球表面位置）' },
    @{ file = 'src\main\java\com\mss\polymech\client\space\SpaceRenderer.java';
       must = 'spaceBlitPass.process(partialTick);';
       why = '天体层颜色必须合成回主缓冲（漏了 ⇒ 星球整个看不见）' },
    @{ file = 'src\main\java\com\mss\polymech\client\space\SpaceAtmosphereRenderer.java';
       must = 'effect.setSampler("SpaceDepthSampler", () -> skyDepthTextureId)';
       why = 'SpaceDepthSampler 必须绑天体层深度，不能与 DepthSampler 同一张（那正是 bug 本身）' },
    @{ file = 'src\main\java\com\mss\polymech\client\space\SpaceAtmosphereRenderer.java';
       must = 'useMcDepth.set(1)';
       why = 'useMinecraftDepth 必须为 1（space 的取值）：ScreenToWorld 取"世界与天体里更近的那个表面"' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceDepthFarMixin.java';
       must = 'return (float) (RenderCompression.FAR * 2.0);';
       why = '两套投影必须"对表"：MC 主投影的 far 要等于天体投影的 near，否则 1-mainDepth 与 spaceDepth 不可比' },

    # ── 子块粒度 64³→16³（第 2 项）：探针里复刻的公式必须与实际实现一致 ──────────
    # SubChunkGranularityProbe 离线跑（不加载 MC 类），所以它只能复刻这几个公式；
    # 这里把"复刻 == 实现"钉住 —— 缺一条，探针就可能一直在验证一个已经不存在的东西。
    @{ file = 'src\main\java\com\mss\polymech\mps\physical\helper\PalettedChunk.java';
       must = 'return x + (z << 4) + (y << 8);';
       why = 'PalettedChunk.index 的位移必须与边长 16 匹配（探针 D 组复刻的就是这一行）' },
    @{ file = 'src\main\java\com\mss\polymech\mps\physical\physical_body\PhysicalBody.java';
       must = 'int lx = i & 15;';
       why = 'voxelBounds 必须用 index 的逆位移（&15 / >>4&15 / >>8）；漏改会让碰撞盒整体错位且不报错' },
    @{ file = 'src\main\java\com\mss\polymech\mps\physical\physical_body\PhysicalBody.java';
       must = 'int lz = (i >> 4) & 15;';
       why = 'voxelBounds 的 z 位移（与 index 的 <<4 互逆）' },
    @{ file = 'src\main\java\com\mss\polymech\mps\physical\physical_body\PhysicalBody.java';
       must = 'int ly = i >> 8;';
       why = 'voxelBounds 的 y 位移（与 index 的 <<8 互逆）' },
    @{ file = 'src\main\java\com\mss\polymech\mps\physical\physical_body\PhysicalBody.java';
       must = 'int baseX = cx * CHUNK_WIDTH - MAX_WIDTH / 2;';
       why = '★ 投影偏移必须是 -MAX_WIDTH/2（-64），不是 -CHUNK_WIDTH（-16）：后者只覆盖 [-16,112)，静默错位 48 格' },
    @{ file = 'src\main\java\com\mss\polymech\mps\physical\physical_body\PhysicalBody.java';
       must = 'public static final int CHUNK_COUNT = 8;';
       why = '子块数 8（探针 A 组与 ProjectionManager 的位宽都依赖它）' },
    @{ file = 'src\main\java\com\mss\polymech\mps\physical\physical_body\PhysicalBody.java';
       must = 'public static final int CHUNK_TOTAL = 512;';
       why = '子块总数 512（数组长度 / 上传循环上界）' },
    @{ file = 'src\main\java\com\mss\polymech\mps\physical\manger\ProjectionManager.java';
       must = '32 - Integer.numberOfLeadingZeros(Math.max(1, PhysicalBody.CHUNK_COUNT - 1))';
       why = '脏块键位宽必须由子块数推出（8 ⇒ 3 位）；探针 F 组复刻的就是这个式子' },
    @{ file = 'src\main\java\com\mss\polymech\mps\physical\manger\ProjectionManager.java';
       must = 'private static final int CHUNK_MASK = (1 << CHUNK_BITS) - 1;';
       why = '★ 3 位/坐标必须配掩码：不掩码时坐标 2..7 会溢出到相邻字段 ⇒ 标脏标到别的子块（不报错，只是不更新）' },
    @{ file = 'src\main\java\com\mss\polymech\mps\physical\manger\ProjectionManager.java';
       must = 'int chunkOffset = PhysicalBody.CHUNK_COUNT / 2;';
       why = '上传/读取的居中偏移必须是 CHUNK_COUNT/2（=4）：0.1.3 是 1，粒度改了不同步改就整体平移 48 格' },
    @{ file = 'src\main\java\com\mss\polymech\mps\network\packet\SyncPhysicalBodyBlockEntity.java';
       must = 'int width = PhysicalBody.CHUNK_WIDTH;';
       why = '方块实体清理宽度必须跟粒度（写死 64 会清错区域：拆掉的箱子还留着 / 清掉隔壁子块的箱子）' },

    # ── 牵引枪角伺服（2026-10-01「抓住时的旋转控制」）─────────────────────────
    # 离线探针 PhysgunRotationProbe 复刻了"角加速度 → 乘惯量 → 力矩冲量"这条链，
    # 但探针里调用的是真实类，所以这里只需钉"实现里的关键片段没被改掉"。
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysgunRotation.java';
       must = 'mass * s * s / 6.0';
       why = '★ 惯量估计必须与建体公式同形（下面那条钉住另一半）——两边不一致则"补的质量惯量"与"伺服以为的惯量"对不上' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysicsBodyMass.java';
       must = 'extra * s * s / 6.0';
       why = '★ 建体时的附加惯量公式（与 PhysgunRotation.estimateInertia 是同一套近似，改一处必须改两处）' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysgunRotation.java';
       must = 'err.set(-err.x, -err.y, -err.z, -err.w)';
       why = '★ 四元数双重覆盖：q 与 -q 是同一旋转，不逐分量取反就会走"长弧"（物体朝反方向绕一大圈）' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysgunRotation.java';
       must = 'acc.mul(MAX_ANG_ACCEL / len)';
       why = '角加速度限幅（θ=π 时未限幅是 25.13 rad/s²；没有它抓一个反过来的体会被拧成陀螺）' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysicsBodyTracker.java';
       must = 'NativePhysics.bodyApplyTorqueImpulse(world, tracked.body, tx, ty, tz)';
       why = '★ 旋转的唯一写入路径：必须是"力矩冲量"而不是 setRotation（参考也是让引擎的马达驱动，写朝向会和求解器抢权威）' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysicsBodyTracker.java';
       must = 'PhysgunRotation.estimateInertia(mass, tracked.blocks.size())';
       why = '惯量由"总质量 × 方块数"估（原生层没有惯量读取接口，只能估；只用于增益归一化）' },

    # ── 牵引枪旋转控制的**接线**（2026-10-01 第 2 步）──────────────────────────
    # 核心数学已在上面钉住；这一段钉的是"输入 → 网络 → 服务端执行"这条链没断。
    @{ file = 'src\main\java\com\mss\polymech\mixin\MouseHandlerMixin.java';
       must = 'target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"';
       why = '★ 注入点：已用 javap 在 neoforge-21.1.228-merged.jar 的 MouseHandler.turnPlayer 字节码里确认这条 invokevirtual 存在（序号无关的 WrapWithCondition）' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\MouseHandlerMixin.java';
       must = 'return !PhysgunInputHandler.onMouseMove(yaw, pitch)';
       why = '旋转模式下吃掉视角转动（相机冻结，鼠标只转物体）—— 参考用 ci.cancel()，等价' },
    @{ file = 'src\main\resources\poly_mech.mixins.json';
       must = '"MouseHandlerMixin"';
       why = '★ mixin 必须注册进 client 段，否则整条旋转输入链静默失效（不报错、就是不转）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunInputHandler.java';
       must = 'player.calculateViewVector(0.0F, player.getYRot() - 90.0F)';
       why = '俯仰要绕"世界侧向轴"（玩家右手边），照参考逐字：calculateViewVector(0, yRot - 90)' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunInputHandler.java';
       must = 'q.rotateLocalY(';
       why = '偏航绕**物体自身** Y（参考的 orientation.rotateLocalY）；写成 rotateY 就变成绕世界 Y，物体会"歪着转"' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunInputHandler.java';
       must = 'q.premul(new Quaterniond(new AxisAngle4d(';
       why = '俯仰用 premul 施加在**世界系**（参考逐字）；用 mul 会变成绕物体自身的侧向轴，两者在物体已倾斜时完全不同' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunInputHandler.java';
       must = 'event.setCanceled(true)';
       why = '滚轮改持握距离时必须吃掉这次滚动，否则会顺手切快捷栏（参考也是 cancel）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunInputHandler.java';
       must = 'Math.sqrt(current / 10.0)';
       why = '滚轮灵敏度随距离按 √(d/10) 放大（钳 [1,5]，疾跑 ×4）—— 照参考，远距离才挪得动' },
    @{ file = 'src\main\java\com\mss\polymech\Polymech.java';
       must = 'com.mss.polymech.network.PhysgunDragPacket::handle';
       why = '★ 拖拽包必须注册成 playToServer，否则客户端发了没人收（服务端永远不做角伺服）' },
    @{ file = 'src\main\java\com\mss\polymech\PolymechClient.java';
       must = 'PhysgunInputHandler.ROTATE_MODE_KEY';
       why = '★ 按键必须注册，否则 ROTATE_MODE_KEY.isDown() 恒假 ⇒ 永远进不了旋转模式' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'PhysicsBodyTracker.applyTorqueImpulse(grab.bodyId(), spin.x, spin.y, spin.z)';
       why = '★ 服务端真的施加了角伺服的力矩冲量（在 physicsStep 里、每 100Hz 物理子步一次（2026-10-04 起），与线性那半同节奏）' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'PhysgunRotation.errorAxisAngle(current, grab.orientation())';
       why = '角误差由 PhysgunRotation（纯函数、有离线判据）算，服务端不另写一份' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'grab.bodyId() != packet.bodyId()';
       why = '★ 服务端校验：客户端可改包，必须再查一遍"这个玩家确实抓着这个体"' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'q.lengthSquared() < 1.0e-12';
       why = '★ 拒绝退化四元数：NaN/零朝向喂进角伺服会让刚体角速度变 NaN，整个体就废了' },

    # ── 缺口 (2)：体局部格 ⇄ 世界格的映射必须含刚体旋转（2026-10-01）────────────
    @{ file = 'src\main\java\com\mss\polymech\physics\RayBox.java';
       must = 'out[0] = (int) Math.floor(local.x);';
       why = '★ "块占 [x, x+1)" 最小角约定：世界格 → 局部格必须 floor（把整数当块心会差半格，本项目栽过）' },
    @{ file = 'src\main\java\com\mss\polymech\physics\RayBox.java';
       must = 'new Vector3d(x, y, z).sub(bodyPos)';
       why = '世界→局部 = R⁻¹·(p − 体位置)（toLocalCell 与客户端拾取共用这一条）' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysicsBodyTracker.java';
       must = 'RayBox.toLocalCell(bodyPos, rot, pos.getX(), pos.getY(), pos.getZ(), cell)';
       why = '★ 邻接判定改在**体局部空间**算（含旋转），不再用 origin + 局部' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysicsBodyTracker.java';
       must = 'Math.abs(e.dx() - cell[0])';
       why = '★ 曼哈顿判据改在局部空间比：局部空间里方块永远是轴对齐整数格，判据在那里才良定义' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysicsBodyTracker.java';
       must = 'placeBlock(adjacent.getKey(), localCell[0], localCell[1], localCell[2], stateId)';
       why = '★ 放方块的局部格由 findAdjacentBody 在局部空间算好返回（原来用 pos − origin 反算，旋转/平移后都错）' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysicsBodyTracker.java';
       must = 'RayBox.toWorldCenter(new Vector3d(tracked.x, tracked.y, tracked.z)';
       why = 'localToWorld 与 toLocalCell 共用同一对映射（单一来源；本项目在"同一概念两份实现"上吃过亏）' },

    # ── 物理体方块射线：必须照 space 0.3.0 的 ShipRaycast（2026-10-06）────────────
    # 旧的手写实现（physics/PhysicsRaycast.java）已删除：它把方块表拍成 int[]、逐块当单位立方体做 slab，
    # ① 真实形状被当满格；② 某轴提供进入点时漏夹 tmax ⇒ 擦边也报命中 ⇒ "瞄半天挖不掉 / 互动错格"。
    # 这几条是**正向**契约：谁把挖掘射线改回旧写法，这里就 MISS。
    @{ file = 'src\main\java\com\mss\polymech\physics\BodyVoxelRaycast.java';
       must = 'BlockGetter.traverseBlocks(localStart, localEnd, shapes,';
       why = '★ 照 space：逐格用**原版 DDA** 走，不自己写 slab 循环（边界归属/命中面语义全靠它）' },
    @{ file = 'src\main\java\com\mss\polymech\physics\BodyVoxelRaycast.java';
       must = 'return shape.clip(start, end, pos);';
       why = '★ 照 space：用方块**真实形状**求交（台阶/半砖/栅栏不再是满格）' },
    @{ file = 'src\main\java\com\mss\polymech\physics\BodyVoxelRaycast.java';
       must = 'shape = Shapes.block();';
       why = '照 space：形状为空的方块按满格处理（否则草/火把这类会被射线穿过去）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysicsBodyInteractionClient.java';
       must = 'BodyVoxelRaycast.traverse(localStart, localEnd, shapesOf(body))';
       why = '★ 挖掘射线必须走新几何内核（旧 physics/PhysicsRaycast 已删除，回归即 MISS）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysicsBodyInteractionClient.java';
       must = 'box.clip(origin, end)';
       why = '★ 照 space 第一步：先用体的世界 AABB 粗筛，再做局部 DDA（少了它，大船的每格都要走一遍）' },
    @{ file = 'native\jni-smoketest\run-offline-checks.ps1';
       must = 'BodyVoxelRaycastProbe.java';
       why = '★ 射线判据必须并入离线自检，否则探针不会被执行（判据写了等于没写）' },

    # 放置射线（2026-10-06 修）：原来打在 **MPS 克隆栈**（恒空）上 ⇒ 永远 null ⇒
    # "对着体某一面放置"是死代码，每次放置退化成 eye + look × 3.0。这五条是正向契约：
    # 谁把放置射线改回 B 栈、或把世界面那一步省掉，这里就 MISS。
    @{ file = 'src\main\java\com\mss\polymech\physics\SpaceBlockPlacement.java';
       must = 'PhysicsBodyTracker.raycastBody(level, eye, look, REACH)';
       why = '★★ 放置射线必须打在 **A 自己的体**上（B 的 ServerPhysicalWorld 恒空，打上去永远是 null）' },
    @{ file = 'src\main\java\com\mss\polymech\physics\SpaceBlockPlacement.java';
       must = 'hit.worldFace()';
       why = '★ 目标格必须沿**世界**面取相邻格：体被转过（牵引枪）时不转面就会放到体的另一侧' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysicsBodyTracker.java';
       must = 'public static BodyVoxelHit raycastBody(';
       why = '★ 服务端射线入口（体局部 DDA + 真实形状，与客户端同一份几何内核）' },
    @{ file = 'src\main\java\com\mss\polymech\physics\BodyVoxelRaycast.java';
       must = 'public static AABB worldAabb(';
       why = '★ 共用几何：世界 AABB 粗筛盒（服务端与客户端同一份，别再各写一套）' },
    @{ file = 'native\jni-smoketest\BodyVoxelRaycastProbe.java';
       must = 'groupF();';
       why = '★ F 组（旋转体的放置几何：局部面→世界面→目标格）必须真的被执行，删了这行就等于没判' },

    # ── 操作方式对齐 Create Aeronautics（用户 2026-10-01 拍板）──────────────────
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunInputHandler.java';
       must = 'GLFW.GLFW_KEY_TAB';
       why = '★ 旋转模式默认键必须照参考 SimKeys.ROTATE_MODE = GLFW_KEY_TAB（用户拍板"操作方式以机械动力航空学为准"）；当初自行挑的左 Alt 属未取证猜测，不得回归' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunInputHandler.java';
       must = 'Math.max(MIN_DISTANCE, Math.min(PhysgunItem.RANGE, next))';
       why = '距离钳位照参考 clampDistance = clamp(d, 2.0, RANGE)（下限 2.0 逐字一致）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunInputHandler.java';
       must = 'Math.max(1.0, Math.min(5.0, Math.sqrt(current / 10.0))) * (sprint ? 4.0 : 1.0)';
       why = '滚轮灵敏度照参考：clamp(√(d/10), 1, 5) × (疾跑 ? 4 : 1) —— 三个数都是参考的，别"顺手调手感"' },

    # ── 操作方式完全对齐：射程与左键语义（用户"一切都按照他的来"）──────────────
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'public static final double RANGE = 128.0;';
       why = '★ 射程照参考 PhysicsStaffItem.RANGE = 128.0f（客户端与服务端共用这一个常量，改一处两端一致）' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {';
       why = '★ 照参考：拿枪时左键不破坏方块（左键是 LOCK，不拦就会每次锁一下顺手挖掉准星前的方块）；这是服务端第二道，客户端那层也会吃掉左键' },

    # ── 操作方式对齐（2/3）：右键从"长按"改成"开关"（照参考的 PRESS + toggle）──────────
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'public static void serverTick(MinecraftServer server)';
       why = '★ 施力必须由服务端自己按 GRABS 表推进 —— 参考不用原版"使用中"状态（它在鼠标层就 cancel 了右键），所以施力不能挂在 onUseTick 上' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'case START_DRAG -> startDrag(sp);';
       why = '★ 右键按下 ⇒ 开始拖（服务端射线决定抓谁，不信任包里的 bodyId）' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'case STOP_DRAG -> {';
       why = '★ 右键再按一次 ⇒ 松手（开关，照参考 onItemUsed 的 START_DRAG && DRAGGING 分支）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunInputHandler.java';
       must = 'if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {';
       why = '★ 右键走"开关"、左键走 LOCK，两者都已在鼠标层处理；这一条钉住"除了左右键别的都不抢"' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunInputHandler.java';
       must = 'if (beam != null && !beam.released()) {';
       why = '★ 开关判据必须用 released 而不是 isGrabbed —— 后者在淡出期间仍为 true（要等强度衰减到 0 才回收），拿它当判据会让"松手后立刻想再抓"被当成又一次松手' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\MouseHandlerMixin.java';
       must = '@Inject(method = "onPress", at = @At("HEAD"), cancellable = true)';
       why = '★ 照参考在鼠标层吃掉点击（原版从此不进入"使用中"）—— 这是"开关"能成立的前提' },
    @{ file = 'src\main\java\com\mss\polymech\network\PhysgunAction.java';
       must = 'return id >= 0 && id < all.length ? all[id] : STOP_DRAG;';
       why = '★ 越界动作 id 回退成 STOP_DRAG：失败方向必须无害（误当 START_DRAG 会让服务端凭空去抓东西）' },
    @{ file = 'src\main\java\com\mss\polymech\Polymech.java';
       must = 'com.mss.polymech.network.PhysgunActionPacket.TYPE';
       why = '★ 动作包必须真的注册（没注册 = 客户端点了没反应，且是静默的）' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'if (!isHolding(sp)) {';
       why = '★ 服务端校验：动作包是客户端来的，必须再查一遍"手里确实拿着牵引枪"（照参考 validateWorthyness；但我们不踢人，只拒绝）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunInputHandler.java';
       must = 'PhysgunAction.START_DRAG, hit == null ? 0L : hit.bodyId()';
       why = '★ 客户端**打不中也要**发 START_DRAG：客户端镜像没同步上时本地求交必然落空，而服务端射线可能命中 —— 不发就永远抓不上（这是 2026-09-29 那次的教训，别"优化"成打不中就不发）' },

    # ── 操作方式对齐（3/3）：左键 = LOCK（原生固定约束 + 持久化）────────────────────
    @{ file = 'native\polymech-physics\src\lib.rs';
       must = 'constraint_map: HashMap<i64, (i64, RigidBodyHandle, ImpulseJointHandle)>';
       why = '★ 约束表的值必须带**所属体 id**：删体时要靠它找回锚体 —— 锚体泄漏既不报错也不可见（E2 判据抓的就是它）' },
    @{ file = 'native\polymech-physics\src\lib.rs';
       must = 'pub const ABI_VERSION: jint = 10;';
       why = '★ ABI 10 = 固定约束。Java 侧用 MIN_ABI_CONSTRAINT 逐项降级，dll 旧了也不停整个物理层' },
    @{ file = 'native\polymech-physics\src\lib.rs';
       must = 'RigidBodyBuilder::fixed().pose(pose).build()';
       why = '★ 锚体必须建在体**当前位姿**上（关节两个局部帧才可以用单位帧）—— 就是参考 addConstraint 里那三个 logicalPose() 参数' },
    @{ file = 'native\polymech-physics\src\lib.rs';
       must = 'FixedJoint::new()';
       why = '★ 用 FixedJoint（锁全部 6 轴）而不是自己拼 GenericJoint —— 照参考的 FixedConstraint 语义' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysicsNatives.java';
       must = 'public static final int MIN_ABI_CONSTRAINT = 10;';
       why = '★ 逐项降级用的最低 ABI' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysgunLocks.java';
       must = 'locks.put(bodyId, PhysicsBodyTracker.addFixedConstraint(bodyId));';
       why = '★ 钉住必须真的下到原生约束（不是 Java 侧记个"锁了"的标志）—— 本项目禁止"殊途同归"' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'PhysgunLocks.get(sp.server).removeLock(grab.bodyId());';
       why = '★ 照参考 drag(...) 里的 removeLock：**拖着的体必须自动解锁**，否则约束与弹簧力互相顶、物体会抖' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysgunLocks.java';
       must = 'tag.putLongArray(KEY_IDS, ids);';
       why = '★ LOCK 必须持久化（照参考 save/loadLocks）。用 long[] 避开 1.21.1 ListTag 那批 getLong(i) 被移除带来的版本差异' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysgunLocks.java';
       must = 'entry.setValue(PhysicsBodyTracker.addFixedConstraint(bodyId));';
       why = '★ 读档后惰性重建（照参考 applyLockIfNeeded）：约束句柄是原生运行时句柄，重启必然失效' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'case LOCK -> toggleLock(sp);';
       why = '★ 左键动作要真的接上（没接 = 左键点了没反应，且静默）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunInputHandler.java';
       must = 'if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {';
       why = '★ 客户端左键要发 LOCK（照参考 onItemPunched），并且吃掉这次点击' },

    # ── 通用方法：带时间戳的插值（根治"线抽抽、框也抽抽"）──────────────────────
    @{ file = 'src\main\java\com\mss\polymech\util\TimedSamples.java';
       must = 'public synchronized boolean evaluate(double[] out)';
       why = '★ 通用件本体：所有"按拍同步、按帧渲染"的量都该走它，而不是各写一份 lerp(prev,cur,partialTick)。synchronized 是 2026-10-04 加的：镜子环由步进线程 push、渲染线程 evaluate（不加锁会读到写了一半的样本）' },
    @{ file = 'src\main\java\com\mss\polymech\util\TimedSamples.java';
       must = 'if (target >= times[newest]) {';
       why = '★ 越过最新样本必须**夹住**（保持那一拍），绝不外推 —— 外推会在丢包时把物体甩到屏幕外' },
    @{ file = 'src\main\java\com\mss\polymech\util\TimedSamples.java';
       must = 'slerp(values[a], values[b], w, out);';
       why = '★ 四元数必须 slerp 且取最短弧（分量线性插值在四元数上是错的；短弧不做会绕一大圈）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunClientState.java';
       must = 'anchor.toWorld(ANCHOR_POS, ANCHOR_ROT, ANCHOR_OUT);';
       why = '★★ 光束终点必须由**抓点 + 客户端自己的体位姿**每帧现算 —— 不许再发世界坐标再插值：服务端 20Hz 采样体位姿 vs 物体接触抖动 100Hz ⇒ 混叠（"玩家一动线就抽、不动就好"的根因）。框的中心就是同一个 renderEnd 的输出，所以这一处同时修线、框' },
    @{ file = 'src\main\java\com\mss\polymech\physics\BodyLocalAnchor.java';
       must = 'public Vector3d toWorld(Vector3d bodyPos, Quaterniond bodyRot, Vector3d out) {';
       why = '★ 「抓点」这个概念只能有**一处**换算（服务端/客户端预测/渲染共用），否则三边各算一套必然漂移' },
    @{ file = 'src\main\java\com\mss\polymech\network\PhysgunBeamPacket.java';
       must = 'long bodyId, double localX, double localY, double localZ,';
       why = '★★ 光束包只发**抓点本体**（体 id + 体局部坐标）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunClientState.java';
       must = '2.0 * PhysgunBeamPacket.BROADCAST_INTERVAL_TICKS / 20.0';
       why = '★★ 渲染延迟必须**由发包间隔推出**（≥2 拍）。写死 0.10 而采样是每 2 tick(0.1s) ⇒ 延迟刚好 1 拍 ⇒ 网络稍抖就越过最新样本被夹住 ⇒ 又开始抽（这正是天空那边 0.10 不能照抄的原因）' },
    @{ file = 'src\main\java\com\mss\polymech\network\PhysgunBeamPacket.java';
       must = 'public static final int BROADCAST_INTERVAL_TICKS = 1;';
       why = '★ 每 tick 发包（20Hz）：① 采样越稀每拍跳变越大；② 它决定渲染延迟（见上一条契约）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunBeamRenderer.java';
       must = 'OWN_BEAM_METER.sample(E[0], E[1], E[2]);';
       why = '★ 丝滑判据的输入必须是 E（线框共同来源）—— 量它就等于线、框两处一起量' },
    @{ file = 'src\main\java\com\mss\polymech\util\FrameJitterMeter.java';
       must = 'boolean decidable = mean > 1.0e-6;';
       why = '★ 速度均值≈0（物体本来就没动）必须判"不可判"，不能判 PASS —— 那会变成一条永远通过的假判据（本项目栽过"死判据"）' },
    @{ file = 'src\main\java\com\mss\polymech\util\FrameJitterMeter.java';
       must = '(times[filled - 1] - times[0]) >= windowSeconds';
       why = '★★ 窗口必须按**时间**开（秒），不能按帧数：120 帧在 60fps 是 2 秒、560fps 只有 0.21 秒，同一判据在不同机器上量的不是同一段时间（2026-10-04 v2）' },
    @{ file = 'src\main\java\com\mss\polymech\util\FrameJitterMeter.java';
       must = 'dx[filled] = ddx / dt;';
       why = '★★ 统计口径必须是**逐帧速度**（位移 ÷ 帧间隔），不是裸位移：帧间隔不均时裸位移两头误报（GC 卡帧虚高、步进短帧漏判）（2026-10-04 v2）' },
    @{ file = 'native\jni-smoketest\InterpSmoothnessProbe.java';
       must = 'double partial = (frame % framesPerSample) / (double) framesPerSample;';
       why = '★★ 旧写法的 partialTick 必须用**整数帧**算，不能写 (simTime % period)/period：浮点取模在 tick 边界给出 0.0499999 而非 0，会凭空造出倒退跳变 —— 本探针第一版就是这么把自己判 FAIL 的（判据写错，不是代码错）' },
    @{ file = 'native\jni-smoketest\InterpSmoothnessProbe.java';
       must = 'new TimedSamples(3, 64, 2.0 * samplePeriod(), () -> clock[0])';
       why = '★ 探针必须调**真实现**（并用可注入时钟保证相位确定性），不许在这里复刻一份插值公式' },
    @{ file = 'native\jni-smoketest\InterpSmoothnessProbe.java';
       must = 'Math.floor(t * STEP_HZ) / STEP_HZ';
       why = '★★ E 组的直读语义（零阶保持：任何时刻读到的都是最新已完成的那一跳）——它复现实机读数的形态（停帧 ≈ 1−100/fps），是"归因 = 消费方式"的对照组；改掉它 E 组就不再证明任何事' },
    @{ file = 'native\jni-smoketest\InterpSmoothnessProbe.java';
       must = 'new TimedSamples(1, 64, 2.0 * stepPeriod, () -> simTime)';
       why = '★ F 组必须调真实现（与 ClientPhysics.renderTransform 同构：延迟 = 2×步进周期 + 可注入时钟），不许复刻公式' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\ClientPhysics.java';
       must = 'PhysicsStepThread.addPostStep(createdWorld, () -> stepShipSync(createdWorld));';
       why = '★★ 镜子体的运动学推进必须每子步跑（掉了=动的船一顿一顿）。2026-10-04 收口镜子采样架子时差点连坐误删——这一句单独钉住' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\ClientPhysics.java';
       must = 'public static boolean renderTransform(long bodyId, float partialTick, double[] posOut, float[] rotOut)';
       why = '★ 渲染消费体位姿的唯一入口必须存在（refreshEnd / poseOf / 方块渲染三处都走它才同口径）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\ClientPhysicsWorld.java';
       must = 'new TimedSamples(3, 32)';
       why = '★★ 渲染位姿的唯一数据源 = 网络位姿环，延迟取 TimedSamples 默认 0.10s = 2×信息周期(50ms)。⚠️ 别按 push 周期算延迟——镜子环那版就是把"延迟≥2×采样周期"里的周期用错了（信息周期≠push 周期），暴露包到达相位：拖着走时镜子环停帧 7.7% vs 网络环 1.0%（2026-10-04 四组读数）' },
    @{ file = 'src\main\java\com\mss\polymech\util\TimedSamples.java';
       must = 'public static final double DEFAULT_DELAY_SECONDS = 0.10;';
       why = '★ 网络环（20Hz 信息）的默认延迟 = 2×50ms，由它推出——改它等于改所有走默认值的环' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'PhysicsStepThread.addPostStep(world, () -> physicsStep(world));';
       why = '★★ 伺服必须接线到步进线程（写了不接线=没有）。没这句，弹簧退回每 MC tick 一次的 20Hz 脉冲 —— 2026-10-04 四组实机读数里终点抖动的地板 ≈2.3× 就是它' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'double dt = PhysicsStepThread.STEP_SECONDS;';
       why = '★★ 施力节奏 = 100Hz 物理子步（冲量 F×0.01、频率×5 单次÷5，平均力不变）。写回 FORCE_DURATION(0.05) 就是退回每 tick 一发的方波激励' },
    @{ file = 'src\main\java\com\mss\polymech\util\FrameJitterMeter.java';
       must = 'decidable && mean >= STALL_MIN_MEAN';
       why = '★ 停帧只在"明显在动"的窗口计数（门槛 0.5 格/秒）：静止窗口的零位移是天经地义，算成停帧会把判据淹了（实测枪口站立时停帧 91% 全是水分）；静止窗口里的速度尖峰仍由抖动抓' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PlayerColliderRender.java';
       must = 'Mth.lerp(pt, player.xo, player.getX())';
       why = '★★ 玩家框的位姿基准必须走**原版实体插值**（与第三人称渲染的玩家同源）。直读刚体是 10ms 阶梯，与实体的连续插值**相对**错位 ⇒ 框在人身上鬼畜（2026-10-04 用户报；space 0.3.1 的 PhysicalPlayerColliderRender 也是 getPos() 直读 —— 参考没解决这个，别照抄回去）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PlayerColliderRender.java';
       must = 'data.bodyQuatForRender(pt, new org.joml.Quaternionf())';
       why = '★★ 框的姿态必须乘**渲染姿态四元数**（与第三人称模型 SpaceBodyTiltMixin 同源）。不乘就"模型转了框不转" ⇒ 滚转/超人姿态下框看着不在人身上（2026-10-04 二修）；用物理真实姿态会暴露 100Hz 采样率（每 2~3 帧才动一次）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PlayerColliderRender.java';
       must = 'rig.centerOffset()';
       why = '★ 中心偏移必须取 **rig 建体时锁死的值**（姿态切换会同步更新），不是每帧重算 bodyCenterOffset —— 框描述的是刚体，两者在姿态切换瞬间可能不一致' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PlayerColliderRender.java';
       mustNot = 'rig.readPosition(';
       why = '★★ 框不得再直读刚体位置（10ms 阶梯，与实体插值相对错位）；直读只留读不到就跳过的兜底也不行 —— 2026-10-04 一修的教训' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PlayerColliderRender.java';
       mustNot = 'rawSibling[0] - rawMain[0]';
       why = '★★ 兄弟差分不得回归：afterStep 每子步 sibling.pos=main.pos，帧级读到的只是子步中间态，每帧不同 ⇒ 橙框相对主框抽动（那信息只在子步内部存在，帧级渲染显示不出来）' },
    @{ file = 'src\main\java\com\mss\polymech\util\PartialTicks.java';
       must = 'return tracker.getGameTimeDeltaPartialTick(false);';
       why = '★★★ 部分刻的**唯一实现**（§7.12）：必须是 deltaTickResidual（getGameTimeDeltaPartialTick），不是 getGameTimeDeltaTicks —— 后者是"本帧走了多少刻"（≈0.04），当插值系数用等于 pt≈0' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PlayerColliderRender.java';
       must = 'PartialTicks.of(event)';
       why = '★★★ 框的 pt 必须走 PartialTicks（§7.12 根因：pt 取错 ⇒ lerp(pt,xo,x)≈xo ⇒ 框停在上一刻、只在 tick 边界跳 ⇒ 移动时抽搐 + 第二/三人称比人慢一整刻）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PlayerColliderRender.java';
       must = 'Stage.AFTER_ENTITIES';
       why = '★★★ 框必须在 AFTER_ENTITIES 阶段画（§7.13 根因）：AFTER_SOLID_BLOCKS 由 renderSectionLayer:972 发出，而相机矩阵要到 renderLevel:1002 才压进 modelView 栈 ⇒ 在那个阶段 flush 的线框吃的是陈旧 modelView，框画在屏幕错处（地形/实体各自重设矩阵所以看不出异常）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PlayerColliderRender.java';
       mustNot = 'AFTER_SOLID_BLOCKS';
       why = '★★★ 不得回到 AFTER_SOLID_BLOCKS（同上：该阶段 flush 时着色器的 modelView 不是相机矩阵）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PlayerColliderRender.java';
       must = 'event.getModelViewMatrix()';
       why = '★★★ flush 前必须显式把 modelView 设成 event.getModelViewMatrix()（= frustumMatrix，与实体同一帧）——"相机旋转在 modelView 栈里、不在 poseStack 里"，只靠阶段"碰巧"有矩阵是不可靠的' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PlayerColliderRender.java';
       mustNot = '.getGameTimeDeltaTicks()';
       why = '★★★ 不得再把"本帧步长"当部分刻（§7.12 根因；取法统一到 PartialTicks）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunBeamRenderer.java';
       mustNot = '.getGameTimeDeltaTicks()';
       why = '★★★ 同上：线/枪口的 pt 也曾取错 —— 枪口读数"停帧 1816/5273"就是 pt≈0 的台阶（§7.12）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysicsBodyRenderer.java';
       mustNot = '.getGameTimeDeltaTicks()';
       why = '★★★ 同上：物理体方块的 pt 也曾取错（§7.12）' },
    @{ file = 'src\main\java\com\mss\polymech\client\space\SpaceRenderer.java';
       mustNot = '.getGameTimeDeltaTicks()';
       why = '★★★ 同上：天体渲染的 pt 也曾取错（§7.12）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunBeamRenderer.java';
       must = 'b.renderStart(partialTick, S);';
       why = '★★ 光束起点 = renderStart（服务端起点环，0.1s）——与终点同一时间线，移动时两端同步线才不被拉扯（公式也与服务端广播/客户端预测统一成 眼+视线×MUZZLE_FORWARD）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunBeamRenderer.java';
       mustNot = 'muzzle(player, partialTick, S);';
       why = '★★ 渲染端对起点的二次覆盖（手部偏移）不得回归：它把起点变成零延迟逐帧量，与 0.1s 延迟的终点不同时间线 ⇒ 移动时线被拉扯（实测行走窗口起点尖峰 240 格/秒）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunBeamRenderer.java';
       must = 'double ahead = holdDistance - PhysgunItem.MUZZLE_FORWARD;';
       why = '★★ 准星落点标记的基准 = 光束起点 S（服务端起点环 0.1s），沿视线补到 holdDistance —— 与线同一时间线。逐帧眼点（getEyePosition）会把玩家物理体的 tick 级抖动打进标记（"黄点一直抽抽"，2026-10-04）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunBeamRenderer.java';
       mustNot = 'player.getEyePosition(';
       why = '★★ 落点标记不得回到逐帧眼点基准（同上一条；整文件不许再出现眼点直读）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\ClientPhysics.java';
       must = 'new Quaterniond(from).slerp(target, p)';
       why = '★★ 镜像体姿态必须随 ramp 一起 slerp（space 0.3.1 起的做法；0.1.3 是瞬时 set，旧注释"setRotation 不插值"已过时）。dot<0 先翻同半球 = 最短弧，否则绕远路转一大圈' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\ClientPhysics.java';
       must = 'existing.syncFromRot = new Quaterniond(curRot[0]';
       why = '★ 姿态也要记两拍（syncFromRot=当前实际姿态、非有限回退包值 = 0.3.1 的 takeCurrent 语义）——少了它 slerp 没有起点' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunClientState.java';
       must = 'ClientPhysics.renderTransform(anchor.bodyId(), partialTick, LIVE_POS, LIVE_ROT)';
       why = '★★ 光束终点的位姿必须走 renderTransform（被两拍包住的时间戳插值）。直读 liveTransform 是 10ms 一跳的阶梯：560fps 下每 5.6 帧才跨一跳 ⇒ 2026-10-03 读数 停帧=101/120、抖动=8.12 ⇒ FAIL' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysicsBodyRenderer.java';
       must = 'ClientPhysics.renderTransform(body.id(), partialTick, renderPos, ROT)';
       why = '★★ 方块渲染必须与光束终点/框**同一口径**：三者各读各的位姿来源就会互相错开（线离开体表面）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunBeamRenderer.java';
       must = 'ClientPhysics.renderTransform(body.id(), partialTick, pos, rot)';
       why = '★ 高亮框（poseOf）同口径；直读阶梯的框会随步进"跳格"（判据 S7）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\ClientPhysicsWorld.java';
       must = 'netPosSamples.push(nx, ny, nz);';
       why = '★ 服务端同步下来的权威位姿同样是拍同步量 —— 抓取框/悬停框用的镜子体位姿就在这里' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\ClientPhysicsWorld.java';
       must = 'if (netRotSamples.evaluateQuat(renderRotScratch)) {';
       why = '★ 姿态必须 slerp（不能分量线性插值）：分量插值在四元数上是错的，转过符号边界时会绕一大圈' },
    @{ file = 'src\main\java\com\mss\polymech\mps\kelvin\physical\celestial_body\CelestialBody.java';
       must = 'this.posSamples.push(this.pos.x, this.pos.y, this.pos.z);';
       why = '★ 天体也必须走**同一个**通用件（把机制抽出来的意义就在这）——不许再有一份私有实现' },
    @{ file = 'src\main\java\com\mss\polymech\mps\kelvin\physical\celestial_body\CelestialBody.java';
       must = 'if (this.rotSamples.evaluateQuat(this.smoothRot)) {';
       why = '★ 天体姿态也要走通用件的时间戳 slerp（原来是自己那份 histR + locate）' },
    @{ file = 'src\main\java\com\mss\polymech\mps\kelvin\physical\celestial_body\CelestialBody.java';
       must = 'return this.posSamples.sampleIntervalSeconds();';
       why = '★ 诊断读数（sampleIntervalSeconds/sampleHistorySeconds，SpaceRenderer 用它打"太空顺滑"）也必须委托，否则收口只收了一半' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceCameraMixin.java';
       must = '@Inject(method = "setup", at = @At("TAIL"))';
       why = '★★ 注入点必须在 setup 的 **TAIL**：Camera.setup 里 setRotation 被调三次（offset 79/170/269），最后一次是 2 参版 = setRotation(yRot, xRot, 0f) ⇒ 会把我们的 roll 硬置 0。旧注入点（ordinal=0 之后）正好被它覆盖 ⇒ 相机不滚、输入基底滚 ⇒ **滚到 ±180 时左右键反向**（用户 2026-10-03 报）' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceCameraMixin.java';
       must = 'this.setRotation(yaw + 180.0F, -pitch, (float) -roll);';
       why = '★ 正面视角（thirdPersonReverse）要照抄原版那条分支自己翻一次，且 roll **取反** —— 这个符号由 RollInputBasisProbe 的 B 组程序判定（可区分的 45 个姿态里只有 -roll 成立，+roll 0/45），不是推出来的' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceCameraMixin.java';
       mustNot = '!this.isDetached() ||';
       why = '★★★ 太空相机锚点不得再排除第一人称（§7.14 根因）：原版第一人称锚点是 lerp + 眼高 = **世界竖直**，而太空里身体是 6DOF 的（实测 俯仰 −88°/滚转 −180° 时"头上的那一点"不在世界 +Y）⇒ 眼睛被钉在身体另一侧，玩家碰撞框看着"位置异常"、随便转视角更明显（用户 2026-10-05 报）。第一/二/三人称一律走身体坐标系锚点' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysicsBodyTracker.java';
       must = '[幽灵：filter=0，与谁都碰不上]';
       why = '★★★ 幽灵物理体的判据必须出现在 describe() 里（2026-10-05 用户报：能看见、没碰撞、牵引枪抓不到、也挖不掉）。两条机制：① 碰撞组 filter=0（collisiongroups 命令文档自己写着"1 0 = 幽灵：filter 空集，跟谁都碰不上"）；② 零四元数/NaN —— BodyRaycast.nearest 直接跳过 ⇒ 不可牵引/不可挖，而渲染侧 renderRotation 的兜底照样画。之前 describe 恰恰不打组，所以查不出来' },
    @{ file = 'src\main\java\com\mss\polymech\mps\physical\physical_body\PhysicalBody.java';
       must = 'int move = MAX_WIDTH / 2;';
       why = '★★★ 子块偏移必须取 MAX_WIDTH/2（局部空间以体心为原点）：0.1.3 里 CHUNK_WIDTH==64==MAX_WIDTH/2，写死 64 看不出问题；粒度改成 16 之后字面量只是"碰巧"还对，下次动 MAX_WIDTH 或 CHUNK_WIDTH 就静默错位（用户 2026-10-05 报"瞄体打不中、瞄旁边空气才能挖"，症状形状正是求交帧与渲染帧错开）' },
    @{ file = 'src\main\java\com\mss\polymech\mps\physical\physical_body\PhysicalBody.java';
       mustNot = 'int move = 64;';
       why = '★★★ 同上：不得回到写死的 64（与 voxelBounds 注释里点名的"最容易改错的一处"同源）' },

    # ── 创造飞行的竖直（2026-10-03 第三条路；docs/space-input-basis.md §三）─────────────
    # 原版把飞行的阻尼做在**世界轴**上（x,z×0.91 / y×0.6），而输入意图是**视线轴**的；
    # 两基不同 ⇒ 稳态速度方向必然偏（水平姿态下两基重合，所以平时看不出来）。
    # 修法照 space 0.3.0 的 MixinPlayerFlight：把 Player.travel 的飞行分支整条换成视线基底版本。
    # 这几条钉的是"少了任何一条就静默退化"的环节：
    @{ file = 'src\main\resources\poly_mech.mixins.json';
       must = '"SpaceFlightMixin"';
       why = '★ mixin 必须注册进 client 段，否则整条飞行竖直链静默失效（不报错、就是又往屏幕下飞）' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceFlightMixin.java';
       must = '@Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)';
       why = '★★ 注入点必须是 Player.travel 的 **HEAD 取消**：原版飞行分支已经把 y 覆盖成 d2·0.6（d2 读于 super.travel 之前），TAIL 上再改就只是在"已经被世界轴阻尼过"的结果上打补丁' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceFlightMixin.java';
       must = 'data.viewFrame(new Quaternionf())';
       why = '★ 基底必须取自 SpacePlayerData.viewFrame（与走路那条 moveRelative、以及相机自检同源）；自造一份基底就是"同一概念两份实现"' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceFlightMixin.java';
       must = 'SpaceFlight.cancelVanillaVertical(ascend, baseFlySpeed)';
       why = '★ 必须撤掉 LocalPlayer.aiStep 已经加进来的**世界 +Y** 冲量：不撤就是"世界竖直 + 屏幕竖直"两份叠加（固定点 = 0.6·冲量/(1−0.6) = 1.5 倍冲量；⚠️ 不是 2.5 倍 —— 那是 v ← D·v+冲量 的解）' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceFlightMixin.java';
       must = 'SpaceFlight.damp(frame, moved.x, moved.y, moved.z, new Vector3d())';
       why = '★★ 阻尼必须**按视线基底逐轴**做 —— 这一句就是"往屏幕下飞"的修复本体；换成世界轴乘法就退回原版结构' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceFlightMixin.java';
       must = 'self.stopFallFlying();';
       why = '★ 取消 travel 会跳过原版飞行分支的 setSharedFlag(7, false)（清鞘翅滑翔标记）。不能省：LivingEntity.updateFallFlying() 只在 !level.isClientSide 时才写 flag7，所以**客户端**的 flag7 平时就靠那一行清。setSharedFlag 是 Entity 的 protected、@Shadow 又只认目标类自身声明的成员 ⇒ 走 public 的 stopFallFlying()（净效果同 flag7=false）' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceFlightMixin.java';
       must = 'self.calculateEntityAnimation(false);';
       why = '★ 取消 travel 会跳过 LivingEntity.travel 末尾的这一步；不补的话飞行时手脚不再摆动（原版飞行是摆的）' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceFlightMixin.java';
       must = 'data.markMovementBasis(true);';
       why = '★ "这一 tick 走了哪条基底"必须记进状态并出现在 [姿态自检] 里（飞行 vs 走路是一个分叉，分叉必须被报出来）' },
    @{ file = 'src\main\java\com\mss\polymech\space\SpaceFlight.java';
       must = 'public static final double HORIZONTAL_DAMP = 0.91;';
       why = '★ 水平阻尼 = 原版 f3（照 space 0.3.0 的字面值）；改它等于改手感' },
    @{ file = 'src\main\java\com\mss\polymech\space\SpaceFlight.java';
       must = 'public static final double UP_DAMP = 0.6;';
       why = '★ 竖直阻尼 = 原版 Player.travel 飞行分支的 0.6（稳态 = 0.6·冲量/(1−0.6) = 1.5 倍）' },
    @{ file = 'src\main\java\com\mss\polymech\space\SpaceFlight.java';
       must = 'left.x * alongLeft + up.x * alongUp + view.x * alongForward,';
       why = '★★ 阻尼必须"分解到视线基底三轴 → 各自乘阻尼 → 再合成回来"。改成对 vx/vy/vz 直接乘常数就是世界轴阻尼 = 原版结构 = 用户报的"往屏幕下飞"' },
    # ── 瞄准射线的**起点**：客户端=真实相机，服务端=同一份锚点算式（2026-10-06）──────────
    # 用户报"右键放置有时不放我瞄准的那个面"。两条独立缺陷：
    #   ① 交互包里带的是**世界面**（体被牵引枪转过之后，方块落到体的另一侧）；
    #   ② 射线起点用 player.getEyePosition()，而太空第一人称相机被 SpaceCameraMixin 挪到
    #      "身体坐标系里的头" ⇒ 实测眼-相机距 1.313~1.430 格（主世界恒 0.000）⇒ 整体平行错开一格多。
    # 这几条是正向契约：谁把起点改回眼睛、或把面再转成世界面，这里就 MISS。
    @{ file = 'src\main\java\com\mss\polymech\client\physics\ClientViewOrigin.java';
       must = 'gameRenderer.getMainCamera().getPosition()';
       why = '★★ 客户端瞄准起点的**唯一实现** = 相机位置（space 的 PhysicalBodyInteractionClient 逐字如此）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysicsBodyInteractionClient.java';
       must = 'ClientViewOrigin.of(player)';
       why = '★★ 挖掘/放置射线必须走相机起点（用 getEyePosition 在太空里与准星平行错开 1.3~1.4 格）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysicsBodyInteractionClient.java';
       must = 'Direction localFace = local.face();';
       why = '★★★ 交互包必须带**体局部**面：服务端 useOrPlace 在投影（=体局部）空间里 pos.relative(face)；带世界面 ⇒ 体转过之后方块落到体的另一侧（用户 2026-10-06 报"右键放置不放我瞄准的那个面"）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysicsBodyInteractionClient.java';
       must = 'hit.localFaceX, hit.localFaceY, hit.localFaceZ';
       why = '★★ 发包时用的就是局部面那三个分量（字段名 localFace* 本身就是这条契约的说明书）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunTarget.java';
       must = 'ClientViewOrigin.of(mc.player)';
       why = '★★ 牵引枪拾取起点同源（"准星指着哪个体/多远"必须与画面同一条线）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunInputHandler.java';
       must = 'ClientViewOrigin.of(player)';
       why = '★★ 枪口/预测抓点起点同源（光束起点必须从画面里那条视线出发）' },
    @{ file = 'src\main\java\com\mss\polymech\client\WireCutterOverlay.java';
       must = 'ClientViewOrigin.of(mc.player)';
       why = '★ 剪线钳瞄准起点同源（同一条准星）' },
    @{ file = 'src\main\java\com\mss\polymech\client\ClampMeterOverlay.java';
       must = 'ClientViewOrigin.of(mc.player)';
       why = '★ 夹表瞄准起点同源（同一条准星）' },
    @{ file = 'src\main\java\com\mss\polymech\space\SpacePlayerData.java';
       must = 'public static Vector3d cameraAnchor(';
       why = '★★★ 相机锚点算式的**唯一实现**（客户端 mixin 与服务端放置/牵引枪共用）。两端各写一份 = 同一个概念两条路，本项目硬规矩' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceCameraMixin.java';
       must = 'SpacePlayerData.cameraAnchor(e, pt, eye, new Vector3d())';
       why = '★★ 相机自己必须走那一份算式（否则"服务端复算的锚点"与真实相机又会分叉，SpaceViewBasisProbe 的 锚点-相机距 会 FAIL）' },
    @{ file = 'src\main\java\com\mss\polymech\space\SpaceAimOrigin.java';
       must = 'SpacePlayerData.cameraAnchor(entity, partialTick, entity.getEyeHeight(), new Vector3d())';
       why = '★★ 服务端起点 = 同一份锚点算式（读不到相机，只能拿同步的 facing/left 重算）' },
    @{ file = 'src\main\java\com\mss\polymech\physics\SpaceBlockPlacement.java';
       must = 'SpaceAimOrigin.of(player)';
       why = '★★★ 太空放置射线起点必须是相机锚点（原先 getEyePosition ⇒ 目标格整体错开一格多 = 用户报的"放到别处/不放我瞄的那个面"）' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       must = 'SpaceAimOrigin.of(sp)';
       why = '★★ 牵引枪服务端射线/弹簧目标点/光束起点三处同源（原先 getEyePosition，太空里抓错点）' },
    @{ file = 'src\main\java\com\mss\polymech\client\space\SpaceViewBasisProbe.java';
       must = '锚点-相机距';
       why = '★★ 运行期判据：拿**真实相机**验"服务端那条锚点算式"（能 FAIL；算式退回眼睛会立刻变成 1.31~1.43 格）' },
    @{ file = 'native\jni-smoketest\BodyVoxelRaycastProbe.java';
       must = 'groupG();';
       why = '★★ G 组（交互包的面帧：局部面 vs 世界面）必须真的被执行，删了这行就等于没判' },
    @{ file = 'src\main\java\com\mss\polymech\physics\SpaceBlockPlacement.java';
       must = 'SpaceBuildRules.targetKind(';
       why = '★★★ 创建/扩增分流必须真的被咨询（用户 2026-10-06 拍板："你应该区分一下创建物理体和对物理体扩增的区别，现在这个是影响到了扩大物理体的手感"）。删掉这一行就退回"一条规则管两件事"——上一版那样会把扩增手感切掉、创建也一起砍掉' },
    @{ file = 'src\main\java\com\mss\polymech\physics\SpaceBlockPlacement.java';
       must = 'PhysicsBodyTracker.hasBodyWithin(level, eye, REACH)';
       why = '★★★ 分流的另一半："REACH 内有没有体"。少了它就只能靠"打空"判断 ⇒ 又变回不分场合的兜底（"点方块前面的空气进行延申搭路"）' },
    @{ file = 'src\main\java\com\mss\polymech\physics\SpaceBlockPlacement.java';
       must = 'REFUSE_MISSED_BODY';
       why = '★★★ "打空 + 周围有体 ⇒ 拒绝"这条必须存在 —— 它才是挡住"点空气搭路"的那一条；创建只在"周围完全没有体"时才允许' },
    @{ file = 'src\main\java\com\mss\polymech\physics\SpaceBlockPlacement.java';
       must = '分流=';
       why = '★★ 运行期判据必须打出"这次是扩增还是创建"（配合 命中来源/目标格）—— 实机一眼分辨，不用猜' },
    @{ file = 'native\jni-smoketest\SpaceBuildRulesProbe.java';
       must = 'placementCase(';
       why = '★★★ 分流真值表必须真的被执行（写了不调用等于没判）：离线那条能 FAIL 的判据在这里' }
)

# ── 「不得存在」型契约 ───────────────────────────────────────────────────────
# 有些改动是**删除**（比如删掉一个无界缓存），"缺少某个片段"才是对的。
# 这类用 mustNot：命中即失败。
$absentContracts = @(
    @{ file = 'src\main\java\com\mss\polymech\mps\physical\manger\PhysicalChunkManager.java';
       mustNot = 'old_physicalChunks = new ConcurrentHashMap<>()';
       why = '★ old 缓存表只增不减 ⇒ 真实的无界内存泄漏；space 0.3.0 已删除，不得回归' },
    @{ file = 'src\main\java\com\mss\polymech\physics\PhysicsBodyTracker.java';
       mustNot = 'tracked.origin.getX() + e.dx()';
       why = '★ 轴对齐映射（origin + 局部，不含刚体旋转）不得回归：体转过/挪过之后它指的世界格是错的（探针实测同场景偏差 11 格，新映射 ≤1 格）' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       mustNot = 'public void onUseTick(';
       why = '★ 原版"使用中"钩子不得回归：右键已改成开关（照参考），原版根本不进入使用状态；若这个钩子回来，施力会**双份**（serverTick + onUseTick 各一次）' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       mustNot = 'public void releaseUsing(';
       why = '★ 同上一一：松手现在由 STOP_DRAG 动作驱动，不再靠原版松开使用' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       mustNot = 'public int getUseDuration(';
       why = '★ 不再需要"使用时长"：原版不会进入使用状态（留着会让后来者以为右键仍是长按）' },
    @{ file = 'src\main\java\com\mss\polymech\mps\kelvin\physical\celestial_body\CelestialBody.java';
       mustNot = 'private double locate(double target';
       why = '★ 天体那份**私有的**时间戳插值实现不得回归（已改为委托 TimedSamples）——两份实现必然漂移，本项目硬规矩' },
    @{ file = 'src\main\java\com\mss\polymech\mps\kelvin\physical\celestial_body\CelestialBody.java';
       mustNot = 'private final Vector3d[] histP';
       why = '★ 同上：私有快照环（histT/histP/histR）不得回归' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceCameraMixin.java';
       mustNot = 'target = "Lnet/minecraft/client/Camera;setRotation(FFF)V"';
       why = '★★ 那个注入点（ordinal=0 的 INVOKE）不得回归：它落在 Camera.setup 的**第一次** setRotation 之后，会被后面两次覆盖，其中最后那次把 roll 置 0 —— 相机不滚而输入基底滚，滚到 ±180 就是左右反' },
    @{ file = 'src\main\java\com\mss\polymech\network\PhysgunBeamPacket.java';
       mustNot = 'double endX, double endY, double endZ,';
       why = '★ 世界端点不得回归：那是**派生量**，发它必然把物体 100Hz 的接触抖动按 20Hz 采样下来（混叠 = 玩家一动就抽）' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceInputRotationMixin.java';
       mustNot = 'if (((Player) self).getAbilities().flying || ((Player) self).isSpectator()) return;';
       why = '★★ 这条"创造/旁观飞行走原版"的提前返回不得回归：它让移动走回原版 yaw-only 基底（**不含 roll**），而相机那侧照滚 ⇒ 滚到 ±180 时画面倒了、A/D 却按没倒的基座走 ⇒ 按 A 往屏幕右（用户 2026-10-03 报"滚转后左右键反向"）。当时离线探针与运行时自检**双双 PASS**，因为两条判据都没问"移动这一 tick 到底走了哪条路"' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceFlightMixin.java';
       mustNot = 'travelVector.y';
       why = '★★ 1.21.1 里 LivingEntity.yya **只被读、从没被写**（永远是 0），所以 travelVector.y 恒为 0 —— docs/space-input-basis.md §3.4 把它列为"竖直输入"的候选是错的（另一个候选 yya 同理）。竖直真正的来源是 LocalPlayer.aiStep 里沿世界 +Y 的 delta.y += j·flySpeed·3。谁再把 travelVector.y 当竖直输入，就是回到了那个错误前提' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceInputRotationMixin.java';
       mustNot = 'shiftKeyDown';
       why = '★★ 不得在这里补"Space/Shift → 上下"（把竖直输入展开成视线基底的 up）。两个理由：① 早先就在这里补过 —— 零重力 + 无阻尼下那点输入会**无限累积**，表现是"在失重里一直往上飞"；② 2026-10-03 起飞行的竖直已由 SpaceFlightMixin 走视线基底给全（水平 + 竖直一起），在这里再补一份就是"两份来源叠加"。本类只该处理走路/游泳：飞行在 Player.travel 的 HEAD 就被取消了，moveRelative 根本不执行。判据用 shiftKeyDown：这条地雷必然要读原版输入字段（jumping / shiftKeyDown），而本类只需旋转基底、没有别的理由读它们' },

    # ── 渲染位姿消费必须走 renderTransform，不得直读刚体（2026-10-04）──────────────
    # 直读（liveTransform）拿到的是运动学镜像 10ms 一跳的**阶梯**：560fps 下每 5.6 帧才跨一跳
    # ⇒ 每帧位移 "0,0,0,0,0,整跳" —— 2026-10-03 实机读数 停帧=101/120、抖动=8.12 的根因。
    # 玩法侧（拾取 PhysgunTarget / 旋转捕获 PhysgunInputHandler）读实时状态是另一回事，不禁。
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunClientState.java';
       mustNot = 'ClientPhysics.liveTransform(';
       why = '★★ 光束终点不得直读刚体阶梯（那正是 101/120 停帧的来源）；渲染消费只能走 renderTransform' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysicsBodyRenderer.java';
       mustNot = 'ClientPhysics.liveTransform(';
       why = '★★ 方块渲染不得直读阶梯（与光束终点同口径才不会互相错开）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunBeamRenderer.java';
       mustNot = 'ClientPhysics.liveTransform(';
       why = '★★ 高亮框不得直读阶梯（框跳格 = 判据 S7 失败）' },
    # ── 起点与面帧：不得退回"眼睛 + 世界面"（2026-10-06）──────────────────────────
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysicsBodyInteractionClient.java';
       mustNot = 'TMP.set(face.getStepX(), face.getStepY(), face.getStepZ());';
       why = '★★★ 不得再把命中面**旋转成世界面**再发：服务端在投影空间 pos.relative(face) ⇒ 方块落到体的另一侧（用户报"右键放置不放我瞄准的那个面"）。这条就是那次修改前的原文' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysicsBodyInteractionClient.java';
       mustNot = 'hit.normalX';
       why = '★★ 旧字段名 normalX/Y/Z（世界面语义）不得回归 —— 名字本身就是坐标系的说明书' },
    @{ file = 'src\main\java\com\mss\polymech\physics\SpaceBlockPlacement.java';
       mustNot = 'Vec3 eye = player.getEyePosition();';
       why = '★★★ 放置射线起点不得回到眼睛：太空第一人称相机离眼睛实测 1.313~1.430 格（主世界 0.000）⇒ 目标格平行错开一格多' },
    @{ file = 'src\main\java\com\mss\polymech\item\PhysgunItem.java';
       mustNot = 'sp.getEyePosition()';
       why = '★★ 牵引枪服务端三处起点不得回到眼睛（同上；太空里抓错点、光束整体偏一格多）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunTarget.java';
       mustNot = 'mc.player.getEyePosition(';
       why = '★★ 牵引枪拾取起点不得回到眼睛（与准星平行错开一格多）' },
    @{ file = 'src\main\java\com\mss\polymech\mixin\SpaceCameraMixin.java';
       mustNot = 'data.eyeOffset(eye - half';
       why = '★★ 相机锚点算式不得在 mixin 里再内联一份（必须调 SpacePlayerData.cameraAnchor）—— 两端各写一份就会分叉，这正是本次缺陷的成因' },
    # ── 创建 / 扩增不得再被合并成一条规则（2026-10-06 用户两轮纠正）──────────────────
    # 第一轮："很像基岩版的放置逻辑…点方块前面的空气进行延申搭路"（兜底不分场合：打空就放到 3 格外）。
    # 第二轮（拍板）："你应该区分一下创建物理体和对物理体扩增的区别，现在这个是影响到了扩大物理体的手感"
    #   —— 我上一版把"打空"一律判成不放，把创建一起砍了。所以下面这条"无条件拒绝"的文案不得回归。
    @{ file = 'src\main\java\com\mss\polymech\physics\SpaceBlockPlacement.java';
       mustNot = '命中来源=无 ⇒ 不放置（无兜底）';
       why = '★★★ 不得退回"打空一律不放"（那把创建一起砍了 —— 用户 2026-10-06 第二轮纠正）；也不得退回"打空一律兜底"（第一轮）。正确形态只有三条：命中⇒扩增 / 打空+周围有体⇒拒绝 / 打空+周围无体⇒创建' }
)
Write-Host ""
Write-Host '--- 着色器/接口契约 ---' -ForegroundColor Cyan
foreach ($ct in $contracts) {
    $p = Join-Path $root $ct.file
    if (-not (Test-Path $p)) {
        Write-Host ("[MISS] {0,-44} 文件不存在 —— {1}" -f (Split-Path $ct.file -Leaf), $ct.why) -ForegroundColor Red
        $miss++
        continue
    }
    $text = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
    if ($text.Contains($ct.must)) {
        Write-Host ("[ OK ] {0,-44} {1}" -f (Split-Path $ct.file -Leaf), $ct.why) -ForegroundColor Green
    } else {
        Write-Host ("[MISS] {0,-44} 缺契约片段 —— {1}" -f (Split-Path $ct.file -Leaf), $ct.why) -ForegroundColor Red
        $miss++
    }
}

Write-Host ""
Write-Host '--- 「不得存在」契约（删除类改动，命中即失败）---' -ForegroundColor Cyan
foreach ($ac in $absentContracts) {
    $p = Join-Path $root $ac.file
    if (-not (Test-Path $p)) {
        Write-Host ("[MISS] {0,-44} 文件不存在 —— {1}" -f (Split-Path $ac.file -Leaf), $ac.why) -ForegroundColor Red
        $miss++
        continue
    }
    $text = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
    if ($text.Contains($ac.mustNot)) {
        Write-Host ("[MISS] {0,-44} 不该出现却出现了 —— {1}" -f (Split-Path $ac.file -Leaf), $ac.why) -ForegroundColor Red
        $miss++
    } else {
        Write-Host ("[ OK ] {0,-44} {1}" -f (Split-Path $ac.file -Leaf), $ac.why) -ForegroundColor Green
    }
}

# ── 顺序契约 ─────────────────────────────────────────────────────────────────
# 线格式的**字段顺序**在 .class 常量池里看不见（顺序不是字符串），所以只能回到源码
# 按 IndexOf 位置比较。跨版本互发会整段错位，正是这里要钉住的东西。
# 用 IndexOf 链式递增而不是"多行字面子串"：后者会被 CRLF/LF 差异误判。
$orderContracts = @(
    @{ file = 'src\main\java\com\mss\polymech\mps\kelvin\physical\celestial_body\variant\Planet.java';
       chain = @('buffer.writeDouble(this.cloudHeight);',
                 'buffer.writeDouble(this.cloudBottom);',
                 'buffer.writeDouble(this.cloudTop);',
                 'buffer.writeDouble(this.ringInsideHeight);');
       why = 'Planet.encode 云字段顺序须与 space 0.3.0 一致（cloudHeight→cloudBottom→cloudTop→ringInsideHeight）' },
    @{ file = 'src\main\java\com\mss\polymech\mps\kelvin\physical\celestial_body\variant\Planet.java';
       chain = @('double cloudHeight = buffer.readDouble();',
                 'double cloudBottom = buffer.readDouble();',
                 'double cloudTop = buffer.readDouble();',
                 'double ringInsideHeight = buffer.readDouble();');
       why = 'Planet.decode 必须与 encode 同序（顺序错 ⇒ 解出来的环/云数据全乱）' },
    @{ file = 'src\main\java\com\mss\polymech\mps\kelvin\physical\celestial_body\variant\Planet.java';
       chain = @('planet.setCloudHeight(cloudHeight);',
                 'planet.setCloudBottom(cloudBottom);',
                 'planet.setCloudTop(cloudTop);',
                 'planet.setRingInsideHeight(ringInsideHeight);');
       why = 'decode 里四个 setter 都要在（读了不 set ⇒ 字段恒为 0，静默错）' },
    @{ file = 'src\main\java\com\mss\polymech\mps\space\util\manger\SpaceModDataPackManger.java';
       chain = @('planet.setCloudHeight(cloud_data.get("height").getAsDouble());',
                 'cloud_data.has("bottom")',
                 'cloud_data.has("top")',
                 'planet.setCloudBottom(cloudBottom);',
                 'planet.setCloudTop(cloudTop);');
       why = '数据包 bottom/top 回退到 getCloudHeight() ⇒ setCloudHeight 必须在前（顺序反了会读到 0）' }
)
Write-Host ""
Write-Host '--- 线格式顺序契约（IndexOf 链式递增）---' -ForegroundColor Cyan
foreach ($oc in $orderContracts) {
    $p = Join-Path $root $oc.file
    if (-not (Test-Path $p)) {
        Write-Host ("[MISS] {0,-44} 文件不存在 —— {1}" -f (Split-Path $oc.file -Leaf), $oc.why) -ForegroundColor Red
        $miss++
        continue
    }
    $text = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
    $pos = -1
    $ok = $true
    $bad = ''
    foreach ($m in $oc.chain) {
        $i = $text.IndexOf($m)
        if ($i -lt 0) { $ok = $false; $bad = "缺片段: $m"; break }
        if ($i -le $pos) { $ok = $false; $bad = "顺序错(位置 $i <= $pos): $m"; break }
        $pos = $i
    }
    if ($ok) {
        Write-Host ("[ OK ] {0,-44} {1}" -f (Split-Path $oc.file -Leaf), $oc.why) -ForegroundColor Green
    } else {
        Write-Host ("[MISS] {0,-44} {1} —— {2}" -f (Split-Path $oc.file -Leaf), $oc.why, $bad) -ForegroundColor Red
        $miss++
    }
}

Write-Host ""
if ($miss -eq 0) {
    Write-Host '产物自检全部命中（源码改动确实进了 build\classes，引用的贴图也在）' -ForegroundColor Green
    exit 0
}
Write-Host ("产物自检有 " + $miss + " 处缺失 —— 先 gradlew compileJava，再重跑本脚本" ) -ForegroundColor Red
exit 1
