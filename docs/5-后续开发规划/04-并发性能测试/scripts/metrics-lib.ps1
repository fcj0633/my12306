# 并发性能测试的采集工具库（被 run-concurrency-matrix.ps1 点源加载）
#
# 设计原则：所有采集都基于【运行期真实可用的手段】，不用拿不到的东西充数。
# 已核实（2026-09-25 实测 9002 的 /actuator/metrics 全量清单）：
#   可用：hikaricp.connections.*、http.server.requests、jvm.threads.live/peak、
#         jvm.gc.pause、process.cpu.time/usage、system.cpu.usage、jdbc.connections.*
#   不可用：tomcat.threads.busy / tomcat.threads.config.max（未绑定）
#   ⇒ Tomcat 排队的替代证据用 jvm.threads.live 的峰值。
#   惰性注册：my12306.* 自定义指标要第一次被记录后才出现，所以预热之前查不到是正常的。

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# ---------------------------------------------------------------- 分位数

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if ($null -eq $Values -or $Values.Count -eq 0) { return 0 }
    $sorted = @($Values | Sort-Object)
    # nearest-rank：与 run-baseline.ps1 同一套算法，保证与历史口径一致
    $index = [Math]::Max(0, [Math]::Ceiling($sorted.Count * $Percentile) - 1)
    return $sorted[$index]
}

# ---------------------------------------------------------------- JMeter .jtl

function Read-JMeterResult {
    param([string]$JtlPath)
    if (-not (Test-Path -LiteralPath $JtlPath)) { throw "找不到 .jtl：$JtlPath" }
    $rows = @(Import-Csv -LiteralPath $JtlPath)
    if ($rows.Count -eq 0) { throw ".jtl 为空：$JtlPath" }

    $elapsedAll = @($rows | ForEach-Object { [double]$_.elapsed })
    $okRows     = @($rows | Where-Object { $_.responseMessage -eq 'OK' })
    $elapsedOk  = @($okRows | ForEach-Object { [double]$_.elapsed })

    # 分类计数：responseMessage 由 jmx 固化成 ASCII 标记，避开 GBK/UTF-8 乱码
    $countOf = { param($mark) @($rows | Where-Object { $_.responseMessage -eq $mark }).Count }
    $errHttp = @($rows | Where-Object { $_.responseMessage -like 'ERR_HTTP_*' }).Count
    $errAuth = & $countOf 'ERR_AUTH'

    # 窗口 = 第一个样本开始 到 最后一个样本结束
    $start = ($rows | ForEach-Object { [int64]$_.timeStamp } | Measure-Object -Minimum).Minimum
    $end   = ($rows | ForEach-Object { [int64]$_.timeStamp + [int64]$_.elapsed } | Measure-Object -Maximum).Maximum
    $windowSeconds = [Math]::Max(0.001, ($end - $start) / 1000.0)

    return [pscustomobject]@{
        rows             = $rows.Count
        ok               = $okRows.Count
        rejectAdmission  = & $countOf 'REJECT_ADMISSION'
        rejectSeat       = & $countOf 'REJECT_SEAT'
        rejectOther      = & $countOf 'REJECT_OTHER'
        errHttp          = $errHttp
        errAuth          = $errAuth
        windowSeconds    = [Math]::Round($windowSeconds, 3)
        qpsTotal         = [Math]::Round($rows.Count / $windowSeconds, 2)
        qpsSuccess       = [Math]::Round($okRows.Count / $windowSeconds, 2)
        p50              = Get-Percentile -Values $elapsedAll -Percentile 0.50
        p95              = Get-Percentile -Values $elapsedAll -Percentile 0.95
        p99              = Get-Percentile -Values $elapsedAll -Percentile 0.99
        meanMs           = if ($elapsedAll.Count -gt 0) { [Math]::Round(($elapsedAll | Measure-Object -Average).Average, 1) } else { 0 }
        maxMs            = if ($elapsedAll.Count -gt 0) { ($elapsedAll | Measure-Object -Maximum).Maximum } else { 0 }
        # 只看成功请求的延迟：把"快速失败"混进来会稀释掉真实购票耗时
        p50Ok            = Get-Percentile -Values $elapsedOk -Percentile 0.50
        p95Ok            = Get-Percentile -Values $elapsedOk -Percentile 0.95
        p99Ok            = Get-Percentile -Values $elapsedOk -Percentile 0.99
    }
}

# ---------------------------------------------------------------- actuator

function Get-Meter {
    param([string]$BaseUrl, [string]$Name)
    try {
        $raw = Invoke-WebRequest -Uri "$BaseUrl/actuator/metrics/$Name" -TimeoutSec 5 -UseBasicParsing
    } catch {
        return $null   # 指标尚未注册（SimpleMeterRegistry 是惰性的）
    }
    $json = [System.Text.Encoding]::UTF8.GetString($raw.RawContentStream.ToArray()) | ConvertFrom-Json
    $map = @{}
    foreach ($m in $json.measurements) { $map[$m.statistic] = [double]$m.value }
    return $map
}

