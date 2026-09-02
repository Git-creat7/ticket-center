package asia.creat.auth;

import asia.creat.common.exception.BusinessException;
import asia.creat.mapper.UserInfoMapper;
import asia.creat.service.UserService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证码限流（第 8 条）与 GET /user/info 不写库（第 9 条）。
 */
@SpringBootTest
@AutoConfigureMockMvc
public class SendCodeRateLimitTest extends IntegrationTestcontainers {

    private static final String PHONE = "13612340001";

    @Autowired
    private UserService userService;

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MockMvc mockMvc;

    private String createdTokenKey;

    @AfterEach
    void cleanUp() {
        stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + PHONE);
        stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_COOLDOWN_KEY + PHONE);
        stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_QUOTA_KEY + PHONE);
        if (createdTokenKey != null) {
            stringRedisTemplate.delete(createdTokenKey);
            createdTokenKey = null;
        }
    }

    /**
     * 只写登录态 Hash，不建 tb_user 行。
     * /user/info/{id} 没有管理员校验，拦截器链不会回查用户表，够用了。
     */
    private String loginToken(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        createdTokenKey = RedisConstants.LOGIN_USER_KEY + token;
        Map<String, String> userMap = new HashMap<>();
        userMap.put("id", String.valueOf(userId));
        userMap.put("nickName", "info-test");
        userMap.put("icon", "");
        stringRedisTemplate.opsForHash().putAll(createdTokenKey, userMap);
        stringRedisTemplate.expire(createdTokenKey, RedisConstants.LOGIN_USER_TTL);
        return token;
    }

    @Test
    @DisplayName("60 秒内重复发送验证码应被拒绝")
    void testSendCode_WhenWithinCooldown_ShouldReject() {
        userService.sendCode(PHONE);
        String firstCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + PHONE);
        Assertions.assertNotNull(firstCode, "首次发送应写入验证码");

        Assertions.assertThrows(BusinessException.class, () -> userService.sendCode(PHONE));

        // 被拒的请求不能覆盖已发出的验证码，否则用户手里那条就失效了
        Assertions.assertEquals(firstCode,
                stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + PHONE),
                "限流拒绝后验证码不应被改写");
    }

    @Test
    @DisplayName("超过单日上限后即使冷却已过也应被拒绝")
    void testSendCode_WhenDailyLimitExceeded_ShouldReject() {
        // 直接把当日计数顶到上限，避免真等 60 秒 × 10 轮
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_QUOTA_KEY + PHONE,
                String.valueOf(RedisConstants.LOGIN_CODE_DAILY_LIMIT), RedisConstants.LOGIN_CODE_QUOTA_TTL);

        Assertions.assertThrows(BusinessException.class, () -> userService.sendCode(PHONE));
        Assertions.assertNull(stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + PHONE),
                "超限时不应生成验证码");
    }

    @Test
    @DisplayName("单日计数只在首次发送时设置过期，不应每次续期")
    void testSendCode_QuotaWindow_ShouldNotSlide() {
        String quotaKey = RedisConstants.LOGIN_CODE_QUOTA_KEY + PHONE;
        // 模拟当日已发过一次、窗口只剩 100 秒
        stringRedisTemplate.opsForValue().set(quotaKey, "1", java.time.Duration.ofSeconds(100));

        userService.sendCode(PHONE);

        Long ttl = stringRedisTemplate.getExpire(quotaKey);
        Assertions.assertNotNull(ttl);
        Assertions.assertTrue(ttl <= 100,
                "第二次发送不应把窗口续到 24 小时，否则单日上限永远达不到，实际 TTL=" + ttl);
    }

    /**
     * 修复前这个 GET 会为任意 userId 建一行 tb_user_info：
     * PersonPage 用 URL 上的 id 直接调它，遍历一遍就能把表刷满。
     */
    @Test
    @DisplayName("GET /user/info 查询不存在的用户不应落库")
    void testQueryUserInfo_WhenMissing_ShouldNotPersist() throws Exception {
        long ghostUserId = 999_999_991L;
        Assertions.assertNull(userInfoMapper.selectById(ghostUserId), "前置条件：该用户详情不存在");

        try {
            mockMvc.perform(get("/user/info/" + ghostUserId)
                            .header("authorization", loginToken(ghostUserId)))
                    .andExpect(status().isOk())
                    // 响应体保持原样：缺行时返回全 0，前端无法与"有行但没数据"区分
                    .andExpect(jsonPath("$.data.credits").value(0))
                    .andExpect(jsonPath("$.data.fans").value(0));

            Assertions.assertNull(userInfoMapper.selectById(ghostUserId),
                    "读接口不应为任意 userId 创建 tb_user_info 行");
        } finally {
            userInfoMapper.deleteById(ghostUserId);
        }
    }
}
