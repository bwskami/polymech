$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-Gz($path) {
    $in = [IO.Compression.GzipStream]::new([IO.File]::OpenRead($path), [IO.Compression.CompressionMode]::Decompress)
    $sr = [IO.StreamReader]::new($in)
    $text = $sr.ReadToEnd()
    $sr.Dispose()
    return $text -split "`r?`n"
}

function Norm($line) {
    # strip timestamp + volatile parts
    $s = $line -replace '^\[[^\]]*\]\s*', ''
    $s = $s -replace '\b\d+\.\d{3}\b', 'N'
    $s = $s -replace '\b0x[0-9a-fA-F]+\b', '0xN'
    return $s
}

function Strip($line) {
    $s = $line -replace '^\[[^\]]*\]\s*', ''
    $s = $s -replace '\b\d{2}:\d{2}:\d{2}[.,]\d{3}\b', ''
    return $s
}

# slow run 08:45 vs fast run 07:47
$slow = Read-Gz 'run\logs\2026-08-04-8.log.gz'
$fast = Read-Gz 'run\logs\2026-08-04-10.log.gz'

$fastSet = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($l in $fast) { [void]$fastSet.Add((Norm $l)) }

Write-Output '===== LINES ONLY IN SLOW RUN (08:45, 2026-08-04-8) ====='
$seen = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($l in $slow) {
    $n = Norm $l
    if (-not $fastSet.Contains($n) -and -not $seen.Contains($n)) {
        [void]$seen.Add($n)
        Write-Output (Strip $l)
    }
}

$slowSet = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($l in $slow) { [void]$slowSet.Add((Norm $l)) }

Write-Output ''
Write-Output '===== LINES ONLY IN FAST RUN (07:47, 2026-08-04-10) ====='
$seen2 = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($l in $fast) {
    $n = Norm $l
    if (-not $slowSet.Contains($n) -and -not $seen2.Contains($n)) {
        [void]$seen2.Add($n)
        Write-Output (Strip $l)
    }
}
