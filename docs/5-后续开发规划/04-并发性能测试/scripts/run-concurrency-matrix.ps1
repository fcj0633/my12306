[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet("s1", "s2", "s3")][string]$Scenario,
    # 用 string 而不是 int[]：powershell -File 不解析逗号数组，会把整串当成一个元素然后转换失败。
    # （P2 时 start-p2.ps1 的 -Only 踩过同一个坑。）
    [string]$Levels = "20,50,100,200,400",
    # 首个并发点预热 90s（仓库既有约定）；后续点 JIT 已热，只需覆盖缓存冷启动
    [int]$WarmupSeconds = 90,
    [int]$WarmupSecondsShort = 30,
    [int]$OfficialRuns = 3,
    [string]$JMeterBat = "D:\Program Files\jmeter\bin\jmeter.bat",
    [string]$BaseUrl = "http://127.0.0.1:9000",
    [string]$TicketActuatorUrl = "http://127.0.0.1:9002",
    [string]$MySql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [string]$MySqlUser = "root",
    [string]$MySqlPassword = "274226",
    [string]$UserFile,
    [string]$TargetsFile,
    [string]$OutDir,
    # 只跑第一个并发点（用于哨兵校准）
    [switch]$FirstLevelOnly,
    [switch]$SkipWarmup,
    # 关闭"每档并发前刷新登录态"。默认开着：网关认的是 Redis 里的登录态（TTL 30 分钟），
    # 而 JWT 是 24 小时 —— 不刷新的话矩阵跑到一半就会整轮 ERR_AUTH。
    [switch]$SkipTokenRefresh,
    # 热身冲刺秒数；-1 = 按场景自动（S3 为 0，其它为 4）。
    # 需要它的场合：S1 的 400 档 —— 400 线程的热身冲刺本身就会吃掉约 400 张票，
    # 使测量窗口只剩 410 张可售、中途被抽干。传 0 可关闭冲刺，换一个干净的窗口。
    [int]$WarmBurst = -1
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. "$PSScriptRoot/metrics-lib.ps1"

# ---- 路径解析（PS 5.1 在 param 默认值里取不到 $PSScriptRoot，一律在脚本体里算）----
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $OutDir)      { $OutDir = Join-Path $Root "results\$Scenario" }
if (-not $UserFile)    { $UserFile = Join-Path $Root "results\users.csv" }
if (-not $TargetsFile) { $TargetsFile = Join-Path $Root "results\targets-$Scenario.csv" }
foreach ($dir in @($OutDir, (Split-Path $UserFile -Parent), (Split-Path $TargetsFile -Parent))) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}
# 两个计划：吞吐用 duration 模式，热点用一次性。
# 不能合成一个 —— JMeter 的 <boolProp> 不做函数求值，scheduler 用 ${__P(...)} 会静默取 false。
$PlanPath = if ($Scenario -eq "s3") {
    Join-Path $Root "jmx\p2-hotspot.jmx"
} else {
    Join-Path $Root "jmx\p2-throughput.jmx"
}
$FixScript = Join-Path $Root "scripts\fixture-seats.ps1"
$RefreshScript = Join-Path $Root "scripts\refresh-tokens.ps1"
foreach ($needed in @($JMeterBat, $PlanPath, $FixScript, $UserFile, $RefreshScript)) {
    if (-not (Test-Path -LiteralPath $needed)) { throw "缺少依赖：$needed" }
}

$userLines = @(Get-Content -LiteralPath $UserFile -Encoding UTF8 | Select-Object -Skip 1)
# ⚠️ 必须解析到【新变量】：$Levels 是 [string] 类型的参数，PowerShell 会把对它的赋值
# 重新强制转回字符串，于是 $Levels.Count 在 StrictMode 下直接报"找不到属性 Count"。
$levelList = @($Levels -split ',' | ForEach-Object { [int]$_.Trim() } | Where-Object { $_ -gt 0 } | Sort-Object)
if ($levelList.Count -eq 0) { throw "Levels 为空" }
$maxLevel = ($levelList | Measure-Object -Maximum).Maximum
if ($userLines.Count -lt $maxLevel) {
    throw "用户数不足：$($userLines.Count) < 最大并发 $maxLevel。先跑 prepare-users.ps1 或 prepare-perf-users.ps1。"
}

