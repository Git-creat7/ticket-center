-- 票档预约 Lua 脚本（原子：查库存 → 查一人一票 → 扣库存 → 返回结果）
-- @return  0 预约成功
-- @return  1 库存不足
-- @return  2 重复预约
-- @return  3 库存键不存在（缓存未预热或被误删）

-- key 列表
-- @param KEYS[1] 库存键 tc:ticket:{id}:stock
-- @param KEYS[2] 一人一票键 tc:ticket:{id}:order

-- 参数列表
-- @param ARGV[1] 用户 id

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local userId = ARGV[1]

-- 键不存在与库存为 0 是两回事：前者是缓存未预热，此时放行会超卖，
-- 但对外报"库存不足"会掩盖故障，交给调用方按系统异常处理。
local stock = redis.call('get', stockKey)
if not stock then
    return 3
end
if tonumber(stock) <= 0 then
    return 1
end

-- 判断用户是否已经预约
if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

-- 扣减库存并记录用户，整个脚本由 Redis 原子执行
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0
