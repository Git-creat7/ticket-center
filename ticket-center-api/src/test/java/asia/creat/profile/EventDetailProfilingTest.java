package asia.creat.profile;

import asia.creat.entity.Event;
import asia.creat.entity.EventCategory;
import asia.creat.entity.Ticket;
import asia.creat.mapper.EventCategoryMapper;
import asia.creat.mapper.TicketMapper;
import asia.creat.service.EventService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.CacheClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static asia.creat.utils.RedisConstants.CACHE_EVENT_KEY;
import static asia.creat.utils.RedisConstants.CACHE_EVENT_TTL;
import static asia.creat.utils.RedisConstants.UV_EVENT_KEY;

@SpringBootTest
public class EventDetailProfilingTest extends IntegrationTestcontainers {

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private EventCategoryMapper eventCategoryMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private EventService eventService;

    @Test
    @DisplayName("Step 0 耗时拆解测量：200 并发下详情页 4 步耗时实测采样")
    void profileEventDetailSteps() throws InterruptedException {
        Long eventId = 1L;
        // 预热 Redis Event 缓存
        cacheClient.queryWithPassThrough(CACHE_EVENT_KEY, eventId, Event.class, eventService::getById, CACHE_EVENT_TTL);

        int totalRequests = 2000;
        int concurrency = 50; // 并发线程
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        AtomicLong sumT1RedisEvent = new AtomicLong(0);
        AtomicLong sumT2RedisUv = new AtomicLong(0);
        AtomicLong sumT3MysqlCategory = new AtomicLong(0);
        AtomicLong sumT4MysqlTickets = new AtomicLong(0);
        AtomicLong sumTotal = new AtomicLong(0);

        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(totalRequests));

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    long start = System.nanoTime();

                    // Step 1: Redis 查询 Event
                    long s1 = System.nanoTime();
                    Event event = cacheClient.queryWithPassThrough(
                            CACHE_EVENT_KEY, eventId, Event.class, eventService::getById, CACHE_EVENT_TTL
                    );
                    long t1 = System.nanoTime() - s1;

                    // Step 2: Redis PFCOUNT 查 UV
                    long s2 = System.nanoTime();
                    Long uv = stringRedisTemplate.opsForHyperLogLog().size(UV_EVENT_KEY + eventId);
                    long t2 = System.nanoTime() - s2;

                    // Step 3: MySQL 查分类
                    long s3 = System.nanoTime();
                    EventCategory category = (event != null && event.getCategoryId() != null)
                            ? eventCategoryMapper.selectById(event.getCategoryId()) : null;
                    long t3 = System.nanoTime() - s3;

                    // Step 4: MySQL 查票档列表
                    long s4 = System.nanoTime();
                    List<Ticket> tickets = ticketMapper.queryTicketOfEvent(eventId);
                    long t4 = System.nanoTime() - s4;

                    long total = System.nanoTime() - start;

                    sumT1RedisEvent.addAndGet(t1);
                    sumT2RedisUv.addAndGet(t2);
                    sumT3MysqlCategory.addAndGet(t3);
                    sumT4MysqlTickets.addAndGet(t4);
                    sumTotal.addAndGet(total);

                    latencies.add(total / 1_000_000); // ms
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        Collections.sort(latencies);
        long p50 = latencies.get((int) (totalRequests * 0.50));
        long p95 = latencies.get((int) (totalRequests * 0.95));
        long p99 = latencies.get((int) (totalRequests * 0.99));
        long max = latencies.get(totalRequests - 1);

        double avgT1 = (sumT1RedisEvent.get() / (double) totalRequests) / 1_000_000.0;
        double avgT2 = (sumT2RedisUv.get() / (double) totalRequests) / 1_000_000.0;
        double avgT3 = (sumT3MysqlCategory.get() / (double) totalRequests) / 1_000_000.0;
        double avgT4 = (sumT4MysqlTickets.get() / (double) totalRequests) / 1_000_000.0;
        double avgTotal = (sumTotal.get() / (double) totalRequests) / 1_000_000.0;

        System.out.println("===============================================================");
        System.out.println(">>> [Step 0 实测证据] 详情页 4 步耗时拆解与占比测量结果 (样本=" + totalRequests + ")");
        System.out.println("===============================================================");
        System.out.printf("1. Redis Event 缓存读:        %.2f ms (%.1f%%)%n", avgT1, (avgT1 / avgTotal) * 100);
        System.out.printf("2. Redis HyperLogLog UV 计算: %.2f ms (%.1f%%)%n", avgT2, (avgT2 / avgTotal) * 100);
        System.out.printf("3. MySQL 分类名称查询:        %.2f ms (%.1f%%)%n", avgT3, (avgT3 / avgTotal) * 100);
        System.out.printf("4. MySQL 票档列表查询:        %.2f ms (%.1f%%)%n", avgT4, (avgT4 / avgTotal) * 100);
        System.out.println("---------------------------------------------------------------");
        System.out.printf("总平均耗时: %.2f ms | P50: %d ms | P95: %d ms | P99: %d ms | Max: %d ms%n",
                avgTotal, p50, p95, p99, max);
        System.out.printf("MySQL 查询耗时占比: %.1f%% (2次DB查询合计 %.2f ms)%n",
                ((avgT3 + avgT4) / avgTotal) * 100, (avgT3 + avgT4));
        System.out.printf("Redis 查询耗时占比: %.1f%% (2次Redis查询合计 %.2f ms)%n",
                ((avgT1 + avgT2) / avgTotal) * 100, (avgT1 + avgT2));
        System.out.println("===============================================================");
    }
}
