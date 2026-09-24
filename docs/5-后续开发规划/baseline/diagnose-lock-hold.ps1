# 301.835ms 锁持有时长的一次性诊断脚本
#
# 配套文档：docs/5-后续开发规划/02-结果与结论/D4-锁持有302ms成因验证.md 第七节
#
# 用法（三个模式，互不依赖）：
#   1) 结构事实 + 执行计划     .\diagnose-lock-hold.ps1 -Mode plan
#   2) 【最关键】同一批语句的本地耗时  .\diagnose-lock-hold.ps1 -Mode timing
#   3) 跑 S3 前后各取一次 I/O   .\diagnose-lock-hold.ps1 -Mode io-before
#                              <跑一轮 S3>
#                              .\diagnose-lock-hold.ps1 -Mode io-after
#
# 默认口径与 S3 压测一致：trainId=1、北京南→宁波、seatType=2（s3-full-flow.jmx:62-66）
#
# 【不改变任何数据】：-Mode timing 里的写语句用 `WHERE id IN (0)` 命中 0 行，且整批以 ROLLBACK 收尾。
#
# 注意 -Mode timing 的设计前提：
#   每次调用 mysql.exe 都是一个【新的会话/新连接】，所以进程启动 + 握手成本必须扣掉。
#   因此先测一条 `SELECT 1` 作为基线，再测目标语句，两者相减才是语句本身的成本。
#   （这也是不能用 SET profiling=1 + SHOW PROFILE 的原因：profiling 是会话级的，
#     换个 mysql.exe 进程就取不到，同一个进程里又没法稳定地知道 query_id。）

