package asia.creat.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    // 2026-01-01T00:00:00Z
    public static final long BEGIN_TIMESTAMP = 1767225600L;
    public static final int COUNT_BITS = 32;
    private static final DateTimeFormatter DATE_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private StringRedisTemplate stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix){
        // 同一时刻生成时间戳和日期，避免跨日不一致。
        ZonedDateTime now = ZonedDateTime.now();

        long timestamp = now.toEpochSecond() - BEGIN_TIMESTAMP;

        String date = now.format(DATE_KEY_FORMATTER);
        long count = stringRedisTemplate.opsForValue().increment(RedisConstants.ID_ICR_KEY + keyPrefix + ":" + date);

        // 时间戳和当天序号组合成唯一 ID。
        return timestamp << COUNT_BITS | count;
    }
}
