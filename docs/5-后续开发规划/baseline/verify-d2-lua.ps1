[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
. "$PSScriptRoot/redis-tools.ps1"

$takeScript = Get-Content "$PSScriptRoot/../../../12306/my12306/services/ticket-services/src/main/resources/lua/take_token_from_bucket.lua" -Raw
$returnScript = Get-Content "$PSScriptRoot/../../../12306/my12306/services/ticket-services/src/main/resources/lua/return_token_to_bucket.lua" -Raw
$prefix = "my12306-ticket-service:test:d2-lua:$([Guid]::NewGuid().ToString('N'))"
$connection = Open-RedisConnection
try {
    $atomicKey = "$prefix`:atomic"
    $null = Send-RedisCommand $connection @("HSET", $atomicKey, "0", "2", "2", "0")
    $insufficient = Send-RedisCommand $connection @("EVAL", $takeScript, "1", $atomicKey, "0", "1", "2", "1")
    $unchanged = Send-RedisCommand $connection @("HGET", $atomicKey, "0")
    $missing = Send-RedisCommand $connection @("EVAL", $takeScript, "1", $atomicKey, "1", "1")

    $absentKey = "$prefix`:absent"
    $returnAbsent = Send-RedisCommand $connection @("EVAL", $returnScript, "1", $absentKey, "0", "1")
    $absentStillMissing = (Send-RedisCommand $connection @("EXISTS", $absentKey)) -eq 0

    $concurrentKey = "$prefix`:concurrent"
    $null = Send-RedisCommand $connection @("HSET", $concurrentKey, "0", "10")
} finally {
    Close-RedisConnection $connection
}

$redisTools = "$PSScriptRoot/redis-tools.ps1"
$results = 1..50 | ForEach-Object -Parallel {
    . $using:redisTools
    $connection = Open-RedisConnection
    try {
        Send-RedisCommand $connection @("EVAL", $using:takeScript, "1", $using:concurrentKey, "0", "1")
    } finally {
        Close-RedisConnection $connection
    }
} -ThrottleLimit 50

$connection = Open-RedisConnection
try {
    $finalTokens = [int](Send-RedisCommand $connection @("HGET", $concurrentKey, "0"))
    $null = Send-RedisCommand $connection @("DEL", $atomicKey, $absentKey, $concurrentKey)
} finally {
    Close-RedisConnection $connection
}

$summary = [ordered]@{
    insufficientReturnsZero = $insufficient -eq 0
    multiSeatFailureKeepsOtherField = $unchanged -eq "2"
    missingFieldReturnsMinusOne = $missing -eq -1
    returnAbsentReturnsZero = $returnAbsent -eq 0
    returnAbsentDoesNotCreate = $absentStillMissing
    concurrentAttempts = $results.Count
    concurrentSuccess = @($results | Where-Object { $_ -eq 1 }).Count
    concurrentRejected = @($results | Where-Object { $_ -eq 0 }).Count
    finalTokens = $finalTokens
}

$resultDirectory = "$PSScriptRoot/results/d2/lua"
New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null
$summary | ConvertTo-Json | Set-Content "$resultDirectory/summary.json" -Encoding utf8
$summary | ConvertTo-Json