[CmdletBinding()]
param(
    [ValidateSet("plan", "timing", "io-before", "io-after")]
    [string]$Mode = "plan",
    [string]$MySql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [string]$MySqlUser = "root",
    [string]$MySqlPassword = "274226",
    [string]$Database = "12306_ticket",
    [int]$TrainId = 1,
    [int]$SeatType = 2,
    [string]$Departure = "北京南",
    [string]$Arrival = "宁波",
    [int]$Runs = 7
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$resultDirectory = Join-Path $PSScriptRoot "results"
New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null

function Invoke-Sql {
    param([string]$Sql, [switch]$Raw)
    $arguments = @("-u$MySqlUser", "-p$MySqlPassword", "--database=$Database", "--default-character-set=utf8mb4")
    if ($Raw) { $arguments += @("-N", "-B") }
    $arguments += "--execute=$Sql"
    return (& $MySql @arguments 2>&1)
}

function Get-MedianMs {
    param([double[]]$Sorted)
    if ($Sorted.Count -eq 0) { return 0 }
    $mid = [int][Math]::Floor($Sorted.Count / 2)
    if ($Sorted.Count % 2 -eq 1) { return $Sorted[$mid] }
    return ($Sorted[$mid - 1] + $Sorted[$mid]) / 2
}

function Measure-Batch {
    param([string]$Sql, [int]$Count)
    $samples = @()
    for ($i = 0; $i -lt $Count; $i++) {
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        $null = Invoke-Sql $Sql
        $watch.Stop()
        $samples += $watch.Elapsed.TotalMilliseconds
    }
    $sorted = @($samples | Sort-Object)
    return [ordered]@{
        medianMs = [Math]::Round((Get-MedianMs $sorted), 1)
        minMs    = [Math]::Round($sorted[0], 1)
        maxMs    = [Math]::Round($sorted[$sorted.Count - 1], 1)
    }
}

# SeatAllocator.allocate 的真实 SQL（MyBatis-Plus 会带上逻辑删除条件 del_flag=0）
function New-SeatQuery {
    return @"
SELECT id, train_id, carriage_number, seat_number, seat_type,
       start_station, end_station, price, seat_status, create_time, update_time, del_flag
FROM t_seat
WHERE del_flag = 0 AND train_id = $TrainId AND seat_type = $SeatType AND seat_status = 0
  AND start_station = '$Departure' AND end_station = '$Arrival'
ORDER BY carriage_number ASC, seat_number ASC
"@
}

# 前四个决定「扫描是否真的在打盘」，中间三个决定「提交是否在落盘",
# row_lock 两项用来【实证排除】行锁等待，最后三项是排序是否落盘的证据。
$statusNames = @(
    "Innodb_buffer_pool_reads",
    "Innodb_buffer_pool_read_requests",
    "Innodb_data_reads",
    "Innodb_data_read",
    "Innodb_data_fsyncs",
    "Innodb_data_writes",
    "Innodb_os_log_written",
    "Innodb_os_log_fsyncs",
    "Innodb_row_lock_waits",
    "Innodb_row_lock_time",
    "Innodb_row_lock_current_waits",
    "Created_tmp_tables",
    "Created_tmp_disk_tables",
    "Sort_merge_passes",
    "Sort_range",
    "Sort_scan",
    "Queries"
)

function Read-StatusSnapshot {
    param([string]$Path)
    $map = @{}
    foreach ($line in (Get-Content -LiteralPath $Path)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        # Windows PowerShell 5.1 的 Out-File -Encoding utf8 会写 BOM，且 .NET 的 Trim() 不剥 U+FEFF，
        # 不显式去掉会让第一个计数器名匹配不上、静默算成 0 —— 这里显式剥掉。
        $line = $line.TrimStart([char]0xFEFF)
        $parts = $line -split "`t"
        if ($parts.Count -ge 2) {
            $parsed = 0L
            if ([long]::TryParse($parts[1].Trim(), [ref]$parsed)) { $map[$parts[0].Trim()] = $parsed }
        }
    }
    return $map
}

switch ($Mode) {

    "plan" {
        Write-Host "=== 1. t_seat 的索引（预期：只有 PRIMARY + idx_train_id）===" -ForegroundColor Cyan
        Invoke-Sql "SHOW INDEX FROM t_seat" | Out-Host

        Write-Host "`n=== 2. 行数量级（预期：总量约 2.7 万；train 1 约 1 万；目标组合约 1 千，其中约 810 可售）===" -ForegroundColor Cyan
        Invoke-Sql "SELECT COUNT(*) AS total_rows FROM t_seat" -Raw | Out-Host
        Invoke-Sql "SELECT COUNT(*) AS train_rows FROM t_seat WHERE train_id = $TrainId" -Raw | Out-Host
        Invoke-Sql "SELECT COUNT(*) AS combo_rows FROM t_seat WHERE train_id = $TrainId AND seat_type = $SeatType AND start_station = '$Departure' AND end_station = '$Arrival'" -Raw | Out-Host
        Invoke-Sql "SELECT COUNT(*) AS combo_available FROM t_seat WHERE train_id = $TrainId AND seat_type = $SeatType AND start_station = '$Departure' AND end_station = '$Arrival' AND seat_status = 0" -Raw | Out-Host

        Write-Host "`n=== 3. 关键环境变量 ===" -ForegroundColor Cyan
        Invoke-Sql "SHOW VARIABLES WHERE Variable_name IN ('version','innodb_buffer_pool_size','innodb_flush_log_at_trx_commit','sync_binlog','innodb_io_capacity')" | Out-Host

        $seatQuery = New-SeatQuery
        Write-Host "`n=== 4. EXPLAIN（看 type / rows / key / Extra）===" -ForegroundColor Cyan
        Invoke-Sql "EXPLAIN $seatQuery" | Out-Host

        Write-Host "`n=== 5. EXPLAIN ANALYZE（MySQL 8.0.18+，给出【实际】行数与【实际】耗时）===" -ForegroundColor Cyan
        try {
            Invoke-Sql "EXPLAIN ANALYZE $seatQuery" | Out-Host
        } catch {
            Write-Host "EXPLAIN ANALYZE 不可用（需 MySQL 8.0.18+ 或语法差异），跳过——不影响 -Mode timing 的结论。" -ForegroundColor Yellow
        }

        Write-Host "`n【判读】type=ALL 或 rows 在 1 万~2.7 万 -> 扫描确实是全表级；Extra 含 Using filesort -> 排序无索引可用。" -ForegroundColor Green
        Write-Host "        但扫描的【实际代价】取决于缓冲池是否命中，要配合 -Mode timing 与 -Mode io-before/io-after 才能定案。" -ForegroundColor Green
    }

    "timing" {
        Write-Host "每条语句组各跑 $Runs 次，取中位数。全部只读或命中 0 行 + ROLLBACK，不改变任何数据。" -ForegroundColor Cyan
        Write-Host "目的是回答一个问题：同样的语句在同一台机器上本地跑，是 ~10ms 还是 ~300ms？`n" -ForegroundColor Cyan

        $seatQuery = New-SeatQuery

        $cases = [ordered]@{
            "A. 冷启动基线（SELECT 1）" = "SELECT 1"
            "B. 仅 allocate 那条 SELECT" = $seatQuery
            "C. 临界区五条语句骨架（含事务开始+回滚）" = @"
START TRANSACTION;
SELECT id FROM t_train_station_relation WHERE train_id = $TrainId AND departure = '$Departure' AND arrival = '$Arrival';
$seatQuery;
UPDATE t_seat SET seat_status = 1 WHERE id IN (0) AND seat_status = 0;
SELECT id FROM t_train_station_price WHERE train_id = $TrainId AND departure = '$Departure' AND arrival = '$Arrival' AND seat_type = $SeatType;
ROLLBACK;
"@
        }

        $measured = [ordered]@{}
        foreach ($name in $cases.Keys) {
            Write-Host "测量中：$name ..." -ForegroundColor DarkGray
            $measured[$name] = Measure-Batch -Sql $cases[$name] -Count $Runs
        }

        Write-Host "`n=== 结果（毫秒）===" -ForegroundColor Cyan
        Write-Host ("{0,-44} {1,12} {2,12} {3,12}" -f "语句组", "中位数", "最小", "最大") -ForegroundColor Cyan
        foreach ($name in $cases.Keys) {
            $row = $measured[$name]
            Write-Host ("{0,-44} {1,12} {2,12} {3,12}" -f $name, $row.medianMs, $row.minMs, $row.maxMs)
        }

        $baseline = [double]$measured["A. 冷启动基线（SELECT 1）"].medianMs
        $alloc = [double]$measured["B. 仅 allocate 那条 SELECT"].medianMs
        $shape = [double]$measured["C. 临界区五条语句骨架（含事务开始+回滚）"].medianMs

        $allocNet = [Math]::Round($alloc - $baseline, 1)
        $shapeNet = [Math]::Round($shape - $baseline, 1)

        Write-Host "`n=== 扣掉 mysql.exe 启动成本后的净耗时 ===" -ForegroundColor Cyan
        Write-Host ("  allocate 那条 SELECT 净耗时        : {0} ms" -f $allocNet)
        Write-Host ("  五条语句骨架净耗时                 : {0} ms" -f $shapeNet)
        Write-Host ("  与应用实测的 302ms 相差            : {0} ms" -f ([Math]::Round(302 - $shapeNet, 1)))

        Write-Host "`n=== 判读（这一步直接决定下一步往哪走）===" -ForegroundColor Green
        Write-Host "① 五条骨架净耗时 ≈ 250~300ms  -> 302ms 就在【MySQL/磁盘】上。再看 allocate 占多少："
        Write-Host "      若 allocate 净耗时占了大头 -> 候选 A/E（全表级扫描）成立 -> 加索引 + LIMIT"
        Write-Host "      若 allocate 很便宜但整体仍慢 -> 候选 B（commit 落盘）=> 做 -Mode io-before/io-after，并考虑临时 flush_log_at_trx_commit=2 做对照"
        Write-Host "② 五条骨架净耗时只有 10~30ms  -> MySQL 没问题，302ms 在【应用侧】"
        Write-Host "      => 候选 C（StdOutImpl 同步写日志）/ Hikari / JVM；这时加索引救不了你"
        Write-Host "③ 若 allocate 单人净耗时很小、但 io 快照显示物理读很大 -> 说明【并发下】才打盘，按并发场景重测"
    }

    "io-before" {
        $statusList = ($statusNames | ForEach-Object { "'$_'" }) -join ","
        $sql = "SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME IN ($statusList)"
        $outFile = Join-Path $resultDirectory "diag-io-before.txt"
        Invoke-Sql $sql -Raw | Out-File -FilePath $outFile -Encoding ascii
        Write-Host "已记录压测前快照：$outFile" -ForegroundColor Green
        Write-Host "现在去跑一轮 S3，然后执行： .\diagnose-lock-hold.ps1 -Mode io-after" -ForegroundColor Green
    }

    "io-after" {
        $statusList = ($statusNames | ForEach-Object { "'$_'" }) -join ","
        $sql = "SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME IN ($statusList)"
        $beforeFile = Join-Path $resultDirectory "diag-io-before.txt"
        $afterFile = Join-Path $resultDirectory "diag-io-after.txt"
        Invoke-Sql $sql -Raw | Out-File -FilePath $afterFile -Encoding utf8
        Write-Host "已记录压测后快照：$afterFile" -ForegroundColor Green

        if (-not (Test-Path -LiteralPath $beforeFile)) {
            Write-Host "没有找到压测前快照，无法做差。请先跑 -Mode io-before。" -ForegroundColor Yellow
            return
        }

        $before = Read-StatusSnapshot $beforeFile
        $after = Read-StatusSnapshot $afterFile

        Write-Host "`n=== 本轮 S3 造成的增量 ===" -ForegroundColor Cyan
        Write-Host ("{0,-36} {1,16} {2,16} {3,16}" -f "counter", "before", "after", "delta") -ForegroundColor Cyan
        foreach ($name in $statusNames) {
            $b = if ($before.ContainsKey($name)) { $before[$name] } else { 0L }
            $a = if ($after.ContainsKey($name)) { $after[$name] } else { 0L }
            Write-Host ("{0,-36} {1,16} {2,16} {3,16}" -f $name, $b, $a, ($a - $b))
        }

        Write-Host "`n=== 判读 ===" -ForegroundColor Green
        Write-Host "Innodb_buffer_pool_reads 显著 > 0  -> 扫描真的在打盘（候选 A/E 成立，加索引 + LIMIT 收益大）"
        Write-Host "Innodb_buffer_pool_reads ≈ 0      -> 扫描全在内存里，绝不是 302ms 的主角（候选 A/E 排除）"
        Write-Host "Innodb_data_fsyncs ≈ 100          -> 每次 commit 确实在落盘；配合磁盘 91% 满，候选 B 值得优先验证"
        Write-Host "Innodb_row_lock_waits ≈ 0         -> 【实证】行锁等待不成立"
        Write-Host "Created_tmp_disk_tables / Sort_merge_passes > 0 -> filesort 落到磁盘了，排序本身也在花钱"
    }
}
