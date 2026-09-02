package asia.creat.utils;

import asia.creat.support.IntegrationTestcontainers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 验证 ID 里的时间戳段能被正确反解回当前时刻。
 *
 * 修复前用 {@code LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)}：
 * 取本地墙上时钟却按 UTC 解读，东八区下时间戳比真实值大 28800 秒，
 * 反解出来的时刻比下单时刻晚 8 小时。偏差是常量，所以唯一性与单调性都不受影响，
 * 唯一后果就是这里断言的这件事——按 ID 对账会对到错的时间。
 */
@SpringBootTest
public class RedisIdWorkerTest extends IntegrationTestcontainers {

    /** 反解允许的误差：覆盖测试自身的执行耗时 */
    private static final long TOLERANCE_SECONDS = 120L;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Test
    @DisplayName("1. ID 高位反解出的时刻应等于当前时刻，不能偏 8 小时")
    void testTimestampDecodesToNow() {
        long before = Instant.now().getEpochSecond();
        long id = redisIdWorker.nextId("test");
        long after = Instant.now().getEpochSecond();

        long decoded = (id >>> RedisIdWorker.COUNT_BITS) + RedisIdWorker.BEGIN_TIMESTAMP;

        Assertions.assertTrue(decoded >= before - TOLERANCE_SECONDS && decoded <= after + TOLERANCE_SECONDS,
                "反解时刻 " + Instant.ofEpochSecond(decoded) + " 落在 ["
                        + Instant.ofEpochSecond(before) + ", " + Instant.ofEpochSecond(after) + "] 之外，"
                        + "偏差 " + (decoded - before) + " 秒");
    }

    @Test
    @DisplayName("2. 基准时间戳就是 2026-01-01T00:00:00Z")
    void testBeginTimestampIsUtcMidnight() {
        // 反解依赖这个前提：BEGIN_TIMESTAMP 若按本地零点定义，
        // nextId 用真 epoch 秒就会又差回 8 小时
        Assertions.assertEquals(
                ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC")).toEpochSecond(),
                RedisIdWorker.BEGIN_TIMESTAMP);
    }

    @Test
    @DisplayName("3. 计数段按本地日期分桶，同一天内递增")
    void testCounterIsPerLocalDateAndIncrements() {
        // 日期键跟本地日历走：这是"今天的第几单"，业务口径不该用 UTC 日期
        long first = redisIdWorker.nextId("test");
        long second = redisIdWorker.nextId("test");

        long mask = (1L << RedisIdWorker.COUNT_BITS) - 1;
        Assertions.assertEquals((first & mask) + 1, second & mask, "同一天内计数段应连续递增");
        Assertions.assertTrue(second > first, "ID 整体应单调递增");
    }

    @Test
    @DisplayName("4. 跨零点时时间戳与计数键取自同一次时钟读取")
    void testClockReadOnce() {
        // nextId 内部只读一次时钟。分两次读会在零点前后拿到不同日期：
        // 时间戳落在新的一天，计数键还在旧的一天（或反之）。
        // 这里只能验证同一次调用里两段自洽——时间戳解出的本地日期与计数键日期一致。
        long id = redisIdWorker.nextId("test");
        long decoded = (id >>> RedisIdWorker.COUNT_BITS) + RedisIdWorker.BEGIN_TIMESTAMP;
        LocalDate decodedLocalDate = Instant.ofEpochSecond(decoded).atZone(ZoneId.systemDefault()).toLocalDate();

        Assertions.assertEquals(LocalDate.now(), decodedLocalDate,
                "时间戳解出的本地日期应与计数键所用日期一致");
    }
}
