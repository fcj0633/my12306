-- KEYS[1]：区间令牌桶 Hash Key
-- ARGV：seatType1, count1, seatType2, count2, ...
-- 返回值：1=全部扣减成功，0=任一席别不足，-1=任一 field 尚未初始化。

local pairCount = #ARGV / 2

-- 先完整校验再统一扣减，保证多席别订单不会只扣成功其中一部分。
for i = 1, pairCount do
    local field = ARGV[(i - 1) * 2 + 1]
    local want = tonumber(ARGV[(i - 1) * 2 + 2])
    local have = redis.call('HGET', KEYS[1], field)
    if have == false then
        return -1
    end
    if tonumber(have) < want then
        return 0
    end
end

for i = 1, pairCount do
    local field = ARGV[(i - 1) * 2 + 1]
    local want = tonumber(ARGV[(i - 1) * 2 + 2])
    redis.call('HINCRBY', KEYS[1], field, -want)
end

return 1
