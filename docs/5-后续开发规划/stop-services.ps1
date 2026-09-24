[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$pidFile = Join-Path $PSScriptRoot "baseline\service-pids.json"

function Stop-ProcessTree {
    param([int]$ProcessId)

    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue)
    foreach ($child in $children) {
        Stop-ProcessTree -ProcessId $child.ProcessId
    }
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

if (-not (Test-Path $pidFile)) {
    Write-Host "没有找到服务 PID 文件，无需停止。"
    exit 0
}

# 注意：PS 5.1 的 ConvertFrom-Json 把 JSON 数组当作【单个】管道项输出，
# 所以外面再套一层 @() 会得到"只有 1 个元素、而它的属性才是数组"的嵌套结构
# （实测：@() 包裹 → count=1；直接赋值 → count=5）。
# 那样 $service.pid 就是 Object[] 而不是标量，传给 [int]$ProcessId 会直接抛
# "无法将 System.Object[] 转换为 System.Int32"。因此这里不能加 @()。
$services = Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json
foreach ($service in $services) {
    $process = Get-Process -Id $service.pid -ErrorAction SilentlyContinue
    if ($null -ne $process) {
        Stop-ProcessTree -ProcessId $service.pid
        Write-Host "已停止 $($service.name)（PID $($service.pid)）"
    }
}
Remove-Item -LiteralPath $pidFile -Force
