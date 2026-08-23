# PolyMech 矿石系统贴图生成器
# 生成染色模板贴图（灰度底图，运行时按 colors.json 的材料配色染色）：
#   - 粗矿物品三层模板：raw_ore / raw_ore_secondary / raw_ore_overlay
#   - 矿石方块矿脉层：ore_speckles_primary / ore_speckles_secondary
# 纯 .NET 实现（手写 PNG 编码），无第三方依赖，可重复执行。

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$itemDir = Join-Path $projectRoot 'src\main\resources\assets\poly_mech\textures\item\material_sets\raw_ore'
$blockDir = Join-Path $projectRoot 'src\main\resources\assets\poly_mech\textures\block\ore'
New-Item -ItemType Directory -Force -Path $itemDir | Out-Null
New-Item -ItemType Directory -Force -Path $blockDir | Out-Null

# ---------- CRC32 / Adler32 / PNG 编码 ----------

function Get-CRC32([byte[]]$data) {
    [uint32]$crc = [uint32]::MaxValue
    foreach ($b in $data) {
        $crc = $crc -bxor $b
        for ($i = 0; $i -lt 8; $i++) {
            if ($crc -band 1) { $crc = ($crc -shr 1) -bxor ([uint32]3988292384) } else { $crc = $crc -shr 1 }
        }
    }
    return $crc -bxor ([uint32]::MaxValue)
}

function Get-Adler32([byte[]]$data) {
    [uint32]$a = 1; [uint32]$b = 0
    foreach ($x in $data) {
        $a = ($a + $x) % 65521
        $b = ($b + $a) % 65521
    }
    return ($b -shl 16) -bor $a
}

function Write-PngChunk([System.IO.MemoryStream]$ms, [string]$type, [byte[]]$data) {
    $len = [byte[]]@(
        (($data.Length -shr 24) -band 0xFF),
        (($data.Length -shr 16) -band 0xFF),
        (($data.Length -shr 8) -band 0xFF),
        ($data.Length -band 0xFF))
    $ms.Write($len, 0, 4)
    $typeBytes = [System.Text.Encoding]::ASCII.GetBytes($type)
    $ms.Write($typeBytes, 0, 4)
    if ($data.Length -gt 0) { $ms.Write($data, 0, $data.Length) }
    $crcInput = New-Object byte[] ($data.Length + 4)
    [Array]::Copy($typeBytes, 0, $crcInput, 0, 4)
    [Array]::Copy($data, 0, $crcInput, 4, $data.Length)
    [uint32]$crc = Get-CRC32 $crcInput
    $crcBytes = [byte[]]@(
        (($crc -shr 24) -band 0xFF),
        (($crc -shr 16) -band 0xFF),
        (($crc -shr 8) -band 0xFF),
        ($crc -band 0xFF))
    $ms.Write($crcBytes, 0, 4)
}

