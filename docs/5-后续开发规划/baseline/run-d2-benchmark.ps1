[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Variant,
    [switch]$RunS1,
    [switch]$RunS3,
    [switch]$SkipS2,
    [ValidateRange(1, 600)][int]$WarmupSeconds = 90,
    [ValidateRange(1, 10)][int]$OfficialRuns = 3,
    [string]$ResultBase = "d2",
    [string]$BaseUrl = "http://127.0.0.1:9000",
    [string]$TicketActuatorUrl = "http://127.0.0.1:9002",
    [string]$JMeter = "D:\Program Files\jmeter\bin\jmeter.bat"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$resultRoot = Join-Path $PSScriptRoot "results\$ResultBase\$Variant"
$userFile = Join-Path $PSScriptRoot "d1-users.csv"
$serviceLogs = @("ticket", "order", "pay") | ForEach-Object { Join-Path $PSScriptRoot "logs\$_.log" }
$mysql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
New-Item -ItemType Directory -Force -Path $resultRoot | Out-Null
$env:JVM_ARGS = "-Djdk.net.unixdomain.tmpdir=Z:\disable-af-unix"

function Reset-TestData {
    & "$PSScriptRoot/reset-test-data.ps1" | Out-Host
}

# ⚠️ 这个指标依赖 MyBatis 的 StdOutImpl —— 它把每条 SQL 以 "Preparing:" 同步打到服务 stdout。
# P0-1（2026-09-24）已把 log-impl 改成 NoLoggingImpl，因此本函数现在恒返回 0，
# 而 summary.sqlCount 会被静默记成 0（看起来像"SQL 被消灭了"，实际是量不到了）。
# 需要这个数字时，用环境变量 MYBATIS_PLUS_CONFIGURATION_LOG_IMPL 临时把日志开回来，
# 单独跑一轮采集（SQL 条数与日志开关无关，两种状态下发出的语句数相同）。
function Get-SqlCount {
    $count = 0L
    foreach ($log in $serviceLogs) {
        if (Test-Path $log) { $count += [long]@(Select-String -Path $log -Pattern "Preparing:" -SimpleMatch).Count }
    }
    return $count
}

function Get-Percentile {
    param([object[]]$Rows, [double]$Percentile)
    $values = @($Rows | ForEach-Object { [long]$_.elapsed } | Sort-Object)
    if ($values.Count -eq 0) { return 0L }
    return $values[[Math]::Max(0, [Math]::Ceiling($values.Count * $Percentile) - 1)]
}

function Read-JMeterResult {
    param([string]$Path)
    $rows = @(Import-Csv $Path)
    $success = @($rows | Where-Object { $_.success -eq "true" }).Count
    $start = ($rows | Measure-Object -Property timeStamp -Minimum).Minimum
    $end = ($rows | ForEach-Object { [long]$_.timeStamp + [long]$_.elapsed } | Measure-Object -Maximum).Maximum
    $seconds = [Math]::Max(0.001, ([double]$end - [double]$start) / 1000.0)
    return [ordered]@{
        samples = $rows.Count
        success = $success
        failed = $rows.Count - $success
        errorRate = [Math]::Round(($rows.Count - $success) * 100.0 / $rows.Count, 2)
        durationSec = [Math]::Round($seconds, 3)
        totalTps = [Math]::Round($rows.Count / $seconds, 2)
        successTps = [Math]::Round($success / $seconds, 2)
        averageMs = [Math]::Round(($rows | Measure-Object -Property elapsed -Average).Average, 2)
        p50Ms = Get-Percentile $rows 0.50
        p95Ms = Get-Percentile $rows 0.95
        p99Ms = Get-Percentile $rows 0.99
    }
}

function Invoke-JMeterPlan {
    param([string]$Name, [string]$Plan, [string[]]$Properties)
    $jtl = Join-Path $resultRoot "$Name.jtl"
    $log = Join-Path $resultRoot "$Name-jmeter.log"
    Remove-Item $jtl, $log -Force -ErrorAction SilentlyContinue
    & $JMeter -n -t $Plan -l $jtl -j $log "-JbaseUrl=$BaseUrl" "-JuserFile=$userFile" @Properties | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "JMeter $Name 执行失败，exitCode=$LASTEXITCODE" }
    return Read-JMeterResult $jtl
}

function Get-MetricValue {
    param([string]$Name)
    try {
        $metric = Invoke-RestMethod "$TicketActuatorUrl/actuator/metrics/$Name" -TimeoutSec 5
        $value = @($metric.measurements | Where-Object { $_.statistic -eq "COUNT" } | Select-Object -First 1).value
        return $(if ($null -eq $value) { 0.0 } else { [double]$value })
    } catch {
        return 0.0
    }
}

