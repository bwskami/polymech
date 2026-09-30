﻿# 离线自检总入口（不需要开游戏、不需要重启客户端）
#
#   .\native\jni-smoketest\run-offline-checks.ps1
#
# 覆盖十三块**只能离线跑才能确定**的东西：
#   1) OrbitAcceptanceTest    —— 用生成出来的 object/*.json 反解轨道六要素，与 JPL 对表
#   2) FrameProbe             —— 姿态帧的 **JOML 语义**（9 参构造是列主序吗？
#                                setFromNormalized 的方向？invert() 之后到底对不对？）
#   3) InterpProbe            —— 旧两拍 lerp vs 新时间戳插值（丝滑度的对拍基线）
#   4) SpaceMappingProbe      —— 地表⇄太空映射往返闭环 + 跨约定存档迁移判据（① 方案的安全带）
#   5) RenderCompressionTest  —— 距离压缩：角直径不变；以及"UBO 该不该全压"的量化
#   6) check-probe-log.ps1    —— 日志判读器自测（既会 PASS 也会 FAIL = 不是死判据）
#   7) check-artifact.ps1     —— 产物自检（源码改动真的进了 build\classes 吗）
#   8) BodyRaycastProbe      —— 服务端体求交（准星指着哪个物理体；坏数据必须跳过）
#   9) DepthOcclusionProbe   —— 两套投影共用一张深度缓冲时"比深度大小"到底成不成立
#                                （2026-09-30「星球挡住物理体」的根因量化 + 回归）
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

Write-Host "`n===== 1/13 轨道验收（object/*.json 对 JPL）=====" -ForegroundColor Cyan
& $java '-Dstdout.encoding=UTF-8' "$root\native\jni-smoketest\OrbitAcceptanceTest.java"
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '轨道验收失败' -ForegroundColor Red }

Write-Host "`n===== 2/13 姿态帧 JOML 语义（FrameProbe）=====" -ForegroundColor Cyan
& $java '-Dstdout.encoding=UTF-8' -cp $joml.FullName "$root\native\jni-smoketest\FrameProbe.java" |
    Select-String -Pattern '===|\[FAIL\]|全部通过|有失败' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '姿态帧自检失败' -ForegroundColor Red }

Write-Host "`n===== 3/13 渲染插值对拍（InterpProbe：旧两拍 lerp vs 新时间戳插值）=====" -ForegroundColor Cyan
& $java '-Dstdout.encoding=UTF-8' "$root\native\jni-smoketest\InterpProbe.java"
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '插值对拍失败' -ForegroundColor Red }

Write-Host "`n===== 4/13 地表⇄太空映射 + 迁移判据（SpaceMappingProbe）=====" -ForegroundColor Cyan
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

Write-Host "`n===== 5/13 距离压缩（RenderCompressionTest：角直径不变 + UBO 该不该全压）=====" -ForegroundColor Cyan
& $java '-Dstdout.encoding=UTF-8' -cp "$root\build\classes\java\main" "$root\native\jni-smoketest\RenderCompressionTest.java" |
    Select-String -Pattern '\[ OK \]|\[FAIL\]|全部通过|失败|全压|半压|zoom=' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '距离压缩自检失败' -ForegroundColor Red }

Write-Host "`n===== 6/13 探针日志判读器自测（既会 PASS 也会 FAIL = 不是死判据）=====" -ForegroundColor Cyan
& "$PSScriptRoot\check-probe-log.ps1" -SelfTest
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '判读器自测失败' -ForegroundColor Red }

Write-Host "`n===== 7/13 产物自检（源码改动真的进了 build\classes 吗）=====" -ForegroundColor Cyan
& "$PSScriptRoot\check-artifact.ps1"
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '产物自检失败' -ForegroundColor Red }

Write-Host "`n===== 8/13 推进器离线标定（ThrustCalibrationProbe：能飞/能停要多大推力）=====" -ForegroundColor Cyan
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

