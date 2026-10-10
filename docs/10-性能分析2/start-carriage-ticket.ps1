param([int]$Port = 9002, [int]$WorkerId = 1)
$ErrorActionPreference = 'Stop'
$benchOut = Join-Path $PSScriptRoot 'results/carriage'
if ($Port -notin @(9002,9012)) { throw 'Unexpected test port' }
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw 'Ticket port occupied' }
$logPrefix = if ($Port -eq 9002) { 'ticket' } else { 'ticket2' }
$agentOut = if ($Port -eq 9002) { $benchOut } else { Join-Path $benchOut 'second' }
New-Item -ItemType Directory -Force -Path $agentOut | Out-Null
$env:MY12306_PAY_NOTIFY_MODE='feign'
$env:MY12306_WORKER_ID="$WorkerId"
$env:MY12306_TICKET_PORT="$Port"
$encodedDirectory=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($agentOut))
$agentPath=Join-Path $benchOut 'stage-agent.jar'
$gcPath=Join-Path $benchOut "$logPrefix.gc.log"
$ticketArgs=@('-Xms64m','-Xmx256m','-Xss512k',"-Xlog:gc:file=$gcPath",
    '-Djdk.net.unixdomain.tmpdir=Z:\disable-af-unix',"-javaagent:${agentPath}=b64:$encodedDirectory",
    '-jar','services/ticket-services/target/ticket-services-0.0.1-SNAPSHOT.jar',
    '--my12306.ticket.orphan-scan-enabled=false','--my12306.order.fallback-scan-enabled=false')
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '../../12306/my12306')).Path
$process=Start-Process 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe' -ArgumentList $ticketArgs -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $benchOut "$logPrefix.stdout.log") -RedirectStandardError (Join-Path $benchOut "$logPrefix.stderr.log")
$records=@(Get-Content (Join-Path $benchOut 'service-pids.json') | ConvertFrom-Json)
if ($Port -eq 9002) { ($records | Where-Object service -eq 'ticket').pid=$process.Id }
else { $records += @{service='ticket2';pid=$process.Id} }
$records | ConvertTo-Json | Set-Content (Join-Path $benchOut 'service-pids.json') -Encoding UTF8
$deadline=(Get-Date).AddSeconds(120)
$ready=$false
do {
    if ($process.HasExited) { throw 'Ticket exited; inspect test logs' }
    try { $health=Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2; if ($health.status -eq 'UP') { $ready=$true;break } }
    catch { Start-Sleep -Seconds 2 }
} while ((Get-Date) -lt $deadline)
if (-not $ready) { Stop-Process -Id $process.Id;throw 'Ticket readiness timed out' }
Write-Output "Ticket ready port=$Port workerId=$WorkerId pid=$($process.Id)"
