package asia.creat.concurrency;

import asia.creat.dto.UserDTO;
import asia.creat.entity.EventReview;
import asia.creat.service.EventReviewService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.RedisConstants;
import asia.creat.utils.UserHolder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest
public class LikeConcurrencyTest extends IntegrationTestcontainers {

    private static final int CONCURRENCY = 20;

    /** 点赞用例的 liked 基线，留出余量避免并发交错把无符号列减到 0 以下 */
    private static final int BASE_LIKED = 10;

    @Autowired
    private EventReviewService eventReviewService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @DisplayName("3. 同一用户并发点赞：liked 只 +1，不会重复计数")
    void testConcurrentLike_shouldCountOnce() throws Exception {
        Long userId = 88812L;

        // 自建评价并设置正数基线，避免测试污染业务数据或触发无符号下溢。
        EventReview review = new EventReview()
                .setEventId(1L)
                .setUserId(userId)
                .setTitle("并发点赞用例专用")
                .setContent("由 LikeConcurrencyTest 创建，用例结束即删除")
                .setLiked(BASE_LIKED);
        Assertions.assertTrue(eventReviewService.save(review), "测试评价插入失败");

        Long reviewId = review.getId();
        String likedKey = RedisConstants.REVIEW_LIKED_KEY + reviewId;

        try {
            int failures = runConcurrently(userId, () -> eventReviewService.likeReview(reviewId));
            Assertions.assertEquals(0, failures,
                    CONCURRENCY + " 个并发点赞请求中有 " + failures + " 个抛异常，结果不可信");

            int liked = eventReviewService.getById(reviewId).getLiked();

            // 并发点赞最终只允许增加 0 或 1，不能重复计数。
            int delta = liked - BASE_LIKED;
            Assertions.assertTrue(delta == 0 || delta == 1,
                    CONCURRENCY + " 个并发点赞请求后 liked 相对基线变化应为 0 或 1，实际为 " + delta);

            // Redis 点赞集合与数据库计数必须保持一致。
            boolean inZSet = stringRedisTemplate.opsForZSet().score(likedKey, userId.toString()) != null;
            Assertions.assertEquals(inZSet ? 1 : 0, delta,
                    "点赞集合中" + (inZSet ? "已有" : "没有") + "该用户，liked 变化却是 " + delta);
        } finally {
            stringRedisTemplate.delete(likedKey);
            eventReviewService.removeById(reviewId);
        }
    }

    // 以同一用户身份并发执行操作。
    private int runConcurrently(Long userId, Runnable action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(CONCURRENCY);
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        for (int i = 0; i < CONCURRENCY; i++) {
            pool.submit(() -> {
                UserDTO user = new UserDTO();
                user.setId(userId);
                UserHolder.saveUser(user);
                try {
                    startGate.await();
                    action.run();
                } catch (Throwable e) {
                    failures.incrementAndGet();
                    firstError.compareAndSet(null, e);
                } finally {
                    UserHolder.removeUser();
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
            // 打出首个异常，否则只看到一个计数无从排查
            System.out.println("[并发用例] 首个异常：");
            e.printStackTrace(System.out);
        }
        return failures.get();
    }
}
