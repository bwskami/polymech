# 离线自检总入口（不需要开游戏、不需要重启客户端）
#
#   .\native\jni-smoketest\run-offline-checks.ps1
#
# 覆盖十八块**只能离线跑才能确定**的东西：
#   1) OrbitAcceptanceTest    —— 用生成出来的 object/*.json 反解轨道六要素，与 JPL 对表
#   2) FrameProbe             —— 姿态帧的 **JOML 语义**（9 参构造是列主序吗？
#                                setFromNormalized 的方向？invert() 之后到底对不对？）
#   3) InterpProbe            —— 旧两拍 lerp vs 新时间戳插值（丝滑度的对拍基线）
#   4) SpaceMappingProbe      —— 地表⇄太空映射往返闭环 + 跨约定存档迁移判据（① 方案的安全带）
#   5) RenderCompressionTest  —— 距离压缩：角直径不变；以及"UBO 该不该全压"的量化
#   6) check-probe-log.ps1    —— 日志判读器自测（既会 PASS 也会 FAIL = 不是死判据）
#   7) check-artifact.ps1     —— 产物自检（源码改动真的进了 build\classes 吗）
#   8) BodyRaycastProbe      —— 服务端体求交（准星指着哪个物理体；坏数据必须跳过）
#   9) DepthOcclusionProbe   —— 两套投影能不能"比深度大小" + 第十二轮反向 Z / 对表判据
#                                （2026-09-30「星球挡住物理体」的根因量化 + 回归）
#  10) MappingRoundTripProbe —— 地表⇄太空映射的 2D 旋转对可逆性（pos_shadow_rotate）
#                                （2026-09-30 切 0.3.0 基准时发现的**潜伏**缺陷量化）
#  11) SubChunkGranularityProbe —— 子块粒度 64³→16³ 的结构不变量（覆盖性 / 索引互逆 / 位布局单射）
#                                （改这种全局耦合常量时编译器帮不上忙，全靠这三条）
#  13) BodyLocalMappingProbe —— 体局部格⇄世界格映射（含刚体旋转；量化极限要分清）
#                                （2026-10-01 修「体转过/挪过后放方块吸错格」）
#  14) ConstraintProbe       —— 原生**固定约束**（牵引枪的「钉住」/LOCK）：
#                                ★ 带对照组（不加约束必须会掉，否则「钉住了」可能是死在跑）
#                                ★ 用 RigidBodySet 原始计数验锚体真的建了 / 真的回收了
#                                （2026-10-02 操作方式对齐 3/3；依赖 Rapier 内部语义，必须程序判定）
#  12) PhysgunRotationProbe  —— 牵引枪**角伺服**：最短弧 / PD 律 / 收敛 / ★ 惯量补偿
#                                （2026-10-01「抓住时的旋转控制」；惯量补偿是探针逼出来的结论）
#
# 为什么必须有 2)：姿态帧那几行纯数学，"读代码觉得对"与"真的是对的"之间隔着 JOML 的存储约定。
# 2026-09-25 就是在这里抓到源码把列序写反了（矩阵变成转置 ⇒ 姿态帧整个反过来 ⇒ 上午的太阳
# 偏 90°），而当时游戏里那两条判据全是 PASS —— 也就是说**只看游戏内判据会漏掉这一类错**。
#
# 本文件必须带 UTF-8 BOM 保存：Windows PowerShell 读无 BOM 的脚本会按 GBK 解，
# 中文串尾字节会把后面的引号吃掉，直接报 "The string is missing the terminator"。
# ⚠️ 只许用**字节级**操作补 BOM（[System.IO.File]::ReadAllBytes/WriteAllBytes）——
#    2026-09-27 用 `Get-Content -Raw` + `WriteAllText` 转换编码，把整份中文脚本按 ANSI 解成了乱码。
#
# 退出码 0 = 全通过；非 0 = 有失败。

$ErrorActionPreference = 'Stop'
chcp 65001 > $null
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # 仓库根
$java = 'C:\develop\JDK\bin\java.exe'
if (-not (Test-Path $java)) {
    $java = (Get-Command java -ErrorAction SilentlyContinue).Source
}
if (-not $java) { Write-Host '找不到 java' -ForegroundColor Red; exit 2 }

