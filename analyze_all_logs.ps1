$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-Gz($path) {
    $in = [IO.Compression.GzipStream]::new([IO.File]::OpenRead($path), [IO.Compression.CompressionMode]::Decompress)
    $sr = [IO.StreamReader]::new($in)
    $text = $sr.ReadToEnd()
    $sr.Dispose()
    return $text -split "`r?`n"
}

# per-session phase summary: B = LWJGL->Sound (model bake), C = Sound->Recipes (datapack check), D = Advancements->IntegratedServer
$files = Get-ChildItem 'run\logs' -Filter '2026-0*.log.gz'
$rows = @()
foreach ($f in $files) {
    $lines = Read-Gz $f.FullName
    $lwjgl = $null; $sound = $null; $rec = $null; $adv = $null; $srv = $null; $login = $null
    foreach ($line in $lines) {
        if ($line -notmatch '2026 (\d{2}):(\d{2}):(\d{2})[.,](\d{3})\]') { continue }
        $sec = [int]$Matches[1] * 3600 + [int]$Matches[2] * 60 + [int]$Matches[3] + [int]$Matches[4] / 1000.0
        if ($line -match 'Backend library: LWJGL' -and $null -eq $lwjgl) { $lwjgl = $sec }
        elseif ($line -match 'Sound engine started' -and $null -eq $sound) { $sound = $sec }
        elseif ($line -match 'Loaded \d+ recipes' -and $null -eq $rec) { $rec = $sec }
        elseif ($line -match 'Loaded \d+ advancements' -and $null -eq $adv) { $adv = $sec }
        elseif ($line -match 'Starting integrated minecraft server' -and $null -eq $srv) { $srv = $sec }
        elseif ($line -match 'logged in with entity id' -and $null -eq $login) { $login = $sec }
    }
    if ($null -ne $lwjgl) {
        $b = if ($sound) { [math]::Round($sound - $lwjgl, 1) } else { -1 }
        $c = if ($sound -and $rec) { [math]::Round($rec - $sound, 1) } else { -1 }
        $d = if ($adv -and $srv) { [math]::Round($srv - $adv, 1) } else { -1 }
        $rows += [pscustomobject]@{ log = $f.Name; date = ($f.Name -replace '-\d+\.log\.gz$',''); start = [int]$lwjgl; B_modelBake_s = $b; C_datapack_s = $c; D_openWorld_s = $d }
    }
}
$rows | Sort-Object date, start | Format-Table -AutoSize | Out-String
