$ErrorActionPreference='Stop'
$benchOut=Join-Path $PSScriptRoot 'results/carriage'
$records=@(Get-Content (Join-Path $benchOut 'service-pids.json') | ConvertFrom-Json)
$extra=$records | Where-Object service -eq 'ticket2'
if (-not $extra) { throw 'No owned second Ticket instance recorded' }
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$($extra.pid)"
if ($process) {
    if ($process.Name -ne 'java.exe' -or $process.CommandLine -notlike '*ticket-services-0.0.1-SNAPSHOT.jar*') { throw 'Owned second Ticket identity changed' }
    Stop-Process -Id $extra.pid
}
$extra | ConvertTo-Json | Set-Content (Join-Path $benchOut 'extra-service-pid.json') -Encoding UTF8
@($records | Where-Object service -ne 'ticket2') | ConvertTo-Json | Set-Content (Join-Path $benchOut 'service-pids.json') -Encoding UTF8
$endpoint='http://127.0.0.1:8848/nacos/v1/ns/instance'
$instances=Invoke-RestMethod "$endpoint/list?serviceName=my12306-ticket-service"
foreach ($instance in @($instances.hosts | Where-Object port -eq 9012)) {
    $query="serviceName=my12306-ticket-service&groupName=DEFAULT_GROUP&ip=$([Uri]::EscapeDataString($instance.ip))&port=9012&ephemeral=true"
    Invoke-RestMethod "$endpoint`?$query" -Method Delete | Out-Null
}
# Gateway load-balancer cache retains discovered instances for 35 seconds.
Start-Sleep -Seconds 40
$instances=Invoke-RestMethod "$endpoint/list?serviceName=my12306-ticket-service"
if (@($instances.hosts | Where-Object { $_.healthy -and $_.port -eq 9012 }).Count) { throw 'Second Ticket remains registered healthy' }
Write-Output 'Second Ticket stopped and deregistered; discovery-cache expiry completed'
