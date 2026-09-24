[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:9000",
    [string]$JMeter = "D:\Program Files\jmeter\bin\jmeter.bat",
    [string]$LogDirectory
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# PS 5.1 在 param() 的默认值里取不到 $PSScriptRoot（为空），会静默指到盘根，
# 而且照样报成功。默认值一律改到脚本体里计算。
if (-not $LogDirectory) { $LogDirectory = Join-Path $PSScriptRoot "logs" }
. "$PSScriptRoot/redis-tools.ps1"

# This host advertises AF_UNIX support but cannot connect to a local AF_UNIX
# socket. Force JMeter's JDK selector to fall back to the TCP loopback pipe.
$selectorFallback = "-Djdk.net.unixdomain.tmpdir=Z:\disable-af-unix"
if ($env:JVM_ARGS -notlike "*$selectorFallback*") {
    $env:JVM_ARGS = "$($env:JVM_ARGS) $selectorFallback".Trim()
}

$resultRoot = Join-Path $PSScriptRoot "results"
$userFile = Join-Path $PSScriptRoot "d1-users.csv"
New-Item -ItemType Directory -Force -Path $resultRoot | Out-Null

function Reset-D1Data { & "$PSScriptRoot/reset-test-data.ps1" | Out-Host }

# ⚠️ 这个指标依赖 MyBatis 的 StdOutImpl —— 它把每条 SQL 以 "Preparing:" 同步打到服务 stdout。
# P0-1（2026-09-24）已把 log-impl 改成 NoLoggingImpl，因此本函数现在恒返回 0，
# 而 sqlCount 会被静默记成 0（看起来像"SQL 被消灭了"，实际是量不到了）。
# 需要这个数字时，用环境变量 MYBATIS_PLUS_CONFIGURATION_LOG_IMPL 临时把日志开回来，
# 单独跑一轮采集（SQL 条数与日志开关无关，两种状态下发出的语句数相同）。
function Get-SqlCount {
    if (-not (Test-Path $LogDirectory)) { return 0 }
    return [long]@(Get-ChildItem $LogDirectory -Filter "*.log" -File -ErrorAction SilentlyContinue |
        Select-String -Pattern "Preparing:" -SimpleMatch).Count
}

function Get-Percentile {
    param([object[]]$Rows, [double]$Percentile)
    $values = @($Rows | ForEach-Object { [int64]$_.elapsed } | Sort-Object)
    if ($values.Count -eq 0) { return 0 }
    $index = [Math]::Max(0, [Math]::Ceiling($values.Count * $Percentile) - 1)
    return $values[$index]
}

function Read-JMeterResult {
    param([string]$Path)
    $rows = @(Import-Csv $Path)
    $success = @($rows | Where-Object { $_.success -eq "true" }).Count
    $start = ($rows | Measure-Object -Property timeStamp -Minimum).Minimum
    $end = ($rows | ForEach-Object { [int64]$_.timeStamp + [int64]$_.elapsed } | Measure-Object -Maximum).Maximum
    $seconds = [Math]::Max(0.001, ([double]$end - [double]$start) / 1000.0)
    return [ordered]@{
        samples = $rows.Count
        success = $success
        failed = $rows.Count - $success
        errorRate = if ($rows.Count) { [Math]::Round((($rows.Count - $success) * 100.0 / $rows.Count), 2) } else { 0 }
        tps = [Math]::Round($rows.Count / $seconds, 2)
        p99Ms = Get-Percentile $rows 0.99
    }
}

function Invoke-JMeter {
    param([string]$Name, [string]$Plan, [string[]]$Properties, [switch]$Report)
    $jtl = Join-Path $resultRoot "$Name.jtl"
    $jmeterLog = Join-Path $resultRoot "$Name-jmeter.log"
    $reportDirectory = Join-Path $resultRoot "$Name-report"
    Remove-Item $jtl -Force -ErrorAction SilentlyContinue
    Remove-Item $jmeterLog -Force -ErrorAction SilentlyContinue
    if (Test-Path $reportDirectory) { Remove-Item $reportDirectory -Recurse -Force }
    $arguments = @("-n", "-t", $Plan, "-l", $jtl, "-j", $jmeterLog,
        "-JbaseUrl=$BaseUrl", "-JuserFile=$userFile") + $Properties
    if ($Report) { $arguments += @("-e", "-o", $reportDirectory) }
    & $JMeter @arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "JMeter $Name 执行失败，exitCode=$LASTEXITCODE" }
    return Read-JMeterResult $jtl
}

Write-Host "准备 100 个固定压测用户..."
& "$PSScriptRoot/prepare-users.ps1" -BaseUrl $BaseUrl -Count 100 -OutputFile $userFile

