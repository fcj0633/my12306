[CmdletBinding()]
param([string]$BaseUrl = "http://127.0.0.1:9000")

$ErrorActionPreference = "Stop"
. "$PSScriptRoot/redis-tools.ps1"

function Invoke-Api {
    param([string]$Method, [string]$Path, [object]$Body, [string]$Token)
    $headers = @{}
    if ($Token) { $headers.Authorization = $Token }
    $parameters = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; TimeoutSec = 60 }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json; charset=utf-8"
        $parameters.Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }
    $response = Invoke-RestMethod @parameters
    if ([string]$response.code -ne "0") { throw "$Method $Path 失败：$($response.message)" }
    return $response
}

function Purchase-Ticket {
    param($User)
    return Invoke-Api "POST" "/api/ticket-service/ticket/purchase" @{
        trainId = "1"
        passengers = @(@{ passengerId = [string]$User.passengerId; seatType = 2 })
        chooseSeats = @()
        departure = "北京南"
        arrival = "宁波"
    } $User.token
}

function Read-Bucket {
    param($Connection, [string]$Key)
    $values = @(Send-RedisCommand $Connection @("HGETALL", $Key))
    if ($values.Count -eq 1 -and $values[0] -is [System.Collections.IEnumerable] -and $values[0] -isnot [string]) {
        $values = @($values[0] | ForEach-Object { $_ })
    }
    $hash = [ordered]@{}
    for ($index = 0; $index -lt $values.Count; $index += 2) {
        $hash[[string]$values[$index]] = [int][string]$values[$index + 1]
    }
    return $hash
}

$users = @(Import-Csv "$PSScriptRoot/d1-users.csv" | Select-Object -First 3)
$bucketKey = "my12306-ticket-service:train_station_token_bucket:1_北京南_宁波"
$redis = Open-RedisConnection
try {
    & "$PSScriptRoot/reset-test-data.ps1" | Out-Host

    $cancelPurchase = Purchase-Ticket $users[0]
    $cancelOrderSn = [string]$cancelPurchase.data.orderSn
    $loadedHash = Read-Bucket $redis $bucketKey
    $ttlSeconds = [int](Send-RedisCommand $redis @("TTL", $bucketKey))
    $afterPurchase = [int](Send-RedisCommand $redis @("HGET", $bucketKey, "2"))
    $null = Invoke-Api "POST" "/api/order-service/order/ticket/close" @{ orderSn = $cancelOrderSn } $users[0].token
    $afterCancel = [int](Send-RedisCommand $redis @("HGET", $bucketKey, "2"))
    $null = Invoke-Api "POST" "/api/order-service/order/ticket/close" @{ orderSn = $cancelOrderSn } $users[0].token
    $afterRepeatedCancel = [int](Send-RedisCommand $redis @("HGET", $bucketKey, "2"))

    $expiredPurchase = Purchase-Ticket $users[1]
    $expiredOrderSn = [string]$expiredPurchase.data.orderSn
    $null = Send-RedisCommand $redis @("DEL", $bucketKey)
    $null = Invoke-Api "POST" "/api/order-service/order/ticket/close" @{ orderSn = $expiredOrderSn } $users[1].token
    $existsAfterExpiredCancel = [int](Send-RedisCommand $redis @("EXISTS", $bucketKey))

    & "$PSScriptRoot/reset-test-data.ps1" | Out-Host
    $payPurchase = Purchase-Ticket $users[2]
    $payOrderSn = [string]$payPurchase.data.orderSn
    $afterPayPurchase = [int](Send-RedisCommand $redis @("HGET", $bucketKey, "2"))
    $pay = Invoke-Api "POST" "/api/pay-service/pay/create" @{
        orderSn = $payOrderSn
        channel = "MOCK_PAY"
        tradeType = "NATIVE"
    } $users[2].token
    $paySn = [string]$pay.data.paySn
    $null = Invoke-Api "GET" "/api/pay-service/pay/mock-cashier?paySn=$paySn" $null $null
    $afterPayment = [int](Send-RedisCommand $redis @("HGET", $bucketKey, "2"))
    $paidOrder = Invoke-Api "GET" "/api/order-service/order/ticket/query?orderSn=$payOrderSn" $null $users[2].token

    $summary = [ordered]@{
        bucketKey = $bucketKey
        loadedFields = @($loadedHash.Keys)
        soldOutFieldsArePresent = $loadedHash.Contains("0") -and $loadedHash.Contains("1") -and $loadedHash.Contains("2")
        ttlSeconds = $ttlSeconds
        ttlIsPositive = $ttlSeconds -gt 0
        cancel = [ordered]@{
            orderSn = $cancelOrderSn
            tokenAfterPurchase = $afterPurchase
            tokenAfterCancel = $afterCancel
            tokenAfterRepeatedCancel = $afterRepeatedCancel
            returnedExactlyOnce = $afterCancel -eq ($afterPurchase + 1) -and $afterRepeatedCancel -eq $afterCancel
        }
        expiredBucketCancelDoesNotRecreate = $existsAfterExpiredCancel -eq 0
        payment = [ordered]@{
            orderSn = $payOrderSn
            paySn = $paySn
            tokenAfterPurchase = $afterPayPurchase
            tokenAfterPayment = $afterPayment
            tokenWasNotReturned = $afterPayment -eq $afterPayPurchase
            orderStatus = [int]$paidOrder.data.status
        }
    }
} finally {
    Close-RedisConnection $redis
}

$resultDirectory = "$PSScriptRoot/results/d2/runtime"
New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null
$summary | ConvertTo-Json -Depth 8 | Set-Content "$resultDirectory/summary.json" -Encoding utf8
$summary | ConvertTo-Json -Depth 8
