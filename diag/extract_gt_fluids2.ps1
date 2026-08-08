$ErrorActionPreference = 'Continue'
$gtRoot = 'C:\Users\34573\Desktop\GregTech-Modern-1.21'
$matDir = Join-Path $gtRoot 'src\main\java\com\gregtechceu\gtceu\common\data\materials'
$files = @(Get-ChildItem $matDir -Filter '*.java')
$files += Get-Item (Join-Path $gtRoot 'src\main\java\com\gregtechceu\gtceu\common\data\GTMaterials.java')

# ---- 1. parse elements: name -> symbol ----
$elemText = [IO.File]::ReadAllText((Join-Path $gtRoot 'src\main\java\com\gregtechceu\gtceu\common\data\GTElements.java'))
$elemSym = @{}
$elemRx = [regex]'public static final Element (\w+) = createAndRegister\(GTCEu\.id\("([^"]+)"\),[\s\S]*?"([^"]+)",\s*"([^"]+)",\s*(?:true|false)\s*\)'
foreach ($m in $elemRx.Matches($elemText)) {
    # groups: 1=field, 2=id name, 3=display name, 4=symbol
    $sym = $m.Groups[4].Value -replace '-', ''
    $elemSym[$m.Groups[2].Value] = $sym
    # also map Java field name (e.g. GTElements.Al) to symbol
    $elemSym[$m.Groups[1].Value] = $sym
}
Write-Output ("ELEMENTS: " + $elemSym.Count)

# ---- 2. parse all material definitions ----
$mats = @{}
$builderRx = [regex]'(?s)(\w+)\s*=\s*new Material\.Builder\(\s*GTCEu\.id\(\s*"([a-z0-9_]+)"\s*\)\s*\)(.*?)(?=\w+\s*=\s*new Material\.Builder|\z)'
foreach ($f in $files) {
    $text = [IO.File]::ReadAllText($f.FullName)
    foreach ($m in $builderRx.Matches($text)) {
        $field = $m.Groups[1].Value
        $name = $m.Groups[2].Value
        $body = $m.Groups[3].Value
        $end = $body.IndexOf('.buildAndRegister()')
        if ($end -ge 0) { $body = $body.Substring(0, $end) }
        $kind = $null
        if ($body -match '\.gas\s*\(') { $kind = 'gas' }
        elseif ($body -match '\.liquid\s*\(') { $kind = 'liquid' }
        if ($body -match '\.plasma\s*\(') { $kind = ($(if ($kind) { $kind } else { 'liquid' }) + '+plasma') }
        $temp = ''
        if ($body -match 'temperature\s*\(\s*(\d+)\s*\)') { $temp = $Matches[1] }
        $color = ''
        if ($body -match '\.color\s*\(\s*(0x[0-9A-Fa-f]+)\s*\)') { $color = $Matches[1] }
        $element = ''
        if ($body -match '\.element\s*\(\s*GTElements\.(\w+)\s*\)') { $element = $Matches[1] }
        $comps = New-Object System.Collections.ArrayList
        $cm = [regex]::Match($body, '(?s)\.components\s*\(([^\)]*)\)')
        if ($cm.Success) {
            $cargs = $cm.Groups[1].Value
            $parts = $cargs -split ','
            $i = 0
            while ($i + 1 -lt $parts.Count) {
                $ref = $parts[$i].Trim()
                $cnt = $parts[$i + 1].Trim()
                $refm = [regex]::Match($ref, '(?:GTMaterials\.)?(\w+)$')
                if ($refm.Success -and $cnt -match '^\d+$') {
                    [void]$comps.Add(@{ name = $refm.Groups[1].Value; count = [int]$cnt })
                }
                $i += 2
            }
        }
        $mats[$name] = @{ field = $field; kind = $kind; temp = $temp; color = $color; element = $element; comps = $comps }
    }
}
Write-Output ("MATERIALS: " + $mats.Count)

