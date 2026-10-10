param([string]$OutputDirectory = '')
$ErrorActionPreference = 'Stop'
$out = Join-Path $PSScriptRoot 'results'
if ($OutputDirectory) { $out = [IO.Path]::GetFullPath($OutputDirectory) }
$ownedServices = @(Get-Content -LiteralPath (Join-Path $out 'service-pids.json') -Raw | ConvertFrom-Json)
$live = @(Get-CimInstance Win32_Process)
$stopped = @()
foreach ($service in $ownedServices) {
    $root = $live | Where-Object ProcessId -eq $service.pid
    if (-not $root) { continue }
    $expectedJar = if ($service.service -eq 'nacos') { 'nacos-server.jar' } else { "$($service.service)-services-0.0.1-SNAPSHOT.jar" }
    if ($root.Name -ne 'java.exe' -or $root.CommandLine -notlike "*$expectedJar*") {
        throw "Recorded PID $($service.pid) no longer matches the owned $($service.service) process"
    }
    $ids = @([int]$root.ProcessId)
    do {
        $children = @($live | Where-Object { $_.ParentProcessId -in $ids -and $_.ProcessId -notin $ids })
        $ids += @($children | ForEach-Object { [int]$_.ProcessId })
    } while ($children.Count -gt 0)
    # Child JVM first; Oracle javapath can leave a launcher parent alive.
    foreach ($ownedPid in @($ids | Sort-Object { if ($_ -eq $root.ProcessId) { 1 } else { 0 } })) {
        $processNow = Get-CimInstance Win32_Process -Filter "ProcessId=$ownedPid"
        if (-not $processNow) { continue }
        $recorded = $live | Where-Object ProcessId -eq $ownedPid
        # Windows creates a conhost child for the launcher; the OS owns its
        # lifetime. Stop only the identified service JVMs and launchers.
        if ($recorded.Name -ne 'java.exe') { continue }
        if (-not $processNow.CommandLine) {
            Start-Sleep -Milliseconds 200
            $processNow = Get-CimInstance Win32_Process -Filter "ProcessId=$ownedPid"
            if (-not $processNow) { continue }
        }
        if ($processNow.CreationDate -ne $recorded.CreationDate -or
            $processNow.Name -ne 'java.exe' -or $processNow.CommandLine -notlike "*$expectedJar*") {
            throw "Process identity changed or unexpected child at PID $ownedPid; stop manually after inspection"
        }
        Stop-Process -Id $ownedPid -ErrorAction Stop
        $stopped += @{service=$service.service;pid=$ownedPid}
    }
}
New-Item -ItemType File -Force -Path (Join-Path $out 'stop-monitor') | Out-Null
Start-Sleep -Seconds 3
$listening = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object LocalPort -in @(8848,9848,9000,9001,9002,9003) | Select-Object LocalPort,OwningProcess)
$result = @{utc=[DateTime]::UtcNow.ToString('o');stopped=$stopped;remainingListening=$listening;
    confirmedRecordedServicesExited=(@(Get-CimInstance Win32_Process | Where-Object ProcessId -in $ownedServices.pid).Count -eq 0);
    freeMemoryGB=[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB,3)}
$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'shutdown-verification.json') -Encoding UTF8
$result | ConvertTo-Json -Depth 5