$joml = Get-ChildItem "$env:USERPROFILE\.gradle\caches" -Recurse -File -Filter 'joml-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc|primitives' } |
        Sort-Object Name -Descending | Select-Object -First 1
if (-not $joml) { Write-Host 'gradle 缓存里找不到 joml jar（FrameProbe 需要）' -ForegroundColor Red; exit 2 }
Write-Host ("joml = " + $joml.FullName)

$failed = 0

Write-Host "`n===== 1/18 轨道验收（object/*.json 对 JPL）=====" -ForegroundColor Cyan
& $java '-Dstdout.encoding=UTF-8' "$root\native\jni-smoketest\OrbitAcceptanceTest.java"
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '轨道验收失败' -ForegroundColor Red }

Write-Host "`n===== 2/18 姿态帧 JOML 语义（FrameProbe）=====" -ForegroundColor Cyan
& $java '-Dstdout.encoding=UTF-8' -cp $joml.FullName "$root\native\jni-smoketest\FrameProbe.java" |
    Select-String -Pattern '===|\[FAIL\]|全部通过|有失败' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '姿态帧自检失败' -ForegroundColor Red }

Write-Host "`n===== 3/18 渲染插值对拍（InterpProbe：旧两拍 lerp vs 新时间戳插值）=====" -ForegroundColor Cyan
& $java '-Dstdout.encoding=UTF-8' "$root\native\jni-smoketest\InterpProbe.java"
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '插值对拍失败' -ForegroundColor Red }

Write-Host "`n===== 4/18 地表⇄太空映射 + 迁移判据（SpaceMappingProbe）=====" -ForegroundColor Cyan
# 需要我们的类与 NeoForge 合并 jar：classpath 用 gradle 缓存全量 jar 拼成 @argfile（见 §31.29）
$merged = "$root\build\moddev\artifacts\neoforge-21.1.228-merged.jar"
$classes = "$root\build\classes\java\main"
if (-not (Test-Path $classes)) {
    Write-Host '先跑一次 gradlew compileJava（缺 build\classes\java\main）' -ForegroundColor Yellow
} else {
    $cpJars = @($classes, $merged) + (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2" -Recurse -File -Filter *.jar -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } | ForEach-Object { $_.FullName })
    $argfile = "$root\build\probe-cp.txt"
    [System.IO.File]::WriteAllLines($argfile, @('-Dstdout.encoding=UTF-8', '-cp', ($cpJars -join ';'),
            "$root\native\jni-smoketest\SpaceMappingProbe.java"), (New-Object System.Text.UTF8Encoding($false)))
    # ⚠️ SLF4J 会把 "multiple providers" 打到 stderr；在 $ErrorActionPreference='Stop' 下
    #    PowerShell 会把原生命令的 stderr 当**终止错误**，于是这一步之后的步骤一个都跑不到
    #    （2026-09-27 实测踩到）。所以这一段临时放成 Continue，只看退出码。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $java "@$argfile" | Select-String -Pattern 'PASS|FAIL|全部判据|有 [0-9]+ 项' | ForEach-Object { $_.Line }
    $probeRc = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($probeRc -ne 0) { $failed++; Write-Host '映射/迁移探针失败' -ForegroundColor Red }
}

Write-Host "`n===== 5/18 距离压缩（RenderCompressionTest：角直径不变 + UBO 该不该全压）=====" -ForegroundColor Cyan
& $java '-Dstdout.encoding=UTF-8' -cp "$root\build\classes\java\main" "$root\native\jni-smoketest\RenderCompressionTest.java" |
    Select-String -Pattern '\[ OK \]|\[FAIL\]|全部通过|失败|全压|半压|zoom=' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '距离压缩自检失败' -ForegroundColor Red }

Write-Host "`n===== 6/18 探针日志判读器自测（既会 PASS 也会 FAIL = 不是死判据）=====" -ForegroundColor Cyan
& "$PSScriptRoot\check-probe-log.ps1" -SelfTest
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '判读器自测失败' -ForegroundColor Red }

