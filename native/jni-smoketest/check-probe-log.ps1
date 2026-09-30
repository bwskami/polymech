# 探针日志判读器 —— 把"抄一行看 PASS/FAIL"变成一条命令
#
#   pwsh native\jni-smoketest\check-probe-log.ps1                     # 读 run\logs\latest.log
#   pwsh native\jni-smoketest\check-probe-log.ps1 -Log <任意 .log/.log.gz>
#   pwsh native\jni-smoketest\check-probe-log.ps1 -SelfTest           # 用自带样例自测（离线，不开游戏）
#
# 为什么要它：本项目的教训是**"没有输出"与"输出正常"在日志里长得一模一样**（§31.23 死判据）。
# 所以这里把三种结论分开报：
#   PASS / ★FAIL = 判据跑了，读数合格/不合格
#   未出现       = 判据根本没跑（比 FAIL 更该查：注入点/分支/门槛）
#   不可判       = 跑了但读数是 n/a / NaN（判据自身坏了）
#
# 退出码：0 = 全部 PASS；1 = 有 FAIL；2 = 有关键项"未出现"。
#
# ⚠️ 本文件必须带 UTF-8 BOM（Windows PowerShell 5.1 无 BOM 会按 GBK 解，中文串会吃掉引号）。

param(
    [string]$Log = "run\logs\latest.log",
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
chcp 65001 > $null

function Read-LogLines([string]$path) {
    if (-not (Test-Path $path)) { throw "找不到日志: $path" }
    if ($path -like '*.gz') {
        $in = [System.IO.File]::OpenRead($path)
        try {
            $gz = New-Object System.IO.Compression.GZipStream($in, [System.IO.Compression.CompressionMode]::Decompress)
            $sr = New-Object System.IO.StreamReader($gz, [System.Text.Encoding]::UTF8)
            try { return ($sr.ReadToEnd() -split "`r?`n") } finally { $sr.Close() }
        } finally { $in.Close() }
    }
    return (Get-Content $path)
}

$script:rows = New-Object System.Collections.Generic.List[object]

function Add-Row([string]$name, [string]$want, [string]$got, [string]$verdict) {
    $script:rows.Add([pscustomobject]@{ 判据 = $name; 期望 = $want; 实测 = $got; 结论 = $verdict })
}

function Verdict-From([bool]$ok) {
    if ($ok) { return 'PASS' }
    return '★FAIL'
}

function Num-From([string]$s, [string]$pattern) {
    $m = [regex]::Match($s, $pattern)
    if ($m.Success) { return [double]$m.Groups[1].Value }
    return [double]::NaN
}

function Judge-Lines($lines) {
    # ---------- 1) 进出太空：去程 ----------
    $up = @($lines | Where-Object { $_.Contains('[坐标落点]') -and $_.Contains('→ 太空') })
    $deep = @($lines | Where-Object { $_.Contains('目标在深空，跳过区块预加载') })
    $down = @($lines | Where-Object { $_.Contains('[坐标落点]') -and $_.Contains('太空 →') })
    $mig = @($lines | Where-Object { $_.Contains('[坐标迁移]') })

    if ($up.Count -eq 0) {
        Add-Row '去程落点' '至少 1 行 [坐标落点] … → 太空' '0 行' '未出现'
    } else {
        $l = $up[-1]
        $ratio = Num-From $l '半径比=([\d.]+)'
        $scaled = $l.Contains('约定=缩放')
        $scaleTxt = '不是缩放'
        if ($scaled) { $scaleTxt = '缩放' }
        Add-Row '去程约定' '约定=缩放(1格=10000米)' $scaleTxt (Verdict-From $scaled)

        $ratioV = '不可判'
        if (-not [double]::IsNaN($ratio)) { $ratioV = Verdict-From ([Math]::Abs($ratio - 2.2) -lt 0.05) }
        Add-Row '去程半径比' '≈2.20（到达距离 2.2R）' ("{0:N4}" -f $ratio) $ratioV

        Add-Row '深空分支消失' '0 行「目标在深空，跳过区块预加载」' ("$($deep.Count) 行") (Verdict-From ($deep.Count -eq 0))
    }

    # ---------- 2) 进出太空：回程 + 往返一致性 ----------
    if ($down.Count -eq 0) {
        Add-Row '回程落点' '至少 1 行 [坐标落点] 太空 → earth' '0 行' '未出现'
    } else {
        $d = $down[-1]
        $r2 = Num-From $d '半径比=([\d.]+)'
        $r2V = '不可判'
        if (-not [double]::IsNaN($r2)) { $r2V = Verdict-From ([Math]::Abs($r2 - 1.02) -lt 0.05) }
        Add-Row '回程半径比' '≈1.02（卡门线捕获壳）' ("{0:N4}" -f $r2) $r2V

        $ang = Num-From $d '与去程方向夹角=([\d.]+)°'
        $angTxt = 'n/a'
        if (-not [double]::IsNaN($ang)) { $angTxt = "{0:N3}°" -f $ang }
        $angOk = $d.Contains('往返: 与去程方向夹角') -and ($d.Contains('PASS') -or ((-not [double]::IsNaN($ang)) -and $ang -le 5.0))
        $angV = '不可判(旧日志无此字段)'
        if ($d.Contains('往返: 与去程方向夹角')) { $angV = Verdict-From $angOk }
        elseif ($d.Contains('n/a(本局没有去程记录)')) { $angV = '不可判(本局没有去程记录)' }
        Add-Row '往返同径线' '夹角 ≤5° ⇒ PASS' $angTxt $angV

        if ($up.Count -gt 0) {
            $mx = [regex]::Match($up[-1], '玩家地表=\(([-\d.eE+]+),')
            $mz = [regex]::Match($up[-1], '玩家地表=\([-\d.eE+]+, [-\d.eE+]+, ([-\d.eE+]+)\)')
            $lx = [regex]::Match($d, '落点=\((-?\d+),')
            $lz = [regex]::Match($d, '落点=\(-?\d+, -?\d+, (-?\d+)\)')
            if ($mx.Success -and $mz.Success -and $lx.Success -and $lz.Success) {
                $dx = [Math]::Abs([double]$lx.Groups[1].Value - [double]$mx.Groups[1].Value)
                $dz = [Math]::Abs([double]$lz.Groups[1].Value - [double]$mz.Groups[1].Value)
                Add-Row '落点回位(去程起飞点)' '|Δx|,|Δz| ≤ 2 格' ("Δx={0:N1} Δz={1:N1}" -f $dx, $dz) `
                    (Verdict-From ($dx -le 2.0 -and $dz -le 2.0))
            } else {
                Add-Row '落点回位(去程起飞点)' '|Δx|,|Δz| ≤ 2 格' '解析不到坐标' '不可判'
            }
        }
    }

    # ---------- 3) 存档迁移：只该发生一次 ----------
    $migV = 'PASS(无需迁移)'
    if ($mig.Count -gt 0) { $migV = Verdict-From ($mig.Count -le 1) }
    Add-Row '存档迁移次数' '≤1 次（重复 = 判据不幂等）' ("$($mig.Count) 次") $migV

    # ---------- 4) 地表天空：四项曾被判死的读数 ----------
    $sky = @($lines | Where-Object { $_.Contains('[Kelvin] [地表天空]') })
    if ($sky.Count -eq 0) {
        Add-Row '地表天空探针' '至少 1 行 [Kelvin] [地表天空]' '0 行' '未出现'
    } else {
        $s = $sky[-1]

        $rate = Num-From $s '天空转速=([\d.]+)°/秒'
        $rateTxt = 'n/a'
        if (-not [double]::IsNaN($rate)) { $rateTxt = "{0:N3}°/秒" -f $rate }
        $rateV = '不可判(n/a ⇒ 判据没跑)'
        if (-not [double]::IsNaN($rate)) { $rateV = Verdict-From ([Math]::Abs($rate - 0.3) -lt 0.05) }
        Add-Row '天空转速' '0.300°/秒（原版同速）' $rateTxt $rateV

        $span = Num-From $s '插值跨度=([\d.eE+-]+) m'
        $spanV = '不可判'
        if (-not [double]::IsNaN($span) -and $span -ne 0) { $spanV = 'PASS' }
        Add-Row '插值跨度(sun)' '> 0（NaN/=0 ⇒ 抽帧或判据坏）' ("{0:E3}" -f $span) $spanV

        $sunDiam = Num-From $s '太阳角直径=([\d.]+)°'
        $sunTxt = '未打印'
        $sunV = '未出现'
        if (-not [double]::IsNaN($sunDiam)) {
            $sunTxt = "{0:N3}°" -f $sunDiam
            $sunV = Verdict-From ([Math]::Abs($sunDiam - 0.533) -lt 0.02)
        }
        Add-Row '太阳角直径' '≈0.53°' $sunTxt $sunV

        $clock = Num-From $s '时钟: ([\d.]+) tick/秒'
        $clockTxt = 'n/a'
        if (-not [double]::IsNaN($clock)) { $clockTxt = "{0:N2}" -f $clock }
        if ($s.Contains('世界暂停/未推进')) {
            $clockV = '不可判(世界暂停)'
        } elseif ([double]::IsNaN($clock)) {
            $clockV = '不可判'
        } else {
            $clockV = Verdict-From ([Math]::Abs($clock - 20.0) -lt 1.0)
        }
        Add-Row '时钟' '≈20 tick/秒（⇒ 一天 20 分钟）' $clockTxt $clockV

        $compassV = '未出现'
        $compassTxt = '未打印'
        if ($s.Contains('罗盘对齐=')) { $compassTxt = '有读数'; $compassV = Verdict-From ($s.Contains('罗盘对齐=PASS')) }
        Add-Row '罗盘对齐' 'PASS' $compassTxt $compassV

        $frameV = '未出现'
        $frameTxt = '未打印'
        if ($s.Contains('姿态帧检查=')) { $frameTxt = '有读数'; $frameV = Verdict-From ($s.Contains('姿态帧检查=PASS')) }
        Add-Row '姿态帧检查' 'PASS' $frameTxt $frameV

        $azV = '未出现'
        $azTxt = '未打印'
        if ($s.Contains('方位检查=')) { $azTxt = '有读数'; $azV = Verdict-From ($s.Contains('方位检查=PASS')) }
        Add-Row '方位检查' 'PASS' $azTxt $azV
    }

    # ---------- 5) 太空视运动：顺滑度（带像素下限） ----------
    $sp = @($lines | Where-Object { $_.Contains('[Kelvin] [太空视运动]') })
    if ($sp.Count -eq 0) {
        Add-Row '太空视运动探针' '至少 1 行 [Kelvin] [太空视运动]' '0 行' '未出现'
    } else {
        $pass = @($sp | Where-Object { $_.Contains('顺滑=PASS') })
        $failJump = @($sp | Where-Object { $_.Contains('★FAIL') -and ((Num-From $_ '最大=([\d.]+)') -gt 0.5) })
        $na = @($sp | Where-Object { $_.Contains('n/a(样本不足)') })
        Add-Row '太空顺滑' '≥1 行 PASS，且无「最大>0.5px 的 ★FAIL」' `
            ("PASS={0} 真跳变={1} 样本不足={2}" -f $pass.Count, $failJump.Count, $na.Count) `
            (Verdict-From ($pass.Count -ge 1 -and $failJump.Count -eq 0))

        $span2 = Num-From $sp[-1] '插值跨度=([\d.eE+-]+) m'
        $span2V = '不可判'
        if (-not [double]::IsNaN($span2) -and $span2 -ne 0) { $span2V = 'PASS' }
        Add-Row '插值跨度(太空)' '> 0（地球 ≈1.08e5 m）' ("{0:E3}" -f $span2) $span2V
    }

    # ---------- 6) 坐标自检（现在会真的比较闸门） ----------
    $chk = @($lines | Where-Object { $_.Contains('[坐标自检]') })
    if ($chk.Count -eq 0) {
        Add-Row '坐标自检' '至少 1 行' '0 行' '未出现'
    } else {
        $ok = $chk[-1].Contains('⇒ PASS')
        $okTxt = '不一致'
        if ($ok) { $okTxt = 'PASS' }
        Add-Row '坐标自检闸门' '静态校验和 = 闸门（⇒ PASS）' $okTxt (Verdict-From $ok)

        $rel = Num-From $chk[-1] '往返最大相对误差=([\d.eE+-]+)'
        $relV = '不可判'
        if (-not [double]::IsNaN($rel)) { $relV = Verdict-From ($rel -le 1.0e-9) }
        Add-Row '换算往返误差' '0（相对）' ("{0:E3}" -f $rel) $relV
    }
}

# ---------------- 自测：内置样例行 ----------------
function Sample-Pass {
    @(
        '[27Sep2026 10:00:00.000] [Server thread/INFO] [com.mss.polymech.Polymech/]: [坐标落点] minecraft:overworld → 太空 | 玩家地表=(0.5, 10000.312500617045, 0.5) | 宇宙系(米)=(1.670127811914102E10, 1.087596717686046E7, -1.4684813291516058E11) | 目标=(1670127.8, 1087.6, -14684813.3) | 约定=缩放(1格=10000米) | 地球参考=(米)(1.53E10, 1.0876018E7, -1.47E11) 半径比=2.2000（期望≈2.20）',
        '[27Sep2026 10:00:04.000] [Server thread/INFO] [com.mss.polymech.Polymech/]: [坐标落点] 太空 → earth | 太空输入(米)=(1.670127811914102E10, 1.087596717686046E7, -1.4684813291516058E11) | 落点=(0, -59, 0) | 约定=缩放(1格=10000米) | 地球参考=(米)(1.53E10, 1.0876018E7, -1.47E11) 半径比=1.0200（期望≈1.02） | 往返: 与去程方向夹角=0.412° ⇒ PASS(与去程同一条径线)',
        '[27Sep2026 10:00:05.000] [Server thread/INFO] [com.mss.polymech.Polymech/]: [坐标迁移] 太空维度检测到**跨约定**坐标 (1.67e10, 1.09e7, -1.47e11)（当前约定=缩放(1格=10000米)，最近天体(方块口径) 1.53e10）⇒ ÷ 10000 搬到 (1.67e6, 1.09e3, -1.47e7)',
        '[27Sep2026 10:00:06.000] [Render thread/INFO] [com.mss.polymech.Polymech/]: [Kelvin] [地表天空] 相机(宇宙系)=(5.006e+10, 1.013e+07, -1.390e+11) | 数学自检: dayTime=6109 时钟角=+1.635° | MC罗盘: 世界东·物理东=+1.0000 世界南·物理南=+1.0000 ⇒ 罗盘对齐=PASS | 姿态帧: 物理东→世界(+1.00,-0.00,-0.00) ⇒ 姿态帧检查=PASS | 时钟: 20.00 tick/秒（=原版 20 ⇒ 一天 20 分钟 ✔） 一天=20.00 分钟 天空转速=0.300°/秒(原版0.300) | 顺滑: 插值跨度=1.076e+05 m （>0 ⇒ 插值在工作，越大越顺） 位姿搬运=380.0 次/秒 帧率=120 fps | 太阳角直径=0.533°（我们的真实值…） | 太阳: 高度角=+73.10° 时角=-90.39° 方位=90.4°(0=北 90=东) ⇒ 方位检查=PASS 屏幕ndc=(+0.10, +0.20)',
        '[27Sep2026 10:00:07.000] [Render thread/INFO] [com.mss.polymech.Polymech/]: [Kelvin] [太空视运动] 最近=earth 距离=1.816e+07 m 角直径=38.671° | 视运动=6.768°/秒 | 每帧位移(px) 均=0.16 最大=0.68 最小=0.09 ⇒ 顺滑=n/a(样本不足) | 插值跨度=1.076e+05 m | 帧率=817 fps',
        '[27Sep2026 10:00:08.000] [Render thread/INFO] [com.mss.polymech.Polymech/]: [Kelvin] [太空视运动] 最近=earth 距离=1.816e+07 m 角直径=38.671° | 视运动=6.768°/秒 | 每帧位移(px) 均=0.16 最大=0.68 最小=0.09 ⇒ 顺滑=PASS(每帧<0.5px) | 插值跨度=1.076e+05 m | 帧率=817 fps',
        '[27Sep2026 10:00:09.000] [Worker-Main-5/INFO] [com.mss.polymech.Polymech/]: [坐标自检] 约定=缩放(1格=10000米) | 往返最大相对误差=0.000e+00 | 静态blockPos校验和=-5411703720350（闸门=-5411703720350 ⇒ PASS） | 实时blockPos校验和=-5411703720350 | 大气数=20'
    )
}

function Sample-Fail {
    $bad = Sample-Pass
    $bad[0] = $bad[0].Replace('半径比=2.2000', '半径比=1.0200')
    $bad[1] = $bad[1] + "`n" + '[27Sep2026 10:00:04.500] [Server thread/INFO] [com.mss.polymech.Polymech/]: [坐标落点] 目标在深空，跳过区块预加载（纯虚空无需预加载）: (1.67e10, 1.09e7, -1.47e11)'
    $bad[3] = $bad[3].Replace('天空转速=0.300°/秒', '天空转速=n/a°/秒').Replace('插值跨度=1.076e+05 m', '插值跨度=NaN m')
    $bad[5] = $bad[5].Replace('顺滑=PASS(每帧<0.5px)', '顺滑=★FAIL(有跳变)').Replace('最大=0.68', '最大=1298.15')
    $bad[6] = $bad[6].Replace('⇒ PASS', '⇒ ★FAIL(静态位置表被改过？Δ=-713039320)')
    return $bad
}

function Show-Report([string]$title) {
    Write-Host ""
    Write-Host ("===== " + $title + " =====") -ForegroundColor Cyan
    $script:rows | Format-Table -AutoSize | Out-String -Width 220 | Write-Host
    $fail = @($script:rows | Where-Object { $_.结论 -like '*FAIL*' }).Count
    $missing = @($script:rows | Where-Object { $_.结论 -eq '未出现' }).Count
    $unknown = @($script:rows | Where-Object { $_.结论 -like '不可判*' }).Count
    $color = 'Green'
    if ($fail -gt 0) { $color = 'Red' } elseif ($missing -gt 0) { $color = 'Yellow' }
    Write-Host ("FAIL=$fail  未出现=$missing  不可判=$unknown") -ForegroundColor $color
    return @($fail, $missing)
}

if ($SelfTest) {
    $script:rows.Clear(); Judge-Lines (Sample-Pass); $a = Show-Report '自测 A：样例应全 PASS'
    $script:rows.Clear(); Judge-Lines (Sample-Fail); $b = Show-Report '自测 B：样例应报出 FAIL'
    $ok = ($a[0] -eq 0 -and $a[1] -eq 0) -and ($b[0] -ge 2)
    $msg = '自测失败：判据本身有问题'
    if ($ok) { $msg = '自测通过：判据既能 PASS 也能 FAIL（不是死判据）' }
    $color = 'Red'
    if ($ok) { $color = 'Green' }
    Write-Host $msg -ForegroundColor $color
    if ($ok) { exit 0 }
    exit 1
}

$lines = Read-LogLines $Log
Write-Host ("读入 " + $lines.Count + " 行: " + $Log)
Judge-Lines $lines
$r = Show-Report '探针判读'
if ($r[0] -gt 0) { exit 1 }
if ($r[1] -gt 0) { exit 2 }
exit 0
