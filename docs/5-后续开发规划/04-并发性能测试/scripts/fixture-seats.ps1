[CmdletBinding()]
param(
    # s1 = 单键充足库存；s2 = 多键分散；s3 = 同键热点超订
    [Parameter(Mandatory = $true)][ValidateSet("s1", "s2", "s3")][string]$Scenario,
    # s3 用的可售张数（超订规模）
    [int]$Available = 10,
    [string]$MySql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [string]$MySqlUser = "root",
    [string]$MySqlPassword = "274226",
    # 跳过 reset-test-data.ps1（只复位座位池、不动交易数据）
    [switch]$SkipReset
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# 目标池：与 P0/P1/P2 一直使用的那个区间保持一致，便于与历史口径对照
$Pool = "train_id=1 AND seat_type=2 AND start_station='北京南' AND end_station='宁波' AND del_flag=0"

# mysql.exe 会向 stderr 打密码警告；PS 5.1 在 Stop 下会把原生 stderr 当错误直接中止脚本。
# 只在原生调用周围放宽，真正的失败仍由 $LASTEXITCODE 判定（与 reset-test-data.ps1 同一套做法）。
function Invoke-Sql {
    param([string]$Sql)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $result = & $MySql "-u$MySqlUser" "-p$MySqlPassword" "--default-character-set=utf8mb4" "-N" "-B" "--execute=$Sql" 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) { throw "MySQL 执行失败，exitCode=$code`nSQL: $Sql`n输出: $result" }
    return @($result | Where-Object { $_ -notmatch '^mysql: \[Warning\]' })
}

# 1) 清交易数据 + 清 Redis 的两个 key 族（令牌桶按可售数装载、TTL 10 分钟，
#    不清就会跨轮沿用上一轮的余量 —— 这是真实存在的口径陷阱，不是洁癖）。
if (-not $SkipReset) {
    $resetScript = Join-Path $PSScriptRoot "..\..\baseline\reset-test-data.ps1"
    if (-not (Test-Path -LiteralPath $resetScript)) { throw "找不到 $resetScript" }
    Write-Host "[1/2] 复位交易数据与 Redis 缓存..."
    & $resetScript
}

# 2) 复位座位池
Write-Host "[2/2] 复位座位池（场景 $Scenario）..."
switch ($Scenario) {
    "s1" {
        # 整个目标池全可售：库存不是约束，被测的是席别锁
        $null = Invoke-Sql "UPDATE 12306_ticket.t_seat SET seat_status=0, update_time=NOW() WHERE $Pool;"
    }
    "s2" {
        # 全部席别的池都复位成全可售：S2 要跨 41 把不同的席别锁分散（seatType=1 有 28 个池、
        # seatType=2 有 13 个池），并留足座位避免轮次中途把池抽干。
        $null = Invoke-Sql "UPDATE 12306_ticket.t_seat SET seat_status=0, update_time=NOW() WHERE del_flag=0;"
    }
    "s3" {
        # 先全部售罄，再放回 $Available 张 —— 制造确定性的超订。
        # 用 UPDATE ... ORDER BY ... LIMIT 而不是子查询，避开 MySQL "不能边更新边查同表" 的限制。
        $null = Invoke-Sql "UPDATE 12306_ticket.t_seat SET seat_status=2, update_time=NOW() WHERE $Pool;"
        $null = Invoke-Sql "UPDATE 12306_ticket.t_seat SET seat_status=0, update_time=NOW() WHERE $Pool AND seat_status=2 ORDER BY id LIMIT $Available;"
    }
}

# 3) 打印结果（每个场景都要能一眼确认池子对不对）
Write-Host ""
Write-Host "目标池状态（t1 / seat_type=2 / 北京南->宁波）："
$pooled = Invoke-Sql "SELECT CONCAT('  可售=', SUM(seat_status=0), ' 已锁=', SUM(seat_status=1), ' 售罄=', SUM(seat_status=2), ' 合计=', COUNT(*)) FROM 12306_ticket.t_seat WHERE $Pool;"
$pooled | ForEach-Object { Write-Host $_ }

if ($Scenario -eq "s2") {
    $spread = Invoke-Sql "SELECT CONCAT('  seatType=2 可用的池数=', COUNT(DISTINCT CONCAT(train_id,'_',start_station,'_',end_station)), ' 合计可售=', SUM(seat_status=0)) FROM 12306_ticket.t_seat WHERE seat_type=2 AND del_flag=0;"
    $spread | ForEach-Object { Write-Host $_ }
}

Write-Host ""
Write-Host "fixture 完成" -ForegroundColor Green
