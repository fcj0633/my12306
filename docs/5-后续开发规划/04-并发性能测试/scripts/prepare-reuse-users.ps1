[CmdletBinding()]
param(
    [int]$Count = 400,
    [string]$OutputFile,
    [string]$BaseUrl = "http://127.0.0.1:9000",
    [string]$Password = "D1-Load-123456",
    [string]$MySql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [string]$MySqlUser = "root",
    [string]$MySqlPassword = "274226"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# 为什么不用 prepare-users.ps1 注册新用户：
#   注册链路的用户名可用性判断依赖 RBloomFilter（RBloomFilterConfiguration 里
#   tryInit(64L, 0.03D) —— 预期插入量只有 64）。实测本环境注册到第 101 个用户起，
#   20 个候选用户名全部被判为"用户名已存在"，即布隆已饱和、误判率接近 100%。
#   而【登录不经过布隆过滤器】，所以直接复用数据库里已存在的用户即可，无需改任何代码。
#
# 输出与 prepare-users.ps1 完全一致：username,token,passengerId（token 含 "Bearer " 前缀）

$password = $Password
if (-not $OutputFile) { $OutputFile = Join-Path $PSScriptRoot "..\results\users.csv" }
$OutputFile = [System.IO.Path]::GetFullPath($OutputFile)
New-Item -ItemType Directory -Force -Path (Split-Path $OutputFile -Parent) | Out-Null

function Invoke-Api {
    param([string]$Method, [string]$Path, [object]$Body, [hashtable]$Headers = @{}, [switch]$AllowFailure)
    $parameters = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $Headers; TimeoutSec = 30; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json; charset=utf-8"
        $parameters.Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }
    $webResponse = Invoke-WebRequest @parameters
    # 服务端返回 application/json 但不带 charset，PS 5.1 会按 ISO-8859-1 解码，中文变乱码。
    # 从原始字节按 UTF-8 手工解码（与 prepare-users.ps1 同一套做法）。
    $json = [System.Text.Encoding]::UTF8.GetString($webResponse.RawContentStream.ToArray())
    $response = $json | ConvertFrom-Json
    if (-not $AllowFailure -and [string]$response.code -ne "0") {
        throw "$Method $Path 失败：$($response.message)"
    }
    return $response
}

function Get-SqlValue {
    param([string]$Sql)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & $MySql "-u$MySqlUser" "-p$MySqlPassword" "--default-character-set=utf8mb4" "-N" "-B" "--execute=$Sql" 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) { throw "MySQL 失败：$Sql`n$out" }
    return @($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' })
}

# 1) 枚举数据库中已存在的用户名。
#    注意：不能用 information_schema.TABLE_ROWS 过滤"非空表"——InnoDB 的 TABLE_ROWS 是估算值，
#    实测 160 张分片表里大量表被估成 0，会把已存在的用户漏掉一半（实测 254 个只枚举出 100 个）。
#    所以老老实实逐表查。
Write-Host "[1/3] 枚举已存在的用户名..."
$tables = Get-SqlValue -Sql @"
SELECT CONCAT(TABLE_SCHEMA,'.',TABLE_NAME) FROM information_schema.TABLES
WHERE TABLE_SCHEMA IN ('12306_user_0','12306_user_1') AND TABLE_NAME REGEXP '^t_user_[0-9]+$';
"@
$usernames = New-Object System.Collections.Generic.List[string]
foreach ($table in $tables) {
    if (-not $table) { continue }
    $names = Get-SqlValue -Sql "SELECT username FROM $table WHERE username LIKE 'd1_test_v3_%';"
    foreach ($n in $names) { if ($n) { $usernames.Add($n) } }
}
$usernames = @($usernames | Sort-Object -Unique)
Write-Host "      已存在用户 $($usernames.Count) 个"
if ($usernames.Count -eq 0) { throw "数据库里没有可复用的用户" }

# 2) 逐个登录并确保有乘车人
Write-Host "[2/3] 登录并准备乘车人（最多 $Count 个）..."
$rows = [System.Collections.Generic.List[string]]::new()
$rows.Add("username,token,passengerId")
$ok = 0
$loginFailed = 0
$noPassenger = 0
foreach ($username in $usernames) {
    if ($ok -ge $Count) { break }
    $token = $null
    try {
        $login = Invoke-Api POST "/api/user-service/v1/login" @{ usernameOrMailOrPhone = $username; password = $password }
        $token = [string]$login.data.accessToken
    } catch {
        $loginFailed++
        continue
    }
    if ([string]::IsNullOrWhiteSpace($token)) { $loginFailed++; continue }
    $authHeaders = @{ Authorization = $token }
    try {
        $passengerList = Invoke-Api GET "/api/user-service/passenger/query" $null $authHeaders
        $passenger = @($passengerList.data) | Select-Object -First 1
        if ($null -eq $passenger) {
            $suffix = $username -replace '[^0-9]', ''
            $null = Invoke-Api POST "/api/user-service/passenger/save" @{
                realName = "压测用户$suffix"; idType = 1; idCard = "11010519900101$($suffix.PadLeft(3,'0').Substring(0,3))"
                discountType = 0; phone = "137$($suffix.PadLeft(8,'0').Substring(0,8))"
            } $authHeaders
            $passengerList = Invoke-Api GET "/api/user-service/passenger/query" $null $authHeaders
            $passenger = @($passengerList.data) | Select-Object -First 1
        }
        if ($null -eq $passenger) { $noPassenger++; continue }
        $rows.Add("$username,$token,$($passenger.id)")
        $ok++
    } catch {
        $noPassenger++
    }
    if ($ok % 25 -eq 0) { Write-Progress -Activity "登录复用用户" -Status "$ok / $Count" -PercentComplete (($ok / [Math]::Max(1,$Count)) * 100) }
}

# 3) 落盘
Write-Host "[3/3] 写出 $OutputFile"
[System.IO.File]::WriteAllLines($OutputFile, $rows, [System.Text.UTF8Encoding]::new($false))

Write-Host ""
Write-Host ("可用用户 {0} 个    登录失败 {1}    无乘车人 {2}" -f $ok, $loginFailed, $noPassenger) -ForegroundColor Green
if ($ok -lt $Count) {
    Write-Host ("⚠️ 只凑到 {0} 个，少于请求的 {1} 个 —— 并发点不要超过 {0}，否则线程会共享用户。" -f $ok, $Count) -ForegroundColor Yellow
}
