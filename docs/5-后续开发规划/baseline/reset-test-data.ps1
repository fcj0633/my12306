[CmdletBinding()]
param(
    [string]$MySql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [string]$MySqlUser = "root",
    [string]$MySqlPassword = "274226",
    [string]$RedisHost = "192.168.204.128",
    [int]$RedisPort = 6379,
    [string]$RedisPassword = "274226"
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot/redis-tools.ps1"

$sql = @"
UPDATE 12306_ticket.t_seat s
JOIN 12306_ticket.t_ticket t
  ON s.train_id = t.train_id
 AND s.start_station = t.start_station
 AND s.end_station = t.end_station
 AND s.seat_type = t.seat_type
 AND s.carriage_number = t.carriage_number
 AND s.seat_number = t.seat_number
SET s.seat_status = 0, s.update_time = NOW()
WHERE t.username LIKE 'd1_test_%' OR t.username LIKE 'd1_smoke_%';

DELETE p FROM 12306_pay.t_pay p
JOIN 12306_order.t_order o ON o.order_sn = p.order_sn
WHERE o.username LIKE 'd1_test_%' OR o.username LIKE 'd1_smoke_%';

DELETE oi FROM 12306_order.t_order_item oi
WHERE oi.username LIKE 'd1_test_%' OR oi.username LIKE 'd1_smoke_%';

DELETE o FROM 12306_order.t_order o
WHERE o.username LIKE 'd1_test_%' OR o.username LIKE 'd1_smoke_%';

DELETE t FROM 12306_ticket.t_ticket t
WHERE t.username LIKE 'd1_test_%' OR t.username LIKE 'd1_smoke_%';
"@

# mysql.exe 会向 stderr 打 "Using a password on the command line interface can be insecure."。
# PS 5.1 在 $ErrorActionPreference = "Stop" 下会把原生 stderr 包成 NativeCommandError 并直接中止，
# 于是脚本第一次调用就死在这里。只在这一次原生调用周围放宽为 Continue；
# 真正的失败仍由下面的 $LASTEXITCODE 判定，不会被吞掉。
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $MySql "-u$MySqlUser" "-p$MySqlPassword" "--database=12306_ticket" "--default-character-set=utf8mb4" "--execute=$sql"
$ErrorActionPreference = $previousPreference
if ($LASTEXITCODE -ne 0) { throw "MySQL 测试数据清理失败，exitCode=$LASTEXITCODE" }

$redis = $null
try {
    $redis = Open-RedisConnection -HostName $RedisHost -Port $RedisPort -Password $RedisPassword
    $keys = [System.Collections.Generic.List[string]]::new()
    $patterns = @(
        "my12306-ticket-service:train_station_remaining_ticket:*",
        "my12306-ticket-service:train_station_token_bucket:*"
    )
    foreach ($pattern in $patterns) {
        $cursor = "0"
        do {
            $scan = @(Send-RedisCommand $redis @("SCAN", $cursor, "MATCH", $pattern, "COUNT", "1000"))
            $cursor = [string]$scan[0]
            foreach ($key in @($scan[1])) {
                if ($key -and -not $keys.Contains([string]$key)) { $keys.Add([string]$key) }
            }
        } while ($cursor -ne "0")
    }
    for ($offset = 0; $offset -lt $keys.Count; $offset += 500) {
        $last = [Math]::Min($offset + 499, $keys.Count - 1)
        $command = [System.Collections.Generic.List[string]]::new()
        $command.Add("UNLINK")
        for ($index = $offset; $index -le $last; $index++) {
            if ($keys[$index]) { $command.Add([string]$keys[$index]) }
        }
        if ($command.Count -gt 1) { [void](Send-RedisCommand $redis $command.ToArray()) }
    }
    Write-Host "已清理测试交易数据，并删除 $($keys.Count) 个展示余票/令牌桶 Key" -ForegroundColor Green
} finally {
    Close-RedisConnection $redis
}