$redis = $null
try {
    $redis = Open-RedisConnection
    $latencies = for ($i = 0; $i -lt 100; $i++) {
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        [void](Send-RedisCommand $redis @("PING"))
        $watch.Stop()
        $watch.Elapsed.TotalMilliseconds
    }
    $redisLatencyAvg = [Math]::Round(($latencies | Measure-Object -Average).Average, 3)
    $redisLatencyP95 = (@($latencies | Sort-Object))[[Math]::Ceiling($latencies.Count * 0.95) - 1]

    Reset-D1Data
    $null = Invoke-JMeter "s1-warmup" "$PSScriptRoot/jmeter/s1-query.jmx" @("-Jthreads=100", "-Jramp=5", "-Jduration=15")
    Reset-D1Data
    $sqlBefore = Get-SqlCount
    $redisBefore = Get-RedisStats $redis
    $s1 = Invoke-JMeter "s1-query" "$PSScriptRoot/jmeter/s1-query.jmx" @("-Jthreads=100", "-Jramp=10", "-Jduration=60") -Report
    $redisAfter = Get-RedisStats $redis
    $s1.Add("sqlCount", (Get-SqlCount) - $sqlBefore)
    $hitDelta = $redisAfter.keyspace_hits - $redisBefore.keyspace_hits
    $missDelta = $redisAfter.keyspace_misses - $redisBefore.keyspace_misses
    $s1.Add("redisHitRate", $(if (($hitDelta + $missDelta) -gt 0) { [Math]::Round($hitDelta * 100.0 / ($hitDelta + $missDelta), 2) } else { 0 }))

    Reset-D1Data
    $null = Invoke-JMeter "s2-warmup" "$PSScriptRoot/jmeter/s2-purchase.jmx" @("-Jthreads=50", "-Jramp=1")
    Reset-D1Data
    # 下面三处 mysql.exe 调用都带 -p 传密码，它会向 stderr 打一行 "Using a password ... insecure"。
    # PS 5.1 在 $ErrorActionPreference = "Stop" 下会把原生 stderr 当错误直接中止脚本，
    # 所以逐处加 2>$null 把它挡在错误流之外（这三条都是 SELECT，失败会在后面的断言里暴露）。
    $initialAvailable = [int](& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p274226 -N -B -e "SELECT COUNT(*) FROM 12306_ticket.t_seat WHERE train_id=1 AND start_station='北京南' AND end_station='宁波' AND seat_type=0 AND seat_status=0;" 2>$null)
    $sqlBefore = Get-SqlCount
    $s2 = Invoke-JMeter "s2-purchase" "$PSScriptRoot/jmeter/s2-purchase.jmx" @("-Jthreads=50", "-Jramp=1") -Report
    $s2.Add("sqlCount", (Get-SqlCount) - $sqlBefore)
    $s2.Add("initialAvailable", $initialAvailable)
    $s2.Add("finalAvailable", [int](& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p274226 -N -B -e "SELECT COUNT(*) FROM 12306_ticket.t_seat WHERE train_id=1 AND start_station='北京南' AND end_station='宁波' AND seat_type=0 AND seat_status=0;" 2>$null))
    $s2.Add("duplicateSeats", [int](& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p274226 -N -B -e "SELECT COUNT(*) FROM (SELECT carriage_number,seat_number,COUNT(*) c FROM 12306_ticket.t_ticket WHERE username LIKE 'd1_test_%' AND train_id=1 AND start_station='北京南' AND end_station='宁波' AND seat_type=0 GROUP BY carriage_number,seat_number HAVING c>1) x;" 2>$null))
    $s2.Add("oversold", ($s2.success -gt $initialAvailable -or $s2.finalAvailable -lt 0 -or $s2.duplicateSeats -gt 0))

    Reset-D1Data
    $null = Invoke-JMeter "s3-warmup" "$PSScriptRoot/jmeter/s3-full-flow.jmx" @("-Jthreads=20", "-Jramp=2")
    Reset-D1Data
    $sqlBefore = Get-SqlCount
    $s3 = Invoke-JMeter "s3-full-flow" "$PSScriptRoot/jmeter/s3-full-flow.jmx" @("-Jthreads=100", "-Jramp=5") -Report
    $s3.Add("sqlCount", (Get-SqlCount) - $sqlBefore)

    $result = [ordered]@{
        timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss zzz")
        redisLatencyAverageMs = $redisLatencyAvg
        redisLatencyP95Ms = [Math]::Round($redisLatencyP95, 3)
        s1 = $s1
        s2 = $s2
        s3 = $s3
    }
    $resultPath = Join-Path $resultRoot "d1-results.json"
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultPath -Encoding utf8
    $result | ConvertTo-Json -Depth 8
    Reset-D1Data
    Write-Host "基线完成：$resultPath" -ForegroundColor Green
} finally {
    Close-RedisConnection $redis
}