function Save-Png([string]$path, [byte[]]$rgba, [int]$size) {
    $ms = New-Object System.IO.MemoryStream
    # PNG 签名
    $sig = [byte[]]@(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    $ms.Write($sig, 0, 8)
    # IHDR：8位深 RGBA，无隔行扫描
    $ihdr = [byte[]]@(
        (($size -shr 24) -band 0xFF), (($size -shr 16) -band 0xFF), (($size -shr 8) -band 0xFF), ($size -band 0xFF),
        (($size -shr 24) -band 0xFF), (($size -shr 16) -band 0xFF), (($size -shr 8) -band 0xFF), ($size -band 0xFF),
        8, 6, 0, 0, 0)
    Write-PngChunk $ms 'IHDR' $ihdr
    # 原始扫描线（每行前置过滤字节 0）→ zlib(0x78 0x01 + deflate + adler32)
    # RGBA 每行实际字节数 = 宽×4，扫描线 = 过滤字节1 + stride
    $stride = $size * 4
    $raw = New-Object byte[] (($stride + 1) * $size)
    for ($y = 0; $y -lt $size; $y++) {
        $raw[$y * ($stride + 1)] = 0
        [Array]::Copy($rgba, $y * $stride, $raw, $y * ($stride + 1) + 1, $stride)
    }
    [uint32]$adler = Get-Adler32 $raw
    $deflateMs = New-Object System.IO.MemoryStream
    $deflate = New-Object System.IO.Compression.DeflateStream($deflateMs, [System.IO.Compression.CompressionMode]::Compress, $true)
    $deflate.Write($raw, 0, $raw.Length)
    $deflate.Dispose()
    $idat = New-Object byte[] ($deflateMs.Length + 6)
    $idat[0] = 0x78; $idat[1] = 0x01
    [Array]::Copy($deflateMs.ToArray(), 0, $idat, 2, $deflateMs.Length)
    $idat[$idat.Length - 4] = ($adler -shr 24) -band 0xFF
    $idat[$idat.Length - 3] = ($adler -shr 16) -band 0xFF
    $idat[$idat.Length - 2] = ($adler -shr 8) -band 0xFF
    $idat[$idat.Length - 1] = $adler -band 0xFF
    Write-PngChunk $ms 'IDAT' $idat
    Write-PngChunk $ms 'IEND' ([byte[]]@())
    [System.IO.File]::WriteAllBytes($path, $ms.ToArray())
    $ms.Dispose()
}

# ---------- 像素画解析 ----------

function Convert-MapToRgba([string[]]$map, [int]$gray, [int]$alpha) {
    $size = $map.Count
    $rgba = New-Object byte[] ($size * $size * 4)
    for ($y = 0; $y -lt $size; $y++) {
        $row = $map[$y]
        if ($row.Length -ne $size) { throw "第 $y 行宽度错误: $row" }
        for ($x = 0; $x -lt $size; $x++) {
            if ($row[$x] -eq '#') {
                $i = ($y * $size + $x) * 4
                $rgba[$i] = $gray; $rgba[$i + 1] = $gray; $rgba[$i + 2] = $gray; $rgba[$i + 3] = $alpha
            }
        }
    }
    return ,$rgba
}

# 粗矿主体：两簇矿块（16x16）
$rawBase = @(
    '................',
    '..###...........',
    '.#####...###....',
    '.#####..#####...',
    '..###...#####...',
    '.........###....',
    '................',
    '...#########....',
    '..###########...',
    '..###########...',
    '..###########...',
    '...#########....',
    '....#######.....',
    '................',
    '................',
    '................'
)

function Get-ShiftedAnd([string[]]$base, [int]$dx, [int]$dy) {
    # 结果(x,y) = base(x-dx, y-dy) && base(x, y)
    $size = $base.Count
    $rows = @()
    for ($y = 0; $y -lt $size; $y++) {
        $chars = New-Object char[] $size
        for ($x = 0; $x -lt $size; $x++) { $chars[$x] = '.' }
        for ($x = 0; $x -lt $size; $x++) {
            $sx = $x - $dx; $sy = $y - $dy
            if ($sx -ge 0 -and $sx -lt $size -and $sy -ge 0 -and $sy -lt $size) {
                if ($base[$y][$x] -eq '#' -and $base[$sy][$sx] -eq '#') { $chars[$x] = '#' }
            }
        }
        $rows += -join $chars
    }
    return $rows
}

function Get-TopLeftEdge([string[]]$base) {
    # 左上轮廓高光：base(x,y) 且 base(x-1,y-1) 不存在
    $size = $base.Count
    $rows = @()
    for ($y = 0; $y -lt $size; $y++) {
        $chars = New-Object char[] $size
        for ($x = 0; $x -lt $size; $x++) { $chars[$x] = '.' }
        for ($x = 0; $x -lt $size; $x++) {
            if ($base[$y][$x] -eq '#') {
                $sx = $x - 1; $sy = $y - 1
                $filled = $sx -ge 0 -and $sy -ge 0 -and $base[$sy][$sx] -eq '#'
                if (-not $filled) { $chars[$x] = '#' }
            }
        }
        $rows += -join $chars
    }
    return $rows
}

# 暗部 = 主体向右下平移1格后与原图取交集（右下内阴影）
$rawSecondary = Get-ShiftedAnd $rawBase 1 1
# 高光 = 主体左上轮廓
$rawOverlay = Get-TopLeftEdge $rawBase

# ---------- 矿石方块矿脉纹理（两层，像素互不重叠） ----------

$specklesPrimary = @(
    '................',
    '...##...........',
    '..###...........',
    '...#............',
    '................',
    '.........##.....',
    '........###.....',
    '.........#......',
    '................',
    '..##............',
    '.###............',
    '..#.......##....',
    '.........###....',
    '..........#.....',
    '................',
    '................'
)

$specklesSecondary = @(
    '................',
    '.....#..........',
    '................',
    '................',
    '...........#....',
    '................',
    '................',
    '................',
    '....#...........',
    '..........#.....',
    '................',
    '....#...........',
    '................',
    '................',
    '..........#.....',
    '................'
)

# 断言两层矿脉像素不重叠（避免共面 z-fighting）
for ($y = 0; $y -lt 16; $y++) {
    for ($x = 0; $x -lt 16; $x++) {
        if ($specklesPrimary[$y][$x] -eq '#' -and $specklesSecondary[$y][$x] -eq '#') {
            throw "矿脉纹理重叠于 ($x, $y)"
        }
    }
}

# ---------- 输出 ----------

Save-Png (Join-Path $itemDir 'raw_ore.png')            (Convert-MapToRgba $rawBase 0xD0 0xFF) 16
Save-Png (Join-Path $itemDir 'raw_ore_secondary.png')  (Convert-MapToRgba $rawSecondary 0x78 0xFF) 16
Save-Png (Join-Path $itemDir 'raw_ore_overlay.png')    (Convert-MapToRgba $rawOverlay 0xFF 0xFF) 16
Save-Png (Join-Path $blockDir 'ore_speckles_primary.png')   (Convert-MapToRgba $specklesPrimary 0xFF 0xFF) 16
Save-Png (Join-Path $blockDir 'ore_speckles_secondary.png') (Convert-MapToRgba $specklesSecondary 0xFF 0xFF) 16

Write-Host "OK: 已生成 5 张贴图"
Get-ChildItem $itemDir, $blockDir -Filter *.png | ForEach-Object { Write-Host ("  " + $_.FullName + " (" + $_.Length + " bytes)") }
