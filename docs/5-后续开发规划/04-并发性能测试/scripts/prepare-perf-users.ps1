[CmdletBinding()]
param(
    [int]$Count = 400,
    [string]$OutputFile,
    [string]$BaseUrl = "http://127.0.0.1:9000",
    [string]$Password = "D1-Load-123456"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# 为什么自己写而不用 baseline/prepare-users.ps1 注册：
#   1) 它从序号 001 开始，与库里已有的 d1_test_v3_001..100 撞名，会走"注册返回已存在 → 转登录"的
#      分支；布隆计数器被重置后该分支的返回消息不确定，容易在准备阶段就抛错；
#   2) 它的证件号由序号推导，而序号 001..100 的证件号已被老用户占用，会撞 t_user 的证件唯一索引。
# 所以这里换一个全新的命名空间（用户名 perf400_*、证件号 19900201 段、手机号 138 段），
# 与既有数据零冲突。输出格式与 prepare-users.ps1 完全一致。

if (-not $OutputFile) { $OutputFile = Join-Path $PSScriptRoot "..\results\users.csv" }
$OutputFile = [System.IO.Path]::GetFullPath($OutputFile)
New-Item -ItemType Directory -Force -Path (Split-Path $OutputFile -Parent) | Out-Null
$password = $Password

function New-TestIdCard {
    param([int]$Sequence)
    # 110105 + 19900201 + 三位序号，与既有用户的 19900101 段区分
    $body = "11010519900201$($Sequence.ToString('000'))"
    $weights = 7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2
    $checks = "10X98765432"
    $sum = 0
    for ($index = 0; $index -lt $body.Length; $index++) {
        $sum += [int]::Parse([string]$body[$index]) * $weights[$index]
    }
    return "$body$($checks[$sum % 11])"
}

function Invoke-Api {
    param([string]$Method, [string]$Path, [object]$Body, [hashtable]$Headers = @{}, [switch]$AllowFailure)
    $parameters = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $Headers; TimeoutSec = 30; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json; charset=utf-8"
        $parameters.Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }
    $webResponse = Invoke-WebRequest @parameters
    $json = [System.Text.Encoding]::UTF8.GetString($webResponse.RawContentStream.ToArray())
    $response = $json | ConvertFrom-Json
    if (-not $AllowFailure -and [string]$response.code -ne "0") {
        throw "$Method $Path 失败：$($response.message)"
    }
    return $response
}

$rows = [System.Collections.Generic.List[string]]::new()
$rows.Add("username,token,passengerId")
$ok = 0
$failed = 0
$started = Get-Date

for ($i = 1; $i -le $Count; $i++) {
    $index = $i.ToString("000")
    $username = "perf400_$index"
    $idCard = New-TestIdCard $i
    $phone = "138$(20000000 + $i)"
    $realName = "压测用户$index"
    try {
        $register = Invoke-Api POST "/api/user-service/register" @{
            username = $username; password = $password; realName = $realName
            idType = 1; idCard = $idCard; phone = $phone; mail = "$username@example.com"
            userType = 0; verifyState = 1
        } -AllowFailure
        # 只容忍"已存在"（重跑时的正常情况）；其它一律抛出，否则真实失败会被后续的
        # "登录失败：账号不存在" 掩盖掉，排查时看不到第一现场。
        if ([string]$register.code -ne "0" -and [string]$register.message -notmatch "已存在") {
            throw "注册失败：code=$($register.code) message=$($register.message)"
        }
        $login = Invoke-Api POST "/api/user-service/v1/login" @{
            usernameOrMailOrPhone = $username; password = $password
        }
        $token = [string]$login.data.accessToken
        $headers = @{ Authorization = $token }
        $list = Invoke-Api GET "/api/user-service/passenger/query" $null $headers
        $passenger = @($list.data) | Where-Object { $_.realName -eq $realName } | Select-Object -First 1
        if ($null -eq $passenger) {
            $null = Invoke-Api POST "/api/user-service/passenger/save" @{
                realName = $realName; idType = 1; idCard = $idCard; discountType = 0; phone = $phone
            } $headers
            $list = Invoke-Api GET "/api/user-service/passenger/query" $null $headers
            $passenger = @($list.data) | Where-Object { $_.realName -eq $realName } | Select-Object -First 1
        }
        if ($null -eq $passenger) { throw "没有可用乘车人" }
        $rows.Add("$username,$token,$($passenger.id)")
        $ok++
    } catch {
        $failed++
        Write-Host ("  [{0}] 失败：{1}" -f $username, $_.Exception.Message) -ForegroundColor Yellow
    }
    if ($i % 25 -eq 0) {
        $elapsed = ((Get-Date) - $started).TotalSeconds
        Write-Host ("  进度 {0}/{1}  成功 {2}  失败 {3}  已用 {4:N0}s" -f $i, $Count, $ok, $failed, $elapsed)
    }
}

[System.IO.File]::WriteAllLines($OutputFile, $rows, [System.Text.UTF8Encoding]::new($false))
$total = ((Get-Date) - $started).TotalSeconds
Write-Host ""
Write-Host ("可用用户 {0} 个    失败 {1}    用时 {2:N0}s    输出 {3}" -f $ok, $failed, $total, $OutputFile) -ForegroundColor Green
if ($ok -lt $Count) { Write-Host "⚠️ 未凑满，并发点不要超过可用用户数" -ForegroundColor Yellow }