Write-Host "`n===== 7/18 产物自检（源码改动真的进了 build\classes 吗）=====" -ForegroundColor Cyan
& "$PSScriptRoot\check-artifact.ps1"
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '产物自检失败' -ForegroundColor Red }

Write-Host "`n===== 8/18 推进器离线标定（ThrustCalibrationProbe：能飞/能停要多大推力）=====" -ForegroundColor Cyan
$dllDir = "$root\src\main\resources\natives\windows_amd64"
if (-not (Test-Path $dllDir)) {
    Write-Host '缺原生库目录，跳过（先跑 gradlew copyNativePhysics）' -ForegroundColor Yellow
} else {
    $slf4j = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1" -Recurse -File -Filter 'slf4j-api-*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
    # 同第 4 步：Java 的 SLF4J/Native-access 警告走 stderr，Stop 会把它当终止错误。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $java '-Dstdout.encoding=UTF-8' "-Djava.library.path=$dllDir" `
        -cp "$classes;$($joml.FullName);$($slf4j.FullName)" "$root\native\jni-smoketest\ThrustCalibrationProbe.java" |
        Select-String -Pattern '\[PASS\]|\[FAIL\]|判据 PASS|有 [0-9]+ 项' | ForEach-Object { $_.Line }
    $thrustRc = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($thrustRc -ne 0) { $failed++; Write-Host '推进器标定失败' -ForegroundColor Red }
}

Write-Host "`n===== 9/18 牵引枪拖拽数学（PhysgunDragProbe：收敛 / 限幅 / 载荷无关）=====" -ForegroundColor Cyan
if (-not (Test-Path $dllDir)) {
    Write-Host '缺原生库目录，跳过' -ForegroundColor Yellow
} else {
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $java '-Dstdout.encoding=UTF-8' "-Djava.library.path=$dllDir" `
        -cp "$classes;$($joml.FullName);$($slf4j.FullName)" "$root\native\jni-smoketest\PhysgunDragProbe.java" |
        Select-String -Pattern '\[ OK \]|\[FAIL\]|判据 PASS|有 [0-9]+ 项' | ForEach-Object { $_.Line }
    $dragRc = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($dragRc -ne 0) { $failed++; Write-Host '牵引枪拖拽验收失败' -ForegroundColor Red }
}

Write-Host "`n===== 10/18 太空建造规则（SpaceBuildRulesProbe：世界→局部 / 地皮边界 / 决策表）=====" -ForegroundColor Cyan
& $java '-Dstdout.encoding=UTF-8' -cp "$classes;$($joml.FullName)" "$root\native\jni-smoketest\SpaceBuildRulesProbe.java" |
    Select-String -Pattern '\[ OK \]|\[FAIL\]|规则判据 PASS|有 [0-9]+ 项' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '太空建造规则判据失败' -ForegroundColor Red }

Write-Host "`n===== 11/18 牵引枪光束几何（PhysgunBeamShapeProbe：曲线 / 受力弯曲 / 线宽 / 光斑 / 悬停面 / 淡出）=====" -ForegroundColor Cyan
# 纯数学类，连 joml 都不需要：只靠 build\classes\java\main。
# 它钉住的是"看不像"背后那一层能离线判断的东西：曲线段数、贝塞尔两端咬合与单调推进、
# 受力弯曲的软弹簧（收敛/稳定/落后量有上限）、相机朝向正交基（相机落在光束轴上必须不出 NaN）、
# 四边形的半宽与面积、正对相机的方块、棋盘奇偶、以及强度曲线（松手必须能淡出）。
& $java '-Dstdout.encoding=UTF-8' -cp "$classes" "$root\native\jni-smoketest\PhysgunBeamShapeProbe.java" |
    Select-String -Pattern '\[ OK \]|\[FAIL\]|光束几何判据 PASS|有 [0-9]+ 项' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '光束几何判据失败' -ForegroundColor Red }

