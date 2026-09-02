-- 票档预约回滚脚本（原子：删除预约记录 → 回补库存）
-- @return 1 回滚成功
-- @return 0 预约记录不存在，无需回滚

-- key 列表
-- @param KEYS[1] 库存键 tc:ticket:{id}:stock
-- @param KEYS[2] 一人一票键 tc:ticket:{id}:order

-- 参数列表
-- @param ARGV[1] 用户 id

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local userId = ARGV[1]

-- 删除预约记录成功后才回补库存，防止重复补偿
local removed = redis.call('srem', orderKey, userId)
if removed == 1 then
    redis.call('incrby', stockKey, 1)
end
return removed