Write-Host "`n===== 9/13 牵引枪拖拽数学（PhysgunDragProbe：收敛 / 限幅 / 载荷无关）=====" -ForegroundColor Cyan
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

Write-Host "`n===== 10/13 太空建造规则（SpaceBuildRulesProbe：世界→局部 / 地皮边界 / 决策表）=====" -ForegroundColor Cyan
& $java '-Dstdout.encoding=UTF-8' -cp "$classes;$($joml.FullName)" "$root\native\jni-smoketest\SpaceBuildRulesProbe.java" |
    Select-String -Pattern '\[ OK \]|\[FAIL\]|规则判据 PASS|有 [0-9]+ 项' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '太空建造规则判据失败' -ForegroundColor Red }

Write-Host "`n===== 11/13 牵引枪光束几何（PhysgunBeamShapeProbe：曲线 / 受力弯曲 / 线宽 / 光斑 / 悬停面 / 淡出）=====" -ForegroundColor Cyan
# 纯数学类，连 joml 都不需要：只靠 build\classes\java\main。
# 它钉住的是"看不像"背后那一层能离线判断的东西：曲线段数、贝塞尔两端咬合与单调推进、
# 受力弯曲的软弹簧（收敛/稳定/落后量有上限）、相机朝向正交基（相机落在光束轴上必须不出 NaN）、
# 四边形的半宽与面积、正对相机的方块、棋盘奇偶、以及强度曲线（松手必须能淡出）。
& $java '-Dstdout.encoding=UTF-8' -cp "$classes" "$root\native\jni-smoketest\PhysgunBeamShapeProbe.java" |
    Select-String -Pattern '\[ OK \]|\[FAIL\]|光束几何判据 PASS|有 [0-9]+ 项' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '光束几何判据失败' -ForegroundColor Red }

Write-Host "`n===== 12/13 服务端体求交（BodyRaycastProbe：准星指着哪个物理体 / 坏数据跳过）=====" -ForegroundColor Cyan
# 2026-09-29：用户报"点一下右键只有一瞬间的射线、无法长按" —— 根因是牵引枪在服务端查了
# 另一张表（MPS 的 PhysicalWorld，那批体的位姿是 NaN），而玩家看得见的体在 PhysicsBodyTracker。
# 这一步钉住新实现（与客户端同一套 RayBox 数学），特别是"位姿 NaN 的体必须跳过"。
& $java '-Dstdout.encoding=UTF-8' -cp "$classes;$($joml.FullName)" "$root\native\jni-smoketest\BodyRaycastProbe.java" |
    Select-String -Pattern '\[ OK \]|\[FAIL\]|体求交判据 PASS|有 [0-9]+ 项' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '服务端体求交判据失败' -ForegroundColor Red }

Write-Host "`n===== 13/13 深度遮挡判据（DepthOcclusionProbe：两套投影共用深度缓冲时能不能比大小）=====" -ForegroundColor Cyan
# 2026-09-30：用户第二次报"远处的星球把近处的物理体挡住"。根因是主深度里混了**两套投影**
# 的深度值（星球走 spaceProj，世界走 MC 主投影），任何"比大小"的遮挡判据都是假的 ——
# 临界距离只有 2.80 格。这一步把"旧判据必失败、新判据必成立"钉死，顺带记录
# "这不是距离压缩引入的回归"（把 spaceProj 的 far 换回压缩前的 1e13，临界距离是 2.53 格）。
# 只依赖 build\classes\java\main（RenderCompression 是纯 Java，无 MC 依赖）。
& $java '-Dstdout.encoding=UTF-8' -cp "$classes" "$root\native\jni-smoketest\DepthOcclusionProbe.java" |
    Select-String -Pattern '\[PASS\]|FAIL|通过，失败|PROBE DONE' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '深度遮挡判据失败' -ForegroundColor Red }

Write-Host ""
if ($failed -eq 0) {
    Write-Host '离线自检全部通过' -ForegroundColor Green
    exit 0
}
Write-Host ("离线自检有 " + $failed + " 项失败") -ForegroundColor Red
exit 1
