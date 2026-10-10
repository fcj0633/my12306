[CmdletBinding()]
param([string]$OutputDirectory = '', [string]$TicketJavaAgent = '', [ValidateSet('Original','Compact')][string]$HeapProfile = 'Original')
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$projectRoot = Join-Path $repoRoot '12306/my12306'
$out = Join-Path $PSScriptRoot 'results'
if ($OutputDirectory) { $out = [IO.Path]::GetFullPath($OutputDirectory) }
New-Item -ItemType Directory -Force -Path $out | Out-Null
$freeGB = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB
Write-Output ('Available memory before sequential startup: {0:N2} GB (fixed 4 GB gate removed by user).' -f $freeGB)
foreach ($port in @(8848,9848,9000,9001,9002,9003)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        throw "Port already occupied: $port"
    }
}
$java = 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe'
$nacosRoot = 'D:/Administrator/mini-softs/nacos'
$nacosHeap = if ($HeapProfile -eq 'Compact') { 192 } else { 256 }
$argsNacos = @('-Xms64m',"-Xmx${nacosHeap}m",'-Xss512k',"-Xlog:gc:file=$out/nacos.gc.log",'-Djdk.net.unixdomain.tmpdir=Z:\disable-af-unix',
 '-Dnacos.standalone=true',"-Dnacos.home=$nacosRoot",'-jar',"$nacosRoot/target/nacos-server.jar",
 "--spring.config.additional-location=file:$nacosRoot/conf/")
$started = @()
try {
    $process = Start-Process -FilePath $java -ArgumentList $argsNacos -WorkingDirectory $nacosRoot -WindowStyle Hidden -PassThru `
       -RedirectStandardOutput (Join-Path $out 'nacos.stdout.log') -RedirectStandardError (Join-Path $out 'nacos.stderr.log')
    $started += @{service='nacos';pid=$process.Id}
    $started | ConvertTo-Json | Set-Content (Join-Path $out 'service-pids.json') -Encoding UTF8
    $deadline = (Get-Date).AddSeconds(180)
    do {
        if ($process.HasExited) { throw 'Nacos exited; inspect results logs' }
        try { $health = Invoke-RestMethod 'http://127.0.0.1:8848/nacos/v1/console/health/readiness' -TimeoutSec 2; break } catch { Start-Sleep -Seconds 2 }
    } while ((Get-Date) -lt $deadline)
    if ((Get-Date) -ge $deadline) { throw 'Nacos readiness timed out' }
    $env:MY12306_PAY_NOTIFY_MODE='feign'
    $env:MY12306_WORKER_ID='1'
    foreach ($service in @('user','order','ticket','gateway')) {
        $jar = "services/$service-services/target/$service-services-0.0.1-SNAPSHOT.jar"
        # Quote jar paths explicitly: Windows joins ArgumentList into a command line.
        $heapMB = @{user=256;order=192;ticket=256;gateway=160}[$service]
        if ($HeapProfile -eq 'Compact') { $heapMB = @{user=192;order=160;ticket=192;gateway=128}[$service] }
        $serviceArgs = @('-Xms64m',"-Xmx${heapMB}m",'-Xss512k',"-Xlog:gc:file=$out/$service.gc.log",'-Djdk.net.unixdomain.tmpdir=Z:\disable-af-unix','-jar',$jar,
            '--my12306.ticket.orphan-scan-enabled=false','--my12306.order.fallback-scan-enabled=false')
        if ($service -eq 'ticket' -and $TicketJavaAgent) {
            $agentDirectory = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($out))
            $serviceArgs = @("-javaagent:${TicketJavaAgent}=b64:$agentDirectory") + $serviceArgs
        }
        $process = Start-Process -FilePath $java -ArgumentList $serviceArgs -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $out "$service.stdout.log") -RedirectStandardError (Join-Path $out "$service.stderr.log")
        $started += @{service=$service;pid=$process.Id}
        $started | ConvertTo-Json | Set-Content (Join-Path $out 'service-pids.json') -Encoding UTF8
        $port = @{user=9001;order=9003;ticket=9002;gateway=9000}[$service]
        $deadline = (Get-Date).AddSeconds(180)
        $ready = $false
        do {
            if ($process.HasExited) { throw "$service exited; inspect results logs" }
            try {
                $health = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2
                if ($health.status -eq 'UP') { $ready=$true; break }
            } catch { Start-Sleep -Seconds 2 }
        } while ((Get-Date) -lt $deadline)
        if (-not $ready) { throw "$service readiness timed out" }
        Write-Output "$service ready on $port"
    }
} catch {
    foreach ($item in $started) { Stop-Process -Id $item.pid -ErrorAction SilentlyContinue }
    throw
}