# field reference name (NitricAcid) -> material name (nitric_acid)
$fieldToMat = @{}
foreach ($kv in $mats.GetEnumerator()) { $fieldToMat[$kv.Value.field] = $kv.Name }

# ---- 3. recursive formula computation ----
$visiting = @{}
function Get-ElementCounts([string]$name) {
    $result = @{}
    if (-not $mats.ContainsKey($name)) { return ,$result }
    if ($script:visiting.ContainsKey($name)) { return ,$result }
    $script:visiting[$name] = $true
    try {
        $mat = $mats[$name]
        if ($mat.element -and $elemSym.ContainsKey($mat.element)) {
            $sym = $elemSym[$mat.element]
            if ($result.ContainsKey($sym)) { $result[$sym] += 1 } else { $result[$sym] = 1 }
            return ,$result
        }
        foreach ($c in $mat.comps) {
            $cname = if ($fieldToMat.ContainsKey($c.name)) { $fieldToMat[$c.name] } else { $null }
            if (-not $cname) { continue }
            $sub = Get-ElementCounts $cname
            foreach ($kv in $sub.GetEnumerator()) {
                if ($result.ContainsKey($kv.Key)) { $result[$kv.Key] += $kv.Value * $c.count }
                else { $result[$kv.Key] = $kv.Value * $c.count }
            }
        }
    } finally {
        $script:visiting.Remove($name)
    }
    return ,$result
}

# Hill order: C, H, then alphabetical
function Format-Formula($counts) {
    if (-not $counts -or @($counts.Keys).Count -eq 0) { return '' }
    $keys = @($counts.Keys)
    $ordered = @()
    if ($keys -contains 'C') { $ordered += 'C' }
    if ($keys -contains 'H') { $ordered += 'H' }
    $ordered += ($keys | Where-Object { $_ -ne 'C' -and $_ -ne 'H' } | Sort-Object)
    $sb = New-Object System.Text.StringBuilder
    foreach ($k in $ordered) {
        [void]$sb.Append($k)
        if ($counts[$k] -gt 1) { [void]$sb.Append([string]$counts[$k]) }
    }
    return $sb.ToString()
}

# ---- 4. load en/zh lang files ----
$enLang = Get-Content (Join-Path $gtRoot 'src\generated\resources\assets\gtceu\lang\en_us.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$zhPath = Join-Path $gtRoot 'src\main\resources\assets\gtceu\lang\zh_cn.json'
$zhLang = $null
if (Test-Path $zhPath) { $zhLang = Get-Content $zhPath -Raw -Encoding UTF8 | ConvertFrom-Json }

# ---- 5. output fluid materials TSV ----
$lines = New-Object System.Collections.ArrayList
[void]$lines.Add("name`tfield`tkind`ttemp`tcolor`telement`tformula`ten`tzh")
$fluidCount = 0
$emptyFormula = New-Object System.Collections.ArrayList
foreach ($name in ($mats.Keys | Sort-Object)) {
    $mat = $mats[$name]
    if (-not $mat.kind) { continue }
    $fluidCount++
    $formula = Format-Formula (Get-ElementCounts $name)
    if (-not $formula) { [void]$emptyFormula.Add($name) }
    $enKey = 'material.gtceu.' + $name
    $en = [string]$enLang.$enKey
    if (-not $en) { $en = '' }
    $zh = ''
    if ($zhLang) { $zh = [string]$zhLang.$enKey; if (-not $zh) { $zh = '' } }
    [void]$lines.Add(($name + "`t" + $mat.field + "`t" + $mat.kind + "`t" + $mat.temp + "`t" + $mat.color + "`t" + $mat.element + "`t" + $formula + "`t" + $en + "`t" + $zh))
}
[IO.File]::WriteAllLines("$env:TEMP\gt_fluids_full.tsv", $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Output ("FLUID MATERIALS: " + $fluidCount)
Write-Output ("EMPTY FORMULA (" + $emptyFormula.Count + "): " + ($emptyFormula -join ','))
Write-Output ("SAVED: " + "$env:TEMP\gt_fluids_full.tsv")