function Get-TimerSnapshot {
    param([string]$Name)
    try {
        $metric = Invoke-RestMethod "$TicketActuatorUrl/actuator/metrics/$Name" -TimeoutSec 5
        $values = @{}
        foreach ($measurement in $metric.measurements) { $values[$measurement.statistic] = [double]$measurement.value }
        # 分位数在 actuator 里【不是】基础指标上的 tag，而是单独的 <name>.percentile 指标，
        # 且 tag 名是 phi（不是 percentile / quantile —— 后两者是 Prometheus 的写法）。
        #
        # ⚠️ 但即使查对了，本项目也拿不到有效分位数：注册表是 SimpleMeterRegistry（没有引
        # micrometer-registry-prometheus），它不为 Timer 维护分布直方图，所以
        # <name>.percentile 的 phi=0.5/0.95/0.99 恒为 0.0。
        # 【可用的是平均值】：COUNT 与 TOTAL_TIME 是真实累计值，averageMs = ΔTOTAL_TIME/ΔCOUNT。
        # 2026-09-24 实测确认：seat-lock.hold 的 COUNT=1081、TOTAL_TIME=27.058s → 25.03ms，可信；
        # 而同期该 .percentile 指标的三个 phi 全部 = 0.000 ms。
        # 要拿到真分位数需引入 Prometheus registry（或配 publishPercentileHistogram + 桶边界）。
        $percentiles = @{}
        foreach ($percentile in @("0.5", "0.95", "0.99")) {
            try {
                $point = Invoke-RestMethod "$TicketActuatorUrl/actuator/metrics/$Name.percentile`?tag=phi:$percentile" -TimeoutSec 5
                $percentiles[$percentile] = [double]@($point.measurements | Select-Object -First 1).value
            } catch { $percentiles[$percentile] = 0.0 }
        }
        return [ordered]@{
            count = [double]$values.COUNT
            totalTime = [double]$values.TOTAL_TIME
            max = [double]$values.MAX
            p50 = $percentiles["0.5"]
            p95 = $percentiles["0.95"]
            p99 = $percentiles["0.99"]
        }
    } catch {
        return [ordered]@{ count = 0.0; totalTime = 0.0; max = 0.0; p50 = 0.0; p95 = 0.0; p99 = 0.0 }
    }
}

function Get-TimerDelta {
    param([System.Collections.IDictionary]$Before, [System.Collections.IDictionary]$After)
    $count = [Math]::Max(0.0, $After.count - $Before.count)
    $total = [Math]::Max(0.0, $After.totalTime - $Before.totalTime)
    return [ordered]@{
        count = [Math]::Round($count, 0)
        averageMs = $(if ($count -eq 0) { 0.0 } else { [Math]::Round($total * 1000.0 / $count, 3) })
        processMaxMs = [Math]::Round($After.max * 1000.0, 3)
        processP50Ms = [Math]::Round($After.p50 * 1000.0, 3)
        processP95Ms = [Math]::Round($After.p95 * 1000.0, 3)
        processP99Ms = [Math]::Round($After.p99 * 1000.0, 3)
    }
}

function Invoke-MySqlScalar {
    param([string]$Sql)
    return (& $mysql -uroot -p274226 -N -B -e $Sql | Select-Object -First 1)
}

$result = [ordered]@{
    variant = $Variant
    timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss zzz")
}

if ($RunS1) {
    Reset-TestData
    $null = Invoke-JMeterPlan "s1-warmup" "$PSScriptRoot/jmeter/s1-query.jmx" @("-Jthreads=100", "-Jramp=5", "-Jduration=$WarmupSeconds")
    Reset-TestData
    $result.s1 = Invoke-JMeterPlan "s1-query" "$PSScriptRoot/jmeter/s1-query.jmx" @("-Jthreads=100", "-Jramp=10", "-Jduration=60")
}

