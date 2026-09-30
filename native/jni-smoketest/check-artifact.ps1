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
    @{ cls = 'com\mss\polymech\mps\kelvin\event\PhysicalBodySpaceEvent.class';
       marks = @('toGame', 'toSpace', 'toMc');
       why = '物理↔天体桥的三处边界换算（S2）' },
    @{ cls = 'com\mss\polymech\mixin\LevelRendererCelestialSkyMixin.class';
       marks = @('原版日月已隐藏');
       why = '只掐原版日月的 mixin（且证明 mixin 类确实被编译进产物）' },
    @{ cls = 'com\mss\polymech\item\PhysgunItem.class';
       marks = @('已抓住', 'onUseTick', 'PhysgunSpring', 'PhysgunClientState', 'PhysgunBeamPacket',
                 'BodyRaycast', 'PhysicsBodyTracker', 'nearestBodyInfo');
       why = '牵引枪（抓取 + 弹簧拖拽 + 光束广播 + 音效）—— 2026-09-29 起在 PhysicsBodyTracker 上求交' },
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
       why = '两套投影必须"对表"：MC 主投影的 far 要等于天体投影的 near，否则 1-mainDepth 与 spaceDepth 不可比' }
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
if ($miss -eq 0) {
    Write-Host '产物自检全部命中（源码改动确实进了 build\classes，引用的贴图也在）' -ForegroundColor Green
    exit 0
}
Write-Host ("产物自检有 " + $miss + " 处缺失 —— 先 gradlew compileJava，再重跑本脚本" ) -ForegroundColor Red
exit 1
