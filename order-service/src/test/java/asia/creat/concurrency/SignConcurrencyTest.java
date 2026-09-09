package asia.creat.concurrency;

import asia.creat.dto.UserDTO;
import asia.creat.entity.CreditLog;
import asia.creat.entity.CreditAccount;
import asia.creat.service.CreditLogService;
import asia.creat.service.SignService;
import asia.creat.service.CreditAccountService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.RedisConstants;
import asia.creat.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest
public class SignConcurrencyTest extends IntegrationTestcontainers {

    private static final int CONCURRENCY = 20;

    @Autowired
    private SignService signService;

    @Autowired
    private CreditAccountService creditAccountService;

    @Autowired
    private CreditLogService creditLogService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @DisplayName("1. 同一用户并发签到：积分只加 10，不会发多份")
    void testConcurrentSign_shouldGrantCreditsOnce() throws Exception {
        Long userId = 88811L;
        LocalDateTime now = LocalDateTime.now();
        String dayKey = RedisConstants.USER_SIGN_KEY + userId + ":"
                + now.format(DateTimeFormatter.ofPattern("yyyyMM"));

        stringRedisTemplate.delete(dayKey);
        creditAccountService.removeById(userId);
        creditLogService.remove(new QueryWrapper<CreditLog>().eq("user_id", userId));
        creditAccountService.save(new CreditAccount().setUserId(userId).setCredits(0));

        try {
            int failures = runConcurrently(userId, () -> signService.sign());
            Assertions.assertEquals(0, failures,
                    CONCURRENCY + " 个并发签到请求中有 " + failures + " 个抛异常，结果不可信");

            int credits = creditAccountService.getById(userId).getCredits();
            Assertions.assertEquals(10, credits,
                    CONCURRENCY + " 个并发签到请求只应加一次 10 积分，实际加了 " + credits);
        } finally {
            stringRedisTemplate.delete(dayKey);
            creditAccountService.removeById(userId);
            creditLogService.remove(new QueryWrapper<CreditLog>().eq("user_id", userId));
        }
    }

    @Test
    @DisplayName("2. 签到位图丢失后重试：修复位图但不重复发积分")
    void testSign_WhenBitmapMissing_ShouldRepairWithoutDuplicateCredits() {
        Long userId = 88813L;
        LocalDateTime now = LocalDateTime.now();
        String dayKey = RedisConstants.USER_SIGN_KEY + userId + ":"
                + now.format(DateTimeFormatter.ofPattern("yyyyMM"));

        stringRedisTemplate.delete(dayKey);
        creditAccountService.removeById(userId);
        creditLogService.remove(new QueryWrapper<CreditLog>().eq("user_id", userId));
        creditAccountService.save(new CreditAccount().setUserId(userId).setCredits(0));

        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
        try {
            signService.sign();
            stringRedisTemplate.delete(dayKey);

            signService.sign();

            Assertions.assertEquals(10, creditAccountService.getById(userId).getCredits());
            Assertions.assertEquals(1, creditLogService.lambdaQuery()
                    .eq(CreditLog::getUserId, userId)
                    .eq(CreditLog::getBizType, 1)
                    .eq(CreditLog::getBizId, now.toLocalDate().toString())
                    .count());
            Assertions.assertTrue(Boolean.TRUE.equals(
                    stringRedisTemplate.opsForValue().getBit(dayKey, now.getDayOfMonth() - 1)));
        } finally {
            UserHolder.removeUser();
            stringRedisTemplate.delete(dayKey);
            creditAccountService.removeById(userId);
            creditLogService.remove(new QueryWrapper<CreditLog>().eq("user_id", userId));
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