if (-not $SkipS2) {
    Reset-TestData
    $warmupDeadline = (Get-Date).AddSeconds($WarmupSeconds)
    $iteration = 0
    do {
        $iteration++
        $null = Invoke-JMeterPlan "s2-warmup-$iteration" "$PSScriptRoot/jmeter/s2-purchase.jmx" @("-Jthreads=50", "-Jramp=1")
        Reset-TestData
    } while ((Get-Date) -lt $warmupDeadline)
    Reset-TestData

    $s2Runs = [System.Collections.Generic.List[object]]::new()
    for ($run = 1; $run -le $OfficialRuns; $run++) {
        Reset-TestData
        $initial = [int](Invoke-MySqlScalar "SELECT COUNT(*) FROM 12306_ticket.t_seat WHERE train_id=1 AND start_station='北京南' AND end_station='宁波' AND seat_type=0 AND seat_status=0;")
        $sqlBefore = Get-SqlCount
        $passBefore = Get-MetricValue "my12306.token.pass"
        $rejectBefore = Get-MetricValue "my12306.token.reject"
        $degradeBefore = Get-MetricValue "my12306.token.degrade"

        $summary = Invoke-JMeterPlan "s2-run-$run" "$PSScriptRoot/jmeter/s2-purchase.jmx" @("-Jthreads=50", "-Jramp=1")
        $summary.sqlCount = (Get-SqlCount) - $sqlBefore
        $summary.initialAvailable = $initial
        $summary.finalAvailable = [int](Invoke-MySqlScalar "SELECT COUNT(*) FROM 12306_ticket.t_seat WHERE train_id=1 AND start_station='北京南' AND end_station='宁波' AND seat_type=0 AND seat_status=0;")
        $summary.duplicateSeats = [int](Invoke-MySqlScalar "SELECT COUNT(*) FROM (SELECT carriage_number,seat_number,COUNT(*) c FROM 12306_ticket.t_ticket WHERE username LIKE 'd1_test_%' AND train_id=1 AND start_station='北京南' AND end_station='宁波' AND seat_type=0 GROUP BY carriage_number,seat_number HAVING c>1) x;")
        $summary.oversold = ($summary.success -gt $initial -or $summary.finalAvailable -lt 0 -or $summary.duplicateSeats -gt 0)
        $summary.tokenPass = [Math]::Round((Get-MetricValue "my12306.token.pass") - $passBefore, 0)
        $summary.tokenReject = [Math]::Round((Get-MetricValue "my12306.token.reject") - $rejectBefore, 0)
        $summary.tokenDegrade = [Math]::Round((Get-MetricValue "my12306.token.degrade") - $degradeBefore, 0)
        $s2Runs.Add([pscustomobject]$summary)
    }
    $result.s2Runs = $s2Runs
}

if ($RunS3) {
    Reset-TestData
    $warmupDeadline = (Get-Date).AddSeconds($WarmupSeconds)
    $warmupRun = 0
    do {
        $warmupRun++
        $null = Invoke-JMeterPlan "s3-warmup-$warmupRun" "$PSScriptRoot/jmeter/s3-full-flow.jmx" @("-Jthreads=100", "-Jramp=5")
        Reset-TestData
    } while ((Get-Date) -lt $warmupDeadline)

    $s3Runs = [System.Collections.Generic.List[object]]::new()
    for ($run = 1; $run -le $OfficialRuns; $run++) {
        Reset-TestData
        $sqlBefore = Get-SqlCount
        $lockBefore = Get-TimerSnapshot "my12306.purchase.seat-lock.hold"
        $passengerBefore = Get-TimerSnapshot "my12306.purchase.passenger.remote"
        $orderBefore = Get-TimerSnapshot "my12306.purchase.order.remote"
        $summary = Invoke-JMeterPlan "s3-run-$run" "$PSScriptRoot/jmeter/s3-full-flow.jmx" @("-Jthreads=100", "-Jramp=5")
        $summary.sqlCount = (Get-SqlCount) - $sqlBefore
        $summary.lockTimer = Get-TimerDelta $lockBefore (Get-TimerSnapshot "my12306.purchase.seat-lock.hold")
        $summary.passengerRemoteTimer = Get-TimerDelta $passengerBefore (Get-TimerSnapshot "my12306.purchase.passenger.remote")
        $summary.orderRemoteTimer = Get-TimerDelta $orderBefore (Get-TimerSnapshot "my12306.purchase.order.remote")
        $summary.duplicateSeats = [int](Invoke-MySqlScalar "SELECT COUNT(*) FROM (SELECT carriage_number,seat_number,COUNT(*) c FROM 12306_ticket.t_ticket WHERE username LIKE 'd1_test_%' GROUP BY train_id,start_station,end_station,carriage_number,seat_number HAVING c>1) x;")
        $summary.paidOrders = [int](Invoke-MySqlScalar "SELECT COUNT(*) FROM 12306_order.t_order WHERE username LIKE 'd1_test_%' AND status=10;")
        $s3Runs.Add([pscustomobject]$summary)
    }
    $result.s3Runs = $s3Runs
}
$resultPath = Join-Path $resultRoot "summary.json"
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultPath -Encoding utf8
$result | ConvertTo-Json -Depth 8
Reset-TestData
