$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-Gz($path) {
    $in = [IO.Compression.GzipStream]::new([IO.File]::OpenRead($path), [IO.Compression.CompressionMode]::Decompress)
    $sr = [IO.StreamReader]::new($in)
    $text = $sr.ReadToEnd()
    $sr.Dispose()
    return $text -split "`r?`n"
}

$milestones = @(
    'ModLauncher running',
    'Starting minecraft client version',
    'Backend library: LWJGL',
    'Sound engine started',
    'blocks.png-atlas',
    'Loaded 0 entity animations',
    'Loaded \d+ recipes',
    'Loaded \d+ advancements',
    'Starting integrated minecraft server',
    'Preparing spawn area',
    'Time elapsed',
    'logged in with entity id',
    'Server shut down'
)

function Dump($name, $lines) {
    Write-Output "===== $name (lines=$($lines.Count)) ====="
    foreach ($line in $lines) {
        if ($line -notmatch '2026 (\d{2}:\d{2}:\d{2}[.,]\d{3})\]') { continue }
        $ts = $Matches[1]
        foreach ($m in $milestones) {
            if ($line -match $m) {
                Write-Output ("{0} | {1}" -f $ts, $line.Substring(0, [Math]::Min(130, $line.Length)))
                break
            }
        }
    }
    Write-Output ''
}

$logs = Get-ChildItem 'run\logs' -Filter '2026-08-04-*.log.gz' | Sort-Object { [int]($_.Name -replace '.*-(\d+)\.log\.gz','$1') }
foreach ($f in $logs) {
    Dump $f.Name (Read-Gz $f.FullName)
}