# ---- 目标池 CSV：S1/S3 单池；S2 取 seatType 1 与 2 的全部池 ----
# 实测池数（2026-09-25）：seatType=1 有 28 个池（每池约 284 座）、seatType=2 有 13 个池（每池约 810 座），
# 合计 41 把不同的席别锁。只用 seatType=2 的话只有 13 把，分散度不够。
# seatType=0 每池只有约 54 座、3/4/5 更小，跑长轮次会中途抽干，所以不纳入分散池。
#
# ⚠️ 站名一律用 HEX() 输出成纯 ASCII，由 jmx 侧解码回 UTF-8。
# 不能直接写中文：PowerShell 调用原生命令时用【控制台编码（本机 GBK）】解码其 stdout，
# 而 mysql 输出的是 UTF-8 —— 中文会被双重编码弄坏，更糟的是"南"的末字节会与后面的逗号
# 被当成一个 GBK 双字节字符，**把分隔逗号吃掉**，于是该行只剩 3 个字段、jmx 里 target[3] 越界。
# 实测踩过：S2 全部 41 个池里有 26 行的逗号被吃，整轮 102076 个请求全部 500，数据全废。
if ($Scenario -eq "s2") {
    $rows = Get-SqlValue -MySql $MySql -User $MySqlUser -Password $MySqlPassword -Sql @"
SELECT CONCAT(train_id,',',seat_type,',',HEX(start_station),',',HEX(end_station))
FROM 12306_ticket.t_seat
WHERE seat_type IN (1,2) AND del_flag=0
GROUP BY train_id,seat_type,start_station,end_station
ORDER BY train_id,seat_type,start_station;
"@
    $rows = @($rows | Where-Object { $_ })
    if ($rows.Count -lt 20) { throw "S2 可用池太少（$($rows.Count)），无法有效分散" }
} else {
    # 1,2,<HEX(北京南)>,<HEX(宁波)> —— 与 S2 保持同一种纯 ASCII 格式
    $rows = @("1,2,E58C97E4BAACE58D97,E5AE81E6B3A2")
}
Set-Content -LiteralPath $TargetsFile -Value (@("trainId,seatType,departure,arrival") + $rows) -Encoding UTF8
Write-Host "目标池数：$($rows.Count)（$TargetsFile）"

# ---- 场景参数 ----
# 轮次时长 10s：实测单线程请求均值约 17ms（≈58 QPS），单键吞吐上限 = 1/锁持有，
# 而 S1 的池只有 810 张 —— 15s 就会把池抽干，后半段测的是"拒绝延迟"而不是购票延迟。
# 10s 留出安全余量，并且每轮都会用 poolConsumed 复核。
$runSeconds    = if ($Scenario -eq "s1") { 8 } else { 10 }
# ⚠️ 变量名不能叫 $warmupSeconds —— 与参数 $WarmupSeconds 是同一个变量（PowerShell 变量名不区分大小写），
# 赋 10 会把调用方传进来的预热总时长静默覆盖成 10 秒。实测被这个坑骗过好几轮：
# 传 -WarmupSeconds 60/-WarmupSeconds 20 全都失效，实际每个并发点只预热了一个 10s 切片。
$warmChunkSeconds = 10   # 预热【切片】长度（切片之间要复位池，否则 S1 的池会先被抽干）
# 正式轮前的"热身冲刺"（结果丢弃）：fixture 每轮都会删掉 Redis 的令牌桶与余票缓存，
# 所以每个正式轮都是冷启动 —— 实测未加热身冲刺时，并发 1 的 10s 轮只有 12.3 QPS、P50=74ms，
# 全部时间花在首次缓存装载（令牌桶装载还要拿 Redisson 锁 + 跑一次 GROUP BY）上。
# S3 是一次性超订，热身冲刺会把仅有的 10 张票买光，所以只对 S1/S2 生效。
$warmBurstSeconds = if ($WarmBurst -ge 0) { $WarmBurst } elseif ($Scenario -eq "s3") { 0 } else { 4 }

