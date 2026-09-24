[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:9000",
    [int]$Count = 100,
    [string]$OutputFile
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# PS 5.1 在 param() 的默认值里取不到 $PSScriptRoot（为空），会静默指到盘根，
# 而且照样报成功。默认值一律改到脚本体里计算。
if (-not $OutputFile) { $OutputFile = Join-Path $PSScriptRoot "d1-users.csv" }
$password = "D1-Load-123456"

function New-TestIdCard {
    param([int]$Sequence)
    $body = "11010519900101$($Sequence.ToString('000'))"
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
    $parameters = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $Headers; TimeoutSec = 30 }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json; charset=utf-8"
        $parameters.Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }
    $parameters.UseBasicParsing = $true
    $webResponse = Invoke-WebRequest @parameters
    # 服务端返回的是 application/json 但不带 charset，PS 5.1 会按 ISO-8859-1 解码，
    # 于是 "用户名已存在" 变成乱码，与源码里的中文字面量比较永不相等，
    # 脚本会在第 55 行抛错中止。这里改为从原始字节按 UTF-8 手工解码再解析。
    $json = [System.Text.Encoding]::UTF8.GetString($webResponse.RawContentStream.ToArray())
    $response = $json | ConvertFrom-Json
    if (-not $AllowFailure -and [string]$response.code -ne "0") {
        throw "$Method $Path 失败：$($response.message)"
    }
    return $response
}

$rows = [System.Collections.Generic.List[string]]::new()
$rows.Add("username,token,passengerId")
for ($i = 1; $i -le $Count; $i++) {
    $index = $i.ToString("000")
    $realName = "基线用户$index"
    $idCard = New-TestIdCard $i

    $login = $null
    for ($attempt = 0; $attempt -lt 20 -and $null -eq $login; $attempt++) {
        $username = "d1_test_v3_$($index)_$($attempt.ToString('00'))"
        $phone = "136$($i.ToString('0000'))$($attempt.ToString('0000'))"
        $mail = "$username@example.com"
        $register = Invoke-Api POST "/api/user-service/register" @{
            username = $username; password = $password; realName = $realName
            idType = 1; idCard = $idCard; phone = $phone; mail = $mail
            userType = 0; verifyState = 1
        } @{} -AllowFailure
        if ([string]$register.code -ne "0" -and [string]$register.message -ne "用户名已存在") {
            throw "准备用户 $username 失败：$($register.message)"
        }
        try {
            $login = Invoke-Api POST "/api/user-service/v1/login" @{
                usernameOrMailOrPhone = $username; password = $password
            }
        } catch {
            if ([string]$register.message -ne "用户名已存在") {
                throw "准备用户 $username 登录失败：$($_.Exception.Message)"
            }
        }
    }
    if ($null -eq $login) { throw "用户序号 $index 的 20 个候选用户名均不可用" }
    $token = [string]$login.data.accessToken
    $headers = @{ Authorization = $token }
    $passengerList = Invoke-Api GET "/api/user-service/passenger/query" $null $headers
    $passenger = @($passengerList.data) | Select-Object -First 1
    if ($null -eq $passenger) {
        $null = Invoke-Api POST "/api/user-service/passenger/save" @{
            realName = $realName; idType = 1; idCard = $idCard
            discountType = 0; phone = $phone
        } $headers
        $passengerList = Invoke-Api GET "/api/user-service/passenger/query" $null $headers
        $passenger = @($passengerList.data) | Select-Object -First 1
    }
    if ($null -eq $passenger) { throw "用户 $username 没有可用乘车人" }
    $rows.Add("$username,$token,$($passenger.id)")
    Write-Progress -Activity "准备 D1 压测用户" -Status "$i / $Count" -PercentComplete (($i / $Count) * 100)
}

[System.IO.File]::WriteAllLines((Resolve-Path (Split-Path $OutputFile -Parent)).Path + "\" + (Split-Path $OutputFile -Leaf), $rows,
        [System.Text.UTF8Encoding]::new($false))
Write-Host "已准备 $Count 个用户：$OutputFile" -ForegroundColor Green