Write-Host "`n===== 12/18 服务端体求交（BodyRaycastProbe：准星指着哪个物理体 / 坏数据跳过）=====" -ForegroundColor Cyan
# 2026-09-29：用户报"点一下右键只有一瞬间的射线、无法长按" —— 根因是牵引枪在服务端查了
# 另一张表（MPS 的 PhysicalWorld，那批体的位姿是 NaN），而玩家看得见的体在 PhysicsBodyTracker。
# 这一步钉住新实现（与客户端同一套 RayBox 数学），特别是"位姿 NaN 的体必须跳过"。
& $java '-Dstdout.encoding=UTF-8' -cp "$classes;$($joml.FullName)" "$root\native\jni-smoketest\BodyRaycastProbe.java" |
    Select-String -Pattern '\[ OK \]|\[FAIL\]|体求交判据 PASS|有 [0-9]+ 项' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '服务端体求交判据失败' -ForegroundColor Red }

Write-Host "`n===== 13/18 深度遮挡判据（DepthOcclusionProbe：两套投影能不能比大小 + 反向 Z / 对表）=====" -ForegroundColor Cyan
# 2026-09-30：用户第二次报"远处的星球把近处的物理体挡住"。根因是主深度里混了**两套投影**
# 的深度值（星球走 spaceProj，世界走 MC 主投影），任何"比大小"的遮挡判据都是假的 ——
# 临界距离只有 2.80 格。这一步把"旧判据必失败、新判据必成立"钉死，顺带记录
# "这不是距离压缩引入的回归"（把 spaceProj 的 far 换回压缩前的 1e13，临界距离是 2.53 格）。
# 只依赖 build\classes\java\main（RenderCompression 是纯 Java，无 MC 依赖）。
& $java '-Dstdout.encoding=UTF-8' -cp "$classes" "$root\native\jni-smoketest\DepthOcclusionProbe.java" |
    Select-String -Pattern '\[PASS\]|FAIL|通过，失败|PROBE DONE' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '深度遮挡判据失败' -ForegroundColor Red }

Write-Host "`n===== 14/18 映射往返可逆性（MappingRoundTripProbe：pos_shadow_rotate 的矩阵对）=====" -ForegroundColor Cyan
# 2026-09-30 把参考基准切到 space 0.3.0 时发现的**潜伏**缺陷：
# 我们照 0.1.3 抄的"正向对称阵 [[c,s],[s,c]] + 反向旋转阵 [[c,-s],[s,c]]"只在 rotate=0 时互逆
# （对称阵的逆是 1/(c²-s²)·[[c,-s],[-s,c]]，不是旋转阵）；0.3.0 已把正向改成真旋转、反向改成严格逆。
# 我们的数据包现在 15 个世界全是 pos_shadow_rotate=0.0，所以实际可逆 —— 但那个字段**真实存在且会被读**
# （SpaceModDataPackManger → setPosShadowData → 网络同步），一旦填非零往返误差最高 200%。
# 纯数学（JDK 即可），不需要 joml，也不需要 build\classes。
& $java '-Dstdout.encoding=UTF-8' "$root\native\jni-smoketest\MappingRoundTripProbe.java" |
    Select-String -Pattern '\[PASS\]|\[FAIL\]|通过，失败' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '映射往返可逆性判据失败' -ForegroundColor Red }

Write-Host "`n===== 15/18 子块粒度结构不变量（SubChunkGranularityProbe：64³→16³）=====" -ForegroundColor Cyan
# 2026-09-30 按 space 0.3.0 把物理体子块粒度由 64³ 改成 16³（对齐原版 section 粒度）。
# 这类"全局耦合常量"编译器完全帮不上忙：每个 64 都是合法 int，全改错了照样 BUILD SUCCESSFUL，
# 只是方块静默出现在错误的位置。所以这里钉三条**结构性**不变量：
#   ① 覆盖性：8×8×8 个子块必须恰好一次铺满 [-64, 64)³（抓"偏移写成 -CHUNK_WIDTH=-16"这类错）
#   ② 索引互逆：PalettedChunk.index 的 <<4/<<8 与 voxelBounds 的 &15/>>4&15/>>8 必须互逆
#   ③ 位布局单射：脏块键对 512 个子块无碰撞（旧 1 位布局会压成 32 个键 = 16 倍碰撞）
# 需要 build\classes\java\main（常量内联）——但**不加载** MC 类，所以派生公式在探针里复刻，
# 由 check-artifact.ps1 的源码契约保证"复刻 == 实现"。
& $java '-Dstdout.encoding=UTF-8' -cp "$classes" "$root\native\jni-smoketest\SubChunkGranularityProbe.java" |
    Select-String -Pattern '\[PASS\]|\[FAIL\]|通过，失败' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '子块粒度结构不变量判据失败' -ForegroundColor Red }

