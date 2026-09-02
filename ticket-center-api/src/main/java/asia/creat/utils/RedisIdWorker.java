package asia.creat.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    //2026-01-01T00:00:00Z
    public static final long BEGIN_TIMESTAMP = 1767225600L;
    public static final int COUNT_BITS = 32;
    private static final DateTimeFormatter DATE_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private StringRedisTemplate stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix){
        // 只读一次时钟：分两次读会在跨零点的瞬间拿到不同的日期，
        // 时间戳落在新的一天而计数键还在旧的一天。
        ZonedDateTime now = ZonedDateTime.now();

        // 用 ZonedDateTime.toEpochSecond()，它按自身偏移换算。
        // 原来是 LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)——取的是本地墙上时钟，
        // 却当成 UTC 读，东八区下算出的 epoch 秒比真实值大 28800，把 ID 反解回时间会差 8 小时。
        long timestamp = now.toEpochSecond() - BEGIN_TIMESTAMP;

        // 计数键按本地日期分桶：这是"今天的第几单"，业务口径就该跟着本地日历走
        String date = now.format(DATE_KEY_FORMATTER);
        long count = stringRedisTemplate.opsForValue().increment(RedisConstants.ID_ICR_KEY + keyPrefix + ":" + date);

        //左移位数并留出计数的位置
        return timestamp << COUNT_BITS | count;
    }
}
