[CmdletBinding()]
param(
    [string]$UserFile,
    [string]$BaseUrl = "http://127.0.0.1:9000",
    [string]$Password = "D1-Load-123456"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# 为什么需要它：
#   网关的认证【不解析 JWT】，而是拿 Authorization 当 key 去 Redis 查登录态
#   （gateway AuthGlobalFilter.java:90 → redisTemplate.opsForValue().get(authorization)）。
#   而登录态在 Redis 的 TTL 只有 30 分钟（user-services UserLoginServiceImpl.java:60
#   LOGIN_TOKEN_EXPIRE_MINUTES = 30L，写入见 :234-235）。JWT 本身是 24 小时，两者独立。
#   ⇒ 压测矩阵跑十几分钟没问题，但连续跑多个场景必然中途失效，表现为整轮 100% ERR_AUTH。
#   实测踩过：并发 20 的第 3 轮 35183 个请求全部 ERR_AUTH。
#
# 做法：按 CSV 里已有的 username 重新登录换新 token，passengerId 不变，原文件覆盖写回。
# 不重新注册、不查数据库、不碰乘车人 —— 只为把 Redis 登录态续上。

if (-not $UserFile) { $UserFile = Join-Path $PSScriptRoot "..\results\users.csv" }
$UserFile = [System.IO.Path]::GetFullPath($UserFile)
if (-not (Test-Path -LiteralPath $UserFile)) { throw "找不到用户文件：$UserFile" }

function Invoke-Api {
    param([string]$Method, [string]$Path, [object]$Body)
    $parameters = @{
        Method = $Method; Uri = "$BaseUrl$Path"; TimeoutSec = 30; UseBasicParsing = $true
        ContentType = "application/json; charset=utf-8"
        Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }
    $webResponse = Invoke-WebRequest @parameters
    $json = [System.Text.Encoding]::UTF8.GetString($webResponse.RawContentStream.ToArray())
    return $json | ConvertFrom-Json
}

$lines = @(Get-Content -LiteralPath $UserFile -Encoding UTF8)
if ($lines.Count -lt 2) { throw "用户文件为空：$UserFile" }

$output = [System.Collections.Generic.List[string]]::new()
$output.Add("username,token,passengerId")
$ok = 0
$failed = 0
$started = Get-Date
for ($i = 1; $i -lt $lines.Count; $i++) {
    $fields = $lines[$i].Split(',')
    if ($fields.Count -lt 3) { continue }
    $username = $fields[0]
    $passengerId = $fields[2]
    try {
        $login = Invoke-Api POST "/api/user-service/v1/login" @{ usernameOrMailOrPhone = $username; password = $Password }
        $token = [string]$login.data.accessToken
        if ([string]::IsNullOrWhiteSpace($token)) { throw "登录未返回 accessToken" }
        $output.Add("$username,$token,$passengerId")
        $ok++
    } catch {
        $failed++
    }
    if ($ok % 50 -eq 0 -and $ok -gt 0) {
        Write-Host ("    已刷新 {0}... 用时 {1:N0}s" -f $ok, ((Get-Date) - $started).TotalSeconds)
    }
}
[System.IO.File]::WriteAllLines($UserFile, $output, [System.Text.UTF8Encoding]::new($false))
$total = ((Get-Date) - $started).TotalSeconds
Write-Host ("    会话刷新完成：成功 {0}  失败 {1}  用时 {2:N0}s" -f $ok, $failed, $total)
if ($failed -gt 0) { Write-Warning "有 $failed 个用户刷新失败，它们所在的线程会得到 ERR_AUTH" }
