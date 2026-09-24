-- KEYS[1]：区间令牌桶 Hash Key
-- ARGV：seatType1, count1, seatType2, count2, ...
-- 桶过期后禁止 HINCRBY 凭空创建残缺 Hash，下一次购票会从 MySQL 完整重装。

if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end

local pairCount = #ARGV / 2
for i = 1, pairCount do
    local field = ARGV[(i - 1) * 2 + 1]
    local count = tonumber(ARGV[(i - 1) * 2 + 2])
    redis.call('HINCRBY', KEYS[1], field, count)
end

return 1
