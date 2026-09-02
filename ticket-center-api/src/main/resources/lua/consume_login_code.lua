local current = redis.call('get', KEYS[1])
if current and current == ARGV[1] then
    return redis.call('del', KEYS[1])
end
return 0
