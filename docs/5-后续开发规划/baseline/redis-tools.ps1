function Open-RedisConnection {
    param(
        [string]$HostName = "192.168.204.128",
        [int]$Port = 6379,
        [string]$Password = "274226"
    )
    $client = [System.Net.Sockets.TcpClient]::new($HostName, $Port)
    $stream = $client.GetStream()
    $connection = [PSCustomObject]@{ Client = $client; Stream = $stream }
    if ($Password) {
        $auth = Send-RedisCommand $connection @("AUTH", $Password)
        if ($auth -ne "OK") { throw "Redis AUTH 失败：$auth" }
    }
    return $connection
}

function Close-RedisConnection {
    param($Connection)
    if ($null -ne $Connection) {
        $Connection.Stream.Dispose()
        $Connection.Client.Dispose()
    }
}

function Send-RedisCommand {
    param($Connection, [string[]]$Arguments)
    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.Append("*$($Arguments.Count)`r`n")
    foreach ($argument in $Arguments) {
        $length = [System.Text.Encoding]::UTF8.GetByteCount($argument)
        [void]$builder.Append("`$$length`r`n$argument`r`n")
    }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($builder.ToString())
    $Connection.Stream.Write($bytes, 0, $bytes.Length)
    $Connection.Stream.Flush()
    $response = Read-RedisResponse $Connection.Stream
    if ($response -is [System.Array]) {
        foreach ($item in $response) { Write-Output -NoEnumerate $item }
        return
    }
    return $response
}

function Read-RedisLine {
    param([System.Net.Sockets.NetworkStream]$Stream)
    $bytes = [System.Collections.Generic.List[byte]]::new()
    while ($true) {
        $value = $Stream.ReadByte()
        if ($value -lt 0) { throw "Redis 连接提前关闭" }
        if ($value -eq 13) {
            if ($Stream.ReadByte() -ne 10) { throw "Redis 响应缺少换行符" }
            break
        }
        $bytes.Add([byte]$value)
    }
    return [System.Text.Encoding]::UTF8.GetString($bytes.ToArray())
}

function Read-RedisResponse {
    param([System.Net.Sockets.NetworkStream]$Stream)
    $prefixValue = $Stream.ReadByte()
    if ($prefixValue -lt 0) { throw "Redis 连接提前关闭" }
    $prefix = [char]$prefixValue
    $line = Read-RedisLine $Stream
    switch ($prefix) {
        "+" { return $line }
        "-" { throw "Redis 错误：$line" }
        ":" { return [long]$line }
        '$' {
            $length = [int]$line
            if ($length -lt 0) { return $null }
            $bytes = New-Object byte[] $length
            $offset = 0
            while ($offset -lt $length) {
                $read = $Stream.Read($bytes, $offset, $length - $offset)
                if ($read -le 0) { throw "Redis Bulk String 提前结束" }
                $offset += $read
            }
            if ($Stream.ReadByte() -ne 13 -or $Stream.ReadByte() -ne 10) {
                throw "Redis Bulk String 缺少结束符"
            }
            return [System.Text.Encoding]::UTF8.GetString($bytes)
        }
        "*" {
            $count = [int]$line
            if ($count -lt 0) { return $null }
            $items = [System.Collections.Generic.List[object]]::new()
            for ($i = 0; $i -lt $count; $i++) {
                $items.Add((Read-RedisResponse $Stream))
            }
            return ,$items.ToArray()
        }
        default { throw "无法识别的 Redis RESP 前缀：$prefix" }
    }
}

function Get-RedisStats {
    param($Connection)
    $info = Send-RedisCommand $Connection @("INFO", "stats")
    $stats = @{}
    foreach ($line in ($info -split "`r?`n")) {
        if ($line -match "^(keyspace_hits|keyspace_misses):([0-9]+)$") {
            $stats[$matches[1]] = [long]$matches[2]
        }
    }
    return $stats
}
