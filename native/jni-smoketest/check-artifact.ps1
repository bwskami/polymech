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
       marks = @('已抓住', 'serverTick', 'tickGrab', 'applyAction', 'startDrag', 'stopDrag',
                 'PhysgunActionPacket', 'PhysgunSpring', 'PhysgunBeamPacket', 'BodyRaycast',
                 'PhysicsBodyTracker', 'nearestBodyInfo');
       why = '牵引枪（抓取 + 弹簧拖拽 + 光束广播 + 音效）—— 2026-09-29 起在 PhysicsBodyTracker 上求交；2026-10-01 起右键是开关（serverTick 驱动施力，不再是 onUseTick）。⚠️ 别把 START_DRAG/STOP_DRAG 写进 marks：switch 里的枚举常量名会被 javac 放进合成的 $SwitchMap 类，本类里没有这两个字符串（写进去必假 MISS）' },
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
       marks = @('grabbedBodyId', 'holdDistance', 'applyServer', 'setPredictedEndpoints');
       why = '按玩家的光束注册表（外层：预测 / 权威 / 松手 三个入口）' },
    @{ cls = 'com\mss\polymech\client\physics\PhysgunClientState$Beam.class';
       marks = @('serverDriven', 'applyServerEndpoints', 'nodeCount', 'tick');
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
       marks = @('MAX_BODIES_PER_DIMENSION', 'PLACE_DISTANCE', 'rotateFace');
       why = '放置判据 + 命中面世界化（离线判据调的就是这一类）' },
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
       why = '★ 服务端真的施加了角伺服的力矩冲量（在 tickGrab 里、每 tick 一次，与线性那半同节奏）' },
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
       must = 'public boolean evaluate(double[] out)';
       why = '★ 通用件本体：所有"按拍同步、按帧渲染"的量都该走它，而不是各写一份 lerp(prev,cur,partialTick)' },
    @{ file = 'src\main\java\com\mss\polymech\util\TimedSamples.java';
       must = 'if (target >= times[newest]) {';
       why = '★ 越过最新样本必须**夹住**（保持那一拍），绝不外推 —— 外推会在丢包时把物体甩到屏幕外' },
    @{ file = 'src\main\java\com\mss\polymech\util\TimedSamples.java';
       must = 'slerp(values[a], values[b], w, out);';
       why = '★ 四元数必须 slerp 且取最短弧（分量线性插值在四元数上是错的；短弧不做会绕一大圈）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\PhysgunClientState.java';
       must = 'if (!endSamples.evaluate(out)) {';
       why = '★ 光束端点必须走时间戳插值 —— 框的中心就是同一个 renderEnd 的输出，所以这一处同时修掉"线抽"和"框抽"' },
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
       why = '★ 位移均值≈0（物体本来就没动）必须判"不可判"，不能判 PASS —— 那会变成一条永远通过的假判据（本项目栽过"死判据"）' },
    @{ file = 'native\jni-smoketest\InterpSmoothnessProbe.java';
       must = 'double partial = (frame % framesPerSample) / (double) framesPerSample;';
       why = '★★ 旧写法的 partialTick 必须用**整数帧**算，不能写 (simTime % period)/period：浮点取模在 tick 边界给出 0.0499999 而非 0，会凭空造出倒退跳变 —— 本探针第一版就是这么把自己判 FAIL 的（判据写错，不是代码错）' },
    @{ file = 'native\jni-smoketest\InterpSmoothnessProbe.java';
       must = 'new TimedSamples(3, 64, 2.0 * samplePeriod(), () -> clock[0])';
       why = '★ 探针必须调**真实现**（并用可注入时钟保证相位确定性），不许在这里复刻一份插值公式' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\ClientPhysicsWorld.java';
       must = 'physPosSamples.push(px, py, pz);';
       why = '★ 客户端刚体自己的位姿也要打时间戳（renderX/renderRotation 的两个分支都是"按拍更新、按帧渲染"）' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\ClientPhysicsWorld.java';
       must = 'netPosSamples.push(nx, ny, nz);';
       why = '★ 服务端同步下来的权威位姿同样是拍同步量 —— 抓取框/悬停框用的镜子体位姿就在这里' },
    @{ file = 'src\main\java\com\mss\polymech\client\physics\ClientPhysicsWorld.java';
       must = 'if (ring.evaluateQuat(renderRotScratch)) {';
       why = '★ 姿态必须 slerp（不能分量线性插值）：分量插值在四元数上是错的，转过符号边界时会绕一大圈' }
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
       why = '★ 不再需要"使用时长"：原版不会进入使用状态（留着会让后来者以为右键仍是长按）' }
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