function Get-PoolAvailable {
    # 显式再包一层 @()：防止将来有人改动 Get-SqlValue 的返回约定后这里静默取错字符
    if ($Scenario -eq "s2") {
        $v = @(Get-SqlValue -MySql $MySql -User $MySqlUser -Password $MySqlPassword -Sql `
            "SELECT IFNULL(SUM(seat_status=0),0) FROM 12306_ticket.t_seat WHERE seat_type IN (1,2) AND del_flag=0;")
        return [int]$v[0]
    }
    $v = @(Get-SqlValue -MySql $MySql -User $MySqlUser -Password $MySqlPassword -Sql `
        "SELECT IFNULL(SUM(seat_status=0),0) FROM 12306_ticket.t_seat WHERE train_id=1 AND seat_type=2 AND start_station='北京南' AND end_station='宁波' AND del_flag=0;")
    return [int]$v[0]
}

function Invoke-Fixture {
    & $FixScript -Scenario $Scenario -MySql $MySql -MySqlUser $MySqlUser -MySqlPassword $MySqlPassword | Out-Null
}

function Invoke-OneRun {
    param([int]$Threads, [int]$Duration, [string]$Tag)
    $jtl  = Join-Path $OutDir "$Tag.jtl"
    $jlog = Join-Path $OutDir "$Tag.jmeter.log"
    # scheduler / loops / continueForever 由计划文件里的字面量决定，不再从命令行传
    $properties = @{
        baseUrl     = $BaseUrl
        userFile    = $UserFile
        targetsFile = $TargetsFile
        threads     = $Threads
        ramp        = 1
        duration    = $Duration
    }
    $cpu = Invoke-JMeterWithSampling -JMeterBat $JMeterBat -PlanPath $PlanPath -JtlPath $jtl `
            -JmeterLogPath $jlog -JMeterProperties $properties -TimeoutSeconds ($Duration + 180)
    return [pscustomobject]@{ jtl = $jtl; cpu = $cpu }
}

# ---- 主循环 ----
$summaries = New-Object System.Collections.Generic.List[object]
# ⚠️ 变量名不能叫 $levels：PowerShell 变量名【不区分大小写】，$levels 与参数 $Levels 是同一个变量，
# 于是数组会被 [string] 的类型约束强制转成"20 50 100 200 400"这种用空格拼接的字符串，
# 循环只跑一次、且把整串当并发数传给 -Threads。必须用一个真正不同的名字。
$levelArray = if ($FirstLevelOnly) { @($levelList[0]) } else { $levelList }
$isFirstLevel = $true

foreach ($level in $levelArray) {
    Write-Host ""
    Write-Host ("=" * 78)
    Write-Host "场景 $Scenario  并发 $level" -ForegroundColor Cyan
    Write-Host ("=" * 78)

    # 每档并发前刷新登录态：网关用 Redis 查登录态（TTL 30 分钟），不刷新则矩阵中途整轮 ERR_AUTH。
    # 每档约 3 分钟，刷新一次（约 1-2 分钟）留出充足余量。
    if (-not $SkipTokenRefresh) {
        Write-Host "[会话刷新] 重新登录换取新 token..."
        & $RefreshScript -UserFile $UserFile -BaseUrl $BaseUrl | Out-Null
    }

    # 1) 预热
    if (-not $SkipWarmup) {
        $warm = if ($isFirstLevel) { $WarmupSeconds } else { $WarmupSecondsShort }
        Write-Host "[预热] $warm 秒（按 ${warmChunkSeconds}s 切片，切片间复位池）"
        $deadline = (Get-Date).AddSeconds($warm)
        $chunk = 0
        do {
            $chunk++
            Invoke-Fixture
            $null = Invoke-OneRun -Threads $level -Duration $warmChunkSeconds -Tag "warmup-$level-$chunk"
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $deadline)
        Write-Host "[预热] 完成（$chunk 个切片）"
    }

    # 2) 正式轮次
    $runs = New-Object System.Collections.Generic.List[object]
    for ($run = 1; $run -le $OfficialRuns; $run++) {
        Write-Host "[正式 $run/$OfficialRuns] 复位 → 热身冲刺 → 采集基线 → 跑 → 采集后值"
        Invoke-Fixture
        Start-Sleep -Seconds 2
        if ($warmBurstSeconds -gt 0) {
            # 丢弃结果，只为把 train_info / 余票缓存 / 令牌桶重新灌热
            $null = Invoke-OneRun -Threads $level -Duration $warmBurstSeconds -Tag "$Scenario-$level-run$run-warmburst"
            Start-Sleep -Seconds 2
        }

        $poolBefore = Get-PoolAvailable
        $meterBefore = Get-MeterSnapshot -BaseUrl $TicketActuatorUrl
        $dbBefore    = Get-DbSnapshot -MySql $MySql -User $MySqlUser -Password $MySqlPassword
        $lockBefore  = Get-InnodbLockSnapshot -MySql $MySql -User $MySqlUser -Password $MySqlPassword

        $result = Invoke-OneRun -Threads $level -Duration $runSeconds -Tag "$Scenario-$level-run$run"
        Start-Sleep -Seconds 2

        $meters = Get-MeterSnapshot -BaseUrl $TicketActuatorUrl
        $dbAfter   = Get-DbSnapshot -MySql $MySql -User $MySqlUser -Password $MySqlPassword
        $lockAfter = Get-InnodbLockSnapshot -MySql $MySql -User $MySqlUser -Password $MySqlPassword
        $poolAfter = Get-PoolAvailable

        $jtl = Read-JMeterResult -JtlPath $result.jtl
        if ($jtl.errAuth -gt 0) {
            # 鉴权失败说明 Redis 登录态已过期，该轮的所有延迟/QPS 都是无意义的快速拒绝
            Write-Warning ("本轮出现 {0}/{1} 个 ERR_AUTH（登录态过期）—— 该轮数据不可用" -f $jtl.errAuth, $jtl.rows)
        }
        $hold = Get-MeterDelta -Before $meterBefore -After $meters -Name 'my12306.purchase.seat-lock.hold'
        $orderOk = Get-MeterDelta -Before $meterBefore -After $meters -Name 'my12306.purchase.order.success'
        $hikariPending = Get-MeterDelta -Before $meterBefore -After $meters -Name 'hikaricp.connections.pending'
        $hikariAcquire = Get-MeterDelta -Before $meterBefore -After $meters -Name 'hikaricp.connections.acquire'
        $cpuTime = Get-MeterDelta -Before $meterBefore -After $meters -Name 'process.cpu.time'
        $threadsPeak = $meters['jvm.threads.peak']

        $holdCount = if ($hold) { [double]$hold['COUNT'] } else { 0 }
        $holdMeanMs = if ($holdCount -gt 0) { [Math]::Round(($hold['TOTAL_TIME'] / $holdCount) * 1000, 2) } else { 0 }
        # holdMaxMs 不可用：SimpleMeterRegistry 对 Timer 的 MAX 实测恒为 0（不是"没有最大值"，是没维护），
        # 所以这里不采集它。锁的上界改用 JMeter 的 P99/分布来看。
        $holdMaxMs = 0
        # 每服务消耗的核数：process.cpu.time 是 FunctionCounter，其 COUNT 实测是【纳秒】累计
        # （baseUnit 标的是 seconds，但取出来的量级是纳秒），所以要 /1e9 再 /窗口秒数。
        # 漏掉这一步会得到 63 亿这种荒谬值 —— 实测踩过。
        $windowSeconds = [Math]::Max(0.001, $jtl.windowSeconds)
        $cores = if ($cpuTime) { [Math]::Round(([double]$cpuTime['COUNT'] / 1e9) / $windowSeconds, 2) } else { 0 }

        $dbTop = @(Get-DbDeltaTop -Before $dbBefore -After $dbAfter -Top 5)
        $dbTotalMs = 0.0
        foreach ($d in $dbTop) { $dbTotalMs += $d.totalMs }

        $runs.Add([pscustomobject]@{
            scenario          = $Scenario
            level             = $level
            run               = $run
            rows              = $jtl.rows
            ok                = $jtl.ok
            rejectAdmission   = $jtl.rejectAdmission
            rejectSeat        = $jtl.rejectSeat
            rejectOther       = $jtl.rejectOther
            errHttp           = $jtl.errHttp
            errAuth           = $jtl.errAuth
            windowSeconds     = $jtl.windowSeconds
            qpsTotal          = $jtl.qpsTotal
            qpsSuccess        = $jtl.qpsSuccess
            p50               = $jtl.p50
            p95               = $jtl.p95
            p99               = $jtl.p99
            meanMs            = $jtl.meanMs
            maxMs             = $jtl.maxMs
            p50Ok             = $jtl.p50Ok
            p95Ok             = $jtl.p95Ok
            p99Ok             = $jtl.p99Ok
            holdMeanMs        = $holdMeanMs
            holdMaxMs         = $holdMaxMs
            holdCount         = [int]$holdCount
            orderSuccessDelta = if ($orderOk) { [int]$orderOk['COUNT'] } else { 0 }
            hikariPendingMax  = if ($hikariPending) { [double]$hikariPending['VALUE'] } else { 0 }
            hikariAcquireMean = if ($hikariAcquire -and [double]$hikariAcquire['COUNT'] -gt 0) {
                                    [Math]::Round(([double]$hikariAcquire['TOTAL_TIME'] / [double]$hikariAcquire['COUNT']) * 1000, 3) } else { 0 }
            jvmThreadsPeak    = if ($threadsPeak) { [int]$threadsPeak['VALUE'] } else { 0 }
            cores             = $cores
            cpuMedianPct      = $result.cpu.median
            cpuMaxPct         = $result.cpu.max
            dbTopTotalMs      = [Math]::Round($dbTotalMs, 2)
            dbTopDigest       = if ($dbTop.Count -gt 0) { $dbTop[0].digest } else { '' }
            dbTopMeanMs       = if ($dbTop.Count -gt 0) { $dbTop[0].meanMs } else { 0 }
            innodbLockWaits   = if ($lockBefore -and $lockAfter) { [int]($lockAfter['Innodb_row_lock_waits'] - $lockBefore['Innodb_row_lock_waits']) } else { -1 }
            innodbLockTimeMs  = if ($lockBefore -and $lockAfter) { [int]($lockAfter['Innodb_row_lock_time'] - $lockBefore['Innodb_row_lock_time']) } else { -1 }
            poolBefore        = $poolBefore
            poolAfter         = $poolAfter
            poolConsumed      = $poolBefore - $poolAfter
        })
        Write-Host ("  完成：QPS总={0} 成功={1} 成功QPS={2} P99={3}ms hold均值={4}ms" -f `
            $jtl.qpsTotal, $jtl.ok, $jtl.qpsSuccess, $jtl.p99, $holdMeanMs)
    }

    # 3) 聚合：3 轮取中位数
    $fields = @('qpsTotal','qpsSuccess','p50','p95','p99','meanMs','maxMs','p50Ok','p95Ok','p99Ok',
                'holdMeanMs','holdMaxMs','hikariPendingMax','hikariAcquireMean','jvmThreadsPeak',
                'cores','cpuMedianPct','cpuMaxPct','dbTopTotalMs','dbTopMeanMs','innodbLockWaits','innodbLockTimeMs')
    $median = @{}
    foreach ($f in $fields) {
        $values = @($runs | ForEach-Object { [double]$_.$f })
        $median[$f] = [Math]::Round((Get-Percentile -Values $values -Percentile 0.50), 3)
    }
    $summaries.Add([pscustomobject]@{
        scenario        = $Scenario
        level           = $level
        runs            = $runs.Count
        rowsMedian      = [int](($runs | ForEach-Object { [double]$_.rows } | Sort-Object)[[int][Math]::Floor($runs.Count / 2)])
        okMedian        = [int](($runs | ForEach-Object { [double]$_.ok } | Sort-Object)[[int][Math]::Floor($runs.Count / 2)])
        medians         = $median
        raw             = $runs
        poolConsumed    = [int](($runs | ForEach-Object { [double]$_.poolConsumed } | Sort-Object)[[int][Math]::Floor($runs.Count / 2)])
    })
    $isFirstLevel = $false
}

# ---- 落盘 ----
$summaryPath = Join-Path $OutDir "summary.json"
$summaries | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
$csvPath = Join-Path $OutDir "summary.csv"
$summaries | ForEach-Object {
    $row = [ordered]@{ scenario = $_.scenario; level = $_.level; runs = $_.runs; rows = $_.rowsMedian; ok = $_.okMedian }
    foreach ($k in $_.medians.Keys) { $row[$k] = $_.medians[$k] }
    [pscustomobject]$row
} | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding UTF8

Write-Host ""
Write-Host "汇总（中位数）：" -ForegroundColor Green
$summaries | ForEach-Object {
    $m = $_.medians
    Write-Host ("  并发 {0,4}  QPS {1,7}  成功QPS {2,6}  P50 {3,6}  P95 {4,6}  P99 {5,7}  hold {6,6}ms  cores {7,5}  CPU中位 {8,5}%" -f `
        $_.level, $m['qpsTotal'], $m['qpsSuccess'], $m['p50'], $m['p95'], $m['p99'], $m['holdMeanMs'], $m['cores'], $m['cpuMedianPct'])
}
Write-Host ""
Write-Host "汇总已写出：$csvPath"
Write-Host "原始轮次已写出：$summaryPath"
