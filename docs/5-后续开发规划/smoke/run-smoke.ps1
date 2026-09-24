[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:9000"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-My12306Api {
    param(
        [ValidateSet("GET", "POST")][string]$Method,
        [string]$Path,
        [object]$Body,
        [hashtable]$Headers = @{}
    )

    $parameters = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $Headers
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json; charset=utf-8"
        $parameters.Body = $Body | ConvertTo-Json -Depth 10 -Compress
    }
    $parameters.UseBasicParsing = $true
    try {
        $webResponse = Invoke-WebRequest @parameters
    } catch {
        throw "$Method $Path HTTP 请求失败：$($_.Exception.Message)"
    }
    # 服务端返回 application/json 但不带 charset，PS 5.1 会按 ISO-8859-1 解码，中文变乱码。
    # 第 63 行靠 -notmatch "用户名已存在" 来跳过布隆过滤器假阳性；乱码后该比较永不成立，
    # 于是第一次假阳性就 throw 中断。改为从原始字节按 UTF-8 手工解码。
    $response = [System.Text.Encoding]::UTF8.GetString($webResponse.RawContentStream.ToArray()) | ConvertFrom-Json
    if ([string]$response.code -ne "0") {
        throw "$Method $Path 业务失败：code=$($response.code), message=$($response.message)"
    }
    return $response
}

function Assert-Equal {
    param([object]$Actual, [object]$Expected, [string]$Message)
    if ([string]$Actual -ne [string]$Expected) {
        throw "$Message。期望=$Expected，实际=$Actual"
    }
}

$password = "D1-Smoke-123456"
$realName = "冒烟用户"

$registered = $false
for ($attempt = 1; $attempt -le 20 -and -not $registered; $attempt++) {
    $suffix = "$(Get-Date -Format 'yyyyMMddHHmmssfff')_$([Guid]::NewGuid().ToString('N').Substring(0, 6))"
    $phone = "139$(Get-Random -Minimum 10000000 -Maximum 99999999)"
    $username = "d1_smoke_$suffix"
    $mail = "$username@example.com"
    Write-Host "[1/9] 注册用户 $username"
    try {
        $null = Invoke-My12306Api POST "/api/user-service/register" @{
            username = $username
            password = $password
            realName = $realName
            idType = 1
            idCard = "11010519491231002X"
            phone = $phone
            mail = $mail
            userType = 0
            verifyState = 1
        } @{}
        $registered = $true
    } catch {
        if ($_.Exception.Message -notmatch "用户名已存在") { throw }
    }
}
if (-not $registered) { throw "连续 20 个随机用户名均被判定为已存在" }

Write-Host "[2/9] 登录并取得 token"
$login = Invoke-My12306Api POST "/api/user-service/v1/login" @{
    usernameOrMailOrPhone = $username
    password = $password
} @{}
$token = [string]$login.data.accessToken
if ([string]::IsNullOrWhiteSpace($token)) { throw "登录响应没有 accessToken" }
$authHeaders = @{ Authorization = $token }

Write-Host "[3/9] 新增并查询乘车人"
$null = Invoke-My12306Api POST "/api/user-service/passenger/save" @{
    realName = $realName
    idType = 1
    idCard = "11010519491231002X"
    discountType = 0
    phone = $phone
} $authHeaders
$passengers = Invoke-My12306Api GET "/api/user-service/passenger/query" $null $authHeaders
$passenger = @($passengers.data) | Where-Object { $_.realName -eq $realName } | Select-Object -First 1
if ($null -eq $passenger -or [string]::IsNullOrWhiteSpace([string]$passenger.id)) {
    throw "新增乘车人后未查询到 passengerId"
}

$departureDate = Get-Date -Format "yyyy-MM-dd"
$queryPath = "/api/ticket-service/ticket/query?fromStation=VNP&toStation=NGH&departureDate=$departureDate"
Write-Host "[4/9] 查询 VNP -> NGH 车票"
$ticketBefore = Invoke-My12306Api GET $queryPath $null @{}
$train = @($ticketBefore.data.trainList) | Where-Object { [string]$_.trainId -eq "1" } | Select-Object -First 1
if ($null -eq $train) { throw "查询结果中没有 trainId=1 的 G35" }
$seatClass = @($train.seatClassList) | Where-Object { [int]$_.type -eq 2 } | Select-Object -First 1
if ($null -eq $seatClass -or [int]$seatClass.quantity -le 0) { throw "G35 席别 2 没有可用余票" }
$remainingBefore = [int]$seatClass.quantity

Write-Host "[5/9] 购买一张 G35 车票"
$purchase = Invoke-My12306Api POST "/api/ticket-service/ticket/purchase" @{
    trainId = "1"
    passengers = @(@{ passengerId = [string]$passenger.id; seatType = 2 })
    chooseSeats = @()
    departure = "北京南"
    arrival = "宁波"
} $authHeaders
$orderSn = [string]$purchase.data.orderSn
if ([string]::IsNullOrWhiteSpace($orderSn)) { throw "购票响应没有 orderSn" }

Write-Host "[6/9] 验证订单为待支付"
$order = Invoke-My12306Api GET "/api/order-service/order/ticket/query?orderSn=$orderSn" $null $authHeaders
Assert-Equal $order.data.status 0 "购票后订单状态错误"

Write-Host "[7/9] 创建支付单"
$pay = Invoke-My12306Api POST "/api/pay-service/pay/create" @{
    orderSn = $orderSn
    channel = "MOCK_PAY"
    tradeType = "NATIVE"
} $authHeaders
$paySn = [string]$pay.data.paySn
if ([string]::IsNullOrWhiteSpace($paySn)) { throw "创建支付单后没有 paySn" }

Write-Host "[8/9] 调用模拟收银台"
$null = Invoke-My12306Api GET "/api/pay-service/pay/mock-cashier?paySn=$paySn" $null @{}

Write-Host "[9/9] 验证订单已支付且余票减少"
$paidOrder = $null
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    $paidOrder = Invoke-My12306Api GET "/api/order-service/order/ticket/query?orderSn=$orderSn" $null $authHeaders
    if ([int]$paidOrder.data.status -eq 10) { break }
    Start-Sleep -Milliseconds 200
}
Assert-Equal $paidOrder.data.status 10 "模拟支付后订单状态错误"

$ticketAfter = Invoke-My12306Api GET $queryPath $null @{}
$trainAfter = @($ticketAfter.data.trainList) | Where-Object { [string]$_.trainId -eq "1" } | Select-Object -First 1
$seatAfter = @($trainAfter.seatClassList) | Where-Object { [int]$_.type -eq 2 } | Select-Object -First 1
$remainingAfter = [int]$seatAfter.quantity
Assert-Equal $remainingAfter ($remainingBefore - 1) "支付后余票没有准确减少一张"

$summary = [ordered]@{
    username = $username
    passengerId = [string]$passenger.id
    trainId = "1"
    seatType = 2
    orderSn = $orderSn
    paySn = $paySn
    orderStatus = [int]$paidOrder.data.status
    remainingBefore = $remainingBefore
    remainingAfter = $remainingAfter
}
Write-Host "冒烟通过：订单已支付，余票 $remainingBefore -> $remainingAfter" -ForegroundColor Green
$summary | ConvertTo-Json -Depth 5
