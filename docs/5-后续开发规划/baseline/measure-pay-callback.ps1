[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:9000",
    [string]$UserFile,
    [int]$Count = 30,
    [int]$Warmup = 3,
    [string]$Label = "unnamed"
)

# 目的：单独测【支付回调接口自身】的响应时间，用于 feign / mq 两种通知模式的对比。
#
# 为什么不用 S3 的结果来回答这个问题：
#   s3-full-flow.jmx 在模拟付款后要【每 500ms 轮询一次】订单状态直到变成已支付。
#   feign 模式下订单在回调返回时就已是已支付 → 第一次轮询就命中，几乎不等待；
#   mq 模式下订单是异步变已支付的 → 要等一个或多个 500ms 周期。
#   于是 S3 的"平均"里混进了一段【由脚本口径造成的等待】，天然惩罚异步方案。
#
# 本脚本的做法：完成"下单 + 建支付单"之后，只对回调那一次调用计时，不轮询。
# 这样量到的就是"支付回调这件事本身花了多久"：
#   feign 模式 ≈ 本地事务 + 3 次 Feign（查订单 + 回调 order + 回调 ticket）
#   mq    模式 ≈ 本地事务 + 1 次消息发送
#
# 注意：一次支付单只能被推进一次，所以每次迭代都要新下单，否则第二次回调会走
# "已是 PAID" 的短路分支，测出来会偏小。

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not $UserFile) { $UserFile = Join-Path $PSScriptRoot "d1-users.csv" }
if (-not (Test-Path $UserFile)) { throw "找不到压测用户文件：$UserFile（先跑 prepare-users.ps1）" }

function Invoke-Api {
    param([string]$Method, [string]$Path, [object]$Body, [hashtable]$Headers = @{})
    $parameters = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $Headers; TimeoutSec = 60; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json; charset=utf-8"
        $parameters.Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }
    $webResponse = Invoke-WebRequest @parameters
    # 服务端返回 application/json 但不带 charset，PS 5.1 会按 ISO-8859-1 解码导致中文乱码
    $json = [System.Text.Encoding]::UTF8.GetString($webResponse.RawContentStream.ToArray())
    $response = $json | ConvertFrom-Json
    if ([string]$response.code -ne "0") {
        throw "$Method $Path 失败：$($response.message)"
    }
    return $response
}

$users = @(Import-Csv -LiteralPath $UserFile)
if ($users.Count -eq 0) { throw "用户文件里没有数据" }

$elapsed = [System.Collections.Generic.List[double]]::new()
$failures = 0
$total = $Warmup + $Count

for ($i = 0; $i -lt $total; $i++) {
    $user = $users[$i % $users.Count]
    $headers = @{ Authorization = $user.token }
    try {
        # ① 下单（会产生一笔 PENDING_PAYMENT 订单 + 锁定的座位）
        $purchase = Invoke-Api POST "/api/ticket-service/ticket/purchase" @{
            trainId = "1"
            passengers = @(@{ passengerId = [string]$user.passengerId; seatType = 2 })
            chooseSeats = @()
            departure = "北京南"
            arrival = "宁波"
        } $headers
        $orderSn = [string]$purchase.data.orderSn
        if ([string]::IsNullOrWhiteSpace($orderSn)) { throw "下单响应没有 orderSn" }

        # ② 建支付单
        $pay = Invoke-Api POST "/api/pay-service/pay/create" @{
            orderSn = $orderSn; channel = "MOCK_PAY"; tradeType = "NATIVE"
        } $headers
        $paySn = [string]$pay.data.paySn
        if ([string]::IsNullOrWhiteSpace($paySn)) { throw "建支付单响应没有 paySn" }

        # ③ 只对这一枪计时：mock-cashier 内部就是"构造渠道回调 → 走真实回调逻辑"
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        $null = Invoke-Api GET "/api/pay-service/pay/mock-cashier?paySn=$paySn" $null @{}
        $watch.Stop()

        if ($i -ge $Warmup) { $elapsed.Add($watch.Elapsed.TotalMilliseconds) }
    } catch {
        if ($i -ge $Warmup) { $failures++ }
        Write-Warning "第 $($i + 1) 轮失败：$($_.Exception.Message)"
    }
}

if ($elapsed.Count -eq 0) {
    Write-Host "没有采到任何样本" -ForegroundColor Red
    exit 1
}

$sorted = @($elapsed | Sort-Object)
function Pct([double]$p) { $sorted[[Math]::Min($sorted.Count - 1, [Math]::Ceiling($sorted.Count * $p) - 1)] }

$result = [ordered]@{
    label       = $Label
    timestamp   = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss zzz")
    samples     = $elapsed.Count
    failures    = $failures
    averageMs   = [Math]::Round(($elapsed | Measure-Object -Average).Average, 3)
    minMs       = [Math]::Round($sorted[0], 3)
    p50Ms       = [Math]::Round((Pct 0.50), 3)
    p95Ms       = [Math]::Round((Pct 0.95), 3)
    p99Ms       = [Math]::Round((Pct 0.99), 3)
    maxMs       = [Math]::Round($sorted[-1], 3)
}
$result | ConvertTo-Json -Depth 4