# 一次采集我们关心的全部指标，返回嵌套 hashtable，便于前后取差
$script:MeterNames = @(
    'my12306.purchase.seat-lock.hold',
    'my12306.purchase.order.success',
    'my12306.purchase.order.ambiguous',
    'my12306.purchase.passenger.remote',
    'my12306.purchase.order.remote',
    'my12306.token.pass', 'my12306.token.reject', 'my12306.token.degrade', 'my12306.token.load',
    'hikaricp.connections.active', 'hikaricp.connections.idle', 'hikaricp.connections.pending',
    'hikaricp.connections.acquire', 'hikaricp.connections.timeout', 'hikaricp.connections.usage',
    'jvm.threads.live', 'jvm.threads.peak', 'jvm.gc.pause',
    'process.cpu.time', 'process.cpu.usage'
)

function Get-MeterSnapshot {
    param([string]$BaseUrl, [string[]]$Names = $script:MeterNames)
    $snap = @{}
    foreach ($name in $Names) {
        $snap[$name] = Get-Meter -BaseUrl $BaseUrl -Name $name
    }
    return $snap
}

function Get-MeterDelta {
    param($Before, $After, [string]$Name)
    $b = $Before[$Name]; $a = $After[$Name]
    if ($null -eq $a) { return $null }
    $result = @{}
    foreach ($stat in $a.Keys) {
        $bv = 0.0
        if ($null -ne $b -and $b.ContainsKey($stat)) { $bv = [double]$b[$stat] }
        $result[$stat] = [double]$a[$stat] - $bv
    }
    return $result
}

# ---------------------------------------------------------------- MySQL

function Get-SqlValue {
    param([string]$MySql, [string]$User, [string]$Password, [string]$Sql)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & $MySql "-u$User" "-p$Password" "--default-character-set=utf8mb4" "-N" "-B" "--execute=$Sql" 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) { throw "MySQL 失败：$Sql`n$out" }
    # ⚠️ 调用方【必须】用 @(...) 包一层再索引。
    # PowerShell 在函数返回时会把【单元素数组】解包成裸字符串，于是 `$v = Get-SqlValue ...; $v[0]`
    # 变成"取字符串的第一个字符"——单行结果（最常见的 SELECT）会静默取错值。
    # 实测踩过：池子读数 810 被 $v[0] 取成 '8'，导致"池消耗 == 成功数"这条交叉校验彻底失效。
    # 不要在函数里用前置逗号 `,` 修（那会造成一层嵌套，$v[0] 变成数组）——正确做法是调用方包 @()。
    return @($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' })
}

# 语句级耗时快照。注意 performance_schema 的时间单位是【皮秒】：
#   秒 = SUM_TIMER_WAIT / 1e12，毫秒 = AVG_TIMER_WAIT / 1e9
function Get-DbSnapshot {
    param([string]$MySql, [string]$User, [string]$Password)
    $lines = Get-SqlValue -MySql $MySql -User $User -Password $Password -Sql @"
SELECT CONCAT(DIGEST,'|',COUNT_STAR,'|',SUM_TIMER_WAIT,'|',AVG_TIMER_WAIT,'|',SUM_ROWS_EXAMINED,'|',SUM_ROWS_AFFECTED)
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME LIKE '12306%';
"@
    $snap = @{}
    foreach ($line in $lines) {
        if (-not $line) { continue }
        $p = $line.Split('|')
        if ($p.Count -lt 6) { continue }
        $snap[$p[0]] = [pscustomobject]@{
            count   = [double]$p[1]
            totalNs = [double]$p[2]
            avgNs   = [double]$p[3]
            examined = [double]$p[4]
            affected = [double]$p[5]
        }
    }
    return $snap
}

function Get-DbDeltaTop {
    param($Before, $After, [int]$Top = 8)
    $deltas = @()
    foreach ($digest in $After.Keys) {
        $a = $After[$digest]
        $b = $Before[$digest]
        $bCount = 0.0; $bTotal = 0.0; $bExamined = 0.0; $bAffected = 0.0
        if ($null -ne $b) { $bCount = $b.count; $bTotal = $b.totalNs; $bExamined = $b.examined; $bAffected = $b.affected }
        $dCount = $a.count - $bCount
        if ($dCount -le 0) { continue }
        $dTotal = $a.totalNs - $bTotal
        $deltas += [pscustomobject]@{
            digest       = $digest
            calls        = [int]$dCount
            totalMs      = [Math]::Round($dTotal / 1e9, 2)          # 皮秒 -> 毫秒
            meanMs       = [Math]::Round($dTotal / $dCount / 1e9, 3)
            examinedRows = [int]($a.examined - $bExamined)
            affectedRows = [int]($a.affected - $bAffected)
        }
    }
    # 调用方要用 @() 包一层（同 Get-SqlValue 的说明）
    return @($deltas | Sort-Object -Property totalMs -Descending | Select-Object -First $Top)
}

function Get-InnodbLockSnapshot {
    param([string]$MySql, [string]$User, [string]$Password)
    $lines = Get-SqlValue -MySql $MySql -User $User -Password $Password -Sql @"
SHOW GLOBAL STATUS WHERE Variable_name IN
('Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_row_lock_time_max','Innodb_row_lock_current_waits');
"@
    $snap = @{}
    foreach ($line in $lines) {
        if (-not $line) { continue }
        $p = $line -split "`t"
        if ($p.Count -ge 2) { $snap[$p[0]] = [double]$p[1] }
    }
    return $snap
}