Write-Host "`n===== 16/18 牵引枪角伺服（PhysgunRotationProbe：最短弧 / PD 律 / 收敛 / 惯量补偿）=====" -ForegroundColor Cyan
# 2026-10-01 补"抓住时的旋转控制"（对照 Simulated-Project 的 physics_staff）：
# 参考在 PhysicsStaffServerHandler.physicsTick 里每个物理 tick 给约束的 6 个轴设马达
# （角轴目标 0 + 刚度/阻尼 = PD 伺服），**不是** setPose/setRotation。我们线性那半早就是
# 弹簧（PhysgunSpring），这一步补的是角那半。三个真结论：
#   ① 四元数双重覆盖：q 与 -q 是同一旋转（不取反就会"朝反方向绕一大圈"）
#   ② 大误差必须限幅（θ=π 时未限幅是 25.13 rad/s²）
#   ③ ★ 必须做**惯量补偿**：第一版按固定力矩实现，实测 10 倍惯量的体阻尼比从 0.71 掉到 0.22、
#      3 秒后还差 37.75° —— 那会变成"能拖动重船、却转不动它"，与线性那半"手感与载荷无关"矛盾。
# 纯数学（joml 即可），不需要原生库。
& $java '-Dstdout.encoding=UTF-8' -cp "$classes;$($joml.FullName)" "$root\native\jni-smoketest\PhysgunRotationProbe.java" |
    Select-String -Pattern '\[PASS\]|\[FAIL\]|通过，失败' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '牵引枪角伺服判据失败' -ForegroundColor Red }

Write-Host "`n===== 17/18 体局部格⇄世界格映射（BodyLocalMappingProbe：含刚体旋转 / 量化极限）=====" -ForegroundColor Cyan
# 2026-10-01 修牵引枪遗留缺口 (2)：PhysicsBodyTracker 原来把局部格换算世界格用的是
#   world = tracked.origin + (dx,dy,dz)      // 轴对齐，不含旋转
# 而 origin 是**建体时**区域的最小角、withTransform 从不更新它。两个后果：
#   ① 体被牵引枪转过之后，方块真实世界位置不再是 origin + 局部
#   ② 体只是被推着平移过也一样错（origin 是冻结的）
# 症状：在转过/挪过的物理体旁边放方块，要么吸不上去、要么吸进错误的格子。
# 现在改判在**体局部空间**（世界格 → RayBox.toLocalCell → 与方块列表比曼哈顿距离），
# 与客户端拾取（RayBox.toLocalPoint）和 localToWorld（RayBox.toWorldCenter）共用同一对映射。
#
# 探针直接调真实现（RayBox 是纯函数），旧映射只作为**被反驳的对象**复刻。四条关键结论：
#   ★ A 行为中性：恒等旋转 + 体未移动时，新映射与旧映射**逐位一致**（不是顺手改语义）
#   ★ B 反例：转 90° 后旧映射指的世界格 → 反算局部 = (-1,0,2)，而真实是 (2,0,0)
#   ★ C 逆变换分三层：实值逆变换**精确**（所有旋转）；90° 整数倍下整数格往返**精确**；
#       斜轴下整数格往返误差**有界 ≤1 格** —— 那是"世界格与旋转点阵错位"的量化极限，
#       **不是缺陷**（本探针第一版把这条写成"必须恒等"，是判据写错，已改正）
#   ★ D 对照：同一场景旧映射最大偏差 **11 格**（无界），新映射 ≤1 格
& $java '-Dstdout.encoding=UTF-8' -cp "$classes;$($joml.FullName)" "$root\native\jni-smoketest\BodyLocalMappingProbe.java" |
    Select-String -Pattern '\[PASS\]|\[FAIL\]|通过，失败' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '体局部格映射判据失败' -ForegroundColor Red }

