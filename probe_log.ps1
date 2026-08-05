$ErrorActionPreference = 'Stop'

function Read-Gz($path) {
    $in = [IO.Compression.GzipStream]::new([IO.File]::OpenRead($path), [IO.Compression.CompressionMode]::Decompress)
    $sr = [IO.StreamReader]::new($in)
    $text = $sr.ReadToEnd()
    $sr.Dispose()
    return $text -split "`r?`n"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$lines = Read-Gz 'run\logs\2026-08-04-2.log.gz'
Write-Output ('total lines = ' + $lines.Count)
for ($i = 0; $i -lt 3; $i++) {
    $l = $lines[$i]
    Write-Output ('--- line ' + $i + ' ---')
    Write-Output ('codes: ' + ([int[]][char[]]$l.Substring(0, [Math]::Min(25, $l.Length)) -join ','))
    Write-Output ('text: ' + $l.Substring(0, [Math]::Min(120, $l.Length)))
}
