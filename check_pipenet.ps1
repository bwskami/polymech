$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-Gz($path) {
    $in = [IO.Compression.GzipStream]::new([IO.File]::OpenRead($path), [IO.Compression.CompressionMode]::Decompress)
    $sr = [IO.StreamReader]::new($in)
    $text = $sr.ReadToEnd()
    $sr.Dispose()
    return $text -split "`r?`n"
}

$files = Get-ChildItem 'run\logs' -Filter '2026-0*.log.gz' |
    Sort-Object { $_.Name -replace '-\d+\.log\.gz$', '' }, { [int]($_.Name -replace '.*-(\d+)\.log\.gz','$1') } |
    Where-Object { $_.Name -match '^2026-(08-0[1-4])' }

foreach ($f in $files) {
    $lines = Read-Gz $f.FullName
    $pipe = ($lines | Select-String -Pattern 'PipeNet' | Measure-Object).Count
    $firstTs = ''; foreach ($l in $lines) { if ($l -match '2026 (\d{2}:\d{2}:\d{2})') { $firstTs = $Matches[1]; break } }
    Write-Output ("{0}  start={1}  pipenet_lines={2}" -f $f.Name, $firstTs, $pipe)
}