Write-Host "`n===== 18/18 原生固定约束（ConstraintProbe：牵引枪的「钉住」，含对照组）=====" -ForegroundColor Cyan
# 2026-10-02 操作方式对齐 (3/3)：左键 = LOCK。参考的 LOCK 是
#   pipeline.addConstraint(null, subLevel, FixedConstraintConfiguration(...))
# —— 关节一端写 null = 锚在世界。但 **Rapier 0.34 没有这个隐式地面**：
#   约束构建器是 `&bodies[joint.body1]` 直接索引，传 RigidBodyHandle::invalid() 会 panic
#   （Coarena::get 返回 Option，没有默认值兜底）。所以原生侧把「世界」这个锚
#   **显式建出来**：一个 FIXED 刚体、建在体当前位姿上、关节两个局部帧都用单位帧。
# 这是对引擎内部语义的依赖 ⇒ 必须由程序判定（搭错了的表现是「钉不住」，而那是静默的）。
#
# 探针的判据设计（★ 是关键的两条）：
#   ★ A 组是**对照组**：同一个世界、同一个体、同样 100 步，不加约束必须自由落体 4.9 格
#     —— 没有它，B 组的「没动」有可能只是「物理世界根本没在跑」（死判据）
#   ★ B/C/E 组用 worldGetRigidBodySetSize()（Rapier 原始刚体集，**含**锚体）而不是
#     worldBodyCount()（只数 Java 可见的体）来验锚体的建立与回收 ——
#     锚体泄漏是「不报错、不可见」的那类错，只能靠这个计数抓
#   D 组：重复加必须被拒（-2）且**不建第二个锚体**
#   F 组：退化输入（世界句柄 0 / 不存在的体 / 不存在的约束）只返回失败，不许崩
#
# ⚠️ 这一项**必须先 javac 编译、再用 -cp 跑**，不能像上面几项那样用源码模式（java Xxx.java）：
#   源码模式用的是另一套类加载器，System.load 与 -cp 上的 NativePhysics 不在同一个加载器里
#   ⇒ JNI 符号绑不上，报 `UnsatisfiedLinkError: 'int ...NativePhysics.abiVersion()'`
#   （我在这里踩过一次；NativeSmokeTest 的注释本来也要求「先编译」）。
$javacExe = Join-Path (Split-Path $java -Parent) 'javac.exe'
$probeClasses = Join-Path $env:TEMP 'polymech-probe-classes'
New-Item -ItemType Directory -Force -Path $probeClasses | Out-Null
$probeDll = (Get-ChildItem -Path $dllDir -Filter 'polymech_physics.*' -File | Select-Object -First 1).FullName
# 同第 8 步：JVM 的 Native-access 警告走 stderr，Stop 会把它当终止错误 ⇒ 临时改成 Continue。
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $javacExe -encoding UTF-8 -cp $classes -d $probeClasses "$root\native\jni-smoketest\ConstraintProbe.java"
$probeJavacRc = $LASTEXITCODE
& $java '-Dstdout.encoding=UTF-8' -cp "$probeClasses;$classes" ConstraintProbe $probeDll |
    Select-String -Pattern '\[PASS\]|\[FAIL\]|通过，失败' | ForEach-Object { $_.Line }
$probeRc = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($probeJavacRc -ne 0) { $failed++; Write-Host 'ConstraintProbe 编译失败' -ForegroundColor Red }
if ($probeRc -ne 0) { $failed++; Write-Host '固定约束判据失败' -ForegroundColor Red }

Write-Host ""
if ($failed -eq 0) {
    Write-Host '离线自检全部通过' -ForegroundColor Green
    exit 0
}
Write-Host ("离线自检有 " + $failed + " 项失败") -ForegroundColor Red
exit 1
