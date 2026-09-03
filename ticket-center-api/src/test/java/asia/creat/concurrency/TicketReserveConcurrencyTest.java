package asia.creat.concurrency;

import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.RedisConstants;
import asia.creat.utils.TicketReservationScript;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 抢票预扣的并发正确性：Lua 脚本在真实竞争下不超卖、不少卖、一人一票。
 *
 * 只压 Redis 预扣这一层，不经过 MQ 落库 —— 预扣是唯一需要靠原子性防资损的环节，
 * 落库侧的幂等与补偿由 TicketOrderConsistencyTest 单线程覆盖。
 */
@SpringBootTest
public class TicketReserveConcurrencyTest extends IntegrationTestcontainers {

    private static final int RESERVE_SUCCESS = 0;
    private static final int RESERVE_SOLD_OUT = 1;
    private static final int RESERVE_DUPLICATE = 2;

    @Autowired
    private TicketReservationScript ticketReservationScript;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @DisplayName("1. 200 人抢 10 张：成功恰好 10 笔，库存归零不为负")
    void testConcurrentReserve_shouldNotOversell() throws Exception {
        long ticketId = 99910L;
        int stock = 10;
        int concurrency = 200;

        String stockKey = RedisConstants.ticketStockKey(ticketId);
        String orderKey = RedisConstants.ticketOrderKey(ticketId);
        stringRedisTemplate.delete(List.of(stockKey, orderKey));
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(stock));

        try {
            // 每个线程一个独立 userId，把一人一票规则排除在外，只考验库存扣减。
            List<Long> codes = runConcurrently(concurrency, i -> ticketReservationScript.reserve(ticketId, 90000L + i));

            long success = codes.stream().filter(c -> c == RESERVE_SUCCESS).count();
            long soldOut = codes.stream().filter(c -> c == RESERVE_SOLD_OUT).count();

            Assertions.assertEquals(stock, success,
                    concurrency + " 人抢 " + stock + " 张，成功数应恰好为 " + stock + "，实际 " + success
                            + "（多于库存即超卖，少于库存即少卖）");
            Assertions.assertEquals(concurrency - stock, soldOut,
                    "未抢到的请求都应返回库存不足，实际只有 " + soldOut + " 个");

            Assertions.assertEquals("0", stringRedisTemplate.opsForValue().get(stockKey),
                    "库存最终应精确归零，负值说明 Lua 的检查与扣减之间被并发穿透");
            Assertions.assertEquals(stock, stringRedisTemplate.opsForSet().size(orderKey),
                    "资格集合的成员数应与成功数一致");
        } finally {
            stringRedisTemplate.delete(List.of(stockKey, orderKey));
        }
    }

    @Test
    @DisplayName("2. 同一用户并发抢同一票档：只成功 1 笔，其余判重复")
    void testConcurrentReserve_sameUser_shouldReserveOnce() throws Exception {
        long ticketId = 99911L;
        long userId = 90500L;
        int concurrency = 50;

        String stockKey = RedisConstants.ticketStockKey(ticketId);
        String orderKey = RedisConstants.ticketOrderKey(ticketId);
        stringRedisTemplate.delete(List.of(stockKey, orderKey));

        // 库存足够多，这样唯一的约束来源就是一人一票。
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(concurrency));

        try {
            List<Long> codes = runConcurrently(concurrency, i -> ticketReservationScript.reserve(ticketId, userId));

            long success = codes.stream().filter(c -> c == RESERVE_SUCCESS).count();
            long duplicate = codes.stream().filter(c -> c == RESERVE_DUPLICATE).count();

            Assertions.assertEquals(1, success,
                    "同一用户 " + concurrency + " 个并发请求只应成功 1 笔，实际 " + success);
            Assertions.assertEquals(concurrency - 1, duplicate,
                    "其余请求都应被判为重复预约，实际只有 " + duplicate + " 个");

            Assertions.assertEquals(String.valueOf(concurrency - 1), stringRedisTemplate.opsForValue().get(stockKey),
                    "只有一笔成功，库存也只应扣 1");
        } finally {
            stringRedisTemplate.delete(List.of(stockKey, orderKey));
        }
    }

    // 同时放行 concurrency 个请求，收集各自的返回码。
    private List<Long> runConcurrently(int concurrency, java.util.function.LongFunction<Long> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(concurrency);
        ConcurrentLinkedQueue<Long> codes = new ConcurrentLinkedQueue<>();
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        for (int i = 0; i < concurrency; i++) {
            long seq = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    codes.add(action.apply(seq));
                } catch (Throwable e) {
                    firstError.compareAndSet(null, e);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = doneGate.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        Assertions.assertTrue(finished, "并发请求未在 30 秒内全部结束");

        Throwable e = firstError.get();
        if (e != null) {
            System.out.println("[并发用例] 首个异常：");
            e.printStackTrace(System.out);
        }
        Assertions.assertNull(e, "并发执行中出现异常，结果不可信");
        Assertions.assertEquals(concurrency, codes.size(), "返回码数量与请求数不符");
        return List.copyOf(codes);
    }
}
