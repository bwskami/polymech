$gtRoot = 'C:\Users\34573\Desktop\GregTech-Modern-1.21'
$files = Get-ChildItem (Join-Path $gtRoot 'src\main\java\com\gregtechceu\gtceu\common\data\materials') -Filter '*.java'
foreach ($f in $files) {
    $text = [IO.File]::ReadAllText($f.FullName)
    if ($text -match 'nitric_oxide') {
        $idx = $text.IndexOf('"nitric_oxide"')
        $chunk = $text.Substring($idx, [Math]::Min(900, $text.Length - $idx))
        Write-Output ("FILE: " + $f.Name)
        Write-Output $chunk
        Write-Output '====='
        $cm = [regex]::Match($chunk, '(?s)\.components\s*\(([^\)]*)\)')
        Write-Output ("CM success: " + $cm.Success)
        if ($cm.Success) {
            $parts = $cm.Groups[1].Value -split ','
            Write-Output ("PARTS COUNT: " + $parts.Count)
            for ($i = 0; $i -lt $parts.Count; $i++) { Write-Output ("  [$i] '" + $parts[$i].Trim() + "'") }
        }
    }
}
