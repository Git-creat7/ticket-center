---
--- 票档库存恢复脚本
---

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local reservationKey = KEYS[3]
local missing = redis.call('exists', stockKey) == 0

-- 库存丢失时一起重建资格，处理中预约由恢复任务重新预扣。
if missing then
    redis.call('del', orderKey, reservationKey)
end
for i = 2, #ARGV, 2 do
    redis.call('sadd', orderKey, ARGV[i])
    if ARGV[i + 1] ~= '' then
        redis.call('hsetnx', reservationKey, ARGV[i], ARGV[i + 1])
    end
end
if missing and ARGV[1] ~= '' then
    redis.call('set', stockKey, ARGV[1])
end
return missing and 1 or 0
