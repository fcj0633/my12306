param([string]$OutputDirectory = '', [int]$IntervalSeconds = 2)
$ErrorActionPreference = 'Stop'
$out = Join-Path $PSScriptRoot 'results'
if ($OutputDirectory) { $out = [IO.Path]::GetFullPath($OutputDirectory) }
$stopFile = Join-Path $out 'stop-monitor'
$logFile = Join-Path $out 'machine-resources.jsonl'
while (-not (Test-Path -LiteralPath $stopFile)) {
    $os = Get-CimInstance Win32_OperatingSystem
    $memory = Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
    $cpu = Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'"
    $processes = @(Get-Process java,mysqld -ErrorAction SilentlyContinue | ForEach-Object {
        @{pid=$_.Id;name=$_.ProcessName;workingSetMB=[math]::Round($_.WorkingSet64/1MB,2);
          privateMB=[math]::Round($_.PrivateMemorySize64/1MB,2);cpuSeconds=$_.CPU;threads=$_.Threads.Count}
    })
    @{utc=[DateTime]::UtcNow.ToString('o');freeMemoryGB=[math]::Round($os.FreePhysicalMemory/1MB,3);
      cpuPercent=$cpu.PercentProcessorTime;pagesInputPerSec=$memory.PagesInputPersec;
      pageReadsPerSec=$memory.PageReadsPersec;processes=$processes} |
        ConvertTo-Json -Depth 5 -Compress | Add-Content -LiteralPath $logFile -Encoding UTF8
    Start-Sleep -Seconds $IntervalSeconds
}
