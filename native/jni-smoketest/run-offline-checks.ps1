# 离线自检总入口（不需要开游戏、不需要重启客户端）
#
#   .\native\jni-smoketest\run-offline-checks.ps1
#
# 覆盖两块**只能离线跑才能确定**的东西：
#   1) OrbitAcceptanceTest —— 用生成出来的 object/*.json 反解轨道六要素，与 JPL 对表
#   2) FrameProbe          —— 姿态帧的 **JOML 语义**（9 参构造是列主序吗？
#                              setFromNormalized 的方向？invert() 之后到底对不对？）
#
# 为什么必须有 2)：姿态帧那几行纯数学，"读代码觉得对"与"真的是对的"之间隔着 JOML 的存储约定。
# 2026-09-25 就是在这里抓到源码把列序写反了（矩阵变成转置 ⇒ 姿态帧整个反过来 ⇒ 上午的太阳
# 偏 90°），而当时游戏里那两条判据全是 PASS —— 也就是说**只看游戏内判据会漏掉这一类错**。
#
# 本文件必须带 UTF-8 BOM 保存：Windows PowerShell 读无 BOM 的脚本会按 GBK 解，
# 中文串尾字节会把后面的引号吃掉，直接报 "The string is missing the terminator"。
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

Write-Host "`n===== 1/3 轨道验收（object/*.json 对 JPL）=====" -ForegroundColor Cyan
& $java '-Dstdout.encoding=UTF-8' "$root\native\jni-smoketest\OrbitAcceptanceTest.java"
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '轨道验收失败' -ForegroundColor Red }

Write-Host "`n===== 2/3 姿态帧 JOML 语义（FrameProbe）=====" -ForegroundColor Cyan
& $java '-Dstdout.encoding=UTF-8' -cp $joml.FullName "$root\native\jni-smoketest\FrameProbe.java" |
    Select-String -Pattern '===|\[FAIL\]|全部通过|有失败' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '姿态帧自检失败' -ForegroundColor Red }

Write-Host "`n===== 3/3 渲染插值对拍（InterpProbe：旧两拍 lerp vs 新时间戳插值）=====" -ForegroundColor Cyan
& $java '-Dstdout.encoding=UTF-8' "$root\native\jni-smoketest\InterpProbe.java"
if ($LASTEXITCODE -ne 0) { $failed++; Write-Host '插值对拍失败' -ForegroundColor Red }

Write-Host ""
if ($failed -eq 0) {
    Write-Host '离线自检全部通过' -ForegroundColor Green
    exit 0
}
Write-Host ("离线自检有 " + $failed + " 项失败") -ForegroundColor Red
exit 1