# ---------------------------------------------------------------- 跑 JMeter 并同步采 CPU

function Stop-LeftoverJMeter {
    # 起跑前清孤儿：实测踩过一次 —— jmeter.bat 是 cmd 桩，桩先退出会让驱动误判"已结束"，
    # 而真正的 JMeter JVM 还在无限循环写 .jtl，污染后续所有轮次。
    $targets = @()
    $targets += @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine -match 'ApacheJMeter|jmeter' })
    $targets += @(Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine -match 'jmeter\.bat' })
    foreach ($p in $targets) {
        Write-Warning ("清理遗留 JMeter 进程 pid={0}" -f $p.ProcessId)
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if ($targets.Count -gt 0) { Start-Sleep -Seconds 3 }
}

function Invoke-JMeterWithSampling {
    param(
        [string]$JMeterBat,
        [string]$PlanPath,
        [string]$JtlPath,
        [string]$JmeterLogPath,
        [hashtable]$JMeterProperties,
        [int]$SampleIntervalSeconds = 2,
        # 硬超时：到点就杀掉 JMeter 并抛错，绝不留下孤儿
        [int]$TimeoutSeconds = 300,
        [string[]]$ExtraArgs = @()
    )
    Stop-LeftoverJMeter
    if (Test-Path -LiteralPath $JtlPath) { Remove-Item -LiteralPath $JtlPath -Force }
    # JMeter 是追加写 .jtl 的，所以每轮必须先删（仓库文档记录过这个坑）
    $stdoutPath = [System.IO.Path]::ChangeExtension($JmeterLogPath, $null) + '.out.log'
    $stderrPath = [System.IO.Path]::ChangeExtension($JmeterLogPath, $null) + '.err.log'
    foreach ($p in @($stdoutPath, $stderrPath)) {
        if (Test-Path -LiteralPath $p) { Remove-Item -LiteralPath $p -Force }
    }

    $jArgs = @()
    foreach ($key in $JMeterProperties.Keys) { $jArgs += "-J$key=$($JMeterProperties[$key])" }
    # 显式指定存盘字段，避免依赖 JMeter 默认值
    $jArgs += '-Jjmeter.save.saveservice.output_format=csv'
    $jArgs += '-Jjmeter.save.saveservice.print_field_names=true'
    $jArgs += '-Jjmeter.save.saveservice.response_message=true'
    $jArgs += '-Jjmeter.save.saveservice.successful=true'

    $argumentList = @('-n', '-t', $PlanPath, '-l', $JtlPath, '-j', $JmeterLogPath) + $ExtraArgs + $jArgs

    $previousHeap = $env:HEAP
    $env:HEAP = '-Xms256m -Xmx512m'
    # AF_UNIX 开关：本机 JDK21 会真的去用 AF_UNIX 然后失败
    $previousToolOptions = $env:JAVA_TOOL_OPTIONS
    $env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=Z:\disable-af-unix'
    $samples = New-Object System.Collections.Generic.List[double]
    $timedOut = $false
    try {
        # stdout 落文件：JMeter -n 模式结束时会在 stdout 打印 "... end of run"，
        # 这是唯一可靠的完成标记（.jmeter.log 里没有它，而 cmd 桩的退出时间不可信）。
        $process = Start-Process -FilePath $JMeterBat -ArgumentList $argumentList -PassThru -NoNewWindow `
            -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        while ($true) {
            if ((Get-Date) -gt $deadline) { $timedOut = $true; break }
            try {
                $sample = (Get-Counter '\Processor(_Total)\% Processor Time' -ErrorAction Stop).CounterSamples[0].CookedValue
                $samples.Add([double]$sample)
            } catch { }
            $out = ''
            if (Test-Path -LiteralPath $stdoutPath) {
                $out = Get-Content -LiteralPath $stdoutPath -Raw -ErrorAction SilentlyContinue
            }
            if ($out -and $out -match 'end of run') { break }
            Start-Sleep -Seconds $SampleIntervalSeconds
        }
    } finally {
        $env:HEAP = $previousHeap
        $env:JAVA_TOOL_OPTIONS = $previousToolOptions
    }
    if ($timedOut) {
        Stop-LeftoverJMeter
        throw ("JMeter 超过 {0}s 仍未结束，已强制清理。plan={1}" -f $TimeoutSeconds, $PlanPath)
    }
    # 让 JMeter 把 .jtl 刷完再让调用方读
    Start-Sleep -Seconds 2

    $cpu = [pscustomobject]@{
        samples = $samples.Count
        median  = if ($samples.Count -gt 0) { [Math]::Round((Get-Percentile -Values $samples.ToArray() -Percentile 0.50), 1) } else { 0 }
        max     = if ($samples.Count -gt 0) { [Math]::Round(($samples | Measure-Object -Maximum).Maximum, 1) } else { 0 }
    }
    return $cpu
}
