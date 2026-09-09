---
--- 票档预约回滚脚本
---

-- 返回 1 表示回滚成功，0 表示预约不存在或预约号不匹配，3 表示库存键不存在。
local stockKey = KEYS[1]
local orderKey = KEYS[2]
local reservationKey = KEYS[3]
local userId = ARGV[1]
local reservationId = ARGV[2]

local owner = reservationKey and redis.call('hget', reservationKey, userId)
if reservationId then
    if owner ~= reservationId then
        return 0
    end
elseif owner or redis.call('sismember', orderKey, userId) == 0 then
    -- 旧消息不能释放带预约号的记录。
    return 0
end
if redis.call('exists', stockKey) == 0 then
    return 3
end

redis.call('srem', orderKey, userId)
if reservationKey and reservationId then
    redis.call('hdel', reservationKey, userId)
end
redis.call('incrby', stockKey, 1)
return 1
