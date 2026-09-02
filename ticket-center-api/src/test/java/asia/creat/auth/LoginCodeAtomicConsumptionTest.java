package asia.creat.auth;

import asia.creat.common.exception.BusinessException;
import asia.creat.dto.UserLoginDTO;
import asia.creat.entity.User;
import asia.creat.service.UserInfoService;
import asia.creat.service.UserService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.RedisConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
class LoginCodeAtomicConsumptionTest extends IntegrationTestcontainers {

    private static final String PHONE = "13612340002";
    private static final String CODE = "123456";
    private static final int CONCURRENCY = 20;

    @Autowired
    private UserService userService;

    @Autowired
    private UserInfoService userInfoService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void sameCodeCanOnlyBeConsumedOnce() throws Exception {
        cleanUser();
        stringRedisTemplate.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + PHONE, CODE, RedisConstants.LOGIN_CODE_TTL);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENCY);
        AtomicInteger successes = new AtomicInteger();
        List<String> tokens = new CopyOnWriteArrayList<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        try {
            for (int i = 0; i < CONCURRENCY; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        tokens.add(userService.login(new UserLoginDTO(PHONE, CODE, null)));
                        successes.incrementAndGet();
                    } catch (BusinessException ignored) {
                    } catch (Throwable e) {
                        unexpected.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            Assertions.assertTrue(done.await(20, TimeUnit.SECONDS), "并发登录未按时结束");
            Assertions.assertTrue(unexpected.isEmpty(), "出现非业务异常: " + unexpected);
            Assertions.assertEquals(1, successes.get(), "同一个验证码只能登录成功一次");
            Assertions.assertNull(stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + PHONE));
        } finally {
            pool.shutdownNow();
            for (String token : tokens) {
                stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
            }
            stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + PHONE);
            cleanUser();
        }
    }

    private void cleanUser() {
        User user = userService.lambdaQuery().eq(User::getPhone, PHONE).one();
        if (user != null) {
            userInfoService.removeById(user.getId());
            userService.removeById(user.getId());
        }
    }
}
