# 跑 datagen 的正确姿势（含自检）—— 专治 "could not open ...\dataRunVmArgs.txt"
#
#   pwsh native\jni-smoketest\datagen.ps1
#
# 为什么需要它：本项目 gradle.properties 里开着
#     org.gradle.configuration-cache=true
# 而 NeoForge moddev 的 run 参数文件（build\moddev\dataRunVmArgs.txt 等）是**任务产出**：
# 配置缓存命中时 `prepareDataRun` 会按缓存里的旧状态跳过，于是在
#   "缓存里有这条任务 + 磁盘上文件已被删（clean / 手动删 / 换机器）"
# 的组合下，JavaExec 直接去开一个不存在的 @argfile ⇒
#     Error: could not open `...\build\moddev\dataRunVmArgs.txt'
# 加 `--no-configuration-cache` 强制重新配置 + 重跑 `prepareDataRun` 即可自愈。
# （同样的坑对 runClient / runServer 一样成立，只是它们的 argfile 通常还在。）
#
# 自检：跑完必须看到牵引枪的模型与语言键 —— 缺一个就 FAIL（非 0 退出）。

$ErrorActionPreference = 'Stop'
chcp 65001 > $null
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

Write-Host '===== gradlew runData --no-configuration-cache =====' -ForegroundColor Cyan
& "$root\gradlew" runData --no-configuration-cache --console=plain
$rc = $LASTEXITCODE
# 本项目 gradle 常以退出码 1 收尾而实际成功（见 docs/mps-clone-plan.md 的教训），
# 所以判据不看退出码，看**产物**（下面）。
Write-Host ("gradle 退出码 = " + $rc + "（本项目常见 1，判据看产物，不看它）") -ForegroundColor Yellow

$miss = 0
function Check-File([string]$rel, [string]$what) {
    $p = Join-Path $root $rel
    if (Test-Path $p) {
        Write-Host ("[ OK ] " + $what + "  -> " + $rel) -ForegroundColor Green
    } else {
        Write-Host ("[MISS] " + $what + "  -> " + $rel) -ForegroundColor Red
        $script:miss++
    }
}

Write-Host ''
Write-Host '===== 产物自检 =====' -ForegroundColor Cyan
Check-File 'build\moddev\dataRunVmArgs.txt' 'run 参数文件已生成'
Check-File 'src\generated\resources\assets\poly_mech\models\item\physgun.json' '牵引枪模型'

# 反向判据：**已被移除**的东西不该残留在产物里（stale 文件比缺文件更难发现）。
# 2026-09-27：装配器（PhysicsAssemblerItem）按用户要求撤销 ⇒ 模型与语言键都必须消失。
$staleModel = Join-Path $root 'src\generated\resources\assets\poly_mech\models\item\physics_assembler.json'
if (Test-Path $staleModel) {
    Write-Host '[MISS] 已撤销的装配器模型仍在产物里（datagen 应清掉它）' -ForegroundColor Red
    $miss++
} else {
    Write-Host '[ OK ] 已撤销的装配器模型不在产物里' -ForegroundColor Green
}

foreach ($lang in @('en_us', 'zh_cn')) {
    $p = Join-Path $root "src\generated\resources\assets\poly_mech\lang\$lang.json"
    if (Test-Path $p) {
        $hit = (Select-String -Path $p -Pattern 'item\.poly_mech\.physgun' -AllMatches).Count
        $stale = (Select-String -Path $p -Pattern 'item\.poly_mech\.physics_assembler' -AllMatches).Count
        if ($hit -ge 1 -and $stale -eq 0) {
            Write-Host ("[ OK ] lang $lang 有牵引枪键、且没有已撤销的装配器键") -ForegroundColor Green
        } else {
            Write-Host ("[MISS] lang $lang 牵引枪键命中 $hit、装配器残留 $stale（应为 ≥1 / 0）") -ForegroundColor Red
            $miss++
        }
    } else {
        Write-Host ("[MISS] 缺 lang/$lang.json") -ForegroundColor Red
        $miss++
    }
}

Write-Host ''
if ($miss -eq 0) {
    Write-Host 'datagen 完成且产物齐全（模型 + 语言 + argfile）' -ForegroundColor Green
    exit 0
}
Write-Host ("datagen 有 " + $miss + " 处产物缺失") -ForegroundColor Red
exit 1
