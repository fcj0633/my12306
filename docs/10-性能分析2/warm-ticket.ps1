param([ValidateSet('stop','start')][string]$Action, [string]$OutputDirectory, [string]$Label, [string]$JarPath)
$ErrorActionPreference='Stop'
$out=[IO.Path]::GetFullPath($OutputDirectory)
$records=@(Get-Content -LiteralPath (Join-Path $out 'service-pids.json') | ConvertFrom-Json)
$ticket=$records | Where-Object service -eq 'ticket'
if ($Action -eq 'stop') {
    $live=Get-CimInstance Win32_Process -Filter "ProcessId=$($ticket.pid)"
    if ($live) {
        if ($live.Name -ne 'java.exe' -or $live.CommandLine -notlike '*ticket-services-0.0.1-SNAPSHOT.jar*') { throw 'Unexpected recorded Ticket process' }
        Stop-Process -Id $ticket.pid
    }
    $archive=Join-Path $out "deployments/$Label"
    New-Item -ItemType Directory -Force -Path $archive | Out-Null
    foreach ($name in @('ticket.stdout.log','ticket.stderr.log','ticket.gc.log','traces.csv','locks.csv')) {
        $source=Join-Path $out $name
        if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination (Join-Path $archive $name) }
    }
    if (Test-Path -LiteralPath (Join-Path $out 'ticket.stdout.log')) {
        Copy-Item -LiteralPath (Join-Path $out 'ticket.stdout.log') -Destination (Join-Path $out "ticket.$Label.stdout.log")
    }
    Write-Output "Stopped Ticket and archived $Label"
    exit
}
if (Get-NetTCPConnection -State Listen -LocalPort 9002 -ErrorAction SilentlyContinue) { throw 'Ticket port occupied' }
$jar=[IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $jar)) { throw 'Artifact missing' }
$env:MY12306_PAY_NOTIFY_MODE='feign';$env:MY12306_WORKER_ID='1';$env:MY12306_TICKET_PORT='9002'
$encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($out))
$agent=Join-Path $out 'stage-agent.jar'
$ticketArgs=@('-Xms64m','-Xmx192m','-Xss512k',"-Xlog:gc:file=$out/ticket.gc.log",'-Djdk.net.unixdomain.tmpdir=Z:\disable-af-unix',
    "-javaagent:${agent}=b64:$encoded",'-jar',$jar,'--my12306.ticket.orphan-scan-enabled=false','--my12306.order.fallback-scan-enabled=false')
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../12306/my12306')).Path
$process=Start-Process -FilePath 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe' -ArgumentList $ticketArgs -WorkingDirectory $root -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $out 'ticket.stdout.log') -RedirectStandardError (Join-Path $out 'ticket.stderr.log')
$ticket.pid=$process.Id
$creation=Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
@{label=$Label;pid=$process.Id;CreationDate=$creation.CreationDate.ToString('o');jarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out "ticket-deployment-$Label.json") -Encoding UTF8
$records | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'service-pids.json') -Encoding UTF8
$deadline=(Get-Date).AddSeconds(120)
do {
    if ($process.HasExited) { throw 'Ticket exited' }
    try { if ((Invoke-RestMethod 'http://127.0.0.1:9002/actuator/health' -TimeoutSec 3).status -eq 'UP') { Write-Output "Ticket ready $Label pid=$($process.Id)";exit } } catch { Start-Sleep -Seconds 2 }
} while ((Get-Date) -lt $deadline)
Stop-Process -Id $process.Id
throw 'Ticket readiness timed out'
