package asia.creat.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Function;

import static asia.creat.utils.RedisConstants.CACHE_NULL_TTL;

@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Duration ttl) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), ttl);
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Duration ttl) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        R result = dbFallback.apply(id);
        if (result == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL);
            return null;
        }
        set(key, result, ttl);
        return result;
    }

}
