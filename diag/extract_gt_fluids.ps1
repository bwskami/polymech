$srcRoot = 'C:\Users\34573\Desktop\GregTech-Modern-1.21\src\main\java\com\gregtechceu\gtceu\common\data'
$files = Get-ChildItem (Join-Path $srcRoot 'materials') -Filter '*.java'
$files += Get-Item (Join-Path $srcRoot 'GTMaterials.java')

# 字段名 -> 材料名（用于 components 解析）
$fieldToName = @{}
$defs = @{}   # name -> @{fluid=kind; comps=raw string; file=...}

foreach ($f in $files) {
    $text = [IO.File]::ReadAllText($f.FullName)
    $chunks = $text -split '(?=\w+\s*=\s*new Material\.Builder)'
    foreach ($chunk in $chunks) {
        if ($chunk -match '^\s*(\w+)\s*=\s*new Material\.Builder\(\s*GTCEu\.id\(\s*"([a-z0-9_]+)"\s*\)') {
            $field = $Matches[1]
            $name = $Matches[2]
            $fieldToName[$field] = $name
            $end = $chunk.IndexOf('.buildAndRegister()')
            if ($end -lt 0) { $end = $chunk.Length }
            $body = $chunk.Substring(0, $end)
            $kind = $null
            if ($body -match '\.liquid\s*\(') { $kind = 'liquid' }
            if ($body -match '\.gas\s*\(') { $kind = 'gas' }
            if ($body -match '\.plasma\s*\(') { $kind = ($kind + '+plasma').TrimStart('+') }
            $comps = $null
            if ($body -match '\.components\((.*?)\)\s*(\r?\n\s*\.\w|\r?\n\s*$)' -or $body -match '\.components\(([^\)]*)\)') {
                $comps = $Matches[1]
            }
            $defs[$name] = @{ field = $field; kind = $kind; comps = $comps; file = $f.Name }
        }
    }
}

Write-Output ("MATERIALS TOTAL: " + $defs.Count)
$fluidOnes = $defs.GetEnumerator() | Where-Object { $_.Value.kind } | Sort-Object Name
Write-Output ("FLUID MATERIALS: " + @($fluidOnes).Count)

# 输出：name | kind | comps（字段名形式）
$lines = @()
foreach ($e in $fluidOnes) {
    $lines += ($e.Name + "`t" + $e.Value.kind + "`t" + $e.Value.comps)
}
$lines | Out-File "$env:TEMP\gt_fluid_defs.txt" -Encoding utf8
$fieldMapLines = @()
foreach ($k in $fieldToName.Keys) { $fieldMapLines += ($k + "=" + $fieldToName[$k]) }
$fieldMapLines | Out-File "$env:TEMP\gt_field_map.txt" -Encoding utf8
Write-Output "SAVED"
