---
--- 票档预约脚本
---

-- 返回 0 表示预约成功，1 表示库存不足，2 表示重复预约，3 表示库存键不存在。
local stockKey = KEYS[1]
local orderKey = KEYS[2]
local reservationKey = KEYS[3]
local userId = ARGV[1]
local reservationId = ARGV[2]

local stock = redis.call('get', stockKey)
if not stock then
    return 3
end

-- 新预约按预约号记录，旧调用仍按用户 Set 兼容。
if reservationKey then
    local owner = redis.call('hget', reservationKey, userId)
    if owner then
        return owner == reservationId and 0 or 2
    end
end
if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end
if tonumber(stock) <= 0 then
    return 1
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
if reservationKey and reservationId then
    redis.call('hset', reservationKey, userId, reservationId)
end
return 0
