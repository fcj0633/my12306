[CmdletBinding()]
param(
    [string]$ProjectRoot,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# PS 5.1 在 param() 的默认值里取不到 $PSScriptRoot（为空），会静默指错目录，
# 而且照样报成功。默认值一律改到脚本体里计算。
if (-not $ProjectRoot) { $ProjectRoot = Join-Path $PSScriptRoot '..\..\12306\my12306' }
$ProjectRoot = (Resolve-Path $ProjectRoot).Path
$logDirectory = Join-Path $PSScriptRoot "baseline\logs"
$pidFile = Join-Path $PSScriptRoot "baseline\service-pids.json"
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

$ports = 9000, 9001, 9002, 9003, 9004
$occupied = @($ports | Where-Object {
    Get-NetTCPConnection -LocalPort $_ -State Listen -ErrorAction SilentlyContinue
})
if ($occupied.Count -gt 0) {
    throw "以下端口已被占用，请先停止旧服务：$($occupied -join ', ')"
}

if (-not $SkipBuild) {
    Push-Location $ProjectRoot
    try {
        & mvn package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Maven 打包失败，exitCode=$LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

$javac = (Get-Command javac -ErrorAction Stop).Source
$java = Join-Path (Split-Path $javac -Parent) "java.exe"
$jvmArguments = @(
    "-Djdk.net.unixdomain.tmpdir=Z:\disable-af-unix"
)
$services = @(
    @{ name = "user"; port = 9001; jar = "services\user-services\target\user-services-0.0.1-SNAPSHOT.jar" },
    @{ name = "ticket"; port = 9002; jar = "services\ticket-services\target\ticket-services-0.0.1-SNAPSHOT.jar" },
    @{ name = "order"; port = 9003; jar = "services\order-services\target\order-services-0.0.1-SNAPSHOT.jar" },
    @{ name = "pay"; port = 9004; jar = "services\pay-services\target\pay-services-0.0.1-SNAPSHOT.jar" },
    @{ name = "gateway"; port = 9000; jar = "services\gateway-services\target\gateway-services-0.0.1-SNAPSHOT.jar" }
)

$processes = foreach ($service in $services) {
    $stdout = Join-Path $logDirectory "$($service.name).log"
    $stderr = Join-Path $logDirectory "$($service.name).err.log"
    $arguments = $jvmArguments + @("-jar", $service.jar)
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $ProjectRoot `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    [pscustomobject]@{ name = $service.name; port = $service.port; pid = $process.Id }
    Start-Sleep -Seconds 2
}
$processes | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding utf8

$deadline = (Get-Date).AddMinutes(3)
do {
    $healthy = 0
    foreach ($service in $services) {
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$($service.port)/actuator/health" -TimeoutSec 2
            if ($health.status -eq "UP") { $healthy++ }
        } catch {}
    }
    if ($healthy -eq $services.Count) { break }
    Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)

if ($healthy -ne $services.Count) {
    throw "服务未在 3 分钟内全部就绪，请查看 $logDirectory"
}

$processes | Format-Table name, port, pid -AutoSize
Write-Host "5 个服务已就绪，统一入口：http://127.0.0.1:9000" -ForegroundColor Green
